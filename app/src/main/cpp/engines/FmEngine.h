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
    float targetFrequency = 440.0f;
    float amplitude = 1.0f;
    FmOperator operators[6];
    TSvf svf;
    float lastOp5Out = 0.0f;
    float op5FeedbackHistory = 0.0f;
    float pitchEnv = 1.0f;
    float pitchEnvDecay = 0.001f;
    Adsr masterEnv;
    uint32_t controlCounter = 0;

    void reset() {
      active = false;
      note = -1;
      frequency = 440.0f;
      targetFrequency = 440.0f;
      for (auto &op : operators) {
        op.setUseEnvelope(true);
      }
      lastOp5Out = 0.0f;
      op5FeedbackHistory = 0.0f;
      masterEnv.reset();
      controlCounter = 0;
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
    mSampleRate = sampleRate;
    mFrequency = freq;
  }
  void setGlide(float g) { mGlide = g; }

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
  void setPitchBend(float bend) { mPitchBend = bend; }

  // Getters for UI Sync
  int getAlgorithm() const { return mAlgorithm; }
  float getCutoff() const { return mCutoff; }
  float getResonance() const { return mResonance; }
  int getCarrierMask() const { return mCarrierMask; }
  float getFeedback() const { return mFeedback; }
  int getActiveMask() const { return mActiveMask; }
  int getFilterMode() const { return mFilterMode; }
  float getBrightness() const {
    return mBrightness * 0.5f;
  } // Normalized back from *2
  float getDetune() const { return mDetune; }
  float getFeedbackDrive() const { return mFeedbackDrive; }

  // Op Getters
  float getOpRatio(int op) const {
    return (op >= 0 && op < 6) ? mOpRatios[op] : 1.0f;
  }
  float getOpAttack(int op) const {
    return (op >= 0 && op < 6) ? mOpAttack[op] : 0.0f;
  }
  float getOpDecay(int op) const {
    return (op >= 0 && op < 6) ? mOpDecay[op] : 0.0f;
  }
  float getOpSustain(int op) const {
    return (op >= 0 && op < 6) ? mOpSustain[op] : 1.0f;
  }
  float getOpRelease(int op) const {
    return (op >= 0 && op < 6) ? mOpRelease[op] : 0.0f;
  }

  // Amp Env Getters
  float getAttack() const { return mAttack; }
  float getDecay() const { return mDecay; }
  float getSustain() const { return mSustain; }
  float getRelease() const { return mRelease; }

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
    v.reset();
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;

    float baseFreq = mIgnoreNoteFrequency
                         ? mFrequency
                         : 440.0f * powf(2.0f, (note - 69) / 12.0f);
    v.targetFrequency = baseFreq;
    v.frequency = (mGlide > 0.001f) ? mLastFrequency : baseFreq;
    mLastFrequency = baseFreq;

    float startFreq = v.frequency;

    for (int i = 0; i < 6; ++i) {
      v.operators[i].setSampleRate(mSampleRate);
      v.operators[i].setFrequency(startFreq, mOpRatios[i], mSampleRate);
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
    } else if (id == 355) {
      setGlide(value);
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

      // Fix Harshness: Limit Brightness and Feedback for these delicate sounds
      mBrightness = 0.6f;
      mFeedback = 0.0f; // Flutes usually don't need feedback noise

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

    case 24: // RECORDERS
      setAlgorithm(2);
      mCarrierMask = 63;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.81f;
      mOpAttack[0] = 0.040f;
      mOpDecay[0] = 0.520f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 0.380f;
      mOpRatios[1] = 4.04f;
      mOpLevels[1] = 0.17f;
      mOpAttack[1] = 0.580f;
      mOpDecay[1] = 0.500f;
      mOpSustain[1] = 0.87f;
      mOpRelease[1] = 0.860f;
      mOpRatios[2] = 1.00f;
      mOpLevels[2] = 0.57f;
      mOpAttack[2] = 0.560f;
      mOpDecay[2] = 1.620f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 1.060f;
      mOpRatios[3] = 4.04f;
      mOpLevels[3] = 0.34f;
      mOpAttack[3] = 0.540f;
      mOpDecay[3] = 1.580f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 1.480f;
      mOpRatios[4] = 1.01f;
      mOpLevels[4] = 0.84f;
      mOpAttack[4] = 0.080f;
      mOpDecay[4] = 0.520f;
      mOpSustain[4] = 1.00f;
      mOpRelease[4] = 0.380f;
      mOpRatios[5] = 4.04f;
      mOpLevels[5] = 0.22f;
      mOpAttack[5] = 0.540f;
      mOpDecay[5] = 1.580f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 1.480f;
      mBrightness = 0.7f;
      mFeedback = 0.0f;
      break;

    case 25: // SHIMMER
      setAlgorithm(1);
      mCarrierMask = 9;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.81f;
      mOpAttack[0] = 0.040f;
      mOpDecay[0] = 0.300f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 0.860f;
      mOpRatios[1] = 5.04f;
      mOpLevels[1] = 0.57f;
      mOpAttack[1] = 0.840f;
      mOpDecay[1] = 0.840f;
      mOpSustain[1] = 1.00f;
      mOpRelease[1] = 0.940f;
      mOpRatios[2] = 1.03f;
      mOpLevels[2] = 0.31f;
      mOpAttack[2] = 0.200f;
      mOpDecay[2] = 1.100f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 0.700f;
      mOpRatios[3] = 1.00f;
      mOpLevels[3] = 0.69f;
      mOpAttack[3] = 0.540f;
      mOpDecay[3] = 0.440f;
      mOpSustain[3] = 1.00f;
      mOpRelease[3] = 0.540f;
      mOpRatios[4] = 6.05f;
      mOpLevels[4] = 0.38f;
      mOpAttack[4] = 0.960f;
      mOpDecay[4] = 0.960f;
      mOpSustain[4] = 0.90f;
      mOpRelease[4] = 0.980f;
      mOpRatios[5] = 0.50f;
      mOpLevels[5] = 0.41f;
      mOpAttack[5] = 0.320f;
      mOpDecay[5] = 1.440f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.800f;
      mBrightness = 0.8f;
      mFeedback = 0.4f;
      break;

    case 26: // FILTER SWP
      setAlgorithm(0);
      mCarrierMask = 1;
      mOpRatios[0] = 0.50f;
      mOpLevels[0] = 0.96f;
      mOpAttack[0] = 0.220f;
      mOpDecay[0] = 0.300f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 1.340f;
      mOpRatios[1] = 1.00f;
      mOpLevels[1] = 0.44f;
      mOpAttack[1] = 0.320f;
      mOpDecay[1] = 0.600f;
      mOpSustain[1] = 1.00f;
      mOpRelease[1] = 1.020f;
      mOpRatios[2] = 0.50f;
      mOpLevels[2] = 0.52f;
      mOpAttack[2] = 0.460f;
      mOpDecay[2] = 0.600f;
      mOpSustain[2] = 0.96f;
      mOpRelease[2] = 1.000f;
      mOpRatios[3] = 1.00f;
      mOpLevels[3] = 0.79f;
      mOpAttack[3] = 0.560f;
      mOpDecay[3] = 0.600f;
      mOpSustain[3] = 0.98f;
      mOpRelease[3] = 1.000f;
      mOpRatios[4] = 1.00f;
      mOpLevels[4] = 0.47f;
      mOpAttack[4] = 0.540f;
      mOpDecay[4] = 1.000f;
      mOpSustain[4] = 0.74f;
      mOpRelease[4] = 1.020f;
      mOpRatios[5] = 0.50f;
      mOpLevels[5] = 0.93f;
      mOpAttack[5] = 0.440f;
      mOpDecay[5] = 1.000f;
      mOpSustain[5] = 0.61f;
      mOpRelease[5] = 1.020f;
      mBrightness = 0.6f;
      mFeedback = 0.5f;
      break;

    case 27: // FUNKY RISE
      setAlgorithm(0);
      mCarrierMask = 1;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.94f;
      mOpAttack[0] = 0.080f;
      mOpDecay[0] = 0.280f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 1.120f;
      mOpRatios[1] = 1.00f;
      mOpLevels[1] = 0.52f;
      mOpAttack[1] = 0.660f;
      mOpDecay[1] = 1.300f;
      mOpSustain[1] = 0.56f;
      mOpRelease[1] = 1.340f;
      mOpRatios[2] = 1.00f;
      mOpLevels[2] = 0.79f;
      mOpAttack[2] = 0.660f;
      mOpDecay[2] = 0.720f;
      mOpSustain[2] = 0.46f;
      mOpRelease[2] = 1.220f;
      mOpRatios[3] = 2.01f;
      mOpLevels[3] = 0.87f;
      mOpAttack[3] = 0.660f;
      mOpDecay[3] = 1.300f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 1.480f;
      mOpRatios[4] = 1.00f;
      mOpLevels[4] = 0.69f;
      mOpAttack[4] = 0.660f;
      mOpDecay[4] = 1.300f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 1.580f;
      mOpRatios[5] = 0.50f;
      mOpLevels[5] = 0.69f;
      mOpAttack[5] = 0.660f;
      mOpDecay[5] = 0.480f;
      mOpSustain[5] = 0.92f;
      mOpRelease[5] = 1.460f;
      mBrightness = 0.8f;
      mFeedback = 0.7f;
      break;

    case 28: // REFS WHISL
      setAlgorithm(1);
      mCarrierMask = 9;
      mOpRatios[0] = 13.07f;
      mOpLevels[0] = 0.32f;
      mOpAttack[0] = 0.390f;
      mOpDecay[0] = 1.200f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 1.000f;
      mOpRatios[1] = 119.00f;
      mOpLevels[1] = 0.54f;
      mOpAttack[1] = 0.390f;
      mOpDecay[1] = 1.200f;
      mOpSustain[1] = 1.00f;
      mOpRelease[1] = 1.080f;
      mOpRatios[2] = 1.03f;
      mOpLevels[2] = 0.68f;
      mOpAttack[2] = 0.390f;
      mOpDecay[2] = 1.200f;
      mOpSustain[2] = 1.00f;
      mOpRelease[2] = 1.980f;
      mOpRatios[3] = 11.50f;
      mOpLevels[3] = 0.83f;
      mOpAttack[3] = 0.050f;
      mOpDecay[3] = 0.620f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 0.880f;
      mOpRatios[4] = 0.50f;
      mOpLevels[4] = 0.00f;
      mOpAttack[4] = 0.001f;
      mOpDecay[4] = 0.000f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 0.000f;
      mOpRatios[5] = 0.50f;
      mOpLevels[5] = 0.00f;
      mOpAttack[5] = 0.001f;
      mOpDecay[5] = 0.000f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.000f;
      mBrightness = 0.7f;
      mFeedback = 0.0f;
      break;

    case 29: // STEEL DRUM
      setAlgorithm(1);
      mCarrierMask = 9;
      mOpRatios[0] = 10.20f;
      mOpLevels[0] = 0.00f;
      mOpAttack[0] = 0.001f;
      mOpDecay[0] = 1.180f;
      mOpSustain[0] = 0.00f;
      mOpRelease[0] = 1.220f;
      mOpRatios[1] = 0.50f;
      mOpLevels[1] = 0.71f;
      mOpAttack[1] = 0.001f;
      mOpDecay[1] = 1.600f;
      mOpSustain[1] = 0.00f;
      mOpRelease[1] = 1.800f;
      mOpRatios[2] = 10.20f;
      mOpLevels[2] = 0.00f;
      mOpAttack[2] = 0.001f;
      mOpDecay[2] = 1.380f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 1.140f;
      mOpRatios[3] = 12.04f;
      mOpLevels[3] = 0.00f;
      mOpAttack[3] = 0.001f;
      mOpDecay[3] = 1.100f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 1.560f;
      mOpRatios[4] = 0.50f;
      mOpLevels[4] = 0.33f;
      mOpAttack[4] = 0.001f;
      mOpDecay[4] = 1.180f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 1.980f;
      mOpRatios[5] = 1.05f;
      mOpLevels[5] = 0.61f;
      mOpAttack[5] = 0.001f;
      mOpDecay[5] = 1.000f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 1.740f;
      mBrightness = 0.9f;
      mFeedback = 0.0f;
      break;

    case 30: // HARMONICA1
      setAlgorithm(0);
      mCarrierMask = 1;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.98f;
      mOpAttack[0] = 0.380f;
      mOpDecay[0] = 0.300f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 0.440f;
      mOpRatios[1] = 2.01f;
      mOpLevels[1] = 0.59f;
      mOpAttack[1] = 0.420f;
      mOpDecay[1] = 0.300f;
      mOpSustain[1] = 1.00f;
      mOpRelease[1] = 0.460f;
      mOpRatios[2] = 3.00f;
      mOpLevels[2] = 0.65f;
      mOpAttack[2] = 0.160f;
      mOpDecay[2] = 1.800f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 0.460f;
      mOpRatios[3] = 2.01f;
      mOpLevels[3] = 0.81f;
      mOpAttack[3] = 0.160f;
      mOpDecay[3] = 0.700f;
      mOpSustain[3] = 0.53f;
      mOpRelease[3] = 0.460f;
      mOpRatios[4] = 3.00f;
      mOpLevels[4] = 0.46f;
      mOpAttack[4] = 0.060f;
      mOpDecay[4] = 0.640f;
      mOpSustain[4] = 0.61f;
      mOpRelease[4] = 0.640f;
      mOpRatios[5] = 2.00f;
      mOpLevels[5] = 0.74f;
      mOpAttack[5] = 0.001f;
      mOpDecay[5] = 0.820f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.500f;
      mBrightness = 0.8f;
      mFeedback = 0.5f;
      break;

    case 31: // ACCORDION
      setAlgorithm(2);
      mCarrierMask = 63;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.79f;
      mOpAttack[0] = 0.440f;
      mOpDecay[0] = 0.320f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 0.660f;
      mOpRatios[1] = 1.02f;
      mOpLevels[1] = 0.79f;
      mOpAttack[1] = 0.340f;
      mOpDecay[1] = 0.320f;
      mOpSustain[1] = 1.00f;
      mOpRelease[1] = 0.580f;
      mOpRatios[2] = 1.00f;
      mOpLevels[2] = 0.79f;
      mOpAttack[2] = 0.400f;
      mOpDecay[2] = 0.360f;
      mOpSustain[2] = 1.00f;
      mOpRelease[2] = 0.740f;
      mOpRatios[3] = 2.01f;
      mOpLevels[3] = 0.78f;
      mOpAttack[3] = 0.440f;
      mOpDecay[3] = 0.400f;
      mOpSustain[3] = 1.00f;
      mOpRelease[3] = 0.500f;
      mOpRatios[4] = 1.00f;
      mOpLevels[4] = 0.83f;
      mOpAttack[4] = 0.300f;
      mOpDecay[4] = 0.500f;
      mOpSustain[4] = 1.00f;
      mOpRelease[4] = 0.560f;
      mOpRatios[5] = 1.00f;
      mOpLevels[5] = 0.71f;
      mOpAttack[5] = 0.320f;
      mOpDecay[5] = 0.840f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.600f;
      mBrightness = 0.8f;
      mFeedback = 0.7f;
      break;

    case 32: // SITAR
      setAlgorithm(1);
      mCarrierMask = 9;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.99f;
      mOpAttack[0] = 0.020f;
      mOpDecay[0] = 1.980f;
      mOpSustain[0] = 0.00f;
      mOpRelease[0] = 0.520f;
      mOpRatios[1] = 2.00f;
      mOpLevels[1] = 0.86f;
      mOpAttack[1] = 0.080f;
      mOpDecay[1] = 1.580f;
      mOpSustain[1] = 0.00f;
      mOpRelease[1] = 1.620f;
      mOpRatios[2] = 0.50f;
      mOpLevels[2] = 0.73f;
      mOpAttack[2] = 0.040f;
      mOpDecay[2] = 0.520f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 1.580f;
      mOpRatios[3] = 0.50f;
      mOpLevels[3] = 0.86f;
      mOpAttack[3] = 0.040f;
      mOpDecay[3] = 1.600f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 0.540f;
      mOpRatios[4] = 2.00f;
      mOpLevels[4] = 0.69f;
      mOpAttack[4] = 0.080f;
      mOpDecay[4] = 1.480f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 1.480f;
      mOpRatios[5] = 0.50f;
      mOpLevels[5] = 0.84f;
      mOpAttack[5] = 0.001f;
      mOpDecay[5] = 0.520f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.700f;
      mBrightness = 0.9f;
      mFeedback = 0.6f;
      break;

    case 33: // LUTE
      setAlgorithm(0);
      mCarrierMask = 1;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.99f;
      mOpAttack[0] = 0.020f;
      mOpDecay[0] = 1.040f;
      mOpSustain[0] = 0.00f;
      mOpRelease[0] = 1.100f;
      mOpRatios[1] = 1.00f;
      mOpLevels[1] = 0.77f;
      mOpAttack[1] = 0.200f;
      mOpDecay[1] = 1.500f;
      mOpSustain[1] = 0.00f;
      mOpRelease[1] = 0.760f;
      mOpRatios[2] = 1.00f;
      mOpLevels[2] = 0.58f;
      mOpAttack[2] = 0.360f;
      mOpDecay[2] = 0.740f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 0.760f;
      mOpRatios[3] = 0.50f;
      mOpLevels[3] = 0.61f;
      mOpAttack[3] = 0.440f;
      mOpDecay[3] = 0.880f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 0.760f;
      mOpRatios[4] = 14.14f;
      mOpLevels[4] = 0.00f;
      mOpAttack[4] = 0.001f;
      mOpDecay[4] = 0.000f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 0.000f;
      mOpRatios[5] = 14.14f;
      mOpLevels[5] = 0.00f;
      mOpAttack[5] = 0.001f;
      mOpDecay[5] = 0.000f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.000f;
      mBrightness = 0.8f;
      mFeedback = 0.8f;
      break;

    case 34: // BANJO
      setAlgorithm(0);
      mCarrierMask = 1;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.99f;
      mOpAttack[0] = 0.020f;
      mOpDecay[0] = 0.980f;
      mOpSustain[0] = 0.00f;
      mOpRelease[0] = 1.100f;
      mOpRatios[1] = 1.00f;
      mOpLevels[1] = 0.76f;
      mOpAttack[1] = 0.001f;
      mOpDecay[1] = 1.340f;
      mOpSustain[1] = 0.00f;
      mOpRelease[1] = 0.800f;
      mOpRatios[2] = 2.01f;
      mOpLevels[2] = 0.76f;
      mOpAttack[2] = 0.001f;
      mOpDecay[2] = 0.660f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 1.200f;
      mOpRatios[3] = 0.50f;
      mOpLevels[3] = 0.80f;
      mOpAttack[3] = 0.120f;
      mOpDecay[3] = 1.160f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 1.000f;
      mOpRatios[4] = 14.14f;
      mOpLevels[4] = 0.10f;
      mOpAttack[4] = 0.040f;
      mOpDecay[4] = 0.460f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 0.360f;
      mOpRatios[5] = 1.00f;
      mOpLevels[5] = 0.83f;
      mOpAttack[5] = 0.040f;
      mOpDecay[5] = 0.920f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.420f;
      mBrightness = 0.9f;
      mFeedback = 0.7f;
      break;

    case 35: // HARP 1
      setAlgorithm(1);
      mCarrierMask = 9;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.99f;
      mOpAttack[0] = 0.020f;
      mOpDecay[0] = 1.980f;
      mOpSustain[0] = 0.00f;
      mOpRelease[0] = 0.900f;
      mOpRatios[1] = 1.00f;
      mOpLevels[1] = 0.76f;
      mOpAttack[1] = 0.001f;
      mOpDecay[1] = 1.480f;
      mOpSustain[1] = 0.00f;
      mOpRelease[1] = 1.200f;
      mOpRatios[2] = 2.01f;
      mOpLevels[2] = 0.68f;
      mOpAttack[2] = 0.080f;
      mOpDecay[2] = 0.680f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 1.220f;
      mOpRatios[3] = 0.50f;
      mOpLevels[3] = 0.86f;
      mOpAttack[3] = 0.040f;
      mOpDecay[3] = 1.600f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 0.600f;
      mOpRatios[4] = 6.09f;
      mOpLevels[4] = 0.00f;
      mOpAttack[4] = 0.001f;
      mOpDecay[4] = 0.000f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 0.000f;
      mOpRatios[5] = 3.01f;
      mOpLevels[5] = 0.00f;
      mOpAttack[5] = 0.001f;
      mOpDecay[5] = 0.000f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.000f;
      mBrightness = 0.8f;
      mFeedback = 0.7f;
      break;

    case 36: // HARP 2
      setAlgorithm(1);
      mCarrierMask = 9;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.99f;
      mOpAttack[0] = 0.001f;
      mOpDecay[0] = 1.980f;
      mOpSustain[0] = 0.00f;
      mOpRelease[0] = 0.900f;
      mOpRatios[1] = 0.50f;
      mOpLevels[1] = 0.84f;
      mOpAttack[1] = 0.020f;
      mOpDecay[1] = 1.320f;
      mOpSustain[1] = 0.00f;
      mOpRelease[1] = 0.860f;
      mOpRatios[2] = 1.00f;
      mOpLevels[2] = 0.75f;
      mOpAttack[2] = 0.001f;
      mOpDecay[2] = 1.080f;
      mOpSustain[2] = 0.00f;
      mOpRelease[2] = 0.380f;
      mOpRatios[3] = 0.50f;
      mOpLevels[3] = 0.92f;
      mOpAttack[3] = 0.001f;
      mOpDecay[3] = 1.980f;
      mOpSustain[3] = 0.00f;
      mOpRelease[3] = 0.900f;
      mOpRatios[4] = 2.01f;
      mOpLevels[4] = 0.59f;
      mOpAttack[4] = 0.040f;
      mOpDecay[4] = 0.840f;
      mOpSustain[4] = 0.00f;
      mOpRelease[4] = 0.840f;
      mOpRatios[5] = 0.50f;
      mOpLevels[5] = 0.90f;
      mOpAttack[5] = 0.020f;
      mOpDecay[5] = 0.900f;
      mOpSustain[5] = 0.00f;
      mOpRelease[5] = 0.700f;
      mBrightness = 0.8f;
      mFeedback = 0.5f;
      break;

    case 37: // SYN-VOX
      setAlgorithm(0);
      mCarrierMask = 1;
      mOpRatios[0] = 1.00f;
      mOpLevels[0] = 0.89f;
      mOpAttack[0] = 0.400f;
      mOpDecay[0] = 1.100f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 0.940f;
      mOpRatios[1] = 1.01f;
      mOpLevels[1] = 0.61f;
      mOpAttack[1] = 0.320f;
      mOpDecay[1] = 1.980f;
      mOpSustain[1] = 1.00f;
      mOpRelease[1] = 0.860f;
      mOpRatios[2] = 2.00f;
      mOpLevels[2] = 0.57f;
      mOpAttack[2] = 0.320f;
      mOpDecay[2] = 1.980f;
      mOpSustain[2] = 1.00f;
      mOpRelease[2] = 0.800f;
      mOpRatios[3] = 3.01f;
      mOpLevels[3] = 0.52f;
      mOpAttack[3] = 0.280f;
      mOpDecay[3] = 1.980f;
      mOpSustain[3] = 1.00f;
      mOpRelease[3] = 0.740f;
      mOpRatios[4] = 4.00f;
      mOpLevels[4] = 0.46f;
      mOpAttack[4] = 0.260f;
      mOpDecay[4] = 1.980f;
      mOpSustain[4] = 1.00f;
      mOpRelease[4] = 0.700f;
      mOpRatios[5] = 5.01f;
      mOpLevels[5] = 0.46f;
      mOpAttack[5] = 0.260f;
      mOpDecay[5] = 1.140f;
      mOpSustain[5] = 1.00f;
      mOpRelease[5] = 0.600f;
      mBrightness = 0.7f;
      mFeedback = 0.5f;
      break;

    case 38: // SYN-ORCH
      setAlgorithm(1);
      mCarrierMask = 9;
      mOpRatios[0] = 0.50f;
      mOpLevels[0] = 0.99f;
      mOpAttack[0] = 0.160f;
      mOpDecay[0] = 0.540f;
      mOpSustain[0] = 1.00f;
      mOpRelease[0] = 0.980f;
      mOpRatios[1] = 0.50f;
      mOpLevels[1] = 0.60f;
      mOpAttack[1] = 0.520f;
      mOpDecay[1] = 0.520f;
      mOpSustain[1] = 1.00f;
      mOpRelease[1] = 1.080f;
      mOpRatios[2] = 0.50f;
      mOpLevels[2] = 0.64f;
      mOpAttack[2] = 0.160f;
      mOpDecay[2] = 0.600f;
      mOpSustain[2] = 1.00f;
      mOpRelease[2] = 1.100f;
      mOpRatios[3] = 1.00f;
      mOpLevels[3] = 0.65f;
      mOpAttack[3] = 0.460f;
      mOpDecay[3] = 0.440f;
      mOpSustain[3] = 1.00f;
      mOpRelease[3] = 0.880f;
      mOpRatios[4] = 1.01f;
      mOpLevels[4] = 0.70f;
      mOpAttack[4] = 0.820f;
      mOpDecay[4] = 0.800f;
      mOpSustain[4] = 1.00f;
      mOpRelease[4] = 1.000f;
      mOpRatios[5] = 1.00f;
      mOpLevels[5] = 0.81f;
      mOpAttack[5] = 0.040f;
      mOpDecay[5] = 0.420f;
      mOpSustain[5] = 1.00f;
      mOpRelease[5] = 0.740f;
      mBrightness = 0.8f;
      mFeedback = 0.6f;
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

      if (mGlide > 0.001f) {
        float glideTimeSamples = mGlide * mSampleRate * 0.5f;
        float glideAlpha = 1.0f / (glideTimeSamples + 1.0f);
        v.frequency += (v.targetFrequency - v.frequency) * glideAlpha;
        for (int i = 0; i < 6; ++i) {
          v.operators[i].setFrequency(v.frequency, mOpRatios[i], mSampleRate);
        }
      } else {
        v.frequency = v.targetFrequency;
        // Optimization: periodically update op frequencies if ratios changed
        if (v.controlCounter % 256 == 0) {
          for (int i = 0; i < 6; ++i)
            v.operators[i].setFrequency(v.frequency, mOpRatios[i], mSampleRate);
        }
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
        o[5] = v.operators[5].nextSample(fbIn, pitchMod) * velModScale *
               mOpLevels[5];
        o[4] = v.operators[4].nextSample(o[5] * modScale, pitchMod) *
               velModScale * mOpLevels[4];
        o[3] = v.operators[3].nextSample(o[4] * modScale, pitchMod) *
               velModScale * mOpLevels[3];
        o[2] = v.operators[2].nextSample(o[3] * modScale, pitchMod) *
               velModScale * mOpLevels[2];
        o[1] = v.operators[1].nextSample(o[2] * modScale, pitchMod) *
               velModScale * mOpLevels[1];
        o[0] =
            v.operators[0].nextSample(o[1] * modScale, pitchMod) * mOpLevels[0];
      } else if (mAlgorithm == 1) { // 2 Branches
        o[5] = v.operators[5].nextSample(fbIn, pitchMod) * velModScale *
               mOpLevels[5];
        o[4] = v.operators[4].nextSample(o[5] * modScale, pitchMod) *
               velModScale * mOpLevels[4];
        o[3] = v.operators[3].nextSample(o[4] * modScale, pitchMod) *
               velModScale * mOpLevels[3];
        o[2] = v.operators[2].nextSample(fbIn, pitchMod) * velModScale *
               mOpLevels[2];
        o[1] = v.operators[1].nextSample(o[2] * modScale, pitchMod) *
               velModScale * mOpLevels[1];
        o[0] =
            v.operators[0].nextSample(o[1] * modScale, pitchMod) * mOpLevels[0];
      } else if (mAlgorithm == 2) { // Parallel
        for (int i = 0; i < 6; ++i)
          o[i] = v.operators[i].nextSample(fbIn, pitchMod) * velModScale *
                 mOpLevels[i];
      } else { // Branching
        o[5] = v.operators[5].nextSample(fbIn, pitchMod) * velModScale *
               mOpLevels[5];
        o[4] = v.operators[4].nextSample(o[5] * modScale, pitchMod) *
               velModScale * mOpLevels[4];
        o[3] = v.operators[3].nextSample(o[4] * modScale, pitchMod) *
               velModScale * mOpLevels[3];
        o[2] = v.operators[2].nextSample(o[5] * modScale, pitchMod) *
               velModScale * mOpLevels[2];
        o[1] = v.operators[1].nextSample(o[2] * modScale, pitchMod) *
               velModScale * mOpLevels[1];
        o[0] =
            v.operators[0].nextSample(o[1] * modScale, pitchMod) * mOpLevels[0];
      }

      float out = 0.0f;
      for (int i = 0; i < 6; ++i)
        if (mCarrierMask & (1 << i))
          out += o[i];

      v.op5FeedbackHistory = v.lastOp5Out;
      v.lastOp5Out = o[5];

      float cutoffNormalized = std::max(0.001f, std::min(0.999f, mCutoff));
      if (v.controlCounter++ % 16 == 0) {
        float freq = 20.0f * powf(900.0f, cutoffNormalized);
        v.svf.setParams(freq, 0.7f + mResonance * 4.0f, mSampleRate);
      }
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

  float getEnvelopeValue() const {
    float maxEnv = 0.0f;
    for (const auto &v : mVoices) {
      if (v.active) {
        maxEnv = std::max(maxEnv, v.masterEnv.getValue());
      }
    }
    return maxEnv;
  }

private:
  std::vector<Voice> mVoices;
  std::vector<float> mOpLevels, mOpRatios, mOpAttack, mOpDecay, mOpSustain,
      mOpRelease;
  float mCutoff = 0.5f, mResonance = 0.0f, mBrightness = 1.0f, mDetune = 0.0f,
        mFeedback = 0.0f, mFeedbackDrive = 0.0f;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 1.0f, mRelease = 0.2f;
  int mAlgorithm = 0, mCarrierMask = 1, mActiveMask = 63, mFilterMode = 0;
  float mSampleRate = 48000.0f, mFrequency = 440.0f, mLastFrequency = 440.0f,
        mGlide = 0.0f;
  float mPitchSweepAmount = 0.0f;
  float mPitchBend = 0.0f;
  bool mUseEnvelope = true, mIgnoreNoteFrequency = false;
};

#endif
