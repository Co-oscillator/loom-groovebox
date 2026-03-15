package com.groovebox

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import com.groovebox.ui.views.isBlackKey

class GrooveboxViewModel(
    private val nativeLib: NativeLib,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + Job())
) {
    var state by mutableStateOf(createInitialState())
        private set

    fun onStateChange(newState: GrooveboxState) {
        state = newState
    }

    private fun createInitialState(): GrooveboxState {
        val tracks = List(8) { i ->
            when(i) {
                0 -> TrackState(id = i, engineType = EngineType.SUBTRACTIVE)
                1 -> {
                    val fmParams = mutableMapOf<Int, Float>()
                    fmParams[160] = 0.8f // Op 1 Lvl
                    fmParams[166] = 0.4f // Op 2 Lvl
                    fmParams[165] = 1.0f // Op 1 Ratio
                    fmParams[171] = 2.0f // Op 2 Ratio
                    fmParams[161] = 0.01f // Op 1 Atk
                    fmParams[162] = 0.5f // Op 1 Dcy
                    fmParams[9] = 0.5f   // Center Pan
                    TrackState(id = i, engineType = EngineType.FM, parameters = fmParams, fmCarrierMask = 3, pan = 0.5f)
                }
                2 -> TrackState(id = i, engineType = EngineType.WAVETABLE)
                3 -> TrackState(id = i, engineType = EngineType.SAMPLER)
                4 -> TrackState(id = i, engineType = EngineType.GRANULAR)
                5 -> TrackState(id = i, engineType = EngineType.FM_DRUM)
                6 -> TrackState(id = i, engineType = EngineType.ANALOG_DRUM)
                7 -> TrackState(id = i, engineType = EngineType.MIDI)
                else -> TrackState(id = i, engineType = EngineType.SUBTRACTIVE)
            }
        }
        return GrooveboxState(tracks = tracks, tempo = 80.0f)
    }

    fun sanitizeAndSetState(loadedState: GrooveboxState) {
        state = sanitizeGrooveboxState(loadedState)
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

    private fun sanitizeGrooveboxState(state: GrooveboxState): GrooveboxState {
        val sanitizedTracks = state.tracks.map { track ->
            var newTrack = track
            if (newTrack.volume < 0.05f) newTrack = newTrack.copy(volume = 0.7f)
            newTrack = newTrack.copy(isActive = true)
            val newParams = newTrack.parameters.toMutableMap()
            newParams[0] = newTrack.volume
            if ((newParams[1] ?: 0.0f) < 0.01f) newParams[1] = 1.0f 
            if ((newParams[101] ?: 0.0f) < 0.01f) newParams[101] = 0.5f 
            if ((newParams[102] ?: 0.0f) < 0.01f) newParams[102] = 1.0f 
            
            if (newTrack.engineType == EngineType.SUBTRACTIVE) {
                if ((newParams[5] ?: 0.0f) < 0.1f) newParams[5] = 0.7f 
                if ((newParams[107] ?: 0.0f) < 0.001f && (newParams[108] ?: 0.0f) < 0.001f) {
                    newParams[107] = 0.6f
                    newParams[108] = 0.4f
                }
                if ((newParams[109] ?: 0.0f) < 0.1f) newParams[109] = 0.4f
                if ((newParams[162] ?: 0.0f) < 0.05f) newParams[162] = 0.125f 
                if ((newParams[172] ?: 0.0f) < 0.1f) newParams[172] = 0.0f    
            }

            if (newParams[9] == null || (newParams[9] ?: 0.5f) < 0.001f) {
                newParams[9] = 0.5f
                newTrack = newTrack.copy(pan = 0.5f)
            }
            
            if (newTrack.engineType == EngineType.FM) {
                if ((newParams[350] ?: 0.0f) < 0.1f) newParams[350] = 1.0f 
            }

            newTrack.copy(
                parameters = newParams,
                soundFontPresetName = newTrack.soundFontPresetName ?: "None",
                soundFontMapping = newTrack.soundFontMapping ?: emptyMap(),
                lastSamplePath = newTrack.lastSamplePath ?: "",
                activeWavetableName = newTrack.activeWavetableName ?: "Basic",
                mutatedNotes = newTrack.mutatedNotes ?: emptyMap()
            )
        }
        
        return state.copy(
            tracks = sanitizedTracks,
            isPlaying = false,
            selectedTab = 0
        )
    }

    private fun syncNativeState(state: GrooveboxState, nativeLib: NativeLib) {
        nativeLib.setTempo(state.tempo)
        nativeLib.setMasterVolume(state.masterVolume)
        nativeLib.setScaleConfig(state.rootNote, state.scaleType.intervals.toIntArray())
        nativeLib.setRecordingSource(state.recordingSource)
        nativeLib.setSwing(state.swing)
        
        state.tracks.forEachIndexed { trackIdx, t ->
            nativeLib.setEngineType(trackIdx, t.engineType.ordinal)
            nativeLib.setTrackVolume(trackIdx, t.volume)
            nativeLib.setTrackTranspose(trackIdx, t.transpose)
            nativeLib.setTrackActive(trackIdx, t.isActive)
            nativeLib.setTrackPan(trackIdx, t.pan)
            
            t.parameters.forEach { (pid, v) -> 
                nativeLib.setParameter(trackIdx, pid, v) 
            }
            
            nativeLib.setPatternLength(state.patternLength)
            
            if (t.engineType == EngineType.FM_DRUM || t.engineType == EngineType.ANALOG_DRUM || t.engineType == EngineType.SAMPLER) {
                 for (instIdx in 0 until 16) {
                     val voiceSteps = t.drumSteps.getOrNull(instIdx) ?: emptyList()
                     voiceSteps.forEachIndexed { stepIdx, s ->
                         nativeLib.setStep(trackIdx, stepIdx, s.active, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped)
                     }
                 }
            } else {
                 t.steps.forEachIndexed { stepIdx, s ->
                     nativeLib.setStep(trackIdx, stepIdx, s.active && s.notes.isNotEmpty(), s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped)
                 }
            }
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
}
