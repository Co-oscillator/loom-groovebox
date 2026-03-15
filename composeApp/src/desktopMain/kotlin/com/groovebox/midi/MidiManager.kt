package com.groovebox.midi

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

actual class MidiManager {
    actual val midiLog: State<String> = mutableStateOf("MIDI: Not supported on Desktop yet")
    actual val deviceName: State<String> = mutableStateOf("No Device")
    
    actual fun sendMidi(data: ByteArray) {
        // TODO: Implement MIDI for Desktop
    }
    actual fun close() {
        // TODO: Implement MIDI for Desktop
    }
}
