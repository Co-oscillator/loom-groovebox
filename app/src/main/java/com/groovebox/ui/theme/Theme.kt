package com.groovebox.ui.theme

import androidx.compose.ui.graphics.Color
import com.groovebox.EngineType

fun getEngineColor(type: EngineType): Color = when (type) {
    EngineType.SUBTRACTIVE -> Color.Cyan
    EngineType.FM -> Color(0xFF00FF00)
    EngineType.SAMPLER -> Color(0xFFFFD700) // Gold
    EngineType.GRANULAR -> Color.Magenta
    EngineType.WAVETABLE -> Color.Blue
    EngineType.FM_DRUM -> Color.Red
    EngineType.ANALOG_DRUM -> Color(0xFFCDDC39) // Lime
    EngineType.MIDI -> Color.Gray
    EngineType.AUDIO_IN -> Color(0xFF4B0082) // Eggplant
    EngineType.SOUNDFONT -> Color(0xFF87CEEB) // Sky Blue
}
