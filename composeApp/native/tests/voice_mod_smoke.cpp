// Phase 4 per-voice modulation smoke test.
// Build from the repository root:
//   clang++ -std=c++17 -O2 -IcomposeApp/native composeApp/native/tests/voice_mod_smoke.cpp \
//     composeApp/native/AudioEngineCore.cpp -o /tmp/voice_mod_smoke && /tmp/voice_mod_smoke

#include "engines/SubtractiveEngine.h"
#include "engines/FmEngine.h"
#include "engines/WavetableEngine.h"
#include "engines/SamplerEngine.h"
#include "AudioEngineCore.h"

#include <cmath>
#include <cstdio>
#include <vector>

static int failures = 0;

static void check(bool ok, const char *what) {
  std::printf("%s  %s\n", ok ? "PASS" : "FAIL", what);
  if (!ok) failures++;
}

static float rms(const std::vector<float> &v) {
  double s = 0.0;
  for (float x : v) s += (double)x * x;
  return (float)std::sqrt(s / (double)(v.empty() ? 1 : v.size()));
}

static float maxDiff(const std::vector<float> &a, const std::vector<float> &b) {
  float m = 0.0f;
  size_t n = std::min(a.size(), b.size());
  for (size_t i = 0; i < n; ++i) m = std::max(m, std::fabs(a[i] - b[i]));
  return m;
}

// Zero-crossing frequency estimate over the second half of the buffer.
static float zcFreq(const std::vector<float> &v, float sr) {
  size_t start = v.size() / 2;
  int crossings = 0;
  for (size_t i = start + 1; i < v.size(); ++i) {
    if ((v[i - 1] < 0.0f && v[i] >= 0.0f) || (v[i - 1] >= 0.0f && v[i] < 0.0f))
      crossings++;
  }
  float seconds = (float)(v.size() - start) / sr;
  return (crossings * 0.5f) / seconds;
}

template <typename E>
static std::vector<float> render(E &e, int frames) {
  std::vector<float> l(frames, 0.0f), r(frames, 0.0f);
  e.render(l.data(), r.data(), frames);
  return l;
}

static std::vector<float> renderCore(AudioEngineCore &core, int frames) {
  std::vector<float> interleaved(frames * 2, 0.0f);
  core.render(interleaved.data(), frames);
  std::vector<float> l(frames, 0.0f);
  for (int i = 0; i < frames; ++i) l[i] = interleaved[i * 2];
  return l;
}

