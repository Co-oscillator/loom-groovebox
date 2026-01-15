#ifndef DELAY_FX_H
#define DELAY_FX_H

#include "../Utils.h"
#include <algorithm>
#include <cmath>
#include <vector>

class DelayFx {
public:
  DelayFx(int maxDelayFrames = 192000) {
    mBufferL.assign(maxDelayFrames, 0.0f);
    mBufferR.assign(maxDelayFrames, 0.0f);
  }

  void setDelay(float frames) {
    if (frames < mBufferL.size())
      mTargetDelayFrames = frames;
  }
  void setDelayTime(float value) {
    mTargetDelayFrames = value * ((float)mBufferL.size() - 2.0f);
    if (mTargetDelayFrames < 1.0f)
      mTargetDelayFrames = 1.0f;
  }

  void setFeedback(float feedback) { mTargetFeedback = feedback; }
  void setMix(float mix) { mTargetMix = mix; }
  void setFilterMix(float mix) { mTargetFilterMix = mix; }
  void setFilterResonance(float res) { mTargetResonance = res; }

  void setFilter(float filterMix, float resonance) {
    mTargetFilterMix = filterMix;
    mTargetResonance = resonance;
  }

  void setType(int type) {
    mType = type;
  } // 0=Digital, 1=Tape, 2=PingPong, 3=Reverse

  void clear() {
    std::fill(mBufferL.begin(), mBufferL.end(), 0.0f);
    std::fill(mBufferR.begin(), mBufferR.end(), 0.0f);
    mWriteIndex = 0;
    mSvfZ1L = mSvfZ2L = mSvfZ1R = mSvfZ2R = 0.0f;
    mSmoothedDelay = mTargetDelayFrames;
    mFeedback = mTargetFeedback;
    mMix = mTargetMix;
    mFilterMix = mTargetFilterMix;
    mResonance = mTargetResonance;
  }

  void processStereo(float inL, float inR, float &outL, float &outR,
                     float sampleRate = 48000.0f) {
    if (!std::isfinite(inL))
      inL = 0.0f;
    if (!std::isfinite(inR))
      inR = 0.0f;

    // Smooth Transitions
    mSmoothedDelay += 0.001f * (mTargetDelayFrames - mSmoothedDelay);
    mFeedback += 0.001f * (mTargetFeedback - mFeedback);
    mMix += 0.001f * (mTargetMix - mMix);
    mFilterMix += 0.001f * (mTargetFilterMix - mFilterMix);
    mResonance += 0.001f * (mTargetResonance - mResonance);

    float delayedL = 0.0f, delayedR = 0.0f;

    // Read from Delay Lines
    float rp = (float)mWriteIndex - mSmoothedDelay;
    while (rp < 0)
      rp += (float)mBufferL.size();
    int i0 = (int)rp;
    int i1 = (i0 + 1) % mBufferL.size();
    float frac = rp - (float)i0;

    delayedL = mBufferL[i0] * (1.0f - frac) + mBufferL[i1] * frac;
    delayedR = mBufferR[i0] * (1.0f - frac) + mBufferR[i1] * frac;

    if (mType == 1) { // Tape
      delayedL = fast_tanh(delayedL * 1.5f);
      delayedR = fast_tanh(delayedR * 1.5f);
    }

    // Filter Logic (Per Channel)
    auto processFilter = [&](float input, float &z1, float &z2, float cutoff,
                             float res) {
      float g = tanf(M_PI * cutoff / sampleRate);
      float k = 2.0f - (res * 1.95f);
      float a1 = 1.0f / (1.0f + g * (g + k));
      float a2 = g * a1;
      float a3 = g * a2;
      float v3 = input - z2;
      float v1 = a1 * z1 + a2 * v3;
      float v2 = z2 + a2 * z1 + a3 * v3;
      z1 = 2.0f * v1 - z1;
      z2 = 2.0f * v2 - z2;
      if (std::abs(z1) < 1.0e-12f)
        z1 = 0.0f;
      if (std::abs(z2) < 1.0e-12f)
        z2 = 0.0f;

      if (mTargetFilterMix < 0.45f)
        return v2; // LP
      if (mTargetFilterMix > 0.55f)
        return input - k * v1 - v2; // HP
      return input;                 // Dry
    };

    float cutoff = 20000.0f;
    if (mFilterMix < 0.45f)
      cutoff = 100.0f * powf(200.0f, mFilterMix / 0.45f);
    else if (mFilterMix > 0.55f)
      cutoff = 40.0f * powf(200.0f, (mFilterMix - 0.55f) / 0.45f);
    cutoff = std::max(20.0f, std::min(sampleRate * 0.45f, cutoff));

    float filteredL =
        processFilter(delayedL, mSvfZ1L, mSvfZ2L, cutoff, mResonance);
    float filteredR =
        processFilter(delayedR, mSvfZ1R, mSvfZ2R, cutoff, mResonance);

    // Cross-Feedback / Ping-Pong
    float nextL = 0, nextR = 0;
    if (mType == 2) { // Ping-Pong
      nextL = inL + filteredR * mFeedback;
      nextR = inR + filteredL * mFeedback;
    } else {
      nextL = inL + filteredL * mFeedback;
      nextR = inR + filteredR * mFeedback;
    }

    mBufferL[mWriteIndex] = fast_tanh(nextL);
    mBufferR[mWriteIndex] = fast_tanh(nextR);
    mWriteIndex = (mWriteIndex + 1) % mBufferL.size();

    outL = (inL * (1.0f - mMix)) + (filteredL * mMix);
    outR = (inR * (1.0f - mMix)) + (filteredR * mMix);
  }

  float process(float input, float sampleRate = 48000.0f) {
    float l = 0, r = 0;
    processStereo(input, input, l, r, sampleRate);
    return (l + r) * 0.5f;
  }

private:
  std::vector<float> mBufferL;
  std::vector<float> mBufferR;
  int mWriteIndex = 0;
  float mTargetDelayFrames = 11025.0f;
  float mSmoothedDelay = 11025.0f;
  int mCounter = 1;

  float mFeedback = 0.5f, mTargetFeedback = 0.5f;
  float mMix = 0.5f, mTargetMix = 0.5f;
  float mFilterMix = 0.5f, mTargetFilterMix = 0.5f;
  float mResonance = 0.0f, mTargetResonance = 0.0f;
  float mSvfZ1L = 0.0f, mSvfZ2L = 0.0f;
  float mSvfZ1R = 0.0f, mSvfZ2R = 0.0f;
  int mType = 0;
};

#endif // DELAY_FX_H
