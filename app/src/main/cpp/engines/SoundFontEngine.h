#ifndef SOUNDFONT_ENGINE_H
#define SOUNDFONT_ENGINE_H

#include "../libs/tsf.h"
#include <memory>
#include <mutex>
#include <string>
#include <vector>

class SoundFontEngine {
public:
  SoundFontEngine() : mTsf(nullptr), mMutex(std::make_unique<std::mutex>()) {}

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
    if (mTsf) {
      if (mLastNote != -1 && mGlide > 0.001f) {
        mCurrentPitchWheel = (float)(mLastNote - note);
      } else {
        mCurrentPitchWheel = 0.0f;
      }
      mLastNote = note;
      tsf_channel_note_on(mTsf, 0, note, velocity / 127.0f);
      updatePitchWheel();
    }
  }

  void noteOff(int note) {
    if (mTsf)
      tsf_channel_note_off(mTsf, 0, note);
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
        left[i] = mInternalBuffer[mBufferPos * 2];
        right[i] = mInternalBuffer[mBufferPos * 2 + 1];
        mBufferPos++;
      }
    } else {
      for (int i = 0; i < numFrames; ++i) {
        left[i] = right[i] = 0.0f;
      }
    }
  }

  void allNotesOff() {
    if (mTsf)
      tsf_note_off_all(mTsf);
  }

  void setParameter(int id, float value) {
    if (id == 355) {
      setGlide(value);
    } else if (id == 100) { // Attack -> CC 73
      midiControl(73, (int)(value * 127));
    } else if (id == 103) { // Release -> CC 72
      midiControl(72, (int)(value * 127));
    } else if (id == 112 || id == 1) { // Cutoff -> CC 74 (Brightness)
      midiControl(74, (int)(value * 127));
    } else if (id == 113 || id == 2) { // Resonance -> CC 71 (Harmonic Content)
      midiControl(71, (int)(value * 127));
    } else if (id == 150) { // Reverb Send -> CC 91
      midiControl(91, (int)(value * 127));
    } else if (id == 151) { // Chorus Send -> CC 93
      midiControl(93, (int)(value * 127));
    } else if (id == 152) { // Pan -> CC 10
      midiControl(10, (int)(value * 127));
    }
  }

  void midiControl(int cc, int val) {
    if (mTsf)
      tsf_channel_midi_control(mTsf, 0, cc, val);
  }

  void setMapping(int knobId, int genId) {
    // Implementation for mapping knobs to TSF generators (future expansion)
  }

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
};

#endif // SOUNDFONT_ENGINE_H
