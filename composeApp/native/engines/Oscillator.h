#ifndef OSCILLATOR_H
#define OSCILLATOR_H

#include "../Log.h"
#include "../Utils.h"
#include <cmath>

enum class Waveform { Sine, Triangle, Square, Sawtooth };

class Oscillator {
public:
  void setFrequency(float frequency, float sampleRate) {
    mPhaseIncrement = frequency / sampleRate;
  }

  void setWaveform(Waveform waveform) {
    mWaveform = waveform;
    mMorphActive = false;
  }

  void setMorphValue(float value) {
    mMorphValue = value;
    mMorphActive = true;
  }

  void setWaveShape(float shape) {
    mShape = shape; // 0.0 to 1.0, affects pulse width or morphing
  }

  bool hasWrapped() const { return (mPhase + mPhaseIncrement) >= 1.0f; }

  void resetPhase() { mPhase = 0.0f; }
  float foldWave(float sample, float amount) {
    if (amount <= 0.0f)
      return sample;
    float threshold = 1.0f - (amount * 0.9f);
    if (threshold < 0.1f)
      threshold = 0.1f;

    if (std::abs(sample) > threshold) {
      float m = fmodf(sample + threshold, threshold * 4.0f);
      if (m < 0) m += threshold * 4.0f;
      if (m < threshold * 2.0f) sample = m - threshold;
      else sample = 3.0f * threshold - m;
    }
    return sample / threshold;
  }

  inline float poly_blep(float t, float dt) const {
    if (t < dt) {
      float t1 = t / dt;
      return t1 + t1 - t1 * t1 - 1.0f;
    } else if (t > 1.0f - dt) {
      float t1 = (t - 1.0f) / dt;
      return t1 * t1 + t1 + t1 + 1.0f;
    }
    return 0.0f;
  }

  float getShapeSample(Waveform wf, float phase, float dt) const {
    switch (wf) {
    case Waveform::Sine:
      return FastSine::get(phase);
    case Waveform::Triangle: {
      float tri;
      if (phase < 0.5f) tri = 4.0f * phase - 1.0f;
      else tri = 3.0f - 4.0f * phase;
      
      if (mShape != 0.5f) {
        if (phase < mShape) {
          tri = (phase / mShape) * 2.0f - 1.0f;
        } else {
          tri = 1.0f - ((phase - mShape) / (1.0f - mShape)) * 2.0f;
        }
      }
      return tri;
    }
    case Waveform::Square: {
      float naive = (phase < mShape) ? 1.0f : -1.0f;
      // Add BLEP for edge 0
      if (phase < dt || phase > 1.0f - dt) {
        naive += poly_blep(phase, dt);
      }
      // Add BLEP for edge mShape (Pulse Width)
      float phaseShifted = phase + (1.0f - mShape);
      if (phaseShifted >= 1.0f) phaseShifted -= 1.0f;
      
      if (phaseShifted < dt || phaseShifted > 1.0f - dt) {
        naive -= poly_blep(phaseShifted, dt);
      }
      return naive;
    }
    case Waveform::Sawtooth: {
      float naive = 2.0f * phase - 1.0f;
      if (phase < dt || phase > 1.0f - dt) {
        naive -= poly_blep(phase, dt);
      }
      return naive;
    }
    }
    return 0.0f;
  }

  float nextSample(float modulation = 0.0f, float fmFreqMult = 1.0f,
                   float waveFold = 0.0f) {
    float phase = mPhase + modulation;
    if (phase >= 1.0f) phase -= (int)phase;
    else if (phase < 0.0f) phase += 1.0f - (int)phase;

    float dt = mPhaseIncrement;
    float sample = 0.0f;

    // Use a local copy to avoid repeated class member access in main path
    if (mMorphActive) {
      float v = mMorphValue;
      float detent = 0.05f;
      if (v < detent) v = 0.0f;
      else if (v > 0.333333f - detent && v < 0.333333f + detent) v = 0.333333f;
      else if (v > 0.666666f - detent && v < 0.666666f + detent) v = 0.666666f;
      else if (v > 1.0f - detent) v = 1.0f;

      if (v <= 0.333333f) {
        float mix = v * 3.0f;
        sample = getShapeSample(Waveform::Sine, phase, dt) * (1.0f - mix) +
                 getShapeSample(Waveform::Triangle, phase, dt) * mix;
      } else if (v <= 0.666666f) {
        float mix = (v - 0.333333f) * 3.0f;
        sample = getShapeSample(Waveform::Triangle, phase, dt) * (1.0f - mix) +
                 getShapeSample(Waveform::Sawtooth, phase, dt) * mix;
      } else {
        float mix = (v - 0.666666f) * 3.0f;
        sample = getShapeSample(Waveform::Sawtooth, phase, dt) * (1.0f - mix) +
                 getShapeSample(Waveform::Square, phase, dt) * mix;
      }
    } else {
      sample = getShapeSample(mWaveform, phase, dt);
    }

    if (waveFold > 0.01f) {
      sample = foldWave(sample, waveFold);
    }

    mPhase += dt * fmFreqMult;
    if (mPhase >= 1.0f) mPhase -= 1.0f;

    return sample;
  }

private:
  float mPhase = 0.0f;
  float mPhaseIncrement = 0.0f;
  float mShape = 0.5f; // Default square pulse width
  Waveform mWaveform = Waveform::Sine;
  float mMorphValue = 0.0f;
  bool mMorphActive = false;
};

#endif // OSCILLATOR_H
