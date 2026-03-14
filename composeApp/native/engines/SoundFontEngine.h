#ifndef SOUNDFONT_ENGINE_H
#define SOUNDFONT_ENGINE_H

#include "../Utils.h"
#include "../libs/tsf.h"
#include "Adsr.h"
#include <memory>
#include <mutex>
#include <string>
#include <vector>

class SoundFontEngine {
public:
  SoundFontEngine() : mTsf(nullptr), mMutex(std::make_unique<std::mutex>()) {}

  // Explicit move semantics required due to custom destructor + unique_ptr
  SoundFontEngine(SoundFontEngine &&other) noexcept
      : mTsf(other.mTsf), mGlide(other.mGlide), mLastNote(other.mLastNote),
        mCurrentPitchWheel(other.mCurrentPitchWheel),
        mSampleRate(other.mSampleRate), mBufferPos(other.mBufferPos),
        mBufferFrames(other.mBufferFrames), mMutex(std::move(other.mMutex)),
        mEnvelope(std::move(other.mEnvelope)),
        mFilter(std::move(other.mFilter)), mAttack(other.mAttack),
        mDecay(other.mDecay), mSustain(other.mSustain),
        mRelease(other.mRelease), mCutoff(other.mCutoff),
        mResonance(other.mResonance), mFilterMode(other.mFilterMode) {
    other.mTsf = nullptr;
    memcpy(mInternalBuffer, other.mInternalBuffer, sizeof(mInternalBuffer));
  }

  SoundFontEngine &operator=(SoundFontEngine &&other) noexcept {
    if (this != &other) {
      if (mTsf)
        tsf_close(mTsf);
      mTsf = other.mTsf;
      other.mTsf = nullptr;
      mGlide = other.mGlide;
      mLastNote = other.mLastNote;
      mCurrentPitchWheel = other.mCurrentPitchWheel;
      mSampleRate = other.mSampleRate;
      mBufferPos = other.mBufferPos;
      mBufferFrames = other.mBufferFrames;
      mMutex = std::move(other.mMutex);
      mEnvelope = std::move(other.mEnvelope);
      mFilter = std::move(other.mFilter);
      mAttack = other.mAttack;
      mDecay = other.mDecay;
      mSustain = other.mSustain;
      mRelease = other.mRelease;
      mCutoff = other.mCutoff;
      mResonance = other.mResonance;
      mFilterMode = other.mFilterMode;
      memcpy(mInternalBuffer, other.mInternalBuffer, sizeof(mInternalBuffer));
    }
    return *this;
  }

  ~SoundFontEngine() {
    if (mTsf)
      tsf_close(mTsf);
  }

  void load(const std::string &path) {
    if (mMutex) {
      std::lock_guard<std::mutex> lock(*mMutex);
      if (mTsf)
        tsf_close(mTsf);
      mTsf = tsf_load_filename(path.c_str());
      if (mTsf) {
        tsf_set_output(mTsf, TSF_STEREO_INTERLEAVED, 48000, 0.0f);
        tsf_channel_set_pitchrange(mTsf, 0, 24.0f); // +/- 2 octaves
        mBufferPos = 128;                           // Force reload
      }
    }
  }

  void setSampleRate(float sr) {
    mSampleRate = sr;
    mEnvelope.setSampleRate(sr);
    mFilter.setParams(mCutoff * 10000.0f, mResonance, sr);
    if (mTsf) {
      tsf_set_output(mTsf, TSF_STEREO_INTERLEAVED, (int)sr, 0.0f);
    }
  }

  void setGlide(float g) { mGlide = g; }

  void setPreset(int presetIndex) {
    if (mMutex) {
      std::lock_guard<std::mutex> lock(*mMutex);
      if (mTsf && presetIndex >= 0 && presetIndex < tsf_get_presetcount(mTsf)) {
        tsf_note_off_all(mTsf);
        tsf_channel_set_presetindex(mTsf, 0, presetIndex);
      }
    }
  }

  std::string getPresetName(int presetIndex) {
    if (mTsf && presetIndex >= 0 && presetIndex < tsf_get_presetcount(mTsf)) {
      const char *name = tsf_get_presetname(mTsf, presetIndex);
      return name ? std::string(name) : "Unknown";
    }
    return "";
  }

  int getPresetCount() { return mTsf ? tsf_get_presetcount(mTsf) : 0; }

  void noteOn(int note, int velocity) {
    if (mMutex && mTsf) {
      std::lock_guard<std::mutex> lock(*mMutex);
      if (mLastNote != -1 && mGlide > 0.001f) {
        mCurrentPitchWheel = (float)(mLastNote - note);
      } else {
        mCurrentPitchWheel = 0.0f;
      }
      mLastNote = note;
      tsf_channel_note_on(mTsf, 0, note, velocity / 127.0f);
      updatePitchWheel();

      mEnvelope.setParameters(mAttack, mDecay, mSustain, mRelease);
      mEnvelope.trigger();
    }
  }

  void noteOff(int note) {
    if (mMutex && mTsf) {
      std::lock_guard<std::mutex> lock(*mMutex);
      if (mLastNote == note) {
        mEnvelope.release();
      }
      tsf_channel_note_off(mTsf, 0, note);
    }
  }

