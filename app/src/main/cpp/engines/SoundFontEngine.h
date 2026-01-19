#ifndef SOUNDFONT_ENGINE_H
#define SOUNDFONT_ENGINE_H

#include "../libs/tsf.h"
#include <string>
#include <vector>

class SoundFontEngine {
public:
  SoundFontEngine() : mTsf(nullptr) {}

  ~SoundFontEngine() {
    if (mTsf)
      tsf_close(mTsf);
  }

  void load(const std::string &path) {
    if (mTsf)
      tsf_close(mTsf);
    mTsf = tsf_load_filename(path.c_str());
    if (mTsf) {
      tsf_set_output(mTsf, TSF_STEREO_INTERLEAVED, 44100, 0.0f);
      tsf_channel_set_pitchrange(mTsf, 0, 24.0f); // +/- 2 octaves
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
    if (mTsf && presetIndex >= 0 && presetIndex < tsf_get_presetcount(mTsf)) {
      tsf_note_off_all(mTsf);
      tsf_channel_set_presetindex(mTsf, 0, presetIndex);
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
    if (mTsf) {
      if (mGlide > 0.001f) {
        float glideTimeSamples = mGlide * mSampleRate * 0.5f;
        float glideAlpha = 1.0f / (glideTimeSamples + 1.0f);
        mCurrentPitchWheel += (0.0f - mCurrentPitchWheel) * glideAlpha;
        updatePitchWheel();
      } else {
        mCurrentPitchWheel = 0.0f;
        updatePitchWheel();
      }

      float buffer[2];
      for (int i = 0; i < numFrames; ++i) {
        tsf_render_float(mTsf, buffer, 1, 0);
        *left = buffer[0];
        *right = buffer[1];
      }
    } else {
      *left = *right = 0.0f;
    }
  }

  void allNotesOff() {
    if (mTsf)
      tsf_note_off_all(mTsf);
  }

  void setParameter(int id, float value) {
    if (id == 355) {
      setGlide(value);
    }
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
  float mSampleRate = 44100.0f;
};

#endif // SOUNDFONT_ENGINE_H
