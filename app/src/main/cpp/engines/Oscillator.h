#ifndef OSCILLATOR_H
#define OSCILLATOR_H

#include <android/log.h>
#include <cmath>

enum class Waveform { Sine, Triangle, Square, Sawtooth };

class Oscillator {
public:
  void setFrequency(float frequency, float sampleRate) {
    mPhaseIncrement = frequency / sampleRate;
  }

  void setWaveform(Waveform waveform) { mWaveform = waveform; }

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

  float nextSample(float modulation = 0.0f, float fmFreqMult = 1.0f,
                   float waveFold = 0.0f) {
    float sample = 0.0f;
    float phaseWithMod = mPhase + modulation;
    // Keep phase in [0, 1]
    phaseWithMod -= floorf(phaseWithMod);

    switch (mWaveform) {
    case Waveform::Sine:
      sample = sinf(phaseWithMod * 2.0f * M_PI);
      break;
    case Waveform::Triangle: {
      float tri =
          2.0f * fabsf(2.0f * (phaseWithMod - floorf(phaseWithMod + 0.5f))) -
          1.0f;
      // Pulse width on triangle (leaning)
      if (mShape != 0.5f) {
        if (phaseWithMod < mShape) {
          tri = (phaseWithMod / mShape) * 2.0f - 1.0f;
        } else {
          tri = 1.0f - ((phaseWithMod - mShape) / (1.0f - mShape)) * 2.0f;
        }
      }
      sample = tri;
      break;
    }
    case Waveform::Square:
      sample = (phaseWithMod < mShape) ? 1.0f : -1.0f;
      break;
    case Waveform::Sawtooth:
      sample = 2.0f * (phaseWithMod - floorf(phaseWithMod + 0.5f));
      break;
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
};

#endif // OSCILLATOR_H