template <typename Factory>
static void testEngine(const char *name, Factory makeEngine, int yDest) {
  const float SR = 48000.0f;
  const int FRAMES = 8192;

  std::printf("\n== %s ==\n", name);

  // A mod aimed at a note that is not sounding must not leak.
  {
    auto a = makeEngine();
    a->setSampleRate(SR);
    a->triggerNote(67, 100);
    auto clean = render(*a, FRAMES);

    auto b = makeEngine();
    b->setSampleRate(SR);
    b->setModRouting(0, 12.0f, yDest, 1.0f);
    b->triggerNote(67, 100);
    b->setVoiceMod(60, 1.0f, 1.0f); // 60 is silent
    auto leaked = render(*b, FRAMES);

    check(maxDiff(clean, leaked) < 1e-7f,
          "mod on a silent note does not leak into the sounding voice");
  }

  // Two voices, mod applied to one, then the other: both must change and the
  // two results must differ.
  {
    auto base = makeEngine();
    base->setSampleRate(SR);
    base->triggerNote(60, 100);
    base->triggerNote(67, 100);
    auto baseBuf = render(*base, FRAMES);

    auto m60 = makeEngine();
    m60->setSampleRate(SR);
    m60->setModRouting(0, 12.0f, yDest, 1.0f);
    m60->triggerNote(60, 100);
    m60->triggerNote(67, 100);
    m60->setVoiceMod(60, 1.0f, 1.0f);
    auto m60Buf = render(*m60, FRAMES);

    auto m67 = makeEngine();
    m67->setSampleRate(SR);
    m67->setModRouting(0, 12.0f, yDest, 1.0f);
    m67->triggerNote(60, 100);
    m67->triggerNote(67, 100);
    m67->setVoiceMod(67, -1.0f, -1.0f);
    auto m67Buf = render(*m67, FRAMES);

    check(maxDiff(baseBuf, m60Buf) > 1e-3f, "mod on note 60 changes the mix");
    check(maxDiff(baseBuf, m67Buf) > 1e-3f, "mod on note 67 changes the mix");
    check(maxDiff(m60Buf, m67Buf) > 1e-3f,
          "mod on 60 and mod on 67 produce different mixes (per-voice routing)");
  }

  // Snap-back: clearAllVoiceMod must restore the exact unmodulated output.
  {
    auto a = makeEngine();
    a->setSampleRate(SR);
    a->triggerNote(60, 100);
    a->triggerNote(67, 100);
    auto baseBuf = render(*a, FRAMES);

    auto b = makeEngine();
    b->setSampleRate(SR);
    b->setModRouting(0, 12.0f, yDest, 1.0f);
    b->triggerNote(60, 100);
    b->triggerNote(67, 100);
    b->setVoiceMod(60, 0.7f, 0.8f);
    b->setVoiceMod(67, -0.4f, -0.6f);
    b->clearAllVoiceMod();
    auto snapped = render(*b, FRAMES);

    check(maxDiff(baseBuf, snapped) < 1e-7f,
          "clearAllVoiceMod snaps both voices back to the base render");
  }
}

static void testBendRange() {
  const float SR = 48000.0f;
  const int FRAMES = 8192;
  std::printf("\n== Subtractive bend range ==\n");

  auto plain = std::make_unique<SubtractiveEngine>();
  plain->setSampleRate(SR);
  plain->triggerNote(60, 100);
  auto base = render(*plain, FRAMES);
  float f0 = zcFreq(base, SR);

  auto bent = std::make_unique<SubtractiveEngine>();
  bent->setSampleRate(SR);
  bent->setModRouting(0, 12.0f, 1, 1.0f);
  bent->triggerNote(60, 100);
  bent->setVoiceMod(60, 1.0f, 0.0f);
  auto bendBuf = render(*bent, FRAMES);
  float f1 = zcFreq(bendBuf, SR);

  std::printf("     base=%.1f Hz bent=%.1f Hz ratio=%.3f\n", f0, f1,
              f0 > 1.0f ? f1 / f0 : 0.0f);
  check(f0 > 1.0f && f1 / f0 > 1.8f && f1 / f0 < 2.2f,
        "x=+1 with a 12 st range bends one octave up");

  auto snap = std::make_unique<SubtractiveEngine>();
  snap->setSampleRate(SR);
  snap->setModRouting(0, 12.0f, 1, 1.0f);
  snap->triggerNote(60, 100);
  snap->setVoiceMod(60, 1.0f, 0.0f);
  snap->clearVoiceMod(60);
  auto snapBuf = render(*snap, FRAMES);
  check(maxDiff(base, snapBuf) < 1e-7f, "clearVoiceMod restores the unbent pitch");
}

