# Loom Groovebox User Manual

Welcome to Loom Groovebox, a comprehensive mobile groovebox and synthesizer designed for tactile sound design and intuitive sequencing. Whether you are using the on-screen interface or the EMP 16 hardware, this manual will guide you through the features and workflows of the application.

---

## 2. Persistent Interface Items

These elements remain on screen regardless of which functional page you are currently viewing, allowing for quick adjustments to your performance and project.

### Sidebar Mixer (Left)
The sidebar is your main hub for track management and volume control.
- **Track Selection (1-8)**: Tap a track number to select it. The selected track is highlighted in cyan.
- **Volume Sliders**: Each track has a horizontal color-coded bar representing its volume. Drag your finger across the bar to adjust the volume.
- **Engine Selection**: Long-press on a track number to open the engine selection menu. From here, you can assign different synthesis or sample-playback engines to that specific track.
- **Engine Labels**: Below each volume bar, you'll see the name of the currently assigned engine (e.g., "SUBTRACTIVE", "FM", "DRUMS").

### Transport Bar (Top)
The top bar controls the timing and playback of your session.
- **Play/Stop Button**: Starts or stops the sequencer.
- **BPM (Beats Per Minute)**: Shows the current tempo. Tap and drag to change the speed of your project.
- **Swing**: Adjusts the rhythmic "feel" of your sequences by slightly delaying off-beats.
- **Metronome**: Tap the bell icon to enable a click track during playback or recording.
- **CPU Monitor**: Displays the current load on your device's processor to help you manage complex projects.

### Navigation Bar (Right)
Use the icons on the right edge of the screen to switch between the functional areas of the app:
- **Playing**: Performance pads and keyboard.
- **Sequencing**: Grid-based step sequencing.
- **Parameters**: Detailed control over the selected engine's sound.
- **Effects**: Pedalboard-style effects processing.
- **Routing**: Visual matrix for connecting tracks to effects.
- **Settings**: Project management and MIDI configuration.

---

## 3. Functional Screens

### Playing Screen
This is where you perform live.
- **Performance Pads**: Large, color-coded pads that trigger sounds on the selected track. 
- **Chromatic Keyboard**: A traditional keyboard layout for playing melodies.
- **Scales & Chords**: Use the selection menus to lock the pads/keys to specific musical scales or trigger full chords with a single tap.
- **Velocity Intensity**: Vertical position on the pads often controls the volume or intensity of the triggered note.

### Sequencing Screen
Create rhythmic and melodic patterns here.
- **Step Grid (16 Pads)**: Tapping a pad toggles a note at that position.
- **Banks**: Move between banks to create sequences up to 128 steps long. 
- **Drum Mode**: For Drum engines or Chopped Samplers, the grid changes to handle multiple instruments or sample slices simultaneously on one track.
- **Step Parameters**: Long-press a step to adjust its specific velocity, gate length, or even "lock" certain parameters to that specific moment in time.

### Parameters Screen (Sound Design)
This is the "heart" of your sound design, where you turn knobs to shape the character of the active track. See the **Sound Engines** chapter for details on each engine's specific controls.
- **Test Trigger**: A dedicated play button in the header allows you to hear your sound as you edit it without needing to switch screens.

### Effects Screen
Process your sounds through a chain of virtual effects "pedals."
- **Effect Pedals**: Each pedal (e.g., Reverb, Delay) has its own set of dedicated knobs.
- **Master/Bus Chains**: Effects can be applied to individual tracks or to groups of sounds (buses) and the final output (Master).

### Routing Screen
A visual map of how audio flows through the app.
- **Matrix Grid**: Tap intersections to "plugin" a track into an effect bus or send it directly to the Master output.
- **Visual Feedback**: Lines represent the audio path, helping you understand complex routings at a glance.

### Settings Screen
Manage your work and hardware connections.
- **Project Management**: Save, Load, and Export your project to high-quality audio files.
- **MIDI Mapping**: Configure external controllers or use the "MIDI LEARN" feature to assign your hardware knobs to on-screen controls.
- **Recording/Resampling**: Controls for capturing master audio back into a Sampler track for further manipulation.

