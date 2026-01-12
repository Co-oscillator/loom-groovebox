package com.groovebox.midi

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper
import android.util.Log

class MidiManager(context: Context, private val onMessageReceived: (ByteArray) -> Unit) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as android.media.midi.MidiManager
    private val connectedDevices = mutableListOf<MidiDevice>()
    private val inputPorts = mutableListOf<MidiInputPort>()
    private val outputPorts = mutableListOf<MidiOutputPort>()
    private val pendingMessages = mutableListOf<ByteArray>()
    val midiLog = androidx.compose.runtime.mutableStateOf("MIDI: Init...")
    val deviceName = androidx.compose.runtime.mutableStateOf("Searching...")
    private fun logToUi(msg: String) {
        val current = midiLog.value.split("\n").takeLast(14).toMutableList()
        current.add(msg)
        midiLog.value = current.joinToString("\n")
    }

    init {
        val devices = midiManager.devices
        val logMsg = "MidiManager Init: Found ${devices.size} devices"
        Log.e("MidiManager", "@@@ $logMsg")
        logToUi(logMsg)
        devices.forEach { device ->
            connectToDevice(device)
        }

        midiManager.registerDeviceCallback(object : android.media.midi.MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                connectToDevice(device)
            }
            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                // Potential cleanup here if needed, but close() handles the main list
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun connectToDevice(deviceInfo: MidiDeviceInfo) {
        midiManager.openDevice(deviceInfo, { device ->
            connectedDevices.add(device)
            val name = deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown"
            deviceName.value = name
            Log.e("MidiManager", "@@@ CONNECTED TO DEVICE: $name")
            logToUi("Connected: $name")
            
            // Open ALL output ports (data FROM device)
            deviceInfo.outputPortCount.let { count ->
                for (i in 0 until count) {
                    val outPort = device.openOutputPort(i)
                    if (outPort != null) {
                        outputPorts.add(outPort)
                        Log.e("MidiManager", "@@@ Opened Output Port $i for $name")
                        outPort.connect(object : MidiReceiver() {
                            override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                val message = data.copyOfRange(offset, offset + count)
                                val hex = message.joinToString(" ") { String.format("%02X", it) }
                                Log.e("MidiManager", "@@@ RAW MIDI IN (Port $i): $hex")
                                onMessageReceived(message)
                            }
                        })
                    }
                }
            }

            // Open ALL input ports (data TO device, e.g. LEDs)
            val newPorts = mutableListOf<MidiInputPort>()
            deviceInfo.inputPortCount.let { count ->
                for (i in 0 until count) {
                    val inPort = device.openInputPort(i)
                    if (inPort != null) {
                        inputPorts.add(inPort)
                        newPorts.add(inPort)
                        Log.e("MidiManager", "@@@ Opened Input Port $i for $name")
                    }
                }
            }

            // Flush pending messages if we opened new ports (Handshake support)
            if (newPorts.isNotEmpty() && pendingMessages.isNotEmpty()) {
                Log.e("MidiManager", "@@@ Flushing ${pendingMessages.size} buffered messages to ${newPorts.size} new ports")
                logToUi("Flushing ${pendingMessages.size} msgs...")
                pendingMessages.forEach { msg ->
                    val hex = msg.joinToString(" ") { String.format("%02X", it) }
                    newPorts.forEach { port ->
                        try {
                            port.send(msg, 0, msg.size)
                            Log.e("MidiManager", "@@@ FLUSHED: $hex")
                            logToUi("FLUSHED: $hex")
                        } catch (e: Exception) {
                            Log.e("MidiManager", "Error flushing MIDI: ${e.message}")
                            logToUi("Flush Error: ${e.message}")
                        }
                    }
                }
                pendingMessages.clear()
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun sendMidi(data: ByteArray) {
        if (inputPorts.isEmpty()) {
            val hex = data.joinToString(" ") { String.format("%02X", it) }
            Log.e("MidiManager", "@@@ Buffering message (No ports open): $hex")
            // Don't spam UI with buffering logs, keep it on Init/Connect
            pendingMessages.add(data.copyOf())
            return
        }
        val hex = data.joinToString(" ") { String.format("%02X", it) }
        Log.e("MidiManager", "@@@ SEND MIDI OUT: $hex (Broadcasting to ${inputPorts.size} ports)")
        
        inputPorts.forEach { port ->
            try {
                port.send(data, 0, data.size)
            } catch (e: Exception) {
                Log.e("MidiManager", "Error sending MIDI to port: ${e.message}")
                logToUi("Send Error: ${e.message}")
            }
        }
    }

    fun close() {
        inputPorts.forEach { it.close() }
        outputPorts.forEach { it.close() }
        connectedDevices.forEach { it.close() }
        inputPorts.clear()
        outputPorts.clear()
        connectedDevices.clear()
    }
}
