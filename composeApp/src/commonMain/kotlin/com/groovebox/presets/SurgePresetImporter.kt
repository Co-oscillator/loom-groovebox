package com.groovebox.presets

import kotlin.math.pow

/**
 * Best-effort importer for Surge XT patches (`.fxp`).
 *
 * Surge XT (GPLv3) stores a VST2 preset chunk followed by an XML `<patch>`.
 * Only scene A is read. The mapping targets Loom's Wavetable engine; classic
 * Surge oscillators are approximated by their shape/position.
 *
 * Values in the XML are Surge's native units:
 *  - envelope times are log2(seconds)
 *  - filter cutoff is `440 Hz * 2^(value / 12)`
 *  - osc params are normalised 0..1
 */
object SurgePresetImporter {

    fun looksLikeSurge(text: String): Boolean =
        text.contains("<patch") && text.contains("<parameters>")

    fun looksLikeSurge(bytes: ByteArray): Boolean {
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 4)), Charsets.ISO_8859_1)
        return head == "CcnK" || indexOf(bytes, "<?xml".toByteArray()) >= 0
    }

    fun import(bytes: ByteArray, targetEngine: String): LoomPreset? {
        if (targetEngine != PresetEngine.WAVETABLE) return null
        val start = indexOf(bytes, "<?xml".toByteArray())
        if (start < 0) return null
        val endTag = "</patch>".toByteArray()
        val end = indexOf(bytes, endTag, start)
        val sliceEnd = if (end >= 0) end + endTag.size else bytes.size
        val xml = String(bytes.copyOfRange(start, sliceEnd), Charsets.UTF_8)
        return importXml(xml, targetEngine)
    }

    fun import(text: String, targetEngine: String): LoomPreset? {
        if (targetEngine != PresetEngine.WAVETABLE) return null
        val start = text.indexOf("<?xml")
        if (start < 0) return null
        val end = text.indexOf("</patch>")
        val xml = if (end >= 0) text.substring(start, end + "</patch>".length) else text.substring(start)
        return importXml(xml, targetEngine)
    }

    private fun importXml(xml: String, targetEngine: String): LoomPreset? {
        if (!looksLikeSurge(xml)) return null

        val params = parseParameters(xml)
        if (params.isEmpty()) return null

        val meta = parseMeta(xml)
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: "Surge Import"
        val category = meta["category"]?.takeIf { it.isNotBlank() } ?: "Import"
        val author = meta["author"]?.takeIf { it.isNotBlank() } ?: "Surge"

        // Scene A only.
        fun f(key: String, default: Float = 0f) = params[key] ?: default
        fun i(key: String, default: Int = 0) = params[key]?.toInt() ?: default

        val oscType = i("a_osc1_type", 0)
        val p0 = f("a_osc1_param0", 0.5f)
        val p1 = f("a_osc1_param1", 0.5f)
        val p2 = f("a_osc1_param2", 0.5f)
        val p3 = f("a_osc1_param3", 0f)

        val morph = when (oscType) {
            0 -> p0.coerceIn(0f, 1f)                 // classic shape
            1 -> 0f                                   // sine
            2, 8 -> p0.coerceIn(0f, 1f)               // wavetable / modern
            3 -> 0.9f                                 // S&H noise
            7 -> windowShapeToMorph(p0)               // window type
            9 -> 0.5f                                 // string
            10 -> 0.7f                                // twist
            11 -> 0.6f                                // alias
            else -> 0.5f                              // FM types
        }

        val warp = when (oscType) {
            2, 8 -> p1.coerceIn(0f, 1f)
            10 -> p1.coerceIn(0f, 1f)
            3 -> 0.25f
            else -> 0f
        }
        val detune = when (oscType) {
            2, 7, 8 -> (p2 * 0.5f).coerceIn(0f, 0.5f)
            else -> 0.03f
        }
        val drive = (f("a_ws_drive", 0f).coerceIn(0f, 1f) * 0.6f +
            p3.coerceIn(0f, 1f) * 0.2f).coerceIn(0f, 1f)
        val crush = if (i("a_ws_type", 0) != 0) 0.2f else 0f
        val bits = if (oscType == 11) 0.35f else 0f

        val filterType = i("a_filter1_type", 0)
        val filterOn = filterType != 0
        val cutoffNorm = if (!filterOn) 0.95f else surgeCutoffToNorm(f("a_filter1_cutoff", 0f))
        val resonance = if (!filterOn) 0.05f else (f("a_filter1_resonance", 0f) * 0.6f).coerceIn(0f, 0.6f)
        val filterEnv = if (!filterOn) 0f else (f("a_filter1_envmod", 0f) / 96f).coerceIn(0f, 1f)
        val filterMode = surgeFilterMode(filterType)

        val paramsOut = mutableMapOf<Int, Float>(
            450 to morph,
            465 to warp,
            466 to crush,
            467 to drive,
            451 to detune,
            475 to bits,
            476 to 0f,
            458 to cutoffNorm,
            459 to resonance,
            464 to filterEnv,
            470 to filterMode.toFloat(),
            454 to attackNorm(surgeTime(f("a_env1_attack", -8f))),
            455 to decayNorm(surgeTime(f("a_env1_decay", 0f))),
            456 to f("a_env1_sustain", 1f).coerceIn(0f, 1f),
            457 to decayNorm(surgeTime(f("a_env1_release", -5f))),
            471 to attackNorm(surgeTime(f("a_env2_attack", -8f))),
            472 to decayNorm(surgeTime(f("a_env2_decay", -2f))),
            473 to f("a_env2_sustain", 0f).coerceIn(0f, 1f),
            474 to decayNorm(surgeTime(f("a_env2_release", -2f)))
        )
        // Surge has no glide parameter with the same semantics; keep it neutral.
        paramsOut[355] = 0f

        return LoomPreset(
            name = name,
            engine = targetEngine,
            category = "Surge",
            author = "Surge / " + category,
            description = "Imported from Surge XT (scene A). Category: $category. " +
                "Oscillator type $oscType; mapping is best-effort.",
            parameters = paramsOut,
            filterMode = filterMode,
            wavetableName = "Basic",
            source = "Surge XT patch \"$name\" by $author (GPLv3)"
        )
    }

    // -- parsing ---------------------------------------------------------

    private fun parseMeta(xml: String): Map<String, String> {
        val metaTag = Regex("""<meta\b([^>]*)/?>""").find(xml)?.groupValues?.getOrNull(1) ?: return emptyMap()
        return attributes(metaTag)
    }

    private fun parseParameters(xml: String): Map<String, Float> {
        val result = HashMap<String, Float>()
        val tagRegex = Regex("""<([A-Za-z0-9_]+)(\s[^>]*?)/?>""")
        for (match in tagRegex.findAll(xml)) {
            val attrs = attributes(match.groupValues[2])
            val value = attrs["value"]?.toFloatOrNull() ?: continue
            if (!attrs.containsKey("type")) continue
            result[match.groupValues[1]] = value
        }
        return result
    }

    private fun attributes(text: String): Map<String, String> {
        val regex = Regex("""([A-Za-z0-9_]+)="([^"]*)"""")
        return regex.findAll(text).associate {
            it.groupValues[1] to decodeEntities(it.groupValues[2])
        }
    }

    private fun decodeEntities(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    // -- conversions -----------------------------------------------------

    /** Surge envelope times are log2(seconds). */
    private fun surgeTime(value: Float): Float = 2f.pow(value.coerceIn(-10f, 6f))

    private fun attackNorm(seconds: Float): Float =
        ((seconds.coerceIn(0.0001f, 4f) / 2f).pow(1f / 3f)).coerceIn(0.001f, 1f)

    private fun decayNorm(seconds: Float): Float =
        ((seconds.coerceIn(0.0001f, 6f) / 3f).pow(1f / 3f)).coerceIn(0.001f, 1f)

    /** Surge cutoff: 440 Hz * 2^(value / 12); Loom: 20 + norm^2 * 14000. */
    private fun surgeCutoffToNorm(value: Float): Float {
        val hz = 440f * 2f.pow(value.coerceIn(-60f, 70f) / 12f)
        return ((hz - 20f) / 14000f).coerceIn(0f, 1f).pow(0.5f)
    }

    /** Filter type indices follow Surge's `FilterType` order. */
    private fun surgeFilterMode(type: Int): Int = when (type) {
        0 -> 0       // off -> LP with open cutoff
        1, 2, 3 -> 0 // LP12 / LP24 / LP variants
        4, 5 -> 1    // HP12 / HP24
        6, 7 -> 2    // BP12 / BP24
        8 -> 3       // notch
        else -> 0
    }

    /** Surge window types: triangle, cosine, blend1..3, saw, sine, square, rectangle. */
    private fun windowShapeToMorph(value: Float): Float = when (value.toInt().coerceIn(0, 8)) {
        0 -> 0.33f // triangle
        1 -> 0f    // cosine
        2, 3, 4 -> 0.5f
        5 -> 0.66f // saw
        6 -> 0f    // sine
        7, 8 -> 1f // square / rectangle
        else -> 0.5f
    }
}
