#ifndef FM_ENGINE_H
#define FM_ENGINE_H

#include "../Utils.h"
#include "Adsr.h"
#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <memory>
#include <oboe/Oboe.h>
#include <vector>

class FmOperator {
public:
  FmOperator() : mPhase(0.0), mPhaseInc(0.0) { mEnvelope.reset(); }

  void setFrequency(float baseFreq, float ratio, float sampleRate) {
    mPhaseInc = (baseFreq * ratio) / sampleRate;
  }

  void setADSR(float a, float d, float s, float r) {
    mEnvelope.setParameters(a, d, s, r);
  }

  void setSampleRate(float sr) { mEnvelope.setSampleRate(sr); }
  void setUseEnvelope(bool use) { mUseEnvelope = use; }

  float nextSample(float modulation, float pitchMod = 1.0f) {
    mPhase += mPhaseInc * pitchMod;
    if (mPhase >= 1.0)
      mPhase -= 1.0;
    float out = FastSine::get(mPhase + modulation);
    return out * (mUseEnvelope ? mEnvelope.nextValue() : 1.0f);
  }

  void trigger() {
    mPhase = 0.0;
    mEnvelope.trigger();
  }

  void release() { mEnvelope.release(); }
  bool isActive() const { return mEnvelope.isActive(); }

private:
  double mPhase;
  double mPhaseInc;
  Adsr mEnvelope;
  bool mUseEnvelope = true;
};

class FmEngine {
public:
  struct Voice {
    bool active = false;
    int note = -1;
    float frequency = 440.0f;
    float amplitude = 1.0f;
    FmOperator operators[6];
    TSvf svf;
    float lastOp5Out = 0.0f;
    float op5FeedbackHistory = 0.0f;
    float pitchEnv = 1.0f;
    float pitchEnvDecay = 0.001f;
    Adsr masterEnv;

    void reset() {
      active = false;
      note = -1;
      for (auto &op : operators) {
        op.setUseEnvelope(true);
      }
      lastOp5Out = 0.0f;
      op5FeedbackHistory = 0.0f;
      masterEnv.reset();
    }
  };

  FmEngine() {
    mVoices.resize(16);
    mOpLevels.assign(6, 0.5f);
    mOpRatios.assign(6, 1.0f);
    mOpAttack.assign(6, 0.01f);
    mOpDecay.assign(6, 0.1f);
    mOpSustain.assign(6, 0.8f);
    mOpRelease.assign(6, 0.5f);
    resetToDefaults();
  }

