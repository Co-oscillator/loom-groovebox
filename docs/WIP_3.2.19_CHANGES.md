# Uncommitted Loom Groovebox changes (v3.2.19 WIP)

HEAD: `4fbdcd4` (v3.1.0 + branch cleanup). Working tree: 46 modified + 4 untracked source files. Build artifacts are now untracked.

This is mostly one coherent **v3.2.19 audio-performance refactor**, plus a parallel legacy/Pi tree and some debug scripts. Review before committing.

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

**Bugs to fix before committing:**
- `MainScreen` removed `updateCurrentStep(...)`, so the sequencer playhead may be frozen.
- `desktopMain/NativeLib.kt` is missing `actual fun setTrackProbability` (expect/actual compile error).

## 4. Android platform

- `app/build.gradle` points CMake at `composeApp/native/CMakeLists.txt` and enables `useLegacyPackaging`.
- `MainActivity` comments out `performanceManager.startSession()` (Pixel 10 boot crash); the parameter is passed but unused, so ADPF is effectively off.
- `NativeFileDialog` accepts `.syx`.
- `AudioPerformanceManager` adds `startSession(tids)`/`updateTids()` but `updateTids` is never called.
- `app/src/main/cpp` is the legacy duplicate tree; its `native-lib.cpp` has an unbalanced brace at line 108 and will not compile.

## 5. Build/config/scripts

- `composeApp/build.gradle.kts`: desktop `jvmTarget = 11`, `syncVersionKt` writes `Version.kt` into the source tree before every compile (CI churn risk).
- `version.properties`: 3.1.0/317 → 3.2.19/389.
- `fix_jni.py`: stale API, would generate non-compiling code.
- `scratch.py`: one-off brace checker.
- `setup_pi.sh`: **destructive** (`rm -rf "$HOME/.git"`) and hard-codes a Pi path.

## Recommended commit grouping

1. **build:** CMake repoint, lib rename, legacy packaging, version sync, bump 3.2.19.
2. **native core:** AudioEngine→Oboe host + AudioEngineCore split, JNI rename, DenormalDisabler, UISnapshot, atomics/wait-free render.
3. **dsp:** block-render APIs, polyBLEP, lock-free wavetable/granular, saturation change (call out audible changes).
4. **feat(ui):** track disable, track levels, native probability, multi-touch and record-button fixes.
5. **cleanup:** delete `app/src/main/cpp`, remove debug scripts, fix desktop `setTrackProbability`, restore playhead updates.

## Do not commit as-is

- `fix_jni.py`, `scratch.py`, `setup_pi.sh`.
- The legacy `app/src/main/cpp` tree (delete or move to a Pi branch).
- Anything with the frozen playhead or missing desktop `actual`.
