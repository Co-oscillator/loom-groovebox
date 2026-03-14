#ifndef OSCILLATOR_H
#define OSCILLATOR_H

#include "../Log.h"
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

    // Recursive folding
    while (std::abs(sample) > threshold) {
      if (sample > threshold) {
        sample = threshold - (sample - threshold);
      } else if (sample < -threshold) {
        sample = -threshold - (sample + threshold);
      }
    }
    return sample / threshold;
  }

  float getShapeSample(Waveform wf, float phaseWithMod) const {
    switch (wf) {
    case Waveform::Sine:
      return sinf(phaseWithMod * 2.0f * M_PI);
    case Waveform::Triangle: {
      float tri =
          2.0f * fabsf(2.0f * (phaseWithMod - floorf(phaseWithMod + 0.5f))) -
          1.0f;
      if (mShape != 0.5f) {
        if (phaseWithMod < mShape) {
          tri = (phaseWithMod / mShape) * 2.0f - 1.0f;
        } else {
          tri = 1.0f - ((phaseWithMod - mShape) / (1.0f - mShape)) * 2.0f;
        }
      }
      return tri;
    }
    case Waveform::Square:
      return (phaseWithMod < mShape) ? 1.0f : -1.0f;
    case Waveform::Sawtooth:
      return 2.0f * (phaseWithMod - floorf(phaseWithMod + 0.5f));
    }
    return 0.0f;
  }

  float nextSample(float modulation = 0.0f, float fmFreqMult = 1.0f,
                   float waveFold = 0.0f) {
    float phaseWithMod = mPhase + modulation;
    phaseWithMod -= floorf(phaseWithMod);

    float sample = 0.0f;

    if (!mMorphActive) {
      sample = getShapeSample(mWaveform, phaseWithMod);
    } else {
      float v = mMorphValue;
      float detent = 0.05f;
      if (v < detent)
        v = 0.0f;
      else if (v > 0.333333f - detent && v < 0.333333f + detent)
        v = 0.333333f;
      else if (v > 0.666666f - detent && v < 0.666666f + detent)
        v = 0.666666f;
      else if (v > 1.0f - detent)
        v = 1.0f;

      if (v <= 0.333333f) {
        float mix = v * 3.0f;
        sample = getShapeSample(Waveform::Sine, phaseWithMod) * (1.0f - mix) +
                 getShapeSample(Waveform::Triangle, phaseWithMod) * mix;
      } else if (v <= 0.666666f) {
        float mix = (v - 0.333333f) * 3.0f;
        sample =
            getShapeSample(Waveform::Triangle, phaseWithMod) * (1.0f - mix) +
            getShapeSample(Waveform::Sawtooth, phaseWithMod) * mix;
      } else {
        float mix = (v - 0.666666f) * 3.0f;
        sample =
            getShapeSample(Waveform::Sawtooth, phaseWithMod) * (1.0f - mix) +
            getShapeSample(Waveform::Square, phaseWithMod) * mix;
      }
    }

    if (waveFold > 0.01f) {
      sample = foldWave(sample, waveFold);
    }

    mPhase += mPhaseIncrement * fmFreqMult;
    if (mPhase >= 1.0f)
      mPhase -= 1.0f;

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
