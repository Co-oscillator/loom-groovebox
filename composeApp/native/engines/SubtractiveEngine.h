#ifndef SUBTRACTIVE_ENGINE_H
#define SUBTRACTIVE_ENGINE_H

#include "../Utils.h"
#include "Adsr.h"
#include "Oscillator.h"
#include "../Log.h"
#include <cmath>
#include <memory>
#include <vector>

class SubtractiveEngine {
public:
  struct Voice {
    bool active = false;
    bool isNoteHeld = false;
    int note = -1;
    float frequency = 440.0f;
    float targetFrequency = 440.0f;
    float amplitude = 1.0f;
    Adsr ampEnv;
    Adsr filterEnv;
    std::vector<Oscillator> oscillators;

    // SVF state
    TSvf svf;
    float currentFilterEnvVal = 0.0f;
    float smoothedCutoff = 0.5f;
    uint32_t controlCounter = 0;

    Voice() { oscillators.resize(4); }

    void reset() {
      active = false;
      isNoteHeld = false;
      note = -1;
      frequency = 440.0f;
      targetFrequency = 440.0f;
      ampEnv.reset();
      filterEnv.reset();
      svf.setParams(1000.0f, 0.7f, 44100.0f);
    }
  };

  SubtractiveEngine() {
    mVoices.resize(16);
    for (auto &v : mVoices)
      v.reset();
    mOscVolumes.assign(4, 0.0f);
    mOscVolumes[0] = 0.6f;
    mOscVolumes[1] = 0.4f;
    mOscWaveValues.assign(4, 0.0f);
    mOscWaveValues[0] = 0.666666f;
    mOscWaveValues[1] = 1.0f;
    resetToDefaults();
  }

