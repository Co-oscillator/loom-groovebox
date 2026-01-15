#ifndef ANALOG_DRUM_ENGINE_H
#define ANALOG_DRUM_ENGINE_H

#include "../Utils.h"
#include <algorithm>
#include <cmath>
#include <cstdint>

// Simple, fast, thread-safe pseudo-random generator
// (Standard rand() is too slow for audio render loops)
struct FastNoise {
  uint32_t seed = 22222;
  float next() {
    // Linear Congruential Generator
    seed = (seed * 196314165 + 907633515);
    // Convert to float -1.0 to 1.0
    return ((int32_t)seed) * 4.6566128737e-10f;
  }
};

class AnalogDrumEngine {
public:
  enum class DrumType {
    Kick = 0,
    Snare = 1,
    Clap = 2,
    HiHatClosed = 3,
    HiHatOpen = 4
  };

  AnalogDrumEngine() {
    setSampleRate(44100.0f);
    resetToDefaults();
  }

  void resetToDefaults() {
    setParams(0, 0.5f, 0.3f, 0.2f, 0.8f, 0.0f); // Kick
    setParams(1, 0.2f, 0.5f, 0.5f, 0.7f, 0.0f); // Snare
    setParams(2, 0.3f, 0.5f, 0.5f, 0.5f, 0.2f); // Clap
    setParams(3, 0.1f, 0.8f, 0.5f, 0.0f, 0.1f); // CH
    setParams(4, 0.4f, 0.8f, 0.5f, 0.0f, 0.1f); // OH
    setParams(5, 0.8f, 0.7f, 0.5f, 0.0f, 0.6f); // Cymbal
    setParams(6, 0.1f, 0.5f, 0.8f, 0.5f, 0.0f); // Perc
    setParams(7, 0.3f, 0.9f, 0.5f, 0.2f, 0.8f); // Noise
  }

  void setSampleRate(float sr) {
    for (auto &v : mVoices)
      v.sampleRate = sr;
  }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.env = 0.0f;
    }
  }

  // 0: Decay

  // 0: Decay
  // 1: Tone (Filter Color)
  // 2: Tune (Pitch)
  // 3: PARAM A (Kick: Punch | Snare: Snappy | Clap: Spread)
  // 4: PARAM B (Hats: Metal Detune)
  void setParameter(int drumIdx, int paramId, float value) {
    if (drumIdx < 0 || drumIdx >= 8)
      return;
    AnalogVoice &v = mVoices[drumIdx];
    LOGD("AnalogEngine::setParameter drum=%d, id=%d, val=%.2f", drumIdx,
         paramId, value);

    switch (paramId) {
    case 0:
      v.decay = 0.05f + (value * 1.5f);
      break;
    case 1:
      v.tone = value;
      break;
    case 2:
      // Tune scales differently per drum
      if (v.type == DrumType::Kick)
        v.baseFreq = 30.0f + (value * 60.0f);
      else if (v.type == DrumType::Snare)
        v.baseFreq = 120.0f + (value * 200.0f);
      else
        v.baseFreq = value; // 0-1 for others (used as offset)
      break;
    case 3:
      v.paramA = value;
      break; // Punch / Snappy / Spread
    case 4:
      v.paramB = value;
      break; // Metal / Distortion
    case 5:
      v.gain = value;
      break; // Volume/Gain
    }
  }

  void triggerNote(int note, int velocity) {
    int idx = -1;
    switch (note) {
    // GM Mapping
    case 36:
    case 35:
      idx = 0;
      break; // Kick
    case 38:
    case 40:
      idx = 1;
      break; // Snare
    case 39:
      idx = 2;
      break; // Clap
    case 42:
      idx = 3;
      break; // CH
    case 46:
      idx = 4;
      break; // OH
    // Index Mapping (0-7) and MIDI C4+ Mapping (60-67)
    default:
      if (note >= 0 && note < 8)
        idx = note;
      else if (note >= 60 && note < 68)
        idx = note - 60;
      break;
    }
    if (idx != -1)
      mVoices[idx].trigger(velocity / 127.0f);
  }

  // Added to satisfy AudioEngine interface
  void releaseNote(int note) {
    // Analog drums are typically one-shot, but we could implement mute groups
    // or early fade here if needed. For now, no-op is fine as envelopes handle
    // decay.
  }

  float render() {
    float out = 0.0f;
    for (int i = 0; i < 8; ++i) {
      if (mVoices[i].active) {
        mLastRenders[i] = mVoices[i].render();
        out += mLastRenders[i];
      } else {
        mLastRenders[i] = 0.0f;
      }
    }
    // Boost and soft limit to prevent harsh "growly" distortion
    // Cleaner soft limiting for analog drums
    return std::tanh(out * 0.9f);
  }

  bool isActive() const {
    for (const auto &v : mVoices) {
      if (v.active)
        return true;
    }
    return false;
  }

  float getVoiceOutput(int index) {
    if (index >= 0 && index < 8) {
      return mLastRenders[index];
    }
    return 0.0f;
  }

