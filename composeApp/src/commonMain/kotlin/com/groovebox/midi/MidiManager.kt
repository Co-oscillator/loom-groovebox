package com.groovebox.midi

import androidx.compose.runtime.State

expect class MidiManager {
    val midiLog: State<String>
    val deviceName: State<String>

    /** Currently attached ports, in a stable order. */
    val devices: State<List<MidiDeviceInfo>>

    /** deviceId -> last activity timestamp (ms). Used for UI activity dots. */
    val deviceActivity: State<Map<String, Long>>

    /** Broadcast to every enabled output port. */
    fun sendMidi(data: ByteArray)

    /**
     * Send to one device id, or broadcast when [deviceId] is null.
     * Per-device channel remap / mute / disable still applies.
     */
    fun sendMidiTo(deviceId: String?, data: ByteArray)

    /** Re-enumerate devices (hotplug is also handled automatically). */
    fun refreshDevices()

    fun close()
}
