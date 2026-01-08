#ifndef STEREO_SPREAD_FX_H
#define STEREO_SPREAD_FX_H

#include <cmath>
#include <vector>

class StereoSpreadFx {
public:
  void updateSampleRate(float sr) {}
  StereoSpreadFx() {
    // Short delays for Haas effect / widening
    mDelayL.resize(2048, 0.0f);
    mDelayR.resize(2048, 0.0f);
  }

  void process(float input, float &outL, float &outR, float sampleRate) {
    if (mMix <= 0.001f) {
      outL = 0.0f;
      outR = 0.0f;
      return;
    }

    // LFO
    mPhase += mRate / sampleRate;
    if (mPhase >= 1.0f)
      mPhase -= 1.0f;
    float lfo = std::sin(2.0f * 3.14159265f * mPhase);

    // Modulate Delay Times inversely for widening
    float baseDelay = 0.010f; // 10ms
    float mod = mDepth * mWidth * 0.005f;

    float dL = baseDelay + mod * lfo;
    float dR = baseDelay - mod * lfo; // Inverse modulation

    // Delay L
    float sampL = readDelay(mDelayL, mPosL, dL * sampleRate);
    mDelayL[mPosL] = input;
    mPosL++;
    if (mPosL >= mDelayL.size())
      mPosL = 0;

    // Delay R
    float sampR = readDelay(mDelayR, mPosR, dR * sampleRate);
    mDelayR[mPosR] = input;
    mPosR++;
    if (mPosR >= mDelayR.size())
      mPosR = 0;

    outL = sampL * mMix;
    outR = sampR * mMix;
  }

  void setParameters(float width, float rate, float depth, float mix) {
    setWidth(width);
    setRate(rate);
    setDepth(depth);
    setMix(mix);
  }

  void setWidth(float v) { mWidth = v; }
  void setRate(float v) { mRate = 0.1f + v * 2.0f; }
  void setDepth(float v) { mDepth = v; }
  void setMix(float v) { mMix = v; }

private:
  float readDelay(const std::vector<float> &buf, int writePos,
                  float delaySamples) {
    float readPos = (float)writePos - delaySamples;
    while (readPos < 0.0f)
      readPos += buf.size();

    int i0 = (int)readPos;
    int i1 = i0 + 1;
    if (i1 >= buf.size())
      i1 = 0;
    float frac = readPos - i0;
    return buf[i0] * (1.0f - frac) + buf[i1] * frac;
  }

  std::vector<float> mDelayL, mDelayR;
  int mPosL = 0, mPosR = 0;
  float mPhase = 0.0f;

  float mWidth = 1.0f;
  float mRate = 0.5f;
  float mDepth = 0.5f;
  float mMix = 0.0f;
};

#endif
