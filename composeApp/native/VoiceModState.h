#ifndef VOICE_MOD_STATE_H
#define VOICE_MOD_STATE_H

/*
 * Per-voice performance modulation (Phase 4).
 *
 * A pad gesture (or a MIDI aftertouch/pitch-bend source routed per voice)
 * writes a normalised X/Y pair to the voice that is actually sounding, so
 * several fingers can bend/modulate independently.  The routing is stored
 * once per engine/track: X is a pitch-bend range in semitones, Y is a
 * parameter id plus an intensity.
 *
 * Engines read these values while evaluating their per-voice parameters;
 * they never overwrite the global parameter, so a released finger snaps
 * back without disturbing other voices.
 */
struct VoiceModState {
  bool active = false;
  float x = 0.0f; // -1..1, scaled by the routing's X range
  float y = 0.0f; // -1..1 delta, scaled by the routing's Y intensity
  int originNote = -1;

  void clear() {
    active = false;
    x = 0.0f;
    y = 0.0f;
    originNote = -1;
  }
};

struct VoiceModRouting {
  int xDest = -1;        // reserved (X is pitch bend on every engine)
  float xRange = 2.0f;   // semitones at |x| = 1
  int yDest = -1;        // parameter id the Y axis modulates
  float yIntensity = 1.0f;

  void reset() {
    xDest = -1;
    xRange = 2.0f;
    yDest = -1;
    yIntensity = 1.0f;
  }

  float bendSemitones(float x) const {
    return x * xRange;
  }
};

static inline float voiceModClamp01(float v) {
  return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
}

static inline float voiceModClamp(float v, float lo, float hi) {
  return v < lo ? lo : (v > hi ? hi : v);
}

#endif // VOICE_MOD_STATE_H
