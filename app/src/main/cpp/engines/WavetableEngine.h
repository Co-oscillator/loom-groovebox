#ifndef WAVETABLE_ENGINE_H
#define WAVETABLE_ENGINE_H

#include "../WavFileUtils.h"
#include "Adsr.h"
#include <algorithm>
#include <cmath>
#include <random>
#include <vector>

class WavetableEngine {
public:
  struct Voice {
    bool active = false;
    int note = -1;
    float frequency = 440.0f;
    float amplitude = 1.0f;
    float phase = 0.0f;
    float unisonPhases[16] = {0};
    Adsr ampEnv;
    Adsr filterEnv;

    float svf_L = 0, svf_B = 0, svf_H = 0;

    // Crush state
    float lastCrushSample = 0.0f;
    float crushPhase = 0.0f;

    void reset() {
      active = false;
      note = -1;
      ampEnv.reset();
      filterEnv.reset();
      svf_L = svf_B = svf_H = 0.0f;
      svf_L = svf_B = svf_H = 0.0f;
      phase = 0.0f;
      crushPhase = 0.0f;
      lastCrushSample = 0.0f;
      for (int i = 0; i < 16; ++i)
        unisonPhases[i] = (float)rand() / (float)RAND_MAX;
    }
  };

  WavetableEngine() {
    mWaveforms.resize(2);
    generateBasicWaveforms();
    mVoices.resize(6);
    for (auto &v : mVoices)
      v.reset();
    resetToDefaults();
  }

  void resetToDefaults() {
    mMorphPos = 0.0f;
    mUnisonDetune = 0.01f;
    mUnisonSpread = 0.5f;
    mUnisonVoices = 1;
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 0.8f;
    mRelease = 0.5f;
    mFilterAttack = 0.01f;
    mFilterDecay = 0.1f;
    mFilterSustain = 0.0f;
    mFilterRelease = 0.5f;
    mCutoff = 0.5f;
    mResonance = 0.0f;
    mFilterEnvAmount = 0.0f;
    mWarpAmount = 0.0f;
    mCrushAmount = 0.0f;
    mDriveAmount = 1.0f;
    updateLiveEnvelopes();
  }

