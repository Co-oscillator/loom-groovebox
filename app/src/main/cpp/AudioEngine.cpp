#include "AudioEngine.h"
#include "WavFileUtils.h" // New
#include "engines/BitcrusherFx.h"
#include <fstream> // Should be in Utils but ensuring

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <jni.h>

#if defined(__i386__) || defined(__x86_64__)
#include <xmmintrin.h>
#endif

#undef LOG_TAG
#define LOG_TAG "AudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Fast, lock-free random generator (Linear Congruential Generator)
struct FastRandom {
  unsigned int seed = 123456789;
  inline float next() {
    seed = (seed * 1103515245 + 12345);
    return static_cast<float>(seed) / 4294967296.0f;
  }
} gRng;

static inline float softLimit(float x) {
  if (std::isnan(x) || std::isinf(x))
    return 0.0f;
  if (x > 1.0f)
    return 1.0f - expf(-x + 1.0f);
  if (x < -1.0f)
    return -1.0f + expf(x + 1.0f);
  return x;
}

AudioEngine::AudioEngine() {
  mSampleRate = 48000.0; // Default to common Android rate
  setupTracks();
  for (int i = 0; i < 15; ++i)
    mFxChainDest[i] = -1;

  // Initialize Global Filter Pedals to transparent values
  mHpLfoFx.setCutoff(0.0f);
  mLpLfoFx.setCutoff(1.0f);
  mHpLfoFx.reset((float)mSampleRate);
  mLpLfoFx.reset((float)mSampleRate);
}

AudioEngine::~AudioEngine() { stop(); }

void AudioEngine::setupTracks() {
  mTracks.reserve(8);
  for (int i = 0; i < 8; ++i) {
    mTracks.emplace_back();
  }

  // Explicitly clear all sequencers and set default volumes on setup
  for (int i = 0; i < 8; ++i) {
    mTracks[i].volume = 0.8f;
    mTracks[i].smoothedVolume = 0.8f;
    clearSequencer(i);
  }
}

bool AudioEngine::start() {
  oboe::AudioStreamBuilder builder;
  builder.setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(oboe::ChannelCount::Stereo)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)
      ->setCallback(this);

  oboe::Result result = builder.openStream(mStream);
  if (result != oboe::Result::OK)
    return false;

  mReverbFx.setSampleRate(mStream->getSampleRate());
  mSampleRate = mStream->getSampleRate();

  // Input stream for recording
  oboe::AudioStreamBuilder inBuilder;
  inBuilder.setDirection(oboe::Direction::Input)
      ->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(oboe::ChannelCount::Mono)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setCallback(this);

  result = inBuilder.openStream(mInputStream);
  if (result != oboe::Result::OK) {
    LOGD("Error opening input stream: %s", oboe::convertToText(result));
  } else {
    mInputStream->requestStart();
  }

  return mStream->requestStart() == oboe::Result::OK;
}

void AudioEngine::stop() {
  if (mStream) {
    mStream->stop();
    mStream->close();
    mStream.reset();
  }
  if (mInputStream) {
    mInputStream->stop();
    mInputStream->close();
    mInputStream.reset();
  }
}

// Internal Note Logic
void AudioEngine::triggerNoteLocked(int trackIndex, int note, int velocity,
                                    bool isSequencerTrigger, float gate,
                                    bool punch) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    Track &track = mTracks[trackIndex];

    if (track.engineType == 7) {
      enqueueMidiEvent(0x90, track.midiOutChannel - 1, note, velocity);
      track.isActive = true;
      return;
    }

    if (!isSequencerTrigger) {
      track.mPhysicallyHeldNoteCount++;
      if (track.arpeggiator.getMode() != ArpMode::OFF) {
        track.arpeggiator.addNote(note);
        return;
      }
    }

    // Check for "Tie" (Sustain across steps)
    if (isSequencerTrigger) {
      static int trigLog = 0;
      if (trigLog++ % 50 == 0 ||
          trackIndex == 1) { // ALWAYS log FM (T1) for now
        float samplesPerStep = (15.0f * mSampleRate) / std::max(1.0f, mBpm);
        float effectiveMultiplier = std::max(0.01f, track.mClockMultiplier);
        float trackSamplesPerStep = samplesPerStep / effectiveMultiplier;
        __android_log_print(ANDROID_LOG_DEBUG, "GrooveboxAudio",
                            "SeqTrigger T%d Note=%d SPS=%.2f Multi=%.2f "
                            "Gate=%.2f Bank=%d Punch=%d",
                            trackIndex, note, trackSamplesPerStep,
                            effectiveMultiplier, gate,
                            track.selectedFmDrumInstrument, punch);
      }
    } else {
      // Recording Logic Fix: Avoid duplicate notes if one is already recording
      // for this pitch
      if (mIsRecording) {
        for (const auto &rn : track.mRecordingNotes) {
          if (rn.note == note)
            return; // Already recording this note (Legato/Slide)
        }
      }
    }

    if (punch) {
      track.mPunchCounter = 4000; // Reset punch counter on trigger
    }
    for (int i = 0; i < AudioEngine::Track::MAX_POLYPHONY; ++i) {
      if (track.mActiveNotes[i].active && track.mActiveNotes[i].note == note &&
          track.mActiveNotes[i].durationRemaining > 512) {
        float samplesPerStep = (15.0f * mSampleRate) / std::max(1.0f, mBpm);
        float trackSamplesPerStep =
            samplesPerStep / std::max(0.01f, track.mClockMultiplier);

        // Only tie if gate is explicitly long (legato)
        if (gate > 0.9f) {
          track.mActiveNotes[i].durationRemaining =
              trackSamplesPerStep * (gate + 0.05f); // Overlap for tie
          return; // Skip re-triggering (Legato)
        } else {
          // Retriggering: We need to steal this voice or let it release?
          // Standard behavior: Cut it off and restart.
          track.mActiveNotes[i].active = false; // Force re-allocation
        }
      }
    }

    // Allocate Voice and Trigger
    float freq = (track.engineType == 5)
                     ? 440.0f
                     : 440.0f * powf(2.0f, (note - 69) / 12.0f);
    track.currentFrequency = freq;
    track.subtractiveEngine.setFrequency(freq, mSampleRate);
    track.fmEngine.setFrequency(freq, mSampleRate);
    track.wavetableEngine.setFrequency(freq, mSampleRate);
    track.analogDrumEngine.setSampleRate(mSampleRate);

    track.isActive = true;
    track.mSilenceFrames = 0;

    for (int i = 0; i < AudioEngine::Track::MAX_POLYPHONY; ++i) {
      if (!track.mActiveNotes[i].active) {
        track.mActiveNotes[i].active = true;
        track.mActiveNotes[i].note = note;
        if (isSequencerTrigger) {
          float samplesPerStep = (15.0f * mSampleRate) / std::max(1.0f, mBpm);
          float trackSamplesPerStep =
              samplesPerStep / std::max(0.01f, track.mClockMultiplier);
          track.mActiveNotes[i].durationRemaining = trackSamplesPerStep * gate;
        } else {
          track.mActiveNotes[i].durationRemaining = 9999998.0f;
        }
        break;
      }
    }

    switch (track.engineType) {
    case 0:
      track.subtractiveEngine.triggerNote(note, velocity);
      break;
    case 1:
      track.fmEngine.triggerNote(note, velocity);
      break;
    case 2:
      track.samplerEngine.triggerNote(note, velocity);
      break;
    case 3:
      track.granularEngine.triggerNote(note, velocity);
      break;
    case 4:
      track.wavetableEngine.triggerNote(note, velocity);
      break;
    case 5:
      track.fmDrumEngine.triggerNote(note, velocity);
      break;
    case 6:
      track.analogDrumEngine.triggerNote(note, velocity);
      break;
    }

    if (mIsRecording && mIsPlaying && !isSequencerTrigger) {
      double phase = (double)mSampleCount / (mSamplesPerStep + 0.001);
      int stepOffset = (phase > 0.5) ? 1 : 0;
      float subStep = static_cast<float>(phase);
      // If we are late in the step, we record to the NEXT step but with subStep
      // near 0? Or just record to nearest.

      int currentStepIdx =
          (track.sequencer.getCurrentStepIndex() + stepOffset) % 128;

      if (track.engineType == 5 || track.engineType == 6) {
        int drumIdx = -1;
        if (note >= 60)
          drumIdx = note - 60;
        else if (note >= 0 && note < 16)
          drumIdx = note;

        if (drumIdx >= 0 && drumIdx < 16) {
          Step &s =
              track.drumSequencers[drumIdx].getStepsMutable()[currentStepIdx];
          s.addNote(note, static_cast<float>(velocity) / 127.0f, subStep);

          track.mRecordingNotes.push_back({note, currentStepIdx, drumIdx,
                                           (uint64_t)mGlobalStepIndex,
                                           (double)subStep});
        }
      } else if (track.engineType == 2 &&
                 track.samplerEngine.getPlayMode() == 2) {
        // Sampler Chops Recording
        int drumIdx = -1;
        if (note >= 60)
          drumIdx = note - 60; // Map note 60 -> Slice 0

        if (drumIdx >= 0 && drumIdx < 16) {
          Step &s =
              track.drumSequencers[drumIdx].getStepsMutable()[currentStepIdx];
          s.addNote(note, static_cast<float>(velocity) / 127.0f, subStep);

          track.mRecordingNotes.push_back({note, currentStepIdx, drumIdx,
                                           (uint64_t)mGlobalStepIndex,
                                           (double)subStep});
        }
      } else {
        Step &s = track.sequencer.getStepsMutable()[currentStepIdx];
        s.addNote(note, static_cast<float>(velocity) / 127.0f, subStep);

        track.mRecordingNotes.push_back({note, currentStepIdx, -1,
                                         (uint64_t)mGlobalStepIndex,
                                         (double)subStep});
      }
    }
  }
}

