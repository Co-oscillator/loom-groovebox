package com.groovebox

enum class EngineType {
    SUBTRACTIVE, FM, SAMPLER, GRANULAR, WAVETABLE, FM_DRUM, ANALOG_DRUM, MIDI
}

enum class ArpMode { OFF, UP, DOWN, UP_DOWN, STAGGER_UP, STAGGER_DOWN, RANDOM }

data class ArpConfig(
    val mode: ArpMode = ArpMode.OFF,
    val octaves: Int = 0, // -3 to +3
    val isMutated: Boolean = false,
    val isLatched: Boolean = false,
    val inversion: Int = 0, // -2 to +2
    val rhythms: List<List<Boolean>> = listOf(
        List(8) { true },
        List(8) { false },
        List(8) { false }
    ), // 3 lanes x 8 steps
    val randomSequence: List<Int> = emptyList(), // The 8-step random seed
    val arpRate: Float = 1.0f,
    val arpDivisionMode: Int = 0 // 0=Reg, 1=Dotted, 2=Triplet
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class TrackState(
    val id: Int,
    val volume: Float = 0.8f,
    val pan: Float = 0.0f,
    val engineType: EngineType = EngineType.SUBTRACTIVE,
    val isActive: Boolean = true,
    val isMuted: Boolean = false,
    val steps: List<StepState> = List(64) { StepState() },
    val drumSteps: List<List<StepState>> = List(16) { List(64) { StepState() } }, // Support up to 16 drum instruments / slices
    val numPages: Int = 1,
    val stepsPerPage: Int = 16,
    val selectedFmDrumInstrument: Int = 0, // For FM Drum track selection
    val arpConfig: ArpConfig = ArpConfig(),
    val mutatedNotes: Map<Int, Int> = emptyMap(), // originalNote -> mutatedNote
    val fmCarrierMask: Int = 1, // Bitmask for FM operators: 1=Carrier, 0=Modulator
    val fmActiveMask: Int = 63, // Bitmask for active operators: 1=Active, 0=Off. Default all on.
    val useEnvelope: Boolean = true, // Playback toggle: ADSR vs Gate
    val fxSends: List<Float> = List(15) { 0.0f }, // Per-track sends to global FX
    val parameters: Map<Int, Float> = emptyMap(), // parameterId -> value
    val midiInChannel: Int = 17, // 1-16, 17=ALL, 0=NONE
    val midiOutChannel: Int = 1,  // 1-16
    val lastSamplePath: String = "",
    val activeWavetableName: String = "Basic",
    val filterMode: Int = 0, // 0=LP, 1=HP, 2=BP
    val clockMultiplier: Float = 1.0f
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class StepState(
    val active: Boolean = false,
    val notes: List<Int> = emptyList(),
    val velocity: Float = 0.8f,
    val ratchet: Int = 1, // x1 to x9 repeats
    val punch: Boolean = false, // 1.1x volume + overdrive
    val probability: Float = 1.0f,
    val gate: Float = 1.0f, // 0.0 to 1.0 (1/128 to 1 full step)
    val parameterLocks: Map<Int, Float> = emptyMap() // parameterId -> value (max 4)
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class LfoState(
    val rate: Float = 0.5f, // 0..1 (UI value)
    val depth: Float = 0.5f,
    val shape: Int = 0, 
    val sync: Boolean = false,
    val intensity: Float = 0.5f, // Routing Amount
    val targetType: Int = 0, // 0=None, 1=TrackParam, 2=GlobalFX, 3=Macro
    val targetId: Int = -1,
    val targetLabel: String = "None"
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class MacroTarget(
    val targetId: Int = -1,
    val targetLabel: String = "None",
    val isInverted: Boolean = false,
    val enabled: Boolean = false
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}


data class MacroState(
    val label: String = "Macro", 
    var sourceLabel: String = "None",
    var sourceType: Int = 0, // 0=None, 1=Strip, 2=Knob, 3=LFO
    var sourceIndex: Int = -1, 
    val targets: List<MacroTarget> = List(3) { MacroTarget() },
    val value: Float = 0.0f
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class RoutingConnection(
    val id: String = java.util.UUID.randomUUID().toString(),
    val source: Int, // ModSource Enum Int
    val destTrack: Int,
    val destParam: Int, // ModDestination Enum Int
    val amount: Float = 0.0f
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class StripRouting(
    val stripIndex: Int,
    val parameterName: String = "None",
    val targetType: Int = 0, // 0=None, 1=Track Param, 2=Global FX
    val targetId: Int = 0,
    val min: Float = 0f,
    val max: Float = 1f
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

data class GrooveboxState(
    val tracks: List<TrackState> = List(8) { i -> 
        val defaultEngine = when(i) {
            0 -> EngineType.SUBTRACTIVE
            1 -> EngineType.FM
            2 -> EngineType.WAVETABLE
            3 -> EngineType.SAMPLER
            4 -> EngineType.GRANULAR
            5 -> EngineType.FM_DRUM
            6 -> EngineType.ANALOG_DRUM
            else -> EngineType.SUBTRACTIVE
        }
        TrackState(id = i, engineType = defaultEngine)
    },
    val tempo: Float = 120.0f,
    val isPlaying: Boolean = false,
    val currentStep: Int = 0,
    val selectedTab: Int = 0,
    val masterVolume: Float = 0.8f,
    val globalParameters: Map<Int, Float> = emptyMap(), 
    val sidechainSourceTrack: Int = -1,
    val sidechainSourceDrumIdx: Int = 0,
    val isSelectingSidechain: Boolean = false,
    
    // Advanced Features
    val scaleType: ScaleType = ScaleType.MAJOR,
    val rootNote: Int = 48, // C3
    val padPageCount: Int = 1,
    val currentPadPage: Int = 0,
    val padColor: Long = 0xFFBB86FC,
    val stripRoutings: List<StripRouting> = List(4) { i -> StripRouting(stripIndex = i) },
    val stripValues: List<Float> = List(4) { 0.5f },
    val knobRoutings: List<StripRouting> = List(4) { i -> StripRouting(stripIndex = i + 4, parameterName = "Knob ${i+1}") }, 
    val knobValues: List<Float> = List(4) { 0.5f },
    val heldNotes: Set<Int> = emptySet(),
    val selectedTrackIndex: Int = 0,
    val midiLearnActive: Boolean = false,
    val midiLearnStep: Int = 0, // 0: OFF, 1: SELECT_STRIP, 2: SELECT_PARAM
    val midiLearnSelectedStrip: Int? = null,
    val focusedParameter: Int? = null, // parameterId
    val currentSequencerBank: Int = 0, // 0=A, 1=B, 2=C, 3=D
    val echoModeActive: Boolean = false, // Debugging: Echo received MIDI back to device

    // Transport & Sequencing Logic
    val swing: Float = 0f, // -0.23f to +0.23f
    val playbackDirection: Int = 1, // 1=Forward, -1=Reverse
    val isRandomOrder: Boolean = false,
    val isJumpMode: Boolean = false,
    val isJumpHold: Boolean = false,
    val jumpModeWaitingForTap: Boolean = false,
    val patternLength: Int = 16,

    // Parameter Locking & Recording State
    val isRecording: Boolean = false,
    val isParameterLocking: Boolean = false,
    val isResampling: Boolean = false, 
    val isRecordingSample: Boolean = false,
    val recordingTrackIndex: Int = -1,
    val lockedParamsThisStep: Set<Int> = emptySet(), 
    
    // Persistent Strip Assignments
    val engineTypeStripAssignments: Map<EngineType, List<StripRouting>> = emptyMap(),
    val engineTypeKnobAssignments: Map<EngineType, List<StripRouting>> = emptyMap(),
    
    // Routing Screen State
    val lfos: List<LfoState> = List(5) { LfoState() },
    val macros: List<MacroState> = List(6) { i -> MacroState(label="Macro ${i+1}") },
    val routingConnections: List<RoutingConnection> = emptyList(),
    val fxChain: Map<Int, Int> = emptyMap(), // Legacy map, keeping for safety but moving to slots
    val fxChainSlots: List<Int> = List(5) { -1 }, // 5 Serial Slots. -1 = Empty
    val focusedValue: String? = null,
    val lockingTarget: Pair<Int, Int>? = null,
    
    // LFO Learn
    val lfoLearnActive: Boolean = false,
    val lfoLearnLfoIndex: Int = -1,
    
    // Macro Learn
    val macroLearnActive: Boolean = false,
    val macroLearnMacroIndex: Int = -1,
    val macroLearnTargetIndex: Int = -1,
    
    // MIDI UI Feedback
    val lastMidiNote: Int = -1,
    val lastMidiVelocity: Int = 0
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
