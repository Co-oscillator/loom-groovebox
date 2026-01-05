#ifndef GALACTIC_REVERB_H
#define GALACTIC_REVERB_H

#include <algorithm>
#include <cmath>
#include <vector>

// --- Helper: Linear Interpolation for Modulation ---
inline float getInterpolated(const std::vector<float> &buffer, float pos) {
  int i = static_cast<int>(pos);
  float frac = pos - i;
  int size = buffer.size();
  float s0 = buffer[i % size];
  float s1 = buffer[(i + 1) % size];
  return s0 + frac * (s1 - s0);
}

// --- Helper: All-Pass Filter with Modulation support ---
class ModulatedAllPass {
public:
  void init(int size) {
    buffer.assign(size, 0.0f);
    writePos = 0;
  }

  float process(float input, float feedback) {
    float bufOut = buffer[writePos];
    float output = -input + bufOut;
    float newVal = input + (bufOut * feedback);

    // Anti-denormal
    if (std::abs(newVal) < 1.0e-15f)
      newVal = 0.0f;

    buffer[writePos] = newVal;
    writePos = (writePos + 1) % buffer.size();
    return output;
  }

  // Process with modulation (reads from a variable position)
  float processMod(float input, float feedback, float modOffset) {
    int size = buffer.size();
    // Read pos is writePos - delay + mod
    float readIdx = (float)writePos - (float)size + modOffset;
    while (readIdx < 0)
      readIdx += size;

    float bufOut = getInterpolated(buffer, readIdx);

    float output = -input + bufOut;
    float newVal = input + (bufOut * feedback);

    if (std::abs(newVal) < 1.0e-15f)
      newVal = 0.0f;

    buffer[writePos] = newVal;
    writePos = (writePos + 1) % size;
    return output;
  }

  void clear() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    writePos = 0;
  }

  std::vector<float> buffer;

private:
  int writePos = 0;
};

// --- Helper: Simple Delay ---
class SimpleDelay {
public:
  void init(int size) {
    buffer.assign(size, 0.0f);
    writePos = 0;
  }
  void write(float in) {
    buffer[writePos] = in;
    writePos = (writePos + 1) % buffer.size();
  }
  float readAt(int delayLen) {
    int idx = writePos - delayLen;
    if (idx < 0)
      idx += buffer.size();
    return buffer[idx];
  }
  void clear() {
    std::fill(buffer.begin(), buffer.end(), 0.0f);
    writePos = 0;
  }
  std::vector<float> buffer;
  int writePos = 0;
};

// --- THE ENGINE ---
class GalacticReverb {
public:
  GalacticReverb() { setSampleRate(44100.0f); }

  void setSampleRate(float sr) {
    currentSampleRate = sr;
    float scale = sr / 29761.0f; // Dattorro fixed Tunings are based on ~29.7k

    // Pre-delay
    preDelay.init((int)(sr * 0.5f)); // Max 500ms

    // Input Diffusers (Smears the transient immediately -> Plate sound)
    diffusers[0].init((int)(142 * scale));
    diffusers[1].init((int)(379 * scale));
    diffusers[2].init((int)(107 * scale));
    diffusers[3].init((int)(277 * scale));

    // The "Tank" (decay loop)
    tankLeftAP[0].init((int)(672 * scale));  // Modulated
    tankLeftDelay.init((int)(4453 * scale)); // Long delay
    tankLeftAP[1].init((int)(1800 * scale)); // Diffuser 1
    tankLeftAP[2].init((int)(3720 * scale)); // Diffuser 2

    tankRightAP[0].init((int)(908 * scale)); // Modulated
    tankRightDelay.init((int)(4217 * scale));
    tankRightAP[1].init((int)(2656 * scale));
    tankRightAP[2].init((int)(3163 * scale));
  }