  void resetToDefaults() {
    mCutoff = 1.0f;
    mResonance = 0.0f;
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 1.0f;
    mRelease = 0.5f;
    mF_Atk = 0.01f;
    mF_Dcy = 0.1f;
    mF_Sus = 0.0f;
    mF_Rel = 0.5f;
    mF_Amt = 0.0f;
    mDetune = 0.0f;
    mNoiseLevel = 0.0f;
    mOscSync = false;
    mRingMod = false;
    mFilterMode = 0;
    mOscPitch[0] = 1.0f;
    mOscPitch[1] = 1.0f;
    mOscPitch[2] = 0.5f;
    mOscPitch[3] = 1.0f;
    mOscVolumes[0] = 0.6f;
    mOscVolumes[1] = 0.4f;
    mOscVolumes[2] = 0.4f; // Default Sub Volume
    mOscVolumes[3] = 0.0f;
    mOscDrive[0] = mOscDrive[1] = mOscDrive[2] = mOscDrive[3] = 1.0f;
    mOscFold[0] = mOscFold[1] = mOscFold[2] = mOscFold[3] = 0.0f;
    mOscPW[0] = mOscPW[1] = mOscPW[2] = mOscPW[3] = 0.5f;
    mOscWaveValues[0] = 0.666666f;
    mOscWaveValues[1] = 1.0f;
    mOscWaveValues[2] = 0.0f; // Explicitly set Sub to Sine
    mOscWaveValues[3] = 0.666666f;

    // Propagate waveforms to all voices immediately
    for (int i = 0; i < 4; ++i) {
      setOscWaveform(i, mOscWaveValues[i]);
    }

    updateLiveEnvelopes();
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      v.ampEnv.setSampleRate(sr);
      v.filterEnv.setSampleRate(sr);
    }
  }

  void setFrequency(float freq, float sampleRate) {
    mSampleRate = sampleRate;
    mFrequency = freq;
  }
  void setIgnoreNoteFrequency(bool ignore) { mIgnoreNoteFrequency = ignore; }

  void setGlide(float v) { mGlide = v; }
  void setPitchBend(float semitones) { mPitchBend = semitones; }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.isNoteHeld = false;
      v.ampEnv.reset();
      v.filterEnv.reset();
    }
  }

  void triggerNote(int note, int velocity) {
    int idx = -1;
    for (int i = 0; i < 16; ++i)
      if (!mVoices[i].active) {
        idx = i;
        break;
      }
    if (idx == -1)
      idx = 0;

    Voice &v = mVoices[idx];
    v.active = true;
    v.isNoteHeld = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    float baseFreq = mIgnoreNoteFrequency
                         ? mFrequency
                         : 440.0f * powf(2.0f, (note - 69) / 12.0f);

    v.targetFrequency = baseFreq;
    v.frequency = (mGlide > 0.001f) ? mLastFrequency : baseFreq;
    mLastFrequency = baseFreq;

    v.ampEnv.setSampleRate(mSampleRate);
    v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.filterEnv.setSampleRate(mSampleRate);
    v.filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);

    v.ampEnv.trigger();
    v.filterEnv.trigger();
    v.svf.setParams(1000.0f, 0.7f, mSampleRate);

    for (int i = 0; i < 4; ++i) {
      v.oscillators[i].setFrequency(v.frequency, mSampleRate);
      v.oscillators[i].resetPhase();
    }
  }

  void releaseNote(int note) {
    for (auto &v : mVoices)
      if (v.active && v.note == note) {
        v.isNoteHeld = false;
        v.ampEnv.release();
        v.filterEnv.release();
      }
  }

  void setAttack(float v) {
    mAttack = v;
    updateLiveEnvelopes();
  }
  void setDecay(float v) {
    mDecay = v;
    updateLiveEnvelopes();
  }
  void setSustain(float v) {
    mSustain = v;
    updateLiveEnvelopes();
  }
  void setRelease(float v) {
    mRelease = v;
    updateLiveEnvelopes();
  }
  void setFilterAttack(float v) {
    mF_Atk = v;
    updateLiveEnvelopes();
  }
  void setFilterDecay(float v) {
    mF_Dcy = v;
    updateLiveEnvelopes();
  }
  void setFilterSustain(float v) {
    mF_Sus = v;
    updateLiveEnvelopes();
  }
  void setFilterRelease(float v) {
    mF_Rel = v;
    updateLiveEnvelopes();
  }

  void setCutoff(float cutoff) { mCutoff = cutoff; }
  void setResonance(float res) { mResonance = res; }
  void setFilterEnvAmount(float v) { mF_Amt = v * 2.0f - 1.0f; }
  void setDetune(float v) { mDetune = v; }
  void setNoiseLevel(float v) { mNoiseLevel = v; }
  void setOscVolume(int osc, float vol) {
    if (osc >= 0 && osc < 4)
      mOscVolumes[osc] = vol;
  }
  void setLfoRate(float rate) { mLfoRate = rate; }
  void setLfoDepth(float depth) { mLfoDepth = depth; }
  void setUseEnvelope(bool v) { mUseEnvelope = v; }

  void setParameter(int id, float value) {
    if (id == 112)
      setCutoff(value);
    else if (id == 113)
      setResonance(value);
    else if (id == 100)
      setAttack(value);
    else if (id == 101)
      setDecay(value);
    else if (id == 102)
      setSustain(value);
    else if (id == 103)
      setRelease(value);
    else if (id == 150)
      mOscSync = (value > 0.5f);
    else if (id == 151)
      mRingMod = (value > 0.5f);
    else if (id == 152)
      mFmAmt = value;
    else if (id >= 160 && id <= 163) {
      if (id == 162)
        mOscPitch[2] = value * 2.0f; // Sub (1 octave lower than Osc 1/2 @ 0.5)
      else
        mOscPitch[id - 160] = value * 4.0f;
    } else if (id >= 170 && id <= 173)
      mOscDrive[id - 170] = 1.0f + value * 10.0f;
    else if (id >= 180 && id <= 183)
      mOscFold[id - 180] = value;
    else if (id >= 190 && id <= 193) {
      mOscPW[id - 190] = value;
      for (auto &v : mVoices)
        v.oscillators[id - 190].setWaveShape(value);
    } else if (id >= 107 && id <= 109) {
      setOscVolume(id - 107, value);
    } else if (id == 110) {
      setNoiseLevel(value);
    } else if (id == 355) {
      setGlide(value);
    } else if (id == 155) { // Sub Shape
      setOscWaveform(2, value);
    }
  }

  void setOscWaveform(int index, float value) {
    if (index >= 0 && index < 4) {
      mOscWaveValues[index] = value;
      for (auto &v : mVoices)
        v.oscillators[index].setMorphValue(value);
    }
  }

  void setFilterMode(int mode) { mFilterMode = mode; }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;
    
    // Optimize: Calculate LFO using FastSine
    float lfo = 0.0f;
    if (mLfoDepth > 0.0f) {
        float lfoPhase = (float)mControlCounter * mLfoRate / mSampleRate;
        lfo = FastSine::get(lfoPhase) * mLfoDepth;
    }

    // Optimize: Pre-calculate bend factor once per render call
    float bendFactor = powf(2.0f, mPitchBend / 12.0f);

    for (auto &v : mVoices) {
      if (!v.active)
        continue;

      // v3.1.41: Moved all modulation and frequency updates to a 32-sample control block
      if (v.controlCounter % 32 == 0) {
        if (mGlide > 0.001f) {
           float glideTimeSamples = mGlide * mSampleRate * 0.5f;
           float glideAlpha = 32.0f / (glideTimeSamples + 32.0f);
           v.frequency += (v.targetFrequency - v.frequency) * glideAlpha;
        } else {
           v.frequency = v.targetFrequency;
        }

        float baseFreqWithBend = v.frequency * bendFactor;
        for (int i = 0; i < 4; ++i) {
          v.oscillators[i].setFrequency(baseFreqWithBend, mSampleRate);
        }
        
        float modCutoff = std::max(
            0.0f,
            std::min(0.999f, mCutoff + v.currentFilterEnvVal * mF_Amt + lfo));
        
        v.smoothedCutoff = v.smoothedCutoff * 0.8f + modCutoff * 0.2f;
        v.svf.setParams(20.0f + v.smoothedCutoff * v.smoothedCutoff * 14000.0f,
                        std::max(0.1f, mResonance * 5.0f), mSampleRate);
      }
      
      float envVal = mUseEnvelope ? v.ampEnv.nextValue() : 1.0f;
      if (envVal < 0.0001f && mUseEnvelope && !v.ampEnv.isActive()) {
        v.active = false;
        continue;
      }
      activeCount++;
      v.currentFilterEnvVal = v.filterEnv.nextValue();

      float oscOutput = 0.0f;
      float osc1Val = 0.0f; // Cache for RingMod
      float baseFreqWithBend = v.frequency * bendFactor;
      
      // Optimize: Only process active oscillators
      // Oscillator 1
      if (mOscVolumes[0] > 0.0f) {
        osc1Val = v.oscillators[0].nextSample(0.0f, mOscPitch[0], mOscFold[0]);
        oscOutput += osc1Val * mOscVolumes[0] * mOscDrive[0];
      }
      
      // Oscillator 2 (with Detune)
      if (mOscVolumes[1] > 0.0f) {
        float osc2Pitch = mOscPitch[1] * (1.0f + mDetune * 0.05f);
        if (mOscSync && v.oscillators[0].hasWrapped()) {
          v.oscillators[1].resetPhase();
        }
        
        float val = v.oscillators[1].nextSample(0.0f, osc2Pitch, mOscFold[1]);
        if (mRingMod && mOscVolumes[0] > 0.0f) {
            oscOutput = (osc1Val * mOscVolumes[0] * mOscDrive[0]) * (val * mOscVolumes[1] * mOscDrive[1]);
        } else {
            oscOutput += val * mOscVolumes[1] * mOscDrive[1];
        }
      }
      
      // Oscillator 3 (Sub)
      if (mOscVolumes[2] > 0.0f) {
        oscOutput += v.oscillators[2].nextSample(0.0f, mOscPitch[2], mOscFold[2]) * mOscVolumes[2] * mOscDrive[2];
      }
      
      // Oscillator 4
      if (mOscVolumes[3] > 0.0f) {
        oscOutput += v.oscillators[3].nextSample(0.0f, mOscPitch[3], mOscFold[3]) * mOscVolumes[3] * mOscDrive[3];
      }

      // Noise
      if (mNoiseLevel > 0.0f) {
        mNoiseSeed = mNoiseSeed * 1103515245 + 12345;
        oscOutput += (((float)(mNoiseSeed & 0x7fffffff) / (float)0x7fffffff) * 2.0f - 1.0f) * mNoiseLevel;
      }
      
      float finalOutput = oscOutput * v.amplitude * envVal;
      TSvf::Type type = (TSvf::Type)mFilterMode;
      mixedOutput += v.svf.process(finalOutput, type);
      
      v.controlCounter++;
    }
    mControlCounter++;
    return fast_tanh(mixedOutput * (activeCount > 1 ? 0.7f : 1.0f));
  }

  // v3.2.5: Brutally Optimized Voices-Outside Block Rendering
  void render(float *outL, float *outR, int numFrames) {
    mActiveCount = 0;
    for (int i = 0; i < 16; ++i) {
        if (mVoices[i].active) mActiveIndices[mActiveCount++] = i;
    }
    if (mActiveCount == 0) return;

    float bendFactor = powf(2.0f, mPitchBend / 12.0f);
    
    // Global LFO (Hoisted)
    float lfo = 0.0f;
    if (mLfoDepth > 0.0f) {
        float lfoPhase = (float)mControlCounter * mLfoRate / mSampleRate;
        lfo = FastSine::get(lfoPhase) * mLfoDepth;
    }

    for (int v_idx = 0; v_idx < mActiveCount; ++v_idx) {
        Voice &v = mVoices[mActiveIndices[v_idx]];
        
        // Hoist modulation/glide to per-block
        if (mGlide > 0.001f) {
            float glideTimeSamples = mGlide * mSampleRate * 0.5f;
            float glideAlpha = (float)numFrames / (glideTimeSamples + (float)numFrames);
            v.frequency += (v.targetFrequency - v.frequency) * glideAlpha;
        } else {
            v.frequency = v.targetFrequency;
        }
        float baseFreqWithBend = v.frequency * bendFactor;
        for (int osc_i = 0; osc_i < 4; ++osc_i) {
            v.oscillators[osc_i].setFrequency(baseFreqWithBend, mSampleRate);
        }

        // Osc constants are block-invariant — hoist above chunk loop
        float v0 = mOscVolumes[0] * mOscDrive[0];
        float p0 = mOscPitch[0];
        float f0 = mOscFold[0];
        float v1 = mOscVolumes[1] * mOscDrive[1];
        float p1 = mOscPitch[1] * (1.0f + mDetune * 0.05f);
        float f1 = mOscFold[1];
        float v2 = mOscVolumes[2] * mOscDrive[2];
        float p2 = mOscPitch[2];
        float f2 = mOscFold[2];
        float v3 = mOscVolumes[3] * mOscDrive[3];
        float p3 = mOscPitch[3];
        float f3 = mOscFold[3];

        // Process in chunks of 16 samples to allow filter envelope updates without massive CPU overhead
        int samplesRemaining = numFrames;
        int frameIndex = 0;
        
        while (samplesRemaining > 0) {
            int chunkSize = std::min(16, samplesRemaining);
            
            // Per-chunk filter envelope update
            v.currentFilterEnvVal = v.filterEnv.getValue(); // Sample current envelope
            float modCutoff = std::max(0.001f, std::min(0.999f, mCutoff + v.currentFilterEnvVal * mF_Amt + lfo));
            v.smoothedCutoff = v.smoothedCutoff * 0.7f + modCutoff * 0.3f;
            
            float f_freq = 20.0f + v.smoothedCutoff * v.smoothedCutoff * 14000.0f;
            v.svf.setParams(f_freq, std::max(0.1f, mResonance * 5.0f), mSampleRate);
            
            for (int f = 0; f < chunkSize; ++f) {
                float envVal = mUseEnvelope ? v.ampEnv.nextValue() : 1.0f;
                if (envVal < 0.0001f && mUseEnvelope && !v.ampEnv.isActive()) {
                    v.active = false;
                    break; 
                }
                
                // Advance filter envelope per sample to keep timing accurate
                v.filterEnv.nextValue();

                float oscOutput = 0.0f;
                float osc1Val = 0.0f;

                if (v0 > 0.0f) {
                    osc1Val = v.oscillators[0].nextSample(0.0f, p0, f0);
                    oscOutput += osc1Val * v0;
                }
                if (v1 > 0.0f) {
                    if (mOscSync && v.oscillators[0].hasWrapped()) v.oscillators[1].resetPhase();
                    float val = v.oscillators[1].nextSample(0.0f, p1, f1);
                    if (mRingMod && v0 > 0.0f) oscOutput = (osc1Val * v0) * (val * v1);
                    else oscOutput += val * v1;
                }
                if (v2 > 0.0f) oscOutput += v.oscillators[2].nextSample(0.0f, p2, f2) * v2;
                if (v3 > 0.0f) oscOutput += v.oscillators[3].nextSample(0.0f, p3, f3) * v3;

                if (mNoiseLevel > 0.0f) {
                    mNoiseSeed = mNoiseSeed * 1103515245 + 12345;
                    oscOutput += (((float)(mNoiseSeed & 0x7fffffff) / (float)0x7fffffff) * 2.0f - 1.0f) * mNoiseLevel;
                }
                
                float voiceSample = v.svf.process(oscOutput * v.amplitude * envVal * 0.5f, (TSvf::Type)mFilterMode);
                outL[frameIndex + f] += voiceSample;
                outR[frameIndex + f] += voiceSample;
            }
            
            if (!v.active) break;
            
            samplesRemaining -= chunkSize;
            frameIndex += chunkSize;
        }
    }
    
    mControlCounter += numFrames;
    // Final block-level saturation
    for (int f = 0; f < numFrames; ++f) {
        float masterS = fast_tanh(outL[f] * (mActiveCount > 1 ? 0.7f : 1.0f));
        outL[f] = masterS;
        outR[f] = masterS;
    }
  }

  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    return false;
  }

  float getEnvelopeValue() const {
    float maxEnv = 0.0f;
    for (const auto &v : mVoices) {
      if (v.active) {
        maxEnv = std::max(maxEnv, v.currentFilterEnvVal);
      }
    }
    return maxEnv;
  }

