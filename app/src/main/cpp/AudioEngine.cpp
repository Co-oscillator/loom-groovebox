#define TSF_IMPLEMENTATION
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

// (Using fast_tanh from Utils.h)

static inline float softLimit(float x) {
  if (std::isnan(x))
    return 0.0f;
  // Soft-Knee Limiter (Linear until -3dB, then smooth curve to 0dB)
  // -3dB = 0.707
  float absX = std::abs(x);
  if (absX < 0.707f)
    return x;

  // Soft compression above -3dB: x = sign(x) * (0.707 + (1.0 - 0.707) *
  // fast_tanh((absX - 0.707) / (1.0 - 0.707)))
  float extended = (absX - 0.707f) / 0.293f;
  float limited = 0.707f + 0.293f * fast_tanh(extended);
  return (x > 0) ? limited : -limited;
}

AudioEngine::AudioEngine() {
  mSampleRate = 48000.0; // Default to common Android rate
  mBpm = 120.0f;
  setupTracks();
  for (int i = 0; i < 15; ++i)
    mFxChainDest[i] = -1;

  // FX Slot Filters (Slots 9/10)
  mHpLfoL.setCutoff(0.0f);
  mHpLfoR.setCutoff(0.0f);
  mLpLfoL.setCutoff(1.0f);
  mLpLfoR.setCutoff(1.0f);
  mHpLfoL.reset((float)mSampleRate);
  mHpLfoR.reset((float)mSampleRate);
  mLpLfoL.reset((float)mSampleRate);
  mLpLfoR.reset((float)mSampleRate);
  mSidechainSourceTrack = -1;
  mSidechainSourceDrumIdx = -1;

  for (int i = 0; i < 15; ++i) {
    mFxMixLevels[i] = 1.0f;
    mFxChainDest[i] = -1;
    mFxFeedbacksL[i] = 0.0f;
    mFxFeedbacksR[i] = 0.0f;
  }
}

AudioEngine::~AudioEngine() { stop(); }

// Helper to reset a single track's parameters and engine state
void AudioEngine::initTrack(int i) {
  mTracks[i].volume = 0.7f;
  mTracks[i].smoothedVolume = 0.7f;
  mTracks[i].pan = 0.5f;
  mTracks[i].smoothedPan = 0.5f;
  mTracks[i].panL = 0.7071f;
  mTracks[i].panR = 0.7071f;
  mTracks[i].mSilenceFrames = 48002;
  mTracks[i].isActive = false; // Start inactive to save CPU

  // Clear all parameters and FX sends to prevent ghost states (Phantom Panning)
  std::fill(std::begin(mTracks[i].parameters), std::end(mTracks[i].parameters),
            0.0f);
  std::fill(std::begin(mTracks[i].appliedParameters),
            std::end(mTracks[i].appliedParameters), 0.0f);
  std::fill(std::begin(mTracks[i].fxSends), std::end(mTracks[i].fxSends), 0.0f);
  std::fill(std::begin(mTracks[i].smoothedFxSends),
            std::end(mTracks[i].smoothedFxSends), 0.0f);

  // Initialize defaults
  mTracks[i].subtractiveEngine.setSustain(1.0f);
  mTracks[i].subtractiveEngine.setDecay(0.5f);

  mTracks[i].fmEngine.resetToDefaults();
  mTracks[i].fmEngine.setParameter(101, 0.5f); // Decay
  mTracks[i].fmEngine.setParameter(102, 1.0f); // Sustain

  // Analog Drum Defaults
  mTracks[i].analogDrumEngine.resetToDefaults();

  // FM Drum Defaults
  for (int k = 0; k < 4; k++) {
    mTracks[i].fmDrumEngine.setParameter(k, 0, 0.5f); // Pitch
    mTracks[i].fmDrumEngine.setParameter(k, 1, 0.5f); // Tone
    mTracks[i].fmDrumEngine.setParameter(k, 2, 0.4f); // Decay
  }

  // Sync parameters array with audible defaults so UI doesn't zero them out
  mTracks[i].parameters[0] = 0.7f;     // Volume
  mTracks[i].parameters[1] = 1.0f;     // Cutoff (Open)
  mTracks[i].parameters[2] = 0.0f;     // Resonance
  mTracks[i].parameters[9] = 0.5f;     // Pan (Center) - Fix "Left at Launch"
  mTracks[i].parameters[100] = 0.01f;  // Attack
  mTracks[i].parameters[101] = 0.5f;   // Decay
  mTracks[i].parameters[102] = 1.0f;   // Sustain
  mTracks[i].parameters[103] = 0.2f;   // Release
  mTracks[i].parameters[107] = 0.6f;   // Osc 1 Volume
  mTracks[i].parameters[108] = 0.4f;   // Osc 2 Volume
  mTracks[i].parameters[109] = 0.4f;   // Osc 3 (Sub) Volume
  mTracks[i].parameters[160] = 0.25f;  // Osc 1 Pitch (1.0)
  mTracks[i].parameters[161] = 0.25f;  // Osc 2 Pitch (1.0)
  mTracks[i].parameters[162] = 0.125f; // Osc 3 (Sub) Pitch (0.5)
  mTracks[i].parameters[163] = 0.25f;  // Osc 4 Pitch (1.0)

  // FM Defaults (Op Levels)
  mTracks[i].parameters[160] = 0.8f; // Op 1 Level
  mTracks[i].parameters[166] = 0.0f; // Op 2 Level (Modulator usually)

  // Wavetable Defaults
  mTracks[i].parameters[310] = 0.0f; // Position
  mTracks[i].parameters[311] = 0.0f; // Morph

  // Sampler Defaults
  mTracks[i].parameters[320] = 0.0f; // Mode: 1-Shot
  mTracks[i].parameters[341] = 0.5f; // Speed (50%)

  // CRITICAL: Force DSP to update immediately using these defaults.
  // The UI relies on the 'parameters' array, but the Engine needs
  // 'setParameter' calls.
  // Common Defaults
  setParameter(i, 0, 0.7f);     // Volume
  setParameter(i, 1, 1.0f);     // Cutoff
  setParameter(i, 2, 0.0f);     // Resonance
  setParameter(i, 100, 0.01f);  // Attack
  setParameter(i, 101, 0.5f);   // Decay
  setParameter(i, 102, 1.0f);   // Sustain
  setParameter(i, 103, 0.2f);   // Release
  setParameter(i, 107, 0.6f);   // Osc 1
  setParameter(i, 108, 0.4f);   // Osc 2
  setParameter(i, 109, 0.4f);   // Osc 3 (Sub)
  setParameter(i, 162, 0.125f); // Osc 3 Pitch (Sub)
  setParameter(i, 9, 0.5f);     // Pan (Center)
  setParameter(i, 341, 0.5f);   // Sampler Speed

  // Engine-Specific Defaults
  int type = mTracks[i].engineType;
  if (type == 0) {               // Subtractive
    setParameter(i, 104, 0.2f);  // Osc 1 Wave: Sawtooth
    setParameter(i, 105, 0.4f);  // Osc 2 Wave: Square
    setParameter(i, 160, 0.25f); // Osc 1 Pitch: 1.0 (Value * 4.0)
    setParameter(i, 161, 0.25f); // Osc 2 Pitch: 1.0 (Value * 4.0)
    setParameter(
        i, 162,
        0.5f); // Osc 3 (Sub) Pitch: 1.0 (Value * 2.0) -> One octave lower
    setParameter(i, 350, 1.0f); // Use Envelope: True
    setParameter(i, 107, 0.6f); // Ensure Osc 1 Vol is set
    setParameter(i, 9, 0.5f);   // PAN CENTER
  } else if (type == 1) {       // FM
    // Use the "Vibe" preset (ID 11) directly to guarantee good sound
    mTracks[i].fmEngine.loadPreset(11);

    // Ensure envelope is enabled
    setParameter(i, 350, 1.0f);

    // Sync critical params back to parameter array for UI consistency
    mTracks[i].parameters[156] = 0.2f;  // Algo 2
    mTracks[i].parameters[160] = 0.5f;  // Op 1 Level
    mTracks[i].parameters[161] = 0.05f; // Op 1 Atk
    mTracks[i].parameters[166] = 0.0f;  // Op 2 Level
  } else if (type == 5) {               // FM Drum
    // Initialize 8 drums (Kick, Snare, Tom, HH, OpenHH, Cymbal, Perc, Noise)
    for (int drum = 0; drum < 8; ++drum) {
      int baseId = 200 + (drum * 10);
      setParameter(i, baseId + 5, 0.7f); // Gain
      setParameter(i, baseId + 2, 0.4f); // Decay
      setParameter(i, baseId + 1, 0.5f); // Tone/Feedback
    }
  } else if (type == 4) { // Wavetable
    mTracks[i].wavetableEngine.resetToDefaults();
    setParameter(i, 458, 1.0f); // Cutoff
    // ADSR A=2, D=10, S=30, R=50 (approx scaled 0.0-1.0 or raw?)
    // Assuming 0-1 scale: 0.02, 0.1, 0.3, 0.5
    setParameter(i, 454, 0.02f);
    setParameter(i, 455, 0.1f);
    setParameter(i, 456, 0.3f);
    setParameter(i, 457, 0.5f);
    // Explicitly set bits/srate to full quality
    setParameter(i, 475, 0.0f); // Bits (0=Full)
    setParameter(i, 476, 0.0f); // Srate (0=Full)
  } else if (type == 3) {       // Granular
    mTracks[i].granularEngine.resetToDefaults();

    // Explicitly reset ALL parameters to match Engine defaults
    // This ensures that when "Restore Patch" is clicked, everything resets
    // and the UI (via getAllTrackParameters) reflects it.

    // Core (Window)
    setParameter(i, 400, 0.5f); // Position
    setParameter(i, 401, 1.0f); // Speed
    setParameter(i, 406, 0.2f); // Grain Size
    setParameter(i, 407, 0.5f); // Density
    setParameter(i, 415, 0.0f); // Spray
    setParameter(i, 429, 0.4f); // Gain

    // ADSR (Main) - 425-428
    setParameter(i, 425, 0.01f); // Attack (Main)
    setParameter(i, 426, 0.1f);  // Decay
    setParameter(i, 427, 1.0f);  // Sustain
    setParameter(i, 428, 0.2f);  // Release

    // Grain Envelope - 408-409 (Not typically exposed but good to reset)
    setParameter(i, 408, 0.5f);
    setParameter(i, 409, 0.5f);

    // Pitch & Mod
    setParameter(i, 410, 1.0f); // Pitch
    setParameter(i, 416, 0.0f); // Detune
    setParameter(i, 355, 0.0f); // Glide
    setParameter(i, 417, 0.0f); // Random Timing

    // Behavior
    setParameter(i, 420, 0.0f); // Reverse Prob
    setParameter(i, 419, 0.5f); // Width
    setParameter(i, 418, 0.2f); // Grain Count (approx 20)

    // LFOs (Reset all to 0/Basic)
    // LFO 1 (402-405)
    setParameter(i, 402, 0.0f);
    setParameter(i, 403, 0.1f);
    setParameter(i, 404, 0.0f);
    setParameter(i, 405, 0.0f);
    // LFO 2 (411-414)
    setParameter(i, 411, 0.0f);
    setParameter(i, 412, 0.1f);
    setParameter(i, 413, 0.0f);
    setParameter(i, 414, 0.0f);
    // LFO 3 (421-424)
    setParameter(i, 421, 0.0f);
    setParameter(i, 422, 0.1f);
    setParameter(i, 423, 0.0f);
    setParameter(i, 424, 0.0f);

    setParameter(i, 350, 1.0f);  // Use Env
  } else if (type == 2) {        // Sampler
    setParameter(i, 330, 0.0f);  // Start
    setParameter(i, 331, 1.0f);  // End
    setParameter(i, 300, 0.5f);  // Pitch (Normal / 50%)
    setParameter(i, 301, 0.25f); // Stretch (1.0x / 25%)
    setParameter(i, 302, 0.5f);  // Speed (1.0x)
    setParameter(i, 350, 1.0f);  // Use Envelope: True
    setParameter(i, 341, 0.5f);  // Speed UI Sync
    setParameter(i, 310, 0.01f); // Attack
    setParameter(i, 311, 0.5f);  // Decay
    setParameter(i, 312, 1.0f);  // Sustain
    setParameter(i, 313, 0.2f);  // Release
  } else {
    setParameter(i, 350, 1.0f); // Default Env usage True
  }

  clearSequencer(i);
}