  void setWavetable(const std::vector<std::vector<float>> &table) {
    if (table.empty() || table[0].empty())
      return;
    mWaveforms = table;
    mTableSize = mWaveforms[0].size();
  }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.ampEnv.reset();
      v.filterEnv.reset();
    }
  }

  void loadWavetable(const std::string &path) {
    std::vector<float> data;
    int sr, ch;
    std::vector<float> slices; // Unused
    if (WavFileUtils::loadWav(path, data, sr, ch, slices)) {
      if (data.empty())
        return;

      // Truncate or Pad to 2048 samples for single-cycle
      // If stereo, take left channel (WavFileUtils returns interleaved, but we
      // need to handle mono/stereo) Wait, WavFileUtils loadWav returns
      // interleaved float data. We need to extracting channel 0.

      std::vector<float> processed;
      processed.reserve(2048);

      for (int i = 0; i < data.size(); i += ch) {
        if (processed.size() >= 2048)
          break;
        processed.push_back(data[i]);
      }

      // If short, pad with zeros (or loop? Python script didn't loop, just
      // truncated)
      while (processed.size() < 2048) {
        processed.push_back(0.0f);
      }

      // Replace waveforms with single frame
      mWaveforms.clear();
      mWaveforms.push_back(processed);
      mTableSize = 2048;
    }
  }

  void loadDefaultWavetable() { generateBasicWaveforms(); }

  void setFrequency(float freq, float sampleRate) { mSampleRate = sampleRate; }

  void triggerNote(int note, int velocity) {
    int idx = -1;
    for (int i = 0; i < 6; ++i) {
      if (!mVoices[i].active) {
        idx = i;
        break;
      }
    }
    if (idx == -1)
      idx = 0;

    Voice &v = mVoices[idx];
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    v.frequency = 440.0f * powf(2.0f, (note - 69) / 12.0f);

    v.ampEnv.setSampleRate(mSampleRate);
    v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.filterEnv.setSampleRate(mSampleRate);
    v.filterEnv.setParameters(mFilterAttack, mFilterDecay, mFilterSustain,
                              mFilterRelease);

    v.ampEnv.trigger();
    v.filterEnv.trigger();
    v.svf_L = v.svf_B = v.svf_H = 0.0f;
    v.phase = 0.0f;
    for (int i = 0; i < 16; ++i)
      v.unisonPhases[i] = (float)rand() / (float)RAND_MAX;
  }

  void releaseNote(int note) {
    for (auto &v : mVoices) {
      if (v.active && v.note == note) {
        v.ampEnv.release();
        v.filterEnv.release();
      }
    }
  }

  void setParameter(int id, float value) {
    float v = std::max(0.0f, std::min(1.0f, value));
    if (id == 0)
      mMorphPos = v * (mWaveforms.size() - 1.001f);
    else if (id == 1)
      mUnisonDetune = v * 0.05f;
    else if (id == 2)
      mUnisonSpread = v;
    else if (id == 3)
      mUnisonVoices = 1 + static_cast<int>(v * 6.0f);
    else if (id == 4) {
      mAttack = v;
      updateLiveEnvelopes();
    } else if (id == 5) {
      mDecay = v;
      updateLiveEnvelopes();
    } else if (id == 6) {
      mSustain = v;
      updateLiveEnvelopes();
    } else if (id == 7) {
      mRelease = v;
      updateLiveEnvelopes();
    } else if (id == 8)
      mCutoff = v;
    else if (id == 9)
      mResonance = v;
    else if (id == 10) {
      mFilterAttack = v;
      updateLiveEnvelopes();
    } else if (id == 11) {
      mFilterDecay = v;
      updateLiveEnvelopes();
    } else if (id == 12) {
      mFilterSustain = v;
      updateLiveEnvelopes();
    } else if (id == 13) {
      mFilterRelease = v;
      updateLiveEnvelopes();
    } else if (id == 14)
      mFilterEnvAmount = v * 2.0f - 1.0f;

    else if (id == 15)
      mWarpAmount = v;
    else if (id == 16)
      mCrushAmount = v;
    // NEW CONTROLS
    else if (id == 17)
      mDriveAmount = 1.0f + (v * 10.0f); // Pre-filter Drive
  }

  // Setters for generic modulation
  void setWavetableIndex(int index) { mMorphPos = (float)index; } // Approximate
  void setFilterCutoff(float v) { mCutoff = v; }
  void setResonance(float v) { mResonance = v; }
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

  // --- 1. THE WARP FUNCTION (Digital Drama Source) ---
  // This bends the phase.
  // If warp > 0, it squashes the wave towards the middle (PWM effect).
  inline float getWarpedPhase(float phase, float amount) {
    if (amount < 0.001f)
      return phase;

    // "Bend" Algorithm
    // This creates the classic "Reese" or "Modern Talking" articulation
    float bend = 0.5f - (amount * 0.49f); // Converge to center
    if (phase < bend) {
      return 0.5f * (phase / bend);
    } else {
      return 0.5f + 0.5f * ((phase - bend) / (1.0f - bend));
    }
  }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;

    int frameA = static_cast<int>(mMorphPos);
    int frameB = std::min((int)mWaveforms.size() - 1, frameA + 1);
    float frameFrac = mMorphPos - static_cast<float>(frameA);

    for (auto &v : mVoices) {
      if (!v.active)
        continue;

      float envVal = v.ampEnv.nextValue();
      if (envVal < 0.0001f && !v.ampEnv.isActive()) {
        v.active = false;
        continue;
      }
      activeCount++;

      float output = 0.0f;
      float normalization = 1.0f / sqrtf((float)mUnisonVoices);

      for (int i = 0; i < mUnisonVoices; ++i) {
        float detuneOffset = 0.0f;
        if (mUnisonVoices > 1) {
          float distrib = (float)i / (mUnisonVoices - 1) * 2.0f - 1.0f;
          detuneOffset =
              distrib * mUnisonDetune * (0.5f + mUnisonSpread * 0.5f);
        }

        float phaseInc = (v.frequency * (1.0f + detuneOffset)) / mSampleRate;
        v.unisonPhases[i] += phaseInc;
        if (v.unisonPhases[i] >= 1.0f)
          v.unisonPhases[i] -= 1.0f;

        // APPLY WARP: Modulate phase before lookup
        float warpedPhase = getWarpedPhase(v.unisonPhases[i], mWarpAmount);

        float sA = getSample(frameA, warpedPhase);
        float sB = getSample(frameB, warpedPhase);
        output += sA + frameFrac * (sB - sA);
      }

      output = output * normalization * v.amplitude * envVal;

      // --- 2. CRUSH (Downsampler) ---
      // Holds the sample state to create stair-stepping artifacts
      if (mCrushAmount > 0.0f) {
        float crushRate = 1.0f - (mCrushAmount * 0.95f); // 1.0 down to 0.05
        v.crushPhase += crushRate;
        if (v.crushPhase >= 1.0f) {
          v.crushPhase -= 1.0f;
          v.lastCrushSample = output; // Update S&H
        }
        output = v.lastCrushSample;
      }

      // --- 3. DRIVE ---
      // Hard clip into the filter to change filter reaction
      output *= mDriveAmount;
      output = std::tanh(output);

      // Filter Logic (Standard SVF)
      float fEnv = v.filterEnv.nextValue();
      float modCutoff =
          std::max(0.0f, std::min(1.0f, mCutoff + fEnv * mFilterEnvAmount));

      // Improved Frequency Mapping (Exponential)
      float targetFreq = 20.0f * powf(1000.0f, modCutoff);
      // Clamp to Nyquist to prevent explosion
      targetFreq = std::min(targetFreq, mSampleRate * 0.45f);

      float f = 2.0f * sinf(M_PI * targetFreq / mSampleRate);
      float q = 1.0f - mResonance * 0.95f;
      if (q < 0.05f)
        q = 0.05f; // Self-oscillate protection

      v.svf_H = output - v.svf_L - q * v.svf_B;
      v.svf_B = f * v.svf_H + v.svf_B;
      v.svf_L = f * v.svf_B + v.svf_L;

      // Smooth Filter Mode Mix
      // Instead of the hard "if/else" switch, use LP as base
      // and mix in other bands based on parameter?
      // For now, let's just output Lowpass for that "fat" sound,
      // or a mix if you want a specific character.
      // Keeping it simple: pure Lowpass usually sounds "biggest".
      float res = v.svf_L;

      mixedOutput += res;
    }

    if (activeCount > 1) {
      // User reported clipping with sqrt(n). Switching to 1/n for stricter
      // safety.
      float polyNormalization = 1.0f / (float)activeCount;
      mixedOutput *= polyNormalization;
    }
    return std::tanh(mixedOutput); // Final safety limiter
  }

  void updateSampleRate(float sr) { mSampleRate = sr; }
  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    return false;
  }