static void testCoreRefCount() {
  const float SR = 48000.0f;
  const int FRAMES = 8192;
  std::printf("\n== AudioEngineCore refcount ==\n");

  AudioEngineCore core;
  core.updateSampleRate(SR);
  core.setEngineType(0, 0); // Subtractive
  core.setTrackVolume(0, 0.8f);
  for (int i = 0; i < 18; ++i) core.setFxEnabled(i, false);

  // Let the command queue settle and the startup gate expire.
  std::vector<float> scratch(FRAMES * 2, 0.0f);
  for (int i = 0; i < 4; ++i) core.render(scratch.data(), FRAMES);

  core.setPadModRouting(0, 0, 12.0f, 1, 1.0f, true);
  core.triggerNote(0, 60, 100);
  auto base = renderCore(core, FRAMES);
  float f0 = zcFreq(base, SR);

  core.beginVoicePadMod(0, 60);
  core.beginVoicePadMod(0, 60);
  core.setVoicePadMod(0, 60, 1.0f, 0.0f);
  auto modded = renderCore(core, FRAMES);
  float f1 = zcFreq(modded, SR);

  core.endVoicePadMod(0, 60); // refcount 2 -> 1
  auto stillModded = renderCore(core, FRAMES);
  float f2 = zcFreq(stillModded, SR);

  core.endVoicePadMod(0, 60); // refcount 1 -> 0
  auto released = renderCore(core, FRAMES);
  float f3 = zcFreq(released, SR);

  std::printf("     base=%.1f modded=%.1f still=%.1f released=%.1f\n", f0, f1,
              f2, f3);
  check(f0 > 1.0f && f1 / f0 > 1.8f && f1 / f0 < 2.2f,
        "core applies the per-voice bend");
  check(f0 > 1.0f && f2 / f0 > 1.8f && f2 / f0 < 2.2f,
        "one endVoicePadMod keeps the mod (refcount > 0)");
  check(f0 > 1.0f && f3 / f0 > 0.85f && f3 / f0 < 1.15f,
        "the final endVoicePadMod releases the bend");
}

static void testCoreTranspose() {
  const float SR = 48000.0f;
  const int FRAMES = 8192;
  std::printf("\n== AudioEngineCore per-track transpose ==\n");

  AudioEngineCore core;
  core.updateSampleRate(SR);
  core.setEngineType(0, 0);
  core.setTrackVolume(0, 0.8f);
  core.setTrackTranspose(0, 12);
  for (int i = 0; i < 18; ++i) core.setFxEnabled(i, false);

  std::vector<float> scratch(FRAMES * 2, 0.0f);
  for (int i = 0; i < 4; ++i) core.render(scratch.data(), FRAMES);

  core.setPadModRouting(0, 0, 12.0f, 1, 1.0f, true);
  core.triggerNote(0, 60, 100); // sounds at 72 after transpose
  auto base = renderCore(core, FRAMES);
  float f0 = zcFreq(base, SR);

  core.beginVoicePadMod(0, 60); // UI note, not the sounding note
  core.setVoicePadMod(0, 60, 1.0f, 0.0f);
  auto modded = renderCore(core, FRAMES);
  float f1 = zcFreq(modded, SR);

  std::printf("     base=%.1f modded=%.1f ratio=%.3f\n", f0, f1,
              f0 > 1.0f ? f1 / f0 : 0.0f);
  check(f0 > 1.0f && f1 / f0 > 1.8f && f1 / f0 < 2.2f,
        "per-voice mod resolves the track transpose to the sounding voice");
}

int main() {
  testEngine("Subtractive", [] { return std::make_unique<SubtractiveEngine>(); }, 1);
  testEngine("FM", [] { return std::make_unique<FmEngine>(); }, 151);
  testEngine("Wavetable", [] { return std::make_unique<WavetableEngine>(); }, 450);
  testEngine("Sampler", [] {
    auto e = std::make_unique<SamplerEngine>();
    std::vector<float> sine(48000);
    for (size_t i = 0; i < sine.size(); ++i)
      sine[i] = 0.5f * std::sin(2.0 * M_PI * 220.0 * (double)i / 48000.0);
    e->loadSample(sine);
    e->setParameter(320, 0.4f); // Loop
    return e;
  }, 302);

  testBendRange();
  testCoreRefCount();
  testCoreTranspose();

  std::printf("\n%s (%d failure%s)\n", failures == 0 ? "OK" : "FAILED",
              failures, failures == 1 ? "" : "s");
  return failures == 0 ? 0 : 1;
}
