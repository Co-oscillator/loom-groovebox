package com.groovebox.utils

import com.groovebox.GrooveboxState
import com.groovebox.EngineType
import com.groovebox.TrackState
import com.groovebox.NativeLib
import com.groovebox.MacroState

fun sanitizeGrooveboxState(state: GrooveboxState): GrooveboxState {
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
        selectedTab = 0,
        globalParameters = state.globalParameters ?: emptyMap(),
        macros = state.macros ?: List(8) { MacroState() }
    )
}

fun syncNativeState(state: GrooveboxState, nativeLib: NativeLib) {
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
