#ifndef ENVELOPE_FOLLOWER_H
#define ENVELOPE_FOLLOWER_H

#include <algorithm>
#include <atomic>
#include <cmath>

class EnvelopeFollower {
public:
  EnvelopeFollower() = default;

  // Custom copy constructor (manual load/store for atomic)
  EnvelopeFollower(const EnvelopeFollower& other) {
    mEnvelope.store(other.mEnvelope.load());
    mAttack = other.mAttack;
    mRelease = other.mRelease;
  }

  // Custom move constructor
  EnvelopeFollower(EnvelopeFollower&& other) noexcept {
    mEnvelope.store(other.mEnvelope.load());
    mAttack = other.mAttack;
    mRelease = other.mRelease;
  }

  // Custom assignment operator
  EnvelopeFollower& operator=(const EnvelopeFollower& other) {
    if (this != &other) {
      mEnvelope.store(other.mEnvelope.load());
      mAttack = other.mAttack;
      mRelease = other.mRelease;
    }
    return *this;
  }

  void setParameters(float attackMs, float releaseMs, float sampleRate) {
    mAttack = expf(-1.0f / (attackMs * 0.001f * sampleRate));
    mRelease = expf(-1.0f / (releaseMs * 0.001f * sampleRate));
  }

  float process(float input) {
    float absInput = fabsf(input);
    float currentEnv = mEnvelope.load(std::memory_order_relaxed);
    
    if (absInput > currentEnv) {
      currentEnv = mAttack * currentEnv + (1.0f - mAttack) * absInput;
    } else {
      currentEnv = mRelease * currentEnv + (1.0f - mRelease) * absInput;
    }

    if (currentEnv < 1e-9f)
      currentEnv = 0.0f;
    
    mEnvelope.store(currentEnv, std::memory_order_relaxed);
    return currentEnv;
  }

  float getLevel() const { return mEnvelope.load(std::memory_order_relaxed); }

private:
  std::atomic<float> mEnvelope{0.0f};
  float mAttack = 0.99f;
  float mRelease = 0.999f;
};

#endif // ENVELOPE_FOLLOWER_H