void AudioEngine::setupTracks() {
  mTracks.reserve(8);
  for (int i = 0; i < 8; ++i) {
    mTracks.emplace_back();
    initTrack(i); // FORCE RESTORE PATCH STATE ON STARTUP
  }

  // Explicitly clear all sequencers and set default volumes/pan on setup
  for (int i = 0; i < 8; ++i) {
    initTrack(i);
  }
}

void AudioEngine::restoreTrackPreset(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < 8) {
    initTrack(trackIndex);
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

  // Fix for startup choppiness:
  // Exclusive mode often defaults to 1 burst, which is too aggressive during
  // app initialization jitter. We explicitly set it to 4 bursts (Quad
  // Buffering) for stability.
  int burstFrames = mStream->getFramesPerBurst();
  mStream->setBufferSizeInFrames(burstFrames * 4);

  mReverbFx.setSampleRate(mStream->getSampleRate());
  mSampleRate = mStream->getSampleRate();

  for (auto &t : mTracks) {
    t.subtractiveEngine.setSampleRate(mSampleRate);
    t.fmEngine.setSampleRate(mSampleRate);
    t.samplerEngine.setSampleRate(mSampleRate);
    t.granularEngine.setSampleRate(mSampleRate);
    t.wavetableEngine.setSampleRate(mSampleRate);
    t.fmDrumEngine.setSampleRate(mSampleRate);
    t.analogDrumEngine.setSampleRate(mSampleRate);
    t.soundFontEngine.setSampleRate(mSampleRate);
    t.audioInEngine.setSampleRate(mSampleRate);
    // t.svf.setParams removed as TSvf is not a member of Track
  }

  // Input stream for recording
  oboe::AudioStreamBuilder inBuilder;
  inBuilder.setDirection(oboe::Direction::Input)
      ->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(oboe::ChannelCount::Stereo) // Request Stereo
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)
      ->setInputPreset(oboe::InputPreset::Camcorder)
      ->setSampleRate(mStream->getSampleRate())
      ->setCallback(this);

  result = inBuilder.openStream(mInputStream);
  if (result != oboe::Result::OK) {
    LOGD("CRITICAL: Error opening input stream: %s",
         oboe::convertToText(result));
  } else {
    result = mInputStream->requestStart();
    if (result != oboe::Result::OK) {
      LOGD("CRITICAL: Error starting input stream: %s",
           oboe::convertToText(result));
    } else {
      LOGD("SUCCESS: Input stream started at %d Hz",
           mInputStream->getSampleRate());
    }
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
                                    bool punch, bool isArpTrigger) {
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

    // 1. Legato / Retrigger Check
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
          // Retriggering: Cut it off and restart.
          track.mActiveNotes[i].active = false;
          mGlobalVoiceCount--;
        }
      }
    }

    // 2. Setup Frequency & Track State
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

    // 3. Allocate Voice (under Global Cap)
    if (mGlobalVoiceCount < 64) {
      for (int i = 0; i < AudioEngine::Track::MAX_POLYPHONY; ++i) {
        if (!track.mActiveNotes[i].active) {
          track.mActiveNotes[i].active = true;
          track.mActiveNotes[i].note = note;
          mGlobalVoiceCount++;
          if (isSequencerTrigger) {
            float samplesPerStep = (15.0f * mSampleRate) / std::max(1.0f, mBpm);
            float trackSamplesPerStep =
                samplesPerStep / std::max(0.01f, track.mClockMultiplier);
            track.mActiveNotes[i].durationRemaining =
                trackSamplesPerStep * gate;
          } else {
            track.mActiveNotes[i].durationRemaining = 9999998.0f;
          }
          break;
        }
      }
    }

    // 4. Trigger Actual Synthesis Engines
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
    case 8: // AUDIO IN
      track.audioInEngine.triggerNote(note, velocity);
      break;
    case 9: // SOUNDFONT
      track.soundFontEngine.noteOn(note, velocity);
      break;
    }

    // 5. Recording Logic
    // Record if it's a manual tap OR an Arp trigger (but NOT a sequencer
    // playback trigger)
    bool isPhysicalNote = !isSequencerTrigger && !isArpTrigger;
    bool arpActive = track.arpeggiator.getMode() != ArpMode::OFF;

    if (mIsRecording && mIsPlaying && (!isSequencerTrigger || isArpTrigger)) {
      if (isPhysicalNote && arpActive) {
        // Do not record physical presses if Arp is handling them
      } else {
        double phase = (double)mSampleCount / (mSamplesPerStep + 0.001);
        int stepOffset = (phase > 0.5) ? 1 : 0;
        float subStep = static_cast<float>(phase);

        int currentStepIdx =
            (track.sequencer.getCurrentStepIndex() + stepOffset) %
            mPatternLength;

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
          int drumIdx = -1;
          if (note >= 60)
            drumIdx = note - 60;

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
}

// Internal Param Logic
void AudioEngine::setParameter(int trackIndex, int parameterId, float value) {
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  if (parameterId < 0 || parameterId >= 2500)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  // Update state (Base Value)
  mTracks[trackIndex].parameters[parameterId] = value;
  // Also update Applied Value so it takes effect immediately (until next step
  // reset)
  mTracks[trackIndex].appliedParameters[parameterId] = value;
  mTracks[trackIndex].mParametersDirty = true;

  // Push to engine
  updateEngineParameter(trackIndex, parameterId, value);
}

void AudioEngine::setParameterPreview(int trackIndex, int parameterId,
                                      float value) {
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  if (parameterId < 0 || parameterId >= 2500)
    return;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  // Update only Applied Value (Temporary sound change)
  mTracks[trackIndex].appliedParameters[parameterId] = value;
  updateEngineParameter(trackIndex, parameterId, value);
}

void AudioEngine::updateEngineParameter(int trackIndex, int parameterId,
                                        float value) {
  if (!std::isfinite(value))
    return;

  // Global Parameters (trackIndex = -1)
  if (trackIndex == -1) {
    // Global FX Mix Levels (3000+)
    if (parameterId >= 3000 && parameterId < 3015) {
      int fxIdx = parameterId - 3000;
      mFxMixLevels[fxIdx] = value;
      return;
    }

    if (parameterId == 2103) {
      mLpLfoL.setShape(value);
      mLpLfoR.setShape(value);
    }
    // Handle Global Effects & Arp (500-599) even if trackIndex is -1
    else if (parameterId >= 500 && parameterId < 600) {
      int fxId = (parameterId - 500) / 10;
      int subId = parameterId % 10;
      if (fxId == 0) { // Reverb
        if (subId == 0)
          mReverbFx.setSize(value);
        else if (subId == 1)
          mReverbFx.setDamping(value);
        else if (subId == 2)
          mReverbFx.setModDepth(value);
        else if (subId == 3) {
          mReverbFx.setMix(value);
          mFxMixLevels[6] = value;
        } else if (subId == 4)
          mReverbFx.setPreDelay(value);
        else if (subId == 5)
          mReverbFx.setType(static_cast<int>(value * 3.9f));
        else if (subId == 6)
          mReverbFx.setTone(value);
      }
      // Add other global FX here if they need trackIndex -1 support
    }
    return;
  }

  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return;
  if (parameterId < 0 || parameterId >= 2500)
    return;
  Track &track = mTracks[trackIndex];

  // Specific Logic for Global / Sends
  if (parameterId >= 2000) {
    // If it's 2103 but targeted at a track, we treat it as global for now
    // or ignore if it should only be truly global.
    // Given the UI sends -1 for 2103, the top block handles it.

    int fxIndex = (parameterId - 2000) / 10;
    if (fxIndex >= 0 && fxIndex < 15) {
      track.fxSends[fxIndex] = value;
    }
    return;
  }

  // Common Track Params (< 100)
  if (parameterId < 100) {
    switch (parameterId) {
    case 0:
      track.volume = std::max(0.001f, value);
      break;
    case 9:
      track.pan = std::clamp(value, 0.0f, 1.0f);
      {
        float angle = track.pan * (float)M_PI * 0.5f;
        track.panL = cosf(angle);
        track.panR = sinf(angle);
      }
      break;
    case 1: // Common Filter Cutoff
      track.subtractiveEngine.setCutoff(value);
      track.fmEngine.setFilter(value);
      track.samplerEngine.setFilterCutoff(value);
      track.wavetableEngine.setFilterCutoff(value);
      track.granularEngine.setParameter(1, value);
      track.soundFontEngine.setParameter(1, value);
      break;
    case 2: // Common Resonance
      track.subtractiveEngine.setResonance(value);
      track.fmEngine.setResonance(value);
      track.samplerEngine.setFilterResonance(value);
      track.wavetableEngine.setResonance(value);
      track.granularEngine.setParameter(2, value);
      track.soundFontEngine.setParameter(2, value);
      break;
    case 3: // Env Amount
      track.subtractiveEngine.setFilterEnvAmount(value);
      track.fmEngine.setParameter(3, value);
      track.soundFontEngine.setParameter(3, value);
      break;
    case 4:
      track.subtractiveEngine.setOscWaveform(1, value);
      break;
    case 5:
      track.subtractiveEngine.setOscVolume(0, std::max(0.001f, value));
      break;
    case 6:
      track.subtractiveEngine.setDetune(value);
      track.soundFontEngine.setParameter(6, value);
      break;
    case 7:
      track.subtractiveEngine.setLfoRate(value);
      track.soundFontEngine.setParameter(7, value);
      break;
    case 8:
      track.subtractiveEngine.setLfoDepth(value);
      track.soundFontEngine.setParameter(8, value);
      break;
    }
  }
  // ADSR / Internal Params (100-149)
  else if (parameterId >= 100 && parameterId < 150) {
    switch (parameterId) {
    case 123: // Audio In Filter Mode
      track.audioInEngine.setParameter(123, value);
      break;
    case 100:
      track.subtractiveEngine.setAttack(value);
      track.samplerEngine.setAttack(value);
      track.granularEngine.setAttack(value);
      track.wavetableEngine.setAttack(value);
      track.fmEngine.setParameter(100, value);
      track.audioInEngine.setParameter(100, value);
      track.soundFontEngine.setParameter(100, value);
      break;
    case 101:
      track.subtractiveEngine.setDecay(value);
      track.samplerEngine.setDecay(value);
      track.granularEngine.setDecay(value);
      track.wavetableEngine.setDecay(value);
      track.fmEngine.setParameter(101, value);
      track.audioInEngine.setParameter(101, value);
      track.soundFontEngine.setParameter(101, value);
      break;
    case 102:
      track.subtractiveEngine.setSustain(value);
      track.samplerEngine.setParameter(parameterId, value);
      track.granularEngine.setParameter(parameterId, value);
      track.fmEngine.setParameter(parameterId, value);
      track.wavetableEngine.setSustain(value);
      track.audioInEngine.setParameter(parameterId, value);
      track.soundFontEngine.setParameter(102, value);
      break;
    case 103:
      track.subtractiveEngine.setRelease(value);
      track.samplerEngine.setParameter(parameterId, value);
      track.granularEngine.setParameter(parameterId, value);
      track.fmEngine.setParameter(parameterId, value);
      track.wavetableEngine.setRelease(value);
      track.audioInEngine.setParameter(parameterId, value);
      track.soundFontEngine.setParameter(103, value);
      break;
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
      track.subtractiveEngine.setOscVolume(0, value);
      break;
    case 108:
      track.subtractiveEngine.setOscVolume(1, value);
      break;
    case 109:
      track.subtractiveEngine.setOscVolume(2, value);
      break;
    case 110:
      track.subtractiveEngine.setNoiseLevel(value);
      break;
    case 112:
    case 113:
    case 122: // Wavefold
      track.subtractiveEngine.setParameter(parameterId, value);
      track.samplerEngine.setParameter(parameterId, value);
      track.audioInEngine.setParameter(parameterId, value);
      track.soundFontEngine.setParameter(parameterId, value);
      break;
    case 118:
      track.subtractiveEngine.setFilterEnvAmount(value);
      track.samplerEngine.setFilterEnvAmount(value);
      track.audioInEngine.setParameter(118, value);
      break;
    case 114:
      track.subtractiveEngine.setFilterAttack(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    case 115:
      track.subtractiveEngine.setFilterDecay(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    case 116:
      track.subtractiveEngine.setFilterSustain(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    case 117:
      track.subtractiveEngine.setFilterRelease(value);
      track.samplerEngine.setParameter(parameterId, value);
      break;
    }
  }
  // Filter & Env (120-149)
  else if (parameterId >= 120 && parameterId < 150) {
    track.subtractiveEngine.setParameter(parameterId, value);
    track.samplerEngine.setParameter(parameterId, value);
    track.granularEngine.setParameter(parameterId, value);
    track.wavetableEngine.setParameter(parameterId, value);
    track.fmDrumEngine.setParameter(track.selectedFmDrumInstrument, parameterId,
                                    value);
    track.audioInEngine.setParameter(parameterId, value);
  }
  // FM / Sound Design (150-199)
  else if (parameterId >= 150 && parameterId < 200) {
    if (track.engineType == 0) { // Subtractive
      track.subtractiveEngine.setParameter(parameterId, value);
    } else if (track.engineType == 1) { // FM
      if (parameterId == 156) {
        track.fmEngine.setParameter(156, value); // Mode
      } else {
        track.fmEngine.setParameter(parameterId, value);
      }
    }
  }
  // FM Drum (200-299)
  else if (parameterId >= 200 && parameterId < 300) {
    track.fmDrumEngine.setParameter((parameterId - 200) / 10,
                                    (parameterId - 200) % 10, value);
  }
  // Sampler & Engine Sub-params (300-399)
  else if (parameterId >= 300 && parameterId < 400) {
    if (parameterId == 350) {
      track.subtractiveEngine.setUseEnvelope(value > 0.5f);
      track.fmEngine.setUseEnvelope(value > 0.5f);
      track.samplerEngine.setParameter(350, value);
      track.granularEngine.setParameter(350, value);
    } else if (parameterId == 355) {
      // User requested Curve: val * val * 0.3 (Max 0.3s)
      float glideVal = value * value * 0.3f;
      track.subtractiveEngine.setParameter(355, glideVal);
      track.fmEngine.setParameter(355, glideVal);
      track.samplerEngine.setParameter(355, glideVal);
      track.granularEngine.setParameter(355, glideVal);
      track.wavetableEngine.setParameter(355, glideVal);
      track.soundFontEngine.setParameter(355, glideVal);
    } else if (track.engineType == 5) {
      track.fmDrumEngine.setParameter(track.selectedFmDrumInstrument,
                                      parameterId - 300, value);
    } else {
      track.samplerEngine.setParameter(parameterId, value);
    }
  }
  // Granular (400-449)
  else if (parameterId >= 400 && parameterId < 450) {
    track.granularEngine.setParameter(parameterId, value);
  }
  // Wavetable (450-489)
  else if (parameterId >= 450 && parameterId < 490) {
    if (parameterId == 450)
      track.wavetableEngine.setParameter(0, value);
    else if (parameterId == 451)
      track.wavetableEngine.setParameter(1, value);
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
    else if (parameterId == 461)
      track.wavetableEngine.setParameter(11, value);
    else if (parameterId == 464)
      track.wavetableEngine.setParameter(14, value);
    else if (parameterId == 465)
      track.wavetableEngine.setParameter(15, value);
    else if (parameterId == 466)
      track.wavetableEngine.setParameter(16, value);
    else if (parameterId == 467)
      track.wavetableEngine.setParameter(17, value);
    else if (parameterId == 470) // Filter Mode (Added)
      track.wavetableEngine.setParameter(20, value);
    else if (parameterId == 471) // Filter Atk (Added)
      track.wavetableEngine.setParameter(21, value);
    else if (parameterId == 472) // Filter Dcy (Shared handle)
      track.wavetableEngine.setParameter(11, value);
    else if (parameterId == 473) // Filter Sus (Added)
      track.wavetableEngine.setParameter(23, value);
    else if (parameterId == 474) // Filter Rel (Added)
      track.wavetableEngine.setParameter(24, value);
    else if (parameterId == 475) // Bits (Moved from 530)
      track.wavetableEngine.setParameter(30, value);
    else if (parameterId == 476) // Srate (Moved from 531)
      track.wavetableEngine.setParameter(31, value);
  }
  // LP LFO (Pedal 10)
  else if (parameterId >= 490 && parameterId < 500) {
    int subId = parameterId % 10;
    if (subId == 0) {
      mLpLfoL.setRate(value);
      mLpLfoR.setRate(value);
    } else if (subId == 1) {
      mLpLfoL.setDepth(value);
      mLpLfoR.setDepth(value);
    } else if (subId == 2) {
      mLpLfoL.setShape(value);
      mLpLfoR.setShape(value);
    } else if (subId == 3) {
      mLpLfoL.setCutoff(value);
      mLpLfoR.setCutoff(value);
    } else if (subId == 4) {
      mLpLfoL.setResonance(value);
      mLpLfoR.setResonance(value);
    }
  }
  // Global Effects & Arp (500-599)
  else if (parameterId >= 500 && parameterId < 600) {
    int fxId = (parameterId - 500) / 10;
    int subId = parameterId % 10;
    switch (fxId) {
    case 0: // Reverb
      if (subId == 0)
        mReverbFx.setSize(value);
      else if (subId == 1)
        mReverbFx.setDamping(value);
      else if (subId == 2)
        mReverbFx.setModDepth(value);
      else if (subId == 3) {
        mReverbFx.setMix(value);
        mFxMixLevels[6] = value;
      } else if (subId == 4)
        mReverbFx.setPreDelay(value);
      else if (subId == 5)
        mReverbFx.setType(static_cast<int>(value * 3.9f));
      else if (subId == 6)
        mReverbFx.setTone(value);
      break;
    case 1: // Chorus
      if (subId == 0) {
        mChorusFxL.setRate(value);
        mChorusFxR.setRate(value);
      } else if (subId == 1) {
        mChorusFxL.setDepth(value);
        mChorusFxR.setDepth(value);
      } else if (subId == 2) {
        mChorusFxL.setMix(value);
        mChorusFxR.setMix(value);
        mFxMixLevels[2] = value;
      } else if (subId == 3) {
        mChorusFxL.setVoices(value);
        mChorusFxR.setVoices(value);
      }
      break;
    case 2: // Delay
      if (subId == 0)
        mDelayFx.setDelayTime(value);
      else if (subId == 1)
        mDelayFx.setFeedback(value);
      else if (subId == 2) {
        mDelayFx.setMix(value);
        mFxMixLevels[5] = value;
      } else if (subId == 3)
        mDelayFx.setFilterMix(value);
      else if (subId == 4)
        mDelayFx.setFilterResonance(value);
      else if (subId == 5)
        mDelayFx.setType(static_cast<int>(value * 3.9f));
      else if (subId == 6)
        mDelayFx.setFilterMode(static_cast<int>(value * 2.9f));
      break;
    case 3: // Bitcrusher
      if (subId == 0) {
        mBitcrusherFxL.setBits(value);
        mBitcrusherFxR.setBits(value);
      } else if (subId == 1) {
        mBitcrusherFxL.setRate(value);
        mBitcrusherFxR.setRate(value);
      } else if (subId == 2) {
        mBitcrusherFxL.setMix(value);
        mBitcrusherFxR.setMix(value);
        mFxMixLevels[1] = value;
      }
      break;
    case 4: // Overdrive
      if (subId == 0) {
        mOverdriveFxL.setDrive(value);
        mOverdriveFxR.setDrive(value);
      } else if (subId == 1) {
        // Repurposed MIX knob as DISTORTION
        mOverdriveFxL.setDistortion(value);
        mOverdriveFxR.setDistortion(value);
        // Ensure Mix is 1.0 internally
        mOverdriveFxL.setMix(1.0f);
        mOverdriveFxR.setMix(1.0f);
        // Send Level to mixer is handled by LEVEL knob?
        // Note: mFxMixLevels[0] was set by this knob (MIX).
        // Since we repurposed it, we'll set mix level to 1.0 fixed or
        // perhaps bind it to Level (SubId 2) if desired.
        // For now, let's just default it to 1.0 here to ensure sound passes.
        mFxMixLevels[0] = 1.0f;
      } else if (subId == 2) {
        mOverdriveFxL.setLevel(value);
        mOverdriveFxR.setLevel(value);
      } else if (subId == 3) {
        mOverdriveFxL.setTone(value);
        mOverdriveFxR.setTone(value);
      }
      break;
    case 5: // Phaser
      if (subId == 0) {
        mPhaserFxL.setRate(value);
        mPhaserFxR.setRate(value);
      } else if (subId == 1) {
        mPhaserFxL.setDepth(value);
        mPhaserFxR.setDepth(value);
      } else if (subId == 2) {
        mPhaserFxL.setMix(value);
        mPhaserFxR.setMix(value);
        mFxMixLevels[3] = value;
      } else if (subId == 3) {
        mPhaserFxL.setIntensity(value);
        mPhaserFxR.setIntensity(value);
      }
      break;
    case 6: // Tape Wobble
      if (subId == 0) {
        mTapeWobbleFx.setRate(value);
      } else if (subId == 1) {
        mTapeWobbleFx.setDepth(value);
      } else if (subId == 2) {
        mTapeWobbleFx.setSaturation(value);
      } else if (subId == 3) {
      } else if (subId == 3) {
        mTapeWobbleFx.setMix(value);
        mFxMixLevels[4] = value;
      }
      break;
    case 7: // Slicer
      if (subId == 0) {
        mSlicerFxL.setRate1(value);
        mSlicerFxR.setRate1(value);
      } else if (subId == 1) {
        mSlicerFxL.setRate2(value);
        mSlicerFxR.setRate2(value);
      } else if (subId == 2) {
        mSlicerFxL.setRate3(value);
        mSlicerFxR.setRate3(value);
      } else if (subId == 3) {
        bool v = (value > 0.5f);
        mSlicerFxL.setActive1(v);
        mSlicerFxR.setActive1(v);
      } else if (subId == 4) {
        bool v = (value > 0.5f);
        mSlicerFxL.setActive2(v);
        mSlicerFxR.setActive2(v);
      } else if (subId == 5) {
        bool v = (value > 0.5f);
        mSlicerFxL.setActive3(v);
        mSlicerFxR.setActive3(v);
      } else if (subId == 6) {
        mSlicerFxL.setDepth(value);
        mSlicerFxR.setDepth(value);
        mFxMixLevels[7] = value;
      }
      break;
    case 8: // Compressor
      if (subId == 0)
        mCompressorFx.setThreshold(value);
      else if (subId == 1)
        mCompressorFx.setRatio(value);
      else if (subId == 2)
        mCompressorFx.setAttack(value);
      else if (subId == 3)
        mCompressorFx.setRelease(value);
      else if (subId == 4)
        mCompressorFx.setMakeup(value);
      else if (subId == 5)
        mSidechainSourceTrack = static_cast<int>(value);
      else if (subId == 6)
        mSidechainSourceDrumIdx = static_cast<int>(value);
      break;
    case 9: // HP LFO (Pedal 9)
      if (subId == 0) {
        mHpLfoL.setRate(value);
        mHpLfoR.setRate(value);
      } else if (subId == 1) {
        mHpLfoL.setDepth(value);
        mHpLfoR.setDepth(value);
      } else if (subId == 2) {
        mHpLfoL.setShape(value);
        mHpLfoR.setShape(value);
      } else if (subId == 3) {
        mHpLfoL.setCutoff(value);
        mHpLfoR.setCutoff(value);
      } else if (subId == 4) {
        mHpLfoL.setResonance(value);
        mHpLfoR.setResonance(value);
      }
      break;
    }
  }
  // Analog Drum (600-699)
  else if (parameterId >= 600 && parameterId < 700) {
    int drumIdx = (parameterId - 600) / 10;
    int subId = (parameterId - 600) % 10;
    track.analogDrumEngine.setParameter(drumIdx, subId, value);
  }
  // Midi Channels (800-809)
  else if (parameterId >= 800 && parameterId < 810) {
    if (parameterId == 800)
      track.midiInChannel = static_cast<int>(value);
    else if (parameterId == 801)
      track.midiOutChannel = static_cast<int>(value);
  }
  // Extra Global FX (1500-1599)
  else if (parameterId >= 1500 && parameterId < 1600) {
    int fxId = (parameterId - 1500) / 10;
    int subId = parameterId % 10;
    switch (fxId) {
    case 0: // Flanger
      if (subId == 0) {
        mFlangerFxL.setRate(value);
        mFlangerFxR.setRate(value);
      } else if (subId == 1) {
        mFlangerFxL.setDepth(value);
        mFlangerFxR.setDepth(value);
      } else if (subId == 2) {
        mFlangerFxL.setMix(value);
        mFlangerFxR.setMix(value);
        mFxMixLevels[11] = value;
      } else if (subId == 3) {
        mFlangerFxL.setFeedback(value);
        mFlangerFxR.setFeedback(value);
      } else if (subId == 4) {
        float delay = value * 0.02f;
        mFlangerFxL.setDelay(delay);
        mFlangerFxR.setDelay(delay);
      }
      break;
    case 1: // TapeEcho
      if (subId == 0) {
        mTapeEchoFxL.setDelayTime(value);
        mTapeEchoFxR.setDelayTime(value);
      } else if (subId == 1) {
        mTapeEchoFxL.setFeedback(value);
        mTapeEchoFxR.setFeedback(value);
      } else if (subId == 2) {
        mTapeEchoFxL.setMix(value);
        mTapeEchoFxR.setMix(value);
        mFxMixLevels[13] = value;
      } else if (subId == 3) {
        mTapeEchoFxL.setDrive(value);
        mTapeEchoFxR.setDrive(value);
      } else if (subId == 4) {
        mTapeEchoFxL.setWow(value);
        mTapeEchoFxR.setWow(value);
      } else if (subId == 5) {
        mTapeEchoFxL.setFlutter(value);
        mTapeEchoFxR.setFlutter(value);
      }
      break;
    case 2: // Auto-Panner (formerly Spread)
      if (subId == 0)
        mAutoPannerFx.setPan(value);
      else if (subId == 1)
        mAutoPannerFx.setRate(value);
      else if (subId == 2)
        mAutoPannerFx.setDepth(value);
      else if (subId == 3) {
        mAutoPannerFx.setShape(value);
      } else if (subId == 4) {
        mAutoPannerFx.setMix(1.0f); // Always Wet Internal
        mFxMixLevels[12] = value;   // Global Return Level
      }
      break;
    case 3: // Octaver
      if (subId == 0) {
        mOctaverFxL.setMix(value);
        mOctaverFxR.setMix(value);
        mFxMixLevels[14] = value;
      } else if (subId == 1) {
        mOctaverFxL.setMode(value);
        mOctaverFxR.setMode(value);
      } else if (subId == 2) {
        mOctaverFxL.setUnison(value);
        mOctaverFxR.setUnison(value);
      } else if (subId == 3) {
        mOctaverFxL.setDetune(value);
        mOctaverFxR.setDetune(value);
      }
      break;
    }
  }
  // Global Auto-Panner (2100-2104)
  else if (parameterId >= 2100 && parameterId < 2105) {
    int subId = parameterId % 10;
    if (subId == 0)
      mAutoPannerFx.setPan(value);
    else if (subId == 1)
      mAutoPannerFx.setRate(value);
    else if (subId == 2)
      mAutoPannerFx.setDepth(value);
    else if (subId == 3)
      mAutoPannerFx.setShape(value);
    else if (subId == 4) {
      mAutoPannerFx.setMix(1.0f); // Always wet internally
      mFxMixLevels[12] = value;   // Global Return Level
    }
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
          mGlobalVoiceCount--;
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
      track.analogDrumEngine.releaseNote(note);
      track.audioInEngine.releaseNote(note);
      track.soundFontEngine.noteOff(note);
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
    float *input = static_cast<float *>(audioData);
    int channels = audioStream->getChannelCount();
    for (int i = 0; i < numFrames; ++i) {
      float combined = 0.0f;
      if (channels == 2) {
        combined = (input[i * 2] + input[i * 2 + 1]) * 0.5f;
      } else {
        combined = input[i];
      }
      mInputRingBuffer[mInputWritePtr % 8192] = combined;
      mInputWritePtr++;
    }

    // If resampling is active, ignore microphone input
    if (mIsResampling) {
      return oboe::DataCallbackResult::Continue;
    }

    if (mIsRecordingSample && mRecordingTrackIndex != -1) {
      auto &track = mTracks[mRecordingTrackIndex];
      float *input = static_cast<float *>(audioData);
      int channels = audioStream->getChannelCount();

      for (int i = 0; i < numFrames; ++i) {
        float sampleToPush = 0.0f;
        if (channels == 2) {
          sampleToPush = (input[i * 2] + input[i * 2 + 1]) * 0.5f;
        } else {
          sampleToPush = input[i];
        }

        if (track.engineType == 2)
          track.samplerEngine.pushSample(sampleToPush);
        else if (track.engineType == 3)
          track.granularEngine.pushSample(sampleToPush);
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
  // Safety: Prevent near-infinite loops if BPM is crazy high
  if (samplesPerStep < 10.0f)
    samplesPerStep = 10.0f;
  mSamplesPerStep = samplesPerStep;

  // Process UI Commands safely before block loop
  // Process UI Commands safely before block loop
  processCommands();

  for (int frameIdx = 0; frameIdx < numFrames; frameIdx += kBlockSize) {
    int framesToDo = std::min(kBlockSize, numFrames - frameIdx);

    // Unified Processing Block (Control + Audio + NoteOff)
    {
      std::lock_guard<std::recursive_mutex> lock(mLock);
      // REMOVED: Locking here causes audio dropouts if UI thread holds lock.
      // Audio thread should run free. We rely on atomics/race-tolerance for
      // params. Critical ops (like vector resize) should be guarded, but we
      // don't resize tracks in realtime.

      mSampleCount += framesToDo;
      while (mSampleCount >= samplesPerStep && samplesPerStep > 0.0f) {
        mSampleCount -= samplesPerStep;
        if (mIsPlaying)
          mGlobalStepIndex = (mGlobalStepIndex + 1) % mPatternLength;
      }

      for (int t = 0; t < (int)mTracks.size(); ++t) {
        Track &track = mTracks[t];
        float effectiveMultiplier = track.mClockMultiplier;
        if (track.mArpTriplet && track.arpeggiator.getMode() != ArpMode::OFF) {
          effectiveMultiplier *= 1.5f;
        }

        float trackSamplesPerStep =
            samplesPerStep / std::max(0.01f, effectiveMultiplier);

        if (trackSamplesPerStep < 2400.0f)
          trackSamplesPerStep = 2400.0f;

        if (mIsPlaying && trackSamplesPerStep > 0) {
          track.mStepCountdown -= framesToDo;
          int safetyCounter = 0;
          while (track.mStepCountdown <= 0 && safetyCounter < 4) {
            safetyCounter++;
            track.mStepCountdown += trackSamplesPerStep;

            track.sequencer.advance();
            int seqStep = track.sequencer.getCurrentStepIndex();
            track.mInternalStepIndex = seqStep;

            // Restoration: Revert ANY P-locks from the PREVIOUS step to their
            // base values before applying logic for THIS step.
            for (int p = 0; p < 2500; ++p) {
              if (std::abs(track.appliedParameters[p] - track.parameters[p]) >
                  0.0001f) {
                track.appliedParameters[p] = track.parameters[p];
                updateEngineParameter(t, p, track.parameters[p]);
              }
            }

            const std::vector<Step> &steps = track.sequencer.getSteps();
            if (seqStep < steps.size()) {
              const Step &s = steps[seqStep];
              if (s.active) {
                if (s.probability >= 1.0f || gRng.next() <= s.probability) {
                  for (const auto &ni : s.notes) {
                    double delayedSamples =
                        ni.subStepOffset * trackSamplesPerStep +
                        track.mStepCountdown;
                    float ratchetedGate =
                        s.gate / static_cast<float>(s.ratchet);

                    if (delayedSamples <= 1.0) {
                      triggerNoteLocked(t, ni.note, ni.velocity * 127, true,
                                        ratchetedGate);
                    } else {
                      track.mPendingNotes.push_back(
                          {ni.note, ni.velocity * 127.0f, delayedSamples,
                           ratchetedGate, 1});
                    }

                    if (s.ratchet > 1) {
                      float ratchetInterval =
                          trackSamplesPerStep / (float)s.ratchet;
                      for (int r = 1; r < s.ratchet; ++r) {
                        double rDelay = delayedSamples + (r * ratchetInterval);
                        track.mPendingNotes.push_back(
                            {ni.note, ni.velocity * 127.0f, rDelay,
                             ratchetedGate, 1});
                      }
                    }
                  }
                  for (auto const &[pid, val] : s.parameterLocks) {
                    track.appliedParameters[pid] = val;
                    updateEngineParameter(t, pid, val);
                  }
                }
              }
            }

            // Drum Sequencer
            bool isSamplerChops = (track.engineType == 2 &&
                                   track.samplerEngine.getPlayMode() >= 3);
            if (track.engineType == 5 || track.engineType == 6 ||
                isSamplerChops) {
              for (int d = 0; d < 16; ++d) {
                track.drumSequencers[d].advance();
                int drumStep = track.drumSequencers[d].getCurrentStepIndex();
                const std::vector<Step> &dSteps =
                    track.drumSequencers[d].getSteps();
                if (drumStep < dSteps.size()) {
                  const Step &ds = dSteps[drumStep];
                  if (ds.active) {
                    if (ds.probability >= 1.0f ||
                        gRng.next() <= ds.probability) {
                      for (const auto &ni : ds.notes) {
                        double delayedSamples =
                            ni.subStepOffset * trackSamplesPerStep +
                            track.mStepCountdown;
                        float ratchetedGate =
                            ds.gate / static_cast<float>(ds.ratchet);

                        if (delayedSamples <= 1.0) {
                          triggerNoteLocked(t, ni.note, ni.velocity * 127, true,
                                            ratchetedGate, ds.punch);
                        } else {
                          track.mPendingNotes.push_back(
                              {ni.note, ni.velocity * 127.0f, delayedSamples,
                               ratchetedGate, 1, ds.punch});
                        }
                        if (ds.ratchet > 1) {
                          float ratchetInterval =
                              trackSamplesPerStep / (float)ds.ratchet;
                          for (int r = 1; r < ds.ratchet; ++r) {
                            double rDelay =
                                delayedSamples + (r * ratchetInterval);
                            track.mPendingNotes.push_back(
                                {ni.note, ni.velocity * 127.0f, rDelay,
                                 ratchetedGate, 1, ds.punch});
                          }
                        }
                      }
                      for (auto const &[pid, val] : ds.parameterLocks) {
                        track.appliedParameters[pid] =
                            val; // SYNC WITH RESTORATION LOOP
                        updateEngineParameter(t, pid, val);
                      }
                    }
                  }
                }
              }
            }
          }
          if (track.mStepCountdown <= 0)
            track.mStepCountdown = trackSamplesPerStep;
        }

        // Arp Clock
        if (track.arpeggiator.getMode() != ArpMode::OFF) {
          float arpSamplesPerStep =
              samplesPerStep * std::max(0.125f, track.mArpRate);
          if (track.mArpDivisionMode == 1)
            arpSamplesPerStep *= 1.5f;
          else if (track.mArpDivisionMode == 2)
            arpSamplesPerStep *= 0.66667f;

          track.mArpCountdown -= framesToDo;
          int asafety = 0;
          while (track.mArpCountdown <= 0 && asafety < 8) {
            asafety++;
            track.mArpCountdown += arpSamplesPerStep;
            std::vector<int> arpNotes = track.arpeggiator.nextNotes();
            for (int arpNote : arpNotes) {
              if (arpNote >= 0)
                triggerNoteLocked(t, arpNote, 100, true, 0.5f, false, true);
            }
          }
          if (track.mArpCountdown <= 0)
            track.mArpCountdown = arpSamplesPerStep;
        }

        // Process Pending
        for (auto it = track.mPendingNotes.begin();
             it != track.mPendingNotes.end();) {
          it->samplesRemaining -= framesToDo;
          if (it->samplesRemaining <= 0) {
            triggerNoteLocked(t, it->note, (int)it->velocity, true, it->gate,
                              it->punch);
            it = track.mPendingNotes.erase(it);
          } else {
            ++it;
          }
        }

        // Note Offs
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

      // Audio Block Rendering
      renderStereo(&output[frameIdx * numChannels], framesToDo);
    }

    // Push to resampling recorder (Sampler/Granular)
    bool isStillRecording = mIsRecordingSample;
    if (mIsResampling && isStillRecording && mRecordingTrackIndex != -1) {
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

    LOGD("AudioEngine Stats: ActiveTracks=%d, MasterVol=%.2f, "
         "SampleRate=%.1f, "
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
                          float gate, bool isSkipped) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    Step step;
    step.isSkipped = isSkipped;
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
    // ONLY apply drum mapping if this is explicitly a drum-capable track
    // (Drum Engine or Sampler Chops)
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

void AudioEngine::setTrackPan(int trackIndex, float pan) {
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    std::lock_guard<std::recursive_mutex> lock(mLock);
    mTracks[trackIndex].pan = pan;
    mTracks[trackIndex].smoothedPan = pan;

    // Fix: Immediately update coefficients for startup/sync
    float angle = pan * (float)M_PI * 0.5f;
    mTracks[trackIndex].panL = cosf(angle);
    mTracks[trackIndex].panR = sinf(angle);
  }
}

void AudioEngine::setSequencerConfig(int trackIndex, int numPages,
                                     int stepsPerPage) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setConfiguration(numPages, stepsPerPage);
    if (mTracks[trackIndex].engineType == 5 ||
        mTracks[trackIndex].engineType == 6) {
      for (int i = 0; i < 16; ++i) {
        mTracks[trackIndex].drumSequencers[i].setConfiguration(numPages,
                                                               stepsPerPage);
      }
    }
  }
}

void AudioEngine::setTempo(float bpm) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  // Safety clamp BPM to reasonable musical range
  if (!std::isfinite(bpm))
    return;
  mBpm = std::max(1.0f, std::min(999.0f, bpm));
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
      track.soundFontEngine.allNotesOff();
    }
  } else {
    for (auto &track : mTracks) {
      track.mInternalStepIndex = 0;
      track.mStepCountdown = 0.0;
      track.mPendingNotes.clear();
    }
    // Clear Global FX Buffers on Start to prevent noise burst
    mDelayFx.clear();
    mLpLfoL.setDepth(0.0f);
    mLpLfoR.setDepth(0.0f);
    mHpLfoL.setDepth(0.0f);
    mHpLfoR.setDepth(0.0f);
    mLpLfoL.setCutoff(1.0f);
    mLpLfoR.setCutoff(1.0f);
    mHpLfoL.setCutoff(0.0f);
    mHpLfoR.setCutoff(0.0f);
    mReverbFx.clear();
    mTapeWobbleFx.clear();
    mPhaserFxL.clear();
    mPhaserFxR.clear();
    mChorusFxL.clear();
    mChorusFxR.clear();
    mFlangerFxL.clear();
    mFlangerFxR.clear();
    mHpLfoL.reset(mSampleRate);
    mHpLfoR.reset(mSampleRate);
    mLpLfoL.reset(mSampleRate);
    mLpLfoR.reset(mSampleRate);
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
  mPatternLength = (length <= 0) ? 1 : (length > 64 ? 64 : length);

  // Propagate to all track sequencers
  int pages = (mPatternLength + 15) / 16;
  for (int i = 0; i < (int)mTracks.size(); ++i) {
    setSequencerConfig(i, pages, 16);
  }

  if (mGlobalStepIndex >= mPatternLength) {
    mGlobalStepIndex = 0;
  }
}

void AudioEngine::setPlaybackDirection(int trackIndex, int direction) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setPlaybackDirection(direction);
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].setPlaybackDirection(direction);
    }
  }
}

void AudioEngine::setIsRandomOrder(int trackIndex, bool isRandom) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].sequencer.setIsRandomOrder(isRandom);
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].setIsRandomOrder(isRandom);
    }
  }
}

