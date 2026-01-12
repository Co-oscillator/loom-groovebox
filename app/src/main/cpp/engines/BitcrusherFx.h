#ifndef BITCRUSHER_FX_H
#define BITCRUSHER_FX_H

#include <cmath>

class BitcrusherFx {
public:
  void setBits(float v) {
    mBits = 1.0f + v * 15.0f; // 1 to 16 bits
  }
  void setDownsample(float v) {
    mDownsample = 1 + (int)(v * 31.0f); // 1x to 32x downsampling
  }
  void setRate(float v) { setDownsample(v); }
  void setMix(float v) { mMix = v; }

  float process(float input) {
    // Parameter Smoothing
    mSmoothedBits += 0.01f * (mBits - mSmoothedBits);
    mSmoothedRate += 0.01f * (mDownsample - mSmoothedRate);

    // Sample rate reduction
    int effectiveRate = (int)mSmoothedRate;
    if (effectiveRate < 1)
      effectiveRate = 1;

    if (mCounter++ % effectiveRate != 0) {
      return mLastOutput;
    }

    // Bit depth reduction
    // Use symmetric rounding to prevent DC offset/crackle on silent signals
    float step = powf(2.0f, mSmoothedBits - 1.0f);
    float crushed = roundf(input * step) / step;

    mLastOutput = crushed;
    return crushed * mMix;
  }

private:
  float mBits = 8.0f;
  float mSmoothedBits = 8.0f; // Smoothed
  int mDownsample = 4;
  float mSmoothedRate = 4.0f; // Smoothed
  int mCounter = 0;
  float mLastOutput = 0.0f;
  float mMix = 1.0f;
};

#endif // BITCRUSHER_FX_H
