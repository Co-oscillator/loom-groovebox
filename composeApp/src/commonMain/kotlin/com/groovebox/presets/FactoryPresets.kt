package com.groovebox.presets

/**
 * Factory patch bank for the four synthesis engines.
 *
 * Presets are plain parameter maps, so they work on any Loom version that
 * understands the same engine parameter ids. Categories follow the usual
 * Bass / Lead / Pad / Keys / Pluck / FX / Drums grouping.
 */
object FactoryPresets {

    private fun preset(
        name: String,
        engine: String,
        category: String,
        description: String,
        parameters: Map<Int, Float>,
        filterMode: Int? = null,
        wavetableName: String? = null
    ) = LoomPreset(
        name = name,
        engine = engine,
        category = category,
        description = description,
        parameters = parameters,
        filterMode = filterMode,
        wavetableName = wavetableName,
        author = "Loom"
    )

    // ------------------------------------------------------------------
    // Subtractive
    // ------------------------------------------------------------------
    private val subBase: Map<Int, Float> = mapOf(
        1 to 0.8f,      // cutoff
        113 to 0.1f,    // resonance
        118 to 0f,      // filter env amount
        104 to 0.66f, 105 to 0.66f, 155 to 0f,          // osc shapes (saw/saw/sine)
        107 to 0.7f, 108 to 0f, 109 to 0f,              // osc levels
        160 to 0.25f, 161 to 0.25f, 162 to 0.25f,       // pitch (unison / sub -1 oct)
        106 to 0.02f, 110 to 0f, 355 to 0f,             // detune / noise / glide
        170 to 0f, 171 to 0f, 172 to 0f,                // drive
        180 to 0f, 181 to 0f, 182 to 0f,                // fold
        190 to 0f, 191 to 0f, 192 to 0f,                // pulse width
        100 to 0.01f, 101 to 0.3f, 102 to 0.8f, 103 to 0.3f,
        114 to 0.01f, 115 to 0.3f, 116 to 0.5f, 117 to 0.3f
    )

    private fun sub(
        name: String,
        category: String,
        description: String,
        overrides: Map<Int, Float>,
        filterMode: Int = 0
    ) = preset(
        name, PresetEngine.SUBTRACTIVE, category, description,
        subBase + overrides, filterMode
    )

