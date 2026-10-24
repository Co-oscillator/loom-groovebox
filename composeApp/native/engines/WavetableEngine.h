#ifndef WAVETABLE_ENGINE_H
#define WAVETABLE_ENGINE_H

#include "../Utils.h"
#include "../WavFileUtils.h"
#include "Adsr.h"
#include <algorithm>
#include "../Log.h"
#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

class WavetableEngine {
public:
  struct Voice {
    bool active = false;
    int note = -1;
    float phase = 0.0f;
    float frequency = 440.0f;
    float targetFrequency = 440.0f;
    float amplitude = 1.0f;
    Adsr envelope;
    Adsr filterEnv;
    TSvf svf;
    float lastSample = 0.0f;
    float srateCounter = 0.0f;
    uint32_t controlCounter = 0;
    float mCachedDelta = 0.0f;

    void reset() {
      active = false;
      note = -1;
      phase = 0.0f;
      envelope.reset();
      filterEnv.reset();
      svf.setParams(1000.0f, 0.7f, 48000.0f);
      frequency = 440.0f;
      targetFrequency = 440.0f;
      lastSample = 0.0f;
      srateCounter = 0.0f;
    }
  };

  WavetableEngine() {
    mVoices.resize(16);
    for (auto &v : mVoices)
      v.reset();
    loadDefaultWavetable();
    resetToDefaults();
  }

