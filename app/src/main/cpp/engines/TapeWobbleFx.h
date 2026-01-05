#ifndef TAPE_WOBBLE_FX_H
#define TAPE_WOBBLE_FX_H

#include <cmath>
#include <random>
#include <vector>

class TapeWobbleFx {
public:
  TapeWobbleFx(int maxDelay = 2048) {
    mBuffer.resize(maxDelay, 0.0f);
    mRandEngine.seed(std::random_device{}());
  }

  void setRate(float v) { mRate = v; }
  void setDepth(float v) { mDepth = v; }
  void setSaturation(float v) { mSaturation = v; }
  void setMix(float v) { mMix = v; }

  void setParameters(float rate, float depth, float saturation, float mix) {
    mRate = rate;
    mDepth = depth;
    mSaturation = saturation;
    mMix = mix;
  }

  void clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mWritePos = 0;
    mPhase = 0.0f;
  }

  float process(float input, float sampleRate) {
    // Slow modulation for tape wobble
    mPhase += (2.0f * M_PI * mRate) / sampleRate;
    if (mPhase > 2.0f * M_PI) {
      mPhase -= 2.0f * M_PI;
      // Add slight random variation to rate for more "authentic" wobble
      std::uniform_real_distribution<float> dist(-0.05f, 0.05f);
      mRandomOffset = dist(mRandEngine);
    }

    float mod = sinf(mPhase + mRandomOffset);
    // Delay modulated around 10ms
    // Depth controls intensity of pitch wobble
    float delaySamples = (10.0f + mod * mDepth * 8.0f) * (sampleRate / 1000.0f);

    float tap = getInterpolatedTap(delaySamples);

    // Apply Saturation (Tape Crunch)
    if (mSaturation > 0.0f) {
      float drive = 1.0f + mSaturation * 4.0f;
      tap = std::tanh(tap * drive) / std::tanh(drive);
    }

    mBuffer[mWritePos] = input;
    mWritePos = (mWritePos + 1) % mBuffer.size();

    return input * (1.0f - mMix) + tap * mMix;
  }

private:
  float getInterpolatedTap(float delaySamples) {
    float readPos = (float)mWritePos - delaySamples;
    while (readPos < 0)
      readPos += mBuffer.size();
    while (readPos >= mBuffer.size()) // Safety
      readPos -= mBuffer.size();

    int i1 = (int)readPos;
    int i2 = (i1 + 1) % mBuffer.size();
    float frac = readPos - i1;

    return mBuffer[i1] * (1.0f - frac) + mBuffer[i2] * frac;
  }

  std::vector<float> mBuffer;
  int mWritePos = 0;
  float mPhase = 0.0f;
  float mRate = 0.5f;
  float mDepth = 0.5f;
  float mSaturation = 0.0f;
  float mMix = 0.5f;
  float mRandomOffset = 0.0f;
  std::mt19937 mRandEngine;
};

#endif // TAPE_WOBBLE_FX_H
