#ifndef GRANULAR_ENGINE_H
#define GRANULAR_ENGINE_H

#include "Adsr.h"
#include <algorithm>
#include <cmath>
#include <memory> // Added for std::shared_ptr
#include <mutex>
#include <random>
#include <vector>

class GranularEngine {
public:
  // ADSR Parameters
  float mAttack = 0.01f;
  float mDecay = 0.1f;
  float mSustain = 1.0f;
  float mRelease = 0.1f;

  void setAttack(float v) { mAttack = v; }
  void setDecay(float v) { mDecay = v; }
  void setSustain(float v) { mSustain = v; }
  void setRelease(float v) { mRelease = v; }

  struct Grain {
    float position;
    float speed;
    float lOffset; // Stereo left
    float rOffset; // Stereo right
    float envValue;
    float attackStep;
    float decayStep;
    int life;
    int initialLife;
    bool isReverse;
    bool isActive;
    int voiceIdx; // Link to voice for ADSR and mixing

    // 4-point, 3rd-order Hermite Interpolation
    inline float cubicInterp(float y0, float y1, float y2, float y3, float mu) {
      float mu2 = mu * mu;
      float a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3;
      float a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
      float a2 = -0.5f * y0 + 0.5f * y2;
      float a3 = y1;
      return (a0 * mu * mu2) + (a1 * mu2) + (a2 * mu) + a3;
    }

    float nextSample(const std::vector<float> &source) {
      if (!isActive || source.empty())
        return 0.0f;

      // -- GRAIN ENVELOPE (Windowing) --
      if (life > initialLife * 0.9f) { // Attack 10%
        envValue += attackStep;
      } else { // Decay 90%
        envValue -= decayStep;
      }
      envValue = std::max(0.0f, std::min(1.0f, envValue));

      // Parameter Setters

      int size = static_cast<int>(source.size());
      int idx = static_cast<int>(position);
      float frac = position - static_cast<float>(idx);

      int i0 = (idx - 1 + size) % size;
      int i1 = idx;
      int i2 = (idx + 1) % size;
      int i3 = (idx + 2) % size;

      float s =
          cubicInterp(source[i0], source[i1], source[i2], source[i3], frac);

      if (isReverse) {
        position -= speed;
        if (position < 0)
          position += static_cast<float>(size);
      } else {
        position += speed;
        if (position >= static_cast<float>(size))
          position -= static_cast<float>(size);
      }

      life--;
      if (life <= 0)
        isActive = false;

      return s * envValue;
    }
  };

  class LFO {
  public:
    float phase = 0.0f;
    float rate = 0.1f;
    float depth = 0.0f;
    float shape = 0.0f;
    int target = 0;

    float nextValue() {
      phase += rate * 0.01f;
      if (phase >= 1.0f)
        phase -= 1.0f;

      float val = 0.0f;
      if (shape < 1.0f) {
        val = sinf(phase * 2.0f * M_PI);
      } else if (shape < 2.0f) {
        val = phase < 0.5f ? phase * 4.0f - 1.0f : 3.0f - phase * 4.0f;
      } else if (shape < 3.0f) {
        val = phase * 2.0f - 1.0f;
      } else {
        val = phase < 0.5f ? 1.0f : -1.0f;
      }
      return val * depth;
    }
  };

  struct Voice {
    bool active = false;
    int note = -1;
    float amplitude = 1.0f;
    float basePitch = 1.0f;
    Adsr envelope;
    float spawnCounter = 0.0f;
  };

  GranularEngine() {
    mLFOS.resize(3);
    mGrains.resize(100);
    mVoices.resize(16);
    for (auto &g : mGrains)
      g.isActive = false;
    for (auto &v : mVoices)
      v.active = false;

    mDensity = 0.5;
    mGrainSize = 0.2;
    resetToDefaults();
  }

