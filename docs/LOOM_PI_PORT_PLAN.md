# Porting Loom Pi Performance Features into Loom Groovebox

Status: proposal / scope confirmed 2026-10-24
Target: Loom Groovebox 3.2.x
Source of inspiration: `~/Documents/Code Projects/Loom Pi`

## Confirmed scope

Agreed with the maintainer on 2026-10-24:

- **Arp clone:** ship **both** actions — *Copy settings* and *Clone live*.
- **Split pads:** 20/20 layout is in, including **per-voice modulation** rather than only the global fallback.
- **MIDI:** per-device configuration + persistence, clock in/out, pitch-bend input, and **aftertouch → pad mod**.
- **Pad modes:** add the extra layouts/pad modes (16/24/40, pad modes) alongside 20/20.
- **Launchkey:** DAW-mode integration and knob banks are in scope.
- **Drum row learn wizard:** build it for general use **and** wire it into the Launchkey workflow.
- **Melody tracker:** explicitly **last priority**.
- **Loom Pi itself:** do a bug-fix pass there too (see §5.1), rather than only porting around the bugs.

Remaining open questions are listed in §7.

---

## 0. Architecture reality check

The two apps are not code-compatible, so this is a **re-implementation of behaviour**, not a copy/paste of UI code.

| | Loom Groovebox | Loom Pi |
|---|---|---|
| UI | Kotlin Compose Multiplatform (`composeApp/src/commonMain`) | C++ / LVGL 9 (`src/ui/UIManager.cpp`, 21k lines) |
| Audio | `composeApp/native/AudioEngineCore.cpp` via JNI | `src/AudioEngine.cpp` |
| MIDI | Kotlin platform APIs (`MidiManager`) + `MidiRouter` | C++ `MidiInput.h` (CoreMIDI / ALSA) |
| Platforms | Android + Desktop | Raspberry Pi / Linux + macOS |
| Arp state | Kotlin `ArpConfig` **and** native `Arpeggiator` (two sources of truth) | Native `Arpeggiator` only |

Important build note: `app/build.gradle` points CMake at `composeApp/native/CMakeLists.txt`. The tree at `app/src/main/cpp/` is a **stale duplicate** (identical `Arpeggiator.h`, divergent `AudioEngine.cpp`). Any engine/arp change should be made in `composeApp/native/` only, and the duplicate should be deleted or clearly marked to avoid drift.

---

## 1. Split-view touchscreen pads (20/20 multitrack performance)

### What Loom Pi does

A fourth Play layout ("20/20") replaces the single 4x4 grid with **two 5x4 banks side by side, 40 pads total**:

- Left bank and right bank each have their own **track**, **octave**, and **X/Y modulation destination + intensity**.
- Root, scale, chord type and voice-link mode remain global to both banks.
- Pads are note-mapped per bank: `48 + root + octave*12`, ascending bottom-left to top-right across 5 scale octaves.
- Every pad carries `(bank, track, note)` so touches route to the right synth without changing the selected track.
- X axis = pitch bend, Y axis = modulation, applied either per-voice (poly) or globally (glitch).
- Drum and chop tracks get special note maps.

### Loom Groovebox changes

#### 1.1 State model

Add a first-class performance layout instead of overloading `GridMode`:

```kotlin
data class SplitZone(
    val trackIndex: Int = 0,
    val octave: Int = 0,                 // -3..+3
    val modXTrack: Int = 0,
    val modXDest: Int? = 1,              // parameter id / macro
    val modXIntensity: Float = 1f,
    val modYTrack: Int = 0,
    val modYDest: Int? = 2,
    val modYIntensity: Float = 1f
)

// In GrooveboxState
val playLayout: PlayLayout = PlayLayout.GRID,   // GRID, SPLIT_20_20
val splitLeft: SplitZone = SplitZone(trackIndex = 0, octave = 0),
val splitRight: SplitZone = SplitZone(trackIndex = 1, octave = 1),
val voiceLinkPoly: Boolean = true
```

Persist these in `StateUtils.kt` and project files. Loom Pi does **not** persist split state at all; fix that on the Loom side from day one.

