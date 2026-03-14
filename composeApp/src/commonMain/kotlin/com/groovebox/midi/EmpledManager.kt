package com.groovebox.midi

import androidx.compose.ui.graphics.Color

expect class EmpledManager {
    fun updatePadColor(padIndex: Int, r: Int, g: Int, b: Int)
    fun updateSequencerPadColor(padIndex: Int, r: Int, g: Int, b: Int, bankIdx: Int)
    fun updatePadColorCompose(padIndex: Int, color: Color)
    fun updateSequencerPadColorCompose(padIndex: Int, color: Color, bankIdx: Int)
    fun sendHandshake()
}
