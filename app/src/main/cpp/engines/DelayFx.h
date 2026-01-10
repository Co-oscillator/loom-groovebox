#ifndef DELAY_FX_H
#define DELAY_FX_H

#include <algorithm>
#include <cmath>
#include <vector>

class DelayFx {
public:
  DelayFx(int maxDelayFrames = 96000) { mBuffer.assign(maxDelayFrames, 0.0f); }

  void setDelay(float frames) {
    if (frames < mBuffer.size())
      mTargetDelayFrames = frames;
  }
  void setDelayTime(float value) {
    mTargetDelayFrames = value * ((float)mBuffer.size() - 2.0f);
    if (mTargetDelayFrames < 1.0f)
      mTargetDelayFrames = 1.0f;
  }

  void setFeedback(float feedback) { mFeedback = feedback; }
  void setMix(float mix) { mMix = mix; }
  void setFilterMix(float mix) { mFilterMix = mix; }
  void setFilterResonance(float res) { mResonance = res; }

  void setFilter(float filterMix, float resonance) {
    mFilterMix = filterMix;
    mResonance = resonance;
  }

  void setType(int type) {
    mType = type;
  } // 0=Digital, 1=Tape, 2=PingPong, 3=Reverse

  void clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mWriteIndex = 0;
    mLow = 0.0f;
    mBand = 0.0f;
    mHigh = 0.0f;
    mSmoothedDelay = mTargetDelayFrames;
  }

  void processStereo(float inL, float inR, float &outL, float &outR) {
    if (!std::isfinite(inL))
      inL = 0.0f;
    float input = (inL + inR) * 0.5f;

    // Smooth Delay Transition
    mSmoothedDelay += 0.001f * (mTargetDelayFrames - mSmoothedDelay);

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
      delayed = std::tanh(delayed * 1.5f);
    }

    // Filter Processing
    float cutoff = 20000.0f;
    if (mFilterMix < 0.45f) {
      cutoff = 200.0f + (mFilterMix / 0.45f) * 11800.0f;
      mMode = 0; // LP
    } else if (mFilterMix > 0.55f) {
      cutoff = 50.0f + ((mFilterMix - 0.55f) / 0.45f) * 8000.0f;
      mMode = 1; // HP
    } else {
      mMode = 2; // Bypass
    }
    float targetF = 2.0f * sinf(M_PI * cutoff / 44100.0f);
    mF += 0.01f * (targetF - mF); // Smooth filter transition

    float filtered = delayed;
    if (mMode != 2) {
      float q = 0.5f + mResonance * 3.0f;
      mLow = mLow + mF * mBand;
      mHigh = filtered - mLow - mBand / q;
      mBand = mBand + mF * mHigh;
      if (mMode == 0)
        filtered = mLow;
      else if (mMode == 1)
        filtered = mHigh;

      // Denormal prevention
      if (std::abs(mLow) < 1.0e-15f)
        mLow = 0.0f;
      if (std::abs(mBand) < 1.0e-15f)
        mBand = 0.0f;
    }

    float toWrite = input + filtered * mFeedback;
    toWrite = std::max(-2.0f, std::min(2.0f, toWrite));
    if (std::abs(toWrite) < 1.0e-15f)
      toWrite = 0.0f;
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

  float process(float input) {
    float l = 0, r = 0;
    processStereo(input, input, l, r);
    return (l + r) * 0.5f;
  }

private:
  std::vector<float> mBuffer;
  int mWriteIndex = 0;
  float mTargetDelayFrames = 11025.0f;
  float mSmoothedDelay = 11025.0f;
  int mCounter = 1; // For Reverse
  bool mPingPongState = false;
  float mFeedback = 0.5f, mMix = 0.5f;
  float mFilterMix = 0.5f, mResonance = 0.0f;
  float mLow = 0.0f, mBand = 0.0f, mHigh = 0.0f, mF = 0.1f;
  int mMode = 2, mType = 0;
};

#endif // DELAY_FX_H
