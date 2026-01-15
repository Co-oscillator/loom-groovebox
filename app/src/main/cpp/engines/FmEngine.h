#ifndef FM_ENGINE_H
#define FM_ENGINE_H

#include "../Utils.h"
#include "FmOperator.h"
#include <algorithm>
#include <cmath>
#include <vector>

class FmEngine {
public:
  struct Voice {
    bool active = false;
    int note = -1;
    float frequency = 440.0f;
    float amplitude = 1.0f;
    std::vector<FmOperator> operators;
    float lastOp5Out = 0.0f;
    float op5FeedbackHistory = 0.0f;

    // Filter State
    TSvf svf;

    Voice() { operators.resize(6); }

    void reset() {
      active = false;
      note = -1;
      for (auto &op : operators)
        op.reset();
      lastOp5Out = 0.0f;
      op5FeedbackHistory = 0.0f;
      svf.setParams(1000.0f, 0.7f, 44100.0f);
      pitchEnv = 0.0f;
      pitchEnvDecay = 0.005f;
    }
    float pitchEnv = 0.0f;
    float pitchEnvDecay = 0.005f;
  };

  FmEngine() {
    mVoices.resize(16);
    for (auto &v : mVoices)
      v.reset();
    mOpLevels.assign(6, 0.0f);
    mOpRatios.assign(6, 1.0f);
    mOpAttack.assign(6, 0.01f);
    mOpDecay.assign(6, 0.2f);
    mOpSustain.assign(6, 0.7f);
    mOpRelease.assign(6, 0.3f);
    resetToDefaults();
  }

