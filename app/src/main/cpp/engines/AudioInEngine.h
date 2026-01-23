#include "../Utils.h"
#include "Adsr.h"
#include <algorithm>
#include <vector>

class AudioInEngine {
public:
  struct Voice {
    bool active = false;
    Adsr ampEnv;
    Adsr filterEnv;
    TSvf svf;

    void reset() {
      active = false;
      ampEnv.reset();
      filterEnv.reset();
      svf.setParams(1000.0f, 0.7f, 48000.0f);
    }
  };

  AudioInEngine() {
    mVoices.resize(1); // Mono "oscillator" style for now
    mVoices[0].reset();
  }

  void resetToDefaults() {
    mGated = true;
    mGain = 1.0f;
    mCutoff = 1.0f;
    mResonance = 0.0f;
    mFilterAmt = 0.0f;
    mWavefold = 0.0f;
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 1.0f;
    mRelease = 0.1f;
    mF_Atk = 0.01f;
    mF_Dcy = 0.1f;
    mF_Sus = 1.0f;
    mF_Rel = 0.1f;
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      v.ampEnv.setSampleRate(sr);
      v.filterEnv.setSampleRate(sr);
    }
  }

  void triggerNote(int note, int velocity) {
    mVoices[0].active = true;
    mVoices[0].ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
    mVoices[0].filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);
    mVoices[0].ampEnv.trigger();
    mVoices[0].filterEnv.trigger();
  }

  void releaseNote(int note) {
    mVoices[0].ampEnv.release();
    mVoices[0].filterEnv.release();
  }

  void setParameter(int id, float value) {
    switch (id) {
    case 100:
      mAttack = value;
      break;
    case 101:
      mDecay = value;
      break;
    case 102:
      mSustain = value;
      break;
    case 103:
      mRelease = value;
      break;
    case 112:
      mCutoff = value;
      break;
    case 113:
      mResonance = value;
      break;
    case 114:
      mF_Atk = value;
      break;
    case 115:
      mF_Dcy = value;
      break;
    case 116:
      mF_Sus = value;
      break;
    case 117:
      mF_Rel = value;
      break;
    case 118:
      mFilterAmt = value;
      break;
    case 120:
      mGated = (value > 0.5f);
      break;
    case 121:
      mGain = value;
      break;
    case 122:
      mWavefold = value;
      break;
    case 123:
      mFilterMode = (int)(value * 2.9f); // 0=LP, 1=HP, 2=BP
      break;
    }
  }

  float render(float inputSample) {
    Voice &v = mVoices[0];

    // DC Blocker (Simple One-Pole High-Pass at ~10Hz)
    // y[n] = x[n] - x[n-1] + R * y[n-1]
    float x = inputSample;
    float y = x - mLastX + 0.999f * mLastY;
    mLastX = x;
    mLastY = y;
    float dcBlocked = y;

    float env = 1.0f;
    float fEnv = 0.0f;

    if (mGated) {
      env = v.ampEnv.nextValue();
      fEnv = v.filterEnv.nextValue();
      if (!v.ampEnv.isActive()) {
        v.active = false;
        // Clear filter state when inactive to prevent denormal/resonance
        // build-up
        v.svf.setParams(1000.0f, 0.7f, 48000.0f); // Default safe point
        return 0.0f;
      }
    }

    float out = fast_tanh(dcBlocked * mGain * env);

    // Wavefolding (Iterative folding)
    if (mWavefold > 0.001f) {
      float foldAmount = 1.0f + mWavefold * 10.0f;
      out *= foldAmount;
      // Simple folding: if > 1, fold back (reflected)
      // Iterative approach
      for (int i = 0; i < 3; ++i) { // Limit iterations for performance
        if (out > 1.0f)
          out = 2.0f - out;
        else if (out < -1.0f)
          out = -2.0f - out;
        else
          break;
      }
      out = fast_tanh(out);
    }

    // Stable TSvf Filter
    float cutoffNormalized = mCutoff + (fEnv * mFilterAmt);
    cutoffNormalized = std::max(0.001f, std::min(0.999f, cutoffNormalized));

    // Exponential mapping
    float low = 20.0f;
    float high = std::min(mSampleRate * 0.49f, 20000.0f);
    float freq = low * powf(high / low, cutoffNormalized);

    float resonance = std::max(0.1f, mResonance);
    v.svf.setParams(freq, resonance, mSampleRate);

    TSvf::Type type = TSvf::LowPass;
    if (mFilterMode == 1)
      type = TSvf::HighPass;
    else if (mFilterMode == 2)
      type = TSvf::BandPass;

    float filtered = v.svf.process(out, type);

    // Use fast_tanh on output for safety and extra gain
    return fast_tanh(filtered * 1.2f);
  }

private:
  std::vector<Voice> mVoices;
  float mSampleRate = 48000.0f;
  float mLastX = 0.0f;
  float mLastY = 0.0f;
  bool mGated = true;
  float mGain = 1.0f;
  float mWavefold = 0.0f;
  float mCutoff = 1.0f, mResonance = 0.0f, mFilterAmt = 0.0f;
  int mFilterMode = 0; // 0=LP, 1=HP, 2=BP
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 1.0f, mRelease = 0.1f;
  float mF_Atk = 0.01f, mF_Dcy = 0.1f, mF_Sus = 1.0f, mF_Rel = 0.1f;
};