void AudioEngine::setIsJumpMode(int trackIndex, bool isJump) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].sequencer.setIsJumpMode(isJump);
    for (int i = 0; i < 16; ++i) {
      mTracks[trackIndex].drumSequencers[i].setIsJumpMode(isJump);
    }
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
  // Logic: Iterate all active routes, calculate source value, update dest
  // Logic: Iterate all active routes, calculate source value, update dest
  // param (NON-destructively to state)

  // 1. Snapshot Mod Sources (LFOs, Macros)
  // LFOs are updated in renderStereo loop. Using last known value is fine for
  // control rate. Or we can snapshot them here if they are running. Macros
  // are stored in mMacros[].

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
      case ModSource::LFO6:
        srcValue = mLfos[5].getCurrentValue();
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
          mod.destParamId >= 0 && mod.destParamId < 1024) {
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
    track.mStepCountdown = 0.0; // Force immediate trigger on next block
    track.sequencer.jumpToStep(mGlobalStepIndex);
    for (int i = 0; i < 16; ++i) {
      track.drumSequencers[i].jumpToStep(mGlobalStepIndex);
    }
  }
  mSampleCount = mSamplesPerStep;
}

int AudioEngine::getCurrentStep(int trackIndex, int drumIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    if (drumIndex >= 0 && drumIndex < 16) {
      return mTracks[trackIndex]
          .drumSequencers[drumIndex]
          .getCurrentStepIndex();
    }
    return mTracks[trackIndex].sequencer.getCurrentStepIndex();
  }
  return 0;
}

