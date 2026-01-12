#ifndef DELAY_FX_H
#define DELAY_FX_H

#include "../Utils.h"
#include <algorithm>
#include <cmath>
#include <vector>

class DelayFx {
public:
  DelayFx(int maxDelayFrames = 192000) { mBuffer.assign(maxDelayFrames, 0.0f); }

  void setDelay(float frames) {
    if (frames < mBuffer.size())
      mTargetDelayFrames = frames;
  }
  void setDelayTime(float value) {
    mTargetDelayFrames = value * ((float)mBuffer.size() - 2.0f);
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
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mWriteIndex = 0;
    mSvfZ1 = 0.0f;
    mSvfZ2 = 0.0f;
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
    float input = (inL + inR) * 0.5f;

    // Smooth Transitions
    mSmoothedDelay += 0.001f * (mTargetDelayFrames - mSmoothedDelay);
    mFeedback += 0.001f * (mTargetFeedback - mFeedback);
    mMix += 0.001f * (mTargetMix - mMix);
    mFilterMix += 0.001f * (mTargetFilterMix - mFilterMix);
    mResonance += 0.001f * (mTargetResonance - mResonance);

    float delayed = 0.0f;
    if (mType == 3) { // Reverse
      int revIdx = (mWriteIndex - mCounter + mBuffer.size()) % mBuffer.size();
      delayed = mBuffer[revIdx];
      mCounter = (mCounter + 1) % (int)std::max(1.0f, mSmoothedDelay);
      if (mCounter == 0)
        mCounter = 1;
    } else {
      float rp = (float)mWriteIndex - mSmoothedDelay;
      while (rp < 0)
        rp += (float)mBuffer.size();
      int i0 = (int)rp;
      int i1 = (i0 + 1) % mBuffer.size();
      float frac = rp - (float)i0;
      delayed = mBuffer[i0] * (1.0f - frac) + mBuffer[i1] * frac;
    }

    if (mType == 1) { // Tape
      delayed = fast_tanh(delayed * 1.5f);
    }

    // Trapezoidal SVF (Zero-Delay Feedback) Processing
    float targetCutoff = 20000.0f;
    float lpMix = 0.0f;
    float hpMix = 0.0f;
    float dryMix = 1.0f;

    if (mFilterMix < 0.45f) {
      float t = mFilterMix / 0.45f;
      targetCutoff = 100.0f * powf(200.0f, t);
      lpMix = 1.0f;
      dryMix = 0.0f;
    } else if (mFilterMix > 0.55f) {
      float t = (mFilterMix - 0.55f) / 0.45f;
      targetCutoff = 40.0f * powf(200.0f, t);
      hpMix = 1.0f;
      dryMix = 0.0f;
    }

    // Coeffs
    float maxCutoff = sampleRate * 0.45f;
    if (targetCutoff > maxCutoff)
      targetCutoff = maxCutoff;
    if (targetCutoff < 20.0f)
      targetCutoff = 20.0f;

    float g = tanf(M_PI * targetCutoff / sampleRate);
    float k = 2.0f - (mResonance * 1.95f); // Slightly more reso room
    float a1 = 1.0f / (1.0f + g * (g + k));
    float a2 = g * a1;
    float a3 = g * a2;

    // Filter Sample
    float v3 = delayed - mSvfZ2;
    float v1 = a1 * mSvfZ1 + a2 * v3;
    float v2 = mSvfZ2 + a2 * mSvfZ1 + a3 * v3;

    // State Update
    mSvfZ1 = 2.0f * v1 - mSvfZ1;
    mSvfZ2 = 2.0f * v2 - mSvfZ2;

    // Output Mix
    float filtered =
        (v2 * lpMix) + ((delayed - k * v1 - v2) * hpMix) + (delayed * dryMix);

    // Denormal prevention
    if (std::abs(mSvfZ1) < 1.0e-12f)
      mSvfZ1 = 0.0f;
    if (std::abs(mSvfZ2) < 1.0e-12f)
      mSvfZ2 = 0.0f;

    float toWrite = input + filtered * mFeedback;
    // Use soft saturation in feedback loop to prevent cumulative degradation
    toWrite = fast_tanh(toWrite);

    mBuffer[mWriteIndex] = toWrite;
    mWriteIndex = (mWriteIndex + 1) % mBuffer.size();

    if (mType == 2) { // Ping-Pong
      mPingPongState = !mPingPongState;
      if (mPingPongState) {
        outL = filtered * mMix;
        outR = 0.0f;
      } else {
        outL = 0.0f;
        outR = filtered * mMix;
      }
    } else {
      outL = outR = filtered * mMix;
    }
  }

  float process(float input, float sampleRate = 48000.0f) {
    float l = 0, r = 0;
    processStereo(input, input, l, r, sampleRate);
    return (l + r) * 0.5f;
  }

private:
  std::vector<float> mBuffer;
  int mWriteIndex = 0;
  float mTargetDelayFrames = 11025.0f;
  float mSmoothedDelay = 11025.0f;
  int mCounter = 1;

  bool mPingPongState = false;
  float mFeedback = 0.5f, mTargetFeedback = 0.5f;
  float mMix = 0.5f, mTargetMix = 0.5f;
  float mFilterMix = 0.5f, mTargetFilterMix = 0.5f;
  float mResonance = 0.0f, mTargetResonance = 0.0f;
  float mSvfZ1 = 0.0f, mSvfZ2 = 0.0f;
  int mType = 0;
};

#endif // DELAY_FX_H
