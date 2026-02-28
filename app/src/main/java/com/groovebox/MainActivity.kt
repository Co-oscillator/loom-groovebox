@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)

package com.groovebox

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import android.os.Build
import android.content.Context
import android.os.Environment
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.groovebox.utils.*
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjection
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioPlaybackCaptureConfiguration
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import com.groovebox.utils.AudioExporter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import com.groovebox.midi.EmpledManager
import com.groovebox.midi.MidiManager
import com.groovebox.midi.MidiRouter
import com.groovebox.midi.MidiCommand
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import com.groovebox.persistence.PersistenceManager
import com.groovebox.ui.views.SettingsScreen
import kotlin.math.abs
import java.io.File
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.ui.LocalFocusedValue
import com.groovebox.ui.views.MixerView
import com.groovebox.ui.views.SequencerView
import com.groovebox.ui.views.GlobalEffectsView
import com.groovebox.ui.views.*
import com.groovebox.ui.components.NativeFileDialog
import com.groovebox.ui.components.VerticalScrollbar




fun sanitizeGrooveboxState(state: GrooveboxState): GrooveboxState {
    // ONLY SANITIZE IF IT'S NOT THE INIT PROJECT OR VALUES ARE COMPLETELY ZERO
    val sanitizedTracks = state.tracks.map { track ->
        var newTrack = track
        
        // 1. Force Track Volume ONLY if it's 0 (Silence)
        if (newTrack.volume < 0.05f) {
            newTrack = newTrack.copy(volume = 0.7f)
        }
        
        newTrack = newTrack.copy(isActive = true)
        val newParams = newTrack.parameters.toMutableMap()
        
        newParams[0] = newTrack.volume
        
        // 2. Force Filter Cutoff ONLY if it's 0 (Fully Closed)
        if ((newParams[1] ?: 0.0f) < 0.01f) newParams[1] = 1.0f 
        
        // 4. Force Envelopes if suspiciously low (likely 0)
        if ((newParams[101] ?: 0.0f) < 0.01f) newParams[101] = 0.5f // Decay
        if ((newParams[102] ?: 0.0f) < 0.01f) newParams[102] = 1.0f // Sustain
        
        // Engine-Specific Safety (Osc Levels)
        if (newTrack.engineType == EngineType.SUBTRACTIVE) {
             if ((newParams[5] ?: 0.0f) < 0.1f) newParams[5] = 0.7f 
             // Ensure Osc 1 & 2 are heard
             if ((newParams[107] ?: 0.0f) < 0.001f && (newParams[108] ?: 0.0f) < 0.001f) {
                 newParams[107] = 0.6f
                 newParams[108] = 0.4f
             }
             // Ensure Sub Osc (Osc 3) is audible!
             if ((newParams[109] ?: 0.0f) < 0.1f) newParams[109] = 0.4f
             if ((newParams[162] ?: 0.0f) < 0.05f) newParams[162] = 0.125f // 0.5x pitch
             if ((newParams[172] ?: 0.0f) < 0.1f) newParams[172] = 0.0f    // Reset drive if too low
        }

        // Force Center Panning if missing or hard left (suspicious)
        if (newParams[9] == null || (newParams[9] ?: 0.5f) < 0.001f) {
            newParams[9] = 0.5f
            newTrack = newTrack.copy(pan = 0.5f)
        }
        
        // FM
        if (newTrack.engineType == EngineType.FM) {
           if ((newParams[160] ?: 0.0f) < 0.1f) newParams[160] = 0.8f // Op 1
           if ((newParams[153] ?: 0.0f) < 0.1f) newParams[153] = 1.0f // Carrier Mask
           if ((newParams[151] ?: 0.0f) < 0.1f) newParams[151] = 1.0f // Filter
           if ((newParams[350] ?: 0.0f) < 0.1f) newParams[350] = 1.0f 
        }

        // Wavetable
        if (newTrack.engineType == EngineType.WAVETABLE) {
             if ((newParams[450] ?: 0.0f) < 0.001f) newParams[450] = 0.5f // Position
             if ((newParams[103] ?: 0.0f) < 0.1f) newParams[103] = 0.5f // Release
             if ((newParams[458] ?: 0.0f) < 0.1f) newParams[458] = 1.0f // Filter
             if ((newParams[350] ?: 0.0f) < 0.1f) newParams[350] = 1.0f 
        }

        // Sampler
        if (newTrack.engineType == EngineType.SAMPLER) {
            if ((newParams[302] ?: 0.0f) < 0.05f) newParams[302] = 0.5f // Speed
            if ((newParams[300] ?: 0.0f) < 0.05f) newParams[300] = 0.5f // Pitch
            if ((newParams[301] ?: 0.0f) < 0.05f) newParams[301] = 0.25f // Stretch
            
            if ((newParams[310] ?: 0.0f) < 0.0001f) newParams[310] = 0.001f 
            if ((newParams[311] ?: 0.0f) < 0.05f) newParams[311] = 0.5f 
            if ((newParams[312] ?: 0.0f) < 0.05f) newParams[312] = 1.0f // Sustain
            if ((newParams[350] ?: 0.0f) < 0.1f) newParams[350] = 1.0f // Env

            if ((newParams[330] ?: 0.0f) == 0f && (newParams[331] ?: 0.0f) == 0f) {
                newParams[330] = 0.0f 
                newParams[331] = 1.0f 
            }
        }

        // FM Drum
        if (newTrack.engineType == EngineType.FM_DRUM) {
             for (drum in 0 until 8) {
                 val gainParams = 200 + (drum * 10) + 5
                 if ((newParams[gainParams] ?: 0.0f) < 0.1f) newParams[gainParams] = 0.7f
                 val decayParam = 200 + (drum * 10) + 2
                 if ((newParams[decayParam] ?: 0.0f) < 0.1f) newParams[decayParam] = 0.5f
             }
        }

        // Granular
        if (newTrack.engineType == EngineType.GRANULAR) {
             if ((newParams[406] ?: 0.0f) < 0.01f) newParams[406] = 0.2f // Size
             if ((newParams[407] ?: 0.0f) < 0.01f) newParams[407] = 0.5f // Density
             if ((newParams[400] ?: 0.0f) < 0.01f) newParams[400] = 0.5f // Pos
             if ((newParams[429] ?: 0.0f) < 0.01f) newParams[429] = 0.4f // Gain
             if ((newParams[350] ?: 0.0f) < 0.1f) newParams[350] = 1.0f // Env
             if ((newParams[401] ?: 0.0f) < 0.01f) newParams[401] = 1.0f // Speed!
             if ((newParams[410] ?: 0.0f) < 0.01f) newParams[410] = 1.0f // Pitch!
             if ((newParams[427] ?: 0.0f) < 0.01f) newParams[427] = 1.0f // Sustain
        }
        
        // Analog Drum
        if (newTrack.engineType == EngineType.ANALOG_DRUM) {
             if ((newParams[0] ?: 0.0f) < 0.1f) newParams[0] = 0.5f 
             if ((newParams[1] ?: 0.0f) < 0.1f) newParams[1] = 0.5f 
             if ((newParams[5] ?: 0.0f) < 0.1f) newParams[5] = 0.7f 
        }

        // Force Envelopes ON logic
        if (newTrack.engineType == EngineType.SUBTRACTIVE || newTrack.engineType == EngineType.FM || 
            newTrack.engineType == EngineType.SAMPLER || newTrack.engineType == EngineType.GRANULAR ||
            newTrack.engineType == EngineType.WAVETABLE) { // Added Wavetable
            newParams[350] = 1.0f 
        }
        
        newTrack.copy(
            parameters = newParams,
            // Safety: These non-null fields can be null after deserialization from legacy files
            soundFontPresetName = newTrack.soundFontPresetName ?: "None",
            soundFontMapping = newTrack.soundFontMapping ?: emptyMap(),
            lastSamplePath = newTrack.lastSamplePath ?: "",
            activeWavetableName = newTrack.activeWavetableName ?: "Basic",
            mutatedNotes = newTrack.mutatedNotes ?: emptyMap(),
            arpConfig = if (newTrack.arpConfig.rhythms[0].size < 16) {
                newTrack.arpConfig.copy(rhythms = listOf(
                    List(16) { true },
                    List(16) { false },
                    List(16) { false }
                ))
            } else newTrack.arpConfig
        )
    }
    
    // 7. Force Global Master Filters Open
    val globalParams = sanitizedTracks[0].parameters.toMutableMap()
    globalParams[493] = 1.0f // LP Open
    globalParams[498] = 0.0f // HP Closed
    
    val finalTracks = sanitizedTracks.toMutableList()
    finalTracks[0] = finalTracks[0].copy(parameters = globalParams)
    
    return state.copy(
        tracks = finalTracks,
        isPlaying = false,
        selectedTab = 0, // Force Play Screen on Startup
        globalParameters = state.globalParameters ?: emptyMap(),
        macros = state.macros ?: List(8) { MacroState() }
    )
}