  void resetToDefaults() {
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 0.8f;
    mRelease = 0.2f;
    mCutoff = 1.0f;
    mResonance = 0.0f;
    mPosition = 0.0f;
    mDetune = 0.0f;
    mF_Atk = 0.01f;
    mF_Dcy = 0.1f;
    mF_Sus = 0.0f;
    mF_Rel = 0.5f;
    mF_Amt = 0.0f;
    mWarp = 0.0f;
    mCrush = 0.0f;
    mDrive = 0.0f;
    mBits = 1.0f;
    mSrate = 0.0f;
    mFilterMode = 0;
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      v.envelope.setSampleRate(sr);
      v.filterEnv.setSampleRate(sr);
    }
  }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.envelope.reset();
      v.filterEnv.reset();
    }
  }

  void setFrequency(float freq, float sampleRate) {
    mSampleRate = sampleRate;
    mFrequency = freq;
  }
  void setGlide(float g) { mGlide = g; }
  void setPitchBend(float v) { mPitchBend = v; }

  void loadWavetable(const std::vector<float> &data) {
    auto newData = std::make_shared<std::vector<float>>(data);
    std::atomic_store(&mTablePtr, newData);
    mNumFrames = (data.size() > 2048) ? (int)(data.size() / 2048) : 1;
  }

  void loadWavetable(const std::string &path) {
    std::vector<float> data;
    int sr, channels;
    std::vector<float> slices;
    if (WavFileUtils::loadWav(path, data, sr, channels, slices)) {
      loadWavetable(data);
    }
  }

  void loadDefaultWavetable() {
    auto data = std::make_shared<std::vector<float>>(2048);
    for (int i = 0; i < 2048; ++i)
      (*data)[i] = sinf(i * 6.283185f / 2048.0f);
    std::atomic_store(&mTablePtr, data);
    mNumFrames = 1;
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
    v.reset();
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    float baseFreq = 440.0f * powf(2.0f, (note - 69) / 12.0f);
    v.targetFrequency = baseFreq;
    v.frequency = (mGlide > 0.001f) ? mLastFrequency : baseFreq;
    mLastFrequency = baseFreq;

    v.envelope.setSampleRate(mSampleRate);
    v.envelope.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.envelope.trigger();

    v.filterEnv.setSampleRate(mSampleRate);
    v.filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);
    v.filterEnv.trigger();
  }

  void releaseNote(int note) {
    for (auto &v : mVoices)
      if (v.active && v.note == note) {
        v.envelope.release();
        v.filterEnv.release();
      }
  }

  void setAttack(float v) { mAttack = v; }
  void setDecay(float v) { mDecay = v; }
  void setSustain(float v) { mSustain = v; }
  void setRelease(float v) { mRelease = v; }
  void setFilterCutoff(float v) { mCutoff = v; }
  void setResonance(float v) { mResonance = v; }

  void setParameter(int id, float value) {
    switch (id) {
    case 0:
      mPosition = value;
      break;
    case 1:
      mDetune = value;
      break;
    case 10:
      setFilterCutoff(value);
      break;
    case 11:
      mF_Dcy = value;
      break;
    case 14:
      mF_Amt = value * 2.0f - 1.0f;
      break;
    case 15:
      mWarp = value;
      break;
    case 16:
      mCrush = value;
      break;
    case 17:
      mDrive = value;
      break;
    case 20: // Filter Mode
      mFilterMode = (int)(value * 3.99f);
      break;
    case 21: // Filter Atk
      mF_Atk = value;
      break;
    case 23: // Filter Sus
      mF_Sus = value;
      break;
    case 24: // Filter Rel
      mF_Rel = value;
      break;
    case 30:                          // Bits
      mBits = 1.0f - (value * 0.95f); // 1.0 is full, 0.05 is 1 bit
      break;
    case 31: // Srate
      mSrate = value;
      break;
    case 355:
      setGlide(value);
      break;
    }
  }

  float render() {
    auto table = std::atomic_load(&mTablePtr);
    if (!table || table->empty()) return 0.0f;
    const auto &tableRef = *table;

    float mixedOutput = 0.0f;
    int activeCount = 0;

    // v3.1.41: Calculate block-rate parameters once per render call
    float bendFactor = powf(2.0f, mPitchBend / 12.0f);
    float bitSteps = (mBits < 0.99f) ? powf(2.0f, mBits * 16.0f) : 0.0f;
    float warpFactor = mWarp * 4.0f;

    for (auto &v : mVoices) {
      if (!v.active)
        continue;

      // 1. Control Rate Updates (Glide/Frequency)
      if (v.controlCounter % 32 == 0) {
        if (mGlide > 0.001f) {
           float glideTimeSamples = mGlide * mSampleRate * 0.5f;
           float glideAlpha = 32.0f / (glideTimeSamples + 32.0f);
           v.frequency += (v.targetFrequency - v.frequency) * glideAlpha;
        } else {
           v.frequency = v.targetFrequency;
        }

        // Cache parameters that only need updating occasionally
        float cutoff = 20.0f + mCutoff * mCutoff * 18000.0f;
        cutoff += v.filterEnv.getValue() * mF_Amt * 12000.0f;
        cutoff = std::max(20.0f, std::min(20000.0f, cutoff));
        v.svf.setParams(cutoff, 0.7f + mResonance * 5.0f, mSampleRate);
      }

      float env = v.envelope.nextValue();
      if (env < 0.0001f && !v.envelope.isActive()) {
        v.active = false;
        continue;
      }
      activeCount++;

      float voiceDetune = 1.0f + (mDetune * 0.02f);
      double delta = (v.frequency * bendFactor * voiceDetune) / mSampleRate;
      v.phase += delta;
      if (v.phase >= 1.0) v.phase -= 1.0;

      // Sample Rate Reduction (Decimation)
      bool skipSample = false;
      if (mSrate > 0.05f) {
        float period = 1.0f + mSrate * 64.0f;
        v.srateCounter += 1.0f;
        if (v.srateCounter < period) {
          skipSample = true;
        } else {
          v.srateCounter -= period;
        }
      }

      if (!skipSample) {
        double wPhase = v.phase;
        // v3.1.41: Fast polynomial warp
        if (std::abs(mWarp) > 0.01f) {
          wPhase = wPhase + (wPhase * (1.0 - wPhase) * warpFactor);
        }

        float pos = mPosition * (float)(mNumFrames - 1);
        int frame1 = (int)pos;
        int frame2 = std::min(mNumFrames - 1, frame1 + 1);
        float posFrac = pos - (float)frame1;

        auto getSample = [&](int frame, double phase) -> float {
          int offset = frame * 2048;
          double tablePos = phase * 2047.0;
          int i1 = (int)tablePos;
          int i2 = (i1 + 1) % 2048;

          if (offset + i1 >= (int)tableRef.size() || offset + i2 >= (int)tableRef.size())
            return 0.0f;

          float f = (float)(tablePos - i1);
          return (1.0f - f) * tableRef[offset + i1] + f * tableRef[offset + i2];
        };

        float sample = (1.0f - posFrac) * getSample(frame1, wPhase) +
                       posFrac * getSample(frame2, wPhase);

        if (bitSteps > 0.0f) {
          sample = roundf(sample * bitSteps) / bitSteps;
        }

        if (mCrush > 0.05f) {
          float st = 2.0f + (1.0f - mCrush) * 32.0f;
          sample = roundf(sample * st) / st;
        }
        if (mDrive > 0.05f) {
          sample = fast_tanh(sample * (1.0f + mDrive * 4.0f));
        }
        v.lastSample = sample;
      }

      v.controlCounter++;
      mixedOutput += v.svf.process(v.lastSample, (TSvf::Type)mFilterMode) *
                     env * v.amplitude;
    }

    if (activeCount > 1)
      mixedOutput *= 0.7f;

    mControlCounter++;
    return mixedOutput;
  }

  // v3.2.0: Lock-free block rendering using atomic shared_ptr swap
  void render(float *outL, float *outR, int numFrames) {
    auto table = std::atomic_load(&mTablePtr);
    if (!table || table->empty()) return;
    const auto &tableRef = *table;

    mActiveCount = 0;
    for (int i = 0; i < 16; ++i) {
        if (mVoices[i].active) mActiveIndices[mActiveCount++] = i;
    }
    
    if (mActiveCount == 0) return;

    float bendFactor = powf(2.0f, mPitchBend / 12.0f);
    float bitSteps = (mBits < 0.99f) ? powf(2.0f, mBits * 16.0f) : 0.0f;
    float warpFactor = mWarp * 4.0f;
    bool hasWarp = std::abs(mWarp) > 0.01f;

    // Pre-calculate morphology parameters once per block
    float pos = mPosition * (float)(mNumFrames - 1);
    int frame1 = (int)pos;
    int frame2 = std::min(mNumFrames - 1, frame1 + 1);
    float posFrac = pos - (float)frame1;
    bool isMorphing = (mNumFrames > 1 && posFrac > 0.001f);

    for (int i = 0; i < numFrames; ++i) {
        float mixedOutput = 0.0f;
        bool updateEnv = (mControlCounter % 16 == 0);
        bool updateMod = (mControlCounter % 32 == 0);

        for (int v_idx = 0; v_idx < mActiveCount; ++v_idx) {
            Voice &v = mVoices[mActiveIndices[v_idx]];
            
            if (updateMod) {
                if (mGlide > 0.001f) {
                    float glideTimeSamples = mGlide * mSampleRate * 0.5f;
                    float glideAlpha = 32.0f / (glideTimeSamples + 32.0f);
                    v.frequency += (v.targetFrequency - v.frequency) * glideAlpha;
                } else {
                    v.frequency = v.targetFrequency;
                }
            }

            if (updateEnv) {
                float cutoff = 20.0f + mCutoff * mCutoff * 18000.0f;
                cutoff += v.filterEnv.getValue() * mF_Amt * 12000.0f;
                cutoff = std::max(20.0f, std::min(20000.0f, cutoff));
                v.svf.setParams(cutoff, 0.7f + mResonance * 5.0f, mSampleRate);
                v.filterEnv.nextValue();
            }

            float env = v.envelope.nextValue();
            if (env < 0.0001f && !v.envelope.isActive()) {
                v.active = false;
                continue;
            }

            float voiceDetune = 1.0f + (mDetune * 0.02f);
            float delta = (v.frequency * bendFactor * voiceDetune) / mSampleRate;
            v.phase += delta;
            if (v.phase >= 1.0f) v.phase -= 1.0f;

            // Sample Rate Reduction
            bool skipSample = false;
            if (mSrate > 0.05f) {
                float period = 1.0f + mSrate * 64.0f;
                v.srateCounter += 1.0f;
                if (v.srateCounter < period) skipSample = true;
                else v.srateCounter -= period;
            }

            if (!skipSample) {
                float wPhase = v.phase;
                if (hasWarp) {
                    wPhase = wPhase + (wPhase * (1.0f - wPhase) * warpFactor);
                }

                auto getTableSample = [&](int frame, float phase) -> float {
                    int offset = frame << 11; // frame * 2048
                    float fPos = phase * 2047.0f;
                    int i1 = (int)fPos;
                    float f = fPos - (float)i1;
                    int i2 = (i1 + 1) & 2047;
                    return (1.0f - f) * tableRef[offset + i1] + f * tableRef[offset + i2];
                };

                float sample;
                if (isMorphing) {
                    sample = (1.0f - posFrac) * getTableSample(frame1, wPhase) + 
                             posFrac * getTableSample(frame2, wPhase);
                } else {
                    sample = getTableSample(frame1, wPhase);
                }

                if (bitSteps > 0.0f) {
                    sample = (float)((int)(sample * bitSteps)) / bitSteps;
                }

                if (mCrush > 0.05f) {
                    float st = 2.0f + (1.0f - mCrush) * 32.0f;
                    sample = (float)((int)(sample * st)) / st;
                }
                if (mDrive > 0.05f) sample = fast_tanh(sample * (1.0f + mDrive * 4.0f));
                v.lastSample = sample;
            }

            mixedOutput += v.svf.process(v.lastSample * 0.5f, (TSvf::Type)mFilterMode) * env * v.amplitude;
            v.controlCounter++;
        }

        if (mActiveCount > 1) mixedOutput *= 0.7f;
        // v3.2.0: Headroom scaling (0.7f)
        float finalS = fast_tanh(mixedOutput * 0.7f);
        outL[i] += finalS;
        outR[i] += finalS;
        mControlCounter++;
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
        maxEnv = std::max(maxEnv, v.envelope.getValue());
      }
    }
    return maxEnv;
  }

private:
  std::vector<Voice> mVoices;
  int mActiveIndices[16];
  int mActiveCount = 0;
  std::shared_ptr<std::vector<float>> mTablePtr;
  int mNumFrames = 1;
  float mSampleRate = 48000.0f, mFrequency = 440.0f, mLastFrequency = 440.0f,
        mGlide = 0.0f;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 0.8f, mRelease = 0.2f;
  float mF_Atk = 0.01f, mF_Dcy = 0.1f, mF_Sus = 0.0f, mF_Rel = 0.5f,
        mF_Amt = 0.0f;
  float mCutoff = 1.0f, mResonance = 0.0f, mPosition = 0.0f, mDetune = 0.0f;
  float mWarp = 0.0f, mCrush = 0.0f, mDrive = 0.0f;
  float mBits = 1.0f, mSrate = 0.0f;
  float mPitchBend = 0.0f;
  int mFilterMode = 0;
  uint32_t mControlCounter = 0;
};

#endif
