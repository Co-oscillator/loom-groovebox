package com.groovebox.midi

import android.content.Context
import android.media.midi.*
import android.os.Handler
import android.os.Looper
import android.util.Log

class MidiManager(context: Context, private val onMessageReceived: (ByteArray) -> Unit) {
    private val midiManager = context.getSystemService(Context.MIDI_SERVICE) as android.media.midi.MidiManager
    private var midiDevice: MidiDevice? = null
    private var inputPort: MidiInputPort? = null
    private var outputPort: MidiOutputPort? = null

    init {
        val devices = midiManager.devices
        devices.forEach { device ->
            // Connect to everything for now to support generic MIDI interfaces
            connectToDevice(device)
        }

        midiManager.registerDeviceCallback(object : android.media.midi.MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                connectToDevice(device)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun connectToDevice(deviceInfo: MidiDeviceInfo) {
        midiManager.openDevice(deviceInfo, { device ->
            midiDevice = device
            Log.d("MidiManager", "Connected to EMP16: ${deviceInfo.properties.getString(MidiDeviceInfo.PROPERTY_NAME)}")
            
            // Open first output port (data FROM device)
            outputPort = device.openOutputPort(0)
            outputPort?.connect(object : MidiReceiver() {
                override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    val message = data.copyOfRange(offset, offset + count)
                    onMessageReceived(message)
                }
            })

            // Open first input port (data TO device, e.g. LEDs)
            inputPort = device.openInputPort(0)
        }, Handler(Looper.getMainLooper()))
    }

    fun sendMidi(data: ByteArray) {
        inputPort?.send(data, 0, data.size)
    }

    fun close() {
        inputPort?.close()
        outputPort?.close()
        midiDevice?.close()
    }
}