  // PARAMS:
  // size: 0.0-1.0 (Decay length)
  // damp: 0.0-1.0 (High freq absorption)
  // modDepth: 0.0-1.0 (Wobbly/Spacey chorusing)
  // mix: 0.0-1.0
  // preDelayMs: 0-200
  void setParameters(float size, float damp, float modDepth, float mix,
                     float preDelayMs, float sr) {
    setSize(size);
    setDamping(damp);
    setModDepth(modDepth);
    setMix(mix);
    modRate = 0.002f; // Slow drift
    setPreDelay(preDelayMs);
  }
  // Helper method to clear state
  void clear() {
    // Clear helpers
    preDelay.clear();
    for (int i = 0; i < 4; ++i)
      diffusers[i].clear();
    for (int i = 0; i < 3; ++i) {
      tankLeftAP[i].clear();
      tankRightAP[i].clear();
    }
    tankLeftDelay.clear();
    tankRightDelay.clear();

    // Clear state
    tankLeftOut = 0.0f;
    tankRightOut = 0.0f;
    lpL = 0.0f;
    lpR = 0.0f;
    lfoPhase = 0.0f;
  }

  void setType(float type) {
    mType = (int)(type * 3.9f); // 0: Plate, 1: Room, 2: Hall, 3: Space
                                // Adjust decay / scaling based on type?
    // For now just store it for labels, can add logic later.
  }

  void setSize(float size) {
    decay = 0.5f + (0.48f * size);
  } // Max 0.98 to avoid infinite loop
  void setDamping(float damp) { damping = damp; }
  void setModDepth(float modDepth) { modAmount = modDepth * 20.0f; }
  void setMix(float mix) { dryWet = mix; }
  int getType() const { return mType; }
  void setPreDelay(float preDelayMs) {
    preDelayReadLen = (int)(preDelayMs * 0.001f * currentSampleRate);
    if (preDelayReadLen >= preDelay.buffer.size())
      preDelayReadLen = preDelay.buffer.size() - 1;
  }

  void processStereo(float inL, float inR, float &outL, float &outR) {
    // 1. Pre-Delay
    float monoIn = (inL + inR) * 0.5f;
    preDelay.write(monoIn);
    float input = preDelay.readAt(preDelayReadLen);

    // 2. Input Diffusion (Smear transients)
    // Series of 4 All-Pass filters
    float t = input;
    t = diffusers[0].process(t, 0.5f);
    t = diffusers[1].process(t, 0.5f);
    t = diffusers[2].process(t, 0.5f);
    t = diffusers[3].process(t, 0.5f);

    // 3. Update LFO for modulation
    lfoPhase += modRate;
    if (lfoPhase > 6.28318f)
      lfoPhase -= 6.28318f;
    float modL = std::sin(lfoPhase) * modAmount;
    float modR = std::cos(lfoPhase) * modAmount;

    // 4. The Tank (Figure-8 Loop)
    // Left Side
    float rt = input + (tankRightOut * decay); // Cross-feedback from Right

    // Modulated AllPass
    rt = tankLeftAP[0].processMod(rt, -0.5f, 40.0f + modL);

    // Long Delay + Damping
    tankLeftDelay.write(rt);
    float delayedL = tankLeftDelay.readAt(tankLeftDelay.buffer.size() - 1);

    // Lowpass (Damping)
    lpL = delayedL * (1.0f - damping) + (lpL * damping);
    if (std::abs(lpL) < 1.0e-15f)
      lpL = 0.0f; // Anti-denormal

    // Diffusers inside loop
    float dL = lpL * decay;
    dL = tankLeftAP[1].process(dL, 0.5f);
    float tankL = tankLeftAP[2].process(dL, 0.5f);

    tankLeftOut = tankL; // Store for cross-feed

    // Right Side (Mirror of Left)
    float lt = input + (tankLeftOut * decay); // Cross-feedback from Left

    lt = tankRightAP[0].processMod(lt, -0.5f, 40.0f + modR);

    tankRightDelay.write(lt);
    float delayedR = tankRightDelay.readAt(tankRightDelay.buffer.size() - 1);

    lpR = delayedR * (1.0f - damping) + (lpR * damping);
    if (std::abs(lpR) < 1.0e-15f)
      lpR = 0.0f; // Anti-denormal

    float dR = lpR * decay;
    dR = tankRightAP[1].process(dR, 0.5f);
    float tankR = tankRightAP[2].process(dR, 0.5f);

    tankRightOut = tankR;

    // 5. Output Taps (Stereo widening)
    // Dattorro taps are at specific points in the long delays
    float wetL = tankLeftDelay.readAt(tankLeftDelay.buffer.size() / 2) +
                 tankRightAP[1].buffer[tankRightAP[1].buffer.size() / 2] -
                 tankLeftAP[2].buffer[tankLeftAP[2].buffer.size() / 3];

    float wetR = tankRightDelay.readAt(tankRightDelay.buffer.size() / 2) +
                 tankLeftAP[1].buffer[tankLeftAP[1].buffer.size() / 2] -
                 tankRightAP[2].buffer[tankRightAP[2].buffer.size() / 3];

    outL = inL * (1.0f - dryWet) + wetL * dryWet;
    outR = inR * (1.0f - dryWet) + wetR * dryWet;
  }

