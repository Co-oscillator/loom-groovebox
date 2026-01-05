package com.groovebox

enum class ScaleType(val displayName: String, val intervals: List<Int>) : java.io.Serializable {
    CHROMATIC("Chromatic", (0..11).toList()),
    MAJOR("Major", listOf(0, 2, 4, 5, 7, 9, 11)),
    NATURAL_MINOR("Natural Minor", listOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR("Harmonic Minor", listOf(0, 2, 3, 5, 7, 8, 11)),
    MELODIC_MINOR("Melodic Minor", listOf(0, 2, 3, 5, 7, 9, 11)),
    PENTATONIC_MAJOR("Pentatonic Major", listOf(0, 2, 4, 7, 9)),
    PENTATONIC_MINOR("Pentatonic Minor", listOf(0, 3, 5, 7, 10)),
    BLUES("Blues", listOf(0, 3, 5, 6, 7, 10)),
    DORIAN("Dorian", listOf(0, 2, 3, 5, 7, 9, 10)),
    PHRYGIAN("Phrygian", listOf(0, 1, 3, 5, 7, 8, 10)),
    LYDIAN("Lydian", listOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLYDIAN("Mixolydian", listOf(0, 2, 4, 5, 7, 9, 10)),
    LOCRIAN("Locrian", listOf(0, 1, 3, 5, 6, 8, 10)),
    WHOLE_TONE("Whole Tone", listOf(0, 2, 4, 6, 8, 10)),
    DIMINISHED_WH("Diminished (W-H)", listOf(0, 2, 3, 5, 6, 8, 9, 11)),
    DIMINISHED_HW("Diminished (H-W)", listOf(0, 1, 3, 4, 6, 7, 9, 10)),
    AUGMENTED("Augmented", listOf(0, 3, 4, 7, 8, 11)),
    ENIGMATIC("Enigmatic", listOf(0, 1, 4, 6, 8, 10, 11)),
    NEAPOLITAN_MAJOR("Neapolitan Major", listOf(0, 1, 3, 5, 7, 9, 11)),
    NEAPOLITAN_MINOR("Neapolitan Minor", listOf(0, 1, 3, 5, 7, 8, 11)),
    BEBOP_MAJOR("Bebop Major", listOf(0, 2, 4, 5, 7, 8, 9, 11)),
    BEBOP_MINOR("Bebop Minor", listOf(0, 2, 3, 4, 5, 7, 9, 10)),
    SPANISH_GYPSY("Spanish", listOf(0, 1, 4, 5, 7, 8, 10)),
    FLAMENCO("Flamenco", listOf(0, 1, 3, 4, 5, 7, 8, 10))
}

object ScaleLogic {
    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun getMidiNoteName(midiNote: Int): String {
        val name = NOTE_NAMES[midiNote % 12]
        val octave = (midiNote / 12) - 1
        return "$name$octave"
    }

    fun generateScaleNotes(rootNote: Int, scaleType: ScaleType, count: Int): List<Int> {
        val notes = mutableListOf<Int>()
        var currentOctave = 0
        while (notes.size < count) {
            for (interval in scaleType.intervals) {
                val note = rootNote + (currentOctave * 12) + interval
                if (note <= 127) {
                    notes.add(note)
                }
                if (notes.size >= count) break
            }
            currentOctave++
            if (rootNote + (currentOctave * 12) > 127) break
        }
        return notes
    }
}
