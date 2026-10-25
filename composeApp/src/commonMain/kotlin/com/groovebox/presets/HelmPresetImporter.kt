package com.groovebox.presets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow

/**
 * Best-effort importer for Helm patches (`.helm`, JSON).
 *
 * Helm is a GPLv3 wavetable/subtractive synth by Matt Tytel. Its factory bank is
 * licensed CC-BY 4.0. Loom maps the parts that have a direct equivalent
 * (oscillator levels/shapes, sub, noise, filter, envelopes) and approximates the
 * rest. The original patch metadata is preserved in [LoomPreset.source].
 */
object HelmPresetImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun looksLikeHelm(text: String): Boolean =
        text.contains("\"settings\"") && text.contains("\"patch_name\"")

    fun import(text: String, targetEngine: String): LoomPreset? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val settings = root["settings"] as? JsonObject ?: return null
        val name = root.string("patch_name") ?: "Helm Import"
        val folder = root.string("folder_name") ?: "Import"
        val author = root.string("author") ?: "Helm"
        val attribution = "Helm patch \"$name\" by $author (CC-BY 4.0)"
        return when (targetEngine) {
            PresetEngine.SUBTRACTIVE -> toSubtractive(settings, name, folder, attribution)
            PresetEngine.WAVETABLE -> toWavetable(settings, name, folder, attribution)
            else -> null
        }
    }

    // -- Subtractive -----------------------------------------------------

    private fun toSubtractive(
        s: JsonObject,
        name: String,
        category: String,
        attribution: String
    ): LoomPreset {
        val osc1Wave = s.int("osc_1_waveform", 0)
        val osc2Wave = s.int("osc_2_waveform", 0)
        var osc1Level = s.f("osc_1_volume", 0.6f)
        var osc2Level = s.f("osc_2_volume", 0f)
        val subLevel = s.f("sub_volume", 0f)
        val noiseLevel = s.f("noise_volume", 0f)
        if (osc1Level + osc2Level + subLevel + noiseLevel < 0.01f) {
            osc1Level = 0.8f
        }

        val params = mutableMapOf<Int, Float>()
        params[104] = helmWaveToShape(osc1Wave)
        params[105] = helmWaveToShape(osc2Wave)
        params[155] = helmWaveToShape(s.int("sub_waveform", 0))
        params[107] = osc1Level.coerceIn(0f, 1f)
        params[108] = osc2Level.coerceIn(0f, 1f)
        params[109] = subLevel.coerceIn(0f, 1f)
        params[110] = noiseLevel.coerceIn(0f, 1f)

        params[160] = pitchParam(s.f("osc_1_transpose", 0f) + s.f("osc_1_tune", 0f) / 100f, 4f)
        params[161] = pitchParam(s.f("osc_2_transpose", 0f) + s.f("osc_2_tune", 0f) / 100f, 4f)
        val subSemis = s.f("sub_octave", 0f) * 12f
        params[162] = pitchParam(subSemis, 2f)

        val detuneCents = maxOf(s.f("osc_1_unison_detune", 0f), s.f("osc_2_unison_detune", 0f))
        params[106] = (detuneCents / 200f).coerceIn(0f, 0.5f)

        val filterOn = s.f("filter_on", 1f) > 0.5f
        params[1] = if (filterOn) helmCutoffToNorm(s.f("cutoff", 96f)) else 1f
        params[113] = (s.f("resonance", 0f) * 0.6f).coerceIn(0f, 0.6f)
        params[118] = (s.f("fil_env_depth", 0f) / 96f).coerceIn(0f, 1f)

        params[100] = attackNorm(s.f("amp_attack", 0f))
        params[101] = decayNorm(s.f("amp_decay", 0.2f))
        params[102] = s.f("amp_sustain", 1f).coerceIn(0f, 1f)
        params[103] = decayNorm(s.f("amp_release", 0.1f))

        params[114] = attackNorm(s.f("fil_attack", 0f))
        params[115] = decayNorm(s.f("fil_decay", 0.2f))
        params[116] = s.f("fil_sustain", 1f).coerceIn(0f, 1f)
        params[117] = decayNorm(s.f("fil_release", 0.1f))

        params[355] = (s.f("portamento", -9f).let { if (it <= -8f) 0f else (it / 12f).coerceIn(0f, 1f) })

        return LoomPreset(
            name = name,
            engine = PresetEngine.SUBTRACTIVE,
            category = "Helm",
            author = "Helm / " + (category.ifBlank { "Import" }),
            description = "Imported from Helm. Original folder: ${category.ifBlank { "none" }}.",
            parameters = params,
            filterMode = 0,
            source = attribution
        )
    }

    // -- Wavetable -------------------------------------------------------

    private fun toWavetable(
        s: JsonObject,
        name: String,
        category: String,
        attribution: String
    ): LoomPreset {
        val osc1Level = s.f("osc_1_volume", 0.6f)
        val osc2Level = s.f("osc_2_volume", 0f)
        val useSecond = osc2Level > osc1Level
        val wave = if (useSecond) s.int("osc_2_waveform", 0) else s.int("osc_1_waveform", 0)

        val params = mutableMapOf<Int, Float>()
        params[450] = helmWaveToShape(wave)
        params[465] = (0.05f * (wave % 4)).coerceIn(0f, 0.3f)
        params[451] = (s.f("osc_1_unison_detune", 0f) / 200f + 0.02f).coerceIn(0f, 0.5f)

        val filterOn = s.f("filter_on", 1f) > 0.5f
        params[458] = if (filterOn) helmCutoffToNorm(s.f("cutoff", 96f)) else 0.95f
        params[459] = (s.f("resonance", 0f) * 0.55f).coerceIn(0f, 0.6f)
        params[464] = (s.f("fil_env_depth", 0f) / 96f).coerceIn(0f, 1f)

        params[454] = attackNorm(s.f("amp_attack", 0f))
        params[455] = decayNorm(s.f("amp_decay", 0.2f))
        params[456] = s.f("amp_sustain", 1f).coerceIn(0f, 1f)
        params[457] = decayNorm(s.f("amp_release", 0.1f))

        params[471] = attackNorm(s.f("fil_attack", 0f))
        params[472] = decayNorm(s.f("fil_decay", 0.2f))
        params[473] = s.f("fil_sustain", 1f).coerceIn(0f, 1f)
        params[474] = decayNorm(s.f("fil_release", 0.1f))

        return LoomPreset(
            name = name,
            engine = PresetEngine.WAVETABLE,
            category = "Helm",
            author = "Helm / " + (category.ifBlank { "Import" }),
            description = "Best-effort Helm mapping: oscillator 1/2 shape becomes the table position; filter and envelopes are converted.",
            parameters = params,
            filterMode = 0,
            wavetableName = "Basic",
            source = attribution
        )
    }

    // -- Helpers ---------------------------------------------------------

    private fun helmWaveToShape(waveform: Int): Float = when (waveform) {
        0 -> 0f          // sine
        1 -> 0.33f       // triangle
        2 -> 1f          // square
        3, 4 -> 0.66f    // down/up saw
        5 -> 0.75f       // 3 step
        6 -> 0.8f        // 4 step
        7 -> 0.85f       // 8 step
        8 -> 0.6f        // 3 pyramid
        9 -> 0.7f        // 5 pyramid
        10 -> 0.8f       // 9 pyramid
        else -> 0.66f
    }

    /** Helm cutoff is a MIDI note value; Loom cutoff is sqrt((Hz-20)/14000). */
    private fun helmCutoffToNorm(midiNote: Float): Float {
        val hz = 440f * 2f.pow((midiNote - 69f) / 12f)
        return ((hz - 20f) / 14000f).coerceIn(0f, 1f).pow(0.5f)
    }

    /** Loom pitch parameters are frequency multipliers (osc: v*4, sub: v*2). */
    private fun pitchParam(semitones: Float, divisor: Float): Float {
        val mult = 2f.pow(semitones / 12f)
        return (mult / divisor).coerceIn(0f, 1f)
    }

    /** Invert Loom's cubic ADSR mapping (attack max 2 s, decay/release max 3 s). */
    private fun attackNorm(seconds: Float): Float =
        ((seconds.coerceIn(0.0001f, 4f) / 2f).pow(1f / 3f)).coerceIn(0.001f, 1f)

    private fun decayNorm(seconds: Float): Float =
        ((seconds.coerceIn(0.0001f, 6f) / 3f).pow(1f / 3f)).coerceIn(0.001f, 1f)

    private fun JsonObject.f(key: String, default: Float): Float =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: default

    private fun JsonObject.int(key: String, default: Int): Int =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()?.toInt() ?: default

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}