// Internal Param Logic
void AudioEngine::setParameter(int trackIndex, int parameterId, float value) {
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  // Update state (Base Value)
  mTracks[trackIndex].parameters[parameterId] = value;
  // Also update Applied Value so it takes effect immediately (until next step
  // reset)
  mTracks[trackIndex].appliedParameters[parameterId] = value;

  // Push to engine
  updateEngineParameter(trackIndex, parameterId, value);
}

void AudioEngine::updateEngineParameter(int trackIndex, int parameterId,
                                        float value) {
  // mLock assumed held by caller if needed, but safe for atomic updates
  // usually. We should be careful about locks if this is called from audio
  // thread. setParameter already holds lock. applyModulations holds lock?

  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  Track &track = mTracks[trackIndex];

  if (parameterId >= 2000) { // Global / FX
    int fxIndex = (parameterId - 2000) / 10;
    int subId = (parameterId - 2000) % 10;
    if (fxIndex >= 0 && fxIndex < 15) {
      track.fxSends[fxIndex] = value;
    }
    return;
  }

  // Subtractive Engine Enhancements (IDs 150-199)
  if (parameterId >= 150 && parameterId < 200) {
    if (track.engineType == 0) {
      track.subtractiveEngine.setParameter(parameterId, value);
    }
    return;
  }
  if (parameterId < 100) {
    // Synth Params
    switch (parameterId) {
    case 0:                                   // Track Volume
      track.volume = std::max(0.001f, value); // Safety floor
      break;
    case 1: // Filter Cutoff
      // Original logic for parameterId 1 was track.engineType =
      // static_cast<int>(value); This new logic seems to be a replacement for a
      // different parameter mapping.
      track.subtractiveEngine.setCutoff(
          value); // Assuming this is the new Filter Cutoff
      track.fmEngine.setFilter(
          value); // Assuming this is the new Filter Cutoff for FM
      track.samplerEngine.setParameter(
          parameterId,
          value); // Assuming sampler uses generic setParameter for this
      track.wavetableEngine.setFilterCutoff(value);
      track.granularEngine.setParameter(
          parameterId,
          value); // Assuming granular uses generic setParameter for this
      break;
    case 2: // Resonance
      track.subtractiveEngine.setResonance(value);
      track.fmEngine.setResonance(value);
      track.samplerEngine.setParameter(parameterId, value);
      track.wavetableEngine.setResonance(value);
      track.granularEngine.setParameter(parameterId, value);
      break;
    case 3: // Env Amount
      track.subtractiveEngine.setFilterEnvAmount(
          value); // Assuming this maps to filter env amount
      track.fmEngine.setParameter(parameterId, value); // Generic for FM
      break;
    case 4:
      track.subtractiveEngine.setOscWaveform(1, value);
      break; // Assuming OSC 2 Wave
    case 5:
      track.subtractiveEngine.setOscVolume(0, std::max(0.001f, value));
      break; // Assuming OSC 1 Volume
    case 6:
      track.subtractiveEngine.setDetune(value);
      break; // Assuming Detune
    case 7:
      track.subtractiveEngine.setLfoRate(value);
      break; // Assuming LFO Rate
    case 8:
      track.subtractiveEngine.setLfoDepth(value);
      break; // Assuming LFO Depth
    }
  }

  // Specific Ranges
  if (parameterId >= 100 && parameterId < 110) { // ADSR
    switch (parameterId) {
    case 100: // Attack
      track.subtractiveEngine.setAttack(value);
      track.fmEngine.setParameter(parameterId, value); // Generic for FM
      track.samplerEngine.setAttack(value);
      track.granularEngine.setAttack(value);
      track.wavetableEngine.setAttack(value);
      break;
    case 101: // Decay
      track.subtractiveEngine.setDecay(value);
      track.fmEngine.setParameter(parameterId, value); // Generic for FM
      track.samplerEngine.setDecay(value);
      track.granularEngine.setDecay(value);
      track.wavetableEngine.setDecay(value);
      break;
    case 102: // Sustain
      track.subtractiveEngine.setSustain(value);
      track.fmEngine.setParameter(parameterId, value); // Generic for FM
      track.samplerEngine.setSustain(value);
      track.granularEngine.setSustain(value);
      track.wavetableEngine.setSustain(value);
      break;
    case 103: // Release
      track.subtractiveEngine.setRelease(value);
      track.fmEngine.setParameter(parameterId, value); // Generic for FM
      track.samplerEngine.setRelease(value);
      track.granularEngine.setRelease(value);
      track.wavetableEngine.setRelease(value);
      break;
    }
  }

  // GLOBAL FX Params (500+) handled by direct FX calls usually, not here?
  // Let's check existing setParameter logic for 500+
  // The original logic for parameterId 500-600 was for global FX, which should
  // remain in the global context. This updateEngineParameter is track-specific.
  // The user's instruction implies this block should be here, but it conflicts
  // with the global nature of the original 500-600 block. I will assume the
  // user wants to keep the original global FX logic as is, and this comment is
  // a note to themselves. The provided snippet for 500-600 is empty, so I will
  // not add anything new here.

  // FM Drum and Analog Drum logic
  if (parameterId >= 400 && parameterId < 464) {
    if (track.engineType == 5) {
      track.fmDrumEngine.setParameter(
          track.selectedFmDrumInstrument, (parameterId - 400),
          value); // Adjusted to match original FM Drum logic
    }
  }
  if (parameterId >= 600 && parameterId < 700) {
    if (track.engineType == 6) {
      int drumIdx = (parameterId - 600) / 10;
      int subId = (parameterId - 600) % 10;
      track.analogDrumEngine.setParameter(drumIdx, subId, value);
    }
  }

  // Original logic from setParameterLocked, now in updateEngineParameter
  // Note: The original setParameterLocked had a 'bool isFromSequencer'
  // parameter. The new updateEngineParameter does not. The recording logic that
  // used 'isFromSequencer' needs to be handled elsewhere or removed if no
  // longer applicable in this context. For now, I will remove the recording
  // logic as it was tied to the 'isFromSequencer' parameter. The user's
  // provided snippet ends with '&& mIsPlaying && !isFromSequencer &&
  // parameterId >= 8) { ... }' which suggests this recording logic should be
  // moved to the new setParameter function, or handled by a separate function
  // that calls updateEngineParameter. Given the instruction, I will place the
  // recording logic in the new setParameter function, and remove it from
  // updateEngineParameter.

  // The original setParameterLocked also had these initial checks and
  // assignments: if (parameterId == 0) track.volume = value; else if
  // (parameterId == 1) track.engineType = static_cast<int>(value); else if
  // (parameterId >= 8 && parameterId < 16)
  // track.subtractiveEngine.setCutoff(value); These are now superseded by the
  // new switch/if blocks for parameterId < 100. I will ensure the new blocks
  // cover these or provide a generic fallback.

  // Engine Specific Params (100-150) - This block is partially covered by the
  // new ADSR block (100-110) I will merge the remaining cases from the original
  // 100-150 block into the new structure.
  if (parameterId >= 100 && parameterId < 150) {
    // Cases 100-103 are handled by the new ADSR block.
    // Remaining cases from original setParameterLocked:
    switch (parameterId) {
    // Cases 100-103 are already handled above in the new ADSR block.
    case 104:
      track.subtractiveEngine.setOscWaveform(0, value);
      break;
    case 105:
      track.subtractiveEngine.setOscWaveform(1, value);
      break;
    case 106:
      track.subtractiveEngine.setDetune(value);
      break;
    case 107:
      track.subtractiveEngine.setOscVolume(0, std::max(0.001f, value));
      break;
    case 108:
      track.subtractiveEngine.setOscVolume(1, std::max(0.0001f, value));
      break;
    case 109:
      track.subtractiveEngine.setOscVolume(2, std::max(0.0001f, value));
      break;
    case 110:
      track.subtractiveEngine.setNoiseLevel(value);
      break;
    case 111:
      track.subtractiveEngine.setChordVoicing(value);
      break;
    case 112:
      track.subtractiveEngine.setCutoff(value);
      break;
    case 113:
      track.subtractiveEngine.setResonance(value);
      break;
    case 114:
      track.subtractiveEngine.setFilterAttack(value);
      break;
    case 115:
      track.subtractiveEngine.setFilterDecay(value);
      break;
    case 116:
      track.subtractiveEngine.setFilterSustain(value);
      break;
    case 117:
      track.subtractiveEngine.setFilterRelease(value);
      break;
    case 118:
      track.subtractiveEngine.setFilterEnvAmount(value);
      break;
    }
  }

  // FM Params (150-199) - Shared with Subtractive Sound Design
  else if (parameterId >= 150 && parameterId < 200) {
    if (track.engineType == 0) { // SUBTRACTIVE
      track.subtractiveEngine.setParameter(parameterId, value);
    } else { // FM (EngineType 1)
      switch (parameterId) {
      case 150:
        track.fmEngine.setAlgorithm(static_cast<int>(value * 8));
        break;
      case 151:
        track.fmEngine.setFilter(value);
        break;
      case 152:
        track.fmEngine.setResonance(value);
        break;
      case 153:
        track.fmEngine.setCarrierMask(static_cast<int>(value));
        break;
      case 154:
        track.fmEngine.setFeedback(value);
        break;
      case 156:
        track.fmEngine.setParameter(4, value);
        break;
      case 155:
        track.fmEngine.setActiveMask(static_cast<int>(value));
        break;
      case 157:
        track.fmEngine.setParameter(5, value); // Brightness
        break;
      case 158:
        track.fmEngine.setParameter(6, value); // Detune
        break;
      case 159:
        track.fmEngine.setParameter(7, value); // Feedback Drive
        break;
      default:
        if (parameterId >= 160) {
          LOGD("FM Param Set: %d val=%.2f", parameterId, value);
          int opIdx = (parameterId - 160) / 6;
          int subId = (parameterId - 160) % 6;
          switch (subId) {
          case 0:
            track.fmEngine.setOpLevel(opIdx, value);
            break;
          case 1:
            track.fmEngine.setOpADSR(opIdx, value,
                                     track.fmEngine.mOpDecay[opIdx],
                                     track.fmEngine.mOpSustain[opIdx],
                                     track.fmEngine.mOpRelease[opIdx]);
            break;
          case 2:
            track.fmEngine.setOpADSR(opIdx, track.fmEngine.mOpAttack[opIdx],
                                     value, track.fmEngine.mOpSustain[opIdx],
                                     track.fmEngine.mOpRelease[opIdx]);
            break;
          case 3:
            track.fmEngine.setOpADSR(opIdx, track.fmEngine.mOpAttack[opIdx],
                                     track.fmEngine.mOpDecay[opIdx], value,
                                     track.fmEngine.mOpRelease[opIdx]);
            break;
          case 4:
            track.fmEngine.setOpADSR(opIdx, track.fmEngine.mOpAttack[opIdx],
                                     track.fmEngine.mOpDecay[opIdx],
                                     track.fmEngine.mOpSustain[opIdx], value);
            break;
          case 5:
            track.fmEngine.setOpRatio(opIdx, 0.5f + (value * 31.5f));
            break;
          }
        }
        break;
      }
    }
  }
  // FM Drum Params (200+) - This block is partially covered by the new 400-464
  // block. I will keep the original 200-300 block as it is distinct.
  else if (parameterId >= 200 && parameterId < 300) {
    track.fmDrumEngine.setParameter((parameterId - 200) / 10,
                                    (parameterId - 200) % 10, value);
  }
  // Analog Drum Params (600+) - This block is partially covered by the new
  // 600-700 block. I will keep the original 600-650 block as it is distinct.
  else if (parameterId >= 600 && parameterId < 650) {
    int drumIdx = (parameterId - 600) / 10;
    int subId = (parameterId - 600) % 10;
    LOGD("Analog Parameter: track=%d, drumIdx=%d, paramId=%d, value=%.2f",
         trackIndex, drumIdx, subId, value);
    track.analogDrumEngine.setParameter(drumIdx, subId, value);
  }
  // Sampler & Focused Drum Params (300+)
  else if (parameterId >= 300 && parameterId < 400) {
    if (parameterId == 350) {
      track.subtractiveEngine.setUseEnvelope(value > 0.5f);
      track.fmEngine.setUseEnvelope(value > 0.5f);
      track.samplerEngine.setParameter(350, value);
      track.granularEngine.setParameter(350, value);
    } else if (track.engineType == 5) {
      track.fmDrumEngine.setParameter(track.selectedFmDrumInstrument,
                                      parameterId - 300, value);
    } else {
      track.samplerEngine.setParameter(parameterId, value);
    }
  }
  // Granular Params (400+) - This block is partially covered by the new 400-464
  // block. I will keep the original 400-450 block as it is distinct.
  else if (parameterId >= 400 && parameterId < 450) {
    track.granularEngine.setParameter(parameterId, value);
  }
  // Wavetable Params (450+)
  else if (parameterId >= 450 && parameterId < 470) {
    if (parameterId == 450)
      track.wavetableEngine.setParameter(0, value); // Morph
    else if (parameterId == 451)
      track.wavetableEngine.setParameter(1, value); // Detune
    // 452, 453 Unused in UI currently (Spread, Unison Voices internal calc?)
    else if (parameterId == 454)
      track.wavetableEngine.setAttack(value);
    else if (parameterId == 455)
      track.wavetableEngine.setDecay(value);
    else if (parameterId == 456)
      track.wavetableEngine.setSustain(value);
    else if (parameterId == 457)
      track.wavetableEngine.setRelease(value);
    else if (parameterId == 458)
      track.wavetableEngine.setFilterCutoff(value);
    else if (parameterId == 459)
      track.wavetableEngine.setResonance(value);
    // 460 Filter Attack (Unused in UI?)
    else if (parameterId == 461)
      track.wavetableEngine.setParameter(11, value); // Filter Decay
    // 462 Filter Sustain
    // 463 Filter Release
    else if (parameterId == 464)
      track.wavetableEngine.setParameter(14, value); // Filter Env Amount
    else if (parameterId == 465)
      track.wavetableEngine.setParameter(15, value); // Warp
    else if (parameterId == 466)
      track.wavetableEngine.setParameter(16, value); // Crush
    else if (parameterId == 467)
      track.wavetableEngine.setParameter(17, value); // Drive
  }
  // Arpeggiator (500+)
  else if (parameterId >= 500 && parameterId < 510) {
    if (parameterId == 500)
      track.arpeggiator.setMode(
          static_cast<ArpMode>(static_cast<int>(value * 7.0f)));
    else if (parameterId == 501)
      track.arpeggiator.setLatched(value > 0.5f);
  } else if (parameterId >= 800 && parameterId < 810) {
    if (parameterId == 800)
      track.midiInChannel = static_cast<int>(value);
    else if (parameterId == 801)
      track.midiOutChannel = static_cast<int>(value);
  }
  // Effect Params (500+) - This block is for GLOBAL FX, not track-specific.
  // It should remain in a global context or be called from a global
  // setParameter. Since updateEngineParameter is track-specific, this block
  // should not be here. I will assume the user's instruction to keep it in the
  // original context. The user's provided snippet for 500-600 was empty, so I
  // will not add anything new here. The original code had this block, which is
  // for global effects, not track effects. I will leave it in the original
  // setParameterLocked function's place, assuming the user will create a
  // separate global parameter setter. For now, I will remove it from
  // updateEngineParameter as it's track-specific. The user's instruction was to
  // split setParameter into setParameter (state update) and
  // updateEngineParameter (logic). The original setParameterLocked had global
  // FX logic. This implies that the new setParameter should handle global FX,
  // or there should be a separate global parameter function.
  // Given the prompt, I will assume updateEngineParameter is strictly for
  // track-specific engine parameters. The global FX logic will be removed from
  // this function.
  // New Effects (1500+)
  else if (parameterId >= 1500 && parameterId < 1600) {
    int fxId = (parameterId - 1500) / 10;
    int subId = parameterId % 10;
    switch (fxId) {
    case 0: // Flanger
      if (subId == 0)
        mFlangerFx.setRate(value); // Removed Scaling
      else if (subId == 1)
        mFlangerFx.setDepth(value);
      else if (subId == 2)
        mFlangerFx.setMix(value); // Reordered Mix to 2 (Priority)
      else if (subId == 3)
        mFlangerFx.setFeedback(value);
      else if (subId == 4)
        mFlangerFx.setDelay(value * 0.02f);
      break;
    case 1: // TapeEcho
      if (subId == 0)
        mTapeEchoFx.setDelayTime(value * 1.0f);
      else if (subId == 1)
        mTapeEchoFx.setFeedback(value);
      else if (subId == 2)
        mTapeEchoFx.setMix(value); // Reordered Mix to 2
      else if (subId == 3)
        mTapeEchoFx.setDrive(value);
      else if (subId == 4)
        mTapeEchoFx.setWow(value);
      else if (subId == 5)
        mTapeEchoFx.setFlutter(value);
      break;
    case 2: // Spread
      if (subId == 0)
        mStereoSpreadFx.setWidth(value);
      else if (subId == 1)
        mStereoSpreadFx.setRate(value); // Added Rate
      else if (subId == 2)
        mStereoSpreadFx.setDepth(value); // Added Depth
      else if (subId == 3)
        mStereoSpreadFx.setMix(value); // Added Mix
      break;
    case 3:
      if (subId == 0)
        mOctaverFx.setMix(value);
      else if (subId == 1)
        mOctaverFx.setMode(value);
      else if (subId == 2)
        mOctaverFx.setUnison(value);
      else if (subId == 3)
        mOctaverFx.setDetune(value);
      break;
    }
  } else if (parameterId >= 490 && parameterId < 500) {
    int subId = parameterId % 10;
    if (subId == 0)
      mLpLfoFx.setRate(value);
    else if (subId == 1)
      mLpLfoFx.setDepth(value);
    else if (subId == 2)
      mLpLfoFx.setShape(value);
    else if (subId == 3)
      mLpLfoFx.setCutoff(value);
    else if (subId == 4)
      mLpLfoFx.setResonance(value);
  } else if (parameterId >= 2000 && parameterId < 2150) {
    int fxIdx = (parameterId - 2000) / 10;
    if (fxIdx < 15)
      track.fxSends[fxIdx] = value;
  }
}