void AudioEngine::setArpConfig(int trackIndex, int mode, int octaves,
                               int inversion, bool isLatched, bool isMutated,
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
    track.arpeggiator.setIsMutated(isMutated);
    track.arpeggiator.setRhythm(rhythms);
    track.arpeggiator.setRandomSequence(sequence);
  }
}

void AudioEngine::setChordProgConfig(int trackIndex, bool enabled, int mood,
                                     int complexity) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mTracks[trackIndex].arpeggiator.setChordProgConfig(enabled, mood,
                                                       complexity);
  }
}

void AudioEngine::setScaleConfig(int rootNote,
                                 const std::vector<int> &intervals) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  for (auto &track : mTracks) {
    track.arpeggiator.setScaleConfig(rootNote, intervals);
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
    } else if (track.engineType == 4) { // Wavetable
      track.wavetableEngine.loadWavetable(data);
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

std::vector<float> AudioEngine::getRecordedSampleData(int trackIndex,
                                                      float targetSampleRate) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex < 0 || trackIndex >= mTracks.size())
    return {};

  auto &track = mTracks[trackIndex];
  const std::vector<float> *source = nullptr;

  if (track.engineType == 2) {
    source = &track.samplerEngine.getSampleData();
  } else if (track.engineType == 3) {
    source = &track.granularEngine.getSampleData();
  }

  if (!source || source->empty())
    return {};

  float sourceRate = static_cast<float>(mSampleRate);
  if (sourceRate <= 0)
    sourceRate = 48000.0f;

  if (std::abs(sourceRate - targetSampleRate) < 1.0f) {
    return *source; // No resampling needed
  }

  double ratio = static_cast<double>(sourceRate) / targetSampleRate;
  size_t targetSize = static_cast<size_t>(source->size() / ratio);
  std::vector<float> result(targetSize);

  for (size_t i = 0; i < targetSize; ++i) {
    double pos = i * ratio;
    int idx = static_cast<int>(pos);
    float frac = static_cast<float>(pos - idx);

    // Get 4 points for cubic interpolation
    float y0 = source->at(std::max(0, idx - 1));
    float y1 = source->at(idx);
    float y2 = source->at(std::min((int)source->size() - 1, idx + 1));
    float y3 = source->at(std::min((int)source->size() - 1, idx + 2));

    result[i] = cubicInterpolation(y0, y1, y2, y3, frac);
  }

  return result;
}

