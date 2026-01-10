#ifndef FILTER_LFO_FX_H
#define FILTER_LFO_FX_H

#include <algorithm>
#include <cmath>
#include <cstdlib>

enum class FilterLfoMode { LowPass, HighPass };

class FilterLfoFx {
public:
  FilterLfoFx(FilterLfoMode mode) : mMode(mode) {}

  void setRate(float v) { mRate = v; }
  void setDepth(float v) { mDepth = v; }
  void setShape(float v) { mShape = v; }
  void setCutoff(float v) { mCutoff = v; }
  void setResonance(float v) { mResonance = v; }

  void setParameters(float rate, float depth, float shape, float cutoff,
                     float resonance) {
    mRate = rate;
    mDepth = depth;
    mShape = shape;
    mCutoff = cutoff;
    mResonance = resonance;
  }

  float process(float input, float sampleRate) {
    // Control rate update (every 16 samples)
    if (mControlCounter++ % 16 == 0) {
      // 1. LFO Calculation (Control Rate)
      float rateHz = 0.1f + (mRate * mRate) * 19.9f;
      // Increment phase by 16 samples worth of time
      mPhase += (rateHz * 16.0f) / sampleRate;
      if (mPhase >= 1.0f) {
        mPhase -= floorf(mPhase);
        mNoiseSeed = (mNoiseSeed * 1103515245 + 12345);
        mNoiseSample =
            (static_cast<float>(mNoiseSeed) / 4294967296.0f) * 2.0f - 1.0f;
      }

      float lfoValue = 0.0f;
      int shapeIdx = static_cast<int>(mShape * 4.99f);
      switch (shapeIdx) {
      case 0: {
        // Approximate sine for LFO to save CPU
        float x = mPhase * 6.283185f;
        if (x < 3.141593f)
          lfoValue = 1.273239f * x - 0.4052847f * x * x;
        else
          lfoValue = -1.273239f * (x - 3.141593f) +
                     0.4052847f * (x - 3.141593f) * (x - 3.141593f);
        break;
      }
      case 1:
        lfoValue =
            (mPhase < 0.5f) ? (4.0f * mPhase - 1.0f) : (3.0f - 4.0f * mPhase);
        break;
      case 2:
        lfoValue = (mPhase < 0.5f) ? 1.0f : -1.0f;
        break;
      case 3:
        lfoValue = 2.0f * mPhase - 1.0f;
        break;
      case 4:
        lfoValue = mNoiseSample;
        break;
      }

      float mod = lfoValue * mDepth;
      float currentCutoff = mCutoff + mod;
      currentCutoff = std::max(0.001f, std::min(0.999f, currentCutoff));

      mSmoothedCutoff += 0.04f * (currentCutoff - mSmoothedCutoff);
      mSmoothedRes += 0.04f * (mResonance - mSmoothedRes);

      // Exponential mapping is expensive, done at control rate
      float targetFreq = 10.0f * powf(2000.0f, mSmoothedCutoff);
      if (targetFreq > sampleRate * 0.45f)
        targetFreq = sampleRate * 0.45f;

      mG = tanf((float)M_PI * targetFreq / sampleRate);
      mK = 2.0f - (1.9f * mSmoothedRes);
      mA1 = 1.0f / (1.0f + mG * (mG + mK));
      mA2 = mG * mA1;
      mA3 = mG * mA2;
    }

    // 2. Filter Processing (Per Sample - Optimized)
    float v3 = input - mSvfZ2;
    float v1 = mA1 * mSvfZ1 + mA2 * v3;
    float v2 = mSvfZ2 + mA2 * mSvfZ1 + mA3 * v3;

    mSvfZ1 = 2.0f * v1 - mSvfZ1;
    mSvfZ2 = 2.0f * v2 - mSvfZ2;

    // Flush to zero for denormals
    if (std::abs(mSvfZ1) < 1.0e-15f)
      mSvfZ1 = 0.0f;
    if (std::abs(mSvfZ2) < 1.0e-15f)
      mSvfZ2 = 0.0f;

    if (mMode == FilterLfoMode::LowPass)
      return v2;
    else
      return input - mK * v1 - v2;
  }

  void reset(float sampleRate) {
    mPhase = 0.0f;
    mSvfZ1 = 0.0f;
    mSvfZ2 = 0.0f;
    mSmoothedCutoff = mCutoff;
    mSmoothedRes = mResonance;
    mControlCounter = 0;
  }

private:
  FilterLfoMode mMode;
  float mRate = 0.5f;
  float mDepth = 0.0f;
  float mShape = 0.0f;
  float mCutoff = 0.5f;
  float mResonance = 0.0f;

  float mPhase = 0.0f;
  unsigned int mNoiseSeed = 12345;
  float mNoiseSample = 0.0f;

  float mSvfZ1 = 0.0f;
  float mSvfZ2 = 0.0f;
  float mSmoothedCutoff = 0.5f;
  float mSmoothedRes = 0.0f;
  uint32_t mControlCounter = 0;

  float mG = 0.0f, mK = 0.0f;
  float mA1 = 0.0f, mA2 = 0.0f, mA3 = 0.0f;
};

#endif // FILTER_LFO_FX_H