private:
  void updateLiveEnvelopes() {
    for (auto &v : mVoices)
      if (v.active) {
        v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
        v.filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);
      }
  }
  std::vector<Voice> mVoices;
  int mActiveIndices[16];
  int mActiveCount = 0;
  std::vector<float> mOscVolumes;
  std::vector<float> mOscWaveValues;
  uint32_t mControlCounter = 0;
  float mCutoff = 0.45f, mResonance = 0.0f, mAttack = 0.01f, mDecay = 0.1f,
        mSustain = 0.8f, mRelease = 0.5f, mF_Atk = 0.01f, mF_Dcy = 0.1f,
        mF_Sus = 0.0f, mF_Rel = 0.5f, mF_Amt = 0.0f;
  float mDetune = 0.0f, mNoiseLevel = 0.0f, mLfoRate = 0.0f, mLfoDepth = 0.0f,
        mFrequency = 440.0f, mLastFrequency = 440.0f, mGlide = 0.0f;
  unsigned int mNoiseSeed = 12345;
  float mSampleRate = 48000.0f;
  bool mUseEnvelope = true;
  bool mReverse = false;
  bool mOscSync = false, mRingMod = false, mIgnoreNoteFrequency = false;
  float mFmAmt = 0.0f;
  float mPitchBend = 0.0f;
  int mFilterMode = 0;
  float mOscPitch[4] = {1.0f, 1.0f, 0.5f, 1.0f},
        mOscDrive[4] = {1.0f, 1.0f, 1.0f, 1.0f},
        mOscFold[4] = {0.0f, 0.0f, 0.0f, 0.0f},
        mOscPW[4] = {0.5f, 0.5f, 0.5f, 0.5f};
};

#endif