void AudioEngine::startRecordingSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < mTracks.size()) {
    mIsRecordingSample = true;
    mIsRecordingLocked = false; // Reset lock on new recording start
    mRecordingTrackIndex = trackIndex;
    if (mTracks[trackIndex].engineType == 2)
      mTracks[trackIndex].samplerEngine.clearBuffer();
    else if (mTracks[trackIndex].engineType == 3) // Granular
      mTracks[trackIndex].granularEngine.clearSource();
  }
}

void AudioEngine::stopRecordingSample(int trackIndex) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (!mIsRecordingLocked) {
    mIsRecordingSample = false;
    mRecordingTrackIndex = -1;
  }
}

void AudioEngine::setRecordingLocked(bool locked) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  mIsRecordingLocked = locked;
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

void AudioEngine::setSoundFontMapping(int trackIndex, int knobIndex,
                                      int paramId) {
  if (trackIndex >= 0 && trackIndex < 8) {
    mTracks[trackIndex].soundFontEngine.setMapping(knobIndex, paramId);
  }
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
    track.mSilenceFrames = 0;
    // Release all notes in the engine
    track.subtractiveEngine.allNotesOff();
    track.fmEngine.allNotesOff();
    track.fmDrumEngine.allNotesOff();
    track.analogDrumEngine.allNotesOff();
    track.wavetableEngine.allNotesOff();
    track.samplerEngine.allNotesOff();
    track.granularEngine.allNotesOff();
    track.soundFontEngine.allNotesOff();

    for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
      track.mActiveNotes[v].active = false;
    }
  }
}

