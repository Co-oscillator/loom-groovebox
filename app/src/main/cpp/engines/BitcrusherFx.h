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
    // Sample rate reduction
    if (mCounter++ % mDownsample != 0) {
      return mLastOutput;
    }

    // Bit depth reduction
    float step = powf(2.0f, mBits - 1);
    float crushed = floorf(input * step) / step;

    mLastOutput = crushed;
    return crushed * mMix;
  }

private:
  float mBits = 8.0f;
  int mDownsample = 4;
  int mCounter = 0;
  float mLastOutput = 0.0f;
  float mMix = 1.0f;
};

#endif // BITCRUSHER_FX_H