  void render(float *left, float *right, int numFrames) {
    if (mMutex && mTsf) {
      std::lock_guard<std::mutex> lock(*mMutex);
      if (mGlide > 0.001f) {
        float glideTimeSamples = mGlide * mSampleRate * 0.5f;
        float glideAlpha = 1.0f / (glideTimeSamples + 1.0f);
        mCurrentPitchWheel += (0.0f - mCurrentPitchWheel) * glideAlpha;
        updatePitchWheel();
      } else {
        mCurrentPitchWheel = 0.0f;
        updatePitchWheel();
      }

      for (int i = 0; i < numFrames; ++i) {
        if (mBufferPos >= mBufferFrames) {
          // Render next internal block (64 samples interleaved)
          mBufferFrames = 64;
          tsf_render_float(mTsf, mInternalBuffer, mBufferFrames, 0);
          mBufferPos = 0;
        }

        float env = mEnvelope.nextValue();
        float sL = mInternalBuffer[mBufferPos * 2];
        float sR = mInternalBuffer[mBufferPos * 2 + 1];

        // Filter processing
        left[i] = mFilter.process(sL, (TSvf::Type)mFilterMode) * env;
        right[i] = mFilter.process(sR, (TSvf::Type)mFilterMode) * env;

        mBufferPos++;
      }
    } else {
      for (int i = 0; i < numFrames; ++i) {
        left[i] = right[i] = 0.0f;
      }
    }
  }

  void allNotesOff() {
    if (mMutex && mTsf) {
      std::lock_guard<std::mutex> lock(*mMutex);
      tsf_note_off_all(mTsf);
      mEnvelope.reset();
    }
  }

  void setParameter(int id, float value) {
    if (mMutex && mTsf) {
      std::lock_guard<std::mutex> lock(*mMutex);
      if (id == 355) {
        setGlide(value);
      } else if (id == 0) { // Level -> CC 7 (Volume)
        midiControl(7, (int)(value * 127));
      } else if (id == 6) { // Detune -> RPN Fine Tune
        int val14 = (int)(value * 16383.0f);
        int msb = (val14 >> 7) & 0x7F;
        int lsb = val14 & 0x7F;
        // RPN 00 01 (Fine Tuning)
        midiControl(101, 0);
        midiControl(100, 1);
        midiControl(6, msb);
        midiControl(38, lsb);
        // Reset RPN
        midiControl(101, 127);
        midiControl(100, 127);
      } else if (id == 7) { // Rate -> CC 76 (Vibrato Rate)
        midiControl(76, (int)(value * 127));
      } else if (id == 8) { // Depth -> CC 1 (Mod Wheel)
        midiControl(1, (int)(value * 127));
      } else if (id == 100) { // Forced Attack
        mAttack = value;
      } else if (id == 101) { // Forced Decay
        mDecay = value;
      } else if (id == 102) { // Forced Sustain
        mSustain = value;
      } else if (id == 103) { // Forced Release
        mRelease = value;
      } else if (id == 112 || id == 1) { // Forced Cutoff
        mCutoff = value;
        mFilter.setParams(mCutoff * 10000.0f, mResonance, mSampleRate);
      } else if (id == 113 || id == 2) { // Forced Resonance
        mResonance = 0.7f + value * 5.0f;
        mFilter.setParams(mCutoff * 10000.0f, mResonance, mSampleRate);
      } else if (id == 20) { // Filter Mode
        mFilterMode = (int)(value * 3.99f);
      } else if (id == 150) { // Reverb Send -> CC 91
        midiControl(91, (int)(value * 127));
      } else if (id == 151) { // Chorus Send -> CC 93
        midiControl(93, (int)(value * 127));
      } else if (id == 152) { // Pan -> CC 10
        midiControl(10, (int)(value * 127));
      }
    }
  }

  void midiControl(int cc, int val) {
    if (mTsf)
      tsf_channel_midi_control(mTsf, 0, cc, val);
  }

  void setMapping(int knobId, int genId) {
    // Implementation for mapping knobs to TSF generators (future expansion)
  }

  float getEnvelopeValue() const { return mEnvelope.getValue(); }

private:
  void updatePitchWheel() {
    if (!mTsf)
      return;
    float wheelValue = 8192.0f + (mCurrentPitchWheel / 24.0f) * 8192.0f;
    if (wheelValue < 0.0f)
      wheelValue = 0.0f;
    if (wheelValue > 16383.0f)
      wheelValue = 16383.0f;
    tsf_channel_set_pitchwheel(mTsf, 0, (int)wheelValue);
  }

  tsf *mTsf;
  float mGlide = 0.0f;
  int mLastNote = -1;
  float mCurrentPitchWheel = 0.0f;
  float mSampleRate = 48000.0f;

  float mInternalBuffer[128]; // 64 stereo frames
  int mBufferPos = 128;
  int mBufferFrames = 128;
  std::unique_ptr<std::mutex> mMutex;

  // New Bolt-on Components
  Adsr mEnvelope;
  TSvf mFilter;
  float mAttack = 0.01f, mDecay = 0.1f, mSustain = 1.0f, mRelease = 0.2f;
  float mCutoff = 1.0f, mResonance = 0.7f;
  int mFilterMode = 0;
};

#endif // SOUNDFONT_ENGINE_H
