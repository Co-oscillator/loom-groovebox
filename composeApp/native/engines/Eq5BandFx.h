#ifndef EQ_5BAND_FX_H
#define EQ_5BAND_FX_H

#include <algorithm>
#include <cmath>
#include <vector>

/**
 * 5-Band Equalizer Effect
 * Bands: Low Shelf (80Hz), Low-Mid (250Hz), Mid (1k), High-Mid (4k), High Shelf
 * (10k)
 */
class Eq5BandFx {
public:
  struct Band {
    float frequency;
    float Q;
    float gain; // in dB
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float m1 = 0.0f, m2 = 0.0f; // state
    float g = 0.0f, k = 0.0f, A = 1.0f, d = 0.0f;
    bool dirty = true;

    Band(float f = 1000.0f, float q = 0.707f, float g_db = 0.0f)
        : frequency(f), Q(q), gain(g_db) {
      reset();
    }

    void reset() {
      m1 = 0;
      m2 = 0;
      dirty = true;
    }

    void updateCoefficients(float sampleRate) {
      if (!dirty) return;
      if (sampleRate <= 0.0f) return;
      
      // Limit frequency to avoid tangent explosion
      float freq = std::max(10.0f, std::min(frequency, sampleRate * 0.45f));
      g = std::tan((float)M_PI * freq / sampleRate);
      k = 1.0f / std::max(0.1f, Q);
      A = std::pow(10.0f, gain / 40.0f); // Square root of linear gain
      float denom = 1.0f + g * (g + k);
      d = (denom != 0.0f) ? 1.0f / denom : 0.0f;
      
      dirty = false;
    }
  };

  Eq5BandFx() {
    mBands[0] = Band(80.0f, 0.707f, 0.0f);   // Low Shelf
    mBands[1] = Band(250.0f, 1.0f, 0.0f);    // Low Mid
    mBands[2] = Band(1000.0f, 1.0f, 0.0f);   // Mid
    mBands[3] = Band(4000.0f, 1.0f, 0.0f);   // High Mid
    mBands[4] = Band(10000.0f, 0.707f, 0.0f); // High Shelf
    reset();
  }

  void setBandGain(int bandIdx, float gainDb) {
    if (bandIdx >= 0 && bandIdx < 5) {
      if (mBands[bandIdx].gain != gainDb) {
        mBands[bandIdx].gain = gainDb;
        mBands[bandIdx].dirty = true;
      }
    }
  }

  void reset() {
    for (auto &band : mBands) {
      band.reset();
    }
  }

  void setMix(float mix) { mMix = mix; }

  float processSample(float input, float sampleRate) {
    if (!std::isfinite(input))
      return 0.0f;
    if (sampleRate <= 0)
      return input;

    float out = input;
    for (int i = 0; i < 5; ++i) {
      mBands[i].updateCoefficients(sampleRate);
      out = processBand(i, out, sampleRate);
    }

    return input * (1.0f - mMix) + out * mMix;
  }

  void processBlock(float *inOut, int numFrames, float sampleRate) {
    // Update all coefficients once per block
    for (int i = 0; i < 5; ++i) {
      mBands[i].updateCoefficients(sampleRate);
    }
    
    for (int i = 0; i < numFrames; ++i) {
      inOut[i] = processSample(inOut[i], sampleRate);
    }
  }

  float process(float input, float sampleRate) {
    return processSample(input, sampleRate);
  }

  void clear() {
    for (auto &b : mBands)
      b.reset();
  }

private:
  float processBand(int idx, float input, float sampleRate) {
    auto &b = mBands[idx];

    // TPT SVF structure (Stable) - uses cached g, k, A, d
    float hp = (input - (b.k + b.g) * b.m1 - b.m2) * b.d;
    float bp = b.g * hp + b.m1;
    float lp = b.g * bp + b.m2;

    // Update state
    b.m1 = b.g * hp + bp;
    b.m2 = b.g * bp + lp;

    float wet = input;

    if (idx == 0) { // Low Shelf
      wet = input + (b.A * b.A - 1.0f) * lp + b.k * (b.A - 1.0f) * bp;
    } else if (idx == 4) { // High Shelf
      wet = input + (b.A * b.A - 1.0f) * hp + b.k * (b.A - 1.0f) * bp;
    } else { // Peaking
      wet = input + b.k * (b.A * b.A - 1.0f) * bp;
    }

    if (!std::isfinite(wet)) {
      b.reset();
      wet = input;
    }
    return wet;
  }

  Band mBands[5];
  float mMix = 1.0f;
};

#endif // EQ_5BAND_FX_H
