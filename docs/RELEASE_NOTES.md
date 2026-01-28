# Loom Groovebox Release Notes

## v1.14.6 (Build 26)
- **Emergency Fix**: Verified and corrected versioning system.
- **Safety**: Added build safety shield in `check_version.py` to prevent stale builds.
- **Audio**: Attempted restoration of silent pedals (Filters, Echo, Octaver) and Slicer precision.
- **UI**: Attempted fix for Phone FM knob sizing and Soundfont visibility.
- *Note: Issues with Audio and Soundfonts reported as persisting in this build.*

## v1.14.5 (Build 25)
- **Audio**: Addressed silent FX pedals by linking Mix knobs to global bus levels.
- **DSP**: Boosted Bitcrusher and Overdrive intensity.
- **UI**: Fixed Tablet Play Pad vertical stretching.
- **Infrastructure**: Implemented centralized `version.properties`.

## v1.14.4 (Build 24)
- **Patterning**: Added "Fill" and "Invert" buttons to the Pattern Generator.
- **UI**: Cleaned up the Sequencer UI, moving "Clear" to a better location.
- **Bugfixes**: Resolved crash when switching between Arp and Chord modes.

## v1.14.3 (Build 23)
- **Synth**: Added "Wavetable" engine with basic interpolation.
- **FX**: Introduced "Tape Wobble" globally.
- **UI**: Added "Patch Bay" visualization for modulation routing.

## v1.14.2 (Build 22)
- **Core**: Major refactor of the `AudioEngine` class structure.
- **Performance**: Optimized LFO processing to reduce CPU load.
- **Feature**: Added "Randomize" function for synthesizer parameters.
