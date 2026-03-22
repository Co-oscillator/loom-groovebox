package com.groovebox.midi

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import javax.sound.midi.*

actual class MidiManager(private val onMessageReceived: (ByteArray) -> Unit) {
    private val _midiLog = mutableStateOf("MIDI: Desktop Init...")
    private val _deviceName = mutableStateOf("Searching...")
    actual val midiLog: State<String> = _midiLog
    actual val deviceName: State<String> = _deviceName

    private val receivers = mutableListOf<Receiver>()
    private val transmitters = mutableListOf<Transmitter>()
    private val openDevices = mutableListOf<MidiDevice>()

    private fun logToUi(msg: String) {
        val lines = _midiLog.value.split("\n").takeLast(14).toMutableList()
        lines.add(msg)
        _midiLog.value = lines.joinToString("\n")
    }

    init {
        try {
            val infos = MidiSystem.getMidiDeviceInfo()
            logToUi("Found ${infos.size} MIDI devices")
            
            infos.forEach { info ->
                try {
                    val device = MidiSystem.getMidiDevice(info)
                    // We want devices that can PROVIDE MIDI (transmitters)
                    if (device.maxTransmitters != 0) {
                        device.open()
                        openDevices.add(device)
                        
                        val transmitter = device.transmitter
                        transmitters.add(transmitter)
                        
                        val receiver = object : Receiver {
                            override fun send(message: MidiMessage?, timeStamp: Long) {
                                if (message is ShortMessage) {
                                    val data = message.message
                                    // val hex = data.joinToString(" ") { String.format("%02X", it) }
                                    onMessageReceived(data)
                                }
                            }
                            override fun close() {}
                        }
                        transmitter.receiver = receiver
                        receivers.add(receiver)
                        
                        _deviceName.value = info.name
                        logToUi("Connected: ${info.name}")
                    }
                } catch (e: Exception) {
                    // Skip devices we can't open
                }
            }
        } catch (e: Exception) {
            logToUi("Error: ${e.message}")
        }
    }

    actual fun sendMidi(data: ByteArray) {
        // Desktop Output not yet implemented
    }

    actual fun close() {
        transmitters.forEach { it.close() }
        receivers.forEach { it.close() }
        openDevices.forEach { it.close() }
        transmitters.clear()
        receivers.clear()
        openDevices.clear()
    }
}
