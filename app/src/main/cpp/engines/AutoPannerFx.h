#ifndef AUTO_PANNER_FX_H
#define AUTO_PANNER_FX_H

#include <algorithm>
#include <cmath>

class AutoPannerFx {
public:
  AutoPannerFx() {}

  void process(float input, float &outL, float &outR, float sampleRate) {
    if (mMix <= 0.001f) {
      outL = input;
      outR = input;
      return;
    }

    // LFO
    mPhase += mRate / sampleRate;
    if (mPhase >= 1.0f)
      mPhase -= 1.0f;

    float lfo = 0.0f;
    // Shape: 0=Sine, 1=Triangle, 2=Square
    if (mShape < 0.5f) {
      lfo = std::sin(2.0f * (float)M_PI * mPhase);
    } else if (mShape < 1.5f) {
      // Precise Triangle
      lfo = (mPhase < 0.5f) ? (4.0f * mPhase - 1.0f) : (3.0f - 4.0f * mPhase);
    } else {
      lfo = (mPhase < 0.5f) ? 1.0f : -1.0f;
    }

    // Calculate Pan [-1, 1]
    // Increased modulation depth effect by using a wider range if needed?
    // Actually mDepth 0..1 is fine.
    float currentPan = mPan + (lfo * mDepth);
    currentPan = std::max(-1.0f, std::min(1.0f, currentPan));

    // Pan Law (Constant Power)
    // To fix the "clean boost" perception, we can slightly attenuate the center
    // or ensure it's not boosting. Constant power (cos/sin) is usually safe,
    // but let's make sure it's not too loud.
    float angle = (currentPan + 1.0f) * (float)M_PI * 0.25f; // 0 to PI/2
    float gainL = std::cos(angle);
    float gainR = std::sin(angle);

    float pannedL = input * gainL;
    float pannedR = input * gainR;

    // Mix
    // If mMix is 1.0, outL = pannedL.
    // If input is 1.0, and pan is 0, gainL=0.707. outL=0.707.
    // Hearing 0.707 in both ears vs 1.0 in mono can feel quieter or like a
    // boost depending on monitoring. Most Auto-pans use a simple linear pan if
    // they want to avoid "gain boost" feel in mono. But constant power is
    // better for stereo.

    outL = (input * (1.0f - mMix)) + (pannedL * mMix);
    outR = (input * (1.0f - mMix)) + (pannedR * mMix);
  }

  void setPan(float pan) { mPan = (pan * 2.0f) - 1.0f; }
  // Allow much slower and faster rates
  void setRate(float rate) { mRate = 0.01f + (rate * rate) * 20.0f; }
  void setDepth(float depth) { mDepth = depth; }
  void setMix(float mix) { mMix = mix; }
  void setShape(float shape) { mShape = std::round(shape * 2.0f); }

  void setParameters(float pan, float rate, float depth, float shape,
                     float mix) {
    setPan(pan);
    setRate(rate);
    setDepth(depth);
    setShape(shape);
    setMix(mix);
  }

private:
  float mPhase = 0.0f;
  float mPan = 0.0f;   // -1 to 1
  float mRate = 1.0f;  // Hz
  float mDepth = 0.5f; // 0 to 1
  float mShape = 0.0f; // 0, 1, 2
  float mMix = 0.0f;
};

#endif