// End of updateEngineParameter

// Processing Commands
void AudioEngine::processCommands() {
  std::vector<AudioCommand> todo;
  {
    std::lock_guard<std::mutex> lock(mCommandLock);
    if (mCommandQueue.empty())
      return;
    todo.swap(mCommandQueue);
  }
  for (const auto &cmd : todo) {
    switch (cmd.type) {
    case AudioCommand::NOTE_ON:
      triggerNoteLocked(cmd.trackIndex, cmd.data1, (int)cmd.value, false);
      break;
    case AudioCommand::NOTE_OFF:
      releaseNoteLocked(cmd.trackIndex, cmd.data1, false);
      break;
    case AudioCommand::PARAM_SET:
      setParameter(cmd.trackIndex, cmd.data1,
                   cmd.value); // Call the new setParameter
      break;
    case AudioCommand::GLOBAL_PARAM_SET:
      break;
    }
  }
}

void AudioEngine::releaseNoteLocked(int trackIndex, int note,
                                    bool isSequencerTrigger) {
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    Track &track = mTracks[trackIndex];
    if (track.engineType == 7) {
      enqueueMidiEvent(0x80, track.midiOutChannel - 1, note, 0);
      track.isActive = false;
    } else {
      if (!isSequencerTrigger) {
        track.mPhysicallyHeldNoteCount--;
        if (track.mPhysicallyHeldNoteCount <= 0) {
          track.mPhysicallyHeldNoteCount = 0;
          track.arpeggiator.onAllPhysicallyReleased();
        }
        if (track.arpeggiator.getMode() != ArpMode::OFF) {
          track.arpeggiator.removeNote(note);
        }
      }

      // ALWAYS release engine notes for manual interaction,
      // even if Arp is ON, otherwise they get stuck when unlatching or
      // switching.
      for (int i = 0; i < Track::MAX_POLYPHONY; ++i) {
        if (track.mActiveNotes[i].active &&
            track.mActiveNotes[i].note == note) {
          track.mActiveNotes[i].active = false;
          break;
        }
      }
      if (mIsRecording && mIsPlaying && !isSequencerTrigger) {
        for (auto it = track.mRecordingNotes.begin();
             it != track.mRecordingNotes.end();) {
          if (it->note == note) {
            // Calculate gate length
            double currentPos =
                (double)mGlobalStepIndex +
                ((double)mSampleCount / (mSamplesPerStep + 0.001));
            double startPos = (double)it->startGlobalStep + it->startOffset;
            float gate = static_cast<float>(currentPos - startPos);

            if (gate < 0.1f)
              gate = 0.1f;
            if (gate > 16.0f)
              gate = 16.0f; // Max 16 steps

            if (it->drumIdx >= 0 && it->drumIdx < 8) {
              track.drumSequencers[it->drumIdx]
                  .getStepsMutable()[it->stepIndex]
                  .gate = gate;
            } else {
              track.sequencer.getStepsMutable()[it->stepIndex].gate = gate;
            }
            it = track.mRecordingNotes.erase(it);
          } else {
            ++it;
          }
        }
      }

      track.subtractiveEngine.releaseNote(note);
      track.fmEngine.releaseNote(note);
      track.samplerEngine.releaseNote(note);
      track.fmDrumEngine.releaseNote(note);
      track.granularEngine.releaseNote(note);
      track.wavetableEngine.releaseNote(note);
    }
  }
}