int AudioEngine::getActiveNoteMask(int trackIndex) {
  if (trackIndex < 0 || trackIndex >= 8)
    return 0;
  std::lock_guard<std::recursive_mutex> lock(mLock);
  int mask = 0;
  auto &track = mTracks[trackIndex];
  for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
    if (track.mActiveNotes[v].active) {
      int note = track.mActiveNotes[v].note;
      // We only care about notes 0..31 for simple 4x4 or 6x6 highlights
      // mapped relative to the view. For now, bitset simple 32 notes.
      if (note >= 60 && note < 92) {
        mask |= (1 << (note - 60));
      }
    }
  }
  return mask;
}

// ... COPY OF OTHER METHODS ...
// (Omitting small getters for brevity, but they follow the pattern)
// setGenericLfoParam, setMacroValue, setFxChain etc.

void AudioEngine::setGenericLfoParam(int lfoIndex, int paramId, float value) {
  if (lfoIndex < 0 || lfoIndex >= 6)
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
  LOGD("AudioEngine::onErrorAfterClose() called. Result: %d",
       static_cast<int>(result));
  if (result == oboe::Result::ErrorDisconnected ||
      result == oboe::Result::ErrorInvalidState ||
      result == oboe::Result::ErrorUnavailable) {
    LOGD("Restarting audio stream...");
    start();
  }
}

void AudioEngine::setFilterMode(int trackIndex, int mode) {
  if (trackIndex >= 0 && trackIndex < 8) {
    if (mTracks[trackIndex].engineType == 0) { // Subtractive
      mTracks[trackIndex].subtractiveEngine.setFilterMode(mode);
    }
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

void AudioEngine::setTrackActive(int trackIndex, bool active) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    mTracks[trackIndex].isActive = active;
  }
}

