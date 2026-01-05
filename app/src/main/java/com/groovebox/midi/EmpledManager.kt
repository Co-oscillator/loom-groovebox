package com.groovebox.midi

class EmpledManager(private val midiManager: MidiManager) {
    
    enum class PageColor(val r: Int, val g: Int, val b: Int) {
        PLAYING(255, 0, 255),    // Purple
        SEQUENCING(0, 255, 0),  // Green
        PARAMETERS(255, 165, 0), // Orange
        EFFECTS(0, 0, 255),     // Blue
        ROUTING(255, 255, 0),   // Yellow
        SETTINGS(128, 128, 128) // Gray
    }

    fun updatePageColors(pageIndex: Int, baseColor: PageColor) {
        // Calculate a shade based on pageIndex (0-7)
        // We'll dim the color for higher pages, or shift its hue
        // For simplicity: Scale intensity. Page 0 = 100%, Page 7 = 30%
        val scale = 1.0f - (pageIndex * 0.1f)
        
        repeat(16) { pad ->
            val sysex = byteArrayOf(
                0xF0.toByte(), 0x00, 0x20, 0x6B, 
                pad.toByte(), 
                ((baseColor.r * scale) / 2).toInt().toByte(), 
                ((baseColor.g * scale) / 2).toInt().toByte(), 
                ((baseColor.b * scale) / 2).toInt().toByte(), 
                0xF7.toByte()
            )
            midiManager.sendMidi(sysex)
        }
    }

    fun updatePageColorsRGB(pageIndex: Int, r: Int, g: Int, b: Int) {
        val scale = 1.0f - (pageIndex * 0.1f)
        repeat(16) { pad ->
            val sysex = byteArrayOf(
                0xF0.toByte(), 0x00, 0x20, 0x6B, 
                pad.toByte(), 
                ((r * scale) / 2).toInt().toByte(), 
                ((g * scale) / 2).toInt().toByte(), 
                ((b * scale) / 2).toInt().toByte(), 
                0xF7.toByte()
            )
            midiManager.sendMidi(sysex)
        }
    }

    fun updatePadColors(page: PageColor) {
        updatePageColorsRGB(0, page.r, page.g, page.b)
    }

    fun updatePadColor(padIndex: Int, r: Int, g: Int, b: Int) {
        if (padIndex !in 0..15) return
        val sysex = byteArrayOf(
            0xF0.toByte(), 0x00, 0x20, 0x6B,
            padIndex.toByte(),
            (r / 2).toByte(),
            (g / 2).toByte(),
            (b / 2).toByte(),
            0xF7.toByte()
        )
        midiManager.sendMidi(sysex)
    }
}