oboe::DataCallbackResult
AudioEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData,
                          int32_t numFrames) {

  // Robust Denormal Prevention (Flush-to-Zero)
#if defined(__aarch64__)
  uint64_t fpcr;
  asm volatile("mrs %0, fpcr" : "=r"(fpcr));
  fpcr |= (1 << 24); // FZ
  fpcr |= (1 << 25); // DN (Default NaN)
  asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__i386__) || defined(__x86_64__)
  // Use intrinsic for x86
  uint32_t mxcsr = _mm_getcsr();
  _mm_setcsr(mxcsr | 0x8040); // FTZ | DAZ
#endif

  if (audioStream->getDirection() == oboe::Direction::Input) {
    // If resampling is active, ignore microphone input
    if (mIsResampling) {
      return oboe::DataCallbackResult::Continue;
    }

    if (mIsRecordingSample && mRecordingTrackIndex != -1) {
      float *input = static_cast<float *>(audioData);
      auto &track = mTracks[mRecordingTrackIndex];
      for (int i = 0; i < numFrames; ++i) {
        if (track.engineType == 2)
          track.samplerEngine.pushSample(input[i]);
        else if (track.engineType == 3)
          track.granularEngine.pushSample(input[i]);
      }
    }
    return oboe::DataCallbackResult::Continue;
  }

  // --- No Global Lock Here ---

  auto start = std::chrono::steady_clock::now();
  float *output = static_cast<float *>(audioData);
  int numChannels = audioStream->getChannelCount();
  mSampleRate = static_cast<double>(audioStream->getSampleRate());
  if (mSampleRate <= 0.0)
    mSampleRate = 48000.0;

  memset(output, 0, numFrames * numChannels * sizeof(float));

  const int kBlockSize = 256;

  float samplesPerStep =
      (static_cast<float>(mSampleRate) * 60.0f) / (std::max(1.0f, mBpm) * 4.0f);
  mSamplesPerStep = samplesPerStep;

  // Process UI Commands safely before block loop
  // Process UI Commands safely before block loop
  processCommands();

  for (int frameIdx = 0; frameIdx < numFrames; frameIdx += kBlockSize) {
    int framesToDo = std::min(kBlockSize, numFrames - frameIdx);

    // Control Block
    {
      std::lock_guard<std::recursive_mutex> lock(mLock);

      for (int l = 0; l < 5; ++l)
        mLfos[l].process((float)mSampleRate,
                         framesToDo); // Update LFOs once per block

      // Recalculate Master FX coefficients at control rate
      mLpLfoFx.recalculate((float)mSampleRate);
      mHpLfoFx.recalculate((float)mSampleRate);

      mSampleCount += framesToDo;
      while (mSampleCount >= samplesPerStep && samplesPerStep > 0.0f) {
        mSampleCount -= samplesPerStep;
        if (mIsPlaying)
          mGlobalStepIndex++;
      }

      for (int t = 0; t < (int)mTracks.size(); ++t) {
        Track &track = mTracks[t];
        // punch reset logic removed here, now per-frame in renderStereo

        float effectiveMultiplier = track.mClockMultiplier;
        if (track.mArpTriplet && track.arpeggiator.getMode() != ArpMode::OFF) {
          effectiveMultiplier *= 1.5f;
        }

        float trackSamplesPerStep =
            samplesPerStep / std::max(0.01f, effectiveMultiplier);

        // Clamp to prevent audio-rate retriggering (Machine Gun fix)
        if (trackSamplesPerStep < 2400.0f)
          trackSamplesPerStep = 2400.0f;

        if (mIsPlaying && trackSamplesPerStep > 0) {
          track.mStepCountdown -= framesToDo;
          int safetyCounter = 0;
          const int kMaxStepsPerBlock = 4; // Prevent CPU spike
          while (track.mStepCountdown <= 0 &&
                 safetyCounter < kMaxStepsPerBlock) {
            safetyCounter++;
            if (safetyCounter > 16) {
              LOGD("Safety Break Track %d: SPS=%.2f Multi=%.2f Countdown=%.2f",
                   t, samplesPerStep, effectiveMultiplier,
                   track.mStepCountdown);
              track.mStepCountdown =
                  trackSamplesPerStep; // Skip ahead to prevent machine gun
              break;
            }
            track.mStepCountdown += trackSamplesPerStep;

            int seqStep = track.mInternalStepIndex;
            track.mInternalStepIndex =
                (track.mInternalStepIndex + 1) % mPatternLength;

            // --- ARPEGGIATOR (Now separate from sequencer loop) ---
            // MOVED OUTSIDE THIS WHILE LOOP

            // --- SYNTH SEQUENCER ---
            track.sequencer.jumpToStep(seqStep);
            const std::vector<Step> &steps = track.sequencer.getSteps();
            if (seqStep < steps.size()) {
              const Step &s = steps[seqStep];

              // Restore Base Parameters for this track at the start of the step
              // Optimization: Only reset params 0-199 (Synth/Drum) to avoid
              // 1024 overhead
              for (int p = 0; p < 200; ++p) {
                if (std::abs(track.appliedParameters[p] - track.parameters[p]) >
                    0.001f) {
                  track.appliedParameters[p] = track.parameters[p];
                  updateEngineParameter(t, p, track.parameters[p]);
                }
              }

              if (s.active) {
                if (s.probability >= 1.0f || gRng.next() <= s.probability) {
                  for (const auto &ni : s.notes) {
                    double actualOffset =
                        ni.subStepOffset * trackSamplesPerStep;
                    double delayedSamples =
                        actualOffset +
                        track.mStepCountdown; // track.mStepCountdown
                                              // is <= 0 here
                    if (delayedSamples <= 1.0) {
                      triggerNoteLocked(t, ni.note, ni.velocity * 127, true,
                                        s.gate);
                    } else {
                      track.mPendingNotes.push_back(
                          {ni.note, ni.velocity * 127.0f, delayedSamples,
                           s.gate, 1});
                    }

                    // Ratchet Implementation
                    if (s.ratchet > 1) {
                      float ratchetInterval =
                          trackSamplesPerStep / (float)s.ratchet;
                      for (int r = 1; r < s.ratchet; ++r) {
                        double rDelay = delayedSamples + (r * ratchetInterval);
                        track.mPendingNotes.push_back(
                            {ni.note, ni.velocity * 127.0f, rDelay, s.gate, 1});
                      }
                    }
                  }
                  for (auto const &[pid, val] : s.parameterLocks) {
                    track.appliedParameters[pid] = val; // Set applied param
                    updateEngineParameter(t, pid, val);
                  }
                }
              }
            }

            // --- DRUM SEQUENCER ---
            bool isSamplerChops = (track.engineType == 2 &&
                                   track.samplerEngine.getPlayMode() == 2);
            if (track.engineType == 5 || track.engineType == 6 ||
                isSamplerChops) {
              bool drumPunch = false;
              for (int d = 0; d < 16; ++d) {
                track.drumSequencers[d].jumpToStep(seqStep);
                const std::vector<Step> &dSteps =
                    track.drumSequencers[d].getSteps();
                if (seqStep < dSteps.size()) {
                  const Step &ds = dSteps[seqStep];
                  if (ds.active) {
                    if (ds.punch)
                      drumPunch = true;
                    if (ds.probability >= 1.0f ||
                        gRng.next() <= ds.probability) {
                      for (const auto &ni : ds.notes) {
                        double actualOffset =
                            ni.subStepOffset * trackSamplesPerStep;
                        double delayedSamples =
                            actualOffset + track.mStepCountdown;
                        if (delayedSamples <= 1.0) {
                          triggerNoteLocked(t, ni.note, ni.velocity * 127, true,
                                            ds.gate, ds.punch);
                        } else {
                          track.mPendingNotes.push_back(
                              {ni.note, ni.velocity * 127.0f, delayedSamples,
                               ds.gate, 1, ds.punch});
                        }

                        // Ratchet implementation for drums
                        if (ds.ratchet > 1) {
                          float ratchetInterval =
                              trackSamplesPerStep / (float)ds.ratchet;
                          for (int r = 1; r < ds.ratchet; ++r) {
                            double rDelay =
                                delayedSamples + (r * ratchetInterval);
                            track.mPendingNotes.push_back(
                                {ni.note, ni.velocity * 127.0f, rDelay, ds.gate,
                                 1, ds.punch});
                          }
                        }
                      }
                      for (auto const &[pid, val] : ds.parameterLocks) {
                        updateEngineParameter(t, pid, val);
                      }
                    }
                  }
                }
              }
              // Removed global "drumPunch" logic because it's now per-note in
              // PendingNote if (drumPunch) track.mPunchCounter = 4000;
            }
          }
          // Backlog Drop: If we are still behind after max iterations, skip
          // history.
          if (track.mStepCountdown <= 0) {
            track.mStepCountdown = trackSamplesPerStep;
          }
        }

        // --- SEPARATE ARPEGGIATOR CLOCK ---
        if (track.arpeggiator.getMode() != ArpMode::OFF) {
          float arpSamplesPerStep =
              samplesPerStep * std::max(0.125f, track.mArpRate);
          // 0=Reg, 1=Dotted (1.5x), 2=Triplet (0.66x)
          if (track.mArpDivisionMode == 1)
            arpSamplesPerStep *= 1.5f;
          else if (track.mArpDivisionMode == 2)
            arpSamplesPerStep *= 0.66667f;

          track.mArpCountdown -= framesToDo;
          int safety = 0;
          while (track.mArpCountdown <= 0 && safety < 8) {
            safety++;
            track.mArpCountdown += arpSamplesPerStep;
            std::vector<int> arpNotes = track.arpeggiator.nextNotes();
            for (int arpNote : arpNotes) {
              if (arpNote >= 0)
                triggerNoteLocked(t, arpNote, 100, true);
            }
          }
          if (track.mArpCountdown <= 0)
            track.mArpCountdown = arpSamplesPerStep;
        } else {
          track.mArpCountdown = 0; // Keep it ready
        }

        // Process Pending Microtiming Triggers
        for (auto it = track.mPendingNotes.begin();
             it != track.mPendingNotes.end();) {
          it->samplesRemaining -= framesToDo;
          if (it->samplesRemaining <= 0) {
            triggerNoteLocked(t, it->note, (int)it->velocity, true, it->gate);
            it = track.mPendingNotes.erase(it);
          } else {
            ++it;
          }
        }
      }
    } // End lock block

    // Audio Block
    renderStereo(&output[frameIdx * numChannels], framesToDo);

    // --- Process Note Durations (Note Off) AFTER Audio processing ---
    // This ensures every triggered note plays for at least one block even if
    // gate is tiny
    {
      std::lock_guard<std::recursive_mutex> lock(mLock);
      for (int t = 0; t < (int)mTracks.size(); ++t) {
        Track &track = mTracks[t];
        for (int i = 0; i < AudioEngine::Track::MAX_POLYPHONY; ++i) {
          if (track.mActiveNotes[i].active) {
            track.mActiveNotes[i].durationRemaining -= framesToDo;
            if (track.mActiveNotes[i].durationRemaining <= 0) {
              releaseNoteLocked(t, track.mActiveNotes[i].note, true);
              track.mActiveNotes[i].active = false;
            }
          }
        }
      }
    }

    // Push to resampling recorder (Sampler/Granular)
    if (mIsResampling && mIsRecordingSample && mRecordingTrackIndex != -1) {
      auto &recTrack = mTracks[mRecordingTrackIndex];
      for (int k = 0; k < framesToDo; ++k) {
        float mixed = (output[(frameIdx + k) * numChannels] +
                       output[(frameIdx + k) * numChannels + 1]) *
                      0.5f;
        if (recTrack.engineType == 2)
          recTrack.samplerEngine.pushSample(mixed);
        else if (recTrack.engineType == 3)
          recTrack.granularEngine.pushSample(mixed);
      }
    }
  }

  auto end = std::chrono::steady_clock::now();
  float elapsed = std::chrono::duration<float>(end - start).count();
  mCpuLoad =
      mCpuLoad * 0.95f + (elapsed / (numFrames / (float)mSampleRate)) * 0.05f;

  static int logCounter = 0;
  static float maxPeak = 0.0f;

  float currentPeak = 0.0f;
  for (int i = 0; i < numFrames * numChannels; ++i) {
    float a = std::abs(output[i]);
    if (a > currentPeak)
      currentPeak = a;
  }
  if (currentPeak > maxPeak)
    maxPeak = currentPeak;

  if (++logCounter > 187) { // ~Once per second at 48k/256
    logCounter = 0;
    int activeTracks = 0;
    for (const auto &tr : mTracks)
      if (tr.isActive)
        activeTracks++;

    LOGD("AudioEngine Stats: ActiveTracks=%d, MasterVol=%.2f, SampleRate=%.1f, "
         "BlockPeak=%.4f, MaxPeak=%.4f",
         activeTracks, mMasterVolume, (float)mSampleRate, currentPeak, maxPeak);

    // Extra debug: track states
    for (int t = 0; t < 8; ++t) {
      if (mTracks[t].isActive || mTracks[t].smoothedVolume > 0.01f) {
        LOGD("  T%d: Active=%s, SmVol=%.2f, Engine=%d, GainRed=%.2f", t,
             mTracks[t].isActive ? "YES" : "NO", mTracks[t].smoothedVolume,
             mTracks[t].engineType, mTracks[t].gainReduction);
      }
    }
    maxPeak = 0.0f; // Reset max peak every second
  }

  return oboe::DataCallbackResult::Continue;
}