  void processStereoWet(float inL, float inR, float &outWL, float &outWR) {
    float dummyL, dummyR;
    // We reuse the state-updating logic by calling a shared internal method
    // but for now, let's just extract the wet calculation logic.
    // (To keep it simple and avoid refactoring too much, I'll just copy the
    // taps)

    // 1. Pre-Delay
    float monoIn = (inL + inR) * 0.5f;
    preDelay.write(monoIn);
    float input = preDelay.readAt(preDelayReadLen);

    // 2. Input Diffusion
    float t = input;
    t = diffusers[0].process(t, 0.5f);
    t = diffusers[1].process(t, 0.5f);
    t = diffusers[2].process(t, 0.5f);
    t = diffusers[3].process(t, 0.5f);

    // 3. Update LFO
    lfoPhase += modRate;
    if (lfoPhase > 6.28318f)
      lfoPhase -= 6.28318f;
    float modL = std::sin(lfoPhase) * modAmount;
    float modR = std::cos(lfoPhase) * modAmount;

    // 4. The Tank
    float rt = input + (tankRightOut * decay);
    rt = tankLeftAP[0].processMod(rt, -0.5f, 40.0f + modL);
    tankLeftDelay.write(rt);
    float delayedL = tankLeftDelay.readAt(tankLeftDelay.buffer.size() - 1);
    lpL = delayedL * (1.0f - damping) + (lpL * damping);
    if (std::abs(lpL) < 1.0e-15f)
      lpL = 0.0f;
    float dL = lpL * decay;
    dL = tankLeftAP[1].process(dL, 0.5f);
    float tankL = tankLeftAP[2].process(dL, 0.5f);
    tankLeftOut = tankL;

    float lt = input + (tankLeftOut * decay);
    lt = tankRightAP[0].processMod(lt, -0.5f, 40.0f + modR);
    tankRightDelay.write(lt);
    float delayedR = tankRightDelay.readAt(tankRightDelay.buffer.size() - 1);
    lpR = delayedR * (1.0f - damping) + (lpR * damping);
    if (std::abs(lpR) < 1.0e-15f)
      lpR = 0.0f;
    float dR = lpR * decay;
    dR = tankRightAP[1].process(dR, 0.5f);
    float tankR = tankRightAP[2].process(dR, 0.5f);
    tankRightOut = tankR;

    // 5. Output Taps
    float wetL = tankLeftDelay.readAt(tankLeftDelay.buffer.size() / 2) +
                 tankRightAP[1].buffer[tankRightAP[1].buffer.size() / 2] -
                 tankLeftAP[2].buffer[tankLeftAP[2].buffer.size() / 3];

    float wetR = tankRightDelay.readAt(tankRightDelay.buffer.size() / 2) +
                 tankLeftAP[1].buffer[tankLeftAP[1].buffer.size() / 2] -
                 tankRightAP[2].buffer[tankRightAP[2].buffer.size() / 3];

    outWL = wetL * dryWet;
    outWR = wetR * dryWet;
  }

private:
  SimpleDelay preDelay;
  int preDelayReadLen = 0;

  // Input Diffusers
  ModulatedAllPass diffusers[4];

  // Tank components
  ModulatedAllPass tankLeftAP[3];
  SimpleDelay tankLeftDelay;
  ModulatedAllPass tankRightAP[3];
  SimpleDelay tankRightDelay;

  // State
  float decay = 0.5f;
  float damping = 0.5f;
  float dryWet = 0.3f;

  float tankLeftOut = 0.0f;
  float tankRightOut = 0.0f;
  float lpL = 0.0f;
  float lpR = 0.0f;
  int mType = 0;

  // Modulation
  float lfoPhase = 0.0f;
  float modRate = 0.001f;
  float modAmount = 5.0f;

  float currentSampleRate = 44100.0f;
};

#endif
