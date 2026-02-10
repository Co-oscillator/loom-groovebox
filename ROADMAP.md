# Loom Groovebox Roadmap

This file tracks outstanding features and long-term goals for the Loom Groovebox project. Items here are persistent and safe from agent session resets.

## Outstanding Features

## Outstanding Features

- [x] **Humanize**: Global control. Act on active steps to add random variations in microtiming (up to +/- 1 substep) and velocity (+/- 10%).
- [ ] **Fix Loaded Sequences**: Loaded sequences display correctly but do not trigger audio until edited. Needs to trigger immediately upon load.
- [ ] **Transpose Sequences**: UI with "Note +/-" and "Octave +/-" to transpose the entire current sequence. Active settings highlighted.
- [ ] **System Audio Sampling**: internal audio as a record source for Sampler/Granular engines (requires permission handling).
- [ ] **Slice Locks**: Per-slice parameter locking for chopped samples, allowing individual shaping of each slice in a kit.
- [ ] **Song Mode / Pattern Chaining**: A queue system to assemble a sequence of saved sequences for each track, allowing independent chaining (e.g., long lead vs short drum loop).
- [ ] **Slicer Cleanup**: Address duplicate/inactive code in Slicer engine.
- [ ] **Effects Suite Completion**: Finish implementing remaining effects (Hall Reverb, Tape Wobble, etc.).

## Completed Features

- [x] **Mixer Fixes**: Resolved unresponsive track issues.
- [x] **Export**: Audio export implemented.
- [x] **Effects Suite**: All effects implemented (Hall Reverb, Tape Wobble, Slicer, etc.).
- [x] **Slicer Cleanup**: Refactored SlicerFx to remove duplicate code.

