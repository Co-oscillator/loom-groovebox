# Loom preset format (v1)

Portable patch data shared between Loom versions (Groovebox, Pi, and any future
build). The same schema is used for factory patches, user exports, and imports.
The canonical factory bank lives in [`factory-presets.json`](factory-presets.json)
and mirrors the in-app `FactoryPresets` object.

## Schema

```json
{
  "schema": 1,
  "name": "Pluck Bass",
  "engine": "subtractive",
  "category": "Bass",
  "author": "Loom",
  "description": "Short, woody pluck with a round sub.",
  "parameters": { "1": 0.28, "104": 0.66, "107": 0.95 },
  "filterMode": 0,
  "wavetableName": null,
  "source": null
}
```

| Field | Meaning |
|---|---|
| `schema` | Format version. Currently `1`. Readers should ignore unknown fields. |
| `engine` | `subtractive`, `wavetable`, `fm_drum`, `analog_drum`. |
| `category` | Free-form browser grouping (`Bass`, `Lead`, `Pad`, `Keys`, `Pluck`, `FX`, `Drums`, `Helm`, `User`, `Init`). |
| `parameters` | `parameter id -> normalized value (0..1)`. Ids are Loom's engine parameter ids, the same ones the UI knobs and `setParameter` use. |
| `filterMode` | Subtractive filter: `0` LP, `1` HP, `2` BP. `null` = leave unchanged. |
| `wavetableName` | Wavetable preset: table name (without extension), resolved from `<Loom>/wavetables/`. Falls back to the default table when missing. |
| `source` | Attribution/provenance for imported patches. |

Values are intentionally normalized 0..1. Engine-specific mappings (cutoff Hz,
ADSR seconds, pitch multipliers) live in the audio engine, not in the file, so a
preset sounds consistent across platforms.

## Parameter ranges

| Engine | Ids | Notes |
|---|---|---|
| Subtractive | `1`, `3`, `100..199`, `355` | `104/105/155` waveforms, `107/108/109` osc levels, `160/161/162` pitch, `1` cutoff, `113` resonance, `118` filter env, `100..103` amp ADSR, `114..117` filter ADSR |
| Wavetable | `1..3`, `355`, `450..489` | `450` morph, `451` detune, `465..467` warp/crush/drive, `475/476` bits/srate, `454..457` amp ADSR, `458/459` filter, `464` filter env, `470` mode, `471..474` filter ADSR |
| FM Drum | `200..299` | 8 instruments × 10 slots (`200 + drum*10 + slot`); slots `0` pitch, `1` snap/click, `2` decay, `4` punch, `5` level |
| Analog Drum | `600..699` | 8 instruments × 10 slots (`600 + drum*10 + slot`); slots `0` decay, `1` tone, `2` tune, `3` snap/param A, `4` param B, `5` gain |

## Applying a preset

1. Reset the engine to its defaults (`restoreTrackPreset`).
2. Send every parameter in the map.
3. Apply `filterMode` (Subtractive) and load the named wavetable (Wavetable,
   falling back to the default table if the file is absent).
4. Replace the engine's parameter range in UI state with the preset values.

The built-in bank clears the engine ranges before applying, so values from the
previous patch cannot leak through.

## Factory bank

20 patches per engine (80 total), grouped by category:

- **Subtractive:** Init, Bass, Brass, Pad, Lead, Keys, Winds, FX.
- **Wavetable:** Init, Pluck, Bass, Pad, Keys, Lead, FX.
- **FM Drum / Analog Drum:** 20 kits each (Studio/808/909/Trap/House/Techno/
  Electro/Funk/Lo-Fi/Minimal/…), with tight/long/hard variants.

The bank is authored in `composeApp/.../presets/FactoryPresets.kt`; the JSON
export is generated from it and is the cross-version reference.

## User presets

- **Legacy `.gbp`**: Java-serialized `TrackState` in `<Loom>/Presets/<ENGINE>/`.
  Still loaded/saved by the existing LOAD/SAVE buttons.
- **Portable `.loom.json`**: written by **EXPORT** in the preset browser and read
  by **IMPORT**. This is the format other Loom versions should implement.

## External imports: Helm (Subtractive) and Surge XT (Wavetable)

**Helm** (GPLv3, Matt Tytel) is used as the reference open-source synth because
its factory bank is JSON (`.helm`), openly licensed (patches CC-BY 4.0), and its
architecture (2 oscillators + sub + noise + filter + two envelopes) maps closely
to Loom's Subtractive engine.

Mapping (best effort):

| Helm | Loom |
|---|---|
| `osc_1/2_waveform` | oscillator shape (`104`/`105`); wavetable position (`450`) in the Wavetable engine |
| `osc_1/2_volume` | osc levels |
| `osc_1/2_transpose` + `_tune` | pitch multiplier |
| `sub_volume`, `sub_octave`, `sub_waveform` | sub level / pitch / shape |
| `noise_volume` | noise level |
| `cutoff` (MIDI note) | converted to Hz, then to Loom's normalized cutoff |
| `resonance`, `fil_env_depth` | resonance and filter env amount |
| `amp_*`, `fil_*` (seconds) | inverted from Loom's cubic ADSR mapping |
| `portamento` | glide |
| metadata | name, folder, author, and `source` attribution |

Not mapped: Helm's unison voice count, cross-modulation, step sequencer, LFO
routing, effects, and exact wavetables. Wavetable imports use the `Basic` table.

To import: **Parameters → PRESETS → IMPORT**, select a `.helm` file. The original
attribution is stored in the preset's `source` field and shown in the browser
description.

### Surge XT → Wavetable

**Surge XT** (GPLv3) is the reference wavetable source. `.fxp` files are VST2
preset chunks followed by an XML `<patch>`; only scene A is read.

| Surge | Loom |
|---|---|
| `a_osc1_type` | oscillator model; `2` (Wavetable), `7` (Window), `8` (Modern) and classic types are mapped |
| `a_osc1_param0` | table position (`450`) or shape |
| `a_osc1_param1` | warp (`465`) for wavetable/modern |
| `a_osc1_param2` | unison detune (`451`) for wavetable/window/modern |
| `a_ws_type`, `a_ws_drive` | crush (`466`) / drive (`467`) |
| `a_filter1_type` | LP → mode 0, HP → 1, BP → 2, notch → 3 |
| `a_filter1_cutoff` | `440 Hz * 2^(v/12)` → Loom norm |
| `a_filter1_resonance`, `a_filter1_envmod` | resonance / filter env amount |
| `a_env1_*` (log2 seconds) | amp ADSR |
| `a_env2_*` (log2 seconds) | filter ADSR |
| metadata | name, category, author, and `source` attribution |

Surge's exact wavetables, unison voice count, modulation matrix, LFO routing,
FM/string/twist oscillator models and effects are not mapped; imports use the
`Basic` table. Select a `.fxp` file in **IMPORT** while on a Wavetable track.

## Cross-version checklist

If you implement this format in another Loom version:

1. Parse `schema` and ignore unknown fields.
2. Use the same engine parameter ids (or translate them in one adapter).
3. Apply by resetting the engine, then setting the map.
4. Resolve `wavetableName` against the local wavetable folder; fall back safely.
5. Keep `filterMode` optional.
6. Preserve `source` when re-exporting imported patches.
7. Loom FM-1 ships a web-editor adapter (`web/loom_import.js`) that reads this
   format plus Helm/Surge files and converts them to FM-1 SUBTRACT/WAVETABLE
   library patches; use it as the reference for a native importer on another
   platform.