void AudioEngine::restorePresets() {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  for (auto &track : mTracks) {
    track.volume = 0.8f;
    track.subtractiveEngine.resetToDefaults();
    track.fmEngine.resetToDefaults();
    track.fmDrumEngine.resetToDefaults();
    track.analogDrumEngine.resetToDefaults();
    track.samplerEngine.resetToDefaults();
    track.granularEngine.resetToDefaults();
    track.wavetableEngine.resetToDefaults();

    // CRITICAL: Also clear the parameter buffers so UI and Engine stay in
    // sync We set meaningful defaults so the UI knobs show the correct
    // initial values
    std::fill(std::begin(track.parameters), std::end(track.parameters), 0.0f);
    std::fill(std::begin(track.appliedParameters),
              std::end(track.appliedParameters), 0.0f);

    // Common EG Defaults
    track.parameters[100] = 0.01f; // Attack
    track.parameters[101] = 0.1f;  // Decay
    track.parameters[102] = 0.8f;  // Sustain
    track.parameters[103] = 0.5f;  // Release

    // Common Filter Defaults
    track.parameters[112] = 0.5f; // Cutoff
    track.parameters[113] = 0.0f; // Resonance

    // FM Specific Defaults
    track.parameters[150] = 0.0f;  // Algorithm 0
    track.parameters[153] = 1.0f;  // Carrier Mask (Op 1)
    track.parameters[155] = 63.0f; // Active Mask (All 6 Ops)
    track.parameters[157] = 0.5f;  // Brightness (1.0 in engine)

    // Sampler Defaults
    track.parameters[302] = 0.5f; // Speed 1.0x
    track.parameters[320] = 0.0f; // OneShot mode
    track.parameters[340] = 0.0f; // 2 slices

    // Wavetable Defaults
    track.parameters[450] = 0.0f; // Position
    track.parameters[451] = 0.0f; // Morph
  }
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
  // Lock handled by onAudioReady caller
  // Master volume and safety
  if (!std::isfinite(mMasterVolume))
    mMasterVolume = 0.5f;

  float sampleRate = static_cast<float>(mSampleRate);

  // --- Block Rate Control Updates ---
  for (int l = 0; l < 6; ++l) {
    mLfos[l].process(sampleRate,
                     1); // Only 1 "sample" of progression per buffer cycle
  }
  for (int m = 0; m < 6; ++m) {
    if (mMacros[m].sourceType == 3) { // LFO
      int lfoIdx = mMacros[m].sourceIndex;
      if (lfoIdx >= 0 && lfoIdx < 6) {
        float val = (mLfos[lfoIdx].getCurrentValue() + 1.0f) * 0.5f;
        mMacros[m].value = std::clamp(val, 0.0f, 1.0f);
      }
    }
  }
  applyModulations();
  // ----------------------------------

  for (int i = 0; i < numFrames; ++i) {

    // Safe ring-buffer read with 2048 samples of latency for stability
    uint32_t writePos = mInputWritePtr.load();
    int32_t distance = static_cast<int32_t>(writePos - mInputReadPtr);
    if (distance < 128 || distance > 8000) {
      mInputReadPtr = writePos - 2048; // Resync if definitely out of bounds
    }
    float inputSample = mInputRingBuffer[mInputReadPtr % 8192];
    mInputReadPtr++;

    float mixedSampleL = 0.0f;
    float mixedSampleR = 0.0f;
    float sidechainSignal = 0.0f;
    float fxBusesL[15];
    float fxBusesR[15];
    for (int b = 0; b < 15; ++b) {
      // Load feedback from previous sample (Backward Chaining)
      fxBusesL[b] = mFxFeedbacksL[b];
      fxBusesR[b] = mFxFeedbacksR[b];
      // Clear for next accumulation
      mFxFeedbacksL[b] = 0.0f;
      mFxFeedbacksR[b] = 0.0f;
    }

    for (int t = 0; t < (int)mTracks.size(); ++t) {
      Track &track = mTracks[t];
      track.gainReduction = 1.0f; // Reset per frame

      if (!track.isActive && track.mSilenceFrames > 2400) { // 50ms at 48k
        track.follower.process(0.0f);
        continue;
      }

      float rawSampleL = 0.0f, rawSampleR = 0.0f;
      switch (track.engineType) {
      case 0:
        rawSampleL = rawSampleR = track.subtractiveEngine.render();
        break;
      case 1:
        rawSampleL = rawSampleR = track.fmEngine.render();
        break;
      case 2:
        rawSampleL = rawSampleR = track.samplerEngine.render();
        break;
      case 3:
        track.granularEngine.render(&rawSampleL, &rawSampleR);
        break;
      case 4:
        rawSampleL = rawSampleR = track.wavetableEngine.render();
        break;
      case 5:
        rawSampleL = rawSampleR = track.fmDrumEngine.render();
        break;
      case 6:
        rawSampleL = rawSampleR = track.analogDrumEngine.render();
        break;
      case 8: // AUDIO IN
        rawSampleL = rawSampleR = track.audioInEngine.render(inputSample);
        break;
      case 9: // SOUNDFONT
        track.soundFontEngine.render(&rawSampleL, &rawSampleR, 1);
        break;
      }

      if (!std::isfinite(rawSampleL))
        rawSampleL = 0.0f;
      if (!std::isfinite(rawSampleR))
        rawSampleR = 0.0f;

      float monoSum = (rawSampleL + rawSampleR) * 0.5f;

      // Silence Detection (Tightened)
      if (std::abs(monoSum) < 0.0001f) {
        track.mSilenceFrames++;
        if (track.mSilenceFrames > 2400) {
          bool activeVoices = false;
          for (int v = 0; v < Track::MAX_POLYPHONY; ++v) {
            if (track.mActiveNotes[v].active) {
              activeVoices = true;
              break;
            }
          }
          if (track.mPhysicallyHeldNoteCount == 0 && !activeVoices) {
            track.isActive = false;
            track.mSilenceFrames = 0;
          }
        }
      } else {
        track.mSilenceFrames = 0;
      }

      float panVal = track.pan;
      if (std::abs(panVal - track.smoothedPan) > 0.0001f) {
        track.smoothedPan += 0.005f * (panVal - track.smoothedPan);
        // Update cached pan coefficients only when smoothed pan changes
        float angle = track.smoothedPan * (float)M_PI * 0.5f;
        track.panL = cosf(angle);
        track.panR = sinf(angle);
      }

      if (std::abs(track.volume - track.smoothedVolume) > 0.0001f) {
        track.smoothedVolume += 0.01f * (track.volume - track.smoothedVolume);
      }

      float finalVol = track.smoothedVolume * track.gainReduction;

      // Special Handling for AutoPanner (Bus 12)
      // Acts as a "Routing" knob: 0% = Main Mix, 100% = AutoPanner Bus
      // This prevents phase cancellation by removing the dry signal as it
      // enters the Panner.
      float pannerSend = track.smoothedFxSends[12];
      float dryScale = 1.0f - pannerSend;
      if (dryScale < 0.0f)
        dryScale = 0.0f;

      float trackOutputL = rawSampleL * finalVol * dryScale;
      float trackOutputR = rawSampleR * finalVol * dryScale;

      // Pre-Fader Signal calculation for Sands (allows Dry Kill)
      // We apply Gain Reduction and Punch, but NOT Track Volume
      float preFaderL = rawSampleL * track.gainReduction;
      float preFaderR = rawSampleR * track.gainReduction;

      if (track.mPunchCounter > 0) {
        // Fast approximation for punch boost (applied to both Main and Sends)
        float punchScale = 1.5f;
        trackOutputL *= punchScale;
        trackOutputR *= punchScale;
        preFaderL *= punchScale;
        preFaderR *= punchScale;
        track.mPunchCounter--;
      }

      // Soft clip only if likely to peak
      if (std::abs(trackOutputL) > 0.8f)
        trackOutputL = fast_tanh(trackOutputL);
      if (std::abs(trackOutputR) > 0.8f)
        trackOutputR = fast_tanh(trackOutputR);

      mixedSampleL += trackOutputL * 0.35f * track.panL;
      mixedSampleR += trackOutputR * 0.35f * track.panR;

      // Sidechain uses mono sum for detection
      if (mSidechainSourceTrack >= 0 &&
          &track == &mTracks[mSidechainSourceTrack % 8]) {
        sidechainSignal = monoSum;
      }

      for (int f = 0; f < 15; ++f) {
        if (track.fxSends[f] > 0.001f || track.smoothedFxSends[f] > 0.001f) {
          track.smoothedFxSends[f] +=
              0.01f * (track.fxSends[f] - track.smoothedFxSends[f]);

          // Use Pre-Fader signal so Volume slider acts as Dry Level
          fxBusesL[f] += preFaderL * track.smoothedFxSends[f];
          fxBusesR[f] += preFaderR * track.smoothedFxSends[f];
        }
      }
      track.follower.process(monoSum);
    }

    float currentSampleL = mixedSampleL;
    float currentSampleR = mixedSampleR;
    float wetSampleL = 0.0f;
    float wetSampleR = 0.0f;
    float spreadL = 0.0f, spreadR = 0.0f;

    auto routeFx = [&](int index, float valL, float valR,
                       bool isDelta = false) {
      int dest = mFxChainDest[index];
      float outL, outR;

      // Calculate Full Wet Output (Recover from Delta if needed)
      if (isDelta) {
        outL = fxBusesL[index] + valL;
        outR = fxBusesR[index] + valR;
      } else {
        outL = valL;
        outR = valR;
      }

      if (dest >= 0 && dest < 15) {
        // Serial Chaining
        // Determine Direction based on Hardcoded Execution Order
        // IDs: 0, 1, 9, 10, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13, 14
        const int order[15] = {0,  1, 4, 5,  6,  7,  8, 9,
                               10, 2, 3, 11, 12, 13, 14};
        bool isForward = order[dest] > order[index];

        if (isForward) {
          fxBusesL[dest] += outL; // Pass Full Signal (Unity Gain)
          fxBusesR[dest] += outR;
        } else {
          // Backward Chain -> Use Feedback Buffer for Next Sample
          mFxFeedbacksL[dest] += outL;
          mFxFeedbacksR[dest] += outR;
        }
      } else {
        // Main Mix: Add to accumulator
        wetSampleL += outL;
        wetSampleR += outR;
      }
    };

    // Serial Chain Processing - Skip if bus is idle
    if (std::abs(fxBusesL[0]) > 0.00001f || std::abs(fxBusesR[0]) > 0.00001f)
      routeFx(0, mOverdriveFxL.process(fxBusesL[0]),
              mOverdriveFxR.process(fxBusesR[0]), true); // Delta

    if (std::abs(fxBusesL[1]) > 0.00001f || std::abs(fxBusesR[1]) > 0.00001f)
      routeFx(1, mBitcrusherFxL.process(fxBusesL[1]),
              mBitcrusherFxR.process(fxBusesR[1]), true); // Delta

    if (std::abs(fxBusesL[9]) > 0.00001f || std::abs(fxBusesR[9]) > 0.00001f) {
      float hpL = mHpLfoL.process(fxBusesL[9], sampleRate);
      mHpLfoR.syncFrom(mHpLfoL);
      float hpR = mHpLfoR.process(fxBusesR[9], sampleRate);
      routeFx(9, hpL, hpR);
    }

    if (std::abs(fxBusesL[10]) > 0.00001f ||
        std::abs(fxBusesR[10]) > 0.00001f) {
      float lpL = mLpLfoL.process(fxBusesL[10], sampleRate);
      mLpLfoR.syncFrom(mLpLfoL); // KILL PHASE SWIRL
      float lpR = mLpLfoR.process(fxBusesR[10], sampleRate);
      routeFx(10, lpL, lpR);
    }

    if (std::abs(fxBusesL[2]) > 0.00001f || std::abs(fxBusesR[2]) > 0.00001f) {
      routeFx(2, mChorusFxL.process(fxBusesL[2], sampleRate),
              mChorusFxR.process(fxBusesR[2], sampleRate));
    }

    if (std::abs(fxBusesL[3]) > 0.00001f || std::abs(fxBusesR[3]) > 0.00001f) {
      routeFx(3, mPhaserFxL.process(fxBusesL[3], sampleRate),
              mPhaserFxR.process(fxBusesR[3], sampleRate));
    }

    if (std::abs(fxBusesL[4]) > 0.00001f || std::abs(fxBusesR[4]) > 0.00001f) {
      // Use new Stereo Process API
      float wL = 0, wR = 0;
      mTapeWobbleFx.processStereo(fxBusesL[4], fxBusesR[4], wL, wR, sampleRate);
      routeFx(4, wL, wR, true); // Delta
    }

    if (std::abs(fxBusesL[5]) > 1.0e-12f || std::abs(fxBusesR[5]) > 1.0e-12f ||
        !mDelayFx.isSilent()) {
      float dL = 0, dR = 0;
      mDelayFx.processStereo(fxBusesL[5], fxBusesR[5], dL, dR, sampleRate);
      int dest = mFxChainDest[5];
      if (dest >= 0 && dest < 15) {
        fxBusesL[dest] += dL * mFxMixLevels[5];
        fxBusesR[dest] += dR * mFxMixLevels[5];
      } else {
        spreadL += dL;
        spreadR += dR;
      }
    }

    if (std::abs(fxBusesL[6]) > 1.0e-12f || std::abs(fxBusesR[6]) > 1.0e-12f ||
        !mReverbFx.isSilent()) { // Use isSilent gate
      float rL = 0, rR = 0;
      mReverbFx.processStereoWet(fxBusesL[6], fxBusesR[6], rL, rR);
      int dest = mFxChainDest[6];
      if (dest >= 0 && dest < 15) {
        fxBusesL[dest] += rL * mFxMixLevels[6];
        fxBusesR[dest] += rR * mFxMixLevels[6];
      } else {
        spreadL += rL;
        spreadR += rR;
      }
    }

    if (std::abs(fxBusesL[7]) > 0.00001f || std::abs(fxBusesR[7]) > 0.00001f) {
      routeFx(
          7, mSlicerFxL.process(fxBusesL[7], mSampleCount + i, mSamplesPerStep),
          mSlicerFxR.process(fxBusesR[7], mSampleCount + i, mSamplesPerStep),
          true); // Delta
    }

    if (std::abs(fxBusesL[8]) > 0.00001f || std::abs(fxBusesR[8]) > 0.00001f) {
      routeFx(8, mCompressorFx.process(fxBusesL[8], sidechainSignal),
              mCompressorFx.process(fxBusesR[8], sidechainSignal));
    }

    if (std::abs(fxBusesL[11]) > 0.00001f ||
        std::abs(fxBusesR[11]) > 0.00001f) {
      float fL, fR;
      fL = mFlangerFxL.process(fxBusesL[11], sampleRate);
      fR = mFlangerFxR.process(fxBusesR[11], sampleRate);
      routeFx(11, fL, fR);
    }

    if (std::abs(fxBusesL[12]) > 0.00001f ||
        std::abs(fxBusesR[12]) > 0.00001f) {
      float sL = 0, sR = 0;
      // FIX: Do NOT sync AutoPanner phase to LP LFO. This froze the panner if
      // LP LFO was idle. mAutoPannerFx.setPhase(mLpLfoL.getPhase());
      mAutoPannerFx.process(fxBusesL[12], fxBusesR[12], sL, sR, sampleRate);
      routeFx(12, sL, sR, false); // Wet (Full Signal)
    }

    if (std::abs(fxBusesL[13]) > 0.00001f ||
        std::abs(fxBusesR[13]) > 0.00001f || !mTapeEchoFxL.isSilent() ||
        !mTapeEchoFxR.isSilent()) {
      // Anti-Denormal DC Offset for Echo Loop
      float dc = 1.0e-18f;
      routeFx(13, mTapeEchoFxL.process(fxBusesL[13] + dc, sampleRate),
              mTapeEchoFxR.process(fxBusesR[13] + dc, sampleRate));
    }

    if (std::abs(fxBusesL[14]) > 0.00001f ||
        std::abs(fxBusesR[14]) > 0.00001f) {
      routeFx(14, mOctaverFxL.process(fxBusesL[14], sampleRate),
              mOctaverFxR.process(fxBusesR[14], sampleRate));
    }

    float finalL = (mixedSampleL + wetSampleL + spreadL) * mMasterVolume;
    float finalR = (mixedSampleR + wetSampleR + spreadR) * mMasterVolume;

    if (!std::isfinite(finalL))
      finalL = 0.0f;
    if (!std::isfinite(finalR))
      finalR = 0.0f;

    outBuffer[i * 2] = softLimit(finalL);
    outBuffer[i * 2 + 1] = softLimit(finalR);
  }
}