    val subtractive: List<LoomPreset> = listOf(
        sub("Init Saw", "Init", "Bright saw with a gentle filter.", mapOf()),
        sub(
            "Pluck Bass", "Bass", "Short, woody pluck with a round sub.",
            mapOf(
                104 to 0.66f, 105 to 0.66f, 107 to 0.95f, 108 to 0.35f, 109 to 0.55f,
                106 to 0.08f, 1 to 0.28f, 113 to 0.35f, 118 to 0.7f,
                100 to 0.005f, 101 to 0.3f, 102 to 0.0f, 103 to 0.15f,
                114 to 0.0f, 115 to 0.35f, 116 to 0.0f, 117 to 0.2f
            )
        ),
        sub(
            "Sub Bass", "Bass", "Deep sine sub for club systems.",
            mapOf(
                104 to 0.0f, 107 to 0.5f, 109 to 0.95f, 162 to 0.25f,
                1 to 0.35f, 113 to 0.1f, 118 to 0.0f,
                100 to 0.01f, 101 to 0.5f, 102 to 0.9f, 103 to 0.15f
            )
        ),
        sub(
            "Reese Bass", "Bass", "Detuned saw pair with a moving filter.",
            mapOf(
                104 to 0.66f, 105 to 0.66f, 107 to 0.85f, 108 to 0.8f,
                106 to 0.25f, 109 to 0.45f, 1 to 0.3f, 113 to 0.25f, 118 to 0.35f,
                100 to 0.01f, 101 to 0.4f, 102 to 0.85f, 103 to 0.25f,
                114 to 0.05f, 115 to 0.5f, 116 to 0.45f, 117 to 0.3f
            )
        ),
        sub(
            "Acid Bass", "Bass", "Resonant 303-style squelch.",
            mapOf(
                104 to 0.66f, 107 to 0.95f, 108 to 0f, 109 to 0f,
                1 to 0.2f, 113 to 0.45f, 118 to 0.85f, 355 to 0.15f,
                100 to 0.005f, 101 to 0.25f, 102 to 0.2f, 103 to 0.1f,
                114 to 0.0f, 115 to 0.35f, 116 to 0.0f, 117 to 0.15f
            ),
            filterMode = 0
        ),
        sub(
            "Wobble Bass", "Bass", "Square/saw hybrid with a dark filter.",
            mapOf(
                104 to 1.0f, 105 to 0.66f, 107 to 0.8f, 108 to 0.6f,
                106 to 0.15f, 109 to 0.4f, 1 to 0.35f, 113 to 0.3f, 118 to 0.5f,
                100 to 0.01f, 101 to 0.4f, 102 to 0.9f, 103 to 0.2f
            )
        ),
        sub(
            "Horn", "Brass", "Soft brass with a slow filter bloom.",
            mapOf(
                104 to 0.66f, 105 to 0.66f, 107 to 0.75f, 108 to 0.5f,
                106 to 0.05f, 1 to 0.45f, 113 to 0.15f, 118 to 0.35f,
                100 to 0.18f, 101 to 0.45f, 102 to 0.85f, 103 to 0.25f,
                114 to 0.15f, 115 to 0.5f, 116 to 0.6f, 117 to 0.3f
            )
        ),
        sub(
            "Brass Stab", "Brass", "Tight brass hit for chord stabs.",
            mapOf(
                104 to 0.66f, 105 to 0.66f, 107 to 0.8f, 108 to 0.55f,
                106 to 0.06f, 1 to 0.4f, 113 to 0.2f, 118 to 0.55f,
                100 to 0.02f, 101 to 0.3f, 102 to 0.5f, 103 to 0.2f,
                114 to 0.0f, 115 to 0.3f, 116 to 0.25f, 117 to 0.2f
            )
        ),
        sub(
            "Vocal Pad", "Pad", "Breathy saw pad with a vocal tilt.",
            mapOf(
                104 to 0.66f, 105 to 1.0f, 107 to 0.6f, 108 to 0.55f,
                106 to 0.12f, 109 to 0.3f, 1 to 0.4f, 113 to 0.2f, 118 to 0.2f,
                100 to 0.7f, 101 to 0.8f, 102 to 0.9f, 103 to 0.9f,
                114 to 0.6f, 115 to 0.7f, 116 to 0.6f, 117 to 0.8f
            )
        ),
        sub(
            "Warm Pad", "Pad", "Sine/triangle bed with a slow swell.",
            mapOf(
                104 to 0.0f, 105 to 0.33f, 107 to 0.6f, 108 to 0.5f,
                106 to 0.06f, 109 to 0.45f, 1 to 0.5f, 113 to 0.1f,
                100 to 0.8f, 101 to 0.9f, 102 to 0.95f, 103 to 1.0f,
                114 to 0.7f, 115 to 0.8f, 116 to 0.7f, 117 to 0.9f
            )
        ),
        sub(
            "String Pad", "Pad", "Wide detuned strings.",
            mapOf(
                104 to 0.66f, 105 to 0.66f, 107 to 0.65f, 108 to 0.6f,
                106 to 0.1f, 1 to 0.55f, 113 to 0.12f, 118 to 0.15f,
                100 to 0.65f, 101 to 0.8f, 102 to 0.9f, 103 to 0.9f,
                114 to 0.5f, 115 to 0.7f, 116 to 0.7f, 117 to 0.8f
            )
        ),
        sub(
            "Glass Pad", "Pad", "High fifth layer with a glassy shimmer.",
            mapOf(
                104 to 0.0f, 105 to 0.33f, 107 to 0.6f, 108 to 0.45f,
                161 to 0.375f, 106 to 0.08f, 1 to 0.7f, 113 to 0.2f,
                100 to 0.6f, 101 to 0.8f, 102 to 0.85f, 103 to 0.8f,
                114 to 0.5f, 115 to 0.6f, 116 to 0.6f, 117 to 0.7f
            )
        ),
        sub(
            "Arp Lead", "Lead", "Punchy square lead for fast arps.",
            mapOf(
                104 to 1.0f, 105 to 0.66f, 107 to 0.9f, 108 to 0.4f,
                106 to 0.05f, 1 to 0.55f, 113 to 0.2f, 118 to 0.4f, 355 to 0.05f,
                100 to 0.005f, 101 to 0.2f, 102 to 0.5f, 103 to 0.2f,
                114 to 0.0f, 115 to 0.25f, 116 to 0.2f, 117 to 0.2f
            )
        ),
        sub(
            "Saw Lead", "Lead", "Classic detuned saw lead.",
            mapOf(
                104 to 0.66f, 105 to 0.66f, 107 to 0.8f, 108 to 0.7f,
                106 to 0.08f, 1 to 0.6f, 113 to 0.2f, 118 to 0.25f,
                100 to 0.02f, 101 to 0.4f, 102 to 0.8f, 103 to 0.25f
            )
        ),
        sub(
            "Square Lead", "Lead", "Hollow square lead with sub.",
            mapOf(
                104 to 1.0f, 107 to 0.85f, 109 to 0.4f,
                1 to 0.5f, 113 to 0.15f, 118 to 0.2f,
                100 to 0.01f, 101 to 0.3f, 102 to 0.7f, 103 to 0.2f
            )
        ),
        sub(
            "Chord Stab", "Keys", "Short bright stab for house chords.",
            mapOf(
                104 to 0.66f, 105 to 1.0f, 107 to 0.8f, 108 to 0.6f,
                106 to 0.05f, 1 to 0.45f, 113 to 0.2f, 118 to 0.55f,
                100 to 0.005f, 101 to 0.35f, 102 to 0.25f, 103 to 0.25f,
                114 to 0.0f, 115 to 0.25f, 116 to 0.2f, 117 to 0.2f
            )
        ),
        sub(
            "Organ", "Keys", "Drawbar-ish sines with a fifth.",
            mapOf(
                104 to 0.0f, 105 to 0.0f, 107 to 0.7f, 108 to 0.5f,
                161 to 0.375f, 109 to 0.3f, 1 to 0.8f, 113 to 0.05f,
                100 to 0.01f, 101 to 0.1f, 102 to 1.0f, 103 to 0.1f
            )
        ),
        sub(
            "Bell", "Keys", "FM-ish bell using a high partial.",
            mapOf(
                104 to 0.0f, 105 to 0.0f, 107 to 0.7f, 108 to 0.45f,
                161 to 0.5f, 109 to 0.2f, 1 to 0.75f, 113 to 0.25f, 118 to 0.2f,
                100 to 0.005f, 101 to 0.7f, 102 to 0.0f, 103 to 0.5f,
                114 to 0.0f, 115 to 0.5f, 116 to 0.0f, 117 to 0.5f
            )
        ),
        sub(
            "Flute", "Winds", "Soft sine with breath noise.",
            mapOf(
                104 to 0.0f, 107 to 0.8f, 110 to 0.06f, 109 to 0.2f,
                1 to 0.6f, 113 to 0.08f, 355 to 0.1f,
                100 to 0.25f, 101 to 0.3f, 102 to 0.9f, 103 to 0.3f
            )
        ),
        sub(
            "Noise Sweep", "FX", "Rising noise wash for transitions.",
            mapOf(
                104 to 0.0f, 107 to 0.0f, 109 to 0.0f, 110 to 0.85f,
                1 to 0.2f, 113 to 0.4f, 118 to 0.9f,
                100 to 0.3f, 101 to 1.0f, 102 to 0.0f, 103 to 0.5f,
                114 to 0.3f, 115 to 1.0f, 116 to 0.0f, 117 to 0.5f
            )
        )
    )