fun syncNativeState(state: GrooveboxState, nativeLib: NativeLib) {
    nativeLib.setTempo(state.tempo)
    nativeLib.setMasterVolume(state.masterVolume)
    nativeLib.setScaleConfig(state.rootNote, state.scaleType.intervals.toIntArray())
    nativeLib.setRecordingSource(state.recordingSource)
    
    // Sync Global Parameters (sent to Track 0)
    state.globalParameters.forEach { (pid, v) -> nativeLib.setParameter(0, pid, v) }

    // Sync Tracks
    state.tracks.forEachIndexed { trackIdx, t ->
        // Ensure engine is set FIRST
        nativeLib.setEngineType(trackIdx, t.engineType.ordinal)
        // Safety: Clamp volume to prevent NaNs/Inf
        val safeVol = if (t.volume.isNaN() || t.volume.isInfinite()) 0.45f else t.volume.coerceIn(0f, 2f)
        Log.d("Groovebox", "SyncTrack ID $trackIdx Type ${t.engineType} Vol $safeVol Act ${t.isActive}")
        
        nativeLib.setTrackVolume(trackIdx, safeVol)
        nativeLib.setTrackTranspose(trackIdx, t.transpose)
        nativeLib.setTrackActive(trackIdx, t.isActive)
        nativeLib.setTrackPan(trackIdx, t.pan) // Re-added Pan sync
        nativeLib.setSelectedFmDrumInstrument(trackIdx, t.selectedFmDrumInstrument)
        
        t.parameters.forEach { (pid, v) -> 
            // Safety: Only prevent NaNs, allow full range (e.g. FM Algo > 1.0)
            val safeVal = if (v.isNaN() || v.isInfinite()) 0.0f else v
            // Debug critical params: Vol(0), Cutoff(1)
            if (pid == 0) Log.d("Groovebox", "SyncTrack ID $trackIdx Param 0 (Vol override?) = $safeVal")
            if (pid == 1) Log.d("Groovebox", "SyncTrack ID $trackIdx Param 1 (Cutoff) = $safeVal")
            
            nativeLib.setParameter(trackIdx, pid, safeVal) 
        }
        
        nativeLib.setPatternLength(state.patternLength)
        
        // Sync Arp
        nativeLib.setArpConfig(
            trackIdx, 
            t.arpConfig.mode.ordinal, 
            t.arpConfig.octaves, 
            t.arpConfig.inversion,
            t.arpConfig.isLatched,
            t.arpConfig.isMutated,
            t.arpConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(),
            t.arpConfig.randomSequence.toIntArray()
        )
        nativeLib.setArpRate(trackIdx, t.arpConfig.arpRate, t.arpConfig.arpDivisionMode)
        nativeLib.setChordProgConfig(trackIdx, t.arpConfig.isChordProgEnabled, t.arpConfig.chordProgMood, t.arpConfig.chordProgComplexity)
        
        // E. Steps & Automation (Lock Parameters)
        if (t.engineType == EngineType.FM_DRUM || t.engineType == EngineType.ANALOG_DRUM || t.engineType == EngineType.SAMPLER) {
             // For Drum/Sampler tracks, we must sync ALL 16 internal sequencers
             for (instIdx in 0 until 16) {
                 val voiceSteps = t.drumSteps.getOrNull(instIdx) ?: emptyList()
                 voiceSteps.forEachIndexed { stepIdx, s ->
                     nativeLib.setStep(
                         trackIdx, 
                         stepIdx, 
                         s.active, 
                         intArrayOf(60 + instIdx), 
                         s.velocity, 
                         s.ratchet, 
                         s.punch, 
                         s.probability, 
                         s.gate,
                         s.isSkipped
                     )
                     s.parameterLocks.forEach { (pid, valAmt) ->
                         nativeLib.setParameterLock(trackIdx, stepIdx, pid, valAmt)
                     }
                 }
             }
        } else {
             // Standard Tracks
             t.steps.forEachIndexed { stepIdx, s ->
                 val isActiveWithNotes = s.active && s.notes.isNotEmpty()
                 nativeLib.setStep(trackIdx, stepIdx, isActiveWithNotes, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped)
                 
                 // SYNC P-LOCKS
                 s.parameterLocks.forEach { (pid, valAmt) ->
                     nativeLib.setParameterLock(trackIdx, stepIdx, pid, valAmt)
                 }
             }
        }

        // F. Sample/Wavetable/SoundFont
        if ((t.lastSamplePath ?: "").isNotEmpty()) {
            if (t.engineType == EngineType.WAVETABLE) {
                nativeLib.loadWavetable(trackIdx, t.lastSamplePath ?: "")
            } else if (t.engineType == EngineType.SAMPLER || t.engineType == EngineType.GRANULAR) {
                nativeLib.loadSample(trackIdx, t.lastSamplePath ?: "")
            }
        }
        if ((t.soundFontPath ?: "").isNotEmpty() && t.engineType == EngineType.SOUNDFONT) {
            nativeLib.loadSoundFont(trackIdx, t.soundFontPath ?: "")
            nativeLib.setSoundFontPreset(trackIdx, t.soundFontPresetIndex)
            // Sync mappings
            (t.soundFontMapping ?: emptyMap()).forEach { (knobId, genId) ->
                nativeLib.setSoundFontMapping(trackIdx, knobId, genId)
            }
        }

        // G. FX Sends
        t.fxSends.forEachIndexed { fxIdx, sendAmt ->
            nativeLib.setParameter(trackIdx, 2000 + (fxIdx * 10), sendAmt)
        }
    }

    // 2. Global Parameters
    state.globalParameters.forEach { (pid, value) ->
        nativeLib.setParameter(0, pid, value)
    }

    // 3. LFOs
    state.lfos.forEachIndexed { i, lfo ->
        nativeLib.setGenericLfoParam(i, 0, lfo.rate)
        nativeLib.setGenericLfoParam(i, 1, lfo.depth)
        nativeLib.setGenericLfoParam(i, 2, lfo.shape.toFloat())
        nativeLib.setGenericLfoParam(i, 3, if (lfo.sync) 1.0f else 0.0f)
    }

    // 4. Macros
    state.macros.forEachIndexed { i, m ->
        nativeLib.setMacroSource(i, m.sourceType, m.sourceIndex)
        nativeLib.setMacroValue(i, m.value)
    }
    
    // 5. Routing Matrix
    state.routingConnections.forEach { connection ->
        // Native setRouting signature: destTrack, sourceTrack, source, dest, amount, destParamId
        // Mapping Kotlin 'RoutingConnection' to Native args:
        // Source: connection.source (Enum Int)
        // DestTrack: connection.destTrack
        // DestParam: connection.destParam (Enum Int)
        // Amount: connection.amount
        
        // What about 'sourceTrack'? For LFOs/Macros it's usually ignored (-1).
        // If source implies a specific track (e.g. Env Follower), we need it.
        // For now, assume -1 or 0 unless we have explicit source track field.
        // Looking at RoutingScreen, when LFO is source, sourceTrack is likely -1 or ignored.
        nativeLib.setRouting(connection.destTrack, -1, connection.source, connection.destParam, connection.amount, -1)
    }

    // 6. FX Chain
    // Reset chain first? Native doesn't have clearChain calls exposed easily, but setting -1 disconnects.
    // Iterating slots: 0->1, 1->2...
    // The state.fxChainSlots is [FX_ID, FX_ID, -1, -1]
    // The native setFxChain(source, dest) links them.
    // We should replicate logic from EffectsScreen or reconstruct the chain.
    val activeSlots = (state.fxChainSlots ?: emptyList()).filter { it != -1 }
    if (activeSlots.isNotEmpty()) {
        for (i in 0 until activeSlots.size - 1) {
            nativeLib.setFxChain(activeSlots[i], activeSlots[i+1])
        }
        // Ensure Pre/Post routing using fixed logic if needed (e.g. Mixer -> Slot 0)
        // Usually handled by hardcoded Mixer -> Chain start in C++.
        // We just need to sync the slots themselves if the engine supports dynamic slots.
        // Native `setFxChain` links two FX units directly.
        // If we have [Reverb, Delay], we call setFxChain(Reverb, Delay).
    }

    // 7. Sidechain
    nativeLib.setSidechainConfig(state.sidechainSourceTrack, state.sidechainSourceDrumIdx)
}