---

## 4. Sound Engines

Loom Groovebox features a diverse set of sound engines, each with its own character and synthesis method.

---

### Subtractive Engine
The Subtractive engine is based on classic analog synthesis. It works by taking rich, harmonically complex digital oscillators and "subtracting" frequencies using a resonant filter to create warm, punchy, or mellow sounds.

**Oscillators**
- **OSC 1-4 VOLUME**: Controls the loudness of the four individual sound sources.
- **OSC 1-4 WAVEFORM**: Choose between Sine (smooth), Sawtooth (bright), Square (hollow), or Noise (hiss) for each oscillator. Combining different waveforms is the first step in building a complex sound.
- **DETUNE**: Slightly shifts the pitch of the second oscillator to create a thicker, "chorus-like" sound.
- **MORPH**: Smoothly changes the timbre of the oscillators by shifting their internal phase or shape.
- **NOISE LEVEL**: Adds a layer of static or hiss, useful for creating "dirty" leads or percussive textures.
- **CHORD TYPE**: Automatically turns a single note into a chord (like a Major or Minor triad) with a single tap.

**Filter**
- **CUTOFF**: The most important knob! It determines the frequency where the filter starts removing sound. Lowering it makes the sound "darker."
- **RESONANCE**: Boosts the frequencies right at the cutoff point, creating a "whistling" or "squelchy" character often associated with classic acid techno or funky leads.

**Envelopes**
- **AMP ENVELOPE (A, D, S, R)**: Controls how the volume changes over time.
    - **Attack**: How fast the sound fades in.
    - **Decay**: How fast it drops to the sustain level.
    - **Sustain**: The volume held while the pad is pressed.
    - **Release**: How long the sound lingers after you let go.
- **FILTER ENVELOPE (A, D, S, R)**: Works exactly like the Amp envelope but moves the **CUTOFF** knob automatically every time you play a note. Use this for "plucky" sounds that start bright and quickly turn dark.
- **FILTER ENV AMT**: Determines how strongly the Filter Envelope affects the Cutoff.

---

### FM Engine (Frequency Modulation)
FM synthesis creates sounds by using one oscillator to vibrate another at extremely high speeds. This results in complex, metallic, and evolving digital textures that are perfect for bells, electric pianos, and aggressive basses.

**Algorithm & Feedback**
- **ALGORITHM**: Chooses the "map" or arrangement of the 6 operators. Some arrangements create complex layers, while others stack operators for extreme modulation.
- **FEEDBACK**: Allows an operator to modulate itself, creating anything from a slight grit to harsh, noisy textures.
- **BRIGHTNESS**: A macro-control that increases the modulation depth across all operators simultaneously.
- **DETUNE**: Adds a subtle pitch offset between layers for a wider, more organic feel.

**Operators (OP 1-6)**
- **LEVEL**: The volume of the specific operator. If the operator is a "modulator," increasing its level changes the *tone* of the sound rather than its volume.
- **RATIO**: Determines the pitch of the operator relative to the note you play. Integer ratios (1, 2, 3) create harmonic, musical tones, while non-integers create dissonant, bell-like timbres.
- **ADSR**: Each operator has its own envelope. Short attacks and decays on modulators create the distinctive "click" or "thump" of FM sounds.

**Filter**
- **CUTOFF & RESONANCE**: Just like in the Subtractive engine, these allow you to tame the complex harmonics of FM synthesis and create smoother, more restrained sounds.

---

### Sampler Engine
The Sampler engine plays back audio files or recordings. You can manipulate these sounds in various ways, from simple one-shots to complex "chopped" rhythmic patterns.

**Sample Manipulation**
- **START & END**: Determines which part of the audio file is played. You can use this to zoom in on a specific sound within a longer recording.
- **TRIM**: A destructive edit that permanently removes audio outside the Start and End markers, saving memory and focusing the sample.
- **PITCH**: Changes the speed and pitch of the sample.
- **LOOP**: When enabled, the sample will play repeatedly as long as the pad is held.

