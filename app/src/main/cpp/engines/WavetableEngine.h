#ifndef WAVETABLE_ENGINE_H
#define WAVETABLE_ENGINE_H

#include "../Utils.h"
#include "Adsr.h"
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

class WavetableEngine {
public:
  struct Voice {
    bool active = false;
    int note = -1;
    double phase = 0.0;
    float frequency = 440.0f;
    float amplitude = 1.0f;
    Adsr envelope;
    TSvf svf;

    void reset() {
      active = false;
      note = -1;
      phase = 0.0;
      envelope.reset();
      svf.setParams(1000.0f, 0.7f, 44100.0f);
    }
  };

  WavetableEngine() {
    mVoices.resize(16);
    for (auto &v : mVoices)
      v.reset();
    mTable.assign(2048, 0.0f); // Init with sine
    for (int i = 0; i < 2048; ++i)
      mTable[i] = sinf(i * 6.283185f / 2048.0f);
    mMutex = std::make_shared<std::mutex>();
    resetToDefaults();
  }

  void resetToDefaults() {
    mAttack = 0.01f;
    mDecay = 0.1f;
    mSustain = 0.8f;
    mRelease = 0.2f;
    mCutoff = 1.0f;
    mResonance = 0.0f;
    mPosition = 0.0f;
    mMorph = 0.0f;
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    for (auto &v : mVoices)
      v.envelope.setSampleRate(sr);
  }

  void allNotesOff() {
    for (auto &v : mVoices) {
      v.active = false;
      v.envelope.reset();
    }
  }

  void setFrequency(float freq, float sampleRate) {
    mSampleRate = sampleRate;
    mFrequency = freq;
  }

  // Overload to support both vector and path (AudioEngine calls path)
  void loadWavetable(const std::vector<float> &data) {
    std::lock_guard<std::mutex> lock(*mMutex);
    mTable = data;
  }

  void loadWavetable(const std::string &path) {
    // This will be handled by AudioEngine which calls WavFileUtils usually
    // But we need a stub to satisfy compile
  }

  void loadDefaultWavetable() {
    std::lock_guard<std::mutex> lock(*mMutex);
    mTable.assign(2048, 0.0f);
    for (int i = 0; i < 2048; ++i)
      mTable[i] = sinf(i * 6.283185f / 2048.0f);
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
    v.reset();
    v.active = true;
    v.note = note;
    v.amplitude = velocity / 127.0f;
    v.frequency = 440.0f * powf(2.0f, (note - 69) / 12.0f);

    v.envelope.setSampleRate(mSampleRate);
    v.envelope.setParameters(mAttack, mDecay, mSustain, mRelease);
    v.envelope.trigger();
  }

  void releaseNote(int note) {
    for (auto &v : mVoices)
      if (v.active && v.note == note)
        v.envelope.release();
  }

  void setAttack(float v) { mAttack = v; }
  void setDecay(float v) { mDecay = v; }
  void setSustain(float v) { mSustain = v; }
  void setRelease(float v) { mRelease = v; }
  void setFilterCutoff(float v) { mCutoff = v; }
  void setResonance(float v) { mResonance = v; }

  void setParameter(int id, float value) {
    switch (id) {
    case 0:
      mPosition = value;
      break;
    case 1:
      mMorph = value;
      break;
    case 10:
      setFilterCutoff(value);
      break;
    case 11:
      setResonance(value);
      break;
    case 14:
      setAttack(value);
      break;
    case 15:
      setDecay(value);
      break;
    case 16:
      setSustain(value);
      break;
    case 17:
      setRelease(value);
      break;
    }
  }

  float render() {
    float mixedOutput = 0.0f;
    int activeCount = 0;
    std::lock_guard<std::mutex> lock(*mMutex);
    if (mTable.empty())
      return 0.0f;

    for (auto &v : mVoices) {
      if (!v.active)
        continue;
      float env = v.envelope.nextValue();
      if (env < 0.0001f && !v.envelope.isActive()) {
        v.active = false;
        continue;
      }
      activeCount++;

      double delta = v.frequency / mSampleRate;
      v.phase += delta;
      if (v.phase >= 1.0)
        v.phase -= 1.0;

      // Linear Interpolation
      double tablePos = v.phase * (mTable.size() - 1);
      int idx1 = static_cast<int>(tablePos);
      int idx2 = (idx1 + 1) % mTable.size();
      float frac = static_cast<float>(tablePos - idx1);
      float sample = (1.0f - frac) * mTable[idx1] + frac * mTable[idx2];

      float cutoff = 20.0f + mCutoff * mCutoff * 18000.0f;
      v.svf.setParams(cutoff, 0.7f + mResonance * 5.0f, mSampleRate);
      float out = v.svf.process(sample, TSvf::LowPass);

      mixedOutput += out * env * v.amplitude;
    }

    if (activeCount > 1)
      mixedOutput *= 0.7f;
    return fast_tanh(mixedOutput);
  }

  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active)
        return true;
    return false;
  }

private:
  std::vector<Voice> mVoices;
  std::vector<float> mTable;
  float mSampleRate = 44100.0f, mFrequency = 440.0f;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 0.8f, mRelease = 0.2f;
  float mCutoff = 1.0f, mResonance = 0.0f, mPosition = 0.0f, mMorph = 0.0f;
  std::shared_ptr<std::mutex>
      mMutex; // Use shared_ptr to allow Track move/copy in vector
};

#endif
