package com.groovebox.midi

import com.groovebox.NativeLib

class MidiRouter(private val nativeLib: NativeLib, private val onBankChange: (Int) -> Unit = {}) {
    
    fun processMidiMessage(message: ByteArray, state: com.groovebox.GrooveboxState) {
        if (message.size < 3) return

        val status = message[0].toInt() and 0xFF
        val data1 = message[1].toInt() and 0x7F
        val data2 = message[2].toInt() and 0x7F

        val midiChan = (status and 0x0F) + 1 // 1-16
        val msgType = status and 0xF0

        state.tracks.forEachIndexed { trackIdx, track ->
            val listensOnAll = track.midiInChannel == 17
            val listensOnChan = track.midiInChannel == midiChan
            
            if (listensOnAll || listensOnChan) {
                when (msgType) {
                    0x90 -> { // Note On
                        if (data2 > 0) {
                            nativeLib.triggerNote(trackIdx, data1, data2)
                        } else {
                            nativeLib.releaseNote(trackIdx, data1)
                        }
                    }
                    0x80 -> { // Note Off
                        nativeLib.releaseNote(trackIdx, data1)
                    }
                }
            }
        }

        if (msgType == 0xB0) { // Control Change (CC)
            handleCC(data1, data2, state)
        }
    }

    private fun handleCC(ccNumber: Int, value: Int, state: com.groovebox.GrooveboxState) {
        // Map knobs/faders to parameters
        if (ccNumber in 10..17) {
            val trackIndex = ccNumber - 10
            val normalizedVolume = value / 127.0f
            nativeLib.setTrackVolume(trackIndex, normalizedVolume)
        }

        // CC 24-31: Control the 8 software Strips/Knobs
        if (ccNumber in 24..31) {
            val stripIdx = ccNumber - 24
            val normalizedValue = value / 127.0f
            
            val routing = if (stripIdx < 4) {
                state.stripRoutings.getOrNull(stripIdx)
            } else {
                state.knobRoutings.getOrNull(stripIdx - 4)
            }

            routing?.let {
                if (it.targetType == 1) { // Track Parameter
                    nativeLib.setParameter(state.selectedTrackIndex, it.targetId, normalizedValue)
                } else if (it.targetType == 2) { // Global FX
                    nativeLib.setParameter(0, it.targetId, normalizedValue)
                }
            }
        }
        
        // EMP16 Bank Buttons A, B, C, D (Assuming CC 20-23)
        if (ccNumber in 20..23 && value > 0) {
            onBankChange(ccNumber - 20)
        }
    }
}