fun toggleStep(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, trackIdx: Int, stepIdx: Int) {
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
        onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(drumSteps = newDrumSteps) else t }))
        nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped)
    } else {
        val rootNote = 60 // Default note if empty
        val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(rootNote) else currentStep.notes
        val newSteps = track.steps.mapIndexed { si, s -> if (si == stepIdx) s.copy(active = newActive, notes = finalNotes) else s }
        onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIdx) t.copy(steps = newSteps) else t }))
         nativeLib.setStep(trackIdx, stepIdx, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped)
    }
}



class MainActivity : ComponentActivity() {
    private lateinit var performanceManager: AudioPerformanceManager
    private val nativeLib = NativeLib()
    private lateinit var midiManager: MidiManager
    private lateinit var midiRouter: MidiRouter
    private lateinit var empledManager: EmpledManager
    private var grooveboxState by mutableStateOf(createInitialState())

    private var mediaProjectionManager: MediaProjectionManager? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            // Revert recording source to MIC if denied
            grooveboxState = grooveboxState.copy(recordingSource = 0)
            nativeLib.setRecordingSource(0)
            Toast.makeText(this, "System Audio Capture Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createInitialState(): GrooveboxState {
        val tracks = List(8) { i ->
            when(i) {
                0 -> TrackState(id = i, engineType = EngineType.SUBTRACTIVE)
                1 -> {
                    val fmParams = mutableMapOf<Int, Float>()
                    // Bell defaults moved to Track 2
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Prevent Tablet Idle Crash by keeping screen on
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Use WindowInsetsControllerCompat to properly hide the Android menubar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        performanceManager = AudioPerformanceManager(this)
        performanceManager.startSession()

        nativeLib.setAppDataDir(getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath)
        
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        
        // Request recording and storage permissions
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT <= 32) {
             perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
             perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        // Copy bundled SoundFonts to internal storage if not present
        Thread {
            try {
                val sfDir = File(PersistenceManager.getLoomFolder(this), "soundfonts")
                // Proactive Fix: Migrate singular "soundfont" folder if it exists (legacy typo fix)
                val legacySfDir = File(PersistenceManager.getLoomFolder(this), "soundfont")
                if (legacySfDir.exists() && !sfDir.exists()) {
                    legacySfDir.renameTo(sfDir)
                    android.util.Log.d("MainActivity", "Migrated singular soundfont folder to plural soundfonts")
                }
                
                if (!sfDir.exists()) sfDir.mkdirs()
                
                val assetsToCopy = listOf(
                    "soundfonts/GeneralUser_GS.sf2" to "GeneralUser_GS.sf2"
                )
                
                assetsToCopy.forEach { (assetPath, destName) ->
                    val destFile = File(sfDir, destName)
                    if (!destFile.exists() || destFile.length() < 1024) {
                        try {
                            assets.open(assetPath).use { input ->
                                destFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()


        perms.add(Manifest.permission.RECORD_AUDIO)
        
        val permissionsToRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
        }
        
        // Migrate and copy assets (PersistenceManager now handles try-catch and fallbacks)
        try {
            PersistenceManager.migrateToExternalStorage(this)
            PersistenceManager.copyWavetablesToFilesDir(this)
            PersistenceManager.copySoundFontsToFilesDir(this)
        } catch (e: Exception) {
            Log.e("Groovebox", "Persistence startup error: ${e.message}")
        }

        
        // Initialize State with Safe Loading
        // Initialize State with Safe Loading & Crash Loop Protection
        val prefs = getSharedPreferences("GrooveboxPrefs", Context.MODE_PRIVATE)
        val crashedLastLaunch = prefs.getBoolean("crashed_on_launch", false)
        
        // Mark this launch as "Potentially Crashing" (cleared after 10s)
        prefs.edit().putBoolean("crashed_on_launch", true).apply()
        
        // Clear the flag after 10 seconds of stability
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            prefs.edit().putBoolean("crashed_on_launch", false).apply()
            Log.d("Groovebox", "Stability Check Passed. Crash Flag Cleared.")
        }, 10000)

        var loadedState: GrooveboxState? = null
        if (crashedLastLaunch) {
            Log.e("Groovebox", "CRASH LOOP DETECTED! Resetting to Fresh State.")
            android.widget.Toast.makeText(this, "Safe Mode: Settings Reset due to Crash", android.widget.Toast.LENGTH_LONG).show()
            
            // Force Fresh State
            loadedState = createInitialState()
            // Overwrite corrupt session
             PersistenceManager.saveProject(this, loadedState, "last_session.gbx")
        } else {
             try {
                 // Load Init project as priority template if it exists, otherwise last session
                 val initProject = PersistenceManager.loadProject(this, "Init.gbx")
                 val lastSession = PersistenceManager.loadProject(this, "last_session.gbx")
                 
                 if (initProject == null) {
                     // Create a fresh Init.gbx if missing
                     val freshState = createInitialState()
                     PersistenceManager.saveProject(this, freshState, "Init.gbx")
                     loadedState = freshState
                     Log.d("Groovebox", "Created new Init.gbx default template")
                 } else {
                     loadedState = initProject ?: lastSession
                 }
                 
                 Log.d("Groovebox", "Started with ${if (initProject != null) "Init.gbx" else "last_session.gbx"}")
             } catch (e: Exception) {
                 e.printStackTrace()
                 loadedState = createInitialState() // Fallback if regular load throws
             }
        }



        grooveboxState = loadedState ?: createInitialState()
        
        // REMOVED: Redundant loadAssignment logic that was overwriting last_session.gbx data.
        // Assignments are now part of GrooveboxState and saved in the project/session file.

        // Initialize Native
        nativeLib.init()
        nativeLib.setAppDataDir(filesDir.absolutePath)
        nativeLib.loadAppState()
        nativeLib.start()

        // Apply Universal Sanitization to the initial/loaded state
        grooveboxState = sanitizeGrooveboxState(grooveboxState)
        
        // Explicitly clear native sequencers on startup to ensure no RAM junk
        for (i in 0 until 8) {
             nativeLib.clearSequencer(i)
        }

        // Sync full state to native engine
        syncNativeState(grooveboxState, nativeLib)

        // Sync Kotlin state with loaded samples
        val initialTracks = grooveboxState.tracks.mapIndexed { i, t ->
            val lastPath = nativeLib.getLastSamplePath(i)
            t.copy(lastSamplePath = lastPath)
        }
        grooveboxState = grooveboxState.copy(tracks = initialTracks)

        midiRouter = MidiRouter(nativeLib) { command ->
            when (command) {
                is MidiCommand.BankChange -> {
                    grooveboxState = grooveboxState.copy(currentSequencerBank = command.bank)
                }
                is MidiCommand.TrackVolume -> {
                    val newTracks = grooveboxState.tracks.toMutableList()
                    if (command.trackIdx in newTracks.indices) {
                        newTracks[command.trackIdx] = newTracks[command.trackIdx].copy(volume = command.volume)
                        grooveboxState = grooveboxState.copy(tracks = newTracks)
                    }
                }
                is MidiCommand.ParameterChange -> {
                    // command.parameterId is special here: -100 to -103 for strips, -200 to -203 for knobs
                    if (command.parameterId in -103..-100) {
                        val stripIdx = -(command.parameterId + 100)
                        val newValues = grooveboxState.stripValues.toMutableList()
                        if (stripIdx in newValues.indices) {
                            newValues[stripIdx] = command.value
                            grooveboxState = grooveboxState.copy(stripValues = newValues)
                        }
                    } else if (command.parameterId in -203..-200) {
                        val knobIdx = -(command.parameterId + 200)
                        val newValues = grooveboxState.knobValues.toMutableList()
                        if (knobIdx in newValues.indices) {
                            newValues[knobIdx] = command.value
                            grooveboxState = grooveboxState.copy(knobValues = newValues)
                        }
                    }
                }
                is MidiCommand.Transport -> {
                    when (command.action) {
                        "PLAY" -> {
                            grooveboxState = grooveboxState.copy(isPlaying = true)
                            nativeLib.setPlaying(true)
                        }
                        "STOP" -> {
                            grooveboxState = grooveboxState.copy(isPlaying = false, isRecording = false)
                            nativeLib.setPlaying(false)
                            nativeLib.setIsRecording(false)
                        }
                        "RECORD" -> {
                            val newRec = !grooveboxState.isRecording
                            grooveboxState = grooveboxState.copy(isRecording = newRec, isPlaying = if (newRec) true else grooveboxState.isPlaying)
                            nativeLib.setIsRecording(newRec)
                            if (newRec) nativeLib.setPlaying(true)
                        }
                    }
                }
                is MidiCommand.NextTrack -> {
                    val nextIdx = (grooveboxState.selectedTrackIndex + 1) % grooveboxState.tracks.size
                    grooveboxState = grooveboxState.copy(selectedTrackIndex = nextIdx)
                }
                is MidiCommand.ToggleMidiLearn -> {
                    val newLearn = !grooveboxState.midiLearnActive
                    grooveboxState = grooveboxState.copy(
                        midiLearnActive = newLearn,
                        midiLearnStep = if (newLearn) 1 else 0,
                        midiLearnSelectedStrip = null
                    )
                }
                is MidiCommand.MidiLearnSelect -> {
                    if (grooveboxState.midiLearnActive && grooveboxState.midiLearnStep == 1) {
                        grooveboxState = grooveboxState.copy(
                            midiLearnSelectedStrip = command.stripIdx,
                            midiLearnStep = 2
                        )
                    }
                }
                is MidiCommand.MacroValue -> {
                    // Update the on-screen macro value
                    val macroIdx = command.macroIdx
                    if (macroIdx in grooveboxState.macros.indices) {
                        val newMacros = grooveboxState.macros.toMutableList()
                        newMacros[macroIdx] = newMacros[macroIdx].copy(value = command.value)
                        grooveboxState = grooveboxState.copy(macros = newMacros)
                    }
                }
                is MidiCommand.NoteTriggered -> {
                    // Update UI state for pad highlighting
                    grooveboxState = grooveboxState.copy(lastMidiNote = command.note, lastMidiVelocity = command.velocity)
                }
                is MidiCommand.StepToggle -> {
                    toggleStep(grooveboxState, { grooveboxState = it }, nativeLib, grooveboxState.selectedTrackIndex, command.stepIdx)
                }
            }
        }
        Log.e("Groovebox", "@@@ MainActivity onCreate: Starting MIDI Initialization")
        midiManager = MidiManager(this) { message ->
            midiRouter.processMidiMessage(message, grooveboxState)
        }
        midiRouter.setMidiSender(midiManager::sendMidi)
        empledManager = EmpledManager(midiManager)
        Log.e("Groovebox", "@@@ MainActivity onCreate: Sending Handshake")
        empledManager.sendHandshake()

        setContent {
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(2000)
                showSplash = false
            }

            GrooveboxTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen(empledManager, nativeLib, grooveboxState, midiManager, onRecordingSourceChange = ::handleRecordingSourceChange) { grooveboxState = it }
                    
                    // RETRY HANDSHAKE after UI load
                    // Some devices aren't ready for input instantly after connection
                    LaunchedEffect(Unit) {
                        delay(1000)
                        Log.e("Groovebox", "@@@ RETRY HANDSHAKE (1s delay)")
                        empledManager.sendHandshake()
                    }
                    

                    AnimatedVisibility(
                        visible = showSplash,
                        exit = fadeOut(animationSpec = tween(1000))
                    ) {
                        SplashScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure audio engine is explicitly resumed/started if it was closed or suspended
        nativeLib.start()
    }

    override fun onPause() {
        super.onPause()
        PersistenceManager.saveProject(this, grooveboxState, "last_session.gbx")
    }

    private fun stopLoopbackCapture() {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        stopService(intent)
    }

    fun handleRecordingSourceChange(source: Int) {
        if (grooveboxState.recordingSource == source) return
        
        if (source == 2) { // SYSTEM
            val intent = mediaProjectionManager?.createScreenCaptureIntent()
            if (intent != null) projectionLauncher.launch(intent)
        } else {
            if (grooveboxState.recordingSource == 2) {
                stopLoopbackCapture()
            }
        }
        grooveboxState = grooveboxState.copy(recordingSource = source)
        nativeLib.setRecordingSource(source)
    }

    override fun onDestroy() {
        stopLoopbackCapture()
        super.onDestroy()
        nativeLib.stop()
        midiManager.close()
    }
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_icon_round),
                contentDescription = "Loom Icon",
                modifier = Modifier
                    .size(400.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Fit
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = "LOOM GROOVEBOX",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    color = Color.White
                )
            )
        }
    }
}

@Composable
fun MainScreen(
    empledManager: EmpledManager,
    nativeLib: NativeLib,
    state: GrooveboxState,
    midiManager: MidiManager,
    onRecordingSourceChange: (Int) -> Unit = {},
    onStateChange: (GrooveboxState) -> Unit
) {
    
    var localFocusedValue by remember { mutableStateOf<String?>(null) }
    
    CompositionLocalProvider(LocalFocusedValue provides { localFocusedValue = it }) {
        // Wrap children in provider
    
    var cpuLoad by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                cpuLoad = nativeLib.getCpuLoad()
                
                // Poll Engine Events
                val events = nativeLib.fetchEngineEvents()
                if (events.isNotEmpty()) {
                    val pendingSaves = mutableMapOf<Int, Int>() // trackIdx -> slotIdx

                    for (i in 0 until events.size / 3) {
                        val type = events[i * 3]
                        val trackIdx = events[i * 3 + 1]
                        val slotIdx = events[i * 3 + 2]

                    }

                    // Process unique saves
                    pendingSaves.forEach { (trackIdx, slotIdx) ->
                         val track = state.tracks.getOrNull(trackIdx)
                         if (track != null) {
                             val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                             val filename = "Rec_${track.engineType.name}_T${trackIdx + 1}_S${slotIdx + 1}_$timestamp.gbp"
                             val file = File(File(PersistenceManager.getLoomFolder(context), "Sequences"), filename)
                             if (!file.parentFile.exists()) file.parentFile.mkdirs()
                             
                             nativeLib.saveTrackPresetToPath(trackIdx, file.absolutePath)
                             
                             withContext(Dispatchers.Main) {
                                 // Toast.makeText(context, "Auto-Saved: $filename", Toast.LENGTH_SHORT).show()
                             }
                        }
                    }
                }
                
                delay(200)
            }
        }
    }

    // Initial state sync with Native
    // Initial state sync with Native (FULL FETCH)
    LaunchedEffect(Unit) {
        // 1. Push critical Globals to Native first
        state.tracks.forEachIndexed { i, track ->
            nativeLib.setEngineType(i, track.engineType.ordinal)
            if (track.engineType == EngineType.WAVETABLE && (track.activeWavetableName ?: "Basic") != "Basic") {
                 // Try to reload wavetable if path known
                 // nativeLib.loadWavetable(i, ...) - requires path logic, skipping for now to rely on native persistence
            }
        }
        nativeLib.setTempo(state.tempo)
        
        // 2. FETCH real state from Native (Knobs & Steps)
        val syncedTracks = state.tracks.mapIndexed { idx, track ->
            // A. Parameters
            val paramArray = nativeLib.getAllTrackParameters(idx)
            val newParams = paramArray.mapIndexed { pid, value -> pid to value }.toMap()
            
            // B. Sequencer Steps (Active State)
            val activeSteps = nativeLib.getAllStepActiveStates(idx)
            val newSteps = track.steps.mapIndexed { si, step ->
                if (si < activeSteps.size) step.copy(active = activeSteps[si]) else step
            }
            
            // C. Drum Steps (if applicable) - For now, Native only returns MAIN sequencer active states. 
            // If we have time, we should expand native-lib to handle drum voices, but checking 0-127 is a good start.
            // (Assuming activeSteps covers the main sequencer mapping).
            
            // C. FX Parameters (Sends & Mix) - CRITICAL FIX for FX Knob Sync
            val fxSends = nativeLib.getFxSends(idx).toList()
            val fxMix = nativeLib.getFxMix(idx).toList()
            
            track.copy(parameters = newParams, steps = newSteps, fxSends = fxSends, fxMix = fxMix)
        }
        
        onStateChange(state.copy(tracks = syncedTracks))
    }

    LaunchedEffect(state.isParameterLocking, state.isRecording, state.midiLearnStep, state.isSelectingSidechain) {
        if (state.isParameterLocking) {
            onStateChange(state.copy(selectedTab = 1)) // Parameters tab
        } else if (state.midiLearnStep == 1) {
            onStateChange(state.copy(selectedTab = 0)) // Playing tab
        } else if (state.midiLearnStep == 2) {
            onStateChange(state.copy(selectedTab = 1))
        } else if (state.isRecording) {
            onStateChange(state.copy(selectedTab = 0))
        }
    }

    // Update LED colors on tab/track change or hardware bank change
     LaunchedEffect(state.selectedTab, state.selectedTrackIndex, state.tracks[state.selectedTrackIndex].engineType, state.currentSequencerBank, state.currentStep, state.isPlaying, state.tracks[state.selectedTrackIndex].selectedFmDrumInstrument) {
        val track = state.tracks[state.selectedTrackIndex]
        Log.e("Groovebox", "@@@ LED LaunchedEffect fired: tab=${state.selectedTab} bank=${state.currentSequencerBank} step=${state.currentStep}")
        
        // Logical check: are we in a sequencing state?
        // Either explicitly on the Sequencing tab (Tab 2) OR on the Playing tab (Tab 0) but in a hardware sequencing bank (Bank > 0)
        val isSequencing = state.selectedTab == 2 || (state.selectedTab == 0 && state.currentSequencerBank > 0)
        
        if (isSequencing) {
            // SEQUENCER MODE LEDs (Steps + Playhead)
            val engineColor = getEngineColor(track.engineType)
            val bankOffset = if (state.selectedTab == 2 || state.currentSequencerBank > 0) {
                // Determine which bank of 16 steps to show
                // If on Tab 2, use the in-app bank selection. If on Tab 0, use the hardware bank selection (Bank B maps to steps 0-15)
                val bank = if (state.selectedTab == 0) state.currentSequencerBank - 1 else state.currentSequencerBank
                (bank * 16).coerceAtLeast(0)
            } else 0
            
            val isSamplerChops = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) > 0.6f
            val isMultiTrack = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || isSamplerChops
            
            for (i in 0 until 16) {
                val stepIdx = bankOffset + i
                if (stepIdx > 63) {
                    empledManager.updatePadColor(i, 0, 0, 0)
                    continue
                }
                
                val isActive = if (isMultiTrack) track.drumSteps[track.selectedFmDrumInstrument][stepIdx].active else track.steps[stepIdx].active
                val isPlayhead = state.isPlaying && state.currentStep == stepIdx
                
                val color = if (isPlayhead) androidx.compose.ui.graphics.Color.White 
                            else if (isActive) engineColor 
                            else engineColor.copy(alpha = 0.1f)
                
                if (state.selectedTab == 0 && state.currentSequencerBank > 0) {
                    empledManager.updateSequencerPadColorCompose(i, color, state.currentSequencerBank)
                } else {
                    empledManager.updatePadColorCompose(i, color)
                }
            }
        } else if (state.selectedTab != 0) {
            // Theme colors for other pages
            val pageColor = when (state.selectedTab) {
                1 -> EmpledManager.PageColor.PARAMETERS
                3 -> EmpledManager.PageColor.EFFECTS
                4 -> EmpledManager.PageColor.ROUTING
                else -> EmpledManager.PageColor.SETTINGS
            }
            empledManager.updatePadColors(pageColor)
        }
        // Note: For Tab 0 / Bank 0 (Playing), the PlayingPad composables handle their own individual LED updates reactively.
    }

    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    // Persistent Strip/Knob Assignments Logic
    // 1. Save assignments when they change
    LaunchedEffect(state.stripRoutings, state.knobRoutings) {
        val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
        val currentEngine = currentTrack.engineType
        
        val savedStrips = (latestState.engineTypeStripAssignments ?: emptyMap())[currentEngine]
        val savedKnobs = (latestState.engineTypeKnobAssignments ?: emptyMap())[currentEngine]

        var newState = latestState
        var changed = false

        if (savedStrips != latestState.stripRoutings) {
            val newMap = (latestState.engineTypeStripAssignments ?: emptyMap()).toMutableMap()
            newMap[currentEngine] = latestState.stripRoutings
            newState = newState.copy(engineTypeStripAssignments = newMap)
            changed = true
        }
        if (savedKnobs != latestState.knobRoutings) {
            val newMap = (latestState.engineTypeKnobAssignments ?: emptyMap()).toMutableMap()
            newMap[currentEngine] = latestState.knobRoutings
            newState = newState.copy(engineTypeKnobAssignments = newMap)
            changed = true
        }

        if (changed) {
            // PersistenceManager.saveAssignments() removed to prevent conflict. 
            // Assignments are saved with the Project/Session.
            latestOnStateChange(newState)
        }
    }

