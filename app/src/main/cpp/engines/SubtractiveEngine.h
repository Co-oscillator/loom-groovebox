#ifndef SUBTRACTIVE_ENGINE_H
#define SUBTRACTIVE_ENGINE_H

#include "../Utils.h"
#include "Adsr.h"
#include "Oscillator.h"
#include <android/log.h>
#include <cmath>
#include <memory>
#include <oboe/Oboe.h>
#include <vector>

class SubtractiveEngine {
public:
  struct Voice {
    bool active = false;
    bool isNoteHeld = false;
    int note = -1;
    float frequency = 440.0f;
    float amplitude = 1.0f;
    Adsr ampEnv;
    Adsr filterEnv;
    std::vector<Oscillator> oscillators;

    // SVF state
    TSvf svf;
    float currentFilterEnvVal = 0.0f;
    uint32_t controlCounter = 0;

    Voice() { oscillators.resize(4); }

    void reset() {
      active = false;
      isNoteHeld = false;
      note = -1;
      ampEnv.reset();
      filterEnv.reset();
      svf.setParams(1000.0f, 0.7f, 44100.0f);
    }
  };

  SubtractiveEngine() {
    mVoices.resize(16);
    for (auto &v : mVoices)
      v.reset();
    mOscVolumes.assign(4, 0.0f);
    mOscVolumes[0] = 0.6f;
    mOscVolumes[1] = 0.4f;
    mOscWaveforms.assign(4, Waveform::Sine);
    mOscWaveforms[0] = Waveform::Sawtooth;
    mOscWaveforms[1] = Waveform::Square;
    resetToDefaults();
  }

