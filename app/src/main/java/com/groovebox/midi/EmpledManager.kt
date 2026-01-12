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

    fun updatePadColor(padIndex: Int, r: Int, g: Int, b: Int) {
        if (padIndex !in 0..15) return
        val note = 48 + padIndex
        val colorValue = getBestColorIndex(r, g, b)
        
        // Broadcast to both Channel 1 and Channel 10 for maximum compatibility
        val channels = listOf(0x90, 0x99, 0xB0, 0xB9)
        channels.forEach { status ->
            midiManager.sendMidi(byteArrayOf(status.toByte(), note.toByte(), colorValue.toByte()))
        }
    }

    /**
     * Specialized LED update for sequencing pads which are in CC mode (CC 24-55)
     */
    fun updateSequencerPadColor(padIndex: Int, r: Int, g: Int, b: Int, bankIdx: Int) {
        if (padIndex !in 0..15) return
        val ccNum = (bankIdx - 1) * 16 + 24 + padIndex
        if (ccNum !in 24..55) return
        
        val colorValue = getBestColorIndex(r, g, b)
        
        // 1. Send to the SPECIFIC CC/Note address (24-55) on Ch 1 and 10
        val targetAddresses = listOf(0x90 to ccNum, 0x99 to ccNum, 0xB0 to ccNum, 0xB9 to ccNum)
        targetAddresses.forEach { (status, data1) ->
            midiManager.sendMidi(byteArrayOf(status.toByte(), data1.toByte(), colorValue.toByte()))
        }

        // 2. ALSO send to the base PHYSICAL pad address (48-63) 
        // Some devices always map LEDs to physical pad IDs regardless of MIDI mapping config
        updatePadColor(padIndex, r, g, b)
    }

    fun updatePadColorCompose(padIndex: Int, color: androidx.compose.ui.graphics.Color) {
        updatePadColor(
            padIndex,
            (color.red * color.alpha * 255).toInt(),
            (color.green * color.alpha * 255).toInt(),
            (color.blue * color.alpha * 255).toInt()
        )
    }

    fun updateSequencerPadColorCompose(padIndex: Int, color: androidx.compose.ui.graphics.Color, bankIdx: Int) {
        updateSequencerPadColor(
            padIndex,
            (color.red * color.alpha * 255).toInt(),
            (color.green * color.alpha * 255).toInt(),
            (color.blue * color.alpha * 255).toInt(),
            bankIdx
        )
    }

    fun updatePadColors(page: PageColor) {
         repeat(16) { pad ->
             updatePadColor(pad, page.r, page.g, page.b)
         }
    }

    /**
     * Maps RGB to the Avatar EMP16 color index (1-16)
     */
    private fun getBestColorIndex(r: Int, g: Int, b: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (value < 0.05f) return 0 // OFF
        if (sat < 0.15f) return 16 // WHITE
        
        return when {
            hue in 0f..15f || hue > 345f -> 1 // RED
            hue in 15f..35f -> 4 // ORANGE
            hue in 35f..65f -> 6 // YELLOW
            hue in 65f..90f -> 8 // MAY GREEN
            hue in 90f..150f -> 2 // GREEN
            hue in 150f..170f -> 7 // BLUE GREEN
            hue in 170f..200f -> 9 // LIGHT CYAN
            hue in 200f..250f -> 3 // BLUE
            hue in 250f..280f -> 10 // PURPLE
            hue in 280f..310f -> 13 // PEARL VIOLET
            hue in 310f..345f -> 11 // ANCIENT PINK
            else -> 16
        }
    }

    /**
     * Enabling external feedback mode (Donner/Avatar specific heartbeat/handshake)
     */
    fun sendHandshake() {
        // 1. MIDI Identity Request (Often triggers a response or wakes up the device)
        midiManager.sendMidi(byteArrayOf(0xF0.toByte(), 0x7E, 0x7F, 0x06, 0x01, 0xF7.toByte()))

        // 2. Worlde / Panda / EasyControl Handshake (Common EMP16 OEM)
        // MCC Control Mode?
        midiManager.sendMidi(byteArrayOf(0xF0.toByte(), 0x7F, 0x7F, 0x06, 0x05, 0xF7.toByte()))
        
        // 3. Mackie Control Universal (MCU) Enable
        midiManager.sendMidi(byteArrayOf(0xF0.toByte(), 0x00, 0x00, 0x66, 0x14, 0x0C, 0x01, 0xF7.toByte()))

        // 4. Common "Handshake" CCs - BROADCAST TO ALL CHANNELS
        // Some devices only unlock if they receive this on their specific control channel (e.g. 16)
        for (ch in 0..15) {
             midiManager.sendMidi(byteArrayOf((0xB0 + ch).toByte(), 127.toByte(), 127.toByte()))
        }
        
        // 5. Donner/Avatar specific "Enable External LED" SysEx (Keep just in case)
        val sysexEnable = byteArrayOf(
            0xF0.toByte(), 0x00, 0x20, 0x6B, 
            0x7F.toByte(), 0x42, 0x02, 0x00, 0x01, 
            0xF7.toByte()
        )
        midiManager.sendMidi(sysexEnable)
    }

}
