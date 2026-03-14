package com.groovebox.midi

import androidx.compose.ui.graphics.Color

actual class EmpledManager(private val midiManager: MidiManager) {
    actual fun updatePadColor(padIndex: Int, r: Int, g: Int, b: Int) {}
    actual fun updateSequencerPadColor(padIndex: Int, r: Int, g: Int, b: Int, bankIdx: Int) {}
    actual fun updatePadColorCompose(padIndex: Int, color: Color) {}
    actual fun updateSequencerPadColorCompose(padIndex: Int, color: Color, bankIdx: Int) {}
    actual fun sendHandshake() {}
}
