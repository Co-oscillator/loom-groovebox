#ifndef SLICER_FX_H
#define SLICER_FX_H

#include <cmath>

class SlicerFx {
public:
  // Input v is 0.0 - 1.0 from GUI.
  // We want range approx 0.125 (1/8th speed) to 16.0 (16x speed) divisions per
  // step? Or if 'rate' represents slices per step: v=0 -> 0.1 slices/step (very
  // slow, 1 slice per 10 steps) v=1 -> 16 slices/step (granular)  // Cubic:
  void setRate2(float v) { mRate2 = 0.02f + (v * v * v) * 16.0f; }
  void setRate3(float v) { mRate3 = 0.02f + (v * v * v) * 16.0f; }
  void setRate5(float v) { mRate5 = 0.02f + (v * v * v) * 16.0f; }
  void setActive2(bool v) { mActive2 = v; }
  void setActive3(bool v) { mActive3 = v; }
  void setActive5(bool v) { mActive5 = v; }
  void setDepth(float v) { mDepth = v; }

  void setParameters(float rate2, float rate3, float rate5, bool active2,
                     bool active3, bool active5, float depth) {
    mRate2 = rate2;
    mRate3 = rate3;
    mRate5 = rate5;
    mActive2 = active2;
    mActive3 = active3;
    mActive5 = active5;
    mDepth = depth;
  }

  float process(float input, double sampleCount, double samplesPerStep) {
    if (samplesPerStep <= 0)
      return input;

    float activeGain = 1.0f;

    // Check 2s (Basic)
    if (mActive2 && mRate2 > 0) {
      double cycle = samplesPerStep / (double)mRate2;
      double pos = std::fmod(sampleCount, cycle) / cycle;
      if (pos > 0.5)
        activeGain *= (1.0f - mDepth);
    }

    // Check 3s
    if (mActive3 && mRate3 > 0) {
      double cycle = samplesPerStep / (double)mRate3;
      double pos = std::fmod(sampleCount, cycle) / cycle;
      if (pos > 0.5)
        activeGain *= (1.0f - mDepth);
    }

    // Check 5s
    if (mActive5 && mRate5 > 0) {
      double cycle = samplesPerStep / (double)mRate5;
      double pos = std::fmod(sampleCount, cycle) / cycle;
      if (pos > 0.5)
        activeGain *= (1.0f - mDepth);
    }

    // If no slicers are active, pass through
    if (!mActive2 && !mActive3 && !mActive5)
      return input;

    return input * activeGain;
  }

private:
  float mRate2 = 1.0f;
  float mRate3 = 1.0f;
  float mRate5 = 1.0f;
  bool mActive2 = true;
  bool mActive3 = false;
  bool mActive5 = false;
  float mDepth = 1.0f;
};

#endif // SLICER_FX_H