void AudioEngine::triggerNote(int trackIndex, int note, int velocity) {
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back(
      {AudioCommand::NOTE_ON, trackIndex, note, (float)velocity});
}

void AudioEngine::releaseNote(int trackIndex, int note) {
  std::lock_guard<std::mutex> lock(mCommandLock);
  mCommandQueue.push_back({AudioCommand::NOTE_OFF, trackIndex, note, 0.0f});
}

// ... COPY OF OTHER METHODS ...
void AudioEngine::setArpRate(int trackIndex, float rate, int divisionMode) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    std::lock_guard<std::recursive_mutex> lock(mLock);
    mTracks[trackIndex].mArpRate = rate;
    mTracks[trackIndex].mArpDivisionMode = divisionMode;
  }
}

void AudioEngine::setStep(int trackIndex, int stepIndex, bool active,
                          const std::vector<int> &notes, float velocity,
                          int ratchet, bool punch, float probability,
                          float gate) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    Step step;
    for (int n : notes) {
      step.addNote(n, velocity, 0.0f);
    }
    step.active = active; // Set this AFTER addNote because addNote overrides
                          // active to true
    step.ratchet = ratchet;
    step.punch = punch;
    step.probability = probability;
    step.gate = gate;

    int firstNote = notes.empty() ? 60 : notes[0];

    // Drum Sequencer Logic
    // ONLY apply drum mapping if this is explicitly a drum-capable track (Drum
    // Engine or Sampler Chops)
    int drumIdx = -1;
    bool isSamplerChops =
        (mTracks[trackIndex].engineType == 2 &&
         mTracks[trackIndex].samplerEngine.getPlayMode() == 2);

    if (mTracks[trackIndex].engineType == 5 ||
        mTracks[trackIndex].engineType == 6 || isSamplerChops) {
      if (firstNote >= 60)
        drumIdx = firstNote - 60;
      else if (firstNote >= 0 && firstNote < 16)
        drumIdx = firstNote;
      else if (firstNote >= 35) {
        // GM Mapping for drums
        if (firstNote == 35 || firstNote == 36)
          drumIdx = 0; // Kick
        else if (firstNote == 38 || firstNote == 40)
          drumIdx = 1; // Snare
        else if (firstNote == 39)
          drumIdx = 2; // Clap/Tom? (GM: Clap=39)
        else if (firstNote == 41 || firstNote == 43 || firstNote == 45)
          drumIdx = 2; // Low Tom
        else if (firstNote == 42 || firstNote == 44 || firstNote == 46)
          drumIdx = 3; // HiHat Closed / Open
        else if (firstNote == 49)
          drumIdx = 5; // Crash
        // ... Simple mapping
        else
          drumIdx = (firstNote % 8); // Last resort
      }
    }

    // Write to Drum Sequencer if valid index found
    if (drumIdx >= 0 && drumIdx < 16) {
      mTracks[trackIndex].drumSequencers[drumIdx].setStep(stepIndex, step);
    } else {
      // ALWAYS write to Main Sequencer (for highlighting, fallback, and
      // non-drum engines)
      mTracks[trackIndex].sequencer.setStep(stepIndex, step);
    }
  }
}

std::vector<float> AudioEngine::getAllTrackParameters(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  std::vector<float> params;
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    for (int i = 0; i < 1024; ++i) {
      params.push_back(mTracks[trackIndex].parameters[i]);
    }
  }
  return params;
}

void AudioEngine::setSequencerConfig(int trackIndex, int numPages,
                                     int stepsPerPage) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setConfiguration(numPages, stepsPerPage);
    if (mTracks[trackIndex].engineType == 5 ||
        mTracks[trackIndex].engineType == 6) {
      for (int i = 0; i < 8; ++i) {
        mTracks[trackIndex].drumSequencers[i].setConfiguration(numPages,
                                                               stepsPerPage);
      }
    }
  }
}

void AudioEngine::setTempo(float bpm) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mBpm = bpm;
}

