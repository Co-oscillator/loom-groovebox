# Porting Loom Pi Performance Features into Loom Groovebox

Status: proposal / scope confirmed 2026-10-24; Phases 0–4 shipped 2026-10-25
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
  - 36–44dp header: track dropdown, octave `[-] OCT [+]`, `MOD` learn button and a `⋮` menu for poly/bend/intensity/destination.
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
void beginVoicePadMod(int trackIndex, int note);              // finger down
void setVoicePadMod(int trackIndex, int note, float normX, float normY);
void endVoicePadMod(int trackIndex, int note);                // finger up
void setPadModRouting(int trackIndex, int xDest, float xRange,
                      int yDest, float yIntensity, bool polyMode);
```

- Each voice in Subtractive, FM, Wavetable and Sampler carries `mod.active`,
  `mod.x`, `mod.y` and `mod.originNote` (`composeApp/native/VoiceModState.h`).
- Modulation is applied while evaluating the per-voice parameter, never by
  overwriting the global parameter, so released fingers snap back and other
  fingers are unaffected.
- `x` is normalised (-1..1) and multiplied by the routing's semitone range; `y`
  is a **signed delta** (-1..1) relative to the gesture start, which fixes the
  Loom Pi bug where the edge of the pad wrote an absolute parameter maximum.
- `begin`/`end` are refcounted per note (`Track::voiceModRef[128]`) so two
  pointers on the same note cannot cancel each other; the last `end` clears the
  voice mod.
- The global `setPadMod` / `setParameter` path remains the fallback for engines
  without per-voice support (Granular, SoundFont, Audio In, MIDI, drums), for
  tracks whose arp is on (the pad feeds the arp, not a sounding voice), and for
  destinations that are inherently global (macros, FX, global parameters). The
  UI only takes the per-voice path when the destination is in the engine's
  supported list, so a learned macro never silently moves a fallback parameter.

Implemented in Phase 4; see §11.1 for the routing semantics and UI.

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
- `AudioEngineCore::copyArpeggiator(src, dest, live)` flushes the destination's sounding voices, copies rate/division/triplet/clock multiplier, resets `mArpCountdown` and the physical-hold count, and tracks live clones in `mArpCloneSource[8]`. While a clone link exists, note-ons and note-offs on the source are mirrored to the clone, so later gestures keep layering. Non-latched clones stop when the source gesture ends; latched clones keep running.
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

#### 3.6 Status: device registry + presets implemented (v3.2.19)

Shipped:

- **Model** (`midi/MidiConfig.kt`): `MidiDeviceInfo` with stable IDs, `MidiDeviceConfig` (enabled, send/receive channel, mute-out, ignore-in), `MidiHardwarePreset`, `MidiSettings`.
- **Registry**: both `MidiManager` actuals now keep a `deviceId -> ports` registry instead of flat lists, tag every inbound message with its source device id, and handle hotplug add/remove (desktop rescans every 3 s, Android uses the device callback). Stable IDs are derived from manufacturer/product/name/serial (Android) or name/vendor/version/description (desktop), never the enumeration index.
- **Routing**: inbound messages are filtered by `ignoreIncoming` / `enabled` / `receiveChannel` before parsing; outbound sends apply `enabled` / `muteOutgoing` / `sendChannel` (channel remap, system messages untouched). `MidiRouter` also accepts a per-track `midiInDeviceId`, enabling two-keyboard splits; tracks with the MIDI engine have a `midiOutDeviceId`.
- **Desktop output**: `sendMidi` on macOS was a no-op; it now enumerates receivers, opens them, and sends short/sysex messages. This closes the macOS "MIDI routing" item from §9.
- **MIDI OUT engine**: the native sequencer already enqueued note events but nothing consumed them. `fetchMidiEvents` now carries the source track index (5 ints per event), and a background pump in `GrooveboxViewModel` routes events to the track's chosen device or all devices.
- **Persistence**: device configs and presets are global (`<Loom folder>/midi-settings.gbs`, Java serialization like projects), loaded at startup, and never written into project files. The file carries a `schemaVersion` (currently 1) and is sanitized on load: invalid channels fall back to "All", duplicate device/preset ids are dropped, and a missing active preset is cleared. Track-level in/out device assignments stay in the project.
- **UI** (`Settings → MIDI Devices`): enable switch, IN/OUT badges, activity dot, send/receive channel pickers, Mute Out / Ignore In chips, refresh, and named hardware presets with save/load/delete. The per-track MIDI rows gained source-device pickers (and a destination picker for MIDI tracks).

Left for later phases: MIDI clock in/out, SPP and transport alignment (§3.5 / Phase 5). Note that a device's `sendChannel` applies to all output, including LED/EMP16 feedback.

### 3.7 Status: MIDI clock + expression implemented (Phase 5)

Shipped in `midi/MidiClock.kt` + `midi/MidiRouter.kt`:

- **Clock out.** `MidiClockOut` runs a dedicated daemon thread and emits 24
  PPQN with an absolute-time anchor, so a late wake-up never accumulates drift.
  Tempo changes re-anchor on the last pulse instead of bursting. `0xFA` Start
  and `0xFC` Stop follow transport transitions; `0xFB` Continue is available to
  receivers. System real-time bytes are channel-less, so per-device channel
  remapping leaves them alone while mute/disable still applies.
- **Clock in.** `MidiClockIn` averages a 48-tick window (reports after 16
  samples) and rounds to 0.1 BPM, which tolerates typical USB jitter. Tempo is
  applied with `setTempo` and mirrored into `state.tempo` so the knob shows the
  master tempo. A gap longer than 1 s restarts the window; `0xFC` resets it.
- **Transport + SPP.** `0xFA` starts, `0xFB` continues and `0xFC` stops. `0xF2`
  Song Position Pointer is stored and applied to the next Start/Continue via
  `jumpToStep` (Loom advances one step per 16th, so SPP maps 1:1). `0xFE`/`0xFF`
  are consumed; MTC quarter-frame and Song Select are parsed and ignored.
- **Pitch bend.** `0xE0` now routes to every track whose channel/device matches
  (falling back to the selected track) and scales by the configured range
  (2/12/24 semitones). Previously the normalised value was passed as semitones,
  so full wheel travel was only ±1 st.
- **Aftertouch.** `0xD0` channel pressure drives every matching track's
  `padModTargetId` (global path). `0xA0` poly key pressure drives the Phase 4
  per-voice path for that note when `polyPadMod` is on, the arp is off and the
  destination is per-voice capable; otherwise it falls back to the global path.
  Note-offs end the per-voice gesture.
- **Persistence + UI.** `MidiSettings` schema 2 adds `clockInEnabled`,
  `clockOutEnabled`, `clockOutStartStop`, `clockInDeviceId`,
  `aftertouchEnabled`, `polyAftertouchEnabled` and `pitchBendRange`. Migration
  from schema 1 turns transport/aftertouch on. Settings → MIDI Devices has a
  new **MIDI Clock & Expression** card (source picker, four switches, bend
  range chips). The dead `sendMidiClock` / `sendMidiStartStop` /
  `midiSyncOutEnabled` project fields were removed: clock settings are global
  hardware preferences, not project data.
- **Bug fix.** Android had a second `fetchMidiEvents` pump in `MainActivity`
  reading 4-int events after the format became 5 ints; it duplicated and
  corrupted note output. The ViewModel pump is now the only consumer and it
  honours `midiOutChannel` on both platforms.

Verified with `:composeApp:desktopTest`: tempo estimation at 90/120/128/174 BPM,
jitter tolerance, window warm-up, SPP start/continue semantics, gap handling,
Start/Stop emission and clock-disable stop. Desktop + Android Kotlin compile.


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
| **1b. CPU budget** | Finish track disable, FX enable mask + UI, performance profile, verify CPU drops | Medium | **Done** (§8.5): track disable, FX mask, High/Balanced/Low profile, idle gate, UI poll scaling |
| **2. MIDI devices** | Device registry, per-device config UI, hardware presets, persistence, routing filters | Medium | **Done** (§3.6); clock/transport deferred to Phase 5 |
| **3. Split pads v1** | State, 20/20 layout, per-bank track/octave, global mod fallback, persistence | Medium | **Done** (§11); per-voice mod shipped in Phase 4 |
| **4. Per-voice mod** | `setVoicePadMod`, per-voice state in 4 engines, routing API | High | **Done** (§11.1): per-finger bends/mods, per-bank destinations/intensity, bend range, refcounted gestures |
| **5. MIDI expression** | Clock in/out, SPP, pitch-bend input, **aftertouch → pad mod** | Medium | **Done** (§3.7): clock in/out, SPP, transport, per-track bend, channel/poly aftertouch |
| **6. Pad modes + drum row wizard** | 16/36 layouts, six pad modes, general drum-row learn wizard | Medium | **Done** (§11.2): 4x4/6x6 + Keys/Tonnetz, split 20/20 in the GRID cycle, AUTO + six pad modes, FX pads, drum-row learn |
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

### 8.5 Status: implemented (v3.2.19)

**Track disable gaps closed**

- `applyModulations()` skips disabled tracks.
- `triggerNoteLocked()` rejects notes (including MIDI and arp triggers) for disabled tracks.
- Disabling a track now releases its voices through every engine (`SET_TRACK_DISABLED`), so re-enabling cannot resurrect stale envelopes.
- `syncNativeState()` always pushes the disabled flag (previously only pushed `true`, so a track disabled in a loaded project could never be re-enabled).
- Playing pads are dimmed and do not trigger or highlight while the selected track is disabled; MixerView already had the toggle.

**FX enable mask**

- `AudioEngineCore::mFxEnabledMask` (18-bit `std::atomic<uint32_t>`, default all enabled).
- `setFxEnabled` / `isFxEnabled`, JNI `nSetFxEnabled`, plus `NativeLib` expect/actual.
- `renderStereo()` snapshots the mask per block; every FX `processBlock` is skipped when its bit is off.
- Disabled buses are skipped when collecting track sends, so they cannot duck the dry signal or consume the sparse-mixer loop.
- Bus buffers are still cleared each block, and `mFxFade[18]` ramps each wet return to/from zero across the toggle block, so enabling or bypassing a pedal does not click.
- UI: per-pedal power dot (tap to bypass) in the FX screen, with a `BYPASS` label and dimmed controls. Settings → Performance lists all 18 buses and 8 tracks as chips, with counts plus **Bypass Unused** / **Enable All** actions. Bypass Unused is conservative: it only disables buses that no track sends to and that no other FX routes into.
- `fxEnabled` persists with the project and is re-applied on load by `syncNativeState()`. Older projects default to all-on.

**Performance profile (High / Balanced / Low)**

Settings → Performance now has a three-way profile. It is persisted with the project and pushed on load:

| Profile | Voices per track | Modulation rate | Idle FX gate | UI polling |
|---|---|---|---|---|
| High (default) | 16 | every block | off | 32 ms |
| Balanced | 8 | every block | after ~4 s of silence | 32 ms |
| Low | 4 | every other block | after ~4 s of silence | 64 ms |

- The voice cap reuses the existing tracking-slot allocator and steals the active voice with the least time remaining.
- Fixed a voice-stealing bug: the steal pass used to pick inactive slots (their `durationRemaining` is 0), which would have made the cap ineffective and could leak voice counts. It now only steals active voices.
- Fixed the silence fast-path: deactivating a track reset `mSilenceFrames` to 0, so `!isActive && mSilenceFrames > 2400` was never true and the skip never engaged. The counter now stays above the threshold; a trigger resets it and wakes the track.
- The idle gate checks transport, input need, active tracks, held notes and rendered tracks. It does not engage while playing or while a note is held.

**Measured impact (macOS, synthetic 8-track patch sending to 10 buses, 6000 blocks ≈ 32 s of audio)**

| Configuration | Render time | Relative |
|---|---|---|
| 8 tracks, 18 FX on | 2517 ms | 100% |
| 8 tracks, FX all off | 328 ms | 13% |
| 4 tracks, FX off | 167 ms | 7% |
| High, 16-note chords on every track | 7663 ms | 100% |
| Low, same chords (4 voices/track) | 3560 ms | 46% |
| Idle, High profile | 2208 ms | 100% |
| Idle, Low/Balanced profile (gate on) | 6 ms | 0.3% |

FX processing dominates the synthetic patch (~87% of render time), synthesis scales near-linearly with track count and polyphony, and idle FX gating removes essentially all render cost when the app is silent. Real-project savings depend on how many buses and voices are actually used, which is why the mask defaults to all-on and the profile defaults to High.

Remaining from §8.3: user-selectable sample rate / buffer size (platform-dependent; Android's Oboe path would need a host-level config surface).



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

## 10. Factory preset bank and portable preset format (cross-version)

Added in v3.2.19. Design and schema live in [`PRESET_FORMAT.md`](PRESET_FORMAT.md).

### 10.1 Factory bank

20 patches each for **Subtractive**, **Wavetable**, **FM Drum** and **Analog
Drum** (80 total), grouped by genre-neutral categories: Bass, Lead, Pad, Keys,
Pluck, FX, Brass, Winds, and 20 drum kits per drum engine (Studio, 808, 909,
Trap, House, Techno, Electro, Funk, Lo-Fi, Minimal, Industrial, Vinyl, with
tight/long/hard variants).

- Authored in `presets/FactoryPresets.kt`; the cross-version JSON export is
  committed as `docs/factory-presets.json`.
- Applied by resetting the engine, setting the parameter map, applying
  `filterMode`, and loading the named wavetable (with a safe fallback).
- Browser: **Parameters → PRESETS**, with category filters and one-tap auditioning.

### 10.2 Portable presets

- New `.loom.json` format (`schema: 1`) with normalized parameter ids, engine
  key, category, description, optional filter mode and wavetable.
- **EXPORT** writes the current track; **IMPORT** reads it back. Legacy `.gbp`
  user presets continue to work through LOAD/SAVE.
- The format is deliberately engine-id based so Loom Pi (C++) and other Loom
  versions can consume the same files with a small translation layer.

### 10.3 External imports: Helm + Surge XT

- **Helm** (GPLv3; factory patches CC-BY 4.0) is the reference open-source synth
  for both engines: its `.helm` files are JSON, and its 2-osc + sub + noise +
  filter + envelope architecture maps closely to Loom Subtractive.
- Import mapping covers oscillator shapes/levels/pitch, sub/noise, cutoff
  (MIDI note → Hz → Loom norm), resonance, filter env, ADSRs and glide.
- Wavetable imports map the oscillator shape to table position and use the
  `Basic` table (Helm's wavetables are not bundled).
- **Surge XT** (GPLv3) is the reference Wavetable source: `.fxp` files contain
  XML scene data, mapped to table position/warp/detune/drive, filter type and
  cutoff (440 Hz · 2^(v/12)), resonance, filter env and both ADSRs (log2 seconds).
- Attribution is preserved in `LoomPreset.source` and shown in the browser.
- Unison voices, cross-mod, step sequencer, LFO routing, exact wavetables and
  effects are not mapped (documented in `PRESET_FORMAT.md`).

This feature is version-neutral: the bank and the import mapping are the parts
to mirror in Loom Pi when its engine exposes the same parameter ids.

---

## 11. Split pads v1 (20/20 performance layout)

Implemented in v3.2.19.

- New persisted state: `playLayout` (`GRID` / `SPLIT_20_20`), `splitLeft` and
  `splitRight` (`SplitZone(trackIndex, octave)`).
- A **SPLIT** button in the Play top bar toggles the layout; it works for synth,
  sampler and drum tracks.
- Each bank is an independent **5×4 (20 pad)** grid with its own track selector
  and octave (`-3..+3`). Different tracks are the default (left T1, right T2),
  and assigning the same track to both banks gives two octave ranges of one
  sound, as agreed.
- Pads are addressed as `(bank, track, note)`: `PlayingPad` gained an optional
  `targetTrackIndex`, so a bank triggers its own track without changing the
  selected track.
- Note maps mirror the single grid: melodic banks generate 20 scale notes from
  `root + octave*12`; FM Drum and Analog Drum banks use the 8 drum notes; chop
  banks use the active slice count (empty pads are skipped).
- The **MOD** button in each bank header selects that bank's track and enters
  pad-mod learn, so X/Y performance gestures target the right sound. X is pitch
  bend and Y uses the track's existing `padModTargetId` (the v1 global fallback).
- Playhead/step-jump highlighting is disabled on split pads (they are a
  performance surface, not a 16-step view). EMP16 LED feedback still uses pad
  indices 0–15.
- State is saved with the project and sanitized on load (track clamped 0..7,
  octave clamped -3..+3). Older projects default to the single grid.

### 11.1 Per-voice modulation (Phase 4)

Implemented in v3.2.19 alongside the split layout.

**Native API.** `AudioEngineCore` exposes four commands, all serialised through
the existing audio command queue:

| Method | Effect |
|---|---|
| `beginVoicePadMod(track, note)` | Increments the note's gesture refcount (max 64) |
| `setVoicePadMod(track, note, x, y)` | Writes `x` (-1..1) and `y` (-1..1 delta) to every active voice with that note; no-op when poly mode is off |
| `endVoicePadMod(track, note)` | Decrements the refcount; at zero it clears that note's voice mod |
| `setPadModRouting(track, xDest, xRange, yDest, yIntensity, poly)` | Stores the routing and pushes it into the four per-voice engines; leaving poly mode clears all voice mods |

`panic()` clears every voice mod and refcount. A `setVoicePadMod` without a
matching `begin` is treated as a single finger.

**Engines.** Per-voice X is pitch bend (`x * bendRange` semitones). Per-voice Y
is resolved per control block against the routing destination:

- **Subtractive:** cutoff (1/112), resonance (2/113), filter env (3/118),
  noise (110), osc volumes (107–109), fold (180–183), drive (170–173),
  osc pitch (160–163). Unknown destinations fall back to cutoff.
- **FM:** cutoff (1/151), resonance (2/152), feedback (154), drive (159),
  operator level (`160 + op*6`). Unknown destinations fall back to mod index
  (brightness). The block renderer also gained the per-voice pitch bend that
  the scalar renderer already had.
- **Wavetable:** morph (450), warp (465), cutoff (1/458), resonance (2/459),
  env amount (464), bits (475), srate (476), crush (466), drive (467),
  detune (451). Unknown destinations fall back to morph.
- **Sampler:** speed (302), pitch (300), cutoff (1/303), resonance (2/304),
  env amount (314). Unknown destinations fall back to speed.

**UI.** `SplitZone` gained `modTargetId` (-1 = track default), `modIntensity`,
`bendRange` (2/12/24 st) and `polyMod`; `GrooveboxState` gained
`padModLearnBank` and `polyPadMod`. Each bank header has a `⋮` menu for poly
on/off, bend range, intensity, learn and "use track destination"; the MOD
button learns into that bank. The single grid keeps the track-level
destination (`BND LRN` resets `padModLearnBank = -1`).

**Gesture rules.** A pad's X range is the full pad width at the configured
bend range; Y is a signed delta from the touch-down point, so releasing returns
to the original value. Poly mode is used when the bank (or `polyPadMod` for the
single grid) enables it **and** the track's arp is off. Drum/chop tracks keep
using their existing global path. Changing a bank's mod settings restarts that
pad's gesture detector.

Routing is stored per **track**, so when both banks are assigned to the same
track they share one destination and the last touched bank wins. Give the banks
different tracks to use independent destinations; the shared-octave setup is
still fully playable, just with one Y destination.

**Verified.** A native smoke test renders two simultaneous notes and checks:
a mod aimed at a silent note does not leak; modding note 60 vs note 67
produces different mixes; `clearAllVoiceMod` restores the exact unmodulated
render; a +1 X at 12 st bends one octave; and the core refcount keeps the bend
until the final `endVoicePadMod`.

Loom Pi calls the poly flag `voiceLinkPoly`; Groovebox exposes the same switch
as `SplitZone.polyMod` (per bank) and `polyPadMod` (single grid). With voice
link on, each finger's modulation is bound to the note it triggered, so two
fingers never share a bend. With it off, the gesture uses the global parameter
path. Chord-voice sharing (one pad triggering several notes) is not used by
Groovebox today; if it is added later, `originNote` is the field to key the
shared identity on.

### 11.2 Pad modes + drum-row wizard (Phase 6)

**Layouts.** `GridMode` offers 4x4 (16 pads), 6x6 (36), Keys (macOS or
keyboard mode) and Tonnetz. `utils/PadLayout.kt` centralises rows/cols/count so
the scale-note buffer, pad sizing and note mapping cannot disagree. The GRID
button cycles 4x4 → 6x6 → Keys/Tonnetz → **20/20 split** → 4x4, so the split
performance layout has no separate control; the button reads `20/20` while the
split is active. The 4x6/5x8 layouts were tried and removed: on phone-sized
screens they crowded the pad area without adding a useful playing surface.

**Pad modes.** `PadMode` is an explicit performance mapping independent of the
engine: `AUTO` (v1 engine-driven), `SCALES`, `KEYBOARD` (chromatic from root),
`FM_DRUM` (60..75), `ANALOG_DRUM` (60..65), `SLICES` (sampler slice count) and
`FX`. The MODE button cycles them; the value is saved with the project and
sanitized on load. `padNoteFor()` is shared by the single grid and the split
banks, so a drum kit or slice bank can be played from any track and both banks
follow the same mapping. In split mode FX falls back to the scale mapping,
because the FX buses are global.

**FX pads.** In FX mode the grid becomes 18 pads (6x3) that toggle the global FX
enable mask, with the same labels and colours as Settings → Performance. This
gives a live "pedalboard" page without leaving the play screen and reuses the
Phase 1b CPU mask (`setFxEnabled` + `fxEnabled`).

**Drum-row wizard.** Drum tracks get a DRUM button that opens a scrollable map.
Each row shows its current note bindings and has LEARN/CLEAR; arming a row makes
`MidiRouter` consume the next pad/key on that track (channel/device filters
still apply) and store `drumNoteMap[inputNote] = row`. Unmapped notes keep the
default `60 + row`. The map is applied before `triggerNote`, so note-offs
release the same remapped note, and it persists with the project. `RESET ALL`
returns to the default. Learning is transient: the armed row is cleared on
load and after each capture.

Both phases compile on desktop + Android and the MIDI clock tests run under
`:composeApp:desktopTest`.

---
