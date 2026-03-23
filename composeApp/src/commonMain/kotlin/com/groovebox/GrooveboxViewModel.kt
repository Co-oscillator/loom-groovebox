package com.groovebox

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import com.groovebox.ui.views.isBlackKey
import com.groovebox.utils.*
import com.groovebox.midi.MidiRouter
import com.groovebox.midi.MidiCommand

class GrooveboxViewModel(
    private val nativeLib: NativeLib,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {
    val midiRouter = MidiRouter(nativeLib) { command ->
        handleMidiCommand(command)
    }

    var state by mutableStateOf(createInitialState())
        private set

    fun onStateChange(update: GrooveboxState) {
        state = update
    }

    fun updateState(transform: (GrooveboxState) -> GrooveboxState) {
        state = transform(state)
    }

    fun updateCurrentStep(newStep: Int) {
        if (state.currentStep != newStep) {
            state = state.copy(currentStep = newStep)
        }
    }

    fun setPlaybackState(isPlaying: Boolean, isRecording: Boolean = state.isRecording) {
        state = state.copy(isPlaying = isPlaying, isRecording = isRecording)
    }

    fun setSelectedTab(index: Int) {
        if (state.selectedTab != index) {
            state = state.copy(selectedTab = index)
        }
    }

    fun setSelectedTrack(index: Int) {
        if (state.selectedTrackIndex != index) {
            val track = state.tracks[index]
            val isDrum = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM
            state = if (isDrum) {
                state.copy(selectedTrackIndex = index, gridMode = GridMode.GRID_4X4)
            } else {
                state.copy(selectedTrackIndex = index)
            }
        }
    }

    fun setSelectedFmDrumInstrument(trackIdx: Int, instIdx: Int) {
        state = state.copy(
            tracks = state.tracks.mapIndexed { idx, t ->
                if (idx == trackIdx) t.copy(selectedFmDrumInstrument = instIdx) else t
            }
        )
    }

    private fun createInitialState(): GrooveboxState {
        val tracks = List(8) { i ->
            val engineType = when(i) {
                0 -> EngineType.SUBTRACTIVE
                1 -> EngineType.FM
                2 -> EngineType.SAMPLER
                3 -> EngineType.GRANULAR
                4 -> EngineType.WAVETABLE
                5 -> EngineType.FM_DRUM
                6 -> EngineType.ANALOG_DRUM
                7 -> EngineType.MIDI
                else -> EngineType.SUBTRACTIVE
            }
            
            val params = mutableMapOf<Int, Float>()
            var padModTarget = 1 // Default Cutoff
            
            when(engineType) {
                EngineType.SUBTRACTIVE -> {
                    params[118] = 0.5f // Env Intensity
                    padModTarget = 1
                }
                EngineType.FM -> {
                    params[160] = 0.8f // Op 1 Lvl
                    params[166] = 0.4f // Op 2 Lvl
                    params[165] = 1.0f // Op 1 Ratio
                    params[171] = 2.0f // Op 2 Ratio
                    params[161] = 0.01f // Op 1 Atk
                    params[162] = 0.5f // Op 1 Dcy
                    params[9] = 0.5f   // Center Pan
                    padModTarget = 159 // Drive
                }
                EngineType.WAVETABLE -> padModTarget = 450 // Morph
                EngineType.SAMPLER -> padModTarget = 302 // Speed
                EngineType.GRANULAR -> padModTarget = 406 // Grain Size
                EngineType.AUDIO_IN -> padModTarget = 122 // Fold
                EngineType.SOUNDFONT -> padModTarget = 1 // Filter
                else -> {}
            }
            
            TrackState(id = i, engineType = engineType, parameters = params, padModTargetId = getDefaultPadModTarget(engineType))
        }

        val stripAssignments = EngineType.values().associateWith { getDefaultStripAssignments(it) }
        val knobAssignments = EngineType.values().associateWith { getDefaultKnobAssignments(it) }

        return GrooveboxState(
            tracks = tracks, 
            tempo = 80.0f,
            engineTypeStripAssignments = stripAssignments,
            engineTypeKnobAssignments = knobAssignments
        )
    }

    fun sanitizeAndSetState(loadedState: GrooveboxState) {
        state = sanitizeGrooveboxState(loadedState)
        syncWithNative() // Ensure native engine is in sync after loading
    }

    fun syncWithNative() {
        syncNativeState(state, nativeLib)
    }

    fun toggleStep(trackIdx: Int, stepIdx: Int) {
        if (trackIdx !in state.tracks.indices) return
        val track = state.tracks[trackIdx]
        if (stepIdx !in 0..63) return

        val isSamplerChops = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) > 0.6f
        val isMultiTrack = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || isSamplerChops
        
        val currentStep = if (isMultiTrack) track.drumSteps[track.selectedFmDrumInstrument][stepIdx] else track.steps[stepIdx]
        val newActive = !currentStep.active
        
        if (isMultiTrack) {
            val instIdx = track.selectedFmDrumInstrument
            val drumNote = 60 + instIdx
            val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(drumNote) else currentStep.notes
            val newDrumSteps = track.drumSteps.mapIndexed { di, dsteps ->
                if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive, notes = finalNotes) else s }
                else dsteps
            }
            state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(drumSteps = newDrumSteps) else t })
            nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped)
        } else {
            val rootNote = 60 // Default note if empty
            val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(rootNote) else currentStep.notes
            val newSteps = track.steps.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive, notes = finalNotes) else s }
            state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(steps = newSteps) else t })
            nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped)
        }
    }


    fun pullRecordedSteps(trackIdx: Int) {
        if (trackIdx !in state.tracks.indices) return
        val track = state.tracks[trackIdx]
        
        val isSamplerChops = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) > 0.6f
        val isMultiTrack = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || isSamplerChops
        
        val patternLen = track.patternLength
        var changed = false

        if (isMultiTrack) {
            val drumIdx = track.selectedFmDrumInstrument
            val updatedSteps = track.drumSteps[drumIdx].mapIndexed { stepIdx, s ->
                if (stepIdx < patternLen) {
                    val isActive = nativeLib.getStepActive(trackIdx, stepIdx, drumIdx)
                    if (isActive && !s.active) {
                        changed = true
                        val notes = nativeLib.getStepNotes(trackIdx, stepIdx, drumIdx)
                        val vel = nativeLib.getStepVelocity(trackIdx, stepIdx, drumIdx)
                        val sub = nativeLib.getStepSubStep(trackIdx, stepIdx, drumIdx)
                        s.copy(active = true, notes = notes.toList(), velocity = vel, subStepOffset = sub)
                    } else if (!isActive && s.active) {
                        changed = true
                        s.copy(active = false, notes = emptyList())
                    } else s
                } else s
            }
            if (changed) {
                val newDrumSteps = track.drumSteps.mapIndexed { di, dsteps ->
                    if (di == drumIdx) updatedSteps else dsteps
                }
                state = state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                    if (idx == trackIdx) t.copy(drumSteps = newDrumSteps) else t
                })
            }
        } else {
            val steps = track.steps
            val updatedSteps = steps.mapIndexed { stepIdx, s ->
                if (stepIdx < patternLen) {
                    val isActive = nativeLib.getStepActive(trackIdx, stepIdx)
                    if (s.active != isActive) {
                        changed = true
                        if (isActive) {
                            val notes = nativeLib.getStepNotes(trackIdx, stepIdx)
                            val vel = nativeLib.getStepVelocity(trackIdx, stepIdx)
                            val sub = nativeLib.getStepSubStep(trackIdx, stepIdx)
                            s.copy(active = true, notes = notes.toList(), velocity = vel, subStepOffset = sub)
                        } else {
                            s.copy(active = false, notes = emptyList())
                        }
                    } else s
                } else s
            }
            if (changed) {
                state = state.copy(tracks = state.tracks.mapIndexed { idx, tr -> 
                    if (idx == trackIdx) tr.copy(steps = updatedSteps) else tr 
                })
            }
        }
    }

    fun pullTransportState() {
        val playing = nativeLib.getIsPlaying()
        val recording = nativeLib.getIsRecording()
        val recordingSample = nativeLib.getIsRecordingSample()
        
        if (state.isPlaying != playing || state.isRecording != recording || state.isRecordingSample != recordingSample) {
            state = state.copy(
                isPlaying = playing,
                isRecording = recording,
                isRecordingSample = recordingSample
            )
        }
    }

    fun pollEngineState() {
        pullTransportState()
        val cpu = nativeLib.getCpuLoad()
        val focusedTrackIdx = state.selectedTrackIndex
        val track = state.tracks[focusedTrackIdx]
        
        // Pull modulated parameter values for visual consistency
        val engineParams = nativeLib.getAllTrackParameters(focusedTrackIdx)
        val updatedParams = track.parameters.toMutableMap()
        
        // Only update if they differ significantly to avoid UI churn
        engineParams.forEachIndexed { pid, v ->
            if (pid in track.parameters) {
                if (Math.abs((track.parameters[pid] ?: 0f) - v) > 0.001f) {
                    updatedParams[pid] = v
                }
            }
        }
        
        state = state.copy(
            cpuLoad = cpu,
            tracks = state.tracks.mapIndexed { idx, t ->
                if (idx == focusedTrackIdx) t.copy(parameters = updatedParams) else t
            }
        )
        
        // Sync waveform if on Sampler/Granular and recording or focused
        if (track.engineType == EngineType.SAMPLER || track.engineType == EngineType.GRANULAR) {
            pullWaveform(focusedTrackIdx)
        }
    }

    fun pullWaveform(trackIdx: Int) {
        val waveform = nativeLib.getWaveform(trackIdx)
        if (waveform != null) {
            // We don't store waveform in state directly usually, it's often a separate Flow or managed by the screen
            // But if we want to ensure visual preview, we might need a way to pass it.
            // For now, let's assume the screen calls nativeLib.getWaveform directly or we add a waveform field to TrackState if needed.
            // Actually, looking at SamplerScreen, it might be using a local state.
        }
    }


    /**
     * Helper to determine which MIDI note a given 4x4 pad index (0-15) should trigger
     * based on the current track's engine type and scale settings.
     */
    fun getNoteForPadIndex(padIndex: Int, state: GrooveboxState): Int {
        val track = state.tracks[state.selectedTrackIndex]
        val maxPadIndex = when(state.gridMode) {
            GridMode.GRID_4X4 -> 15
            GridMode.GRID_6X6 -> 35
            GridMode.MAC_KEYS -> 45
            GridMode.TONNETZ -> 127 // Tonnetz handles itself elsewhere usually but for safety
        }
        if (padIndex < 0 || padIndex > maxPadIndex) return -1
        
        val samplerMode = track.parameters[320] ?: 0f
        val isChopMode = track.engineType == EngineType.SAMPLER && samplerMode >= 0.6f
        val numSlices = if (isChopMode) (((track.parameters[340] ?: 0f) * 14f).toInt() + 2) else 0

        return if (track.engineType == EngineType.FM_DRUM) {
            60 + (padIndex % 16)
        } else if (isChopMode) {
            if (padIndex < numSlices) 60 + padIndex else -1
        } else if (track.engineType == EngineType.ANALOG_DRUM) {
            val localIdx = if (padIndex >= 8) padIndex - 8 else padIndex
            if (localIdx < 6) {
                when(localIdx) {
                    0 -> 60 // Kick
                    1 -> 61 // Snare
                    2 -> 62 // Rim
                    3 -> 63 // Hat C
                    4 -> 64 // Hat O
                    5 -> 65 // Cymbal
                    else -> -1
                }
            } else -1
        } else {
            // Melodic mapping
            val count = maxOf(16, padIndex + 1)
            val scaleNotes = ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, count)
            scaleNotes.getOrElse(padIndex) { state.rootNote + padIndex }
        }
    }

    fun triggerPad(padIndex: Int, velocity: Int) {
        val note = getNoteForPadIndex(padIndex, state)
        if (note != -1) {
            nativeLib.triggerNote(state.selectedTrackIndex, note, velocity)
            state = state.copy(heldNotes = state.heldNotes + note)
        }
    }

    fun releasePad(padIndex: Int) {
        val note = getNoteForPadIndex(padIndex, state)
        if (note != -1) {
            nativeLib.releaseNote(state.selectedTrackIndex, note)
            state = state.copy(heldNotes = state.heldNotes - note)
        }
    }

    fun processMidiMessage(data: ByteArray) {
        midiRouter.processMidiMessage(data, state)
    }

    private fun handleMidiCommand(command: MidiCommand) {
        when (command) {
            is MidiCommand.BankChange -> {
                state = state.copy(currentSequencerBank = command.bank)
            }
            is MidiCommand.TrackVolume -> {
                val newTracks = state.tracks.toMutableList()
                if (command.trackIdx in newTracks.indices) {
                    newTracks[command.trackIdx] = newTracks[command.trackIdx].copy(volume = command.volume)
                    state = state.copy(tracks = newTracks)
                }
            }
            is MidiCommand.ParameterChange -> {
                if (command.parameterId in -103..-100) {
                    val stripIdx = -(command.parameterId + 100)
                    val newValues = state.stripValues.toMutableList()
                    if (stripIdx in newValues.indices) {
                        newValues[stripIdx] = command.value
                        state = state.copy(stripValues = newValues)
                    }
                } else if (command.parameterId in -203..-200) {
                    val knobIdx = -(command.parameterId + 200)
                    val newValues = state.knobValues.toMutableList()
                    if (knobIdx in newValues.indices) {
                        newValues[knobIdx] = command.value
                        state = state.copy(knobValues = newValues)
                    }
                }
            }
            is MidiCommand.Transport -> {
                when (command.action) {
                    "PLAY" -> { state = state.copy(isPlaying = true); nativeLib.setPlaying(true) }
                    "STOP" -> { state = state.copy(isPlaying = false, isRecording = false); nativeLib.setPlaying(false); nativeLib.setIsRecording(false) }
                    "RECORD" -> { val newRec = !state.isRecording; state = state.copy(isRecording = newRec, isPlaying = if (newRec) true else state.isPlaying); nativeLib.setIsRecording(newRec); if (newRec) nativeLib.setPlaying(true) }
                }
            }
            is MidiCommand.NextTrack -> { state = state.copy(selectedTrackIndex = (state.selectedTrackIndex + 1) % state.tracks.size) }
            is MidiCommand.ToggleMidiLearn -> { val nl = !state.midiLearnActive; state = state.copy(midiLearnActive = nl, midiLearnStep = if (nl) 1 else 0, midiLearnSelectedStrip = null) }
            is MidiCommand.MidiLearnSelect -> { if (state.midiLearnActive && state.midiLearnStep == 1) state = state.copy(midiLearnSelectedStrip = command.stripIdx, midiLearnStep = 2) }
            is MidiCommand.MacroValue -> { val mi = command.macroIdx; if (mi in state.macros.indices) { val nm = state.macros.toMutableList(); nm[mi] = nm[mi].copy(value = command.value); state = state.copy(macros = nm) } }
            is MidiCommand.NoteTriggered -> { state = state.copy(lastMidiNote = command.note, lastMidiVelocity = command.velocity) }
            is MidiCommand.StepToggle -> { 
                // We need the toggleStep logic here or accessible. 
                // For now I will manually implement it or leave it as it matches MainActivity.
                // Re-implementing toggleStep logic in VM is better anyway for shared logic.
                val trackIdx = state.selectedTrackIndex
                val track = state.tracks[trackIdx]
                val stepIdx = command.stepIdx
                val isDrum = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM
                if (isDrum) {
                    val instIdx = track.selectedFmDrumInstrument
                    val currentDrumSteps = track.drumSteps[instIdx]
                    val step = currentDrumSteps[stepIdx]
                    val newActive = !step.active
                    val newDrumSteps = track.drumSteps.mapIndexed { idx, ds ->
                        if (idx == instIdx) ds.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive) else s }
                        else ds
                    }
                    val finalNotes = if (newActive && step.notes.isEmpty()) listOf(60) else step.notes
                    state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(drumSteps = newDrumSteps) else t })
                    nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), step.velocity, step.ratchet, step.punch, step.probability, step.gate, step.isSkipped)
                } else {
                    val step = track.steps[stepIdx]
                    val newActive = !step.active
                    val finalNotes = if (newActive && step.notes.isEmpty()) listOf(60) else step.notes
                    val newSteps = track.steps.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive, notes = finalNotes) else s }
                    state = state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(steps = newSteps) else t })
                    nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), step.velocity, step.ratchet, step.punch, step.probability, step.gate, step.isSkipped)
                }
            }
        }
    }
}