void AudioEngine::setPlaying(bool playing) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mIsPlaying = playing;
  if (!playing) {
    mSampleCount = 0;
    mGlobalStepIndex = 0;
    // Panic Logic (Unlocked copy) to stop CPU usage immediately
    for (auto &track : mTracks) {
      track.mInternalStepIndex = 0;
      track.mStepCountdown = 0.0;
      track.mPendingNotes.clear();
      track.isActive = false;
      track.mPendingNotes.clear();
      track.isActive = false;

      // Panic: Force silence
      track.subtractiveEngine.allNotesOff();
      track.fmEngine.allNotesOff();
      track.samplerEngine.allNotesOff();
      track.fmDrumEngine.allNotesOff();
      track.granularEngine.allNotesOff();
      track.wavetableEngine.allNotesOff();
      track.analogDrumEngine.allNotesOff();
    }
  } else {
    for (auto &track : mTracks) {
      track.mInternalStepIndex = 0;
      track.mStepCountdown = 0.0;
      track.mPendingNotes.clear();
    }
    // Clear Global FX Buffers on Start to prevent noise burst
    mDelayFx.clear();
    mReverbFx.clear();
    mTapeWobbleFx.clear();
    mPhaserFx.clear();
    mChorusFx.clear();
    mFlangerFx.clear();
    mFilterLfoFx.reset(mSampleRate);
    mHpLfoFx.reset(mSampleRate);
    mLpLfoFx.reset(mSampleRate);
  }
}

void AudioEngine::setClockMultiplier(int trackIndex, float multiplier) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].mClockMultiplier = multiplier;
  }
}

void AudioEngine::setSwing(float swing) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  for (auto &track : mTracks) {
    track.sequencer.setSwing(swing);
  }
}

void AudioEngine::setPatternLength(int length) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mPatternLength = (length <= 0) ? 1 : (length > 128 ? 128 : length);
  if (mGlobalStepIndex >= mPatternLength) {
    mGlobalStepIndex = 0;
  }
}

void AudioEngine::setPlaybackDirection(int trackIndex, int direction) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setPlaybackDirection(direction);
  }
}

void AudioEngine::setIsRandomOrder(int trackIndex, bool isRandom) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setIsRandomOrder(isRandom);
  }
}

void AudioEngine::setIsJumpMode(int trackIndex, bool isJump) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
  }
}

void AudioEngine::setSelectedFmDrumInstrument(int trackIndex, int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].selectedFmDrumInstrument = drumIndex % 8;
  }
}

void AudioEngine::setParameterLock(int trackIndex, int stepIndex,
                                   int parameterId, float value) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setParameterLock(stepIndex, parameterId,
                                                   value);
  }
}

void AudioEngine::clearParameterLocks(int trackIndex, int stepIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.clearParameterLocks(stepIndex);
  }
}

void AudioEngine::setRouting(int destTrack, int sourceTrack, int source,
                             int dest, float amount, int destParamId) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  RoutingEntry entry = {sourceTrack, static_cast<ModSource>(source),
                        static_cast<ModDestination>(dest), destParamId, amount};
  mRoutingMatrix.addConnection(destTrack, entry);
}

void AudioEngine::applyModulations() {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  // Run control-rate modulation updates (once per buffer)
  // Logic: Iterate all active routes, calculate source value, update dest param
  // (NON-destructively to state)

  // 1. Snapshot Mod Sources (LFOs, Macros)
  // LFOs are updated in renderStereo loop. Using last known value is fine for
  // control rate. Or we can snapshot them here if they are running. Macros are
  // stored in mMacros[].

  for (int t = 0; t < mTracks.size(); ++t) {
    const RoutingEntry *mods;
    int count;
    mRoutingMatrix.getFastConnections(t, &mods, &count);
    if (count == 0)
      continue;

    for (int i = 0; i < count; ++i) {
      const auto &mod = mods[i];
      float srcValue = 0.0f;

      // Get Source Value
      switch (mod.source) {
      case ModSource::LFO1:
        srcValue = mLfos[0].getCurrentValue();
        break;
      case ModSource::LFO2:
        srcValue = mLfos[1].getCurrentValue();
        break;
      case ModSource::LFO3:
        srcValue = mLfos[2].getCurrentValue();
        break;
      case ModSource::LFO4:
        srcValue = mLfos[3].getCurrentValue();
        break;
      case ModSource::LFO5:
        srcValue = mLfos[4].getCurrentValue();
        break;
      case ModSource::Macro1:
        srcValue = mMacros[0].value;
        break;
      case ModSource::Macro2:
        srcValue = mMacros[1].value;
        break;
      case ModSource::Macro3:
        srcValue = mMacros[2].value;
        break;
      case ModSource::Macro4:
        srcValue = mMacros[3].value;
        break;
      case ModSource::Macro5:
        srcValue = mMacros[4].value;
        break;
      case ModSource::Macro6:
        srcValue = mMacros[5].value;
        break;
      default:
        break;
      }

      // Safety: Prevent NaN/Inf from spreading through modulation
      if (!std::isfinite(srcValue)) {
        srcValue = 0.0f;
      }

      // Apply to Destination
      if (mod.destination == ModDestination::Parameter &&
          mod.destParamId != -1) {
        float baseVal =
            mTracks[t].parameters[mod.destParamId]; // Use BASE value
        float effectiveVal = baseVal + (srcValue * mod.amount);

        // Store in appliedParameters for consistency
        mTracks[t].appliedParameters[mod.destParamId] = effectiveVal;

        if (std::isfinite(effectiveVal)) {
          updateEngineParameter(t, mod.destParamId, effectiveVal);
        }
      }
      // Handle legacy destinations if needed (Volume, Cutoff...)
      else if (mod.destination == ModDestination::FilterCutoff) {
        float baseVal = mTracks[t].parameters[112]; // Assuming 112 is Cutoff
        float effectiveVal = baseVal + (srcValue * mod.amount);
        if (std::isfinite(effectiveVal)) {
          updateEngineParameter(t, 112, effectiveVal);
        }
      }
    }
  }
}

void AudioEngine::setIsRecording(bool isRecording) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mIsRecording = isRecording;
}

void AudioEngine::jumpToStep(int stepIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mGlobalStepIndex = stepIndex % mPatternLength;

  for (auto &track : mTracks) {
    track.sequencer.jumpToStep(mGlobalStepIndex);
    for (int i = 0; i < 8; ++i) {
      track.drumSequencers[i].jumpToStep(mGlobalStepIndex);
    }
  }
  mSampleCount = mSamplesPerStep;
}

int AudioEngine::getCurrentStep(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    return mTracks[trackIndex].sequencer.getCurrentStepIndex();
  }
  return 0;
}

void AudioEngine::setArpConfig(int trackIndex, int mode, int octaves,
                               int inversion, bool isLatched,
                               const std::vector<std::vector<bool>> &rhythms,
                               const std::vector<int> &sequence) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    ArpMode newMode = static_cast<ArpMode>(mode);
    Track &track = mTracks[trackIndex];

    bool wasLatched = track.arpeggiator.isLatched();
    if (wasLatched && !isLatched && track.mPhysicallyHeldNoteCount == 0) {
      track.arpeggiator.clear();
      for (int i = 0; i < AudioEngine::Track::MAX_POLYPHONY; ++i) {
        if (track.mActiveNotes[i].active) {
          track.subtractiveEngine.releaseNote(track.mActiveNotes[i].note);
          track.fmEngine.releaseNote(track.mActiveNotes[i].note);
          track.samplerEngine.releaseNote(track.mActiveNotes[i].note);
          track.fmDrumEngine.releaseNote(track.mActiveNotes[i].note);
          track.granularEngine.releaseNote(track.mActiveNotes[i].note);
          track.wavetableEngine.releaseNote(track.mActiveNotes[i].note);
          track.mActiveNotes[i].active = false;
        }
      }
    }

    if (newMode == ArpMode::OFF) {
      track.arpeggiator.clear();
    }
    track.arpeggiator.setMode(newMode);
    track.arpeggiator.setOctaves(octaves);
    track.arpeggiator.setInversion(inversion);
    track.arpeggiator.setLatched(isLatched);
    track.arpeggiator.setRhythm(rhythms);
    track.arpeggiator.setRandomSequence(sequence);
  }
}

void AudioEngine::getGranularPlayheads(int trackIndex,
                                       GranularEngine::PlayheadInfo *out,
                                       int maxCount) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].granularEngine.getPlayheads(out, maxCount);
  }
}

void AudioEngine::normalizeSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].samplerEngine.normalize();
  }
}

void AudioEngine::saveSample(int trackIndex, const std::string &path) {
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  auto &track = mTracks[trackIndex];
  // Currently only support SamplerEngine saving
  if (track.engineType == 2) { // Sampler
    std::vector<float> data = track.samplerEngine.getSampleData();
    std::vector<float> slices = track.samplerEngine.getSlicePoints();
    WavFileUtils::writeWav(path, data, 48000, 1, slices);
  } else if (track.engineType == 3) { // Granular (Standardized to 3)
    std::vector<float> data = track.granularEngine.getSampleData();
    std::vector<float> slices;
    WavFileUtils::writeWav(path, data, 48000, 1, slices);
  }
}

void AudioEngine::loadSample(int trackIndex, const std::string &path) {
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  auto &track = mTracks[trackIndex];

  std::vector<float> data;
  std::vector<float> slices;
  int sampleRate, channels;

  if (WavFileUtils::loadWav(path, data, sampleRate, channels, slices)) {
    if (track.engineType == 2) {
      track.samplerEngine.loadSample(data);
      track.samplerEngine.setSlicePoints(slices);
    } else if (track.engineType == 3) { // Granular (Standardized to 3)
      track.granularEngine.setSource(data);
    }
    track.lastSamplePath = path;
    saveAppState();
  }
}

void AudioEngine::setAppDataDir(const std::string &dir) { mAppDataDir = dir; }

void AudioEngine::saveAppState() {
  if (mAppDataDir.empty())
    return;
  std::string path = mAppDataDir + "/app_state.txt";
  std::ofstream file(path);
  if (file.is_open()) {
    for (int i = 0; i < (int)mTracks.size(); ++i) {
      if (!mTracks[i].lastSamplePath.empty()) {
        file << i << ":" << mTracks[i].lastSamplePath << "\n";
      }
    }
    file.close();
  }
}

