package com.groovebox.utils

import com.groovebox.EngineType
import com.groovebox.GridMode
import com.groovebox.GrooveboxState
import com.groovebox.PadMode
import com.groovebox.TrackState

/** Rows in the single-grid pad layout (TONNETZ/MAC_KEYS keep their own shape). */
fun GridMode.padRows(): Int = when (this) {
    GridMode.GRID_4X4 -> 4
    GridMode.GRID_6X6 -> 6
    GridMode.MAC_KEYS -> 4
    GridMode.TONNETZ -> 4
}

/** Columns in the single-grid pad layout. */
fun GridMode.padCols(): Int = when (this) {
    GridMode.GRID_4X4 -> 4
    GridMode.GRID_6X6 -> 6
    GridMode.MAC_KEYS -> 13
    GridMode.TONNETZ -> 4
}

/** Number of scale/chromatic notes a grid needs (MAC_KEYS is a special case). */
fun GridMode.padCount(): Int = if (this == GridMode.MAC_KEYS) 46 else padRows() * padCols()

/** Short label for the GRID button. */
fun GridMode.shortLabel(): String = when (this) {
    GridMode.GRID_4X4 -> "4x4"
    GridMode.GRID_6X6 -> "6x6"
    GridMode.MAC_KEYS -> "Keys"
    GridMode.TONNETZ -> "TON"
}

/**
 * Grid modes offered by the GRID button, in order. The split 20/20 layout is
 * offered after the last entry, so it needs no separate control.
 */
fun gridModeCycle(includeMacKeys: Boolean): List<GridMode> = buildList {
    add(GridMode.GRID_4X4)
    add(GridMode.GRID_6X6)
    if (includeMacKeys) add(GridMode.MAC_KEYS)
    add(GridMode.TONNETZ)
}

/**
 * Advances the GRID button one step: walks [gridModeCycle] and ends on the split
 * 20/20 layout before wrapping back to 4x4. Returns the next grid mode and
 * whether the split layout should be shown.
 */
fun nextGridStep(
    gridMode: GridMode,
    isSplit: Boolean,
    includeMacKeys: Boolean
): Pair<GridMode, Boolean> {
    if (isSplit) return GridMode.GRID_4X4 to false
    val cycle = gridModeCycle(includeMacKeys)
    val index = cycle.indexOf(gridMode)
    return when {
        index == -1 -> GridMode.GRID_4X4 to false
        index == cycle.lastIndex -> gridMode to true
        else -> cycle[index + 1] to false
    }
}

/** Analog drum kit: 6 sounds, laid out in groups of 8 (two blank pads). */
fun analogDrumNote(padIndex: Int): Int {
    val local = padIndex % 8
    return if (local < 6) 60 + local else -1
}

/** Sampler slice count from the engine parameters (320 = mode, 340 = count). */
fun sliceCount(track: TrackState): Int =
    (((track.parameters[340] ?: 0f) * 14f).toInt() + 2).coerceIn(2, 16)

fun isChopMode(track: TrackState): Boolean =
    track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) >= 0.6f

/**
 * Note for one pad, honouring the explicit [PadMode].
 *
 * AUTO reproduces the v1 engine-driven mapping; the explicit modes let a
 * drum kit, slice bank, chromatic keyboard or scale grid be played from any
 * track. FX mode has no note (the pad grid renders FX toggles instead).
 */
fun padNoteFor(
    mode: PadMode,
    track: TrackState,
    state: GrooveboxState,
    padIndex: Int,
    scaleNotes: List<Int>
): Int = when (mode) {
    PadMode.AUTO -> when {
        track.engineType == EngineType.FM_DRUM -> 60 + (padIndex % 16)
        track.engineType == EngineType.ANALOG_DRUM -> analogDrumNote(padIndex)
        isChopMode(track) -> if (padIndex < sliceCount(track)) 60 + padIndex else -1
        else -> scaleNotes.getOrElse(padIndex) { state.rootNote + padIndex }
    }
    PadMode.SCALES -> scaleNotes.getOrElse(padIndex) { state.rootNote + padIndex }
    PadMode.KEYBOARD -> state.rootNote + padIndex
    PadMode.FM_DRUM -> 60 + (padIndex % 16)
    PadMode.ANALOG_DRUM -> analogDrumNote(padIndex)
    PadMode.SLICES -> if (padIndex < sliceCount(track)) 60 + padIndex else -1
    PadMode.FX -> -1
}

/** Human-readable short label for the pad-mode cycle button. */
fun PadMode.shortLabel(): String = when (this) {
    PadMode.AUTO -> "AUTO"
    PadMode.SCALES -> "SCL"
    PadMode.KEYBOARD -> "KEY"
    PadMode.FM_DRUM -> "FMD"
    PadMode.ANALOG_DRUM -> "AND"
    PadMode.SLICES -> "SLC"
    PadMode.FX -> "FX"
}

/** 18 FX bus names, matching the native FX order. */
val FX_BUS_NAMES: List<String> = listOf(
    "Overdrive", "Bitcrush", "Chorus", "Phaser", "Wobble", "Delay",
    "Reverb", "Slicer", "Compressor", "HP LFO", "LP LFO", "Flanger",
    "Filter 1", "Tape Echo", "Octaver", "Filter 2", "Filter 3", "5-Band EQ"
)