  void resetToDefaults() {
    mPosition = 0.5f;
    mSpeed = 1.0f;
    mGrainSize = 0.2f;
    mDensity = 0.5f;
    mPitch = 1.0f;
    mSpray = 0.0f;
    mDetune = 0.0f;
    mRandomTiming = 0.0f;
    mMaxGrains = 40;
    mWidth = 0.5f;
    mReverseProb = 0.0f;
    mMainAttack = 0.01f;
    mMainDecay = 0.1f;
    mMainSustain = 1.0f;
    mMainRelease = 0.2f;
    mGain = 1.0f;
    for (int i = 0; i < 3; ++i) {
      mLFOS[i].phase = 0.0f;
      mLFOS[i].rate = 0.1f;
      mLFOS[i].depth = 0.0f;
      mLFOS[i].shape = 0.0f;
      mLFOS[i].target = 0;
    }
    for (auto &v : mVoices) {
      if (v.active)
        v.envelope.setParameters(mMainAttack, mMainDecay, mMainSustain,
                                 mMainRelease);
    }
  }

  void setSource(const std::vector<float> &source) {
    std::lock_guard<std::mutex> lock(*mBufferLock);
    mSource = source;
  }
  const std::vector<float> &getSampleData() const { return mSource; }
  void clearSource() {
    std::lock_guard<std::mutex> lock(*mBufferLock);
    mSource.clear();
  }
  void pushSample(float sample) {
    std::lock_guard<std::mutex> lock(*mBufferLock);
    mSource.push_back(sample);
  }

  void normalize() {
    std::lock_guard<std::mutex> lock(*mBufferLock);
    if (mSource.empty())
      return;
    float maxVal = 0.0f;
    for (float s : mSource)
      maxVal = std::max(maxVal, std::abs(s));
    if (maxVal > 0.0001f) {
      for (float &s : mSource)
        s /= maxVal;
    }
  }

  void trim(float start, float end) {
    std::lock_guard<std::mutex> lock(*mBufferLock);
    if (mSource.empty())
      return;
    int s = std::max(
        0, std::min((int)(start * mSource.size()), (int)mSource.size()));
    int e =
        std::max(0, std::min((int)(end * mSource.size()), (int)mSource.size()));
    if (e > s) {
      std::vector<float> trimmed(mSource.begin() + s, mSource.begin() + e);
      mSource = trimmed;
    }
  }

  void triggerNote(int note, int velocity) {
    // Alloc Voice
    int idx = -1;
    for (int i = 0; i < 16; ++i) {
      if (!mVoices[i].active) {
        idx = i;
        break;
      }
    }
    if (idx == -1)
      idx = 0; // Steal

    Voice &v = mVoices[idx];
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    v.basePitch = powf(2.0f, (note - 60) / 12.0f);
    v.spawnCounter = 0.0f;

    // Make sure ADSR parameters are applied to THIS voice's ADSR
    v.envelope.setSampleRate(44100.0f);
    v.envelope.setParameters(mMainAttack, mMainDecay, mMainSustain,
                             mMainRelease);
    v.envelope.trigger();
  }

  void releaseNote(int note) {
    for (auto &v : mVoices) {
      if (v.active && v.note == note) {
        v.envelope.release();
      }
    }
  }

  void allNotesOff() {
    for (auto &g : mGrains) {
      g.isActive = false;
      g.life = 0;
    }
    for (auto &v : mVoices) {
      v.active = false;
      v.envelope.reset();
      v.spawnCounter = 0.0f;
    }
  }