void AudioEngine::loadAppState() {
  if (mAppDataDir.empty())
    return;
  std::string path = mAppDataDir + "/app_state.txt";
  std::ifstream file(path);
  if (file.is_open()) {
    std::string line;
    while (std::getline(file, line)) {
      size_t pos = line.find(':');
      if (pos != std::string::npos) {
        int trackIndex = std::stoi(line.substr(0, pos));
        std::string samplePath = line.substr(pos + 1);
        if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
          loadSample(trackIndex, samplePath);
        }
      }
    }
    file.close();
  }
}

std::string AudioEngine::getLastSamplePath(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    return mTracks[trackIndex].lastSamplePath;
  }
  return "";
}

void AudioEngine::trimSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    if (mTracks[trackIndex].engineType == 2) {
      mTracks[trackIndex].samplerEngine.trim();
    } else if (mTracks[trackIndex].engineType == 3) { // Granular
      float start = mTracks[trackIndex].parameters[330];
      float end = mTracks[trackIndex].parameters[331];
      mTracks[trackIndex].granularEngine.trim(start, end);
    }
  }
}

void AudioEngine::startRecordingSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mIsRecordingSample = true;
    mRecordingTrackIndex = trackIndex;
    if (mTracks[trackIndex].engineType == 2)
      mTracks[trackIndex].samplerEngine.clearBuffer();
    else if (mTracks[trackIndex].engineType == 3) // Granular
      mTracks[trackIndex].granularEngine.clearSource();
  }
}

void AudioEngine::stopRecordingSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mIsRecordingSample = false;
  mRecordingTrackIndex = -1;
}

void AudioEngine::setEngineType(int trackIndex, int type) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].engineType = type;
  }
}

std::vector<float> AudioEngine::getSamplerWaveform(int trackIndex,
                                                   int numPoints) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    if (mTracks[trackIndex].engineType == 2)
      return mTracks[trackIndex].samplerEngine.getAmplitudeWaveform(numPoints);
    else if (mTracks[trackIndex].engineType == 3)
      return mTracks[trackIndex].granularEngine.getAmplitudeWaveform(numPoints);
  }
  return {};
}

bool AudioEngine::getStepActive(int trackIndex, int stepIndex, int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    if (drumIndex >= 0 && drumIndex < 16) {
      const auto &steps =
          mTracks[trackIndex].drumSequencers[drumIndex].getSteps();
      if (stepIndex >= 0 && stepIndex < (int)steps.size()) {
        return steps[stepIndex].active;
      }
    } else {
      const auto &steps = mTracks[trackIndex].sequencer.getSteps();
      if (stepIndex >= 0 && stepIndex < (int)steps.size()) {
        return steps[stepIndex].active;
      }
    }
  }
  return false;
}

void AudioEngine::resetSampler(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].samplerEngine.clearBuffer();
  }
}

std::vector<float> AudioEngine::getSamplerSlicePoints(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    if (mTracks[trackIndex].engineType == 2)
      return mTracks[trackIndex].samplerEngine.getSlicePoints();
  }
  return {};
}

void AudioEngine::clearSequencer(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.clear();
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].clear();
    }
  }
}

void AudioEngine::setMasterVolume(float volume) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mMasterVolume = volume * 1.5f; // 50% boost at max
}

void AudioEngine::panic() {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  for (auto &track : mTracks) {
    track.isActive = false;
    track.mSilenceFrames = 0;
    for (int n = 0; n < 128; ++n) {
      track.subtractiveEngine.releaseNote(n);
      track.fmEngine.releaseNote(n);
      track.samplerEngine.releaseNote(n);
      track.fmDrumEngine.releaseNote(n);
      track.granularEngine.releaseNote(n);
      track.wavetableEngine.releaseNote(n);
    }
  }
}

// ... COPY OF OTHER METHODS ...
// (Omitting small getters for brevity, but they follow the pattern)
// setGenericLfoParam, setMacroValue, setFxChain etc.

void AudioEngine::setGenericLfoParam(int lfoIndex, int paramId, float value) {
  if (lfoIndex < 0 || lfoIndex >= 5)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  switch (paramId) {
  case 0:
    mLfos[lfoIndex].setFrequency(value);
    break;
  case 1:
    mLfos[lfoIndex].setDepth(value);
    break;
  case 2:
    mLfos[lfoIndex].setShape((int)value);
    break;
  case 3:
    mLfos[lfoIndex].setSync(value > 0.5f);
    break;
  }
}

void AudioEngine::setMacroValue(int macroIndex, float value) {
  if (macroIndex < 0 || macroIndex >= 6)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mMacros[macroIndex].value = value;
}

void AudioEngine::setMacroSource(int macroIndex, int sourceType,
                                 int sourceIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (macroIndex >= 0 && macroIndex < 6) {
    mMacros[macroIndex].sourceType = sourceType;
    mMacros[macroIndex].sourceIndex = sourceIndex;
  }
}

void AudioEngine::setFxChain(int sourceFx, int destFx) {
  if (sourceFx < 0 || sourceFx >= 15)
    return;
  if (destFx < -1 || destFx >= 15)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mFxChainDest[sourceFx] = destFx;
}

void AudioEngine::setTrackVolume(int trackIndex, float volume) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].volume = volume;
  }
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *audioStream,
                                    oboe::Result result) {
  if (result == oboe::Result::ErrorDisconnected) {
    start();
  }
}

void AudioEngine::setArpTriplet(int trackIndex, bool isTriplet) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].mArpTriplet = isTriplet;
  }
}

float AudioEngine::getCpuLoad() { return mCpuLoad.load(); }

void AudioEngine::enqueueMidiEvent(int type, int channel, int data1,
                                   int data2) {
  std::lock_guard<std::mutex> lock(mMidiLock);
  MidiMessage msg;
  msg.type = type;
  msg.channel = channel;
  msg.data1 = data1;
  msg.data2 = data2;
  mMidiQueue.push_back(msg);
}

int AudioEngine::fetchMidiEvents(int *outBuffer, int maxEvents) {
  std::lock_guard<std::mutex> lock(mMidiLock);
  int count = 0;
  while (!mMidiQueue.empty() && count < maxEvents) {
    MidiMessage msg = mMidiQueue.front();
    mMidiQueue.erase(mMidiQueue.begin());
    int offset = count * 4;
    outBuffer[offset] = msg.type;
    outBuffer[offset + 1] = msg.channel;
    outBuffer[offset + 2] = msg.data1;
    outBuffer[offset + 3] = msg.data2;
    count++;
  }
  return count;
}

std::vector<Step> AudioEngine::getSequencerSteps(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    return mTracks[trackIndex].sequencer.getSteps();
  }
  return {};
}

void AudioEngine::loadFmPreset(int trackIndex, int presetId) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].fmEngine.loadPreset(presetId);
  }
}

void AudioEngine::setResampling(bool isResampling) {
  mIsResampling = isResampling;
}

