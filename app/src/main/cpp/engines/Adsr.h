#ifndef ADSR_H
#define ADSR_H

#include <cmath>

enum class AdsrStage { Idle, Attack, Decay, Sustain, Release };

class Adsr {
public:
  void setSampleRate(float sr) { mSampleRate = sr; }
  void setParameters(float a, float d, float s, float r) {
    mAttack = a;
    mDecay = d;
    mSustain = s;
    mRelease = r;

    // Scaling: quadratic instead of cubic for better range at low values
    float aScl = mAttack * mAttack;
    float dScl = mDecay * mDecay;
    float rScl = mRelease * mRelease;

    // Longer max times and smoother curves
    mDecayCoeff = exp(-1.0f / (dScl * mSampleRate * 5.0f + 10.0f));
    mReleaseCoeff = exp(-1.0f / (rScl * mSampleRate * 5.0f + 10.0f));
    mAttackRate = 1.0f / (aScl * mSampleRate * 3.0f + 5.0f);
  }

  void trigger() { mStage = AdsrStage::Attack; }

  void release() {
    if (mStage != AdsrStage::Idle) {
      mStage = AdsrStage::Release;
    }
  }

  void reset() {
    mStage = AdsrStage::Idle;
    mValue = 0.0f;
  }

  float nextValue() {
    switch (mStage) {
    case AdsrStage::Idle:
      return 0.0f;
    case AdsrStage::Attack:
      mValue += mAttackRate;
      if (mValue >= 1.0f) {
        mValue = 1.0f;
        mStage = AdsrStage::Decay;
      }
      break;
    case AdsrStage::Decay:
      // Exponential Decay towards Sustain level: current = target + (current -
      // target) * coeff
      mValue = mSustain + (mValue - mSustain) * mDecayCoeff;

      if (std::abs(mValue - mSustain) < 0.0001f || mValue <= mSustain) {
        mValue = mSustain;
        mStage = AdsrStage::Sustain;
      }
      break;
    case AdsrStage::Sustain:
      mValue = mSustain;
      break;
    case AdsrStage::Release:
      mValue *= mReleaseCoeff;
      if (mValue < 0.0001f) {
        mValue = 0.0f;
        mStage = AdsrStage::Idle;
      }
      break;
    }
    return mValue;
  }

  bool isActive() const { return mStage != AdsrStage::Idle; }
  float getValue() const { return mValue; }

private:
  float mSampleRate = 44100.0f;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 0.8f, mRelease = 0.5f;

  float mDecayCoeff = 0.999f;
  float mReleaseCoeff = 0.999f;
  float mAttackRate = 0.01f;

  float mValue = 0.0f;
  AdsrStage mStage = AdsrStage::Idle;
};

#endif
