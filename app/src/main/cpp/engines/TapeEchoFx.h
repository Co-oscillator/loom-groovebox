#ifndef TAPE_ECHO_FX_H
#define TAPE_ECHO_FX_H

#include <algorithm>
#include <cmath>
#include <vector>

class TapeEchoFx {
public:
  TapeEchoFx() {
    mBuffer.resize(88200, 0.0f); // 2 sec
  }

  float process(float input, float sampleRate) {
    if (mMix <= 0.001f)
      return 0.0f;
    if (!std::isfinite(input))
      input = 0.0f;

    // Tape Speed / Wow & Flutter
    // We modulate the READ speed, not just position, to simulate varispeed
    // pitch effects. Or simpler: modulate delay time, but with interpolation it
    // creates pitch shift naturally.

    // Wow LFO (Slow)
    mWowPhase += 0.5f / sampleRate; // 0.5 Hz
    if (mWowPhase >= 1.0f)
      mWowPhase -= 1.0f;

    // Flutter LFO (Fast)
    mFlutterPhase += 8.0f / sampleRate; // 8 Hz
    if (mFlutterPhase >= 1.0f)
      mFlutterPhase -= 1.0f;

    float modulation =
        (std::sin(2.0f * 3.14159265f * mWowPhase) * mWowAmount) +
        (std::sin(2.0f * 3.14159265f * mFlutterPhase) * mFlutterAmount);

    float targetDelay = mTime + modulation * mTime;

    // Target Delay in samples
    float delaySamples = targetDelay * sampleRate;

    // Smoothed Delay for rubbery transitions
    mSmoothedDelay += 0.0002f * (delaySamples - mSmoothedDelay);

    // Read
    float readPos = (float)mWritePos - mSmoothedDelay;
    while (readPos < 0.0f)
      readPos += mBuffer.size();
    while (readPos >= mBuffer.size())
      readPos -= mBuffer.size();

    // Cubic Interpolation for better quality repitching? Linear is fine for
    // grit.
    int i0 = (int)readPos;
    int i1 = i0 + 1;
    if (i1 >= mBuffer.size())
      i1 = 0;
    float frac = readPos - i0;
    float echo = mBuffer[i0] * (1.0f - frac) + mBuffer[i1] * frac;

    // Saturation on echo
    if (mSaturation > 0.0f) {
      echo = std::tanh(echo * (1.0f + mSaturation * 2.0f));
    }

    // Feedback
    float feedbackSig = echo * mFeedback;
    // Simple LPF on feedback to simulate tape degradation
    mFilterState += 0.4f * (feedbackSig - mFilterState); // Darken
    // Anti-Denormal
    if (std::abs(mFilterState) < 1.0e-15f)
      mFilterState = 0.0f;
    feedbackSig = mFilterState;

    float toWrite = input + feedbackSig;
    if (!std::isfinite(toWrite)) {
      toWrite = 0.0f;
      mFilterState = 0.0f;
    }
    toWrite = std::max(-4.0f, std::min(4.0f, toWrite));
    mBuffer[mWritePos] = toWrite;
    mWritePos++;
    if (mWritePos >= mBuffer.size())
      mWritePos = 0;

    return echo * mMix;
  }

  void setParameters(float time, float feedback, float saturation, float mix) {
    setDelayTime(time);
    setFeedback(feedback);
    setDrive(saturation);
    setMix(mix);
    // Defaults for others
    mWowAmount = 0.002f;
    mFlutterAmount = 0.0005f;
  }

  void setDelayTime(float v) { mTime = 0.05f + v * 0.95f; } // 50ms - 1s
  void setFeedback(float v) {
    mFeedback = v * 0.95f;
  } // Lowered to prevent runaway clipping
  void setWow(float v) { mWowAmount = v * 0.005f; }
  void setFlutter(float v) { mFlutterAmount = v * 0.002f; }
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
  float mSaturation = 0.0f;
  float mMix = 0.0f;

  float mWowAmount = 0.002f;
  float mFlutterAmount = 0.0005f;
};

#endif