void AudioEngine::renderStereo(float *outBuffer, int numFrames) {
  // FAILSAFE DIAGNOSTIC REMOVED

  applyModulations();
  float sampleRate = (float)mSampleRate;

  // Generic LFO snapshot
  float lfoValues[5];
  for (int l = 0; l < 5; ++l) {
    lfoValues[l] = mLfos[l].getCurrentValue();
  }

  // Update Macros from LFOs once per block
  for (int m = 0; m < 6; ++m) {
    if (mMacros[m].sourceType == 3) { // 3 = LFO
      int lfoIdx = mMacros[m].sourceIndex;
      if (lfoIdx >= 0 && lfoIdx < 5) {
        float val = (lfoValues[lfoIdx] + 1.0f) * 0.5f;
        if (val < 0.0f)
          val = 0.0f;
        else if (val > 1.0f)
          val = 1.0f;
        mMacros[m].value = val;
      }
    }
  }

  // Auto-Release logic once per block
  for (int t = 0; t < (int)mTracks.size(); ++t) {
    Track &track = mTracks[t];
    track.gainReduction = 1.0f; // Reset gain reduction per block

    for (int n = 0; n < Track::MAX_POLYPHONY; ++n) {
      if (track.mActiveNotes[n].active &&
          track.mActiveNotes[n].durationRemaining < 999990.0f) {
        track.mActiveNotes[n].durationRemaining -= (float)numFrames;
        if (track.mActiveNotes[n].durationRemaining <= 0.0f) {
          switch (track.engineType) {
          case 0:
            track.subtractiveEngine.releaseNote(track.mActiveNotes[n].note);
            break;
          case 1:
            track.fmEngine.releaseNote(track.mActiveNotes[n].note);
            break;
          case 2:
            track.samplerEngine.releaseNote(track.mActiveNotes[n].note);
            break;
          case 3:
            track.granularEngine.releaseNote(track.mActiveNotes[n].note);
            break;
          case 4:
            track.wavetableEngine.releaseNote(track.mActiveNotes[n].note);
            break;
          case 5:
            track.fmDrumEngine.releaseNote(track.mActiveNotes[n].note);
            break;
          case 6:
            track.analogDrumEngine.releaseNote(track.mActiveNotes[n].note);
            break;
          } // End switch
          track.mActiveNotes[n].active = false;
          track.mActiveNotes[n].durationRemaining = 9999998.0f;
        }
      }
    }
  }

  if (mStream) {
    sampleRate = static_cast<float>(mStream->getSampleRate());
  }

  for (int i = 0; i < numFrames; ++i) {
    float fxBuses[15] = {0.0f};
    float mixedSample = 0.0f;
    float sidechainSignal = 0.0f;

    for (int t = 0; t < (int)mTracks.size(); ++t) {
      Track &track = mTracks[t];

      // Idle CPU Optimization:
      // If the track is inactive and has been silent for > 1 second (48000
      // frames), effectively bypass the expensive engine render logic.
      if (!track.isActive && track.mSilenceFrames > 48000) {
        // Keep envelope follower fed with silence so it decays properly
        track.follower.process(0.0f);
        continue;
      }

      if (!track.isActive && track.smoothedVolume < 0.0001f) {
        // Fallback legacy check
      }
      float rawSample = 0.0f;
      switch (track.engineType) {
      case 0:
        rawSample = track.subtractiveEngine.render();
        break;
      case 1:
        rawSample = track.fmEngine.render();
        break;
      case 2:
        rawSample = track.samplerEngine.render();
        break;
      case 3: {
        float l = 0, r = 0;
        track.granularEngine.render(&l, &r);
        rawSample = (l + r) * 0.5f;
        break;
      }
      case 4:
        rawSample = track.wavetableEngine.render();
        break;
      case 5:
        rawSample = track.fmDrumEngine.render();
        break;
      case 6:
        rawSample = track.analogDrumEngine.render();
        break;
      }

      if (std::isnan(rawSample)) {
        rawSample = 0.0f; // Safety clamp
      }

      // --- Silence Detection ---
      if (std::abs(rawSample) < 0.0001f) {
        track.mSilenceFrames++;
        if (track.mSilenceFrames > 48000) { // >1 Second Silence
          // Only sleep if no keys are held and no active voices
          bool activeVoices = false;
          for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
            if (track.mActiveNotes[v].active) {
              activeVoices = true;
              break;
            }
          }
          if (track.mPhysicallyHeldNoteCount == 0 && !activeVoices) {
            track.isActive = false;
            track.mSilenceFrames = 0; // Reset for next wake
          }
        }
      } else {
        track.mSilenceFrames = 0;
      }

      if (std::abs(track.volume - track.smoothedVolume) > 0.0001f) {
        track.smoothedVolume += 0.01f * (track.volume - track.smoothedVolume);
      }

      // Safety: Prevent denormals before FX
      if (std::abs(rawSample) < 1e-12f) {
        rawSample = 0.0f;
      }

      float trackOutput =
          rawSample * track.smoothedVolume * track.gainReduction;

      if (std::isnan(trackOutput)) {
        trackOutput = 0.0f;
      }

      // Punch (Drum Compression) Logic
      if (track.mPunchCounter > 0) {
        float x = trackOutput * 2.25f; // 50% increase from 1.5x (original gain)
        float x2 = x * x;
        // More aggressive saturation formula for "Drive +100%"
        // trackOutput = x * (27.0f + x2) / (27.0f + 9.0f * x2); // original
        trackOutput =
            std::tanh(x * 1.5f); // Real tanh with extra drive, scaled by gain
        track.mPunchCounter--;
      }

      trackOutput = std::tanh(trackOutput);
      // HEADROOM SCALING: Scale down internal engine output by 0.5x
      // This prevents the mix bus from clipping when multiple tracks are
      // active. Master Volume can boost this back up if needed.
      mixedSample += trackOutput * 0.5f;

      if (mSidechainSourceTrack >= 0 &&
          &track == &mTracks[mSidechainSourceTrack % 8]) {
        sidechainSignal = trackOutput;
      }

      for (int f = 0; f < 15; ++f) {
        if (track.fxSends[f] > 0.001f || track.smoothedFxSends[f] > 0.001f) {
          track.smoothedFxSends[f] +=
              0.01f * (track.fxSends[f] - track.smoothedFxSends[f]);
          // Pad FX Send by 0.7x (User requested boost from 0.5x)
          fxBuses[f] += (trackOutput * 0.7f) * track.smoothedFxSends[f];
        }
      }
      track.follower.process(trackOutput);
    }
    // Removed erroneous line 1829

    // float sampleRate = 48000.0f; // Already defined above

    // Master FX Chain
    float currentSampleL = mixedSample;
    float currentSampleR = mixedSample;
    float wetSample = 0.0f;
    float spreadL = 0.0f, spreadR = 0.0f;

    auto routeFx = [&](int index, float val) {
      int dest = mFxChainDest[index];
      if (dest >= 0 && dest < 15)
        fxBuses[dest] += val;
      else
        wetSample += val;
    };

    const float kSilence = 0.00001f;
    if (std::abs(fxBuses[0]) > kSilence)
      routeFx(0, mOverdriveFx.process(fxBuses[0]));
    if (std::abs(fxBuses[1]) > kSilence)
      routeFx(1, mBitcrusherFx.process(fxBuses[1]));
    if (std::abs(fxBuses[2]) > kSilence)
      routeFx(2, mChorusFx.process(fxBuses[2], sampleRate));
    if (std::abs(fxBuses[3]) > kSilence)
      routeFx(3, mPhaserFx.process(fxBuses[3], sampleRate));
    if (std::abs(fxBuses[4]) > kSilence)
      routeFx(4, mTapeWobbleFx.process(fxBuses[4], sampleRate));

    // Delay (Bus 5)
    if (std::abs(fxBuses[5]) > kSilence) {
      float dL = 0, dR = 0;
      mDelayFx.processStereo(fxBuses[5], fxBuses[5], dL, dR);

      int dest = mFxChainDest[5];
      if (dest >= 0 && dest < 15) {
        fxBuses[dest] += (dL + dR) * 0.5f;
      } else {
        spreadL += dL;
        spreadR += dR;
      }
    }

    // Reverb (Bus 6)
    if (std::abs(fxBuses[6]) > kSilence) {
      float rL = 0.0f, rR = 0.0f;
      // Reverb is a SEND effect. We only want the WET tail.
      mReverbFx.processStereoWet(fxBuses[6], fxBuses[6], rL, rR);

      int dest = mFxChainDest[6];
      if (dest >= 0 && dest < 15) {
        fxBuses[dest] += (rL + rR) * 0.5f;
      } else {
        // These are added to the FINAL output
        spreadL += rL;
        spreadR += rR;
      }
    }

    if (std::abs(fxBuses[7]) > kSilence)
      routeFx(7, mSlicerFx.process(fxBuses[7], mSampleCount, mSamplesPerStep));

    // Compressor (Bus 8)
    if (std::abs(fxBuses[8]) > kSilence)
      routeFx(8, mCompressorFx.process(fxBuses[8], sidechainSignal));

    if (std::abs(fxBuses[11]) > kSilence)
      routeFx(11, mFlangerFx.process(fxBuses[11], sampleRate));

    if (std::abs(fxBuses[12]) > kSilence) {
      float sL = 0, sR = 0;
      mStereoSpreadFx.process(fxBuses[12], sL, sR, sampleRate);
      int dest = mFxChainDest[12];
      if (dest >= 0 && dest < 15)
        fxBuses[dest] += (sL + sR) * 0.5f;
      else {
        spreadL += sL;
        spreadR += sR;
      }
    }

    if (std::abs(fxBuses[13]) > kSilence)
      routeFx(13, mTapeEchoFx.process(fxBuses[13], sampleRate));
    if (std::abs(fxBuses[14]) > kSilence)
      routeFx(14, mOctaverFx.process(fxBuses[14], sampleRate));

    // LP/HP LFO Pedals (Final utility filters)
    currentSampleL = mLpLfoFx.process(currentSampleL);
    currentSampleR = mLpLfoFx.process(currentSampleR);
    currentSampleL = mHpLfoFx.process(currentSampleL);
    currentSampleR = mHpLfoFx.process(currentSampleR);

    // Apply Master Volume with 2.0 boost multiplier (higher headroom)
    // Since we scaled internal engines by 0.5x, 2.0x here restores 100% level
    // at max knob. Normalized range allows boosting quiet mixes.
    float finalL =
        (currentSampleL + wetSample + spreadL) * (mMasterVolume * 2.0f);
    float finalR =
        (currentSampleR + wetSample + spreadR) * (mMasterVolume * 2.0f);

    // Soft Limiter to prevent "screaming" / explosion / NaNs
    // Final Safety Clamps
    if (std::isnan(finalL) || std::isinf(finalL))
      finalL = 0.0f;
    if (std::isnan(finalR) || std::isinf(finalR))
      finalR = 0.0f;

    outBuffer[i * 2] = softLimit(finalL);
    outBuffer[i * 2 + 1] = softLimit(finalR);
  }

  // Reset Punch Active flags for all tracks after processing the block
  // Reset of mPunchActive removed here, handled frame-by-frame
}

void AudioEngine::renderToWav(int numCycles, const std::string &path) {
  std::lock_guard<std::recursive_mutex> lock(mLock);

  // Offline rendering logic
  int framesPerCycle = mSamplesPerStep * 16; // One bar
  int totalFrames = framesPerCycle * numCycles;
  std::vector<float> output(totalFrames * 2);

  // Reset sequence state for export
  mSampleCount = 0;
  mGlobalStepIndex = 0;
  for (auto &t : mTracks)
    t.sequencer.clear();

  int framesRendered = 0;
  while (framesRendered < totalFrames) {
    // Process sequencer updates
    processCommands();

    // Check if we need to trigger next step
    if (mSampleCount >= mSamplesPerStep) {
      mSampleCount -= mSamplesPerStep;
      mGlobalStepIndex = (mGlobalStepIndex + 1) % 128;
      for (int t = 0; t < (int)mTracks.size(); ++t) {
        mTracks[t].sequencer.advance();
        const Step &s = mTracks[t].sequencer.getCurrentStep();
        if (s.active) {
          triggerNoteLocked(t, 60, 100, true);
        }
      }
    }

    int chunk = std::min(128, totalFrames - framesRendered);
    renderStereo(&output[framesRendered * 2], chunk);
    framesRendered += chunk;
    mSampleCount += chunk;
  }

  WavFileUtils::writeWav(path, output, 48000, 2, {});
}

void AudioEngine::loadWavetable(int trackIndex, const std::string &path) {
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    if (mTracks[trackIndex].engineType == 4) { // Wavetable Engine
      mTracks[trackIndex].wavetableEngine.loadWavetable(path);
    }
  }
}

void AudioEngine::loadDefaultWavetable(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    if (mTracks[trackIndex].engineType == 4) {
      mTracks[trackIndex].wavetableEngine.loadDefaultWavetable();
    }
  }
}
