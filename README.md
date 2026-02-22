# Loom Groovebox

A professional, low-latency Android groovebox built on a hybrid C++ (Oboe) and Kotlin (Compose) architecture. Designed for real-time sound design, complex sequencing, and high-performance audio synthesis on mobile devices.

[Read the User Manual](UserManual.md)

## Feature Demos
Check out the latest features in action on the [Loom Audio YouTube Channel](https://www.youtube.com/@LoomGroovebox).

- [Arpeggiator modes and chord progression generator](https://www.youtube.com/watch?v=YlBpx7S0_Po)
- [FM synthesis engine and randomized parameter button](https://www.youtube.com/watch?v=uVyzNNPYyPY)
- [Sample slicing and chop loops feature](https://www.youtube.com/watch?v=y2wgiXaNp20)
- [Granular Engine](https://www.youtube.com/watch?v=1apCxMzsy-Q)
- [Sample scrub](https://www.youtube.com/watch?v=hznzb8Tw_sY)
- [FM drum engine, multitrack sequencer, bit crusher effect](https://www.youtube.com/watch?v=zTfICTmZr60)
- [Arpeggiator and LFO Modulation](https://www.youtube.com/watch?v=mSK8OcSM5A0)

![Loom UI](docs/images/loom_hero.jpg)

## Core Systems & Features

### Audio Engine Architecture
- **Native performance**: DSP core implemented in C++ using the Oboe library for minimal latency.
- **NEON Optimized**: Leverages ARM SIMD instructions for intensive tasks like oscillator banks and filter processing.
- **Engine Types**:
  - **Subtractive Synth**: Dual oscillators with multi-waveform support, sub-osc, and resonant SVF filters.
  - **FM Synth**: 6-Operator frequency modulation with flexible routing matrix and feedback.
  - **Sampler**: Disk-streaming capable with real-time time-stretching, pitch-shifting, and slicing.
  - **Granular**: Stochastic grain emission with variable density, size, and spray parameters.
  - **Wavetable**: Phase-distortion warping and morphing across single-cycle waveform frames.
  - **Drum Engines**: Specialized models for analog-style modeling and FM-driven percussion.

### Logic & Sequencing
- **Polyphonic Sequencer**: 64-step grid supporting parameter automation (P-Locks) per step.
- **Rhythmic Complexity**: Sub-step micro-timing, per-step probability, and independent track lengths for polymetric patterns.
- **Arpeggiator**: Procedural pattern generator with rhythmic lanes, chord mutation logic, and scale quantization.

### Modulation & Effects
- **Parallel/Serial Routing**: Flexible FX chain allowing for traditional pedalboard order or parallel wet/dry mixing.
- **Modulation Matrix**: Assign LFOs (BPM-synced or free-running) and macros to any reachable engine or effect parameter.
- **Signal Processing**: High-quality delay, shimmer reverb, bitcrushing, and bus compression.

## Technical Requirements
- **Hardware**: Best performance achieved on devices with AAudio support.
- **Latency**: Audio buffers are automatically tuned; manually adjustable in settings for ultra-low latency hardware.
- **Build**: Built with Android Studio using NDK 27+ and CMake.

## License
Loom Groovebox is licensed under the **GNU GPL v3**. 
(See included LICENSE file for full text).
