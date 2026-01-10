# Loom Groovebox User Manual

Welcome to Loom Groovebox, a powerful mobile synthesizer and sequencer designed for tactile performance and deep sound design.

---

## 2. Interface Overview

### Play Screen
The hub for live performance. Toggle between Melodic and Drum Kit layouts depending on your track type.

````carousel
![Melodic Play Screen](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Play Screen (Melodic).png)
<!-- slide -->
![Drum Kit Play Screen](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Play Screen (Drum Kit).png)
````

- **Arpeggiator Menu**: Long-press the **ARP** button to open the advanced Arpeggiator configuration.
    - **Rhythms**: 3 independent lanes (Root, Poly 1, Poly 2) for complex rhythmic patterns.
    - **Octaves & Inversion**: Expand the range or flip the chord voicing.
    - **Mutation**: Randomly swap notes in your arpeggio for evolving patterns.

---

### Sequencing Screen
Create and edit patterns with the 16-pad grid.

````carousel
![Single Track Sequencer](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Sequencer Screen (Single Track).png)
<!-- slide -->
![Drum Kit Sequencer](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Sequencer Screen (Drum Kit).png)
````

- **Step Options**: Long-press any active step to access the Step Editor.
    - **Velocity**: Adjust the loudness of the step.
    - **Gate Length**: Control note duration. Gates > 100% create "Legato" slides if the engine supports it.
    - **Probability**: Set the chance (0-100%) that the step will play.
    - **Ratchet**: Repeat the note multiple times within a single step (e.g., 2x, 3x, 4x) for drum rolls or glitches.
- **Octave +/-**: Quickly shift the entire sequence pitch.

---

### Parameters Screen (Sound Design)
Shape your sound using the dedicated controls for each engine.

````carousel
![Subtractive Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (Subtractive).png)
<!-- slide -->
![FM Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (FM).png)
<!-- slide -->
![Wavetable Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (Wavetable).png)
<!-- slide -->
![Sampler Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (Sampler).png)
<!-- slide -->
![Granular Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (Granular).png)
<!-- slide -->
![FM Drum Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (FM Drum).png)
<!-- slide -->
![Analog Drum Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (Analogue Drum).png)
<!-- slide -->
![MIDI Output Engine](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Param Screen (MIDI).png)
````

**Sampler Modes**:
- **One Shot**: Plays the sample from start to finish. Good for drums.
- **Chopped**: Splits the sample into 16 slices, mapped to the step sequencer. Each step can trigger a specific slice.
- **Loop**: Loops the sample continuously while the key is held.

---

### Effects & Routing
Chain your tracks through high-quality FX and manage the signal path.

````carousel
![Effects Pedalboard](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Effects Screen.png)
<!-- slide -->
![Routing Matrix](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Patch Screen.png)
````

**Global Effects Suite**:
The effects chain processes audio in a semi-parallel bus structure. Each track has a **Send** amount to the FX bus.
- **Reverb (Hall)**: Spacious hall reverb with Size and Damping controls.
- **Delay**: Stereo delay with Feedback and Mix.
- **Overdrive**: Analog-style saturation and wave-folding.
- **Bitcrusher**: Reduces sample rate and bit depth for lo-fi textures.
- **Chorus / Phaser / Flanger**: Modulation effects for widening and motion.
- **Slicer**: Rhythmic gating effect synced to the tempo.
- **Compressor**: Dynamics control to glue the mix together.
- **Tape Wobble**: Simulates the pitch instability of worn tape.

**Routing Matrix**:
- Connect LFOs (Left Side) to any destinations (Top Labels).
- "Cables" show active connections. Tap a node to adjust modulation depth.

---

### Settings & Troubleshooting
Project management and system controls.

![Settings Screen](/Users/danielmiller/.gemini/antigravity/brain/0dad9a40-7a34-4754-a4da-b660dbf82e09/Settings Screen.png)

- **RESET AUDIO ENGINE (PANIC)**: If the audio becomes silent, distorted, or "heavy," tap this button. It performs a **"Nuclear Reset"** by completely reconstructing the native audio engine, clearing out any bad state or NaN (Not-a-Number) values.
- **Project Files**: Save and Load your sessions. Note: Loading a project also triggers an engine reset for stability.

---

## 3. Advanced Features

### Arpeggiator Polyphony
The new Arpeggiator supports 3 rhythm lanes:
1. **ROOT (Bottom)**: Triggers the base note of the arpeggio cycle.
2. **UP 1 / UP 2 (Middle/Top)**: Cycle through the remaining held notes in a "staggered walk" pattern, allowing for complex polyphonic interplay even with monophonic engines.

### Gain Staging
To provide maximum headroom and prevent clipping:
- Track volumes are default-scaled to **45%** on load.
- Global saturation and internal gain stages are optimized to allow for layering multiple heavy synth engines without digital distortion.
- **Global Limiter**: A master soft-limiter prevents digital overs at the final output.