private:
  void updateLiveEnvelopes() {
    for (auto &v : mVoices) {
      if (v.active) {
        v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
        v.filterEnv.setParameters(mFilterAttack, mFilterDecay, mFilterSustain,
                                  mFilterRelease);
      }
    }
  }

  inline float cubicInterp(float y0, float y1, float y2, float y3, float mu) {
    float mu2 = mu * mu;
    float a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3;
    float a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    float a2 = -0.5f * y0 + 0.5f * y2;
    float a3 = y1;
    return (a0 * mu * mu2) + (a1 * mu2) + (a2 * mu) + a3;
  }

  float getSample(int frameIdx, float phase) {
    float pos = phase * mTableSize;
    int i1 = static_cast<int>(pos);
    float frac = pos - i1;

    int i0 = (i1 - 1 + mTableSize) % mTableSize;
    int i2 = (i1 + 1) % mTableSize;
    int i3 = (i1 + 2) % mTableSize;

    return cubicInterp(mWaveforms[frameIdx][i0], mWaveforms[frameIdx][i1],
                       mWaveforms[frameIdx][i2], mWaveforms[frameIdx][i3],
                       frac);
  }

  void generateBasicWaveforms() {
    mTableSize = 2048;
    int numFrames = 64;
    mWaveforms.resize(numFrames);
    for (int f = 0; f < numFrames; ++f) {
      mWaveforms[f].resize(mTableSize);
      float morph = (float)f / (numFrames - 1);
      for (int i = 0; i < mTableSize; ++i) {
        float p = (float)i / mTableSize;
        float sine = sinf(p * 2.0f * M_PI);
        float saw = 2.0f * (p - floorf(p + 0.5f));
        mWaveforms[f][i] = sine * (1.0f - morph) + saw * morph;
      }
    }
  }

  std::vector<Voice> mVoices;
  std::vector<std::vector<float>> mWaveforms;
  int mTableSize = 2048;
  float mSampleRate = 44100.0f;
  float mMorphPos = 0.0f, mUnisonDetune = 0.0f, mUnisonSpread = 0.5f;
  int mUnisonVoices = 1;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 0.8f, mRelease = 0.5f;
  float mFilterAttack = 0.01f, mFilterDecay = 0.1f, mFilterSustain = 0.0f,
        mFilterRelease = 0.5f;
  float mCutoff = 0.5f, mResonance = 0.0f, mFilterEnvAmount = 0.0f;
  float mWarpAmount = 0.0f;
  float mCrushAmount = 0.0f;
  float mDriveAmount = 1.0f;
};

#endif
