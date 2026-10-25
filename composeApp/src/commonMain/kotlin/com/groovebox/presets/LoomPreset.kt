package com.groovebox.presets

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Portable patch format shared across Loom versions.
 *
 * Parameters are addressed by Loom's engine parameter ids (the same ids used by
 * the UI knobs and `NativeLib.setParameter`), which keeps the format independent
 * of any one app's state serialization.
 */
@Serializable
data class LoomPreset(
    val schema: Int = CURRENT_PRESET_SCHEMA,
    val name: String,
    val engine: String,
    val category: String = "Init",
    val author: String = "Loom",
    val description: String = "",
    val parameters: Map<Int, Float> = emptyMap(),
    val filterMode: Int? = null,
    val wavetableName: String? = null,
    /** Attribution / provenance, e.g. "Helm factory bank (CC-BY 4.0)". */
    val source: String? = null
)

const val CURRENT_PRESET_SCHEMA = 1

object PresetEngine {
    const val SUBTRACTIVE = "subtractive"
    const val WAVETABLE = "wavetable"
    const val FM_DRUM = "fm_drum"
    const val ANALOG_DRUM = "analog_drum"

    fun forEngineTypeId(engineType: Int): String? = when (engineType) {
        0 -> SUBTRACTIVE
        4 -> WAVETABLE
        5 -> FM_DRUM
        6 -> ANALOG_DRUM
        else -> null
    }

    fun displayName(engine: String): String = when (engine) {
        SUBTRACTIVE -> "Subtractive"
        WAVETABLE -> "Wavetable"
        FM_DRUM -> "FM Drum"
        ANALOG_DRUM -> "Analog Drum"
        else -> engine
    }
}

object PresetCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(preset: LoomPreset): String =
        json.encodeToString(LoomPreset.serializer(), preset)

    fun decode(text: String): LoomPreset? =
        runCatching { json.decodeFromString(LoomPreset.serializer(), text) }.getOrNull()

    fun save(file: File, preset: LoomPreset): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(encode(preset))
        true
    }.getOrDefault(false)

    fun load(file: File): LoomPreset? = runCatching {
        if (!file.exists()) null else decode(file.readText())
    }.getOrNull()

    /** Export a single track as a portable preset. */
    fun fromTrack(
        track: com.groovebox.TrackState,
        trackIndex: Int,
        engine: String,
        name: String,
        author: String = "User"
    ): LoomPreset {
        val ids = PresetRanges.idsFor(engine)
        val params = track.parameters
            .filterKeys { it in ids }
            .mapValues { (_, v) -> if (v.isFinite()) v else 0f }
        return LoomPreset(
            name = name,
            engine = engine,
            category = "User",
            author = author,
            parameters = params,
            filterMode = if (engine == PresetEngine.SUBTRACTIVE) track.filterMode else null,
            wavetableName = if (engine == PresetEngine.WAVETABLE) track.activeWavetableName else null
        )
    }
}

/** Parameter id ranges owned by each engine. Used to reset/export cleanly. */
object PresetRanges {
    val subtractive: List<IntRange> = listOf(1..1, 3..3, 100..199, 355..355)
    val wavetable: List<IntRange> = listOf(1..3, 355..355, 450..489)
    val fmDrum: List<IntRange> = listOf(200..299)
    val analogDrum: List<IntRange> = listOf(600..699)

    fun rangesFor(engine: String): List<IntRange> = when (engine) {
        PresetEngine.SUBTRACTIVE -> subtractive
        PresetEngine.WAVETABLE -> wavetable
        PresetEngine.FM_DRUM -> fmDrum
        PresetEngine.ANALOG_DRUM -> analogDrum
        else -> emptyList()
    }

    fun idsFor(engine: String): Set<Int> =
        rangesFor(engine).flatMap { it.toList() }.toSet()
}
