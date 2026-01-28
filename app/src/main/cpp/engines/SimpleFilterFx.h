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
    if (mMix <= 0.001f || sampleRate <= 0.0f)
      return input;

    // Smooth parameters
    mCutoff += 0.002f * (mTargetCutoff - mCutoff);
    mResonance += 0.002f * (mTargetResonance - mResonance);

    float f_clipped = std::clamp(mCutoff, 20.0f, sampleRate / 6.0f);
    float g = std::tan((float)M_PI * f_clipped / sampleRate);
    float q = 1.0f / mResonance;
    float d = 1.0f / (1.0f + g * (g + q));

    float hp = (input - (q + g) * mState1 - mState2) * d;
    float bp = g * hp + mState1;
    mState1 = g * hp + bp;
    float lp = g * bp + mState2;
    mState2 = g * bp + lp;

    float wet = 0.0f;
    switch (mMode) {
    case LP:
      wet = lp;
      break;
    case HP:
      wet = hp;
      break;
    case BP:
      wet = bp;
      break;
    default:
      wet = lp;
      break;
    }

    return input * (1.0f - mMix) + wet * mMix;
  }

  void clear() {
    mState1 = 0.0f;
    mState2 = 0.0f;
  }

private:
  float mCutoff = 1000.0f;
  float mTargetCutoff = 1000.0f;
  float mResonance = 0.707f;
  float mTargetResonance = 0.707f;
  float mMix = 0.0f;
  Mode mMode = LP;

  // Filter State (TPT form)
  float mState1 = 0.0f;
  float mState2 = 0.0f;
};

#endif // SIMPLE_FILTER_FX_H
