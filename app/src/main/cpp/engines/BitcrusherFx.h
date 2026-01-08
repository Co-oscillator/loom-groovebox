#ifndef BITCRUSHER_FX_H
#define BITCRUSHER_FX_H

#include <cmath>

class BitcrusherFx {
public:
  void updateSampleRate(float sr) {}
  void setBits(int bits) { mBits = bits; }
  void setDownsample(int factor) { mDownsample = factor; }
  void setMix(float mix) { mMix = mix; }

  void setParameters(float depth, float downsample, float mix) {
    // Map normalized parameters to internal state
    // Depth (0-1) -> Bits (16 down to 1). Higher depth = fewer bits
    int bits = 16 - (int)(depth * 15.0f);
    if (bits < 1)
      bits = 1;

    // Downsample (0-1) -> Factor (1 up to 50). Higher = more downsampling
    int ds = 1 + (int)(downsample * 49.0f);

    setBits(bits);
    setDownsample(ds);
    setMix(mix);
  }

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