    // ------------------------------------------------------------------
    // Wavetable
    // ------------------------------------------------------------------
    private val wtBase: Map<Int, Float> = mapOf(
        450 to 0f, 465 to 0f, 466 to 0f, 467 to 0f,   // morph / warp / crush / drive
        451 to 0f, 355 to 0f, 475 to 0f, 476 to 0f,   // detune / glide / bits / srate
        454 to 0.01f, 455 to 0.3f, 456 to 0.8f, 457 to 0.3f,
        458 to 0.8f, 459 to 0.1f, 464 to 0f, 470 to 0f,
        471 to 0.01f, 472 to 0.3f, 473 to 0.5f, 474 to 0.3f
    )

    private fun wt(
        name: String,
        category: String,
        description: String,
        overrides: Map<Int, Float>,
        filterMode: Int = 0,
        wavetable: String? = null
    ) = preset(
        name, PresetEngine.WAVETABLE, category, description,
        wtBase + overrides, filterMode, wavetable
    )

    val wavetable: List<LoomPreset> = listOf(
        wt("Basic Motion", "Init", "The default table with a slow morph.", mapOf()),
        wt(
            "Glass Pluck", "Pluck", "Bright plucked table with a fast decay.",
            mapOf(
                450 to 0.25f, 465 to 0.2f, 458 to 0.7f, 459 to 0.15f, 464 to 0.35f,
                454 to 0.005f, 455 to 0.35f, 456 to 0f, 457 to 0.2f,
                471 to 0f, 472 to 0.3f, 473 to 0f, 474 to 0.2f
            ),
            wavetable = "BBELLS"
        ),
        wt(
            "Vocal Morph", "Pad", "Formant-like table with a slow swell.",
            mapOf(
                450 to 0.55f, 465 to 0.1f, 451 to 0.06f, 458 to 0.5f, 459 to 0.15f,
                454 to 0.6f, 455 to 0.7f, 456 to 0.9f, 457 to 0.8f
            ),
            wavetable = "VOICE_A"
        ),
        wt(
            "Acid Table", "Bass", "Resonant table for squelchy basslines.",
            mapOf(
                450 to 0.4f, 466 to 0.15f, 458 to 0.22f, 459 to 0.45f, 464 to 0.8f,
                454 to 0.005f, 455 to 0.25f, 456 to 0.2f, 457 to 0.1f,
                471 to 0f, 472 to 0.3f, 473 to 0f, 474 to 0.15f
            ),
            wavetable = "ACID_RIN"
        ),
        wt(
            "Warm Analog", "Bass", "Rounded table with light detune.",
            mapOf(
                450 to 0.3f, 451 to 0.05f, 458 to 0.4f, 459 to 0.1f,
                454 to 0.01f, 455 to 0.4f, 456 to 0.85f, 457 to 0.25f
            )
        ),
        wt(
            "Digital Bass", "Bass", "Hard-edged table with a short envelope.",
            mapOf(
                450 to 0.6f, 465 to 0.3f, 466 to 0.2f, 458 to 0.3f, 459 to 0.3f, 464 to 0.5f,
                454 to 0.005f, 455 to 0.3f, 456 to 0.4f, 457 to 0.15f
            ),
            wavetable = "LSDJ_WAV"
        ),
        wt(
            "Formant Pad", "Pad", "Slow formant sweep with a wide tail.",
            mapOf(
                450 to 0.45f, 465 to 0.25f, 451 to 0.08f, 458 to 0.55f, 459 to 0.2f,
                454 to 0.7f, 455 to 0.8f, 456 to 0.9f, 457 to 0.9f
            ),
            wavetable = "TALKING"
        ),
        wt(
            "Choir Pad", "Pad", "Breathy choir table with a soft attack.",
            mapOf(
                450 to 0.35f, 451 to 0.1f, 458 to 0.5f, 459 to 0.12f,
                454 to 0.75f, 455 to 0.8f, 456 to 0.92f, 457 to 1.0f
            ),
            wavetable = "VOXSYNTH"
        ),
        wt(
            "Bell Table", "Keys", "Bell-like table with a long release.",
            mapOf(
                450 to 0.5f, 465 to 0.15f, 458 to 0.7f, 459 to 0.2f,
                454 to 0.005f, 455 to 0.7f, 456 to 0f, 457 to 0.6f
            ),
            wavetable = "BBELLS"
        ),
        wt(
            "Metallic Lead", "Lead", "Bright metallic lead with drive.",
            mapOf(
                450 to 0.7f, 465 to 0.4f, 467 to 0.25f, 458 to 0.65f, 459 to 0.2f,
                454 to 0.01f, 455 to 0.35f, 456 to 0.7f, 457 to 0.25f
            ),
            wavetable = "PHANTOMS"
        ),
        wt(
            "Chip Arp", "Lead", "Lo-fi stepped table for chiptune arps.",
            mapOf(
                450 to 0.4f, 466 to 0.3f, 475 to 0.2f, 476 to 0.2f,
                458 to 0.75f, 459 to 0.1f,
                454 to 0.005f, 455 to 0.2f, 456 to 0.5f, 457 to 0.15f
            ),
            wavetable = "LSDJ_WAV"
        ),
        wt(
            "Bitcrush Bass", "Bass", "Crushed, aliased bass table.",
            mapOf(
                450 to 0.5f, 466 to 0.45f, 475 to 0.35f, 476 to 0.3f,
                458 to 0.35f, 459 to 0.2f,
                454 to 0.005f, 455 to 0.35f, 456 to 0.5f, 457 to 0.2f
            )
        ),
        wt(
            "Sweep Pad", "Pad", "Slow morphing sweep pad.",
            mapOf(
                450 to 0.2f, 465 to 0.3f, 451 to 0.07f, 458 to 0.5f, 459 to 0.18f,
                454 to 0.7f, 455 to 0.9f, 456 to 0.9f, 457 to 0.9f
            ),
            wavetable = "TEXTURE4"
        ),
        wt(
            "Hollow Keys", "Keys", "Hollow table with a short roomy tail.",
            mapOf(
                450 to 0.6f, 458 to 0.55f, 459 to 0.12f,
                454 to 0.01f, 455 to 0.4f, 456 to 0.55f, 457 to 0.3f
            ),
            wavetable = "ORGAN_DI"
        ),
        wt(
            "Wavetable Organ", "Keys", "Sustained organ-like table.",
            mapOf(
                450 to 0.15f, 465 to 0.05f, 458 to 0.8f, 459 to 0.05f,
                454 to 0.01f, 455 to 0.1f, 456 to 1.0f, 457 to 0.1f
            ),
            wavetable = "ORGAN_DI"
        ),
        wt(
            "Grit Lead", "Lead", "Table with heavy warp and drive.",
            mapOf(
                450 to 0.55f, 465 to 0.6f, 467 to 0.35f, 458 to 0.6f, 459 to 0.25f,
                454 to 0.01f, 455 to 0.4f, 456 to 0.75f, 457 to 0.3f
            ),
            wavetable = "WOWEE"
        ),
        wt(
            "Soft Keys", "Keys", "Mellow table with a gentle attack.",
            mapOf(
                450 to 0.3f, 465 to 0.05f, 458 to 0.45f, 459 to 0.08f,
                454 to 0.25f, 455 to 0.5f, 456 to 0.85f, 457 to 0.4f
            )
        ),
        wt(
            "Alien FX", "FX", "Unstable table with a long tail.",
            mapOf(
                450 to 0.8f, 465 to 0.5f, 466 to 0.3f, 451 to 0.15f,
                458 to 0.5f, 459 to 0.3f,
                454 to 0.4f, 455 to 0.9f, 456 to 0.7f, 457 to 1.0f
            ),
            wavetable = "ALIEN_VO"
        ),
        wt(
            "Resonant Pluck", "Pluck", "Resonant table pluck with a filter snap.",
            mapOf(
                450 to 0.45f, 465 to 0.2f, 458 to 0.35f, 459 to 0.4f, 464 to 0.6f,
                454 to 0.005f, 455 to 0.3f, 456 to 0.1f, 457 to 0.2f,
                471 to 0f, 472 to 0.25f, 473 to 0.1f, 474 to 0.2f
            ),
            wavetable = "ACID_RIN"
        ),
        wt(
            "Deep Drone", "FX", "Slow, dark drone bed.",
            mapOf(
                450 to 0.1f, 465 to 0.2f, 451 to 0.1f, 458 to 0.3f, 459 to 0.2f,
                454 to 0.8f, 455 to 0.9f, 456 to 0.95f, 457 to 1.0f
            ),
            wavetable = "DRONE"
        )
    )

