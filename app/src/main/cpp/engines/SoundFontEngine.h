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
      tsf_set_output(mTsf, TSF_STEREO_INTERLEAVED, 48000, 0.0f);
    }
  }

  void setPreset(int presetIndex) {
    if (mTsf && presetIndex >= 0 && presetIndex < tsf_get_presetcount(mTsf)) {
      tsf_note_off_all(mTsf);
      // TSF_STEREO_INTERLEAVED is 0, so just calling set_presetindex is enough
      // for most cases But if we want bank support, we can use
      // tsf_channel_set_presetindex
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
    if (mTsf)
      tsf_channel_note_on(mTsf, 0, note, velocity / 127.0f);
  }

  void noteOff(int note) {
    if (mTsf)
      tsf_channel_note_off(mTsf, 0, note);
  }

  void render(float *output, int numFrames) {
    if (mTsf) {
      tsf_render_float(mTsf, output, numFrames, 0);
    } else {
      for (int i = 0; i < numFrames * 2; ++i)
        output[i] = 0.0f;
    }
  }

  void allNotesOff() {
    if (mTsf)
      tsf_note_off_all(mTsf);
  }

  void setParameter(int id, float value) {
    if (!mTsf)
      return;
    // Map ADSR and other params to TSF generators if possible
    // For now, we mainly use this for future expansion or specific needs
    // such as setting global volume or channel parameters.
  }

  // Mapping for generator modifications (Cutoff, Resonance, etc.)
  void setMapping(int knobId, int genId) {
    // Implementation for mapping knobs to TSF generators (future expansion)
  }

private:
  tsf *mTsf;
};

#endif // SOUNDFONT_ENGINE_H
