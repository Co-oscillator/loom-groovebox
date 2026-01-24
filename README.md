# Loom Groovebox

A powerful, native Android groovebox featuring a hybrid C++/Kotlin audio engine, advanced sequencing, and deep sound design capabilities.

![Loom UI](docs/images/loom_hero.jpg)

## Key Features

### Audio Engine
- **Hybrid Architecture**: Low-latency C++ DSP (Oboe) with Kotlin for UI and state management.
- **Multiple Synthesis Engines**:
  - **Subtractive**: Virtual Analog with dual oscillators, sub-oscillator, and resonant filters.
  - **FM**: 6-Operator FM synthesis with matrix routing and feedback.
  - **Sampling**: Disk-streaming sampler with slicing and chopping.
  - **Granular**: Real-time granular synthesis with variable grain size and density.
  - **Wavetable**: Morphing wavetable synthesis.
  - **Drum Engines**: Dedicated Analog and FM drum synthesizers.

### Sequencing & Composition
- **Advanced Sequencer**: 64-step polyphonic sequencer with parameter locks, probability, and conditional trigs.
- **Micro-Timing**: Sub-step timing offsets for humanized grooves.
- **Polyrhythms**: Independent track lengths and speeds.
- **Arpeggiator**: Deep arpeggiator with 16-step rhythm editor, chord mutations, and latching.

### Effects Processing
- **Pedalboard Architecture**: Modular FX chain with insert and send effects.
- **Creative FX**:
  - **Triple Filter**: 3-stage SVF filter bank (LP/HP/BP).
  - **Tape Wobble**: Stereo-linked tape saturation and pitch instability.
  - **Galactic Reverb**: Infinite decay shimmer reverb.
  - **Slicer**: Rhythmic audio gating.
  - **Compressor**: Bus compressor with sidechain support.

## Technical Highlights
- **Performance**: Optimized for mobile devices using lock-free audio ring buffers and NEON SIMD instructions.
- **State Persistence**: robust JSON-based project saving and loading.
- **Architecture**: Clean separation of concerns between DSP (native-lib) and UI (Compose).

## Getting Started
1. Open the project in Android Studio.
2. Build and Run on a connected Android device or high-performance emulator.
3. Audio latency settings are automatically tuned for the device.

## License
MIT License.
