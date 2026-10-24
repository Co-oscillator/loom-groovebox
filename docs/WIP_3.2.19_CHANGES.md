# Loom Groovebox v3.2.19 changes (committed)

Status: **committed and pushed** on `main` as `ec1f313`, `1b2af58`, `e434618`, `1516382`.

This was one coherent **v3.2.19 audio-performance refactor**, plus a parallel legacy/Pi tree and some debug scripts.

## Commit layout

| Commit | Contents |
|---|---|
| `ec1f313` | build: CMake repoint, legacy JNI packaging, version 3.2.19 |
| `1b2af58` | chore: delete stale `app/src/main/cpp` duplicate |
| `e434618` | perf(native): Oboe host + lock-free core, block-render DSP |
| `1516382` | feat(ui): track disable, track levels, native probability, playhead sync |

Pre-commit fixes applied:

- Added desktop `actual fun setTrackProbability` + `nSetTrackProbability` JNI.
- Restored playhead sync in `pollEngineState()`.
- Deleted the broken legacy `app/src/main/cpp` tree.

Not committed (intentionally, still untracked): `scratch.py`, `setup_pi.sh` (destructive).

## 1. Native core refactor

- `composeApp/native/AudioEngine.cpp` (−4,427 lines) and `AudioEngine.h` (−492) remove the old god-class wrapper. `AudioEngine` is now only an Oboe host owning `std::unique_ptr<AudioEngineCore>`.
- `AudioEngineCore.cpp/.h` absorb the engine, convert hot state to atomics, remove `mLock` from `render()`, add `PARAM_PREVIEW`, `SET_TRACK_PROBABILITY`, `SET_TRACK_DISABLED`, pre-allocated scratch buffers, and a `UISnapshot` read path.
- `native-lib.cpp` renames JNI symbols `Java_..._x` → `..._nx`, routes through `engine->getCore()`, and adds `nSetTrackDisabled`, `nSetFocusedTrack`, `nSetTrackProbability`, `nGetTrackLevels`, `nGetAudioThreadTid`, `nGetIsPlaying/Recording`.
- New: `DenormalDisabler.h` (FTZ/FPCR RAII), `UISnapshot.h` (POD state snapshot).

**Risk:** `updateUISnapshot()` only copies params/steps for the focused track; startup sync may read stale/zero data.

## 2. DSP block rendering

- All engines gain `render(outL, outR, numFrames)`: FM, Subtractive, Wavetable, Granular, Analog Drum, EQ5.
- PolyBLEP oscillators, lock-free wavetable `shared_ptr`, granular "MediaTek-optimized" chunks, smoothed cutoff, control-rate decimation.
- `EnvelopeFollower` becomes atomic; `Adsr` release floor 1e-5; `Utils.h` replaces `fast_tanh` with a cubic and adds saturation inside `TSvf`.

**Risk:** audible timbre/aliasing changes. Needs A/B listening tests.

## 3. Kotlin state and UI

- Adds `TrackState.isDisabled` (the requested track-disable feature) and `GrooveboxState.trackLevels`.
- MixerView: Enable/Disable Track menu, greyed card, OFF chip.
- SequencerView: per-step probability loop replaced by `setTrackProbability`.
- PlayingScreen: multi-touch fix (`down.id`); ParametersScreen: record-button rewrite with 150 ms cool-off.
- StateUtils sanitizes/syncs `isDisabled`; Version.kt generated from `version.properties`.

**Bugs fixed before committing:**
- Playhead sync restored in `GrooveboxViewModel.pollEngineState()` via `getCurrentStep()`.
- `desktopMain/NativeLib.kt` now has `actual fun setTrackProbability` + JNI symbol.

## 4. Android platform

- `app/build.gradle` points CMake at `composeApp/native/CMakeLists.txt` and enables `useLegacyPackaging`.
- `MainActivity` comments out `performanceManager.startSession()` (Pixel 10 boot crash); the parameter is passed but unused, so ADPF is effectively off.
- `NativeFileDialog` accepts `.syx`.
- `AudioPerformanceManager` adds `startSession(tids)`/`updateTids()` but `updateTids` is never called.
- `app/src/main/cpp` (legacy duplicate tree) has been deleted; the build uses `composeApp/native`.

## 5. Build/config/scripts

- `composeApp/build.gradle.kts`: desktop `jvmTarget = 11`, `syncVersionKt` writes `Version.kt` into the source tree before every compile (CI churn risk).
- `version.properties`: 3.1.0/317 → 3.2.19/389.
- `fix_jni.py`: stale API, would generate non-compiling code.
- `scratch.py`: one-off brace checker.
- `setup_pi.sh`: **destructive** (`rm -rf "$HOME/.git"`) and hard-codes a Pi path.

## Commit grouping (as landed)

1. **build:** CMake repoint, legacy packaging, version sync, bump 3.2.19.
2. **cleanup:** delete `app/src/main/cpp`.
3. **native core + DSP:** AudioEngine→Oboe host + AudioEngineCore split, JNI rename, DenormalDisabler, UISnapshot, block-render DSP.
4. **feat(ui):** track disable, track levels, native probability, playhead sync, multi-touch and record-button fixes.

## Left untracked (not committed)

- `scratch.py` — one-off brace checker.
- `setup_pi.sh` — **destructive** (`rm -rf "$HOME/.git"`) and hard-codes a Pi path.
- `fix_jni.py` — tracked but left unmodified (stale API).

