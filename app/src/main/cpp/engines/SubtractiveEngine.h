#ifndef SUBTRACTIVE_ENGINE_H
#define SUBTRACTIVE_ENGINE_H

#include "../Utils.h"
#include "Adsr.h"
#include "Oscillator.h"
#include <android/log.h> // Fixed missing include
#include <cmath>
#include <vector>

class SubtractiveEngine {
public:
  struct Voice {
    bool active = false;
    int note = -1;
    float frequency = 440.0f;
    float amplitude = 1.0f;
    Adsr ampEnv;
    Adsr filterEnv;
    std::vector<Oscillator> oscillators;

    // SVF state
    float svf_L = 0, svf_B = 0, svf_H = 0;
    float currentFilterEnvVal = 0.0f;

    Voice() { oscillators.resize(4); }

    void reset() {
      active = false;
      note = -1;
      ampEnv.reset();
      filterEnv.reset();
      svf_L = svf_B = svf_H = 0.0f;
    }
  };

  SubtractiveEngine() {
    mVoices.resize(6);
    for (auto &v : mVoices)
      v.reset();

    mOscVolumes.assign(4, 0.0f);
    mOscVolumes[0] = 0.6f;
    mOscVolumes[1] = 0.4f; // Sane default
    mOscWaveforms.assign(4, Waveform::Sine);
    mOscWaveforms[0] = Waveform::Sawtooth;
    mOscWaveforms[1] = Waveform::Square;
    mOscWaveforms[2] = Waveform::Square;
    mOscWaveforms[3] = Waveform::Sine;

    // Apply default waveforms to all oscillators in all voices
    for (auto &v : mVoices) {
      for (int i = 0; i < 4; ++i)
        v.oscillators[i].setWaveform(mOscWaveforms[i]);
    }
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices) {
      v.ampEnv.setSampleRate(sr);
      v.filterEnv.setSampleRate(sr);
    }
  }

  void setFrequency(float freq, float sampleRate) { mSampleRate = sampleRate; }
  void setFMEnabled(bool enabled);
  void setRingVal(float val);

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.ampEnv.reset();
      v.filterEnv.reset();
    }
    // activeCount is a local variable in render(), not a member.
    // If the intention was to track active voices globally, a member variable
    // would be needed. For now, removing this line to maintain syntactic
    // correctness. activeCount = 0;
  }

  void triggerNote(int note, int velocity) {
    // Alloc voice
    int idx = -1;
    for (int i = 0; i < mVoices.size(); ++i) {
      if (mVoices[i].active && mVoices[i].note == note) {
        idx = i;
        break;
      }
    }
    if (idx == -1) {
      for (int i = 0; i < mVoices.size(); ++i) {
        if (!mVoices[i].active) {
          idx = i;
          break;
        }
      }
    }
    if (idx == -1)
      idx = 0; // Steal

    Voice &v = mVoices[idx];
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    v.frequency = 440.0f * powf(2.0f, (note - 69) / 12.0f);

    // Set parameters
    v.ampEnv.setSampleRate(mSampleRate);
    v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.filterEnv.setSampleRate(mSampleRate);
    v.filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);

    v.ampEnv.trigger();
    v.filterEnv.trigger();
    v.svf_L = v.svf_B = v.svf_H = 0.0f;

    // FIX: Must set oscillator frequency for phase increment to be non-zero!
    for (int i = 0; i < 4; ++i) {
      v.oscillators[i].setFrequency(v.frequency, mSampleRate);
    }
  }

  void releaseNote(int note) {
    for (auto &v : mVoices) {
      if (v.active && v.note == note) {
        v.ampEnv.release();
        v.filterEnv.release();
      }
    }
  }

  void setOscVolume(int index, float volume) {
    if (index >= 0 && index < 4)
      mOscVolumes[index] = volume;
  }
  void setCutoff(float cutoff) { mCutoff = cutoff; }
  void setResonance(float res) { mResonance = res; }
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

  void setMorph(float v) { mMorph = v; }
  void setDetune(float v) { mDetune = v; }
  void setNoiseLevel(float v) { mNoiseLevel = v; }
  void setChordVoicing(float v) { mChordType = static_cast<int>(v * 5); }
  void setFilterEnvAmount(float v) { mF_Amt = v * 2.0f - 1.0f; }
  void setUseEnvelope(bool use) { mUseEnvelope = use; }

  // Stubs for generic modulation compatibility
  void setLfoRate(float v) { /* No internal LFO yet */ }
  void setLfoDepth(float v) { /* No internal LFO yet */ }

  void setParameter(int id, float value) {
    if (id == 150)
      mOscSync = value > 0.5f;
    else if (id == 151)
      mRingMod = value > 0.5f;
    else if (id == 152)
      mFmAmt = value;
    else if (id >= 160 && id <= 163)
      mOscPitch[id - 160] = value * 4.0f; // 0..4x ratio
    else if (id >= 170 && id <= 173)
      mOscDrive[id - 170] = 1.0f + value * 10.0f; // 1..11x drive
    else if (id >= 180 && id <= 183)
      mOscFold[id - 180] = value;
    else if (id >= 190 && id <= 193) {
      mOscPW[id - 190] = value;
      for (auto &v : mVoices)
        v.oscillators[id - 190].setWaveShape(value);
    }
    // Filter/Env params already have individual setters.
  }

  void setOscWaveform(int index, float value) {
    if (index >= 0 && index < 4) {
      int shape = static_cast<int>(value * 3.99f);
      mOscWaveforms[index] = static_cast<Waveform>(shape);
      for (auto &v : mVoices)
        v.oscillators[index].setWaveform(mOscWaveforms[index]);
    }
  }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;

    for (auto &v : mVoices) {
      if (!v.active)
        continue;

      float envVal = mUseEnvelope ? v.ampEnv.nextValue() : 1.0f;
      if (envVal < 0.0001f && !v.ampEnv.isActive()) {
        v.active = false;
        continue;
      }
      activeCount++;

      v.currentFilterEnvVal = v.filterEnv.nextValue();

      float osc1Pitch = mOscPitch[0];
      float osc2Pitch = mOscPitch[1] * (1.0f + mDetune * 0.05f);

      // Hard Sync Check
      if (mOscSync && v.oscillators[0].hasWrapped()) {
        v.oscillators[1].resetPhase();
      }

      float osc1Val = 0.0f;
      float osc2Val = 0.0f;

      // FM Modulation: Osc 2 modulates Osc 1
      if (mFmAmt > 0.001f) {
        osc2Val = v.oscillators[1].nextSample(0.0f, osc2Pitch, mOscFold[1]);
        osc1Val = v.oscillators[0].nextSample(osc2Val * mFmAmt * 3.0f,
                                              osc1Pitch, mOscFold[0]);
      } else {
        osc1Val = v.oscillators[0].nextSample(0.0f, osc1Pitch, mOscFold[0]);
        osc2Val = v.oscillators[1].nextSample(0.0f, osc2Pitch, mOscFold[1]);
      }

      float osc3Val =
          v.oscillators[2].nextSample(0.0f, mOscPitch[2], mOscFold[2]);

      float subOutput = 0.0f;
      if (mRingMod) {
        subOutput = (osc1Val * mOscVolumes[0] * mOscDrive[0]) *
                    (osc2Val * mOscVolumes[1] * mOscDrive[1]);
      } else {
        subOutput = (osc1Val * mOscVolumes[0] * mOscDrive[0]) +
                    (osc2Val * mOscVolumes[1] * mOscDrive[1]);
      }
      subOutput += (osc3Val * mOscVolumes[2] * mOscDrive[2]);

      // Noise
      mNoiseSeed = mNoiseSeed * 1103515245 + 12345;
      float noise =
          ((float)(mNoiseSeed & 0x7fffffff) / (float)0x7fffffff) * 2.0f - 1.0f;
      subOutput += noise * mNoiseLevel;

      float output = subOutput * 0.5f * v.amplitude * envVal;

      // Filter
      float modCutoff = std::max(
          0.0f, std::min(1.0f, mCutoff + v.currentFilterEnvVal * mF_Amt));
      float radians = (float)M_PI * (20.0f + modCutoff * modCutoff * 14980.0f) /
                      mSampleRate;
      float f = 2.0f * FastSine::getInstance().sin(radians);
      float q = 1.0f - mResonance * 0.95f;
      if (q < 0.05f)
        q = 0.05f;

      v.svf_H = output - v.svf_L - q * v.svf_B;
      v.svf_B = f * v.svf_H + v.svf_B;
      v.svf_L = f * v.svf_B + v.svf_L;

      float res = (mCutoff < 0.5f) ? v.svf_L : v.svf_H;

      // Soft Clip
      if (res < -3.0f)
        res = -1.0f;
      else if (res > 3.0f)
        res = 1.0f;
      else
        res = res * (27.0f + res * res) / (27.0f + 9.0f * res * res);

      mixedOutput += res;

      // Debug
      static int debugCounter = 0;
      if (debugCounter++ % 5000 == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "SubtractiveDebug",
                            "V%d: Note=%d Osc1=%.2f Osc2=%.2f Ring=%d FM=%.2f "
                            "Vol1=%.2f Vol2=%.2f Env=%.2f Out=%.2f",
                            activeCount, v.note, osc1Val, osc2Val, mRingMod,
                            mFmAmt, mOscVolumes[0], mOscVolumes[1], envVal,
                            res);
      }
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
  void updateLiveEnvelopes() {
    for (auto &v : mVoices) {
      if (v.active) {
        v.ampEnv.setParameters(mAttack, mDecay, mSustain, mRelease);
        v.filterEnv.setParameters(mF_Atk, mF_Dcy, mF_Sus, mF_Rel);
      }
    }
  }

  std::vector<Voice> mVoices;
  std::vector<float> mOscVolumes;
  std::vector<Waveform> mOscWaveforms;
  float mCutoff = 0.45f, mResonance = 0.0f;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 0.8f, mRelease = 0.5f;
  float mF_Atk = 0.01f, mF_Dcy = 0.1f, mF_Sus = 0.0f, mF_Rel = 0.5f;
  float mF_Amt = 0.0f;
  float mMorph = 0.0f, mDetune = 0.0f, mNoiseLevel = 0.0f;
  unsigned int mNoiseSeed = 12345;
  int mChordType = 0;
  float mSampleRate = 44100.0f;
  bool mUseEnvelope = true;

  // Modulation State
  bool mOscSync = false;
  bool mRingMod = false;
  float mFmAmt = 0.0f;
  float mOscPitch[4] = {1.0f, 1.0f, 0.5f, 1.0f}; // Ratio
  float mOscDrive[4] = {1.0f, 1.0f, 1.0f, 1.0f};
  float mOscFold[4] = {0.0f, 0.0f, 0.0f, 0.0f};
  float mOscPW[4] = {0.5f, 0.5f, 0.5f, 0.5f};
};

#endif
