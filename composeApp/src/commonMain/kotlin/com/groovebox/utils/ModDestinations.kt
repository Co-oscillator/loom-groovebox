package com.groovebox.utils

import com.groovebox.EngineType

/**
 * True when [id] is a destination the track's engine can modulate per voice.
 *
 * Macros, FX and other global destinations must use the global parameter path
 * instead, otherwise a per-voice gesture would silently move the engine's
 * fallback parameter. Shared by the pad gestures (PlayingScreen) and the MIDI
 * expression router so both agree on what "per voice" means.
 */
fun isPerVoiceModDest(engineType: EngineType, id: Int): Boolean = when (engineType) {
    EngineType.SUBTRACTIVE -> id in 1..3 || id in 107..110 || id == 112 ||
        id == 113 || id == 118 || id in 160..163 || id in 170..173 || id in 180..183
    EngineType.FM -> id == 1 || id == 2 || id == 151 || id == 152 ||
        id == 154 || id == 159 || id == 160 || id == 166 || id == 172 ||
        id == 178 || id == 184 || id == 190
    EngineType.WAVETABLE -> id == 450 || id == 451 || id == 458 || id == 459 ||
        id == 464 || id == 465 || id == 466 || id == 467 || id == 475 ||
        id == 476 || id == 1 || id == 2
    EngineType.SAMPLER -> id == 300 || id == 302 || id == 303 || id == 304 ||
        id == 314 || id == 1 || id == 2
    else -> false
}
