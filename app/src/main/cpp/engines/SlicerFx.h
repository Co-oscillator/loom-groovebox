#ifndef SLICER_FX_H
#define SLICER_FX_H

#include <cmath>

class SlicerFx {
public:
  // Input v is typically the rate multiplier (e.g. 1.0, 2.0, 4.0) passed from
  // AudioEngine
  void setRate1(float v) { mRate1 = v; }
  void setRate2(float v) { mRate2 = v; }
  void setRate3(float v) { mRate3 = v; }

  void setActive1(bool v) { mActive1 = v; }
  void setActive2(bool v) { mActive2 = v; }
  void setActive3(bool v) { mActive3 = v; }
  void setDepth(float v) { mDepth = v; }

  void setParameters(float rate1, float rate2, float rate3, bool active1,
                     bool active2, bool active3, float depth) {
    mRate1 = rate1;
    mRate2 = rate2;
    mRate3 = rate3;
    mActive1 = active1;
    mActive2 = active2;
    mActive3 = active3;
    mDepth = depth;
  }

  float process(float input, double sampleCount, double samplesPerStep) {
    if (samplesPerStep <= 0)
      return input;

    float activeGain = 1.0f;

    // Slicer 1
    if (mActive1 && mRate1 > 0) {
      double cycle = samplesPerStep / (double)mRate1;
      double pos = std::fmod(sampleCount, cycle) / cycle;
      if (pos > 0.5)
        activeGain *= (1.0f - mDepth);
    }

    // Slicer 2
    if (mActive2 && mRate2 > 0) {
      double cycle = samplesPerStep / (double)mRate2;
      double pos = std::fmod(sampleCount, cycle) / cycle;
      if (pos > 0.5)
        activeGain *= (1.0f - mDepth);
    }

    // Slicer 3
    if (mActive3 && mRate3 > 0) {
      double cycle = samplesPerStep / (double)mRate3;
      double pos = std::fmod(sampleCount, cycle) / cycle;
      if (pos > 0.5)
        activeGain *= (1.0f - mDepth);
    }

    // Standard Amplitude Modulation: return input * gain
    return input * activeGain;
  }

private:
  float mRate1 = 1.0f;
  float mRate2 = 1.0f;
  float mRate3 = 1.0f;
  bool mActive1 = true;
  bool mActive2 = false;
  bool mActive3 = false;
  float mDepth = 1.0f;
};

#endif // SLICER_FX_H