  void resetToDefaults() {
    setAlgorithm(0);
    mFeedback = 0.0f;
    mBrightness = 1.0f;
    mDetune = 0.0f;
    mFeedbackDrive = 0.0f;
    mPitchSweepAmount = 0.0f;

    std::fill(mOpLevels.begin(), mOpLevels.end(), 0.0f);
    std::fill(mOpRatios.begin(), mOpRatios.end(), 1.0f);
    mOpLevels[0] = 0.8f;

    for (auto &v : mVoices) {
      for (int i = 0; i < 6; ++i) {
        v.operators[i].setLevel(mOpLevels[i]);
        v.operators[i].setADSR(mOpAttack[i], mOpDecay[i], mOpSustain[i],
                               mOpRelease[i]);
      }
    }
  }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.pitchEnv = 0.0f;
      for (auto &op : v.operators)
        op.reset();
    }
  }

  void setAlgorithm(int alg) {
    mAlgorithm = alg;
    if (alg == 0) {
      mCarrierMask = (1 << 0);
      mActiveMask = 63;
    } else if (alg == 1) {
      mCarrierMask = (1 << 0) | (1 << 3);
      mActiveMask = 63;
    } else if (alg == 2) {
      mCarrierMask = 63;
      mActiveMask = 63;
    } else if (alg == 3) {
      mCarrierMask = (1 << 0);
      mActiveMask = 63;
    } else {
      mCarrierMask = 1;
      mActiveMask = 63;
    }
  }

  void setFilter(float v) { mCutoff = v; }
  void setResonance(float v) { mResonance = v; }
  void setUseEnvelope(bool v) { mUseEnvelope = v; }
  void loadPreset(int presetId) { /* TODO if needed */ }

  void setCarrierMask(int mask) { mCarrierMask = mask; }
  void setIgnoreNoteFrequency(bool ignore) { mIgnoreNoteFrequency = ignore; }

  void setFrequency(float freq, float sampleRate) {
    mFrequency = freq;
    mSampleRate = sampleRate;
  }

  void updateSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      for (auto &op : v.operators)
        op.setFrequency(v.frequency, 1.0f, sr);
    }
  }

  void triggerNote(int note, int velocity) {
    int idx = -1;
    for (int i = 0; i < 16; ++i)
      if (!mVoices[i].active) {
        idx = i;
        break;
      }
    if (idx == -1)
      idx = 0;

    Voice &v = mVoices[idx];
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;

    if (!mIgnoreNoteFrequency) {
      v.frequency = 440.0f * powf(2.0f, (note - 69) / 12.0f);
    } else {
      v.frequency = mFrequency;
    }

    for (int i = 0; i < 6; ++i) {
      float ratio = mOpRatios[i] + (i * mDetune * 0.01f);
      v.operators[i].setFrequency(v.frequency, ratio, mSampleRate);
      v.operators[i].setLevel(mOpLevels[i]);
      v.operators[i].setADSR(mOpAttack[i], mOpDecay[i], mOpSustain[i],
                             mOpRelease[i]);
      v.operators[i].setUseEnvelope(mUseEnvelope);
      if (mActiveMask & (1 << i))
        v.operators[i].trigger();
    }
    v.svf.setParams(1000.0f, 0.7f, mSampleRate);
    v.pitchEnv = 1.0f;
  }

  void releaseNote(int note) {
    for (auto &v : mVoices)
      if (v.active && v.note == note)
        for (auto &op : v.operators)
          op.release();
  }

  void setFeedback(float v) { mFeedback = v; }
  void setPitchSweep(float v) { mPitchSweepAmount = v; }

  void setOpLevel(int i, float v) {
    if (i >= 0 && i < 6)
      mOpLevels[i] = v;
  }
  float getOpLevel(int i) const {
    if (i >= 0 && i < 6)
      return mOpLevels[i];
    return 0.0f;
  }

  void setOpRatio(int i, float r) {
    if (i >= 0 && i < 6)
      mOpRatios[i] = r;
  }
  void setOpADSR(int i, float a, float d, float s, float r) {
    if (i >= 0 && i < 6) {
      mOpAttack[i] = a;
      mOpDecay[i] = d;
      mOpSustain[i] = s;
      mOpRelease[i] = r;
      for (auto &voice : mVoices)
        voice.operators[i].setADSR(a, d, s, r);
    }
  }

  void setParameter(int id, float value) {
    if (id == 151 || id == 1)
      mCutoff = value;
    else if (id == 152 || id == 2)
      mResonance = value;
    else if (id == 150)
      setAlgorithm((int)(value * 3.99f));
    else if (id == 153)
      mBrightness = value * 2.0f;
    else if (id == 154)
      mFeedback = value;
    else if (id == 155)
      mDetune = value;
    else if (id == 156)
      mFeedbackDrive = value;
    else if (id == 157)
      mPitchSweepAmount = value;
  }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;

    for (auto &v : mVoices) {
      if (!v.active)
        continue;
      bool opActive = false;
      for (auto &op : v.operators)
        if (op.isActive()) {
          opActive = true;
          break;
        }
      if (!opActive) {
        v.active = false;
        continue;
      }
      activeCount++;

      float velModScale = 1.0f - (mVelSens * (1.0f - v.amplitude));
      float fbSignal = (v.op5FeedbackHistory + v.lastOp5Out) * 0.5f;
      if (mFeedbackDrive > 0.0f)
        fbSignal = fast_tanh(fbSignal * (1.0f + mFeedbackDrive * 3.0f));
      float fbIn = fbSignal * mFeedback;
      float modScale = mBrightness;
      float pitchMod = 1.0f + (v.pitchEnv * mPitchSweepAmount);
      v.pitchEnv *= (1.0f - v.pitchEnvDecay);

      float o[6];
      // Simple Serial Algorithm for now
      o[5] = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
      o[4] = v.operators[4].nextSample(o[5] * modScale, pitchMod) * velModScale;
      o[3] = v.operators[3].nextSample(o[4] * modScale, pitchMod) * velModScale;
      o[2] = v.operators[2].nextSample(o[3] * modScale, pitchMod) * velModScale;
      o[1] = v.operators[1].nextSample(o[2] * modScale, pitchMod) * velModScale;
      o[0] = v.operators[0].nextSample(o[1] * modScale, pitchMod);

      float out = 0.0f;
      for (int i = 0; i < 6; ++i)
        if (mCarrierMask & (1 << i))
          out += o[i];

      v.op5FeedbackHistory = v.lastOp5Out;
      v.lastOp5Out = o[5];

      float sample = out * v.amplitude;

      // Filter (TSVF)
      float cutoffNormalized = std::max(0.001f, std::min(0.999f, mCutoff));
      float freq = 20.0f * powf(900.0f, cutoffNormalized);
      v.svf.setParams(freq, std::max(0.1f, mResonance * 4.0f), mSampleRate);

      float filtered = v.svf.process(sample, TSvf::LowPass);
      mixedOutput += fast_tanh(filtered);
    }

    if (activeCount > 1)
      mixedOutput *= 0.7f;
    return mixedOutput;
  }

  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    return false;
  }

private:
  std::vector<Voice> mVoices;
  std::vector<float> mOpLevels, mOpRatios, mOpAttack, mOpDecay, mOpSustain,
      mOpRelease;
  float mCutoff = 0.5f, mResonance = 0.0f, mBrightness = 1.0f, mDetune = 0.0f,
        mFeedback = 0.0f, mFeedbackDrive = 0.0f, mVelSens = 0.6f;
  float mPitchSweepAmount = 0.0f;
  int mAlgorithm = 0, mCarrierMask = 1, mActiveMask = 63, mFilterMode = 0;
  float mSampleRate = 44100.0f, mFrequency = 440.0f;
  bool mUseEnvelope = true;
  bool mIgnoreNoteFrequency = false;
};

#endif
