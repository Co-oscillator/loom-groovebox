package com.groovebox.midi

import androidx.compose.runtime.State

expect class MidiManager {
    val midiLog: State<String>
    val deviceName: State<String>
    fun sendMidi(data: ByteArray)
    fun close()
}
