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
    // Pre-calculate coefficients could be done here for optimization,
    // but calculating per-sample is okay for now or valid in nextValue with
    // pow/exp approximations. For simple exp decay: val *= coeff.
    mDecayCoeff = exp(-1.0f / (mDecay * mSampleRate * 0.2f + 1.0f));
    mReleaseCoeff = exp(-1.0f / (mRelease * mSampleRate * 0.2f + 1.0f));
    mAttackRate = 1.0f / (mAttack * mSampleRate + 1.0f);
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
      mValue *= mDecayCoeff;
      if (mValue <= mSustain) {
        mValue = mSustain;
        mStage = AdsrStage::Sustain;
      }
      // Fallback linear check to prevent getting stuck if coeff is too slow
      // close to target? For simple exp, we approach 0. But we want to approach
      // Sustain. Better Exp Decay to Sustain: current = target + (current -
      // target) * coeff
      mValue = mSustain + (mValue - mSustain) * mDecayCoeff;
      // Fix: If we are very close to Sustain (or overshoot/undershoot due to
      // Zeno), snap to it. Also handles Sustain=0 case where we might approach
      // 0 forever.
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
