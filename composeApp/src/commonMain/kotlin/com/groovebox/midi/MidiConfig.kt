package com.groovebox.midi

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.groovebox.persistence.PersistenceManager

/**
 * A platform-neutral description of a MIDI port.
 *
 * [id] must be stable across replugs (derived from manufacturer/product/name,
 * never from the enumeration index).
 */
data class MidiDeviceInfo(
    val id: String,
    val name: String,
    val vendor: String = "",
    val isInput: Boolean = false,
    val isOutput: Boolean = false
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Per-device routing configuration. This is a global preference, not project
 * data, so the same hardware setup follows the user between projects.
 *
 * Channels: -1 = All, 0 = Off, 1..16 = that channel.
 */
data class MidiDeviceConfig(
    val deviceId: String,
    val sendChannel: Int = -1,
    val receiveChannel: Int = -1,
    val muteOutgoing: Boolean = false,
    val ignoreIncoming: Boolean = false,
    val enabled: Boolean = true
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** A named snapshot of the device list, e.g. "Home" or "Live". */
data class MidiHardwarePreset(
    val name: String,
    val configs: List<MidiDeviceConfig> = emptyList()
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

const val CURRENT_MIDI_SETTINGS_VERSION = 1

data class MidiSettings(
    val schemaVersion: Int = CURRENT_MIDI_SETTINGS_VERSION,
    val configs: List<MidiDeviceConfig> = emptyList(),
    val presets: List<MidiHardwarePreset> = emptyList(),
    val activePreset: String? = null
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Global MIDI configuration store.
 *
 * Compose state drives the Settings UI; a volatile snapshot is used by the
 * MIDI receive/send threads so they never touch a Compose snapshot.
 */
object MidiConfigStore {
    var settings by mutableStateOf(MidiSettings())
        private set

    @Volatile
    private var snapshot: MidiSettings = MidiSettings()

    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        val loadedSettings = sanitize(PersistenceManager.loadMidiSettings() ?: MidiSettings())
        settings = loadedSettings
        snapshot = loadedSettings
        loaded = true
    }

    /** Coerce persisted data from older/corrupt files into a valid shape. */
    private fun sanitize(value: MidiSettings): MidiSettings {
        fun channel(raw: Int): Int = when {
            raw == -1 || raw == 0 -> raw
            raw in 1..16 -> raw
            else -> -1
        }

        val configs = value.configs
            .filter { it.deviceId.isNotBlank() }
            .distinctBy { it.deviceId }
            .map { it.copy(sendChannel = channel(it.sendChannel), receiveChannel = channel(it.receiveChannel)) }

        val presets = value.presets
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
            .map { preset ->
                preset.copy(
                    configs = preset.configs
                        .filter { it.deviceId.isNotBlank() }
                        .distinctBy { it.deviceId }
                        .map { it.copy(sendChannel = channel(it.sendChannel), receiveChannel = channel(it.receiveChannel)) }
                )
            }

        return MidiSettings(
            schemaVersion = CURRENT_MIDI_SETTINGS_VERSION,
            configs = configs,
            presets = presets,
            activePreset = value.activePreset?.takeIf { name -> presets.any { it.name == name } }
        )
    }

    fun apply(newSettings: MidiSettings, persist: Boolean = true) {
        settings = newSettings
        snapshot = newSettings
        loaded = true
        if (persist) PersistenceManager.saveMidiSettings(newSettings)
    }

    fun update(transform: (MidiSettings) -> MidiSettings) {
        apply(transform(settings))
    }

    /** Thread-safe lookup used by platform MIDI implementations. */
    fun configFor(deviceId: String): MidiDeviceConfig =
        snapshot.configs.firstOrNull { it.deviceId == deviceId }
            ?: MidiDeviceConfig(deviceId)

    fun configsSnapshot(): List<MidiDeviceConfig> = snapshot.configs

    fun savePreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val preset = MidiHardwarePreset(trimmed, settings.configs.map { it.copy() })
        val withoutSame = settings.presets.filterNot { it.name.equals(trimmed, ignoreCase = true) }
        apply(settings.copy(presets = withoutSame + preset, activePreset = trimmed))
    }

    fun loadPreset(name: String) {
        val preset = settings.presets.firstOrNull { it.name == name } ?: return
        apply(settings.copy(configs = preset.configs.map { it.copy() }, activePreset = name))
    }

    fun deletePreset(name: String) {
        apply(
            settings.copy(
                presets = settings.presets.filterNot { it.name == name },
                activePreset = if (settings.activePreset == name) null else settings.activePreset
            )
        )
    }

    /** Upsert one device config, preserving the rest. */
    fun updateDevice(config: MidiDeviceConfig) {
        val next = settings.configs.toMutableList()
        val index = next.indexOfFirst { it.deviceId == config.deviceId }
        if (index >= 0) next[index] = config else next.add(config)
        apply(settings.copy(configs = next))
    }
}

/** MIDI channel helpers shared by both platform implementations. */
object MidiFilters {
    /** True when [status]/[data] belongs to [channel] (1..16) or has no channel. */
    fun matchesChannel(message: ByteArray, channel: Int): Boolean {
        if (message.isEmpty()) return false
        val status = message[0].toInt() and 0xFF
        // System messages (>= 0xF0) are channel-less: always pass.
        if (status >= 0xF0) return true
        val msgChannel = (status and 0x0F) + 1
        return msgChannel == channel
    }

    /** Rewrite the channel nibble of channel messages; leave system messages alone. */
    fun remapChannel(message: ByteArray, channel: Int): ByteArray {
        if (message.isEmpty() || channel !in 1..16) return message
        val status = message[0].toInt() and 0xFF
        if (status >= 0xF0) return message
        val out = message.copyOf()
        out[0] = (((status and 0xF0) or ((channel - 1) and 0x0F)) and 0xFF).toByte()
        return out
    }
}