    // 2. Load assignments when track or engine changes
    LaunchedEffect(state.selectedTrackIndex, state.tracks[state.selectedTrackIndex].engineType) {
        val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
        val currentEngine = currentTrack.engineType
        
        // Strips
        val savedStrips = (latestState.engineTypeStripAssignments ?: emptyMap())[currentEngine]
        val newStrips = savedStrips ?: List(4) { i -> StripRouting(stripIndex = i) }
        
        // Knobs
        val savedKnobs = (latestState.engineTypeKnobAssignments ?: emptyMap())[currentEngine]
        val newKnobs = savedKnobs ?: List(4) { i -> StripRouting(stripIndex = i + 4, parameterName = "Knob ${i+1}") }
        
        if (latestState.stripRoutings != newStrips || latestState.knobRoutings != newKnobs) {
             latestOnStateChange(latestState.copy(stripRoutings = newStrips, knobRoutings = newKnobs))
        }
    }
    
    // Polling for current step
    LaunchedEffect(state.isPlaying, state.selectedTrackIndex, state.tracks[state.selectedTrackIndex].selectedFmDrumInstrument) {
        if (state.isPlaying) {
            while (true) {
                val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
                val isDrum = currentTrack.engineType == EngineType.FM_DRUM || 
                             currentTrack.engineType == EngineType.ANALOG_DRUM || 
                             (currentTrack.engineType == EngineType.SAMPLER && (currentTrack.parameters[320] ?: 0f) > 0.6f)
                
                val drumIdx = if (isDrum) currentTrack.selectedFmDrumInstrument else -1
                val stepNum = nativeLib.getCurrentStep(latestState.selectedTrackIndex, drumIdx)
                
                if (stepNum != latestState.currentStep) {
                    latestOnStateChange(latestState.copy(currentStep = stepNum))
                }
                kotlinx.coroutines.delay(20)
            }
        }
    }