**Play Modes**
- **ONE-SHOT**: The sample plays from start to end once when triggered.
- **SUSTAIN**: The sample plays as long as the pad is held and fades out based on the Envelope settings.
- **CHOP**: Automatically divides the sample into equal parts or "slices." In this mode, the Sequencing screen allows you to trigger each slice independently.

**Reverse & Envelopes**
- **REVERSE**: Plays the sample backwards.
- **AMP ENVELOPE (A, D, S, R)**: Shapes the volume of the sample playback, allowing for slow-building pads or sharp, percussive hits.

---

### Granular Engine
Granular synthesis breaks audio into thousands of tiny "grains" and reorganizes them into ethereal clouds, frozen textures, or shimmering soundscapes.

**Grain Control**
- **POSITION**: Determines where in the sample the grains are being harvested from. Moving this creates a "traveling" effect through the sound.
- **GRAIN SIZE**: Controls the length of each individual grain. Smaller grains sound more digital and "glitchy," while larger ones preserve more of the original sound's character.
- **DENSITY**: Determines how many grains are playing at once. High density creates a thick "cloud" of sound.
- **SPRAY**: Adds randomness to the position of each grain, creating a more smeared and ambient texture.

**Movement & Texture**
- **SPEED**: Determines how fast the grains "walk" through the audio file. At 0, the sound is "frozen" in time.
- **WIDTH**: Randomizes the stereo position of each grain, making the sound feel wide and spacious.
- **REVERSE PROB**: The chance that any given grain will play backwards, adding to the chaotic and atmospheric nature of the engine.

**LFOs (Low Frequency Oscillators)**
- **LFO 1-3**: These are slow, rhythmic modulators that can be "mapped" to parameters like Position, Pitch, or Grain Size, creating movement and evolution in your sound without you having to turn the knobs manually.

---

### Wavetable Engine
Wavetable synthesis allows you to "morph" between different wave shapes, creating complex and evolving digital sounds that go far beyond basic sines or saws.

**Sculpting the Wave**
- **MORPH**: This is the core control. It moves the playback position through a series of stored waveforms, allowing you to blend smoothly from one timbre to another.
- **WARP**: Bends and squashes the waveform's phase, creating aggressive "Reese" bass textures or modern digital articulations.
- **DRIVE**: Adds saturation before the filter, making the sound "thicker" and more aggressive.
- **BITCRUSH**: Reduces the "resolution" of the sound, adding digital grit and artifacts for a lo-fi or aggressive character.

**Unison & Filter**
- **UNISON VOICES**: Plays multiple copies of the same sound simultaneously.
- **DETUNE**: Spreads the pitch of the unison voices for a huge, wide sound.
- **CUTOFF & RESONANCE**: Provides the same subtraction power as the Subtractive engine, essential for taming the complex harmonics of wavetable morphing.

---

### FM Drum Engine
Specifically designed for rhythmic sounds, the FM Drum engine uses Frequency Modulation to create punchy kicks, metallic hi-hats, and electronic percussion.

**Drum Parameters**
- **DECAY**: Controls the length of the drum hit.
- **TONE**: Adjusts the clarity or brightness of the drum.
- **TUNE**: Changes the base pitch of the instrument.
- **PARAMETER A & B**: These knobs change their function depending on the selected drum (e.g., controlling the "click" of a kick or the "snap" of a snare).
- **PITCH SWEEP**: Adds a quick downward slide in pitch at the start of the hit, essential for creating "punchy" electronic kicks.

---

### Analog Drum Engine
The Analog Drum engine uses virtual modeling to recreate the sounds of classic drum machines. It is simpler than the FM Drum engine but offers immediate, recognizable character.

**Classic Voices**
- **KICK**: Deep, booming bottom end with a "Punch" control.
- **SNARE**: A balance of "Shell" tone and "Wire" noise with a "Snappy" control.
- **CLAP**: A distinct "burst" of noise with a "Spread" control to simulate multiple hands clapping.
- **HI-HATS**: Metallic, percussive sounds created by clusters of oscillators.
- **CYMBAL & PERC**: Additional percussive voices for filling out your rhythm.

