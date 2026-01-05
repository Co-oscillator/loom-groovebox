#ifndef PHASER_FX_H
#define PHASER_FX_H

#include <cmath>
#include <vector>

class PhaserFx {
public:
  void setRate(float v) { mRate = v; }
  void setDepth(float v) { mDepth = v; }
  void setFeedback(float v) { mFeedback = v; }
  void setMix(float v) { mMix = v; }
  void setIntensity(float v) { mFeedback = v * 0.95f; }

  void setParameters(float rate, float depth, float feedback) {
    mRate = rate;
    mDepth = depth;
    mFeedback = feedback;
  }

  void clear() {
    for (int i = 0; i < 4; ++i)
      mStageZ[i] = 0.0f;
    mLastOutput = 0.0f;
    mPhase = 0.0f;
  }

  float process(float input, float sampleRate) {
    mPhase += (2.0f * M_PI * mRate) / sampleRate;
    if (mPhase > 2.0f * M_PI)
      mPhase -= 2.0f * M_PI;

    float lfo = (sinf(mPhase) + 1.0f) * 0.5f; // 0.0 to 1.0
    float freq = 200.0f + lfo * mDepth * 4000.0f;
    float alpha = tanf(M_PI * freq / sampleRate);
    float a1 = (alpha - 1.0f) / (alpha + 1.0f);

    float x = input + mFeedback * mLastOutput;

    // 4 stages of all-pass filters
    for (int i = 0; i < 4; ++i) {
      float y = a1 * x + mStageZ[i];
      mStageZ[i] = x - a1 * y;
      if (std::abs(mStageZ[i]) < 1.0e-15f)
        mStageZ[i] = 0.0f;
      x = y;
    }

    if (std::abs(x) < 1.0e-15f)
      x = 0.0f;
    mLastOutput = x;
    return x * 0.5f * mMix;
  }

private:
  float mStageZ[4] = {0.0f, 0.0f, 0.0f, 0.0f};
  float mLastOutput = 0.0f;
  float mPhase = 0.0f;
  float mRate = 0.5f;
  float mDepth = 0.5f;
  float mFeedback = 0.5f;
  float mMix = 1.0f;
};

#endif // PHASER_FX_H
