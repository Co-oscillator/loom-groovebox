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
    resetToDefaults();
  }

  void resetToDefaults() {
    setAlgorithm(0);
    mFeedback = 0.0f;
    mBrightness = 1.0f;
    mDetune = 0.0f;
    mFeedbackDrive = 0.0f;
    for (int i = 0; i < 6; ++i) {
      mOpLevels[i] = (i == 0) ? 0.8f : 0.0f;
      mOpRatios[i] = 1.0f;
      mOpAttack[i] = 0.01f;
      mOpDecay[i] = 0.2f;
      mOpSustain[i] = 0.7f;
      mOpRelease[i] = 0.3f;
    }
    // Update operators in all voices
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
    // Implement algorithm setting logic here
    // This will involve setting mCarrierMask and mActiveMask based on the
    // algorithm For now, a simple placeholder:
    mAlgorithm = alg;
    // Example: Algorithm 0 (DX7 Algo 5)
    if (alg == 0) {
      mCarrierMask = (1 << 0); // Op 0 is carrier
      mActiveMask = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) |
                    (1 << 5); // All active
    }
    // Example: Algorithm 1 (DX7 Algo 1)
    else if (alg == 1) {
      mCarrierMask = (1 << 0) | (1 << 3); // Op 0 and 3 are carriers
      mActiveMask = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) |
                    (1 << 5); // All active
    }
    // Example: Algorithm 2 (All parallel carriers)
    else if (alg == 2) {
      mCarrierMask = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) |
                     (1 << 5); // All carriers
      mActiveMask = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) |
                    (1 << 5); // All active
    }
    // Example: Algorithm 3 (Stacked modulators)
    else if (alg == 3) {
      mCarrierMask = (1 << 0); // Op 0 is carrier
      mActiveMask = (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3) | (1 << 4) |
                    (1 << 5); // All active
    }
    // Add more algorithms as needed
  }

  // -----------------------------------------------------------------------------
  // FM PRESET LIBRARY
  // -----------------------------------------------------------------------------
  // Call this function with a preset ID (0-23) to load a classic FM sound.
  // -----------------------------------------------------------------------------

  void loadPreset(int presetId) {
    // Reset Globals
    mFeedback = 0.0f;
    mCutoff = 0.5f;
    mResonance = 0.0f;
    mVelSens = 0.6f;
    mActiveMask =
        63; // Enable all operators by default (levels control silence)
    mCarrierMask =
        1; // Default carrier (User can adjust or we can map per algo)

    // Default Macros (Neutral)
    mBrightness = 1.0f;
    mDetune = 0.0f;
    mFeedbackDrive = 0.0f;

    // Helper lambda for quick Op setup
    // op(index, level, ratio, A, D, S, R)
    auto op = [&](int i, float l, float r, float a, float d, float s,
                  float rel) {
      setOpLevel(i, l);
      setOpRatio(i, r);
      setOpADSR(i, a, d, s, rel);
    };

    switch (presetId) {
    case 0:            // BRASS (Classic 80s Synth Brass)
      setAlgorithm(3); // Stacked Modulators
      mFeedback = 0.15f;
      mBrightness = 1.2f;
      op(0, 1.0f, 1.00f, 0.05f, 0.2f, 0.7f, 0.3f); // Carrier
      op(1, 0.8f, 1.00f, 0.04f, 0.2f, 0.6f, 0.3f); // Carrier 2
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);  // Off
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);  // Off
      op(4, 0.6f, 1.00f, 0.03f, 0.3f, 0.0f, 0.2f); // Modulator (Bright attack)
      op(5, 0.7f, 2.00f, 0.02f, 0.2f, 0.0f, 0.2f); // Modulator (Transient)
      break;

    case 1:            // STRINGS (Warm Analog-ish Pad)
      setAlgorithm(2); // Parallel Carriers
      mDetune = 0.3f;  // Heavy detune for chorus effect
      mBrightness = 0.8f;
      op(0, 0.9f, 1.00f, 0.5f, 0.5f, 0.8f, 1.2f);
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.9f, 1.00f, 0.6f, 0.6f, 0.8f, 1.2f);
      op(3, 0.3f, 2.00f, 0.5f, 0.3f, 0.0f, 1.0f); // Subtle harmonic
      op(4, 0.9f, 1.00f, 0.4f, 0.4f, 0.8f, 1.2f);
      op(5, 0.2f, 3.00f, 0.1f, 1.0f, 0.0f, 1.0f); // High sheen
      break;

    case 2: // ORCHESTRA (Tutti Hit)
      setAlgorithm(1);
      mFeedback = 0.1f;
      op(0, 1.0f, 1.00f, 0.05f, 0.3f, 0.6f, 0.5f);
      op(1, 0.6f, 2.00f, 0.05f, 0.2f, 0.0f, 0.4f);
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.8f, 1.00f, 0.08f, 0.3f, 0.5f, 0.6f);
      op(4, 0.5f, 1.00f, 0.02f, 0.1f, 0.0f, 0.3f);
      op(5, 0.4f, 4.00f, 0.01f, 0.2f, 0.0f, 0.3f);
      break;

    case 3:            // PIANO (Clean Digital Grand)
      setAlgorithm(1); // Modulator Stacks
      mVelSens = 0.8f; // Dynamic
      op(0, 1.0f, 1.00f, 0.005f, 1.5f, 0.0f, 0.4f); // Body
      op(1, 0.4f, 4.00f, 0.005f, 0.3f, 0.0f, 0.3f); // Wire
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.9f, 1.00f, 0.005f, 1.0f, 0.0f, 0.4f); // Body 2
      op(4, 0.3f, 7.00f, 0.002f, 0.2f, 0.0f, 0.3f); // Hammer
      op(5, 0.2f, 14.0f, 0.001f, 0.1f, 0.0f, 0.2f); // Click
      break;

    case 4:             // E PIANO (Classic DX7 Rhodes)
      setAlgorithm(0);  // Serial/Algo 5 style
      mFeedback = 0.2f; // Key for the "Bark"
      op(0, 1.0f, 1.00f, 0.01f, 2.0f, 0.0f, 0.5f);
      op(1, 0.6f, 1.00f, 0.01f, 1.5f, 0.0f, 0.4f); // Detuner
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.7f, 14.0f, 0.005f, 0.1f, 0.0f, 0.2f); // Bell tines
      op(5, 0.5f, 1.00f, 0.005f, 0.5f, 0.0f, 0.3f); // Body mod
      break;

    case 5: // GUITAR (Nylon/Pluck)
      setAlgorithm(0);
      mFeedback = 0.15f;
      op(0, 1.0f, 1.00f, 0.005f, 0.4f, 0.0f, 0.1f);
      op(1, 0.5f, 2.00f, 0.005f, 0.2f, 0.0f, 0.1f); // Octave harmonic
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.3f, 3.00f, 0.005f, 0.1f, 0.0f, 0.1f);
      op(5, 0.4f, 1.00f, 0.01f, 0.2f, 0.0f, 0.1f);
      break;

    case 6: // BASS (Solid Bass / Lately Bass)
      setAlgorithm(3);
      mFeedback = 0.3f;                            // Gritty
      mFeedbackDrive = 0.4f;                       // Saturate the low end
      op(0, 1.0f, 0.50f, 0.01f, 0.4f, 0.6f, 0.3f); // Sub Osc
      op(1, 0.8f, 1.00f, 0.01f, 0.2f, 0.0f, 0.2f); // Pluck Mod
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.7f, 1.50f, 0.005f, 0.1f, 0.0f, 0.1f); // Click
      op(5, 0.6f, 1.00f, 0.01f, 0.3f, 0.0f, 0.2f);
      break;

    case 7:            // ORGAN (Drawbar / Hammond)
      setAlgorithm(2); // Algorithm 32 (All carriers)
      mCutoff = 1.0f;  // Open filter
      op(0, 1.0f, 0.50f, 0.05f, 0.0f, 1.0f, 0.05f); // 16'
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.8f, 1.00f, 0.05f, 0.0f, 1.0f, 0.05f); // 8'
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.6f, 1.50f, 0.05f, 0.0f, 1.0f, 0.05f); // 5 1/3'
      op(5, 0.5f, 2.00f, 0.05f, 0.0f, 1.0f, 0.05f); // 4'
      break;

    case 8: // PIPES (Church Organ)
      setAlgorithm(2);
      mDetune = 0.2f;
      op(0, 1.0f, 0.50f, 0.1f, 0.1f, 1.0f, 0.5f);
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.9f, 1.00f, 0.1f, 0.1f, 1.0f, 0.5f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.7f, 2.00f, 0.1f, 0.1f, 1.0f, 0.5f);
      op(5, 0.4f, 4.00f, 0.1f, 0.1f, 1.0f, 0.5f); // Whistle
      break;

    case 9: // HARPSICHORD
      setAlgorithm(2);
      op(0, 1.0f, 1.00f, 0.01f, 1.5f, 0.0f, 0.2f);
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.8f, 2.00f, 0.01f, 1.2f, 0.0f, 0.2f); // Octave up
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.6f, 4.00f, 0.01f, 0.8f, 0.0f, 0.2f);  // High brilliance
      op(5, 0.5f, 8.00f, 0.005f, 0.3f, 0.0f, 0.1f); // Pluck noise
      break;

    case 10: // CLAV (Funky)
      setAlgorithm(0);
      mFeedback = 0.25f;
      op(0, 1.0f, 1.00f, 0.005f, 0.6f, 0.0f, 0.1f);
      op(1, 0.6f, 1.00f, 0.005f, 0.3f, 0.0f, 0.1f);
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.7f, 2.00f, 0.005f, 0.2f, 0.0f, 0.1f); // Bite
      op(5, 0.6f, 6.00f, 0.005f, 0.1f, 0.0f, 0.1f); // Sting
      break;

    case 11: // VIBE (Vibraphone)
      setAlgorithm(1);
      mDetune = 0.1f; // Tremolo feel
      op(0, 1.0f, 1.00f, 0.01f, 2.0f, 0.0f, 0.5f);
      op(1, 0.3f, 4.00f, 0.01f, 0.5f, 0.0f, 0.3f); // Metallic ring
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.9f, 1.00f, 0.01f, 2.0f, 0.0f, 0.5f);
      op(4, 0.2f, 10.0f, 0.005f, 0.2f, 0.0f, 0.2f);
      op(5, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      break;

    case 12: // MARIMBA
      setAlgorithm(0);
      op(0, 1.0f, 1.00f, 0.005f, 0.4f, 0.0f, 0.1f);
      op(1, 0.7f, 3.50f, 0.005f, 0.2f, 0.0f, 0.1f); // Wood tone
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(5, 0.5f, 9.75f, 0.002f, 0.1f, 0.0f, 0.1f); // Mallet hit
      break;

    case 13: // KOTO
      setAlgorithm(0);
      op(0, 1.0f, 1.00f, 0.01f, 0.5f, 0.0f, 0.1f);
      op(1, 0.6f, 2.00f, 0.01f, 0.3f, 0.0f, 0.1f);
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.5f, 5.00f, 0.005f, 0.2f, 0.0f, 0.1f);
      op(5, 0.4f, 1.00f, 0.01f, 0.3f, 0.0f, 0.1f); // Feedback source
      mFeedback = 0.2f;
      break;

    case 14: // FLUTE
      setAlgorithm(0);
      op(0, 1.0f, 1.00f, 0.2f, 0.0f, 1.0f, 0.2f); // Slow attack (breath)
      op(1, 0.4f, 1.00f, 0.1f, 0.0f, 1.0f, 0.2f); // Breath noise body
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.3f, 2.00f, 0.05f, 0.2f, 0.0f, 0.1f); // Initial chiff
      op(5, 0.3f, 0.50f, 0.1f, 0.0f, 1.0f, 0.2f);  // Sub octave breath
      break;

    case 15: // TUBULAR BELLS
      setAlgorithm(2);
      // Non-integer ratios create inharmonic bell tones
      op(0, 1.0f, 1.00f, 0.01f, 3.0f, 0.0f, 1.0f);
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.8f, 1.50f, 0.01f, 2.5f, 0.0f, 1.0f); // Fifth
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.7f, 3.56f, 0.01f, 2.0f, 0.0f, 1.0f); // Dissonant high
      op(5, 0.6f, 4.22f, 0.01f, 1.5f, 0.0f, 1.0f); // Clang
      break;

    case 16: // VOICE (Solo)
      setAlgorithm(3);
      op(0, 1.0f, 1.00f, 0.2f, 0.3f, 0.8f, 0.3f);
      op(1, 0.6f, 1.00f, 0.2f, 0.3f, 0.6f, 0.3f); // Formant 1
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.5f, 2.00f, 0.2f, 0.3f, 0.5f, 0.3f); // Formant 2
      op(5, 0.4f, 5.00f, 0.1f, 0.2f, 0.0f, 0.2f); // Sibilance
      break;

    case 17: // VOICES (Choir)
      setAlgorithm(2);
      mDetune = 0.4f;
      op(0, 0.9f, 1.00f, 1.0f, 1.0f, 0.8f, 1.0f); // Slow wash
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.9f, 1.00f, 1.2f, 1.0f, 0.8f, 1.0f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.6f, 1.00f, 0.8f, 1.0f, 0.6f, 1.0f);
      op(5, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      break;

    case 18: // CALLIOPE (Carnival)
      setAlgorithm(2);
      mDetune = 0.3f; // Out of tune
      op(0, 1.0f, 1.00f, 0.1f, 0.0f, 1.0f, 0.2f);
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.8f, 2.00f, 0.1f, 0.0f, 1.0f, 0.2f); // Octave
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.6f, 4.00f, 0.1f, 0.0f, 1.0f, 0.2f);  // High pipe
      op(5, 0.7f, 1.00f, 0.05f, 0.2f, 0.0f, 0.1f); // Chiff (wind)
      break;

    case 19: // OBOE
      setAlgorithm(0);
      op(0, 1.0f, 1.00f, 0.1f, 0.0f, 1.0f, 0.1f);
      op(1, 0.7f, 2.00f, 0.1f, 0.0f, 1.0f, 0.1f); // Nasal harmonic
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.3f, 4.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(5, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      break;

    case 20: // BASSOON
      setAlgorithm(0);
      op(0, 1.0f, 1.00f, 0.05f, 0.0f, 1.0f, 0.2f);
      op(1, 0.8f, 1.00f, 0.05f, 0.0f, 1.0f, 0.2f); // Self mod for saw wave
      mFeedback = 0.2f;
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.3f, 3.00f, 0.05f, 0.0f, 1.0f, 0.2f); // Woody hollow
      op(5, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      break;

    case 21: // XYLOPHONE
      setAlgorithm(0);
      op(0, 1.0f, 1.00f, 0.002f, 0.2f, 0.0f, 0.1f);
      op(1, 0.8f, 3.00f, 0.002f, 0.15f, 0.0f, 0.1f); // Overtone
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(5, 0.6f, 8.00f, 0.001f, 0.05f, 0.0f, 0.1f); // Hard strike
      break;

    case 22: // BELLS (Church)
      setAlgorithm(2);
      // Deep, rich, inharmonic
      op(0, 1.0f, 0.50f, 0.05f, 4.0f, 0.0f, 1.0f); // Sub Fundamental
      op(1, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(2, 0.9f, 1.00f, 0.05f, 3.0f, 0.0f, 1.0f); // Fundamental
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.6f, 1.41f, 0.05f, 2.0f, 0.0f, 1.0f); // Tritone
      op(5, 0.5f, 2.72f, 0.05f, 1.0f, 0.0f, 1.0f); // High Clang
      break;

    case 23: // SYNTH LEAD (Square Wave-ish)
      setAlgorithm(0);
      mFeedback = 0.4f;
      mFeedbackDrive = 0.5f; // Saturated feedback = Square wave
      op(0, 1.0f, 1.00f, 0.01f, 0.0f, 1.0f, 0.2f);
      op(1, 0.5f, 1.00f, 0.01f, 0.2f, 0.0f, 0.2f); // Edge
      op(2, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(3, 0.0f, 1.00f, 0.1f, 0.1f, 0.0f, 0.1f);
      op(4, 0.3f, 2.00f, 0.01f, 0.1f, 0.5f, 0.2f); // Octave
      op(5, 0.4f, 1.00f, 0.01f, 0.0f, 1.0f, 0.2f); // Feeder
      break;
    }

    // Reset all voice states to apply these new parameters immediately
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
    if (!mIgnoreNoteFrequency) {
      v.frequency = 440.0f * powf(2.0f, (note - 69) / 12.0f);
    } else {
      v.frequency = mFrequency;
    }

    // Sync operator parameters
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
    v.pitchEnvDecay = 0.005f; // Hardcoded fast decay for drum-like click
  }

  void releaseNote(int note) {
    for (auto &v : mVoices) {
      if (v.active && v.note == note) {
        for (auto &op : v.operators)
          op.release();
      }
    }
  }

  void setFilter(float cutoff) { mCutoff = cutoff; }
  void setResonance(float res) { mResonance = res; }
  void setCarrierMask(int mask) { mCarrierMask = mask; }
  void setActiveMask(int mask) { mActiveMask = mask; }
  void setFeedback(float v) { mFeedback = v; }
  void setUseEnvelope(bool use) {
    mUseEnvelope = use;
    for (auto &v : mVoices)
      if (v.active)
        for (auto &op : v.operators)
          op.setUseEnvelope(use);
  }
  void setPitchSweep(float amount) { mPitchSweepAmount = amount; }

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
      setFeedback(value * value);
    else if (id == 4)
      mVelSens = value;
    else if (id == 5)
      mBrightness = value * 2.0f;
    else if (id == 6) {
      mDetune = value;
      for (auto &v : mVoices) {
        if (v.active) {
          for (int i = 0; i < 6; ++i) {
            float ratio = mOpRatios[i] + (i * mDetune * 0.05f);
            v.operators[i].setFrequency(v.frequency, ratio, mSampleRate);
          }
        }
      }
    } else if (id == 7)
      mFeedbackDrive = value;

    // UI Standard IDs (150-199)
    else if (id == 150)
      setAlgorithm((int)(value * 8));
    else if (id == 151)
      setFilter(value);
    else if (id == 152)
      setResonance(value);
    else if (id == 153)
      setCarrierMask((int)value);
    else if (id == 154)
      setFeedback(value);
    else if (id == 155)
      setActiveMask((int)value);
    else if (id == 156)
      mVelSens = value; // Check UI binding if needed
    else if (id == 157)
      mBrightness = value * 2.0f;
    else if (id == 158) { // Detune Duplicate
      mDetune = value;
      for (auto &v : mVoices) {
        if (v.active) {
          for (int i = 0; i < 6; ++i) {
            float ratio = mOpRatios[i] + (i * mDetune * 0.05f);
            v.operators[i].setFrequency(v.frequency, ratio, mSampleRate);
          }
        }
      }
    } else if (id == 159)
      mFeedbackDrive = value;

    // Operator Params (UI IDs 160+)
    else if (id >= 160) {
      int opIdx = (id - 160) / 6;
      int subId = (id - 160) % 6;
      if (opIdx >= 0 && opIdx < 6) {
        switch (subId) {
        case 0:
          setOpLevel(opIdx, value);
          break;
        case 1:
          setOpADSR(opIdx, 0.001f + (value * value * value), mOpDecay[opIdx],
                    mOpSustain[opIdx], mOpRelease[opIdx]);
          break;
        case 2:
          setOpADSR(opIdx, mOpAttack[opIdx],
                    0.001f + (value * value * value * 5.0f), mOpSustain[opIdx],
                    mOpRelease[opIdx]);
          break;
        case 3:
          setOpADSR(opIdx, mOpAttack[opIdx], mOpDecay[opIdx], value,
                    mOpRelease[opIdx]);
          break;
        case 4:
          setOpADSR(opIdx, mOpAttack[opIdx], mOpDecay[opIdx], mOpSustain[opIdx],
                    0.001f + (value * value * value * 5.0f));
          break;
        case 5: {
          float raw = value * 16.0f;
          float nearest = round(raw);
          float dist = fabs(raw - nearest);
          float finalRatio = (dist < 0.15f) ? std::max(0.5f, nearest) : raw;
          setOpRatio(opIdx, finalRatio);
          break;
        }
        }
      }
    }

    // Legacy / Internal 10-69 logic kept for compatibility if needed
    else if (id >= 10 && id < 70) {
      int op = (id - 10) / 10;
      int sub = (id - 10) % 10;
      if (sub == 0)
        setOpLevel(op, value);
      else if (sub == 1)
        setOpADSR(op, 0.001f + (value * value * value), mOpDecay[op],
                  mOpSustain[op], mOpRelease[op]);
      else if (sub == 2)
        setOpADSR(op, mOpAttack[op], 0.001f + (value * value * value * 5.0f),
                  mOpSustain[op], mOpRelease[op]);
      else if (sub == 3)
        setOpADSR(op, mOpAttack[op], mOpDecay[op], value, mOpRelease[op]);
      else if (sub == 4)
        setOpADSR(op, mOpAttack[op], mOpDecay[op], mOpSustain[op],
                  0.001f + (value * value * value * 5.0f));
      else if (sub == 5) {
        float raw = value * 16.0f;
        float nearest = round(raw);
        float dist = fabs(raw - nearest);
        float finalRatio = (dist < 0.15f) ? std::max(0.5f, nearest) : raw;
        setOpRatio(op, finalRatio);
      }
    }

    else if (id >= 100 && id <= 103) {
      // Global ADSR Control
      for (int i = 0; i < 6; ++i) {
        if (id == 100)
          setOpADSR(i, 0.001f + (value * value * value), mOpDecay[i],
                    mOpSustain[i], mOpRelease[i]);
        else if (id == 101)
          setOpADSR(i, mOpAttack[i], 0.001f + (value * value * value * 5.0f),
                    mOpSustain[i], mOpRelease[i]);
        else if (id == 102)
          setOpADSR(i, mOpAttack[i], mOpDecay[i], value, mOpRelease[i]);
        else if (id == 103)
          setOpADSR(i, mOpAttack[i], mOpDecay[i], mOpSustain[i],
                    0.001f + (value * value * value * 5.0f));
      }
    }
  }

  void updateSampleRate(float sr) { mSampleRate = sr; }

  bool isActive() const {
    for (const auto &v : mVoices)
      if (v.active) {
        for (const auto &op : v.operators)
          if (op.isActive())
            return true;
      }
    return false;
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

      float velModScale = 1.0f - (mVelSens * (1.0f - v.amplitude));

      // Feedback Drive: Saturate feedback path
      float fbSignal = (v.op5FeedbackHistory + v.lastOp5Out) * 0.5f;
      if (mFeedbackDrive > 0.0f) {
        fbSignal *= (1.0f + mFeedbackDrive * 3.0f);
        fbSignal = std::tanh(fbSignal);
      }
      float fbIn = fbSignal * mFeedback;

      float dt = 1.0f / mSampleRate;
      float modScale = mBrightness;

      float pitchMod = 1.0f + (v.pitchEnv * mPitchSweepAmount);
      v.pitchEnv *= (1.0f - v.pitchEnvDecay);

      float out = 0.0f;
      float o0, o1, o2, o3, o4, o5;

      // Algo logic
      switch (mAlgorithm) {
      case 0: // SERIAL (Default)
        o5 = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o4 = v.operators[4].nextSample(o5 * modScale, pitchMod) * velModScale;
        o3 = v.operators[3].nextSample(o4 * modScale, pitchMod) * velModScale;
        o2 = v.operators[2].nextSample(o3 * modScale, pitchMod) * velModScale;
        o1 = v.operators[1].nextSample(o2 * modScale, pitchMod) * velModScale;
        o0 = v.operators[0].nextSample(o1 * modScale, pitchMod);
        break;
      case 1: // PIANO
        o5 = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o4 = v.operators[4].nextSample(o5 * modScale, pitchMod) * velModScale;
        o3 = v.operators[3].nextSample(o4 * modScale, pitchMod);
        o2 = v.operators[2].nextSample(0.0f, pitchMod) * velModScale;
        o1 = v.operators[1].nextSample(o2 * modScale, pitchMod) * velModScale;
        o0 = v.operators[0].nextSample(o1 * modScale, pitchMod);
        break;
      case 2: // ORGAN
        o5 = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o4 = v.operators[4].nextSample(o5 * modScale, pitchMod);
        o3 = v.operators[3].nextSample(0.0f, pitchMod) * velModScale;
        o2 = v.operators[2].nextSample(o3 * modScale, pitchMod);
        o1 = v.operators[1].nextSample(0.0f, pitchMod) * velModScale;
        o0 = v.operators[0].nextSample(o1 * modScale, pitchMod);
        break;
      case 3: // BRASS
        o5 = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o4 = v.operators[4].nextSample(o5 * modScale, pitchMod);
        o3 = v.operators[3].nextSample(o5 * modScale, pitchMod);
        o2 = v.operators[2].nextSample(o5 * modScale, pitchMod);
        o1 = v.operators[1].nextSample(o5 * modScale, pitchMod);
        o0 = v.operators[0].nextSample(o5 * modScale, pitchMod);
        break;
      default: // ALL PARALLEL
        o5 = v.operators[5].nextSample(fbIn, pitchMod) * velModScale;
        o4 = v.operators[4].nextSample(0.0f, pitchMod) * velModScale;
        o3 = v.operators[3].nextSample(0.0f, pitchMod) * velModScale;
        o2 = v.operators[2].nextSample(0.0f, pitchMod) * velModScale;
        o1 = v.operators[1].nextSample(0.0f, pitchMod) * velModScale;
        o0 = v.operators[0].nextSample(0.0f, pitchMod) * velModScale;
        break;
      }

      // Respect Carrier Mask for Output summing
      float finalOut = 0.0f;
      int carrierCount = 0;
      float ops[6] = {o0, o1, o2, o3, o4, o5};
      for (int i = 0; i < 6; ++i) {
        if (mCarrierMask & (1 << i)) {
          finalOut += ops[i];
          carrierCount++;
        }
      }
      if (carrierCount > 0)
        out = finalOut / sqrtf((float)carrierCount);
      else
        out = o0; // Fallback to Op 0 if mask is empty for some reason

      v.op5FeedbackHistory = v.lastOp5Out;
      v.lastOp5Out = o5;

      float sample = out * v.amplitude;

      // Filter
      float targetFreq = 20.0f * powf(1000.0f, mCutoff);
      if (targetFreq > mSampleRate * 0.45f)
        targetFreq = mSampleRate * 0.45f;
      float f = 2.0f * sin(M_PI * targetFreq / mSampleRate);
      float q = 1.0f - mResonance * 0.95f;
      if (q < 0.05f)
        q = 0.05f;

      v.svf_H = sample - v.svf_L - q * v.svf_B;
      v.svf_B = f * v.svf_H + v.svf_B;
      v.svf_L = f * v.svf_B + v.svf_L;

      mixedOutput += std::tanh(v.svf_L) * 1.5f;

      // Check if voice has finished playing (all carriers silent or envelopes
      // done) Simplification: Check if all operators are idle or silent? Since
      // we don't know the exact envelope state from here easily without
      // iterating. Let's assume if the total output energy is tiny AND release
      // phase is active? Better: ask operators.
      bool allDone = true;
      if (mUseEnvelope) {
        for (auto &op : v.operators) {
          if (op.isActive()) {
            allDone = false;
            break;
          }
        }
      } else {
        // No envelope? Then it's infinite until note off?
        // But releaseNote triggers Release phase. FOr gate mode, it snaps off.
        // If no envelope, we rely on level?
        // Let's assume if mUseEnvelope is false, we don't auto-deactivate
        // unless we implement a Gate check. But typical usage is Envelopes.
        allDone = false; // Careful not to kill sustained notes.
      }

      if (allDone && mUseEnvelope) {
        v.active = false;
      }
    }

    if (activeCount > 1)
      mixedOutput *= 0.7f;
    return mixedOutput;
  }

  std::vector<float> mOpLevels, mOpRatios;
  std::vector<float> mOpAttack, mOpDecay, mOpSustain, mOpRelease;

private:
  std::vector<Voice> mVoices;
  int mAlgorithm = 0, mCarrierMask = 1, mActiveMask = 63;
  float mFeedback = 0.0f, mVelSens = 0.5f, mCutoff = 0.5f, mResonance = 0.0f;
  float mBrightness = 1.0f, mDetune = 0.0f, mFeedbackDrive = 0.0f;
  float mFrequency = 440.0f;
  float mPitchSweepAmount = 0.0f;
  float mSampleRate = 44100.0f;
  bool mUseEnvelope = true;
};

#endif