// Reset Punch Active flags for all tracks after processing the block
// Reset of mPunchActive removed here, handled frame-by-frame

void AudioEngine::renderToWav(int numCycles, const std::string &path) {
  std::lock_guard<std::recursive_mutex> lock(mLock);

  // Offline rendering logic
  int framesPerCycle = mSamplesPerStep * 16; // One bar (16 steps)
  int totalFrames = framesPerCycle * numCycles;
  std::vector<float> output(totalFrames * 2);

  // Reset sequence state for export
  mSampleCount = 0;
  // --- High Priority: Audio Rendering ---
  int framesRendered = 0;
  while (framesRendered < totalFrames) {
    int chunk = std::min(64, totalFrames - framesRendered);

    // Lock only for the minimum duration needed to update state or commands
    {
      std::lock_guard<std::recursive_mutex> lock(mLock);
      processCommands();

      // Check for next step trigger
      if (mSampleCount >= mSamplesPerStep) {
        mSampleCount -= mSamplesPerStep;
        mGlobalStepIndex = (mGlobalStepIndex + 1) % mPatternLength;

        // Advance sequencers
        for (int t = 0; t < (int)mTracks.size(); ++t) {
          if (mTracks[t].isActive) {
            mTracks[t].sequencer.advance();
            const Step &s = mTracks[t].sequencer.getCurrentStep();
            if (s.active) {
              // Trigger logic here... simplified for stability
              triggerNoteLocked(t, 60, 100, true);
            }
          }
        }
      }
    } // Unlock immediately after state update

    // Render Audio (Lock is re-acquired inside renderStereo only if needed?
    // Actually renderStereo accesses mTracks heavily, so it probably NEEDS
    // safety. However, locking for the WHOLE block causes glitches. The
    // "Shield" strategy: Hold lock during render, but check it's not held too
    // long. For now, simplicity = safety.
    {
      std::lock_guard<std::recursive_mutex> lock(mLock);
      renderStereo(&output[framesRendered * 2], chunk);
    }

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
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    if (mTracks[trackIndex].engineType == 4) {
      mTracks[trackIndex].wavetableEngine.loadDefaultWavetable();
    }
  }
}

void AudioEngine::getStepActiveStates(int trackIndex, bool *out, int maxSize) {
  std::lock_guard<std::recursive_mutex> lock(mLock);
  if (trackIndex < 0 || trackIndex >= (int)mTracks.size()) {
    std::fill(out, out + maxSize, false);
    return;
  }
  const auto &steps = mTracks[trackIndex].sequencer.getSteps();
  int limit = std::min(maxSize, (int)steps.size());
  for (int i = 0; i < limit; ++i) {
    out[i] = steps[i].active;
  }
}
void AudioEngine::setInputDevice(int deviceId) {
  std::lock_guard<std::recursive_mutex> lock(mLock);

  if (mInputStream) {
    mInputStream->stop();
    mInputStream->close();
    mInputStream.reset();
  }

  oboe::AudioStreamBuilder inBuilder;
  inBuilder.setDirection(oboe::Direction::Input)
      ->setFormat(oboe::AudioFormat::Float)
      ->setChannelCount(oboe::ChannelCount::Mono)
      ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
      ->setSharingMode(oboe::SharingMode::Exclusive)
      ->setInputPreset(oboe::InputPreset::Camcorder)
      ->setCallback(this);

  if (mStream) {
    inBuilder.setSampleRate(mStream->getSampleRate());
  } else if (mSampleRate > 0) {
    inBuilder.setSampleRate(mSampleRate);
  }

  if (deviceId > 0) {
    inBuilder.setDeviceId(deviceId);
  }

  oboe::Result result = inBuilder.openStream(mInputStream);
  if (result != oboe::Result::OK) {
    LOGD("CRITICAL: Error re-opening input stream with device %d: %s", deviceId,
         oboe::convertToText(result));
  } else {
    result = mInputStream->requestStart();
    if (result != oboe::Result::OK) {
      LOGD("CRITICAL: Error starting re-opened input stream: %s",
           oboe::convertToText(result));
    } else {
      LOGD("SUCCESS: Re-opened input stream on device %d at %d Hz", deviceId,
           mInputStream->getSampleRate());
    }
  }
}
void AudioEngine::loadSoundFont(int trackIndex, const std::string &path) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    std::lock_guard<std::recursive_mutex> lock(mLock);
    mTracks[trackIndex].soundFontEngine.load(path);
  }
}

void AudioEngine::setSoundFontPreset(int trackIndex, int presetIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    std::lock_guard<std::recursive_mutex> lock(mLock);
    mTracks[trackIndex].soundFontEngine.setPreset(presetIndex);
  }
}

int AudioEngine::getSoundFontPresetCount(int trackIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    return mTracks[trackIndex].soundFontEngine.getPresetCount();
  }
  return 0;
}

std::string AudioEngine::getSoundFontPresetName(int trackIndex,
                                                int presetIndex) {
  if (trackIndex >= 0 && trackIndex < (int)mTracks.size()) {
    return mTracks[trackIndex].soundFontEngine.getPresetName(presetIndex);
  }
  return "";
}
