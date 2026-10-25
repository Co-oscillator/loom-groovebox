package com.groovebox.midi

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.MidiUnavailableException
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage
import javax.sound.midi.Transmitter

actual class MidiManager(private val onMessageReceived: (ByteArray, String?) -> Unit) {
    private data class Entry(
        val info: MidiDeviceInfo,
        val device: MidiDevice,
        val transmitter: Transmitter?,
        val receiver: Receiver?
    )

    private val _midiLog = mutableStateOf("MIDI: Desktop Init...")
    private val _deviceName = mutableStateOf("Searching...")
    private val _devices = mutableStateOf<List<MidiDeviceInfo>>(emptyList())
    private val _deviceActivity = mutableStateOf<Map<String, Long>>(emptyMap())

    actual val midiLog: State<String> = _midiLog
    actual val deviceName: State<String> = _deviceName
    actual val devices: State<List<MidiDeviceInfo>> = _devices
    actual val deviceActivity: State<Map<String, Long>> = _deviceActivity

    private val registry = mutableMapOf<String, Entry>()
    private var isClosed = false

    private fun logToUi(msg: String) {
        val lines = _midiLog.value.split("\n").takeLast(14).toMutableList()
        lines.add(msg)
        _midiLog.value = lines.joinToString("\n")
    }

    init {
        MidiConfigStore.load()
        scanDevices()

        Thread {
            while (!isClosed) {
                try {
                    Thread.sleep(3000)
                    if (!isClosed) scanDevices()
                } catch (e: Exception) {
                    // Ignore and retry
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stableId(info: javax.sound.midi.MidiDevice.Info): String {
        val parts = listOf(info.name, info.vendor, info.version, info.description)
            .filter { !it.isNullOrBlank() }
        return if (parts.isEmpty()) "desktop-midi-${info.hashCode()}" else parts.joinToString("|")
    }

    @Synchronized
    private fun scanDevices() {
        try {
            val infos = MidiSystem.getMidiDeviceInfo()
            val presentIds = infos.map { stableId(it) }.toSet()

            registry.keys.toList().filter { it !in presentIds }.forEach { id ->
                val name = registry[id]?.info?.name ?: id
                closeEntry(id)
                logToUi("Disconnected: $name")
            }

            infos.forEach { info ->
                val id = stableId(info)
                if (registry.containsKey(id)) return@forEach

                try {
                    val device = MidiSystem.getMidiDevice(info)
                    val canInput = device.maxTransmitters != 0
                    val canOutput = device.maxReceivers != 0
                    if (!canInput && !canOutput) return@forEach

                    device.open()
                    val transmitter = if (canInput) device.transmitter else null
                    val receiver = if (canOutput) {
                        try { device.receiver } catch (e: MidiUnavailableException) { null }
                    } else null

                    transmitter?.receiver = object : Receiver {
                        override fun send(message: MidiMessage, timeStamp: Long) {
                            handleIncoming(id, message.message)
                        }

                        override fun close() {}
                    }

                    registry[id] = Entry(
                        info = MidiDeviceInfo(
                            id = id,
                            name = info.name,
                            vendor = info.vendor,
                            isInput = canInput,
                            isOutput = canOutput
                        ),
                        device = device,
                        transmitter = transmitter,
                        receiver = receiver
                    )
                    _deviceName.value = info.name
                    logToUi("Connected: ${info.name} (${if (canInput) "IN" else ""}${if (canOutput) "OUT" else ""})")
                } catch (e: Exception) {
                    logToUi("Skip ${info.name}: ${e.message}")
                }
            }
            publishDevices()
        } catch (e: Exception) {
            logToUi("Scan Error: ${e.message}")
        }
    }

    @Synchronized
    private fun closeEntry(id: String) {
        val entry = registry.remove(id) ?: return
        runCatching { entry.transmitter?.close() }
        runCatching { entry.receiver?.close() }
        runCatching { entry.device.close() }
        _deviceActivity.value = _deviceActivity.value - id
    }

    private fun publishDevices() {
        _devices.value = registry.values
            .map { it.info }
            .sortedBy { it.name.lowercase() }
    }

    private fun markActivity(deviceId: String) {
        val now = System.currentTimeMillis()
        val previous = _deviceActivity.value[deviceId] ?: 0L
        if (now - previous > 400) {
            _deviceActivity.value = _deviceActivity.value + (deviceId to now)
        }
    }

    private fun handleIncoming(deviceId: String, message: ByteArray) {
        if (message.isEmpty()) return
        val config = MidiConfigStore.configFor(deviceId)
        if (!config.enabled || config.ignoreIncoming) return
        if (config.receiveChannel in 1..16 &&
            !MidiFilters.matchesChannel(message, config.receiveChannel)) return

        markActivity(deviceId)
        onMessageReceived(message, deviceId)
    }

    private fun toMidiMessage(data: ByteArray): MidiMessage? {
        if (data.isEmpty()) return null
        val status = data[0].toInt() and 0xFF
        val d1 = (data.getOrNull(1)?.toInt() ?: 0) and 0x7F
        val d2 = (data.getOrNull(2)?.toInt() ?: 0) and 0x7F
        return try {
            when {
                status == 0xF0 -> {
                    val sysex = SysexMessage()
                    sysex.setMessage(data, data.size)
                    sysex
                }
                status >= 0xF8 -> {
                    val msg = ShortMessage()
                    msg.setMessage(status)
                    msg
                }
                status == 0xF1 || status == 0xF3 || status == 0xF2 -> {
                    val msg = ShortMessage()
                    msg.setMessage(status, d1, d2)
                    msg
                }
                status in 0x80..0xEF -> {
                    val msg = ShortMessage()
                    msg.setMessage(status, d1, d2)
                    msg
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun sendToDevice(deviceId: String, message: MidiMessage) {
        val config = MidiConfigStore.configFor(deviceId)
        if (!config.enabled || config.muteOutgoing || config.sendChannel == 0) return
        val entry = registry[deviceId] ?: return
        val receiver = entry.receiver ?: return

        val payload = if (config.sendChannel in 1..16 && message.length >= 1 &&
            (message.message[0].toInt() and 0xFF) < 0xF0) {
            toMidiMessage(MidiFilters.remapChannel(message.message, config.sendChannel))
        } else {
            message
        } ?: return

        runCatching {
            receiver.send(payload, -1)
            markActivity(deviceId)
        }.onFailure { logToUi("Send Error: ${it.message}") }
    }

    @Synchronized
    actual fun sendMidi(data: ByteArray) {
        val message = toMidiMessage(data) ?: return
        registry.keys.toList().forEach { sendToDevice(it, message) }
    }

    @Synchronized
    actual fun sendMidiTo(deviceId: String?, data: ByteArray) {
        val message = toMidiMessage(data) ?: return
        if (deviceId == null) {
            sendMidi(data)
        } else {
            sendToDevice(deviceId, message)
        }
    }

    @Synchronized
    actual fun refreshDevices() {
        scanDevices()
    }

    @Synchronized
    actual fun close() {
        isClosed = true
        synchronized(this) {
            registry.keys.toList().forEach { closeEntry(it) }
            _devices.value = emptyList()
            _deviceActivity.value = emptyMap()
        }
    }
}
