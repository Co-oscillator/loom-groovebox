#ifndef UTILS_H
#define UTILS_H

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <vector>

#define LOG_TAG "Groovebox"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static inline float fast_tanh(float x) {
  if (x < -3.0f)
    return -1.0f;
  if (x > 3.0f)
    return 1.0f;
  float x2 = x * x;
  return x * (27.0f + x2) / (27.0f + 9.0f * x2);
}

// Fast Sine Approximation using Look-Up Table
struct FastSine {
  static const int TABLE_SIZE = 2048;
  static const int MASK = TABLE_SIZE - 1;
  std::vector<float> table;

  FastSine() {
    table.resize(TABLE_SIZE +
                 1); // +1 for guard point (no wrapping needed for linear interp
                     // of last segment if we handle it)
    for (int i = 0; i < TABLE_SIZE; ++i) {
      table[i] = sinf((float)i * 2.0f * (float)M_PI / (float)TABLE_SIZE);
    }
    table[TABLE_SIZE] = 0.0f; // Wrap around for 2PI
  }

  // Input: Phase in radians [0, 2PI]
  // Uses linear interpolation
  inline float sin(float radians) {
    // Normalize to 0..TABLE_SIZE
    // 2PI -> TABLE_SIZE
    // factor = TABLE_SIZE / 2PI
    static const float factor = (float)TABLE_SIZE / (2.0f * (float)M_PI);

    float indexFloat = radians * factor;
    int indexInt = (int)indexFloat;
    float frac = indexFloat - (float)indexInt;

    // Wrap index
    indexInt &= MASK;

    // Linear Interp
    // We use MASK for next index too to be safe, though +1 is usually enough if
    // table has it
    int nextIndex = (indexInt + 1) & MASK;

    return table[indexInt] + frac * (table[nextIndex] - table[indexInt]);
  }

  // Static instance access if preferred, but usually members have their own
  static FastSine &getInstance() {
    static FastSine instance;
    return instance;
  }
};

// T-SVF (Zero-Delay Feedback State Variable Filter)
// Based on Andrew Simper's Trapezoidal integration method.
// Extremely stable even at high frequencies and high resonance.
class TSvf {
public:
  enum Type { LowPass, HighPass, BandPass, Notch, Peak };

  void setParams(float cutoff, float resonance, float sampleRate) {
    float f = tanf(M_PI * cutoff / sampleRate);
    float k = 1.0f / std::max(0.1f, resonance);
    mA1 = 1.0f / (1.0f + f * (f + k));
    mA2 = f * mA1;
    mA3 = f * mA2;
    mF = f;
    mK = k;
  }

  float process(float input, Type type) {
    float v3 = input - mSvfZ2;
    float v1 = mA1 * mSvfZ1 + mA2 * v3;
    float v2 = mSvfZ2 + mA2 * mSvfZ1 + mA3 * v3;

    mSvfZ1 = 2.0f * v1 - mSvfZ1;
    mSvfZ2 = 2.0f * v2 - mSvfZ2;

    // Denormal snapping
    if (std::abs(mSvfZ1) < 1e-9f)
      mSvfZ1 = 0.0f;
    if (std::abs(mSvfZ2) < 1e-9f)
      mSvfZ2 = 0.0f;

    switch (type) {
    case LowPass:
      return v2;
    case HighPass:
      return input - mK * v1 - v2;
    case BandPass:
      return v1;
    case Notch:
      return input - mK * v1;
    case Peak:
      return input - mK * v1 - 2.0f * v2;
    default:
      return v2;
    }
  }

private:
  float mSvfZ1 = 0.0f;
  float mSvfZ2 = 0.0f;
  float mA1 = 0.0f, mA2 = 0.0f, mA3 = 0.0f;
  float mF = 0.0f, mK = 0.0f;
};

#endif // UTILS_H
