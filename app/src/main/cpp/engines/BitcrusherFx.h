#ifndef BITCRUSHER_FX_H
#define BITCRUSHER_FX_H

#include <cmath>

class BitcrusherFx {
public:
  void setBits(int bits) { mBits = bits; }
  void setDownsample(int factor) { mDownsample = factor; }
  void setMix(float mix) { mMix = mix; }

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
  int mBits = 8;
  int mDownsample = 4;
  int mCounter = 0;
  float mLastOutput = 0.0f;
  float mMix = 1.0f;
};

#endif // BITCRUSHER_FX_H
