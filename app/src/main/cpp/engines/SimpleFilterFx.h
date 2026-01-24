#ifndef SIMPLE_FILTER_FX_H
#define SIMPLE_FILTER_FX_H

#include <algorithm>
#include <cmath>

class SimpleFilterFx {
public:
  enum Mode { LP = 0, HP = 1, BP = 2 };

  SimpleFilterFx() {}

  void setCutoff(float cutoff) {
    // Map 0..1 to 20Hz..20kHz exponential
    // f = 20 * (1000^val)
    float targetFreq = 20.0f * std::pow(1000.0f, cutoff);
    mTargetCutoff = std::clamp(targetFreq, 20.0f, 20000.0f);
  }

  void setResonance(float res) {
    // Map 0..1 to Q 0.707..10.0
    float targetQ = 0.707f + res * 9.3f;
    mTargetResonance = std::clamp(targetQ, 0.707f, 10.0f);
  }

  void setMode(float mode) {
    int m = static_cast<int>(mode);
    if (m >= 0 && m <= 2) {
      mMode = static_cast<Mode>(m);
    }
  }

  void setMix(float mix) { mMix = mix; }

  // Process mono sample
  float process(float input, float sampleRate) {
    if (mMix <= 0.001f)
      return input;

    // Smooth parameters
    mCutoff += 0.002f * (mTargetCutoff - mCutoff);
    mResonance += 0.002f * (mTargetResonance - mResonance);

    // SVF Algorithm (Chamberlin / State Variable)
    // Stability limit: f < sampleRate / 6
    float f = std::clamp(mCutoff, 20.0f, sampleRate / 6.0f);

    // Pre-warp factor mostly relevant near Nyquist, simplified here:
    // f = 2 * sin(PI * freq / sampleRate) for better tuning match
    float g = 2.0f * std::sin((float)M_PI * f / sampleRate);
    float q = 1.0f / mResonance;

    // 2x Oversampling loop? No, simple single sample step for efficiency
    // Use standard SVF form:
    // low = low + f * band
    // high = input - low - q*band
    // band = band + f * high

    float low, high, band, notch;

    // We run the filter structure:
    mLow = mLow + g * mBand;
    mHigh = input - mLow - q * mBand;
    mBand = mBand + g * mHigh;

    // Extra stability check (if things blow up)
    if (!std::isfinite(mLow))
      mLow = 0.0f;
    if (!std::isfinite(mBand))
      mBand = 0.0f;

    float wet = 0.0f;
    switch (mMode) {
    case LP:
      wet = mLow;
      break;
    case HP:
      wet = mHigh;
      break;
    case BP:
      wet = mBand;
      break;
    default:
      wet = mLow;
      break;
    }

    return input * (1.0f - mMix) + wet * mMix;
  }

  void clear() {
    mLow = 0.0f;
    mBand = 0.0f;
    mHigh = 0.0f;
  }

private:
  float mCutoff = 1000.0f;
  float mTargetCutoff = 1000.0f;
  float mResonance = 0.707f;
  float mTargetResonance = 0.707f;
  float mMix = 0.0f;
  Mode mMode = LP;

  // Filter State
  float mLow = 0.0f;
  float mBand = 0.0f;
  float mHigh = 0.0f;
};

#endif // SIMPLE_FILTER_FX_H