  void setParameter(int id, float value) {
    if (id == 400)
      mPosition = value;
    else if (id == 401)
      mSpeed = value;
    else if (id >= 402 && id <= 405) {
      if (id == 402)
        mLFOS[0].shape = value;
      else if (id == 403)
        mLFOS[0].rate = value;
      else if (id == 404)
        mLFOS[0].depth = value;
      else if (id == 405)
        mLFOS[0].target = static_cast<int>(value * 3.0f);
    } else if (id == 406)
      mGrainSize = value;
    else if (id == 407)
      mDensity = value;
    else if (id == 408)
      mAttack = value;
    else if (id == 409)
      mDecay = value;
    else if (id == 410)
      mPitch = value;
    else if (id >= 411 && id <= 414) {
      if (id == 411)
        mLFOS[1].shape = value;
      else if (id == 412)
        mLFOS[1].rate = value;
      else if (id == 413)
        mLFOS[1].depth = value;
      else if (id == 414)
        mLFOS[1].target = static_cast<int>(value * 5.0f);
    } else if (id == 415)
      mSpray = value;
    else if (id == 416)
      mDetune = value;
    else if (id == 417)
      mRandomTiming = value;
    else if (id == 418)
      mMaxGrains = static_cast<int>(value * 95.0f + 5.0f);
    else if (id == 419)
      mWidth = value;
    else if (id == 420)
      mReverseProb = value;
    else if (id >= 421 && id <= 424) {
      if (id == 421)
        mLFOS[2].shape = value;
      else if (id == 422)
        mLFOS[2].rate = value;
      else if (id == 423)
        mLFOS[2].depth = value;
      else if (id == 424)
        mLFOS[2].target = static_cast<int>(value * 5.0f);
    }
    // New ADSR Params
    else if (id == 425)
      mMainAttack = value;
    else if (id == 426)
      mMainDecay = value;
    else if (id == 427)
      mMainSustain = value;
    else if (id == 428)
      mMainRelease = value;
    else if (id == 429)
      mGain = value * 2.5f; // 0 to 250% Gain

    // Apply to live voices
    for (auto &v : mVoices) {
      if (v.active)
        v.envelope.setParameters(mMainAttack, mMainDecay, mMainSustain,
                                 mMainRelease);
    }
  }

  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    for (const auto &g : mGrains)
      if (g.isActive)
        return true;
    return false;
  }

  void render(float *left, float *right) {
    if (mSource.empty()) {
      *left = *right = 0.0f;
      return;
    }

    bool hasActive = isActive();
    if (!hasActive) {
      *left = *right = 0.0f;
      return;
    }

    // Process LFOs
    float lfoOffsets[3] = {mLFOS[0].nextValue(), mLFOS[1].nextValue(),
                           mLFOS[2].nextValue()};

    for (int i = 0; i < 16; ++i) {
      Voice &v = mVoices[i];
      if (!v.active)
        continue;

      float envVal = v.envelope.nextValue();
      if (envVal < 0.0001f && !v.envelope.isActive()) {
        v.active = false;
      }

      // Spawn logic
      float grainDuration = (mGrainSize * 44100.0f * 2.0f) + 100.0f;
      float overlap = 0.1f + (mDensity * 4.0f);
      float interval = grainDuration / overlap;
      if (interval < 1.0f)
        interval = 1.0f;

      v.spawnCounter += 1.0f;
      if (v.spawnCounter >= interval) {
        v.spawnCounter = 0.0f;
        spawnGrain(lfoOffsets, i);
      }
    }

    // 2. Render and Mix Grains
    float lMixed = 0.0f, rMixed = 0.0f;
    int activeCount = 0;
    for (auto &g : mGrains) {
      if (g.isActive) {
        float sample = g.nextSample(mSource);

        // Multiplier from parent voice (ADSR + Velocity)
        float masterGain = 0.0f;
        if (g.voiceIdx >= 0 && g.voiceIdx < 16) {
          masterGain = mVoices[g.voiceIdx].envelope.getValue() *
                       mVoices[g.voiceIdx].amplitude;
        }

        float out = sample * masterGain;
        lMixed += out * (1.0f - g.lOffset);
        rMixed += out * (1.0f - g.rOffset);
        activeCount++;
      }
    }

    float norm = activeCount > 0 ? 1.0f / sqrtf(activeCount) : 0.0f;
    // Boost output gain (2.5x)
    float finalGain = norm * 2.5f * mGain; // Apply user gain

    *left = lMixed * finalGain;
    *right = rMixed * finalGain;
  }

  struct PlayheadInfo {
    float pos;
    float vol;
  };
  void getPlayheads(PlayheadInfo *out, int maxCount) {
    int count = 0;
    for (const auto &g : mGrains) {
      if (g.isActive && count < maxCount) {
        out[count].pos = g.position / mSource.size();

        // VISIBILITY FIX: Multiply grain envelope by voice envelope so it fades
        // correctly
        float voiceEnv = 0.0f;
        if (g.voiceIdx >= 0 && g.voiceIdx < 16) {
          voiceEnv = mVoices[g.voiceIdx].envelope.getValue();
        }

        out[count].vol = g.envValue * voiceEnv;
        count++;
      }
    }
    for (int i = count; i < maxCount; ++i) {
      out[i].pos = -1.0f;
      out[i].vol = 0.0f;
    }
  }

  std::vector<float> getAmplitudeWaveform(int numPoints) const {
    std::vector<float> result;
    if (mSource.empty())
      return result;
    int step = mSource.size() / numPoints;
    if (step < 1)
      step = 1;
    for (int i = 0; i < numPoints; ++i) {
      float maxVal = 0.0f;
      int end = std::min((int)mSource.size(), (i + 1) * step);
      for (int j = i * step; j < end; ++j) {
        maxVal = std::max(maxVal, std::abs(mSource[j]));
      }
      result.push_back(maxVal);
    }
    return result;
  }

