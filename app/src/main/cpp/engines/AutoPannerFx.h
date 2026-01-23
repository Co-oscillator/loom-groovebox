#ifndef AUTO_PANNER_FX_H
#define AUTO_PANNER_FX_H

#include <algorithm>
#include <cmath>

class AutoPannerFx {
public:
  AutoPannerFx() {}

  void setPhase(float phase) { mPhase = phase; }

  void process(float inL, float inR, float &outL, float &outR,
               float sampleRate) {
    // LFO
    mPhase += mRate / sampleRate;
    if (mPhase >= 1.0f)
      mPhase -= 1.0f;

    if (mMix <= 0.001f) {
      outL = inL;
      outR = inR;
      return;
    }

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

    // Calculate Pan Position [-1, 1]
    float currentPan = mPan + (lfo * mDepth);
    currentPan = std::max(-1.0f, std::min(1.0f, currentPan));

    // True Stereo Panning (Criss-Cross)
    // Left channel pans L->R, Right channel pans R->L
    float angleL = (currentPan + 1.0f) * (float)M_PI * 0.25f; // 0 to PI/2
    float gainLL = std::cos(angleL);
    float gainLR = std::sin(angleL);

    float angleR = (1.0f - currentPan) * (float)M_PI * 0.25f; // Inverse
    float gainRL = std::cos(angleR);
    float gainRR = std::sin(angleR);

    float wetL = (inL * gainLL + inR * gainRL);
    float wetR = (inL * gainLR + inR * gainRR);

    outL = (inL * (1.0f - mMix)) + (wetL * mMix);
    outR = (inR * (1.0f - mMix)) + (wetR * mMix);
  }

  void setPan(float pan) { mPan = (pan * 2.0f) - 1.0f; }
  // Allow much slower and faster rates
  // User requested 0.05Hz to 20Hz with fine control at low end.
  // Using quadratic curve: 0.05 + v*v*20
  void setRate(float rate) { mRate = 0.05f + (rate * rate) * 20.0f; }
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