    // ------------------------------------------------------------------
    // FM Drum kits
    // ------------------------------------------------------------------
    private data class FmDrum(
        val pitch: Float,
        val snap: Float,
        val decay: Float,
        val level: Float,
        val punch: Float = 0f
    )

    private val fmBase: Map<Int, Float> = buildMap {
        for (i in 0 until 8) {
            val b = 200 + i * 10
            put(b, 0.5f); put(b + 1, 0.1f); put(b + 2, 0.3f); put(b + 3, 0f)
            put(b + 4, 0f); put(b + 5, 0.8f); put(b + 6, 0f); put(b + 7, 0f)
            put(b + 8, 0f); put(b + 9, 0f)
        }
    }

    private fun fmKit(
        name: String,
        category: String,
        description: String,
        drums: List<FmDrum>
    ): LoomPreset {
        val params = fmBase.toMutableMap()
        drums.forEachIndexed { i, d ->
            val b = 200 + i * 10
            params[b] = d.pitch
            params[b + 1] = d.snap
            params[b + 2] = d.decay
            params[b + 4] = d.punch
            params[b + 5] = d.level
        }
        return preset(name, PresetEngine.FM_DRUM, category, description, params)
    }

    private fun List<FmDrum>.tighten() = map { it.copy(decay = (it.decay * 0.65f).coerceAtLeast(0.02f)) }
    private fun List<FmDrum>.loosen() = map { it.copy(decay = (it.decay * 1.45f).coerceAtMost(1f)) }
    private fun List<FmDrum>.harder() = map { it.copy(punch = (it.punch + 0.3f).coerceAtMost(1f)) }
    private fun List<FmDrum>.tunedUp() = map { it.copy(pitch = (it.pitch + 0.08f).coerceAtMost(1f)) }