#### 1.2 Play screen layout

In `PlayingScreen.kt`:

- Add a layout cycle button next to the grid-mode control: `4x4 → 6x6 → Tonnetz → Keys → 20/20`.
- For `SPLIT_20_20`, render a `Row` with two `PerformanceBank` composables and a 2dp divider.
- Each bank is a `Column`:
  - 36–44dp header: track dropdown, octave `[-] OCT [+]`, `X: dest` selector + intensity, `Y: dest` selector + intensity.
  - `5 rows × 4 columns` of `PlayingPad`s.
- Keep Root / Scale / Chord / Voice-Link controls in the shared top bar.
- Bank header border and root-note pads use the assigned track's engine colour.
- Do **not** reproduce Loom Pi's broken drum mapping (`60 + i % 8` over 20 pads). Use Loom's existing drum maps: FM Drum = 16 distinct voices, Analog Drum = 5–6 voices, and grey out unused pads. For chop mode, clamp to the real slice count.

#### 1.3 Touch routing refactor (the critical piece)

`PlayingPad` currently hard-codes `latestState.selectedTrackIndex` for:

- `nativeLib.triggerNote(...)` / `releaseNote(...)`
- `setPitchBend`
- `setParameter` for the Y-axis
- `setPadMod`
- live parameter-lock recording

Change `PlayingPad` to accept:

```kotlin
targetTrack: Int = state.selectedTrackIndex,
originNote: Int = note,
modX: ModRoute? = null,
modY: ModRoute? = null,
zone: SplitZone? = null
```

Then:

- Trigger on `targetTrack`, pass the **root note as `originNote`** so chord voices share a voice-mod identity.
- Y-axis uses `modY.track` / `modY.dest`, not the selected track.
- Write parameter locks to `targetTrack`.
- Keep the existing snap-back behaviour on release (Loom already restores the initial value, which is better than Loom Pi's sticky modulation).

Compose already gives each pad its own `pointerInput`, so **simultaneous pads work**; the missing part is that all pads currently target the same track. No global pointer manager is needed. One caveat to test: two fingers on the *same* pad (currently one note, no refcount).

#### 1.4 Engine support

Loom currently exposes only:

- `setPitchBend(track, semitones)`
- `setPadMod(track, value)` — one global scalar per track

For the split layout to be musically useful, port the **per-voice modulation** contract from Loom Pi:

```cpp
void setVoicePadMod(int trackIndex, int originNote, float normX, float normY);
void setPadModRouting(int trackIndex, int xDest, float xInt,
                      int yDest, float yInt, bool polyMode);
```

- Add `modX`, `modY`, `hasVoiceMod`, `originNote` to each voice in Subtractive, FM, Wavetable and Sampler engines.
- Apply modulation in the per-voice parameter evaluation (`evalMod`-style), not by overwriting the global parameter.
- Keep a global `setParameter` fallback for cross-track destinations and for engines without per-voice support (Granular, SoundFont, Audio In, MIDI, drums).

Phasing: **v1 can ship split pads with global modulation** (each bank still targets its own track, so two tracks are playable); **per-voice modulation is confirmed in scope** and lands in Phase 4 for independent bends/mods per finger.

#### 1.5 Bugs from Loom Pi to avoid

1. Split state not persisted; routing not re-applied on screen entry.
2. Changing a bank's track does not retarget its mod routing.
3. Chord extensions (`+14`) not clamped to 127; release blindly sends all chord intervals.
4. Drum/chop banks address non-existent voices.
5. Two fingers on one pad retrigger/release each other.
6. Global mod path writes **absolute** values (edge of pad = parameter max) instead of an offset or an initial-value-relative delta.
7. Destination labels show the bank's note track rather than the actual mod target track.
8. Voice-link toggle not propagated to both banks.
9. Split unreachable from drum tracks even though banks may target drums.

---

## 2. Clone a running arpeggiator from one track to another

### What Loom Pi does

- `AudioEngine::copyArpeggiator(src, dest)` deep-copies the whole `Arpeggiator` via `copyFrom`, then forces the destination track active and `mArpCountdown = 0` so it starts on the next block.
- Copies: mode, octaves, inversion, strum, latch, mutated flag, held notes, sequence, rhythm lanes, gate lengths, random seed, chord-progression settings, probability, weird, rate/speed multipliers, step/note index.
- Does **not** copy: RNG state, triplet/clock multiplier, pending note-offs, MIDI routing. Source keeps running.
- UI: a "Copy To…" dropdown at the bottom of the Arp screen.

### Loom Groovebox changes

Loom's arp is split between Kotlin and native, so a clone must update both.

#### 2.1 Native

- Add `Arpeggiator::copyFrom(const Arpeggiator&)` to `composeApp/native/Arpeggiator.h` (exclude `mRng`, scratch iteration fields).
- Add `AudioEngineCore::copyArpeggiator(int src, int dest)`:
  - Lock `mLock`.
  - Deep-copy arp state.
  - Copy `mArpRate`, `mArpDivisionMode`, `mLiveHeldNotes`, `mPhysicallyHeldNoteCount`, `mArpTriplet`, `mClockMultiplier`.
  - Flush/stop destination voices if it was already playing, then `mArpCountdown = 0`.
  - Force destination `isTrackEnabled = true`.
- Expose JNI `nCopyArpeggiator(src, dest)` in both `native-lib.cpp` and `native-lib-desktop.cpp`; add to `NativeLib` expect/actual.

#### 2.2 Kotlin

- In `GrooveboxViewModel` / state handler: copy `ArpConfig` into `state.tracks[dest].arpConfig` and push it with `setArpConfig(...)`, plus `setArpRate` and `setArpStrum`.
- Copy `TrackState.clockMultiplier`, and optionally `mutatedNotes` if the arp is in mutated mode.
- Rebuild/refresh `ArpSettingsSheet` after copying (Loom Pi leaves stale widgets).

#### 2.3 UX

Add to `ArpSettingsSheet`:

- **"COPY TO →"** dropdown (T1…T8), disabled for the current track.
- Two explicit actions (confirmed):
  - **Copy settings** — transfer configuration only; destination starts from its next gesture.
  - **Clone live** — also transfer held/latched notes and immediately retrigger, for layering a running arp onto a second sound.
- Show a brief confirmation/toast, since this is destructive to the destination's arp.
- Optional: long-press a track's ARP button to open a "copy from" menu.

#### 2.4 Semantics to decide

- Keep the source running (Loom Pi behaviour, good for layering) or stop it (handoff)? Recommend keeping it running, with the two action modes above.

#### 2.5 Status: implemented (v3.2.19)

Shipped in `composeApp`:

- `Arpeggiator::copyFrom(other, live)` and `Arpeggiator::reseed()`.
  - `live == true` copies the RNG stream, held notes, sequence, chord progression, step/note index and multipliers **without** calling `updateSequence()`, so the cloned stream stays in phase.
  - `live == false` reseeds, clears held/sequence/chord state, resets position, then rebuilds the sequence.
- `AudioEngineCore::copyArpeggiator(src, dest, live)` flushes the destination's sounding voices, copies rate/division/triplet/clock multiplier, resets `mArpCountdown` and the physical-hold count, and tracks live clones in `mArpCloneSource[8]` so note-offs on the source stop non-latched clones. Latched clones keep running.
- JNI `nCopyArpeggiator(src, dest, live)` in both native entry points, wired through `NativeLib` expect/actual.
- UI: **COPY SETTINGS** / **CLONE LIVE** buttons in `ArpSettingsSheet`, each opening an 8-track destination picker (source excluded, disabled tracks marked). Clone live re-enables the destination track in Kotlin and native.
- Kotlin state copies `arpConfig`, `mutatedNotes` and `clockMultiplier`; native copies rate/division/triplet/clock multiplier too, so both sides stay in sync.

Known follow-ups: `StateUtils` re-pushes `setArpConfig` for every track on project load, which resets a live clone (expected, but worth documenting); no confirmation toast yet.
- Copy RNG seed for deterministic "random" patterns, or keep divergent streams? Recommend copying the seed in "Copy settings" so both sound identical at first.
- Destination currently holding notes → send note-offs before applying the clone.

---

## 3. More sophisticated MIDI configuration

### Gap analysis

Loom today:

- `MidiManager` opens **all** Android MIDI devices and broadcasts outgoing messages to every open port. Desktop is similar (all transmitters/receivers).
- `MidiRouter` supports per-track `midiInChannel` (OMNI or 1–16), note remapping, CC learn, transport, aftertouch is parsed but not routed.
- `SettingsScreen` shows per-track input channel + a MIDI monitor.
- No per-device settings, no device selection, no per-device mute/ignore/channel, no persistence of device configuration, no clock input, no program change, no pitch-bend input.

Loom Pi adds a per-device model:

```cpp
struct MidiDeviceSettings {
  name; client; port;
  sendChannel;      // 1-16, -1 All, 0 Off
  receiveChannel;   // 1-16, -1 All, 0 Off
  muteOutgoing;
  ignoreIncoming;
  isInput; isOutput;
};
```

…but it **fails to persist these settings**, only enumerates sources on macOS, and has no clock sync. Port the model, not the bugs.

### Plan

#### 3.1 Device registry (Kotlin, both platforms)

Introduce a platform-neutral model:

```kotlin
data class MidiDeviceInfo(
    val id: String,            // stable: name + vendor + port, not index
    val name: String,
    val vendor: String,
    val isInput: Boolean,
    val isOutput: Boolean
)

data class MidiDeviceConfig(
    val deviceId: String,
    val sendChannel: Int = -1,      // -1 All, 0 Off
    val receiveChannel: Int = -1,
    val muteOutgoing: Boolean = false,
    val ignoreIncoming: Boolean = false,
    val enabled: Boolean = true
)
```

- Refactor `MidiManager` to keep a `Map<deviceId, Ports>` rather than flat lists.
- Tag every inbound message with its source device ID.
- Send only to enabled output ports, applying `sendChannel` / `muteOutgoing`.
- Keep the existing loopback filter, but scope it per device.

#### 3.2 Routing

- `MidiRouter.processMidiMessage(message, sourceDeviceId, state)`.
- Apply `receiveChannel` / `ignoreIncoming` before parsing.
- Keep per-track channel routing; add an explicit **per-track source-device filter** (optional).
- Add an **All / specific device** selector for the MIDI OUT engine per track.

#### 3.3 Settings UI

New **MIDI Devices** section in `SettingsScreen.kt`:

- List of detected devices with direction badges (IN / OUT / IN+OUT).
- Per device: Send Ch, Receive Ch, Mute Out, Ignore In, Enable.
- Hotplug updates the list; settings survive replug by matching stable IDs.
- Per-device activity dot (last message timestamp).
- Keep the existing monitor, ideally with a device filter.

#### 3.4 Persistence

- Save `List<MidiDeviceConfig>` plus global MIDI options in the app settings JSON (`StateUtils` / `PersistenceManager`).
- Do **not** put transient device state in project files; device config is a global preference. Track-level channel/out-device assignments stay in the project.
- Add a schema version and default-migration path.

#### 3.5 MIDI feature gaps worth closing at the same time

| Feature | Loom | Loom Pi | Recommendation |
|---|---|---|---|
| Per-device channels/mute/ignore | no | yes (not persisted) | **Port + persist** |
| MIDI clock **out** | flag exists (`sendMidiClock`), appears unwired | no | **Wire it up** |
| MIDI clock **in** / external sync | no | no | **Add** (high value for split performance) |
| Transport start/stop/continue | partial | yes | Align |
| Song Position Pointer | no | no | Optional |
| Program change | no | no | Optional |
| Pitch bend input | no | no | **Add** to `MidiRouter` |
| Channel pressure / aftertouch | parsed only | yes (→ pad mod) | **Add** routing |
| Poly aftertouch / MPE | no | no | Later; needs per-voice plumbing from §1.4 |
| SysEx | basic parser | no | Leave out unless needed |
| MIDI 2.0 / UMP | no | no | Out of scope |

Recommended ordering: per-device config + persistence first, then clock input, then aftertouch/pitch-bend routing (aftertouch becomes much more useful once per-voice modulation exists).

---

## 4. Other Loom Pi features you may be forgetting

Ranked by value-to-effort for Loom:

### High value, moderate effort

1. **MIDI clock sync (in and out) + SPP.**
   Loom already has dead `sendMidiClock` / `sendMidiStartStop` fields. Makes external gear and DAW sync possible; very complementary to split performance.
2. **Aftertouch / channel pressure → pad modulation.**
   Loom parses it and drops it. Loom Pi routes it to the active FX pad or `setPadMod`. Small change, big expressive win, especially with per-voice mod.
3. **Per-voice pad modulation** (also required for a good split view).
   Loom's Y-axis is a global track parameter write; Loom Pi stores `modX/modY` per voice. This is what makes multitouch expressive rather than "last finger wins".
4. **More pad layouts and pad modes.**
   Loom Pi offers 16 / 24 / 40 pad layouts and six pad modes (Keyboard, FX, Scales, FM Drum, Analogue Drum, Slices). Loom has 4x4, 6x6, Tonnetz, Mac Keys. The 24/40 layouts are cheap once split banks exist.
5. **Arp rhythm depth.**
   Loom Pi has **7 rhythm lanes × 16 steps with per-lane gate lengths**; Loom has 3 lanes and shared gates. Extending to 7 lanes is mostly UI + a wider `rhythms` list.

### Medium value, larger effort

6. **Hardware controller integration (Launchkey DAW mode).**
   DAW-mode init, knob banks (3 pages), pad/transport interception, LED feedback. Loom has EMP16 LED feedback but no generic controller layer. Best done after the MIDI device registry.
7. **Rich CC mapping (up to 40 knobs + 40 faders per track, remap dialog).**
   Loom has 4 strips + 4 knobs + 4 macros. Loom Pi supports 8 tracks × 40 slots with CC/channel/parameter tables and a remap dialog. A good target for a "MIDI mapping" screen.
8. **Virtual MIDI engine controls (24 knobs + 8 faders as CC).**
   Loom Pi maps parameters 2400–2431 to outgoing CC. Useful for controlling external gear from Loom's UI.
9. **Drum pad number-row learn / pad wizard.**
   Guided assignment of MIDI pad notes and drum rows. Nice onboarding, moderate UI work.
10. **Melody tracker / audio-to-MIDI.**
    Loom Pi has `MelodyTracker` (YIN-based pitch detection with HP/LP, confidence, speed, tolerance, sample source). Loom Groovebox has no melody tracker, though Loom FM-1 may. This is a standalone feature, not a small port; treat as its own project.

### Low priority / already covered

11. Chord progression generator — both apps have it; compare Loom Pi's mood/complexity options before porting anything.
12. Routing matrix / FX sends — both have equivalents (`RoutingScreen.kt`, `RoutingMatrix.h`).
13. Scale library — Loom Pi has 21 scales; Loom has a scale enum. Worth diffing the lists.
14. Project save/load — Loom already saves projects; Loom Pi's `saveProject` is less complete.
15. Hardware display / SSD1306-style status screen — not applicable to a phone/desktop app.

---

## 5. Suggested delivery phases

| Phase | Scope | Risk | Notes |
|---|---|---|---|---|
| **0. Prep** | Delete/mark `app/src/main/cpp` duplicate; extract `PlayingPad` target-track/mod params; add settings schema version | Low | Prevents engine drift |
| **1. Arp clone** | `copyFrom`, `copyArpeggiator`, JNI, Kotlin state sync, "Copy To" UI with **Copy settings** and **Clone live** | Low | Independently shippable, immediate live-performance value |
| **1b. CPU budget** | Finish track disable, add FX enable mask + UI, performance profile, verify CPU drops | Medium | Directly addresses budget-tablet performance (§8) |
| **2. MIDI devices** | Device registry, per-device config UI, hardware presets, persistence, routing filters | Medium | Touches both platform MIDI implementations |
| **3. Split pads v1** | State, 20/20 layout, per-bank track/octave, global mod fallback, persistence | Medium | Does not require engine changes |
| **4. Per-voice mod** | `setVoicePadMod`, per-voice state in 4 engines, routing API | High | Unlocks true multitouch expression; needs audio-thread care |
| **5. MIDI expression** | Clock in/out, SPP, pitch-bend input, **aftertouch → pad mod** | Medium | Aftertouch depends on Phase 4 for full benefit |
| **6. Pad modes + drum row wizard** | 16/24/40 layouts, six pad modes, general drum-row learn wizard | Medium | Reuses the split-bank pad abstraction |
| **7. Launchkey** | DAW-mode init, 3 knob banks, pad/transport interception, LED feedback, wizard integration | High | Hardware-dependent; build on Phase 2 device registry |
| **8. Melody tracker** | Audio-to-MIDI (YIN, confidence, HP/LP, sample source) | High | Explicitly last |

Recommended first release: **Phase 1 + Phase 2**, then **Phase 3**, then **Phase 4**.

### 5.1 Parallel track: Loom Pi bug-fix pass

Rather than only working around Loom Pi's known defects, fix them upstream and cherry-pick the resulting design back:

1. Persist split-mode state (pad count, both banks, octave, mod routing, root/scale/chord) and re-apply routing on screen entry.
2. Retarget mod routing when a bank's track changes; propagate voice-link mode to both banks.
3. Clamp chord extensions; release only the intervals that were triggered.
4. Fix drum/chop bank note maps (no duplicate pads, no out-of-range slices).
5. Add per-finger refcounting so two fingers on one pad do not cancel each other.
6. Make global mod relative to the initial value, or add an explicit spring-back option.
7. Label mod destinations with the actual target track.
8. Allow entering split mode from drum tracks.
9. Persist `MidiDeviceSettings` (send/receive channel, mute, ignore, enable).
10. Enumerate MIDI destinations on macOS and set `isOutput` correctly.
11. Adopt stable device IDs instead of name-only matching.
12. Copy `mArpTriplet` / `mClockMultiplier` in `copyArpeggiator`; flush destination voices.
13. Rebuild arp widgets after a copy so the UI is not stale.
14. Add MIDI clock input (currently absent on both platforms).

---

## 6. Risks and testing notes

- **Two sources of truth for arp state** (Kotlin `ArpConfig` vs native `Arpeggiator`). Every clone/edit must update both or the UI and audio diverge.
- **Audio-thread locking.** Loom Pi's `copyArpeggiator` holds the engine mutex for the whole copy. Prefer building a snapshot on the UI thread and applying it in one short command on the audio thread.
- **Settings migration.** Adding device config and split state to saved state needs defaults for old projects; otherwise loading an old project resets the Play screen.
- **Multi-touch on Android.** Each pad's `pointerInput` handles one pointer; verify 4–6 simultaneous pads on a real device before shipping split mode.
- **Desktop parity.** The desktop `MidiManager` uses `javax.sound.midi`; per-device channel/mute filtering must be implemented there too, or hide the UI on desktop.
- **MIDI feedback loops.** Loom sends LED/CC data back to controllers; per-device `ignoreIncoming` and the existing loopback cache must both be respected.
- **Drum/chop edge cases** in split banks (see §1.5).
- **Latency/jank**: keep pad modulation calls off the Compose recomposition path (use the existing direct `nativeLib` calls, not state updates per touch-move).

---

## 7. Decisions

Resolved (2026-10-24):

1. **Arp clone semantics:** both *Copy settings* and *Clone live* ship. *Clone live* keeps the source running, layers, and **copies the RNG seed** so the clone matches exactly. *Copy settings* starts a **fresh random seed** so it can diverge.
2. **Split pads:** different tracks are the normal case, but assigning **the same track to both banks is allowed** — the two banks then act as separate octave ranges of one sound.
3. **MIDI device config:** **global preference**, not per project. Add named **hardware configuration presets** (e.g. "Home" vs "Live") that save/load the device list, channels, mute/ignore and routing.
4. **Desktop parity:** **Android is the priority.** Split pads are not required on macOS; MIDI routing/device configuration is. Keep a macOS follow-up list (§9).
5. **Loom Pi:** `main` stays the published default. Port generic features from `10inch-1280x800` into `main` deliberately (see `Loom Pi/PORT_FROM_10INCH.md`), rather than making the 10-inch line the default.
6. **Per-voice modulation, aftertouch, clock, pad modes, Launchkey, drum-row wizard:** in scope as previously confirmed.
7. **Melody tracker:** last priority.

---

## 8. Android CPU budget: disable tracks and unused FX

Motivation: Loom runs well on high-end devices but bogs down on budget tablets. The goal is to let the user spend CPU only where it is audible.

### 8.1 Track disable

The uncommitted v3.2.19 work already contains most of this:

- `TrackState.isDisabled`, `NativeLib.setTrackDisabled`, and a MixerView **Enable/Disable Track** action.
- The engine already skips disabled tracks in both the sequencer loop and the render loop (`if (track.isDisabled) continue`).

Gaps to close before calling it done:

- `applyModulations()` still iterates disabled tracks.
- `triggerNoteLocked()` does not reject notes for disabled tracks (MIDI/arp can still wake them).
- Arp/LFO/macro sources can still reference a disabled track; decide whether to mute or skip those sources.
- UI should block pad/sequence triggering and show a clear "OFF" state (partly present).
- Verify the disabled flag is pushed after project load and after track engine changes.

Expected saving is roughly linear in track count for synthesis work: 4 active tracks ≈ half the voice/DSP cost of 8, minus shared FX.

### 8.2 FX enable/disable

All 18 FX buses currently process unconditionally every block: overdrive, bitcrusher, HP/LP filter LFOs, chorus, phaser, tape wobble, delay, reverb, slicer, compressor, flanger, three filter pedals, tape echo, octaver and EQ5.

Plan:

- Add an atomic `mFxEnabledMask` (18 bits) to `AudioEngineCore`.
- Skip each FX's `processBlock` when its bit is off; skip bus clearing and track sends to disabled buses.
- Fade the bus out over a few ms on disable (and optionally clear state) to avoid clicks; on re-enable, either reset tails or preserve them deliberately.
- UI: a toggle per pedal in the Routing/FX screen, plus a compact **Performance** panel listing active FX and track states.
- Persist the mask in the project and expose it through JNI.

Expected saving: reverb, delay, chorus, EQ5, tape echo and the filter pedals are the expensive buses. Bypassing unused ones is one of the largest wins available on low-end hardware.

### 8.3 Additional cheap wins

- Idle/silence gating (partly present; Loom Pi's `7fd774d` is a useful reference).
- Reduce UI polling and avoid per-frame label formatting (Loom Pi `d2bcc29`).
- Increase control-rate decimation on a low-end profile.
- Add a **Performance profile** (High / Balanced / Low) that caps polyphony and disables the most expensive options.
- Per-track max polyphony / voice stealing.
- Let the user choose sample rate and buffer size where the platform allows it.

### 8.4 Acceptance criteria

- Eight tracks with all FX remain real-time on a budget tablet.
- Disabling four tracks produces a measurable CPU drop.
- Disabling unused FX produces a measurable CPU drop with no clicks or stuck tails.
- Track/FX enable states persist across project save/load.

---

## 9. macOS follow-up list (after Android)

Required on macOS:

- MIDI device registry, per-device configuration, and hardware configuration presets.
- MIDI clock in/out, pitch-bend input, aftertouch routing.
- Arp clone and the MIDI-learn improvements.

Not required on macOS:

- 20/20 split pads (not useful without a touchscreen).
- Launchkey hardware integration (unless specifically requested).

Also needs desktop parity work in `MidiManager` (`javax.sound.midi`): per-device channels, mute/ignore, and stable device IDs.

---