  void resetToDefaults() {
    mAlgorithm = 0;
    mFeedback = 0.0f;
    mCutoff = 0.5f;
    mResonance = 0.0f;
    mBrightness = 1.0f;
    mDetune = 0.0f;
    mFeedbackDrive = 0.0f;
    mCarrierMask = 1;
    mActiveMask = 63;
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 1.0f;
    mRelease = 0.2f;
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      for (auto &op : v.operators)
        op.setSampleRate(sr);
      v.masterEnv.setSampleRate(sr);
    }
  }

  void updateSampleRate(float sr) { setSampleRate(sr); }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.masterEnv.reset();
    }
  }

  void setAlgorithm(int algo) { mAlgorithm = std::max(0, std::min(4, algo)); }

  void setFilter(float v) { mCutoff = v; }
  void setResonance(float v) { mResonance = v; }
  void setUseEnvelope(bool v) { mUseEnvelope = v; }

  void setCarrierMask(int mask) { mCarrierMask = mask; }
  void setIgnoreNoteFrequency(bool ignore) { mIgnoreNoteFrequency = ignore; }

  void setFrequency(float freq, float sampleRate) {
    mFrequency = freq;
    mSampleRate = sampleRate;
  }

  void setOpRatio(int op, float ratio) {
    if (op >= 0 && op < 6)
      mOpRatios[op] = ratio;
  }
  void setOpLevel(int op, float level) {
    if (op >= 0 && op < 6)
      mOpLevels[op] = level;
  }
  float getOpLevel(int op) const {
    if (op >= 0 && op < 6)
      return mOpLevels[op];
    return 0.0f;
  }
  void setOpADSR(int op, float a, float d, float s, float r) {
    if (op >= 0 && op < 6) {
      mOpAttack[op] = a;
      mOpDecay[op] = d;
      mOpSustain[op] = s;
      mOpRelease[op] = r;
    }
  }
  void setFeedback(float fb) { mFeedback = fb; }
  void setPitchSweep(float sweep) { mPitchSweepAmount = sweep; }

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

    float baseFreq = mIgnoreNoteFrequency
                         ? mFrequency
                         : 440.0f * powf(2.0f, (note - 69) / 12.0f);
    v.frequency = baseFreq;

    for (int i = 0; i < 6; ++i) {
      v.operators[i].setSampleRate(mSampleRate);
      v.operators[i].setFrequency(baseFreq, mOpRatios[i], mSampleRate);
      v.operators[i].setADSR(mOpAttack[i], mOpDecay[i], mOpSustain[i],
                             mOpRelease[i]);
      v.operators[i].setUseEnvelope(mUseEnvelope);
      if (mActiveMask & (1 << i))
        v.operators[i].trigger();
    }
    v.masterEnv.setSampleRate(mSampleRate);
    v.masterEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.masterEnv.trigger();

    v.svf.setParams(1000.0f, 0.7f, mSampleRate);
    v.pitchEnv = 1.0f;
    // Faster decay for drums, slower for others
    v.pitchEnvDecay = mIgnoreNoteFrequency ? 0.005f : 0.001f;
  }

  void releaseNote(int note) {
    for (auto &v : mVoices)
      if (v.active && v.note == note) {
        for (auto &op : v.operators)
          op.release();
        v.masterEnv.release();
      }
  }

  void setParameter(int id, float value) {
    if (id == 151)
      mCutoff = value;
    else if (id == 152)
      mResonance = value;
    else if (id == 150)
      setAlgorithm((int)(value * 4.99f));
    else if (id == 153)
      mCarrierMask = (int)value;
    else if (id == 154)
      mFeedback = value;
    else if (id == 155)
      mActiveMask = (int)value;
    else if (id == 156)
      mFilterMode = (int)value;
    else if (id == 157)
      mBrightness = value * 2.0f;
    else if (id == 158)
      mDetune = value;
    else if (id == 159)
      mFeedbackDrive = value;
    else if (id == 100)
      mAttack = value;
    else if (id == 101)
      mDecay = value;
    else if (id == 102)
      mSustain = value;
    else if (id == 103)
      mRelease = value;
    else if (id >= 160 && id <= 195) {
      int opIdx = (id - 160) / 6;
      int subId = (id - 160) % 6;
      if (opIdx < 6) {
        if (subId == 0)
          mOpLevels[opIdx] = value;
        else if (subId == 1)
          mOpAttack[opIdx] = value;
        else if (subId == 2)
          mOpDecay[opIdx] = value;
        else if (subId == 3)
          mOpSustain[opIdx] = value;
        else if (subId == 4)
          mOpRelease[opIdx] = value;
        else if (subId == 5)
          mOpRatios[opIdx] = value * 16.0f;
      }
    }
  }

  void loadPreset(int presetId) {
    resetToDefaults();

    // Default Envelope (Safe Start)
    mAttack = 0.01f;
    mDecay = 0.5f;
    mSustain = 0.8f;
    mRelease = 0.4f;
    mBrightness = 0.5f;

    switch (presetId) {
    case 0:            // Brass
    case 1:            // Strings (Soft)
      setAlgorithm(1); // 2 Branches
      mCarrierMask = (1 << 0) | (1 << 3);
      mOpRatios[0] = 1.0f;
      mOpLevels[0] = 0.8f;
      mOpRatios[1] = 1.0f;
      mOpLevels[1] = 0.4f;
      mOpRatios[3] = 1.0f;
      mOpLevels[3] = 0.8f;
      mOpRatios[4] = 1.005f;
      mOpLevels[4] = 0.3f; // Slight detune
      if (presetId == 0) {
        mAttack = 0.05f;
        mBrightness = 0.7f;
      } else {
        mAttack = 0.2f;
        mRelease = 0.8f;
      }
      break;

    case 2:            // Orchestra / Ensemble
      setAlgorithm(2); // Parallel
      mCarrierMask = 63;
      for (int i = 0; i < 6; ++i) {
        mOpLevels[i] = 0.25f;
        mOpRatios[i] = 1.0f + (i * 0.002f); // Detuned ensemble
      }
      mAttack = 0.15f;
      mRelease = 0.6f;
      break;

    case 3:            // Piano
    case 4:            // E. Piano
      setAlgorithm(3); // 3 Pairs
      mCarrierMask = (1 << 0) | (1 << 2) | (1 << 4);
      mOpRatios[0] = 1.0f;
      mOpLevels[0] = 0.8f;
      mOpRatios[1] = 1.0f;
      mOpLevels[1] = 0.5f;
      mOpRatios[2] = 1.0f;
      mOpLevels[2] = 0.6f;
      mOpRatios[3] = 14.0f;
      mOpLevels[3] = 0.2f; // Tine
      mOpRatios[4] = 1.0f;
      mOpLevels[4] = 0.4f;
      mOpRatios[5] = 1.0f;
      mOpLevels[5] = 0.1f;
      mAttack = 0.001f;
      mDecay = 0.6f;
      mSustain = 0.0f;
      if (presetId == 4) {
        mSustain = 0.3f;
        mBrightness = 0.6f;
      }
      break;

    case 6:            // Bass
    case 7:            // Organ
      setAlgorithm(0); // Serial
      mCarrierMask = 1;
      mOpRatios[0] = 1.0f;
      mOpLevels[0] = 0.9f;
      mOpRatios[1] = 0.5f;
      mOpLevels[1] = 0.7f; // Sub
      mOpRatios[2] = 1.0f;
      mOpLevels[2] = 0.4f;
      mOpRatios[3] = 2.0f;
      mOpLevels[3] = 0.2f;
      if (presetId == 6) {
        mDecay = 0.3f;
        mSustain = 0.0f;
        mBrightness = 0.4f;
      } else {
        mSustain = 1.0f;
        mBrightness = 0.6f;
        mOpRatios[1] = 2.0f;
        mOpRatios[2] = 3.0f;
      }
      break;

    case 11:           // Vibe
    case 12:           // Marimba
    case 21:           // Xylophone
      setAlgorithm(2); // Parallel
      mCarrierMask = 63;
      for (int i = 0; i < 5; ++i) {
        mOpLevels[i] = 0.5f / (float)(i + 1);
        mOpRatios[i] = (i == 0) ? 1.0f : (float)(i * 3 + 1.2f);
      }
      mAttack = 0.001f;
      mDecay = 0.7f;
      mSustain = 0.0f;
      if (presetId == 12)
        mDecay = 0.4f;
      if (presetId == 21)
        mDecay = 0.2f;
      break;

    case 14:           // Flute
    case 18:           // Calliope
    case 19:           // Oboe
      setAlgorithm(1); // 2 Branches
      mCarrierMask = (1 << 0) | (1 << 3);
      mOpRatios[0] = 1.0f;
      mOpLevels[0] = 0.8f;
      mOpRatios[1] = 2.0f;
      mOpLevels[1] = 0.3f;
      mOpRatios[3] = 1.0f;
      mOpLevels[3] = 0.7f;
      mOpRatios[4] = (presetId == 14) ? 3.0f : 1.5f;
      mOpLevels[4] = 0.2f;
      mAttack = 0.08f;
      mRelease = 0.3f;
      mDecay = 1.0f;
      break;

    case 15:           // Tubular Bells
    case 22:           // Church Bells
      setAlgorithm(2); // Parallel
      mCarrierMask = 63;
      mOpRatios[0] = 1.0f;
      mOpLevels[0] = 0.7f;
      mOpRatios[1] = 2.76f;
      mOpLevels[1] = 0.5f;
      mOpRatios[2] = 5.4f;
      mOpLevels[2] = 0.3f;
      mOpRatios[3] = 8.93f;
      mOpLevels[3] = 0.2f;
      mAttack = 0.001f;
      mDecay = 1.5f;
      mSustain = 0.0f;
      mRelease = 1.5f;
      break;

    case 23:           // Synth Lead
      setAlgorithm(0); // Serial
      mCarrierMask = 1;
      mOpRatios[0] = 1.0f;
      mOpLevels[0] = 0.8f;
      mOpRatios[1] = 1.0f;
      mOpLevels[1] = 0.6f;
      mOpRatios[2] = 2.01f;
      mOpLevels[2] = 0.5f;
      mOpRatios[3] = 3.99f;
      mOpLevels[3] = 0.4f;
      mFeedback = 0.6f;
      mBrightness = 0.7f;
      break;

    default:
      // Generic Sine / FM Start
      setAlgorithm(1);
      mCarrierMask = 1;
      mOpRatios[0] = 1.0f;
      mOpLevels[0] = 0.8f;
      mOpRatios[1] = 1.0f;
      mOpLevels[1] = 0.2f;
      break;
    }
  }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;

    for (auto &v : mVoices) {
      if (!v.active)
        continue;
      float mEnv = v.masterEnv.nextValue();
      if (mEnv < 0.0001f && !v.masterEnv.isActive()) {
        v.active = false;
        continue;
      }
      activeCount++;

      float velModScale = 1.0f - (0.6f * (1.0f - v.amplitude));
      float fbSignal = (v.op5FeedbackHistory + v.lastOp5Out) * 0.5f;
      // Soft-clip feedback to prevent runaway noise
      fbSignal = fast_tanh(fbSignal * (1.0f + mFeedbackDrive * 3.0f));
      float fbIn = fbSignal * mFeedback;
      float modScale = mBrightness;

      // Pitch Sweep Logic
      float pitchMod = 1.0f + (v.pitchEnv * mPitchSweepAmount);
      v.pitchEnv *= (1.0f - v.pitchEnvDecay);
      if (v.pitchEnv < 0.0001f)
        v.pitchEnv = 0.0f;

      float o[6];
      if (mAlgorithm == 0) { // Serial
        o[5] = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o[4] =
            v.operators[4].nextSample(o[5] * modScale, pitchMod) * velModScale;
        o[3] =
            v.operators[3].nextSample(o[4] * modScale, pitchMod) * velModScale;
        o[2] =
            v.operators[2].nextSample(o[3] * modScale, pitchMod) * velModScale;
        o[1] =
            v.operators[1].nextSample(o[2] * modScale, pitchMod) * velModScale;
        o[0] = v.operators[0].nextSample(o[1] * modScale, pitchMod);
      } else if (mAlgorithm == 1) { // 2 Branches
        o[5] = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o[4] =
            v.operators[4].nextSample(o[5] * modScale, pitchMod) * velModScale;
        o[3] =
            v.operators[3].nextSample(o[4] * modScale, pitchMod) * velModScale;
        o[2] = v.operators[2].nextSample(fbIn, pitchMod) * velModScale;
        o[1] =
            v.operators[1].nextSample(o[2] * modScale, pitchMod) * velModScale;
        o[0] = v.operators[0].nextSample(o[1] * modScale, pitchMod);
      } else if (mAlgorithm == 2) { // Parallel
        for (int i = 0; i < 6; ++i)
          o[i] = v.operators[i].nextSample(fbIn, pitchMod) * velModScale;
      } else { // Branching
        o[5] = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o[4] =
            v.operators[4].nextSample(o[5] * modScale, pitchMod) * velModScale;
        o[3] =
            v.operators[3].nextSample(o[4] * modScale, pitchMod) * velModScale;
        o[2] =
            v.operators[2].nextSample(o[5] * modScale, pitchMod) * velModScale;
        o[1] =
            v.operators[1].nextSample(o[2] * modScale, pitchMod) * velModScale;
        o[0] = v.operators[0].nextSample(o[1] * modScale, pitchMod);
      }

      float out = 0.0f;
      for (int i = 0; i < 6; ++i)
        if (mCarrierMask & (1 << i))
          out += o[i] * mOpLevels[i];

      v.op5FeedbackHistory = v.lastOp5Out;
      v.lastOp5Out = o[5];

      float cutoffNormalized = std::max(0.001f, std::min(0.999f, mCutoff));
      float freq = 20.0f * powf(900.0f, cutoffNormalized);
      v.svf.setParams(freq, 0.7f + mResonance * 4.0f, mSampleRate);
      float filtered =
          v.svf.process(out * v.amplitude * mEnv, (TSvf::Type)mFilterMode);
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
        mFeedback = 0.0f, mFeedbackDrive = 0.0f;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 1.0f, mRelease = 0.2f;
  int mAlgorithm = 0, mCarrierMask = 1, mActiveMask = 63, mFilterMode = 0;
  float mSampleRate = 44100.0f, mFrequency = 440.0f;
  float mPitchSweepAmount = 0.0f;
  bool mUseEnvelope = true, mIgnoreNoteFrequency = false;
};

#endif
