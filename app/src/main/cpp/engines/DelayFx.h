#ifndef DELAY_FX_H
#define DELAY_FX_H

#include <algorithm>
#include <cmath>
#include <vector>

class DelayFx {
public:
  DelayFx(int maxDelayFrames = 44100) { mBuffer.assign(maxDelayFrames, 0.0f); }

  void setDelay(int frames) {
    if (frames < mBuffer.size())
      mDelayFrames = frames;
  }

  void setFeedback(float feedback) { mFeedback = feedback; }
  void setMix(float mix) { mMix = mix; }
  void setFilterMix(float mix) {
    mFilterMix = mix;
    updateFilterCoeffs();
  }
  void setFilterResonance(float res) {
    mResonance = res;
    updateFilterCoeffs();
  }

  void setFilter(float filterMix, float resonance) {
    mFilterMix = filterMix;
    mResonance = resonance;
    updateFilterCoeffs();
  }

  void setType(int type) {
    mType = type;
  } // 0=Digital, 1=Tape, 2=PingPong, 3=Reverse

  void clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mWriteIndex = 0;
    mReadIndex = 0;
    mLow = 0.0f;
    mBand = 0.0f;
    mHigh = 0.0f;
  }

  void updateFilterCoeffs() {
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
    float f = 2.0f * sinf(M_PI * cutoff / 44100.0f);
    mF = f;
  }

  void processStereo(float inL, float inR, float &outL, float &outR) {
    if (!std::isfinite(inL))
      inL = 0.0f;
    float input = (inL + inR) * 0.5f;

    float delayed = 0.0f;
    if (mType == 3) { // Reverse
      int revIdx = (mWriteIndex - mCounter + mBuffer.size()) % mBuffer.size();
      delayed = mBuffer[revIdx];
      mCounter = (mCounter + 1) % mDelayFrames;
      if (mCounter == 0)
        mCounter = 1;
    } else {
      delayed = mBuffer[mReadIndex];
    }

    if (mType == 1) { // Tape (Saturation + Wow)
      mWowPhase += 0.0001f;
      if (mWowPhase > 1.0f)
        mWowPhase = 0.0f;
      delayed = std::tanh(delayed * 1.5f + 0.1f * sinf(mWowPhase * 6.28f));
    }

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
    }

    float toWrite = input + filtered * mFeedback;
    toWrite = std::max(-2.0f, std::min(2.0f, toWrite));
    mBuffer[mWriteIndex] = toWrite;

    mWriteIndex = (mWriteIndex + 1) % mBuffer.size();
    mReadIndex = (mWriteIndex - mDelayFrames + mBuffer.size()) % mBuffer.size();

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

  // Backwards compatibility for mono bus usage
  float process(float input) {
    float l = 0, r = 0;
    processStereo(input, input, l, r);
    return (l + r) * 0.5f;
  }

private:
  std::vector<float> mBuffer;
  int mWriteIndex = 0, mReadIndex = 0, mDelayFrames = 11025;
  int mCounter = 1; // For Reverse
  bool mPingPongState = false;
  float mFeedback = 0.5f, mMix = 0.5f, mWowPhase = 0.0f;
  float mFilterMix = 0.5f, mResonance = 0.0f;
  float mLow = 0.0f, mBand = 0.0f, mHigh = 0.0f, mF = 0.1f;
  int mMode = 2, mType = 0;
};

#endif // DELAY_FX_H