    // High-Frequency MIDI Event Polling
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                val events = nativeLib.fetchMidiEvents()
                if (events.isNotEmpty()) {
                    events.toList().chunked(4).forEach { event ->
                        // event is a List<Byte> of size 4: [type, channel, data1, data2]
                        val type = event[0].toInt()
                        val trackIdx = event[1].toInt() // This comes from C++ as Track Index (0-7)
                        val data1 = event[2]
                        val data2 = event[3]
                        
                        // Look up the track's configured MIDI Output Channel
                        // Access via property or state passed in?
                        // If we are inside MainActivity's scope, it should be visible.
                        // Try this@MainActivity.grooveboxState
                        // Look up the track's configured MIDI Output Channel
                        // Use latestState which is captured and updated
                        val track = latestState.tracks.getOrNull(trackIdx)
                        val configCh = track?.midiOutChannel ?: 1 // Default to Ch 1 if null
                        
                        // Logic:
                        // 1-16: Use that channel (0-15)
                        // 17 (All): Use trackIdx (0-7) mapped to Ch 1-8 (Pass-through)
                        
                        val targetChannel = if (configCh == 17) trackIdx else (configCh - 1).coerceIn(0, 15)
                        
                        // Construct MIDI Message (3 bytes: status, data1, data2)
                        // Note: Status = Type (0xF0) | Channel (0x0F)
                        // We must masking type to 0xF0 (e.g. 0x90) and OR with targetChannel
                        val status = (type and 0xF0) or (targetChannel and 0x0F)
                        midiManager.sendMidi(byteArrayOf(status.toByte(), data1.toByte(), data2.toByte()))
                    }
                }
                delay(5) // Poll very fast (~200Hz)
            }
        }
    }

    // Poll sequencer steps during recording to ensure UI sync
    LaunchedEffect(state.isRecording, state.selectedTrackIndex, state.currentSequencerBank) {
        if (state.isRecording) {
            while (true) {
                val bankOffset = latestState.currentSequencerBank * 16
                var stateNeedsUpdate = false
                val updatedTracks = latestState.tracks.mapIndexed { tIdx, t ->
                    if (tIdx == latestState.selectedTrackIndex) {
                        val isDrum = t.engineType == EngineType.FM_DRUM
                        val nativeStates = nativeLib.getAllStepActiveStates(tIdx)
                        if (nativeStates.isEmpty()) return@mapIndexed t

                        if (isDrum) {
                            val instIdx = t.selectedFmDrumInstrument
                            val newDrumStepsList = t.drumSteps.mapIndexed { di, dsteps ->
                                if (di == instIdx) {
                                    var drumChanged = false
                                    val newSteps = dsteps.mapIndexed { si, s ->
                                        if (si < nativeStates.size) {
                                            val nativeActive = nativeStates[si]
                                            if (nativeActive) {
                                                val nativeNotes = nativeLib.getStepNotes(tIdx, si, di).toList()
                                                val nativeVel = nativeLib.getStepVelocity(tIdx, si, di)
                                                
                                                if (nativeActive != s.active || nativeNotes != s.notes || Math.abs(nativeVel - s.velocity) > 0.01f) {
                                                    drumChanged = true
                                                    s.copy(active = nativeActive, notes = nativeNotes, velocity = nativeVel)
                                                } else s
                                            } else {
                                                if (s.active) {
                                                    drumChanged = true
                                                    s.copy(active = false)
                                                } else s
                                            }
                                        } else s
                                    }
                                    if (drumChanged) stateNeedsUpdate = true
                                    newSteps
                                } else dsteps
                            }
                            t.copy(drumSteps = newDrumStepsList)
                        } else {
                            var trackChanged = false
                            val newSteps = t.steps.mapIndexed { si, s ->
                                if (si < nativeStates.size) {
                                    val nativeActive = nativeStates[si]
                                    if (nativeActive) {
                                        // Pull recorded data from engine
                                        // Pull recorded data from engine
                                        val nativeNotes = nativeLib.getStepNotes(tIdx, si).toList()
                                        val nativeVel = nativeLib.getStepVelocity(tIdx, si)
                                        val nativeSub = nativeLib.getStepSubStep(tIdx, si)
                                        
                                        if (nativeActive != s.active || nativeNotes != s.notes || Math.abs(nativeVel - s.velocity) > 0.01f) {
                                            trackChanged = true
                                            s.copy(active = nativeActive, notes = nativeNotes, velocity = nativeVel, subStepOffset = nativeSub)
                                        } else s
                                    } else {
                                        if (s.active) {
                                            trackChanged = true
                                            s.copy(active = false)
                                        } else s
                                    }
                                } else s
                            }
                            if (trackChanged) stateNeedsUpdate = true
                            t.copy(steps = newSteps)
                        }
                    } else t
                }
                
                if (stateNeedsUpdate) {
                    latestOnStateChange(latestState.copy(tracks = updatedTracks))
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    // Dynamic Pattern Length Calculation
    LaunchedEffect(state.tracks, state.currentSequencerBank) {
        var maxUsedStep = 15 // Default 16 steps
        state.tracks.forEach { track ->
            track.steps.forEachIndexed { i, step ->
                if (step.active) maxUsedStep = maxUsedStep.coerceAtLeast(i)
            }
            track.drumSteps.forEach { steps ->
                steps.forEachIndexed { i, step ->
                    if (step.active) maxUsedStep = maxUsedStep.coerceAtLeast(i)
                }
            }
        }
        
        // Also consider current bank viewing
        val viewingLastStep = (state.currentSequencerBank + 1) * 16 - 1
        maxUsedStep = maxUsedStep.coerceAtLeast(viewingLastStep)
        
        val newLength = if (state.is64StepView) 64
                        else if (maxUsedStep < 16) 16
                        else if (maxUsedStep < 32) 32
                        else if (maxUsedStep < 48) 48
                        else 64
        
        if (newLength != state.patternLength) {
            onStateChange(state.copy(patternLength = newLength))
            nativeLib.setPatternLength(newLength)
        }
    }

    // State for SideSheet is no longer needed
    var arpSheetOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Edge: Sidebar Mixer
            MixerView(state, onStateChange, nativeLib)

        // Center: Main Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(
                    if (state.isRecording && (state.selectedTab == 0 || state.selectedTab == 1 || state.selectedTab == 2 || state.selectedTab == 3)) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.1f,
                            targetValue = 0.5f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(500, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            )
                        )
                        Modifier.border(4.dp, Color.Red.copy(alpha = alpha))
                    } else Modifier
                )
        ) {
            when (state.selectedTab) {
                0 -> PlayingScreen(state, onStateChange, nativeLib, empledManager, midiManager)
                1 -> ParametersScreen(state, state.selectedTrackIndex, onStateChange, nativeLib, onRecordingSourceChange = onRecordingSourceChange)
                2 -> SequencerView(state, onStateChange, nativeLib, empledManager)
                3 -> GlobalEffectsView(state, onStateChange, nativeLib)
                4 -> RoutingScreen(state, onStateChange, nativeLib)
                5 -> SettingsScreen(state, onStateChange, nativeLib, midiManager)
            }
            
            Text(
                text = "CPU: ${(cpuLoad * 100).toInt()}%", 
                style = MaterialTheme.typography.labelSmall, 
                color = Color.White.copy(alpha = 0.5f), 
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )



            // Parameter Value Display (Bottom Right)
            val displayValue = localFocusedValue ?: state.focusedValue
            val isPhone = LocalConfiguration.current.screenWidthDp < 600
            displayValue?.let { valStr ->
                Text(
                    text = valStr,
                    style = if (isPhone) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) 
                            else MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd) // Moved to Top
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            
            // MIDI Learn Banner
            if (state.midiLearnActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Yellow),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val text = when(state.midiLearnStep) {
                                1 -> "MIDI LEARN ACTIVE: SELECT STRIP"
                                2 -> "SELECT PARAMETER TO MAP"
                                else -> "MIDI LEARN ACTIVE"
                            }
                            Text(text, color = Color.Black, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            Text("Click a UI control to map", color = Color.Black.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Parameter Lock Banner
            if (state.isParameterLocking) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 60.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Magenta),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        onClick = { onStateChange(state.copy(isParameterLocking = false)) }
                    ) {
                        Text("RECORDING PARAMETER LOCK (Tap to Exit)", modifier = Modifier.padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
                }
            }

            // Right Sidebars
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Transport Column
                Box(modifier = Modifier.width(80.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.2f))) {
                    TransportControls(state, onStateChange, nativeLib)
                }
                
                // Vertical Navigation Tabs
                VerticalNavigationTabs(state.selectedTab, state.isRecording) { onStateChange(state.copy(selectedTab = it)) }
            }
        }
    }

    // Modal Side Sheet Overlay
    // EngineSideSheet call removed

    // Arp Settings Overlay
    ArpSettingsSheet(
        isOpen = arpSheetOpen,
        onDismiss = { arpSheetOpen = false },
        state = state,
        onStateChange = onStateChange,
        nativeLib = nativeLib
    )
}
}



