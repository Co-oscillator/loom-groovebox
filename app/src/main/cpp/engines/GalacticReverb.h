#ifndef GALACTIC_REVERB_H
#define GALACTIC_REVERB_H

#include <algorithm>
#include <cmath>
#include <vector>

// Freeverb Tuning Values (Schroeder/Moorer)
const int kNumCombs = 8;
const int kNumAllPass = 4;
const float kFixedGain = 0.015f;
const float kScaleDamp = 0.4f;
const float kScaleRoom = 0.28f;
const float kOffsetRoom = 0.7f;
const float kInitialRoom = 0.5f;
const float kInitialDamp = 0.5f;
const float kInitialWet = 1.0f / 3.0f; // roughly -10dB
const float kInitialDry = 0.0f;
const float kInitialWidth = 1.0f;
const float kInitialMode = 0.0f;
const float kStereoSpread = 23.0f;

// Comb Filter Class
class Comb {
public:
  void setBufferSize(int size) {
    buffer.assign(size, 0.0f);
    bufSize = size;
    bufIdx = 0;
  }
  inline float process(float input) {
    float output = buffer[bufIdx];
    undoDenormal(output);

    filterStore = (output * (1.0f - damp)) + (filterStore * damp);
    undoDenormal(filterStore);

    buffer[bufIdx] = input + (filterStore * feedback);
    if (++bufIdx >= bufSize)
      bufIdx = 0;

    return output;
  }
  void setDamp(float val) { damp = val; }
  void setFeedback(float val) { feedback = val; }
  void clear() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    filterStore = 0.0f;
    bufIdx = 0;
  }

private:
  std::vector<float> buffer;
  int bufSize = 0;
  int bufIdx = 0;
  float feedback = 0.5f;
  float filterStore = 0.0f;
  float damp = 0.5f;

  inline void undoDenormal(float &v) {
    if (std::abs(v) < 1e-15f)
      v = 0.0f;
  }
};

// Allpass Filter Class
class AllPass {
public:
  void setBufferSize(int size) {
    buffer.assign(size, 0.0f);
    bufSize = size;
    bufIdx = 0;
  }
  inline float process(float input) {
    float bufOut = buffer[bufIdx];
    undoDenormal(bufOut);

    float output = -input + bufOut;
    buffer[bufIdx] = input + (bufOut * 0.5f);
    if (++bufIdx >= bufSize)
      bufIdx = 0;

    return output;
  }
  void clear() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    bufIdx = 0;
  }

private:
  std::vector<float> buffer;
  int bufSize = 0;
  int bufIdx = 0;

  inline void undoDenormal(float &v) {
    if (std::abs(v) < 1e-15f)
      v = 0.0f;
  }
};

class GalacticReverb {
public:
  GalacticReverb() {
    // Stereo initialization
    // Comb Tunings (samples at 44.1k)
    const int combTunings[kNumCombs] = {1116, 1188, 1277, 1356,
                                        1422, 1491, 1557, 1617};
    // Allpass Tunings
    const int allpassTunings[kNumAllPass] = {556, 441, 341, 225};

    for (int i = 0; i < kNumCombs; ++i) {
      combL[i].setBufferSize(combTunings[i]);
      combR[i].setBufferSize(combTunings[i] +
                             kStereoSpread); // Slight spread for stereo
    }
    for (int i = 0; i < kNumAllPass; ++i) {
      allpassL[i].setBufferSize(allpassTunings[i]);
      allpassR[i].setBufferSize(allpassTunings[i] + kStereoSpread);
    }

    setWet(kInitialWet);
    setRoomSize(kInitialRoom);
    setDamp(kInitialDamp);
    setWidth(kInitialWidth);
    setMix(0.3f);
  }