    private val fmStudio = listOf(
        FmDrum(0.32f, 0.45f, 0.30f, 0.9f),  // kick
        FmDrum(0.45f, 0.55f, 0.25f, 0.75f), // snare
        FmDrum(0.40f, 0.30f, 0.30f, 0.7f),  // tom
        FmDrum(0.70f, 0.35f, 0.08f, 0.55f), // closed hat
        FmDrum(0.70f, 0.35f, 0.28f, 0.5f),  // open hat
        FmDrum(0.65f, 0.30f, 0.55f, 0.5f),  // cymbal
        FmDrum(0.60f, 0.40f, 0.18f, 0.6f),  // perc
        FmDrum(0.80f, 0.50f, 0.20f, 0.35f)  // noise
    )

    private val fm808 = listOf(
        FmDrum(0.18f, 0.20f, 0.75f, 1.0f),
        FmDrum(0.40f, 0.40f, 0.35f, 0.7f),
        FmDrum(0.30f, 0.20f, 0.55f, 0.65f),
        FmDrum(0.65f, 0.20f, 0.10f, 0.45f),
        FmDrum(0.65f, 0.25f, 0.40f, 0.45f),
        FmDrum(0.60f, 0.20f, 0.70f, 0.4f),
        FmDrum(0.55f, 0.30f, 0.25f, 0.55f),
        FmDrum(0.75f, 0.40f, 0.30f, 0.3f)
    )

    private val fm909 = listOf(
        FmDrum(0.28f, 0.55f, 0.22f, 1.0f, 0.3f),
        FmDrum(0.48f, 0.65f, 0.20f, 0.8f, 0.2f),
        FmDrum(0.42f, 0.40f, 0.22f, 0.7f),
        FmDrum(0.75f, 0.45f, 0.05f, 0.6f),
        FmDrum(0.75f, 0.45f, 0.22f, 0.55f),
        FmDrum(0.70f, 0.40f, 0.50f, 0.5f),
        FmDrum(0.62f, 0.50f, 0.15f, 0.65f),
        FmDrum(0.85f, 0.60f, 0.18f, 0.35f)
    )

    private val fmTrap = listOf(
        FmDrum(0.15f, 0.15f, 0.9f, 1.0f),
        FmDrum(0.55f, 0.70f, 0.18f, 0.7f),
        FmDrum(0.35f, 0.35f, 0.45f, 0.6f),
        FmDrum(0.78f, 0.55f, 0.04f, 0.5f),
        FmDrum(0.78f, 0.55f, 0.20f, 0.45f),
        FmDrum(0.72f, 0.45f, 0.45f, 0.4f),
        FmDrum(0.65f, 0.55f, 0.12f, 0.55f),
        FmDrum(0.90f, 0.70f, 0.15f, 0.3f)
    )

    private val fmHouse = listOf(
        FmDrum(0.25f, 0.35f, 0.35f, 0.95f, 0.2f),
        FmDrum(0.45f, 0.60f, 0.22f, 0.75f),
        FmDrum(0.38f, 0.35f, 0.28f, 0.65f),
        FmDrum(0.72f, 0.40f, 0.07f, 0.55f),
        FmDrum(0.72f, 0.40f, 0.25f, 0.5f),
        FmDrum(0.68f, 0.35f, 0.5f, 0.45f),
        FmDrum(0.58f, 0.45f, 0.16f, 0.6f),
        FmDrum(0.82f, 0.55f, 0.2f, 0.3f)
    )

