#ifndef OCTAVER_FX_H
#define OCTAVER_FX_H

#include <algorithm>
#include <cmath>
#include <vector>

// Simple Granular Pitch Shifter for Octaver
class OctaverFx {
public:
  OctaverFx() {
    mBuffer.resize(8192, 0.0f); // ~180ms circular buffer
  }

  float process(float input, float sampleRate) {
    if (mMix <= 0.001f)
      return 0.0f;

    // Write to circular buffer
    mBuffer[mWritePos] = input;

    // Render voices based on Mode
    float wet = 0.0f;

    // Main Pitch Logic:
    // pitchRatio 0.5 = Oct Down
    // pitchRatio 2.0 = Oct Up

    // 0: Oct Up
    // 1: Octs Up (1up, 2up)
    // 2: Oct Down
    // 3: Octs Down (1down, 2down)
    // 4: Up/Down
    // 5..11: Chords

    int mode = (int)(mMode * 11.9f);

    auto processVoice = [&](float ratio, float &phase,
                            float &windowPhase) -> float {
      // Grain Logic:
      // Traversing buffer at speed 'ratio'.
      // But we are constantly writing.
      // Effective read speed needs to stay within buffer relative to write
      // head. Simple Pitch Shifter: ReadRate = Ratio. But we need to window to
      // avoid clicks.

      // Increment Phase
      float rate = ratio;
      // Correction: If reading faster (2.0), we pass write head.
      // We need typically 2 overlapping windows.
      // Let's use a simpler "phasor" approach for pitch shifting.
      // Phasor 0..1 at freq = (1 - ratio) / WindowSize?
      // Actually, for real-time delay-based pitch shifting:
      // Delay ramps from 0 to WindowSize.
      // Slope determines pitch.

      // Delay modulation rate:
      // (1.0 - ratio) samples per sample.
      // If ratio 0.5 (Down), delay increases by 0.5 samples/sample.
      // If ratio 2.0 (Up), delay decreases by 1.0 samples/sample.

      float drift = 1.0f - ratio;
      phase += drift;

      // Wrap / Window
      const float windowSize = 2048.0f; // ~46ms

      auto getGrain = [&](float p) -> float {
        while (p < 0.0f)
          p += windowSize;
        while (p >= windowSize)
          p -= windowSize;

        // Actual delay
        float delay = p; // 0..Window

        float rPos = (float)mWritePos - delay;
        while (rPos < 0.0f)
          rPos += mBuffer.size();
        while (rPos >= mBuffer.size())
          rPos -= mBuffer.size();

        int i0 = (int)rPos;
        int i1 = (i0 + 1) % mBuffer.size();
        float f = rPos - i0;
        float samp = mBuffer[i0] * (1.0f - f) + mBuffer[i1] * f;

        // Triangular Window
        float win = 1.0f - std::abs(2.0f * (p / windowSize) - 1.0f);
        return samp * win;
      };

      float v1 = getGrain(phase);
      float v2 = getGrain(phase + windowSize * 0.5f); // Offset 50%

      return v1 + v2;
    };

    if (mode == 0) { // Oct Up
      wet += processVoice(2.0f, mPhase1, mWin1);
      if (mUnison > 0.3f)
        wet += processVoice(2.01f, mPhase2, mWin2); // Detuned
    } else if (mode == 2) {                         // Oct Down
      wet += processVoice(0.5f, mPhase1, mWin1);
      if (mUnison > 0.3f)
        wet += processVoice(0.505f, mPhase2, mWin2);
    } else if (mode == 4) { // Up/Down
      wet += processVoice(2.0f, mPhase1, mWin1);
      wet += processVoice(0.5f, mPhase2, mWin2);
    } else {
      // Default placeholder for complex chord modes
      // Just Oct Up/Down mix for now
      wet += processVoice(2.0f, mPhase1, mWin1);
    }

    // Advance Write
    mWritePos++;
    if (mWritePos >= mBuffer.size())
      mWritePos = 0;

    return wet * mMix;
  }

  void setParameters(float mix, float detune, float unison, float mode) {
    mMix = mix;
    mDetune = detune;
    mUnison = unison;
    mMode = mode;
  }

  void setMix(float v) { mMix = v; }
  void setDetune(float v) { mDetune = v; }
  void setUnison(float v) { mUnison = v; }
  void setMode(float v) { mMode = v; }

private:
  std::vector<float> mBuffer;
  int mWritePos = 0;

  // Voices state
  float mPhase1 = 0.0f, mPhase2 = 0.0f, mPhase3 = 0.0f;
  float mWin1 = 0.0f, mWin2 = 0.0f, mWin3 = 0.0f;

  float mMix = 0.0f;
  float mDetune = 0.0f;
  float mUnison = 0.0f;
  float mMode = 0.0f;
};

#endif
