#ifndef UTILS_H
#define UTILS_H

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <vector>

#define LOG_TAG "Groovebox"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

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

// State Variable Filter (SVF)
class Svf {
public:
  enum Type { LowPass, HighPass, BandPass, Notch };

  void setParams(float cutoff, float resonance, float sampleRate) {
    float f = 2.0f * sinf(M_PI * cutoff / sampleRate);
    mF = std::max(0.0f, std::min(1.0f, f));
    mQ = 1.0f / std::max(0.1f, resonance);
  }

  float process(float input, Type type) {
    float notepad = input - mR1 * mQ - mLow;
    float h = notepad;
    float b = mF * h + mR1;
    float l = mF * b + mLow;

    mR1 = b;
    mLow = l;

    // Anti-denormal
    if (std::abs(mR1) < 1.0e-15f)
      mR1 = 0.0f;
    if (std::abs(mLow) < 1.0e-15f)
      mLow = 0.0f;

    switch (type) {
    case LowPass:
      return l;
    case HighPass:
      return h;
    case BandPass:
      return b;
    case Notch:
      return h + l;
    default:
      return l;
    }
  }

  // Bipolar Filter: -1.0 (LP) to 0.0 (None) to 1.0 (HP)
  float processBipolar(float input, float morph) {
    float notepad = input - mR1 * mQ - mLow;
    float h = notepad;
    float b = mF * h + mR1;
    float l = mF * b + mLow;

    mR1 = b;
    mLow = l;

    // Anti-denormal
    if (std::abs(mR1) < 1.0e-15f)
      mR1 = 0.0f;
    if (std::abs(mLow) < 1.0e-15f)
      mLow = 0.0f;

    if (morph < 0.0f) {
      // Morph from bypassed (0.0) to LP (-1.0)
      float t = -morph;
      return input * (1.0f - t) + l * t;
    } else {
      // Morph from bypassed (0.0) to HP (1.0)
      float t = morph;
      return input * (1.0f - t) + h * t;
    }
  }

private:
  float mLow = 0.0f;
  float mR1 = 0.0f;
  float mF = 0.1f;
  float mQ = 1.0f;
};

#endif // UTILS_H