    private val fmTechno = listOf(
        FmDrum(0.22f, 0.60f, 0.28f, 1.0f, 0.45f),
        FmDrum(0.50f, 0.75f, 0.18f, 0.8f, 0.3f),
        FmDrum(0.36f, 0.45f, 0.25f, 0.7f),
        FmDrum(0.80f, 0.60f, 0.04f, 0.6f),
        FmDrum(0.80f, 0.60f, 0.18f, 0.55f),
        FmDrum(0.74f, 0.50f, 0.45f, 0.5f),
        FmDrum(0.66f, 0.55f, 0.12f, 0.65f),
        FmDrum(0.92f, 0.75f, 0.14f, 0.35f)
    )

    private val fmElectro = listOf(
        FmDrum(0.30f, 0.50f, 0.25f, 0.95f),
        FmDrum(0.52f, 0.70f, 0.30f, 0.8f),
        FmDrum(0.45f, 0.40f, 0.35f, 0.7f),
        FmDrum(0.68f, 0.35f, 0.12f, 0.5f),
        FmDrum(0.68f, 0.40f, 0.32f, 0.45f),
        FmDrum(0.62f, 0.30f, 0.60f, 0.4f),
        FmDrum(0.60f, 0.50f, 0.22f, 0.6f),
        FmDrum(0.86f, 0.65f, 0.25f, 0.3f)
    )

    private val fmFunk = listOf(
        FmDrum(0.30f, 0.40f, 0.22f, 0.9f),
        FmDrum(0.50f, 0.80f, 0.15f, 0.8f),
        FmDrum(0.42f, 0.55f, 0.20f, 0.7f),
        FmDrum(0.66f, 0.45f, 0.06f, 0.55f),
        FmDrum(0.66f, 0.45f, 0.20f, 0.5f),
        FmDrum(0.60f, 0.35f, 0.40f, 0.45f),
        FmDrum(0.58f, 0.65f, 0.12f, 0.65f),
        FmDrum(0.84f, 0.60f, 0.18f, 0.3f)
    )

    private val fmLofi = listOf(
        FmDrum(0.28f, 0.30f, 0.35f, 0.8f),
        FmDrum(0.46f, 0.45f, 0.28f, 0.65f),
        FmDrum(0.40f, 0.30f, 0.30f, 0.6f),
        FmDrum(0.62f, 0.25f, 0.10f, 0.45f),
        FmDrum(0.62f, 0.25f, 0.30f, 0.4f),
        FmDrum(0.55f, 0.20f, 0.55f, 0.35f),
        FmDrum(0.56f, 0.35f, 0.20f, 0.5f),
        FmDrum(0.70f, 0.35f, 0.30f, 0.25f)
    )

    val fmDrum: List<LoomPreset> = listOf(
        fmKit("Studio Kit", "Drums", "Balanced acoustic-style kit.", fmStudio),
        fmKit("Studio Tight", "Drums", "Tighter, punchier studio kit.", fmStudio.tighten().harder()),
        fmKit("Studio Room", "Drums", "Longer decay studio kit.", fmStudio.loosen()),
        fmKit("808 Kit", "Drums", "Classic long-tail 808 kit.", fm808),
        fmKit("808 Long", "Drums", "Extra-long 808 tails.", fm808.loosen()),
        fmKit("808 Short", "Drums", "Tight 808 for fast patterns.", fm808.tighten()),
        fmKit("909 Kit", "Drums", "Bright 909-style kit.", fm909),
        fmKit("909 Hard", "Drums", "Harder 909 with more punch.", fm909.harder()),
        fmKit("Trap Kit", "Drums", "Long sub kick and sharp hats.", fmTrap),
        fmKit("Trap Hi", "Drums", "Brighter trap kit.", fmTrap.tunedUp()),
        fmKit("House Kit", "Drums", "Four-on-the-floor house kit.", fmHouse),
        fmKit("House Tight", "Drums", "Tight house drums.", fmHouse.tighten()),
        fmKit("Techno Kit", "Drums", "Driving, punchy techno kit.", fmTechno),
        fmKit("Techno Hard", "Drums", "Distorted, hard techno kit.", fmTechno.harder()),
        fmKit("Electro Kit", "Drums", "Classic electro kit.", fmElectro),
        fmKit("Funk Kit", "Drums", "Ghost-note friendly funk kit.", fmFunk),
        fmKit("Lo-Fi Kit", "Drums", "Soft, dusty lo-fi kit.", fmLofi),
        fmKit("Lo-Fi Long", "Drums", "Dusty kit with long tails.", fmLofi.loosen()),
        fmKit("Minimal Kit", "Drums", "Sparse minimal kit.", fmStudio.tighten().map { it.copy(level = it.level * 0.85f) }),
        fmKit("Glitch Kit", "Drums", "Extreme pitches and short decays.", fmTechno.tighten().tunedUp().harder())
    )

    // ------------------------------------------------------------------
    // Analog Drum kits
    // ------------------------------------------------------------------
    private data class AdDrum(
        val decay: Float,
        val tone: Float,
        val tune: Float,
        val snap: Float,
        val gain: Float
    )

    private val adBase: Map<Int, Float> = buildMap {
        for (i in 0 until 8) {
            val b = 600 + i * 10
            put(b, 0.4f); put(b + 1, 0.5f); put(b + 2, 0.5f)
            put(b + 3, 0.3f); put(b + 4, 0.3f); put(b + 5, 0.8f)
            put(b + 6, 0f); put(b + 7, 0f); put(b + 8, 0f); put(b + 9, 0f)
        }
    }

