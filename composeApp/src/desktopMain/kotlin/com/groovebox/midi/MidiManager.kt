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
    private val openDeviceInfos = mutableSetOf<String>()
    private var isClosed = false

    private fun logToUi(msg: String) {
        val lines = _midiLog.value.split("\n").takeLast(14).toMutableList()
        lines.add(msg)
        _midiLog.value = lines.joinToString("\n")
    }

    init {
        // Initial scan
        scanDevices()
        
        // Background scan thread for hot-plugging
        Thread {
            while (!isClosed) {
                try {
                    Thread.sleep(5000)
                    if (!isClosed) scanDevices()
                } catch (e: Exception) {}
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun scanDevices() {
        try {
            val infos = MidiSystem.getMidiDeviceInfo()
            
            infos.forEach { info ->
                val deviceId = "${info.name}-${info.vendor}-${info.version}"
                if (openDeviceInfos.contains(deviceId)) return@forEach
                
                try {
                    val device = MidiSystem.getMidiDevice(info)
                    // We want devices that can PROVIDE MIDI (transmitters)
                    if (device.maxTransmitters != 0) {
                        device.open()
                        openDevices.add(device)
                        openDeviceInfos.add(deviceId)
                        
                        val transmitter = device.transmitter
                        transmitters.add(transmitter)
                        
                        val receiver = object : Receiver {
                            override fun send(message: MidiMessage, timeStamp: Long) {
                                val data = message.message
                                if (data != null && data.isNotEmpty()) {
                                    onMessageReceived(data)
                                }
                            }
                            override fun close() {}
                        }
                        transmitter.receiver = receiver
                        receivers.add(receiver)
                        
                        _deviceName.value = info.name
                        logToUi("Connected: ${info.name}")
                    } else {
                        // Log found devices even if they aren't transmitters
                        // logToUi("Found: ${info.name} (No Input)")
                    }
                } catch (e: Exception) {
                    // Skip devices we can't open
                }
            }
        } catch (e: Exception) {
            logToUi("Scan Error: ${e.message}")
        }
    }

    actual fun sendMidi(data: ByteArray) {
        // Desktop Output not yet implemented
    }

    actual fun close() {
        isClosed = true
        transmitters.forEach { it.close() }
        receivers.forEach { it.close() }
        openDevices.forEach { it.close() }
        transmitters.clear()
        receivers.clear()
        openDevices.clear()
        openDeviceInfos.clear()
    }
}