@Composable
fun VerticalNavigationTabs(selectedTab: Int, isRecording: Boolean, onTabSelected: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.5f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        val tabItems = listOf("Play", "Param", "Seq", "FX", "Patch", "Settings")
        tabItems.forEachIndexed { index, title ->
            val isRecordTarget = isRecording && (index == 0 || index == 1 || index == 2)
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selectedTab == index) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .background(Color.Cyan.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .border(1.dp, Color.Cyan, RoundedCornerShape(4.dp))
                    )
                } else if (isRecordTarget) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(2.dp)
                            .border(1.dp, Color.Red, RoundedCornerShape(4.dp))
                    )
                }
                
                if (index < 5) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedTab == index) Color.Cyan else if (isRecordTarget) Color.Red else Color.White,
                        modifier = Modifier.graphicsLayer { rotationZ = 90f }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = title,
                        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 90f },
                        tint = if (selectedTab == index) Color.Cyan else Color.White
                    )
                }
            }
        }
    }
}

// Moved to com.groovebox.ui.views.SettingsScreen


@Composable
fun SoundFontCreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Credits & Privacy Policy", color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Loom includes curated sounds and code. We thank the following creators:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
                
                CreditItem("GeneralUser GS", "S. Christian Collins", "GNU GPL v2", "A versatile GM/GS soundset.")
                CreditItem("Chorium Rev A", "open-samples.com", "GPL", "High-quality transparent GM soundset.")
                CreditItem("Musical Artifacts", "musical-artifacts.com", "Open Community", "A fantastic resource for soundfonts and presets.")
                
                Spacer(modifier = Modifier.height(16.dp))
                Text("PRIVACY POLICY", style = MaterialTheme.typography.labelLarge, color = Color.Magenta)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Loom Groovebox does NOT collect, store, or transmit any personal data.\n\n" +
                    "AUDIO RECORDING: The app requests access to the microphone solely for the 'Sampler' feature, allowing users to record their own sounds for use within the engine. Recorded audio is processed locally and is never uploaded or shared.\n\n" +
                    "STORAGE: The app accesses its own internal storage to save projects and recorded samples. It does not access unrelated personal files.\n\n" +
                    "DATA COLLECTION: No user data, analytics, or behavioral tracking information is collected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(16.dp))
                Text("LICENSING", style = MaterialTheme.typography.labelLarge, color = Color.Cyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Loom Groovebox is Free Software released under the GNU GPL v3.\n\n" +
                    "Copyright © 2026 R Daniel Miller\n\n" +
                    "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.",
                    style = MaterialTheme.typography.labelSmall, 
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                Text("Note: Larger SoundFonts like VSCO 2 and SGM are not bundled to keep the app size compact. You can import them manually via the SoundFont selector.", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("CLOSE") }
        },
        containerColor = Color(0xFF121212),
        tonalElevation = 8.dp
    )
}