    private fun adKit(
        name: String,
        category: String,
        description: String,
        drums: List<AdDrum>
    ): LoomPreset {
        val params = adBase.toMutableMap()
        drums.forEachIndexed { i, d ->
            val b = 600 + i * 10
            params[b] = d.decay
            params[b + 1] = d.tone
            params[b + 2] = d.tune
            params[b + 3] = d.snap
            params[b + 5] = d.gain
        }
        return preset(name, PresetEngine.ANALOG_DRUM, category, description, params)
    }

    private fun List<AdDrum>.adTighten() = map { it.copy(decay = (it.decay * 0.65f).coerceAtLeast(0.02f)) }
    private fun List<AdDrum>.adLoosen() = map { it.copy(decay = (it.decay * 1.45f).coerceAtMost(1f)) }
    private fun List<AdDrum>.adTunedUp() = map { it.copy(tune = (it.tune + 0.08f).coerceAtMost(1f)) }
    private fun List<AdDrum>.adHarder() = map { it.copy(snap = (it.snap + 0.25f).coerceAtMost(1f), tone = (it.tone + 0.1f).coerceAtMost(1f)) }

    private val ad808 = listOf(
        AdDrum(0.75f, 0.35f, 0.20f, 0.15f, 1.0f),  // kick
        AdDrum(0.30f, 0.60f, 0.45f, 0.65f, 0.75f), // snare
        AdDrum(0.25f, 0.55f, 0.50f, 0.50f, 0.5f),  // clap
        AdDrum(0.10f, 0.70f, 0.65f, 0.20f, 0.45f), // closed hat
        AdDrum(0.35f, 0.65f, 0.65f, 0.25f, 0.45f), // open hat
        AdDrum(0.60f, 0.55f, 0.55f, 0.20f, 0.4f),  // cymbal
        AdDrum(0.25f, 0.60f, 0.55f, 0.40f, 0.55f), // perc
        AdDrum(0.30f, 0.80f, 0.70f, 0.60f, 0.3f)   // noise
    )

    private val ad909 = listOf(
        AdDrum(0.30f, 0.45f, 0.25f, 0.55f, 1.0f),
        AdDrum(0.22f, 0.70f, 0.48f, 0.75f, 0.8f),
        AdDrum(0.20f, 0.65f, 0.52f, 0.60f, 0.55f),
        AdDrum(0.06f, 0.80f, 0.68f, 0.30f, 0.5f),
        AdDrum(0.25f, 0.75f, 0.68f, 0.35f, 0.5f),
        AdDrum(0.50f, 0.65f, 0.60f, 0.30f, 0.45f),
        AdDrum(0.18f, 0.70f, 0.60f, 0.50f, 0.6f),
        AdDrum(0.20f, 0.85f, 0.75f, 0.70f, 0.35f)
    )

    private val adHouse = listOf(
        AdDrum(0.45f, 0.40f, 0.22f, 0.35f, 0.95f),
        AdDrum(0.25f, 0.65f, 0.46f, 0.70f, 0.75f),
        AdDrum(0.22f, 0.60f, 0.50f, 0.55f, 0.5f),
        AdDrum(0.08f, 0.75f, 0.66f, 0.25f, 0.5f),
        AdDrum(0.28f, 0.70f, 0.66f, 0.30f, 0.5f),
        AdDrum(0.55f, 0.60f, 0.58f, 0.25f, 0.45f),
        AdDrum(0.20f, 0.65f, 0.56f, 0.45f, 0.55f),
        AdDrum(0.25f, 0.80f, 0.72f, 0.60f, 0.3f)
    )

    private val adTechno = listOf(
        AdDrum(0.35f, 0.55f, 0.18f, 0.55f, 1.0f),
        AdDrum(0.20f, 0.75f, 0.44f, 0.80f, 0.8f),
        AdDrum(0.18f, 0.70f, 0.48f, 0.65f, 0.55f),
        AdDrum(0.05f, 0.85f, 0.70f, 0.35f, 0.55f),
        AdDrum(0.22f, 0.80f, 0.70f, 0.40f, 0.55f),
        AdDrum(0.45f, 0.70f, 0.62f, 0.30f, 0.45f),
        AdDrum(0.15f, 0.75f, 0.62f, 0.55f, 0.6f),
        AdDrum(0.18f, 0.90f, 0.78f, 0.75f, 0.35f)
    )

    private val adTrap = listOf(
        AdDrum(0.95f, 0.25f, 0.15f, 0.10f, 1.0f),
        AdDrum(0.22f, 0.80f, 0.42f, 0.75f, 0.7f),
        AdDrum(0.20f, 0.75f, 0.46f, 0.60f, 0.5f),
        AdDrum(0.04f, 0.90f, 0.72f, 0.40f, 0.5f),
        AdDrum(0.20f, 0.85f, 0.72f, 0.45f, 0.45f),
        AdDrum(0.40f, 0.75f, 0.64f, 0.35f, 0.4f),
        AdDrum(0.12f, 0.80f, 0.64f, 0.60f, 0.55f),
        AdDrum(0.15f, 0.95f, 0.80f, 0.80f, 0.3f)
    )