private:
  std::shared_ptr<std::mutex> mBufferLock = std::make_shared<std::mutex>();
  float mBasePitch = 1.0f;
  std::vector<float> mSource;
  std::vector<Grain> mGrains;
  std::vector<LFO> mLFOS;
  std::vector<Voice> mVoices;

  float mPosition = 0.0f;
  float mSpeed = 1.0f;
  float mGrainSize = 0.1f;
  float mDensity = 0.2f;
  // mAttack and mDecay already defined in public section
  float mPitch = 1.0f;
  float mSpray = 0.0f;
  float mDetune = 0.0f;
  float mRandomTiming = 0.0f;
  int mMaxGrains = 20;
  float mWidth = 0.5f;
  float mReverseProb = 0.0f;

  // Main Envelope Params
  float mMainAttack = 0.01f;
  float mMainDecay = 0.1f;
  float mMainSustain = 1.0f;
  float mMainRelease = 0.1f;
  float mGain = 1.0f;

  void spawnGrain(float *lfoOffsets, int voiceIdx) {
    Voice &v = mVoices[voiceIdx];
    // Find inactive grain
    for (auto &g : mGrains) {
      if (!g.isActive) {
        float p = mPosition;
        if (mLFOS[0].target == 1)
          p += lfoOffsets[0];
        p +=
            (static_cast<float>(rand()) / static_cast<float>(RAND_MAX) - 0.5f) *
            mSpray;
        p = std::max(0.0f, std::min(1.0f, p));

        float sp = mSpeed;
        if (mLFOS[0].target == 2)
          sp *= (1.0f + lfoOffsets[0]);

        float grainPitch = mPitch;
        if (mLFOS[1].target == 5)
          grainPitch *= (1.0f + lfoOffsets[1]);
        grainPitch +=
            (static_cast<float>(rand()) / static_cast<float>(RAND_MAX) - 0.5f) *
            mDetune;

        g.position = p * mSource.size();
        g.speed = sp * v.basePitch * grainPitch;
        g.isReverse = (static_cast<float>(rand()) /
                       static_cast<float>(RAND_MAX)) < mReverseProb;

        float length = mGrainSize;
        if (mLFOS[1].target == 1)
          length *= (1.0f + lfoOffsets[1]);
        g.initialLife = static_cast<int>(length * 44100.0f * 2.0f + 100);
        g.life = g.initialLife;
        g.envValue = 0.0f;
        g.voiceIdx = voiceIdx; // Link to voice

        g.attackStep = 1.0f / (g.initialLife * 0.1f);
        g.decayStep = 1.0f / (g.initialLife * 0.9f);

        float pan =
            (static_cast<float>(rand()) / static_cast<float>(RAND_MAX) - 0.5f) *
            mWidth;
        g.lOffset = 0.5f + pan;
        g.rOffset = 0.5f - pan;

        g.isActive = true;
        break;
      }
    }
  }
};

#endif