@Composable
fun CreditItem(name: String, author: String, license: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = Color.Cyan, fontWeight = FontWeight.Bold)
        Text("By $author", style = MaterialTheme.typography.bodySmall, color = Color.White)
        Text("License: $license", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        Divider(modifier = Modifier.padding(top = 8.dp), color = Color.DarkGray.copy(alpha=0.3f))
    }
}


@Composable
fun TransportControls(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)

    val isWideScreen = (LocalConfiguration.current.screenWidthDp.toFloat() / LocalConfiguration.current.screenHeightDp.toFloat()) > 1.7f
    val spacing = if (isWideScreen) 4.dp else 4.dp // Reduced vertical padding

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = spacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (isWideScreen) Arrangement.spacedBy(4.dp) else Arrangement.spacedBy(4.dp) // Reduced from 16.dp to 4.dp (75% reduction)
    ) {
        // Cluster 1: BPM and Swing
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (isWideScreen) 4.dp else 2.dp)) { // Reduced from 8.dp to 2.dp
            Knob("BPM", (latestState.tempo - 12f) / 228f, -1, latestState, latestOnStateChange, nativeLib, knobSize = if (isWideScreen) 40.dp else 56.dp, 
                onValueChangeOverride = {
                    val newBpm = 12f + (it * 228f)
                    latestOnStateChange(latestState.copy(tempo = newBpm))
                    nativeLib.setTempo(newBpm)
                },
                valueFormatter = {
                    val bpm = 12f + (it * 228f)
                    "BPM: ${bpm.toInt()}"
                }
            )
            
            var tapTimes by remember { mutableStateOf(listOf<Long>()) }
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val newTaps = tapTimes.filter { now - it < 2000 } + now
                    if (newTaps.size >= 2) {
                        val intervals = newTaps.zipWithNext { a, b -> b - a }
                        val avgInterval = intervals.average()
                        val newBpm = (60000f / avgInterval.toFloat()).coerceIn(12f, 240f)
                        latestOnStateChange(latestState.copy(tempo = newBpm))
                        nativeLib.setTempo(newBpm)
                    }
                    tapTimes = newTaps
                },
                modifier = Modifier.height(24.dp).width(50.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("Tap${latestState.tempo.toInt()}", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White)
            }
            // Removed redundant BPM Text
            
            // Removed Spacer
            
            Knob("SWNG", latestState.swing, -1, latestState, latestOnStateChange, nativeLib, knobSize = if (isWideScreen) 40.dp else 56.dp, onValueChangeOverride = {
                latestOnStateChange(latestState.copy(swing = it))
                nativeLib.setSwing(it)
            })
            if (!isWideScreen) Text("${(latestState.swing * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)

            // Removed Spacer


        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 2: Play, Record, Stop
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Record Button
            Box(
                modifier = Modifier
                    .size(if (isWideScreen) 34.dp else 44.dp)
                    .background(if (latestState.isRecording) Color.Red else Color.Red.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
                    .border(2.dp, Color.Red, RoundedCornerShape(22.dp))
                    .clickable {
                        val current = latestState
                        val newRec = !current.isRecording
                        latestOnStateChange(current.copy(isRecording = newRec, isPlaying = if (newRec) true else current.isPlaying))
                        nativeLib.setIsRecording(newRec)
                        if (newRec) nativeLib.setPlaying(true)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (latestState.isRecording) {
                    Box(modifier = Modifier.size(if (isWideScreen) 10.dp else 14.dp).background(Color.White, RoundedCornerShape(6.dp)))
                } else {
                    Box(modifier = Modifier.size(if (isWideScreen) 10.dp else 14.dp).background(Color.Red, RoundedCornerShape(6.dp)))
                }
            }

            // Play Button
            IconButton(
                onClick = { 
                    val current = latestState
                    val newPlaying = !current.isPlaying
                    latestOnStateChange(current.copy(isPlaying = newPlaying))
                    nativeLib.setPlaying(newPlaying)
                },
                modifier = Modifier.size(if (isWideScreen) 34.dp else 44.dp).background(if (latestState.isPlaying) Color.Green.copy(alpha = 0.2f) else Color.DarkGray, RoundedCornerShape(22.dp))
            ) {
                Icon(
                    Icons.Filled.PlayArrow, 
                    contentDescription = "Play", 
                    tint = if (latestState.isPlaying) Color.Green else Color.White,
                    modifier = Modifier.size(if (isWideScreen) 18.dp else 24.dp)
                )
            }
            // Stop Button
            var stopFlash by remember { mutableStateOf(false) }
            val stopColor by animateColorAsState(if (stopFlash) Color.Red else Color.Gray)
            
            Box(
                modifier = Modifier
                    .size(if (isWideScreen) 34.dp else 44.dp)
                    .background(stopColor, RoundedCornerShape(22.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            stopFlash = true
                            val current = latestState
                            latestOnStateChange(current.copy(isPlaying = false, isRecording = false))
                            nativeLib.setPlaying(false)
                            nativeLib.setIsRecording(false)
                            nativeLib.panic()
                            try { awaitRelease() } finally { stopFlash = false }
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.size(if (isWideScreen) 14.dp else 18.dp).background(Color.White, RoundedCornerShape(2.dp)))
            }
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 3: Master Volume
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Knob(if (isWideScreen) "VOL" else "MASTER", latestState.masterVolume, -1, latestState, latestOnStateChange, nativeLib, knobSize = if (isWideScreen) 36.dp else 48.dp, onValueChangeOverride = {
                latestOnStateChange(latestState.copy(masterVolume = it))
                nativeLib.setMasterVolume(it)
            })
            if (!isWideScreen) Text("VOLUME", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 8.sp)
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 4.dp))

        // Cluster 4: Playback Order (Consolidated)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            val buttonModifier = Modifier.size(if (isWideScreen) 34.dp else 46.dp, 30.dp)
            val buttonShape = RoundedCornerShape(8.dp)
            
            // Determine current state for display
            val (label, color) = when {
                latestState.isRandomOrder -> "RND" to Color.Magenta
                latestState.playbackDirection == 2 -> "PNG" to Color.Cyan
                latestState.playbackDirection == 1 -> "REV" to Color.Red
                else -> "REG" to Color.Gray
            }
            
            Button(
                onClick = {
                    val current = latestState
                    // Cycle: REG -> REV -> PNG -> RND -> REG
                    if (!current.isRandomOrder) {
                        when (current.playbackDirection) {
                            0 -> { // REG -> REV
                                latestOnStateChange(current.copy(playbackDirection = 1))
                                nativeLib.setPlaybackDirection(current.selectedTrackIndex, 1)
                            }
                            1 -> { // REV -> PNG
                                latestOnStateChange(current.copy(playbackDirection = 2))
                                nativeLib.setPlaybackDirection(current.selectedTrackIndex, 2)
                            }
                            2 -> { // PNG -> RND
                                latestOnStateChange(current.copy(playbackDirection = 0, isRandomOrder = true))
                                nativeLib.setPlaybackDirection(current.selectedTrackIndex, 0)
                                nativeLib.setIsRandomOrder(current.selectedTrackIndex, true)
                            }
                        }
                    } else {
                        // RND -> REG
                        latestOnStateChange(current.copy(isRandomOrder = false, playbackDirection = 0))
                        nativeLib.setIsRandomOrder(current.selectedTrackIndex, false)
                        nativeLib.setPlaybackDirection(current.selectedTrackIndex, 0)
                    }
                },
                modifier = buttonModifier,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = color),
                shape = buttonShape
            ) { 
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) 
            }
            
            Text("ORDER", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 8.sp)
        }

    }
}







@Composable
fun GrooveboxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFBB86FC),
            secondary = Color(0xFF03DAC6),
            background = Color(0xFF121212),
            surface = Color(0xFF121212)
        ),
// End of GrooveboxTheme
        content = content
    )
}