---

## 5. EMP 16 Hardware Integration

Loom Groovebox is designed to work seamlessly with the **EMP 16** tactile controller. When connected, the hardware mirrors the state of the application, allowing you to focus on the pads rather than the screen.

### Visual Sync
- **Color Matching**: The 16 pads on the EMP 16 will light up with the color of the currently selected track (e.g., Cyan for Subtractive, Red for FM Drum).
- **Step Feedback**: In the Sequencing screen, the pads represent the 16 steps of the current bank.
    - **Bright Light**: Indicates an active step containing a note.
    - **Dim Light**: Indicates an empty step.
    - **White Flash**: When the sequencer is playing, a white highlight travels across the pads to show the current playback position.

### Tactile Performance
- **Velocity Sensitivity**: The EMP 16 pads are velocity-sensitive, allowing for more expressive performances on the Playing screen.
- **Bi-Directional Control**: Tapping a pad on the hardware instantly updates the on-screen sequencer grid, and vice versa.

---

## 6. Workflow Walkthroughs

### Recording & Playing Your Own Samples
1. Navigate to the **Settings** screen.
2. In the **Recording Strip**, tap the **RECORD** button to start capturing audio from your device's microphone or input.
3. Tap **STOP** when finished. 
4. Navigate to the **Sampler** track and use the **START** and **END** markers to trim any silence from the recording.
5. Select **ONE-SHOT** mode.
6. Go to the **Playing** screen; you can now play your recording across the pads at different pitches.

### Chopping a Beat
1. Record a short rhythmic sample as described above.
2. In the Sampler engine, set the **MODE** to **CHOP**.
3. The engine automatically splits your sample into 16 equal slices.
4. Navigate to the **Sequencing** screen. You will now see buttons for **S1** through **S16**.
5. Select **S1** and tap some pads in the grid. Then select **S2** and tap others. 
6. Play the sequencer to hear your sample re-arranged into a new rhythmic pattern.

### Quick Sound Design: The "Fat" Synth Lead (Subtractive)
1. Select a Subtractive track.
2. Set **OSC 1** and **OSC 2** to **Sawtooth**.
3. Turn up the **DETUNE** knob to about 20% to create a thick, phased sound.
4. Lower the **CUTOFF** until the sound is warm but not muffled.
5. Increase **RESONANCE** to about 30% for a classic electronic "bite."

### Quick Sound Design: Ambient Cloud (Granular)
1. Load any sample into the Granular engine.
2. Set **DENSITY** to maximum and **GRAIN SIZE** to a medium setting.
3. Increase **SPRAY** to smear the grains across the sample.
4. Increase **WIDTH** to 100% for a massive stereo field.
5. Set **SPEED** to 0.1 for a slow, evolving texture.

### Connecting to Effects
1. Go to the **Routing** screen.
2. Find the intersection between your active track and **Bus A** (usually the first column).
3. Tap the intersection to route the audio.
4. Navigate to the **Effects** screen and select **Bus A**.
5. Turn up the **MIX** knob on the **Hall Reverb** pedal to hear your sound in a lush virtual space.

### MIDI Learning
1. Navigate to the **Parameters** screen for your chosen engine.
2. Tap the **MIDI LEARN** button (it will turn red).
3. Tap any on-screen knob (e.g., **CUTOFF**).
4. Move a physical knob or slider on your external MIDI controller.
5. The on-screen knob is now assigned! Tap **MIDI LEARN** again to exit mapping mode.

### Building a Layered Sequence
1. Start with an **FM Drum** track. Navigate to **Sequencing** and tap in a basic Kick and Snare pattern.
2. Switch to a **Subtractive** track.
3. Go to the **Playing** screen, press **RECORD** on the transport bar, and play a bassline live on the pads.
4. The sequencer records your notes onto the grid.
5. Go to the **Sequencing** screen for that track and long-press a few notes to change their **VELOCITY** for added groove and dynamics.