  void processStereo(float inL, float inR, float &outL, float &outR) {
    float input = (inL + inR) * kFixedGain;
    float outLAccum = 0.0f;
    float outRAccum = 0.0f;

    // Parallel Combs
    for (int i = 0; i < kNumCombs; ++i) {
      outLAccum += combL[i].process(input);
      outRAccum += combR[i].process(input);
    }

    // Series Allpass
    for (int i = 0; i < kNumAllPass; ++i) {
      outLAccum = allpassL[i].process(outLAccum);
      outRAccum = allpassR[i].process(outRAccum);
    }

    // Wet/Dry Mix
    // Currently AudioEngine does mix outside, but user requested dry/wet inside
    // pedal logic? Usually sends are 100% wet, but if used as insert, we need
    // mix. Let's output wet only for now, and let AudioEngine mix it, or if
    // it's an insert, we mix here. Actually, AudioEngine adds result to bus. If
    // this is Global FX, it returns wet. We will return WET signal scaled by
    // mMix.

    // Final character filtering (6kHz Low Pass) to remove metallic artifacts
    mFilterStateL += 0.2f * (outLAccum * gain * mMix - mFilterStateL);
    mFilterStateR += 0.2f * (outRAccum * gain * mMix - mFilterStateR);

    outL = mFilterStateL;
    outR = mFilterStateR;
  }

  // Support processStereoWet for the specific AudioEngine call
  void processStereoWet(float inL, float inR, float &outL, float &outR) {
    // This calculates pure wet signal
    float input = (inL + inR) * kFixedGain;
    float outLAccum = 0.0f;
    float outRAccum = 0.0f;

    for (int i = 0; i < kNumCombs; ++i) {
      outLAccum += combL[i].process(input);
      outRAccum += combR[i].process(input);
    }
    for (int i = 0; i < kNumAllPass; ++i) {
      outLAccum = allpassL[i].process(outLAccum);
      outRAccum = allpassR[i].process(outRAccum);
    }
    outL =
        outLAccum * gain; // No mix scaling here, caller handles? Or consistent.
    outR = outRAccum * gain;
  }

  void setParameters(float size, float damp, float width, float mix,
                     float preDelay, float sr) {
    setRoomSize(size);
    setDamp(damp);
    setWidth(width); // Used as modDepth previously? Map appropriately.
    setMix(mix);
  }

  // Mappings
  void setSize(float v) { setRoomSize(v); }
  void setDamping(float v) { setDamp(v); }
  void setModDepth(float v) { setWidth(v); } // Reuse 3rd param for Width
  void setMix(float v) { mMix = v; }
  void
  setPreDelay(float v) { /* Not in standard Freeverb, ignore for stability */ }
  void setSampleRate(float sr) { /* Tuning is fixed, maybe scale later */ }
  void
  setType(int t) { /* Freeverb is just one type, maybe alter size limits? */ }

  // Internal Setters
  void setRoomSize(float value) {
    roomsize = (value * kScaleRoom) + kOffsetRoom;
    for (int i = 0; i < kNumCombs; ++i) {
      combL[i].setFeedback(roomsize);
      combR[i].setFeedback(roomsize);
    }
  }
  void setDamp(float value) {
    damp = value * kScaleDamp;
    for (int i = 0; i < kNumCombs; ++i) {
      combL[i].setDamp(damp);
      combR[i].setDamp(damp);
    }
  }
  void setWidth(float value) { width = value; }
  void setWet(float value) { wet = value; }
  void clear() {
    for (int i = 0; i < kNumCombs; ++i) {
      combL[i].clear();
      combR[i].clear();
    }
    for (int i = 0; i < kNumAllPass; ++i) {
      allpassL[i].clear();
      allpassR[i].clear();
    }
  }

private:
  float gain = 1.0f;
  float roomsize, damp;
  float wet, width;
  float mMix = 0.5f;
  float mFilterStateL = 0.0f;
  float mFilterStateR = 0.0f;

  Comb combL[kNumCombs];
  Comb combR[kNumCombs];
  AllPass allpassL[kNumAllPass];
  AllPass allpassR[kNumAllPass];
};

#endif // GALACTIC_REVERB_H