private:
  float mLastRenders[8] = {0.0f};
  struct AnalogVoice {
    DrumType type = DrumType::Kick;
    bool active = false;
    float sampleRate = 44100.0f;
    FastNoise rng;

    // Current State
    float phase = 0.0f;
    float currentFreq = 0.0f;
    float env = 0.0f;

    // Hat Oscillators (Schmitt Trigger Cluster)
    float hatPhases[6] = {0};

    // Filter State
    float filterState = 0.0f;

    // Clap State
    float clapTimer = 0.0f;
    int clapStage = 0;
    float clapEnv = 0.0f;

    // Settings
    float baseFreq = 50.0f;
    float decay = 0.5f;
    float tone = 0.5f;   // Brightness/Filter
    float paramA = 0.5f; // "Punch" or "Snappy"
    float paramB = 0.0f; // "Metal"
    float gain = 0.65f;

    float velocity = 0.0f;

    void trigger(float vel) {
      active = true;
      velocity = vel;
      env = 1.0f;
      phase = 0.0f;
      clapTimer = 0.0f;
      clapStage = 0;
      clapEnv = 0.0f;
      currentFreq = baseFreq;

      // Kick starts at high freq determined by "Punch" (Param A)
      if (type == DrumType::Kick) {
        // Punch: 0.0 -> Start at 2x freq, 1.0 -> Start at 8x freq
        float punchAmt = 2.0f + (paramA * 6.0f);
        currentFreq = baseFreq * punchAmt;
      }
    }

    float render() {
      if (!active)
        return 0.0f;

      float out = 0.0f;
      float dt = 1.0f / sampleRate;

      switch (type) {

      // --- KICK (Sine Sweep) ---
      case DrumType::Kick: {
        env -= dt / decay;
        if (env <= 0.0f) {
          active = false;
          return 0.0f;
        }

        // Pitch Envelope (The "Punch")
        currentFreq +=
            (baseFreq - currentFreq) * (0.002f + (1.0f - tone) * 0.005f);
        phase += currentFreq * dt;
        if (phase > 1.0f)
          phase -= 1.0f;

        float sine = FastSine::getInstance().sin(phase * 6.28318f);
        if (tone > 0.5f) {
          float x = sine; // Original 'x' from the snippet
          // Apply characterful boost and soft-clipping similar to FM drum
          float boost = 1.4f; // 40% boost
          x = x * boost;      // Apply boost to 'x'
          // Soft clipping / saturation
          if (x > 1.0f)
            x = 1.0f - expf(1.0f - x);
          else if (x < -1.0f)
            x = -1.0f + expf(1.0f + x);

          float x2 = x * x;
          sine = x * (27.0f + x2) / (27.0f + 9.0f * x2);
        }
        out = sine * env;
        break;
      }

      // --- SNARE (Shell + Wires) ---
      case DrumType::Snare: {
        float envTone = std::max(0.0f, env - dt / (decay * 0.4f));
        env -= dt / decay;
        if (env <= 0.0f) {
          active = false;
          return 0.0f;
        }

        phase += baseFreq * dt;
        float shell = std::sin(phase * 6.28318f) * envTone;
        float noise = rng.next();
        float hpCoeff = 0.1f + (tone * 0.6f);
        filterState += (noise - filterState) * hpCoeff;
        float wires = (noise - filterState) * env;
        out = (shell * (1.0f - paramA * 0.5f)) + (wires * (0.2f + paramA));
        break;
      }

      // We can apply a generic check at the end if we want, but doing it per
      // case is explicit. Let's rely on `env` decrementing logic being similar
      // across voices. Actually, Clap and Hats also decrement env.

      // --- CLAP (Burst Noise) ---
      case DrumType::Clap: {
        // Burst Logic: Retrigger envelope 3 times
        // Spread (ParamA) controls time between claps (5ms to 20ms)
        // The outer env controls the overall decay, clapEnv controls individual
        // claps
        clapTimer -= dt;     // clapTimer now counts down
        if (clapStage < 4) { // 0-3 for 4 claps
          if (clapTimer <= 0) {
            clapEnv = 1.0f; // Retrigger individual clap envelope
            float spreadTime =
                0.005f + (paramA * 0.025f); // Adjusted spread range
            clapTimer = spreadTime + rng.next() * 0.005f; // Add some jitter
            clapStage++;
          }
        }
        clapEnv -= dt / (0.01f + decay * 0.1f);
        if (clapEnv < 0.0f)
          clapEnv = 0.0f;

        env -= dt / decay; // Overall decay
        if (env <= 0.0f && clapStage >= 4) {
          active = false;
          return 0.0f;
        }

        float noise = rng.next();
        // High pass noise for clap
        filterState += (noise - filterState) * (0.4f + tone * 0.4f);
        float clapNoise = (noise - filterState) * clapEnv;

        return clapNoise * velocity * 0.8f;
      }

      // --- HI-HATS (Schmitt Trigger Cluster) ---
      case DrumType::HiHatClosed:
      case DrumType::HiHatOpen: {
        env -= dt / decay;
        if (env <= 0.0f) {
          active = false;
          return 0.0f;
        }

        // Sum 6 square waves with random frequencies
        float metal = 0.0f;
        // 808-style cluster Ratios
        // We use ParamB ("Metal") to detune them
        float spread = 1.0f + (paramB * 0.2f);
        float freqs[6] = {
            263.0f,          400.0f * spread, 421.0f, 474.0f * spread,
            520.0f * spread, 600.0f}; // Added two more frequencies

        float cluster = 0.0f;
        for (int i = 0; i < 6; ++i) {
          hatPhases[i] += freqs[i] * dt;
          if (hatPhases[i] > 1.0f)
            hatPhases[i] -= 1.0f;
          // Square wave
          cluster += (hatPhases[i] > 0.5f) ? 1.0f : -1.0f;
        }

        // High Pass Filter (Crucial for metallic sound)
        // Tone controls Filter Cutoff
        float hpFreq = 0.3f + (tone * 0.5f);
        filterState += (cluster - filterState) * hpFreq;
        float filtered = cluster - filterState;

        out = filtered * env * 0.25f; // Scale down
        break;
      }
      } // Switch

      if (env <= 0.0f)
        active = false;
      return out * velocity * gain;
    }
  };

  // Expanded to 8 voices to match FM Drum and prevent buffer overflow
  AnalogVoice mVoices[8];

  void setParams(int idx, float dec, float tone, float tune, float pA,
                 float pB) {
    if (idx < 0 || idx >= 8)
      return;

    // Map extended indices to existing DSP types
    if (idx == 5)
      mVoices[idx].type = DrumType::HiHatOpen; // Cymbal
    else if (idx == 6)
      mVoices[idx].type = DrumType::Kick; // Perc
    else if (idx == 7)
      mVoices[idx].type = DrumType::Snare; // Noise
    else
      mVoices[idx].type = (DrumType)idx;

    setParameter(idx, 0, dec);
    setParameter(idx, 1, tone);
    setParameter(idx, 2, tune);
    setParameter(idx, 3, pA);
    setParameter(idx, 4, pB);
  }
};

#endif // ANALOG_DRUM_ENGINE_H