  void resetToDefaults() {
    mCutoff = 0.45f;
    mResonance = 0.0f;
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 0.8f;
    mRelease = 0.5f;
    mF_Atk = 0.01f;
    mF_Dcy = 0.1f;
    mF_Sus = 0.0f;
    mF_Rel = 0.5f;
    mF_Amt = 0.0f;
    mDetune = 0.0f;
    mNoiseLevel = 0.0f;
    mOscSync = false;
    mRingMod = false;
    mFilterMode = 0;
    mOscPitch[0] = 1.0f;
    mOscPitch[1] = 1.0f;
    mOscPitch[2] = 0.5f;
    mOscPitch[3] = 1.0f;
    mOscVolumes[0] = 0.6f;
    mOscVolumes[1] = 0.4f;
    mOscVolumes[2] = 0.0f;
    mOscVolumes[3] = 0.0f;
    mOscDrive[0] = mOscDrive[1] = mOscDrive[2] = mOscDrive[3] = 1.0f;
    mOscFold[0] = mOscFold[1] = mOscFold[2] = mOscFold[3] = 0.0f;
    mOscPW[0] = mOscPW[1] = mOscPW[2] = mOscPW[3] = 0.5f;
    mOscWaveforms[0] = Waveform::Sawtooth;
    mOscWaveforms[1] = Waveform::Square;
    updateLiveEnvelopes();
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      v.ampEnv.setSampleRate(sr);
      v.filterEnv.setSampleRate(sr);
    }
  }

  void setFrequency(float freq, float sampleRate) {
    mSampleRate = sampleRate;
    mFrequency = freq;
  }
  void setIgnoreNoteFrequency(bool ignore) { mIgnoreNoteFrequency = ignore; }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.isNoteHeld = false;
      v.ampEnv.reset();
      v.filterEnv.reset();
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
    v.isNoteHeld = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    v.frequency = mIgnoreNoteFrequency
                      ? mFrequency
                      : 440.0f * powf(2.0f, (note - 69) / 12.0f);

    v.ampEnv.setSampleRate(mSampleRate);
    v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.filterEnv.setSampleRate(mSampleRate);
    v.filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);

    v.ampEnv.trigger();
    v.filterEnv.trigger();
    v.svf.setParams(1000.0f, 0.7f, mSampleRate);

    for (int i = 0; i < 4; ++i) {
      v.oscillators[i].setFrequency(v.frequency, mSampleRate);
      v.oscillators[i].resetPhase();
    }
  }

  void releaseNote(int note) {
    for (auto &v : mVoices)
      if (v.active && v.note == note) {
        v.isNoteHeld = false;
        v.ampEnv.release();
        v.filterEnv.release();
      }
  }

  void setAttack(float v) {
    mAttack = v;
    updateLiveEnvelopes();
  }
  void setDecay(float v) {
    mDecay = v;
    updateLiveEnvelopes();
  }
  void setSustain(float v) {
    mSustain = v;
    updateLiveEnvelopes();
  }
  void setRelease(float v) {
    mRelease = v;
    updateLiveEnvelopes();
  }
  void setFilterAttack(float v) {
    mF_Atk = v;
    updateLiveEnvelopes();
  }
  void setFilterDecay(float v) {
    mF_Dcy = v;
    updateLiveEnvelopes();
  }
  void setFilterSustain(float v) {
    mF_Sus = v;
    updateLiveEnvelopes();
  }
  void setFilterRelease(float v) {
    mF_Rel = v;
    updateLiveEnvelopes();
  }

  void setCutoff(float cutoff) { mCutoff = cutoff; }
  void setResonance(float res) { mResonance = res; }
  void setFilterEnvAmount(float v) { mF_Amt = v * 2.0f - 1.0f; }
  void setDetune(float v) { mDetune = v; }
  void setNoiseLevel(float v) { mNoiseLevel = v; }
  void setOscVolume(int osc, float vol) {
    if (osc >= 0 && osc < 4)
      mOscVolumes[osc] = vol;
  }
  void setLfoRate(float rate) { mLfoRate = rate; }
  void setLfoDepth(float depth) { mLfoDepth = depth; }
  void setUseEnvelope(bool v) { mUseEnvelope = v; }

  void setParameter(int id, float value) {
    if (id == 112)
      setCutoff(value);
    else if (id == 113)
      setResonance(value);
    else if (id == 100)
      setAttack(value);
    else if (id == 101)
      setDecay(value);
    else if (id == 102)
      setSustain(value);
    else if (id == 103)
      setRelease(value);
    else if (id == 150)
      mOscSync = (value > 0.5f);
    else if (id == 151)
      mRingMod = (value > 0.5f);
    else if (id == 152)
      mFmAmt = value;
    else if (id >= 160 && id <= 163)
      mOscPitch[id - 160] = value * 4.0f;
    else if (id >= 170 && id <= 173)
      mOscDrive[id - 170] = 1.0f + value * 10.0f;
    else if (id >= 180 && id <= 183)
      mOscFold[id - 180] = value;
    else if (id >= 190 && id <= 193) {
      mOscPW[id - 190] = value;
      for (auto &v : mVoices)
        v.oscillators[id - 190].setWaveShape(value);
    }
  }

  void setOscWaveform(int index, float value) {
    if (index >= 0 && index < 4) {
      Waveform w = Waveform::Sine;
      if (value < 0.2f)
        w = Waveform::Sine;
      else if (value < 0.4f)
        w = Waveform::Triangle;
      else if (value < 0.6f)
        w = Waveform::Sawtooth;
      else if (value < 0.8f)
        w = Waveform::Square;
      else
        w = Waveform::Sawtooth;
      mOscWaveforms[index] = w;
      for (auto &v : mVoices)
        v.oscillators[index].setWaveform(w);
    }
  }

  void setFilterMode(int mode) { mFilterMode = mode; }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;
    float lfo =
        sinf(mControlCounter * 6.283185f * mLfoRate / mSampleRate) * mLfoDepth;

    for (auto &v : mVoices) {
      if (!v.active)
        continue;
      float envVal = mUseEnvelope ? v.ampEnv.nextValue() : 1.0f;
      if (envVal < 0.0001f && mUseEnvelope && !v.ampEnv.isActive()) {
        v.active = false;
        continue;
      }
      activeCount++;
      v.currentFilterEnvVal = v.filterEnv.nextValue();

      float osc1Pitch = mOscPitch[0];
      float osc2Pitch = mOscPitch[1] * (1.0f + mDetune * 0.05f);
      float osc3Pitch = mOscPitch[2];
      float osc4Pitch = mOscPitch[3];

      if (mOscSync && v.oscillators[0].hasWrapped()) {
        v.oscillators[1].resetPhase();
      }

      float osc1Val = v.oscillators[0].nextSample(0.0f, osc1Pitch, mOscFold[0]);
      float osc2Val = v.oscillators[1].nextSample(0.0f, osc2Pitch, mOscFold[1]);
      float osc3Val = v.oscillators[2].nextSample(0.0f, osc3Pitch, mOscFold[2]);
      float osc4Val = v.oscillators[3].nextSample(0.0f, osc4Pitch, mOscFold[3]);

      float subOutput = 0.0f;
      if (mRingMod) {
        subOutput = (osc1Val * mOscVolumes[0] * mOscDrive[0]) *
                    (osc2Val * mOscVolumes[1] * mOscDrive[1]);
      } else {
        subOutput = (osc1Val * mOscVolumes[0] * mOscDrive[0]) +
                    (osc2Val * mOscVolumes[1] * mOscDrive[1]);
      }
      subOutput += (osc3Val * mOscVolumes[2] * mOscDrive[2]);
      subOutput += (osc4Val * mOscVolumes[3] * mOscDrive[3]);

      mNoiseSeed = mNoiseSeed * 1103515245 + 12345;
      subOutput +=
          (((float)(mNoiseSeed & 0x7fffffff) / (float)0x7fffffff) * 2.0f -
           1.0f) *
          mNoiseLevel;
      float output = subOutput * 1.0f * v.amplitude * envVal;

      if (v.controlCounter++ % 16 == 0) {
        float modCutoff = std::max(
            0.0f,
            std::min(0.999f, mCutoff + v.currentFilterEnvVal * mF_Amt + lfo));
        v.svf.setParams(20.0f + modCutoff * modCutoff * 14000.0f,
                        std::max(0.1f, mResonance * 5.0f), mSampleRate);
      }

      TSvf::Type type = TSvf::LowPass;
      if (mFilterMode == 1)
        type = TSvf::HighPass;
      else if (mFilterMode == 2)
        type = TSvf::BandPass;
      else if (mFilterMode == 3)
        type = TSvf::Notch;

      mixedOutput += v.svf.process(output, type);
    }
    return fast_tanh(mixedOutput * (activeCount > 1 ? 0.7f : 1.0f));
  }

  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    return false;
  }

