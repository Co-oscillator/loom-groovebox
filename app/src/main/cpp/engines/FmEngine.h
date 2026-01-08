#ifndef FM_ENGINE_H
#define FM_ENGINE_H

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
    float svf_L = 0, svf_B = 0, svf_H = 0;

    Voice() { operators.resize(6); }

    void reset() {
      active = false;
      note = -1;
      for (auto &op : operators)
        op.reset();
      lastOp5Out = 0.0f;
      op5FeedbackHistory = 0.0f;
      svf_L = svf_B = svf_H = 0.0f;
      pitchEnv = 0.0f;
    }
    float pitchEnv = 0.0f;
    float pitchEnvDecay = 0.001f;
  };

  bool mIgnoreNoteFrequency = false;
  void setIgnoreNoteFrequency(bool ignore) { mIgnoreNoteFrequency = ignore; }

  FmEngine() {
    mVoices.resize(6);
    for (auto &v : mVoices)
      v.reset();

    mAlgorithm = 0;
    mActiveMask = 0x3F;
    mCarrierMask = 0x01;

    mOpLevels.assign(6, 0.0f);
    mOpLevels[0] = 0.8f;
    mOpLevels[1] = 0.5f;
    mOpRatios.assign(6, 1.0f);
    mOpRatios[1] = 3.5f;

    mOpAttack.assign(6, 0.001f);
    mOpDecay.assign(6, 0.3f);
    mOpSustain.assign(6, 0.0f);
    mOpRelease.assign(6, 0.5f);

    // Initial operator setup for all voices
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
      for (auto &op : v.operators) {
        op.reset();
      }
    }
  }

  void setAlgorithm(int alg) {
    mAlgorithm = alg;
    if (alg == 0) {
      mCarrierMask = (1 << 0);
      mActiveMask = 0x3F;
    } else if (alg == 1) {
      mCarrierMask = (1 << 0) | (1 << 3);
      mActiveMask = 0x3F;
    } else if (alg == 2) {
      mCarrierMask = 0x3F;
      mActiveMask = 0x3F;
    } else if (alg == 3) {
      mCarrierMask = (1 << 0);
      mActiveMask = 0x3F;
    }
  }

  void loadPreset(int presetId) {
    mCutoff = 1.0f;
    mResonance = 0.0f;
    mKeytrack = 0.0f;
    mVelSens = 0.6f;
    mActiveMask = 63;
    mCarrierMask = 1;
    mBrightness = 1.0f;
    mDetune = 0.0f;
    mFeedbackDrive = 0.0f;

    auto op = [&](int i, float l, float r, float a, float d, float s,
                  float rel) {
      setOpLevel(i, l);
      setOpRatio(i, r);
      setOpADSR(i, a, d, s, rel);
    };

    switch (presetId) {
    case 0:
      setAlgorithm(3);
      mFeedback = 0.15f;
      mBrightness = 1.2f;
      op(0, 1.0f, 1.00f, 0.05f, 0.2f, 0.7f, 0.3f);
      op(1, 0.8f, 1.00f, 0.04f, 0.2f, 0.6f, 0.3f);
      op(4, 0.6f, 1.00f, 0.03f, 0.3f, 0.0f, 0.2f);
      op(5, 0.7f, 2.00f, 0.02f, 0.2f, 0.0f, 0.2f);
      break;
    case 1:
      setAlgorithm(2);
      mDetune = 0.3f;
      mBrightness = 0.8f;
      op(0, 0.9f, 1.00f, 0.5f, 0.5f, 0.8f, 1.2f);
      op(2, 0.9f, 1.00f, 0.6f, 0.6f, 0.8f, 1.2f);
      op(3, 0.3f, 2.00f, 0.5f, 0.3f, 0.0f, 1.0f);
      op(4, 0.9f, 1.00f, 0.4f, 0.4f, 0.8f, 1.2f);
      op(5, 0.2f, 3.00f, 0.1f, 1.0f, 0.0f, 1.0f);
      break;
    case 4:
      setAlgorithm(0);
      mFeedback = 0.2f;
      op(0, 1.0f, 1.00f, 0.01f, 2.0f, 0.0f, 0.5f);
      op(1, 0.6f, 1.00f, 0.01f, 1.5f, 0.0f, 0.4f);
      op(4, 0.7f, 14.0f, 0.005f, 0.1f, 0.0f, 0.2f);
      op(5, 0.5f, 1.00f, 0.005f, 0.5f, 0.0f, 0.3f);
      break;
    case 6:
      setAlgorithm(3);
      mFeedback = 0.3f;
      mFeedbackDrive = 0.4f;
      op(0, 1.0f, 0.50f, 0.01f, 0.4f, 0.6f, 0.3f);
      op(1, 0.8f, 1.00f, 0.01f, 0.2f, 0.0f, 0.2f);
      op(4, 0.7f, 1.50f, 0.005f, 0.1f, 0.0f, 0.1f);
      op(5, 0.6f, 1.00f, 0.01f, 0.3f, 0.0f, 0.2f);
      break;
    case 7:
      setAlgorithm(2);
      mCutoff = 1.0f;
      op(0, 1.0f, 0.50f, 0.05f, 0.0f, 1.0f, 0.05f);
      op(2, 0.8f, 1.00f, 0.05f, 0.0f, 1.0f, 0.05f);
      op(4, 0.6f, 1.50f, 0.05f, 0.0f, 1.0f, 0.05f);
      op(5, 0.5f, 2.00f, 0.05f, 0.0f, 1.0f, 0.05f);
      break;
    }

    for (auto &v : mVoices) {
      if (v.active) {
        for (int i = 0; i < 6; ++i) {
          v.operators[i].setLevel(mOpLevels[i]);
          v.operators[i].setADSR(mOpAttack[i], mOpDecay[i], mOpSustain[i],
                                 mOpRelease[i]);
          v.operators[i].setFrequency(v.frequency, mOpRatios[i], mSampleRate);
        }
      }
    }
  }

  void setFrequency(float freq, float sampleRate) {
    mFrequency = freq;
    mSampleRate = sampleRate;
    // Propagate the new sample rate to all active operators
    for (auto &v : mVoices) {
      if (v.active) {
        for (int i = 0; i < 6; ++i) {
          v.operators[i].setFrequency(v.frequency, mOpRatios[i], mSampleRate);
        }
      }
    }
  }

  void triggerNote(int note, int velocity) {
    int idx = -1;
    for (int i = 0; i < 6; ++i) {
      if (!mVoices[i].active) {
        idx = i;
        break;
      }
    }
    if (idx == -1)
      idx = 0;
    Voice &v = mVoices[idx];
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    v.frequency = mIgnoreNoteFrequency
                      ? mFrequency
                      : 440.0f * powf(2.0f, (note - 69) / 12.0f);

    for (int i = 0; i < 6; ++i) {
      float ratio = mOpRatios[i] + (i * mDetune * 0.05f);
      v.operators[i].setFrequency(v.frequency, ratio, mSampleRate);
      v.operators[i].setLevel(mOpLevels[i]);
      v.operators[i].setADSR(mOpAttack[i], mOpDecay[i], mOpSustain[i],
                             mOpRelease[i]);
      v.operators[i].setUseEnvelope(mUseEnvelope);
      if (mActiveMask & (1 << i))
        v.operators[i].trigger();
    }
    v.svf_L = v.svf_B = v.svf_H = 0.0f;
    v.pitchEnv = 1.0f;
    v.pitchEnvDecay = 0.005f;
  }

  void releaseNote(int note) {
    for (auto &v : mVoices) {
      if (v.active && v.note == note) {
        for (auto &op : v.operators)
          op.release();
      }
    }
  }

  void setFilter(float f) { mCutoff = f; }
  void setResonance(float r) { mResonance = r; }
  void setKeytrack(float k) { mKeytrack = k; }
  void setFeedback(float v) { mFeedback = v; }
  void setUseEnvelope(bool use) {
    mUseEnvelope = use;
    for (auto &v : mVoices)
      if (v.active)
        for (auto &op : v.operators)
          op.setUseEnvelope(use);
  }

  void setCarrierMask(int mask) { mCarrierMask = mask; }
  void setActiveMask(int mask) { mActiveMask = mask; }
  void setPitchSweep(float sweep) {
    // Basic pitch sweep implementation for drums
    for (auto &v : mVoices) {
      v.pitchEnv = sweep;
      v.pitchEnvDecay = 0.05f;
    }
  }

  void setOpLevel(int i, float v) {
    if (i >= 0 && i < 6) {
      mOpLevels[i] = v;
      for (auto &voice : mVoices)
        if (voice.active)
          voice.operators[i].setLevel(v);
    }
  }
  void setOpADSR(int i, float a, float d, float s, float r) {
    if (i >= 0 && i < 6) {
      mOpAttack[i] = a;
      mOpDecay[i] = d;
      mOpSustain[i] = s;
      mOpRelease[i] = r;
      for (auto &voice : mVoices)
        if (voice.active)
          voice.operators[i].setADSR(a, d, s, r);
    }
  }
  void setOpRatio(int i, float ratio) {
    if (i >= 0 && i < 6) {
      mOpRatios[i] = ratio;
      for (auto &voice : mVoices)
        if (voice.active)
          voice.operators[i].setFrequency(voice.frequency, mOpRatios[i],
                                          mSampleRate);
    }
  }

  void setParameter(int id, float value) {
    if (id == 0)
      setAlgorithm((int)(value * 8));
    else if (id == 1)
      setFilter(value);
    else if (id == 2)
      setResonance(value);
    else if (id == 3)
      mFeedback = value * value;
    else if (id == 8)
      mKeytrack = value;
    else if (id == 4)
      mVelSens = value;
    else if (id == 5)
      mBrightness = value * 2.0f;
    else if (id == 6) {
      mDetune = value;
      for (auto &v : mVoices)
        if (v.active)
          for (int i = 0; i < 6; ++i)
            v.operators[i].setFrequency(
                v.frequency, mOpRatios[i] + (i * mDetune * 0.05f), mSampleRate);
    } else if (id == 7)
      mFeedbackDrive = value;
    else if (id >= 10 && id < 70) {
      int opIdx = (id - 10) / 10;
      int sub = (id - 10) % 10;
      if (sub == 0)
        setOpLevel(opIdx, value * value);
      else if (sub == 1)
        setOpADSR(opIdx, 0.001f + (value * value * value), mOpDecay[opIdx],
                  mOpSustain[opIdx], mOpRelease[opIdx]);
      else if (sub == 2)
        setOpADSR(opIdx, mOpAttack[opIdx],
                  0.001f + (value * value * value * 5.0f), mOpSustain[opIdx],
                  mOpRelease[opIdx]);
      else if (sub == 3)
        setOpADSR(opIdx, mOpAttack[opIdx], mOpDecay[opIdx], value,
                  mOpRelease[opIdx]);
      else if (sub == 4)
        setOpADSR(opIdx, mOpAttack[opIdx], mOpDecay[opIdx], mOpSustain[opIdx],
                  0.001f + (value * value * value * 5.0f));
      else if (sub == 5)
        setOpRatio(opIdx, value * 16.0f);
    }
  }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;
    for (auto &v : mVoices) {
      if (!v.active)
        continue;
      bool opActive = false;
      for (auto &op : v.operators)
        if (op.isActive())
          opActive = true;
      if (!opActive) {
        v.active = false;
        continue;
      }
      activeCount++;
      float fbSignal = (v.op5FeedbackHistory + v.lastOp5Out) * 0.5f;
      if (mFeedbackDrive > 0.0f)
        fbSignal = std::tanh(fbSignal * (1.0f + mFeedbackDrive * 3.0f));
      float fbIn = fbSignal * mFeedback;
      float pitchMod = 1.0f + (v.pitchEnv * 0.0f);
      float o0, o1, o2, o3, o4, o5;
      float out = 0.0f;
      switch (mAlgorithm) {
      case 2:
        o5 = v.operators[5].nextSample(fbIn, pitchMod);
        o4 = v.operators[4].nextSample(0.0f, pitchMod);
        o3 = v.operators[3].nextSample(0.0f, pitchMod);
        o2 = v.operators[2].nextSample(0.0f, pitchMod);
        o1 = v.operators[1].nextSample(0.0f, pitchMod);
        o0 = v.operators[0].nextSample(0.0f, pitchMod);
        out = (o5 + o4 + o3 + o2 + o1 + o0) * 0.166f;
        break;
      case 3:
        o5 = v.operators[5].nextSample(fbIn, pitchMod);
        o4 = v.operators[4].nextSample(o5 * mBrightness, pitchMod);
        o3 = v.operators[3].nextSample(o5 * mBrightness, pitchMod);
        o2 = v.operators[2].nextSample(o5 * mBrightness, pitchMod);
        o1 = v.operators[1].nextSample(o5 * mBrightness, pitchMod);
        o0 = v.operators[0].nextSample(o5 * mBrightness, pitchMod);
        out = (o4 + o3 + o2 + o1 + o0) * 0.2f;
        break;
      default:
        o5 = v.operators[5].nextSample(fbIn, pitchMod);
        o4 = v.operators[4].nextSample(o5 * mBrightness, pitchMod);
        o3 = v.operators[3].nextSample(o4 * mBrightness, pitchMod);
        o2 = v.operators[2].nextSample(o3 * mBrightness, pitchMod);
        o1 = v.operators[1].nextSample(o2 * mBrightness, pitchMod);
        o0 = v.operators[0].nextSample(o1 * mBrightness, pitchMod);
        out = o0;
        break;
      }
      v.op5FeedbackHistory = v.lastOp5Out;
      v.lastOp5Out = o5;
      float keytrackOffset = (log2f(v.frequency / 440.0f) * mKeytrack) / 10.0f;
      float modCutoff =
          std::max(0.0f, std::min(1.0f, mCutoff + keytrackOffset));
      float targetFreq =
          20.0f + (modCutoff * modCutoff *
                   12000.0f); // Lowered max for stability in simple SVF
      float f = 2.0f * sinf(M_PI * std::min(targetFreq, mSampleRate * 0.25f) /
                            mSampleRate); // Lowered clamp for stability
      float q = 1.0f - mResonance * 0.95f;
      if (q < 0.1f)
        q = 0.1f;
      v.svf_H = (out * v.amplitude) - v.svf_L - q * v.svf_B;
      v.svf_B = f * v.svf_H + v.svf_B;
      v.svf_L = f * v.svf_B + v.svf_L;

      // Anti-explosion / Safety
      if (!std::isfinite(v.svf_L)) {
        v.svf_L = 0;
        v.svf_B = 0;
        v.svf_H = 0;
      }
      mixedOutput += std::tanh(v.svf_L) * 1.5f;
    }
    return (activeCount > 1) ? mixedOutput * 0.7f : mixedOutput;
  }

  void updateSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      for (int i = 0; i < 6; ++i) {
        v.operators[i].setFrequency(v.frequency, mOpRatios[i], sr);
      }
    }
  }
  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    return false;
  }

public: // Moved to public
  std::vector<float> mOpLevels, mOpRatios, mOpAttack, mOpDecay, mOpSustain,
      mOpRelease;

private:
  std::vector<Voice> mVoices;
  int mAlgorithm = 0, mCarrierMask = 1, mActiveMask = 63;
  float mFeedback = 0.0f, mVelSens = 0.5f, mCutoff = 1.0f, mResonance = 0.0f,
        mKeytrack = 0.0f;
  float mBrightness = 1.0f, mDetune = 0.0f, mFeedbackDrive = 0.0f,
        mFrequency = 440.0f;
  float mSampleRate = 44100.0f;
  bool mUseEnvelope = true;
};

#endif
