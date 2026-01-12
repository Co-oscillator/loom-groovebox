#ifndef TAPE_ECHO_FX_H
#define TAPE_ECHO_FX_H

#include "../Utils.h"
#include <algorithm>
#include <cmath>
#include <vector>

class TapeEchoFx {
public:
  TapeEchoFx() {
    mBuffer.resize(192000, 0.0f); // 4 sec at 48k
  }

  void clear() {
    std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
    mWritePos = 0;
    mFilterState = 0.0f;
    mSmoothedFeedback = mFeedback;
    mSmoothedSaturation = mSaturation;
    mSmoothedMix = mMix;
  }

  float process(float input, float sampleRate) {
    if (!std::isfinite(input))
      input = 0.0f;

    // Wow & Flutter LFOs
    mWowPhase += 0.5f / sampleRate;
    if (mWowPhase >= 1.0f)
      mWowPhase -= 1.0f;
    mFlutterPhase += 12.0f / sampleRate;
    if (mFlutterPhase >= 1.0f)
      mFlutterPhase -= 1.0f;

    float modulation =
        (std::sin(2.0f * 3.14159265f * mWowPhase) * mWowAmount) +
        (std::sin(2.0f * 3.14159265f * mFlutterPhase) * mFlutterAmount);

    float targetDelaySamples = (mTime + modulation * mTime) * sampleRate;

    // Faster smoothing for "rubbery" transitions
    mSmoothedDelay += 0.001f * (targetDelaySamples - mSmoothedDelay);

    // Read positions
    float readPos = (float)mWritePos - mSmoothedDelay;
    while (readPos < 0.0f)
      readPos += (float)mBuffer.size();
    while (readPos >= (float)mBuffer.size())
      readPos -= (float)mBuffer.size();

    // Hermite Interpolation (4-point)
    int i1 = (int)readPos;
    int i2 = (i1 + 1) % mBuffer.size();
    int i3 = (i2 + 1) % mBuffer.size();
    int i0 = (i1 - 1 + mBuffer.size()) % mBuffer.size();

    float frac = readPos - (float)i1;

    float y0 = mBuffer[i0];
    float y1 = mBuffer[i1];
    float y2 = mBuffer[i2];
    float y3 = mBuffer[i3];

    float a = (3.0f * (y1 - y2) - y0 + y3) * 0.5f;
    float b = 2.0f * y2 + y0 - 5.0f * y1 * 0.5f - y3 * 0.5f;
    float c = (y2 - y0) * 0.5f;
    float d = y1;

    float echo = ((a * frac + b) * frac + c) * frac + d;

    // Smooth Parameters
    mSmoothedFeedback += 0.001f * (mFeedback - mSmoothedFeedback);
    mSmoothedSaturation += 0.001f * (mSaturation - mSmoothedSaturation);
    mSmoothedMix += 0.001f * (mMix - mSmoothedMix);

    // Tape Saturation
    if (mSmoothedSaturation > 0.0f) {
      echo = fast_tanh(echo * (1.0f + mSmoothedSaturation * 4.0f));
    }

    float feedbackSig = echo * mSmoothedFeedback;
    // Low-pass to simulate tape head wear
    mFilterState += 0.1f * (feedbackSig - mFilterState); // Smoother
    if (std::abs(mFilterState) < 1.0e-12f)
      mFilterState = 0.0f;
    feedbackSig = mFilterState;

    float toWrite = input + feedbackSig;
    toWrite = fast_tanh(toWrite);

    if (std::abs(toWrite) < 1.0e-12f)
      toWrite = 0.0f;
    mBuffer[mWritePos] = toWrite;
    mWritePos = (mWritePos + 1) % mBuffer.size();

    return (echo * mSmoothedMix);
  }

  void setParameters(float time, float feedback, float saturation, float mix) {
    setDelayTime(time);
    setFeedback(feedback);
    setDrive(saturation);
    setMix(mix);
    mWowAmount = 0.002f;
    mFlutterAmount = 0.0005f;
    // Sync on load
    mSmoothedFeedback = mFeedback;
    mSmoothedSaturation = mSaturation;
    mSmoothedMix = mMix;
  }

  void setDelayTime(float v) { mTime = 0.05f + (v * v) * 3.95f; } // Up to 4s

  void setFeedback(float v) { mFeedback = v * 0.85f; }
  void setWow(float v) { mWowAmount = v * 0.006f; }
  void setFlutter(float v) { mFlutterAmount = v * 0.003f; }
  void setDrive(float v) { mSaturation = v; }
  void setMix(float v) { mMix = v; }

private:
  std::vector<float> mBuffer;
  int mWritePos = 0;
  float mSmoothedDelay = 1000.0f;
  float mWowPhase = 0.0f;
  float mFlutterPhase = 0.0f;
  float mFilterState = 0.0f;

  float mTime = 0.3f;
  float mFeedback = 0.4f;
  float mSmoothedFeedback = 0.4f;
  float mSaturation = 0.0f;
  float mSmoothedSaturation = 0.0f;
  float mMix = 0.3f;
  float mSmoothedMix = 0.3f;

  float mWowAmount = 0.002f;
  float mFlutterAmount = 0.0005f;
};

#endif