    private val adElectro = listOf(
        AdDrum(0.40f, 0.45f, 0.20f, 0.45f, 0.95f),
        AdDrum(0.28f, 0.70f, 0.45f, 0.75f, 0.8f),
        AdDrum(0.24f, 0.65f, 0.50f, 0.55f, 0.55f),
        AdDrum(0.10f, 0.70f, 0.64f, 0.25f, 0.5f),
        AdDrum(0.32f, 0.65f, 0.64f, 0.30f, 0.45f),
        AdDrum(0.55f, 0.55f, 0.56f, 0.25f, 0.45f),
        AdDrum(0.22f, 0.65f, 0.55f, 0.45f, 0.55f),
        AdDrum(0.28f, 0.75f, 0.70f, 0.60f, 0.3f)
    )

    private val adFunk = listOf(
        AdDrum(0.30f, 0.50f, 0.22f, 0.40f, 0.95f),
        AdDrum(0.18f, 0.75f, 0.44f, 0.85f, 0.8f),
        AdDrum(0.16f, 0.70f, 0.48f, 0.65f, 0.55f),
        AdDrum(0.05f, 0.80f, 0.66f, 0.30f, 0.55f),
        AdDrum(0.18f, 0.80f, 0.66f, 0.35f, 0.5f),
        AdDrum(0.35f, 0.65f, 0.58f, 0.25f, 0.4f),
        AdDrum(0.14f, 0.75f, 0.58f, 0.55f, 0.6f),
        AdDrum(0.20f, 0.85f, 0.72f, 0.65f, 0.3f)
    )

    private val adLofi = listOf(
        AdDrum(0.50f, 0.30f, 0.25f, 0.20f, 0.8f),
        AdDrum(0.30f, 0.50f, 0.48f, 0.50f, 0.65f),
        AdDrum(0.28f, 0.45f, 0.52f, 0.40f, 0.5f),
        AdDrum(0.12f, 0.55f, 0.62f, 0.15f, 0.4f),
        AdDrum(0.35f, 0.50f, 0.62f, 0.20f, 0.4f),
        AdDrum(0.55f, 0.45f, 0.55f, 0.15f, 0.35f),
        AdDrum(0.25f, 0.55f, 0.52f, 0.35f, 0.45f),
        AdDrum(0.35f, 0.65f, 0.68f, 0.45f, 0.25f)
    )

    val analogDrum: List<LoomPreset> = listOf(
        adKit("808 Analog", "Drums", "Round analog 808-style kit.", ad808),
        adKit("808 Long", "Drums", "Long-tail 808 analog kit.", ad808.adLoosen()),
        adKit("808 Short", "Drums", "Tight 808 analog kit.", ad808.adTighten()),
        adKit("909 Analog", "Drums", "Bright analog 909-style kit.", ad909),
        adKit("909 Tight", "Drums", "Tight, punchy 909 kit.", ad909.adTighten().adHarder()),
        adKit("909 Long", "Drums", "Long 909-style tails.", ad909.adLoosen()),
        adKit("House Kit", "Drums", "Classic house analog kit.", adHouse),
        adKit("House Tight", "Drums", "Tight house kit.", adHouse.adTighten()),
        adKit("Techno Kit", "Drums", "Hard-driving techno kit.", adTechno),
        adKit("Techno Hard", "Drums", "Saturated hard techno kit.", adTechno.adHarder()),
        adKit("Trap Kit", "Drums", "Sub-heavy trap kit.", adTrap),
        adKit("Trap Hi", "Drums", "Brighter trap kit.", adTrap.adTunedUp()),
        adKit("Electro Kit", "Drums", "Old-school electro kit.", adElectro),
        adKit("Funk Kit", "Drums", "Ghost-note funk kit.", adFunk),
        adKit("Lo-Fi Kit", "Drums", "Dusty, soft analog kit.", adLofi),
        adKit("Lo-Fi Long", "Drums", "Dusty kit with long tails.", adLofi.adLoosen()),
        adKit("Dub Kit", "Drums", "Deep, spacious dub kit.", adTechno.adLoosen().map { it.copy(tone = it.tone * 0.8f) }),
        adKit("Minimal Kit", "Drums", "Sparse minimal analog kit.", adHouse.adTighten().map { it.copy(gain = it.gain * 0.85f) }),
        adKit("Industrial Kit", "Drums", "Harsh industrial kit.", adTechno.adHarder().adTunedUp()),
        adKit("Vinyl Kit", "Drums", "Warm, noisy vinyl kit.", adLofi.map { it.copy(gain = it.gain * 0.9f, tone = (it.tone + 0.08f).coerceAtMost(1f)) })
    )

    // ------------------------------------------------------------------
    val all: List<LoomPreset> = subtractive + wavetable + fmDrum + analogDrum

    fun forEngine(engine: String): List<LoomPreset> = when (engine) {
        PresetEngine.SUBTRACTIVE -> subtractive
        PresetEngine.WAVETABLE -> wavetable
        PresetEngine.FM_DRUM -> fmDrum
        PresetEngine.ANALOG_DRUM -> analogDrum
        else -> emptyList()
    }

    fun categories(engine: String): List<String> =
        forEngine(engine).map { it.category }.distinct()
}