private:
  void updateLiveEnvelopes() {
    for (auto &v : mVoices)
      if (v.active) {
        v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
        v.filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);
      }
  }
  std::vector<Voice> mVoices;
  std::vector<float> mOscVolumes;
  std::vector<Waveform> mOscWaveforms;
  uint32_t mControlCounter = 0;
  float mCutoff = 0.45f, mResonance = 0.0f, mAttack = 0.01f, mDecay = 0.1f,
        mSustain = 0.8f, mRelease = 0.5f, mF_Atk = 0.01f, mF_Dcy = 0.1f,
        mF_Sus = 0.0f, mF_Rel = 0.5f, mF_Amt = 0.0f;
  float mDetune = 0.0f, mNoiseLevel = 0.0f, mLfoRate = 0.0f, mLfoDepth = 0.0f,
        mFrequency = 440.0f;
  unsigned int mNoiseSeed = 12345;
  float mSampleRate = 44100.0f;
  bool mUseEnvelope = true, mOscSync = false, mRingMod = false,
       mIgnoreNoteFrequency = false;
  float mFmAmt = 0.0f;
  int mFilterMode = 0;
  float mOscPitch[4] = {1.0f, 1.0f, 0.5f, 1.0f},
        mOscDrive[4] = {1.0f, 1.0f, 1.0f, 1.0f},
        mOscFold[4] = {0.0f, 0.0f, 0.0f, 0.0f},
        mOscPW[4] = {0.5f, 0.5f, 0.5f, 0.5f};
};

#endif
