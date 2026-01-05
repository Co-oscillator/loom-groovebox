#ifndef LFO_ENGINE_H
#define LFO_ENGINE_H

#include <cmath>
#include <cstdlib>

enum class LfoShape { Sine, Triangle, Square, Saw, Random };

class LfoEngine {
public:
  LfoEngine() {}

  void setParameters(float frequency, float depth, int shape, bool sync) {
    mFrequency = frequency;
    mDepth = depth;
    mShape = static_cast<LfoShape>(shape);
    mSync = sync;
  }

  void setFrequency(float f) { mFrequency = f; }
  void setDepth(float d) { mDepth = d; }
  void setShape(int s) { mShape = static_cast<LfoShape>(s); }
  void setSync(bool s) { mSync = s; }

  void setBpm(float bpm) { mBpm = bpm; }

  float process(float sampleRate, int numFrames = 1) {
    float effectiveFreq = mFrequency;

    float phaseInc = (effectiveFreq / sampleRate) * numFrames;
    mPhase += phaseInc;
    if (mPhase >= 1.0f) {
      mPhase = fmodf(mPhase, 1.0f);
      updateRandom();
    }

    float out = 0.0f;
    switch (mShape) {
    case LfoShape::Sine:
      out = std::sin(mPhase * 2.0f * 3.14159265f);
      break;
    case LfoShape::Triangle:
      out = (mPhase < 0.5f) ? (4.0f * mPhase - 1.0f) : (3.0f - 4.0f * mPhase);
      break;
    case LfoShape::Square:
      out = (mPhase < 0.5f) ? 1.0f : -1.0f;
      break;
    case LfoShape::Saw:
      out = 2.0f * mPhase - 1.0f;
      break;
    case LfoShape::Random:
      out = mRandomValue;
      break;
    }

    mLastOutput = out * mDepth;
    return mLastOutput;
  }

  float getCurrentValue() const { return mLastOutput; }

private:
  void updateRandom() {
    mRandomValue = ((float)rand() / (float)RAND_MAX) * 2.0f - 1.0f;
  }

  float mPhase = 0.0f;
  float mLastOutput = 0.0f;
  float mFrequency = 1.0f; // Hz
  float mDepth = 1.0f;
  LfoShape mShape = LfoShape::Sine;
  bool mSync = false;
  float mBpm = 120.0f;
  float mRandomValue = 0.0f;
};

#endif
