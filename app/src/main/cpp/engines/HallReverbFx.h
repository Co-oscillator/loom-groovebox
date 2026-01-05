#ifndef HALL_REVERB_FX_H
#define HALL_REVERB_FX_H

#include <cmath>
#include <vector>

// Helper Classes
class CombFilter {
public:
  void setBufferSize(int size) {
    mBuffer.assign(size, 0.0f);
    mWritePos = 0;
  }

  float process(float input, float feedback, float damp) {
    float readVal = mBuffer[mWritePos];
    mFilterStore = (readVal * (1.0f - damp)) + (mFilterStore * damp);

    // Anti-Denormal
    if (std::abs(mFilterStore) < 1.0e-15f)
      mFilterStore = 0.0f;

    float output = readVal;
    float newVal = input + (mFilterStore * feedback);

    // Anti-Denormal
    if (std::abs(newVal) < 1.0e-15f)
      newVal = 0.0f;

    mBuffer[mWritePos] = newVal;
    mWritePos++;
    if (mWritePos >= mBuffer.size())
      mWritePos = 0;

    return output;
  }

  // Helper Helper
  void clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mWritePos = 0;
    mFilterStore = 0.0f;
  }

private:
  std::vector<float> mBuffer;
  int mWritePos = 0;
  float mFilterStore = 0.0f;
};

class AllPassFilter {
public:
  void setBufferSize(int size) {
    mBuffer.assign(size, 0.0f);
    mWritePos = 0;
  }

  void clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mWritePos = 0;
  }

  float process(float input) {
    float bufOut = mBuffer[mWritePos];

    // Anti-Denormal check for buffer content
    if (std::abs(bufOut) < 1.0e-15f)
      bufOut = 0.0f;

    float output = -input + bufOut;
    float newVal = input + (bufOut * 0.5f); // 0.5 feedback

    // Anti-Denormal
    if (std::abs(newVal) < 1.0e-15f)
      newVal = 0.0f;

    mBuffer[mWritePos] = newVal;
    mWritePos++;
    if (mWritePos >= mBuffer.size())
      mWritePos = 0;

    return output;
  }

private:
  std::vector<float> mBuffer;
  int mWritePos = 0;
};

class HallReverbFx {
public:
  HallReverbFx() {
    // Standard Schroeder tunings (approx for 44.1k)
    // Scaled by sample rate in setParameters usually, but fixed for now
    mCombs[0].setBufferSize(1116);
    mCombs[1].setBufferSize(1188);
    mCombs[2].setBufferSize(1277);
    mCombs[3].setBufferSize(1356);

    mAllPass[0].setBufferSize(225);
    mAllPass[1].setBufferSize(556);
  }

  void clear() {
    for (int i = 0; i < 4; ++i)
      mCombs[i].clear();
    for (int i = 0; i < 2; ++i)
      mAllPass[i].clear();
  }

  // Initialize with SR support if needed, but fixed delays are often fine for
  // reverb character
  void setSampleRate(float sr) {
    float scale = sr / 44100.0f;
    mCombs[0].setBufferSize((int)(1116 * scale));
    mCombs[1].setBufferSize((int)(1188 * scale));
    mCombs[2].setBufferSize((int)(1277 * scale));
    mCombs[3].setBufferSize((int)(1356 * scale));
    mAllPass[0].setBufferSize((int)(225 * scale));
    mAllPass[1].setBufferSize((int)(556 * scale));
  }

  void setParameters(float size, float damp, float mix) {
    mSize = 0.7f + (size * 0.28f); // 0.7 to 0.98 feedback
    mDamp = damp * 0.4f;
    mMix = mix;
  }

  float process(float input) {
    if (mMix <= 0.001f)
      return 0.0f; // Early exit optimisation

    float out = 0.0f;
    // Parallel Combs
    for (int i = 0; i < 4; ++i) {
      out += mCombs[i].process(input, mSize, mDamp);
    }

    // Series AllPass
    out = mAllPass[0].process(out);
    out = mAllPass[1].process(out);

    return out * mMix * 0.3f; // Scale down
  }

private:
  float mSize = 0.8f;
  float mDamp = 0.2f;
  float mMix = 0.3f;

  CombFilter mCombs[4];
  AllPassFilter mAllPass[2];
};

#endif // HALL_REVERB_FX_H
