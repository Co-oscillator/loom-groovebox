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
import android.os.Environment
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

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
import kotlin.math.abs
import java.io.File

val LocalFocusedValue = staticCompositionLocalOf<(String?) -> Unit> { {} }

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
        nativeLib.setTrackActive(trackIdx, t.isActive)
        nativeLib.setTrackPan(trackIdx, t.pan) // Re-added Pan sync
        
        t.parameters.forEach { (pid, v) -> 
            val safeVal = if (v.isNaN() || v.isInfinite()) 0.0f else v.coerceIn(0f, 1f)
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
}


fun isBlackKey(midiNote: Int): Boolean {
    val noteInOctave = midiNote % 12
    return noteInOctave == 1 || noteInOctave == 3 || noteInOctave == 6 || noteInOctave == 8 || noteInOctave == 10
}

fun getNoteLabel(midiNote: Int): String {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[midiNote % 12] + (midiNote / 12 - 1)
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

fun getEngineColor(type: EngineType): Color = when (type) {
    EngineType.SUBTRACTIVE -> Color.Cyan
    EngineType.FM -> Color(0xFF00FF00)
    EngineType.SAMPLER -> Color(0xFFFFD700) // Gold
    EngineType.GRANULAR -> Color.Magenta
    EngineType.WAVETABLE -> Color.Blue
    EngineType.FM_DRUM -> Color.Red
    EngineType.ANALOG_DRUM -> Color(0xFFCDDC39) // Lime
    EngineType.MIDI -> Color.Gray
    EngineType.AUDIO_IN -> Color(0xFF4B0082) // Eggplant
    EngineType.SOUNDFONT -> Color(0xFF87CEEB) // Sky Blue
}

class MainActivity : ComponentActivity() {
    private val nativeLib = NativeLib()
    private lateinit var midiManager: MidiManager
    private lateinit var midiRouter: MidiRouter
    private lateinit var empledManager: EmpledManager
    private var grooveboxState by mutableStateOf(createInitialState())

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
        
        // Request recording and storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivityForResult(intent, 2296)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, 2296)
                }
            }
        } else {
        val perms = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val permissionsToRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1001)
        }
    }
        
        // Migrate and copy assets (PersistenceManager now handles try-catch and fallbacks)
        try {
            PersistenceManager.migrateToExternalStorage(this)
            PersistenceManager.copyWavetablesToFilesDir(this)
            PersistenceManager.copySoundFontsToFilesDir(this)
        } catch (e: Exception) {
            Log.e("Groovebox", "Persistence startup error: ${e.message}")
        }

        
        // Load Init project as priority template if it exists, otherwise last session
        var initProject = PersistenceManager.loadProject(this, "Init.gbx")
        val lastSession = PersistenceManager.loadProject(this, "last_session.gbx")
        
        if (initProject == null) {
            // Create a fresh Init.gbx if missing
            val freshState = createInitialState()
            PersistenceManager.saveProject(this, freshState, "Init.gbx")
            initProject = freshState
            Log.d("Groovebox", "Created new Init.gbx default template")
        }

        grooveboxState = initProject ?: lastSession ?: createInitialState()
        Log.d("Groovebox", "Started with ${if (initProject != null) "Init.gbx" else if (lastSession != null) "last_session.gbx" else "Clean State"}")
        
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
                    MainScreen(empledManager, nativeLib, grooveboxState, midiManager) { grooveboxState = it }
                    
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

    override fun onDestroy() {
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
                painter = painterResource(id = R.drawable.ic_icon),
                contentDescription = "Loom Icon",
                modifier = Modifier
                    .size(280.dp)
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
fun MainScreen(empledManager: EmpledManager, nativeLib: NativeLib, state: GrooveboxState, midiManager: MidiManager, onStateChange: (GrooveboxState) -> Unit) {
    
    var localFocusedValue by remember { mutableStateOf<String?>(null) }
    
    CompositionLocalProvider(LocalFocusedValue provides { localFocusedValue = it }) {
        // Wrap children in provider
    
    var cpuLoad by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (true) {
                cpuLoad = nativeLib.getCpuLoad()
                delay(500)
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
            
            track.copy(parameters = newParams, steps = newSteps)
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
    
    val context = LocalContext.current
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
                        val channel = event[1].toInt() // Track Index (0-7) or MIDI Channel
                        val data1 = event[2]
                        val data2 = event[3]
                        
                        // Construct MIDI Message (3 bytes: status, data1, data2)
                        val status = (type and 0xF0) or (channel and 0x0F)
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
                                            if (nativeActive != s.active) {
                                                drumChanged = true
                                                s.copy(active = nativeActive)
                                            } else s
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
                                    if (nativeActive != s.active) {
                                        trackChanged = true
                                        s.copy(active = nativeActive)
                                    } else s
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
        
        val newLength = if (maxUsedStep < 16) 16
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
            SidebarMixer(state, onStateChange, nativeLib)

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
                1 -> ParametersScreen(state, state.selectedTrackIndex, onStateChange, nativeLib)
                2 -> SequencingScreen(state, onStateChange, nativeLib, empledManager)
                3 -> EffectsScreen(state, onStateChange, nativeLib)
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
            displayValue?.let { valStr ->
                Text(
                    text = valStr,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd) // Moved to Top
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
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
fun SidebarMixer(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val engineTypes = remember { EngineType.values() }

    Column(
        modifier = Modifier
            .width(180.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        state.tracks.forEachIndexed { i, track ->
            val isSelected = state.selectedTrackIndex == i
            val engineColor = getEngineColor(track.engineType)
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 1.dp)
                    .background(
                        if (isSelected) engineColor.copy(alpha = 0.2f) 
                        else Color.White.copy(alpha = 0.05f),
                        RoundedCornerShape(6.dp)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 0.5.dp,
                        color = if (isSelected) engineColor else Color.Gray.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .pointerInput(i) {
                        detectTapGestures(
                            onTap = { latestOnStateChange(latestState.copy(selectedTrackIndex = i)) }
                        )
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Engine Selection Knob
                    Knob(
                        label = "ENG", 
                        initialValue = (track.engineType.ordinal.toFloat() / (engineTypes.size - 1)),
                        parameterId = -2,
                        state = state,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib,
                        knobSize = 30.dp,
                        overrideValue = (track.engineType.ordinal.toFloat() / (engineTypes.size - 1)),
                        overrideColor = engineColor,
                        isBold = true,
                        showValue = false,
                        valueFormatter = { v ->
                            val engineIdx = (v * (engineTypes.size - 1).toFloat() + 0.5f).toInt().coerceIn(0, engineTypes.size - 1)
                            engineTypes[engineIdx].name.replace("_", " ")
                        },
                        onValueChangeOverride = { newVal ->
                            val engineIdx = (newVal * (engineTypes.size - 1).toFloat() + 0.5f).toInt().coerceIn(0, engineTypes.size - 1)
                            val newType = engineTypes[engineIdx]
                            if (newType != latestState.tracks[i].engineType) {
                                // Explicitly clear native sequencers when engine type changes
                                // to prevent "garbage" patterns from leaking between engines.
                                nativeLib.clearSequencer(i)
                                
                                val newTracks = latestState.tracks.toMutableList()
                                newTracks[i] = newTracks[i].copy(engineType = newType)
                                latestOnStateChange(latestState.copy(
                                    tracks = newTracks,
                                    focusedValue = "ENGINE: ${newType.name.replace("_", " ")}"
                                ))
                                nativeLib.setEngineType(i, engineIdx)
                            }
                        }
                    )

                    // Engine Icon (Center)
                    EngineIcon(
                        type = track.engineType,
                        modifier = Modifier.size(32.dp),
                        color = if (isSelected) engineColor else Color.White.copy(alpha = 0.8f)
                    )

                    // Volume Knob
                    Knob(
                        label = "VOL", 
                        initialValue = 0.8f,
                        parameterId = -1,
                        state = state,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib,
                        knobSize = 30.dp,
                        overrideValue = track.volume,
                        overrideColor = engineColor,
                        isBold = true,
                        showValue = false,
                        onValueChangeOverride = { newVal ->
                            val newTracks = latestState.tracks.toMutableList()
                            newTracks[i] = newTracks[i].copy(volume = newVal)
                            latestOnStateChange(latestState.copy(
                                tracks = newTracks,
                                focusedValue = "CH ${i+1} VOL: ${(newVal * 100).toInt()}%"
                            ))
                            nativeLib.setTrackVolume(i, newVal)
                        }
                    )

                    // Pan Knob (NEW)
                    Knob(
                        label = "PAN",
                        initialValue = 0.5f,
                        parameterId = 9, // Native ID for Track Pan
                        state = state,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib,
                        knobSize = 30.dp,
                        overrideValue = track.pan,
                        overrideColor = engineColor,
                        isBold = true,
                        showValue = false,
                        onValueChangeOverride = { newVal ->
                            val newTracks = latestState.tracks.toMutableList()
                            newTracks[i] = newTracks[i].copy(pan = newVal)
                            latestOnStateChange(latestState.copy(
                                tracks = newTracks,
                                focusedValue = "CH ${i+1} PAN: ${if(newVal < 0.45f) "L" else if(newVal > 0.55f) "R" else "C"} ${(newVal * 100).toInt()}"
                            ))
                            nativeLib.setParameter(i, 9, newVal)
                        }
                    )
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // Thin Separator for Footer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(if (isSelected) engineColor.copy(alpha = 0.5f) else Color.Gray.copy(alpha = 0.3f))
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                // Track Info Footer (Single row to save space)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CH ${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) engineColor else Color.Gray,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = track.engineType.name.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) engineColor else Color.Gray.copy(alpha = 0.8f),
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
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

@Composable
fun SettingsScreen(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, midiManager: MidiManager? = null) {
    val context = LocalContext.current
    var mappingStripIndex by remember { mutableStateOf(-1) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        
        // MIDI STATUS OVERLAY
        if (midiManager != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                border = BorderStroke(1.dp, Color.DarkGray)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("MIDI CONNECTION STATUS", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Device: ${midiManager.deviceName.value}",
                        color = Color.Yellow,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recent Activity:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    Text(
                        text = midiManager.midiLog.value.takeLast(500), // Show more history in settings
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(8.dp)
                            .height(100.dp) // Fixed height scrollable area effectively
                    )
                }
            }
        }
        Button(
            onClick = {
                // PANIC / RESET AUDIO ENGINE
                nativeLib.stop()
                // NUCLEAR OPTION: Destroy and recreate engine to clear bad internal state (NaNs)
                nativeLib.init() 
                nativeLib.setAppDataDir(context.filesDir.absolutePath)
                // nativeLib.loadAppState() - Removed: better to sync directly from Kotlin state
                
                // Force re-sync of Kotlin state BEFORE starting audio
                syncNativeState(state, nativeLib)
                nativeLib.start()
                Toast.makeText(context, "Audio Engine Reset", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha=0.7f))
        ) {
            Text("RESET AUDIO ENGINE (PANIC)", color = Color.White, fontWeight = FontWeight.Bold)
        }

        var showCreditsDialog by remember { mutableStateOf(false) }
        Button(
            onClick = { showCreditsDialog = true },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Magenta.copy(alpha=0.3f)),
            border = BorderStroke(1.dp, Color.Magenta.copy(alpha=0.5f))
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.Magenta)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SOUNDFONT CREDITS & ATTRIBUTION", color = Color.Magenta, fontWeight = FontWeight.SemiBold)
        }

        if (showCreditsDialog) {
            SoundFontCreditsDialog(onDismiss = { showCreditsDialog = false })
        }

        // Project Management Cluster
        Text("Project", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        val scope = rememberCoroutineScope()
        
        var showSaveDialog by remember { mutableStateOf(false) }
        var showLoadDialog by remember { mutableStateOf(false) }
        var showExportDialog by remember { mutableStateOf(false) }

        if (showSaveDialog) {
            var fileName by remember { mutableStateOf("") }
            val existingProjects = remember { PersistenceManager.listProjects(context).sorted() }
            
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save Project") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("Project Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Existing Projects:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Box(modifier = Modifier.heightIn(max = 200.dp).fillMaxWidth().border(1.dp, Color.Gray.copy(alpha=0.3f), RoundedCornerShape(4.dp))) {
                             LazyColumn(modifier = Modifier.padding(8.dp)) {
                                 items(existingProjects) { name ->
                                     Text(
                                         name, 
                                         modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { fileName = name.removeSuffix(".gbx") } // Auto-fill name without extension
                                            .padding(vertical = 8.dp),
                                         style = MaterialTheme.typography.bodySmall,
                                         color = Color.LightGray
                                     )
                                     Divider(color = Color.DarkGray.copy(alpha = 0.5f))
                                 }
                             }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (fileName.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    PersistenceManager.saveProject(context, state, fileName)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Project Saved: $fileName", Toast.LENGTH_SHORT).show()
                                        showSaveDialog = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Save Failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }) { Text("SAVE") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("CANCEL") } }
            )
        }

        if (showLoadDialog) {
            val projects = remember { PersistenceManager.listProjects(context).sorted() }
            AlertDialog(
                onDismissRequest = { showLoadDialog = false },
                title = { Text("Load Project") },
                text = {
                    if (projects.isEmpty()) {
                        Text("No projects found in Projects folder.")
                    } else {
                        Box(modifier = Modifier.heightIn(max = 300.dp)) {
                            val listState = rememberLazyListState()
                            
                            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                                items(projects) { name ->
                                    var showMenu by remember { mutableStateOf(false) }
                                    
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = {
                                                        scope.launch(Dispatchers.IO) {
                                                            val loaded = PersistenceManager.loadProject(context, name)
                                                            withContext(Dispatchers.Main) {
                                                                if (loaded != null) {
                                                                    nativeLib.stop()
                                                                    nativeLib.init()
                                                                    nativeLib.setAppDataDir(context.filesDir.absolutePath)
                                                                    val sanitized = sanitizeGrooveboxState(loaded)
                                                                    onStateChange(sanitized)
                                                                    // Sync to native IMMEDIATELY
                                                                    syncNativeState(sanitized, nativeLib)
                                                                    nativeLib.start()
                                                                    Toast.makeText(context, "Loaded: $name", Toast.LENGTH_SHORT).show()
                                                                    showLoadDialog = false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    onLongClick = { showMenu = true }
                                                )
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Menu, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text(name, color = Color.White)
                                        }
                                        Divider(color = Color.DarkGray, modifier = Modifier.align(Alignment.BottomCenter))

                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Rename") },
                                                onClick = {
                                                    // Rename logic
                                                    showMenu = false
                                                    // Trigger Rename Dialog (Nested state needed or simple prompt)
                                                    // For simplicity in this iteration, we'll implement Copy/Delete first as requested priority
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Copy") },
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        PersistenceManager.copyProject(context, name)
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(context, "Copied to ${name.removeSuffix(".gbx")}_copy.gbx", Toast.LENGTH_SHORT).show()
                                                            showMenu = false
                                                            showLoadDialog = false // Force refresh on re-open
                                                        }
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Delete", color = Color.Red) },
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        PersistenceManager.deleteProject(context, name)
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(context, "Deleted $name", Toast.LENGTH_SHORT).show()
                                                            showMenu = false
                                                            showLoadDialog = false // Force refresh
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Scrollbar hint (Simple Visual Indicator)
                            if (listState.canScrollForward || listState.canScrollBackward) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(4.dp)
                                        .background(Color.White.copy(alpha = 0.1f))
                                ) {
                                    val totalItems = projects.size
                                    val visibleItems = listState.layoutInfo.visibleItemsInfo.size
                                    if (totalItems > 0 && totalItems > visibleItems) {
                                         val scrollRatio = listState.firstVisibleItemIndex.toFloat() / totalItems
                                         val heightRatio = visibleItems.toFloat() / totalItems
                                         
                                         // Dynamic Scroll Handle
                                         Box(
                                             modifier = Modifier
                                                 .fillMaxWidth()
                                                 .fillMaxHeight(fraction = heightRatio.coerceIn(0.1f, 1f))
                                                 .align(Alignment.TopCenter)
                                                 // NOTE: Exact positioning in Compose requires constraints or custom layout. 
                                                 // For a reliable "discreet" indicator without math hell, we use a simple alignment trick:
                                                 // Since we can't easily offset by percentage without `BiasAlignment`, let's just show a static bar 
                                                 // to indicate "Scrolling is possible" or use a weighted column.
                                         )
                                         // Better: Standard Scrollbar logic is hard to inline. 
                                         // User asked for "discreet scroll indicator".
                                    }
                                }
                                // Simple Top/Bottom shadow indicators
                                if (listState.canScrollBackward) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(16.dp).align(Alignment.TopCenter).background(
                                            brush = Brush.verticalGradient(colors = listOf(Color.Black, Color.Transparent))
                                        )
                                    )
                                }
                                if (listState.canScrollForward) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(16.dp).align(Alignment.BottomCenter).background(
                                            brush = Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black))
                                        )
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showLoadDialog = false }) { Text("CANCEL") } }
            )
        }

        if (showExportDialog) {
            var loops by remember { mutableStateOf("1") }
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("Export Audio") },
                text = {
                    Column {
                        Text("Number of sequence repeats (loops):")
                        OutlinedTextField(
                            value = loops,
                            onValueChange = { loops = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val numLoops = loops.toIntOrNull() ?: 1
                        scope.launch(Dispatchers.IO) {
                            try {
                                val exportPath = File(context.getExternalFilesDir(null), "export.wav").absolutePath
                                nativeLib.exportAudio(numLoops, exportPath)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Exported $numLoops loops to: $exportPath", Toast.LENGTH_LONG).show()
                                    showExportDialog = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Export Failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }) { Text("EXPORT") }
                },
                dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("CANCEL") } }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showSaveDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("SAVE")
            }
            Button(
                onClick = { showLoadDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("LOAD")
            }
            Button(
                onClick = { showExportDialog = true },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6A1B9A))
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("EXPORT")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                PersistenceManager.clearAssignments(context)
                val newState = state.copy(
                    engineTypeStripAssignments = emptyMap(), 
                    engineTypeKnobAssignments = emptyMap(),
                    // Immediately reset current routings to default
                    stripRoutings = List(4) { i -> StripRouting(stripIndex = i) },
                    knobRoutings = List(4) { i -> StripRouting(stripIndex = i + 4, parameterName = "KNOB ${i + 1}") },
                    lfos = List(6) { LfoState() },
                    macros = List(6) { i -> MacroState(label="Macro ${i+1}") },
                    routingConnections = emptyList(),
                    fxChainSlots = List(5) { -1 }
                )
                onStateChange(newState)
                // Sync native state
                scope.launch(Dispatchers.IO) {
                    syncNativeState(newState, nativeLib)
                    // Explicitly stop all FX if slots are cleared
                    (0 until 5).forEach { slot -> nativeLib.setFxChain(slot, -1) }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.6f))
        ) {
            Text("RESET ALL PATCHING & MIDI")
        }
        Text("Clears all MIDI assignments, LFOs, Macros, Routings and Pedal arrangements.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Text("Assignable Controls (MIDI Learn)", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Touch Strips Cluster
            Column(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text("TOUCH STRIPS", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
                Spacer(modifier = Modifier.height(4.dp))
                state.stripRoutings.forEachIndexed { index, routing ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("S${index + 1}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.LightGray)
                            Text(routing.parameterName.take(12), style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (mappingStripIndex == index) Color.Yellow else Color.DarkGray, RoundedCornerShape(4.dp))
                                .clickable { mappingStripIndex = if (mappingStripIndex == index) -1 else index },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (mappingStripIndex == index) "..." else "SET", color = if (mappingStripIndex == index) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Knobs Cluster
            Column(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text("MACRO KNOBS", style = MaterialTheme.typography.labelSmall, color = Color.Magenta)
                Spacer(modifier = Modifier.height(4.dp))
                state.knobRoutings.forEachIndexed { index, routing ->
                    val globalIndex = index + 4
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("K${index + 1}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.LightGray)
                            Text(routing.parameterName.take(12), style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 1)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (mappingStripIndex == globalIndex) Color.Yellow else Color.DarkGray, RoundedCornerShape(4.dp))
                                .clickable { mappingStripIndex = if (mappingStripIndex == globalIndex) -1 else globalIndex },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (mappingStripIndex == globalIndex) "..." else "SET", color = if (mappingStripIndex == globalIndex) Color.Black else Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        
        if (mappingStripIndex != -1) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Simulate Parameter Selection (Click one):", style = MaterialTheme.typography.labelMedium)
            val params = listOf("Volume T1", "Volume T2", "Filter Cutoff T1", "Reverb Wet", "Delay Feedback")
            params.forEach { param ->
                OutlinedButton(
                    onClick = {
                        if (mappingStripIndex < 4) {
                            val newRoutings = state.stripRoutings.toMutableList()
                            newRoutings[mappingStripIndex] = newRoutings[mappingStripIndex].copy(parameterName = param, targetType = 1, targetId = 100) // Mock ID
                            onStateChange(state.copy(stripRoutings = newRoutings))
                        } else {
                            val knobIdx = mappingStripIndex - 4
                            val newRoutings = state.knobRoutings.toMutableList()
                            newRoutings[knobIdx] = newRoutings[knobIdx].copy(parameterName = param, targetType = 1, targetId = 100) // Mock ID
                            onStateChange(state.copy(knobRoutings = newRoutings))
                        }
                        mappingStripIndex = -1
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(param)
                }
            }
        }
    }
}

@Composable
fun SoundFontCreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SoundFont Attributions", color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Loom includes a curated 'Starter Pack' of SoundFonts. We thank the following creators for their incredible work:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(16.dp))
                
                CreditItem("GeneralUser GS", "S. Christian Collins", "GNU GPL v2", "A versatile GM/GS soundset.")
                CreditItem("MuseScore MS Basic", "MuseScore Team", "MIT / GPL", "High-quality symphonic and general bank.")
                CreditItem("FreePats Project", "FreePats Community", "GPL / CC-BY-SA", "Community-driven free instrument patches.")
                
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
fun ParametersScreen(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val track = state.tracks[trackIndex]
    val engineColor = getEngineColor(track.engineType)
    
    var refreshTrigger by remember { mutableStateOf(0) }
    
    // Sync UI with Native Engine state when screen loads or refresh requested
    androidx.compose.runtime.LaunchedEffect(trackIndex, refreshTrigger) {
        val nativeParams = nativeLib.getAllTrackParameters(trackIndex)
        val updatedParams = track.parameters.toMutableMap()
        nativeParams.forEachIndexed { idx, value ->
            updatedParams[idx] = value
        }
        val currentTrack = state.tracks[trackIndex]
        val updatedTrack = currentTrack.copy(parameters = updatedParams)
        val updatedTracks = state.tracks.toMutableList()
        updatedTracks[trackIndex] = updatedTrack
        onStateChange(state.copy(tracks = updatedTracks))
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        // Redesigned Header Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
                .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "TRACK ${trackIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Cyan.copy(alpha = 0.8f),
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "PARAMETERS",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Compact Buttons in center of glassy header
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TestTriggerButton(trackIndex, nativeLib, engineColor, state)
                    RandomizeButton(trackIndex, state, onStateChange, nativeLib, engineColor)
                    
                    // PATCH RESTORE (Global)
                    Button(
                        onClick = {
                            nativeLib.restoreTrackPreset(state.selectedTrackIndex)
                            // Re-sync parameter states from native to UI
                            val current = state
                            val updatedTracks = current.tracks.mapIndexed { idx, track ->
                                val nativeParamsArray = nativeLib.getAllTrackParameters(idx)
                                val updatedParams = track.parameters.toMutableMap()
                                nativeParamsArray.forEachIndexed { pIdx, v -> updatedParams[pIdx] = v }
                                track.copy(parameters = updatedParams, isActive = true) // Force Active on Restore
                            }
                            onStateChange(current.copy(tracks = updatedTracks))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = engineColor.copy(alpha = 0.2f)),
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, engineColor.copy(alpha = 0.5f))
                    ) {
                        Text("RESTORE PATCH", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = engineColor)
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.engineType.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = engineColor,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    
                    Button(
                        onClick = { 
                            if (state.midiLearnActive) {
                                onStateChange(state.copy(midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null))
                            } else {
                                onStateChange(state.copy(midiLearnActive = true, midiLearnStep = 1))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.midiLearnActive) Color.Yellow else Color.White.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (state.midiLearnActive) "LEARNING..." else "MIDI LEARN",
                            color = if (state.midiLearnActive) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        when (track.engineType) {
            EngineType.SUBTRACTIVE -> SubtractiveParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.FM -> FmParameters(state, trackIndex, onStateChange, nativeLib, onRefresh = { refreshTrigger++ })
            EngineType.FM_DRUM -> FmDrumParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.SAMPLER -> SamplerParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.GRANULAR -> GranularParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.WAVETABLE -> WavetableParameters(state, trackIndex, onStateChange, nativeLib, onRefresh = { refreshTrigger++ })
            EngineType.ANALOG_DRUM -> AnalogDrumParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.MIDI -> MidiParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.AUDIO_IN -> AudioInParameters(state, trackIndex, onStateChange, nativeLib)
            EngineType.SOUNDFONT -> SoundFontParameters(state, trackIndex, onStateChange, nativeLib, onRefresh = { refreshTrigger++ })
            else -> Text("Engine under development")
        }
    }
}

@Composable
fun AnalogDrumParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val drumColor = getEngineColor(EngineType.ANALOG_DRUM)
    
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val colModifier = Modifier.weight(1f).fillMaxHeight()
        val knobSize = 50.dp // Increased size
        
        // Kick
        AnalogDrumColumn("KICK", trackIndex, 0, 60, nativeLib, drumColor, state, onStateChange, colModifier,
            icon = { color -> Canvas(Modifier.size(32.dp)) { drawCircle(color = color) } }
        ) {
             Knob("Dcy", 0.5f, 600, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Tone", 0.8f, 601, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Tune", 0.25f, 602, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Gain", 0.8f, 605, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Snare
        AnalogDrumColumn("SNARE", trackIndex, 1, 61, nativeLib, drumColor, state, onStateChange, colModifier,
            icon = { color -> Canvas(Modifier.size(32.dp)) { drawRect(color = color) } }
        ) {
             Knob("Dcy", 0.2f, 610, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Snap", 0.4f, 613, state, onStateChange, nativeLib, knobSize = knobSize) // Changed from 611 to 613 for ParamA
             Knob("Tune", 0.33f, 612, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Gain", 0.8f, 615, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Cymbal
        AnalogDrumColumn("CYMBAL", trackIndex, 2, 62, nativeLib, drumColor, state, onStateChange, colModifier,
            icon = { color -> 
                Canvas(Modifier.size(32.dp)) { 
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width/2, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(path, color) 
                } 
            }
        ) {
             Knob("Dcy", 0.3f, 620, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Col", 0.0f, 621, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Tune", 0.5f, 622, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Gain", 0.8f, 625, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Hat Closed
        AnalogDrumColumn("HAT C", trackIndex, 3, 63, nativeLib, drumColor, state, onStateChange, colModifier,
            icon = { color -> 
                 Canvas(Modifier.size(32.dp)) { 
                     drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, size.height/2), end = androidx.compose.ui.geometry.Offset(size.width, size.height/2), strokeWidth = 5f)
                     drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width/2, 0f), end = androidx.compose.ui.geometry.Offset(size.width/2, size.height), strokeWidth = 5f)
                 } 
            }
        ) {
             Knob("Dcy", 0.1f, 630, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Col", 0.8f, 631, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Gain", 0.8f, 635, state, onStateChange, nativeLib, knobSize = knobSize)
        }
        
        // Hat Open
        AnalogDrumColumn("HAT O", trackIndex, 4, 64, nativeLib, drumColor, state, onStateChange, colModifier,
            icon = { color -> 
                 Canvas(Modifier.size(32.dp)) { 
                      drawCircle(color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f))
                 } 
            }
        ) {
             Knob("Dcy", 0.4f, 640, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Col", 0.8f, 641, state, onStateChange, nativeLib, knobSize = knobSize)
             Knob("Gain", 0.8f, 645, state, onStateChange, nativeLib, knobSize = knobSize)
        }
    }
}

@Composable
fun AnalogDrumColumn(
    label: String, 
    trackIndex: Int, 
    drumIndex: Int,
    note: Int, 
    nativeLib: NativeLib, 
    color: Color, 
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit, 
    content: @Composable ColumnScope.() -> Unit
) {
    val hotPink = Color(0xFFFF69B4)
    val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == drumIndex

    Column(
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly 
    ) {
        // Test Button (Icon + Label)
        var isPressed by remember { mutableStateOf(false) }
        val currentColor = if (isPressed) Color.White else color
        
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isPressed) color.copy(alpha=0.5f) else Color.Transparent)
                .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                .pointerInput(state.isSelectingSidechain) {
                    detectTapGestures(
                        onPress = {
                            if (!state.isSelectingSidechain) {
                                try {
                                    isPressed = true
                                    nativeLib.triggerNote(trackIndex, note, 100)
                                    awaitRelease()
                                } finally {
                                    isPressed = false
                                    nativeLib.releaseNote(trackIndex, note)
                                }
                            }
                        },
                        onTap = {
                            if (state.isSelectingSidechain) {
                                nativeLib.setParameter(0, 585, trackIndex.toFloat())
                                nativeLib.setParameter(0, 586, drumIndex.toFloat())
                                onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = drumIndex, selectedTab = 3))
                            }
                        }
                    )
                }
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon(if (isSelectedForSidechain) hotPink else currentColor)
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else currentColor, maxLines = 1, fontWeight = FontWeight.Bold)
        }
        
        content()
    }
}

@Composable
fun WavetableParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRefresh: () -> Unit) {
    var showLoadDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val track = state.tracks[trackIndex]

    val wavetablesDir = File(PersistenceManager.getLoomFolder(context), "wavetables")
    if (!wavetablesDir.exists()) wavetablesDir.mkdirs()

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { input ->
                val fileName = "imported_${System.currentTimeMillis()}.wav"
                val dest = File(wavetablesDir, fileName)
                dest.outputStream().use { output -> input.copyTo(output) }
                nativeLib.loadWavetable(trackIndex, dest.absolutePath)
                onRefresh()
            }
        }
    }

    if (showLoadDialog) {
        NativeFileDialog(
            directory = wavetablesDir,
            onDismiss = { showLoadDialog = false },
            state = state,
            onFileSelected = { path ->
                if (path == "DEFAULT") {
                    nativeLib.loadDefaultWavetable(trackIndex)
                } else if (path == "IMPORT") {
                    launcher.launch("audio/*")
                } else {
                    nativeLib.loadWavetable(trackIndex, path)
                    val filename = File(path).name
                    val displayName = filename.removeSuffix(".WAV").removeSuffix(".wav")
                    val newTracks = state.tracks.mapIndexed { i, t ->
                        if (i == trackIndex) t.copy(activeWavetableName = displayName) else t
                    }
                    onStateChange(state.copy(tracks = newTracks))
                }
                onRefresh()
            },
            isSave = false,
            extraOptions = listOf("Default (Basic)" to "DEFAULT", "Import from Device..." to "IMPORT"),
            trackIndex = trackIndex,
            extensions = listOf("wav", "wt")
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        
        // Header with Load Button
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), 
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
             Text("WAVETABLE SOURCE", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
             
             Button(
                onClick = { showLoadDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.1f)),
                border = BorderStroke(1.dp, Color.Cyan.copy(alpha=0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp)
             ) {
                 Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Cyan)
                 Spacer(modifier = Modifier.width(8.dp))
                 Text(track.activeWavetableName ?: "Basic", color = Color.Cyan, style = MaterialTheme.typography.labelLarge)
             }
         }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // CHARACTER
            ParameterGroup("Character", modifier = Modifier.weight(1f), titleSize = 11) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("Morph", 0.0f, 450, state, onStateChange, nativeLib, knobSize = 44.dp)
                        Knob("Warp", 0.0f, 465, state, onStateChange, nativeLib, knobSize = 44.dp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("Crush", 0.0f, 466, state, onStateChange, nativeLib, knobSize = 44.dp)
                        Knob("Drive", 0.0f, 467, state, onStateChange, nativeLib, knobSize = 44.dp)
                    }
                }
            }

            // FILTER
            ParameterGroup("Filter", modifier = Modifier.weight(1f), titleSize = 11) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("MODE", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
                            val currentMode = (track.parameters[470] ?: 0.0f).toInt().coerceIn(0, 3)
                            Box(
                                modifier = Modifier
                                    .size(width = 32.dp, height = 24.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.DarkGray)
                                    .clickable {
                                        val nextMode = (currentMode + 1) % 4
                                        val newVal = nextMode.toFloat()
                                        nativeLib.setParameter(trackIndex, 470, newVal)
                                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(parameters = t.parameters + (470 to newVal)) else t }))
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(when(currentMode) { 0 -> "LP"; 1 -> "HP"; 2 -> "BP"; else -> "NT" }, fontSize = 9.sp, color = Color.White)
                            }
                        }
                        Knob("Cutoff", 0.5f, 458, state, onStateChange, nativeLib, knobSize = 44.dp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("Reso", 0.0f, 459, state, onStateChange, nativeLib, knobSize = 44.dp)
                        Knob("F.Amt", 0.0f, 464, state, onStateChange, nativeLib, knobSize = 44.dp)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // FILTER ENVELOPE
            ParameterGroup("Filter Env", modifier = Modifier.weight(1f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Atk", 0.01f, 471, state, onStateChange, nativeLib, knobSize = 38.dp)
                    Knob("Dcy", 0.1f, 461, state, onStateChange, nativeLib, knobSize = 38.dp)
                    Knob("Sus", 0.0f, 473, state, onStateChange, nativeLib, knobSize = 38.dp)
                    Knob("Rel", 0.5f, 474, state, onStateChange, nativeLib, knobSize = 38.dp)
                }
            }
            
            // AMP ENVELOPE
            ParameterGroup("Amp Env", modifier = Modifier.weight(1f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Atk", 0.01f, 454, state, onStateChange, nativeLib, knobSize = 38.dp)
                    Knob("Dcy", 0.1f, 455, state, onStateChange, nativeLib, knobSize = 38.dp)
                    Knob("Sus", 0.8f, 456, state, onStateChange, nativeLib, knobSize = 38.dp)
                    Knob("Rel", 0.5f, 457, state, onStateChange, nativeLib, knobSize = 38.dp)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // UNISON & LO-FI
            ParameterGroup("Unison & Lo-Fi", modifier = Modifier.weight(1f), titleSize = 11) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Detune", 0.0f, 451, state, onStateChange, nativeLib, knobSize = 40.dp)
                    Knob("Glide", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 40.dp)
                    Knob("Bits", 1.0f, 475, state, onStateChange, nativeLib, knobSize = 40.dp)
                    Knob("Srate", 0.0f, 476, state, onStateChange, nativeLib, knobSize = 40.dp)
                }
            }
        }
    }
}

@Composable
fun AudioInParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val track = state.tracks[trackIndex]
    
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("AUDIO IN ENGINE", style = MaterialTheme.typography.titleMedium, color = getEngineColor(EngineType.AUDIO_IN))
        Spacer(modifier = Modifier.height(8.dp))
        
        // Input Source Selection
        InputSourceSelector(nativeLib = nativeLib)
        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "GAIN", initialValue = 0.8f, parameterId = 121, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "FOLD", initialValue = 0.0f, parameterId = 122, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            
            // Filter Mode Toggle
            val currentMode = (track.parameters[123] ?: 0.0f).toInt().coerceIn(0, 2)
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("FILTER", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { 
                        val nextMode = (currentMode + 1) % 3
                        val newVal = nextMode.toFloat()
                        nativeLib.setParameter(trackIndex, 123, newVal)
                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(parameters = t.parameters + (123 to newVal)) else t }))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                    modifier = Modifier.height(36.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(when(currentMode) { 0 -> "LP"; 1 -> "HP"; else -> "BP" }, fontSize = 10.sp, color = Color.White)
                }
            }

            // TOGGLE: GATED vs OPEN
            val isGated = (track.parameters[120] ?: 1.0f) > 0.5f
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MODE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { 
                        val newVal = if (isGated) 0.0f else 1.0f
                        nativeLib.setParameter(trackIndex, 120, newVal)
                        onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(parameters = t.parameters + (120 to newVal)) else t }))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isGated) getEngineColor(EngineType.AUDIO_IN) else Color.DarkGray),
                    modifier = Modifier.height(36.dp).fillMaxWidth()
                ) {
                    Text(if (isGated) "GATED" else "OPEN", fontSize = 10.sp, color = if (isGated) Color.Black else Color.White)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "CUTOFF", initialValue = 0.5f, parameterId = 112, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "RESON", initialValue = 0.0f, parameterId = 113, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "ENV AMT", initialValue = 0.0f, parameterId = 118, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("AMP ENVELOPE", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "A", initialValue = 0.1f, parameterId = 100, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "D", initialValue = 0.5f, parameterId = 101, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "S", initialValue = 0.8f, parameterId = 102, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "R", initialValue = 0.2f, parameterId = 103, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("FILTER ENVELOPE", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "A", initialValue = 0.01f, parameterId = 114, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "D", initialValue = 0.1f, parameterId = 115, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "S", initialValue = 0.0f, parameterId = 116, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "R", initialValue = 0.5f, parameterId = 117, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
        }
    }
}

@Composable
fun InputSourceSelector(nativeLib: NativeLib) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager }
    
    var devices by remember { mutableStateOf<Array<android.media.AudioDeviceInfo>>(emptyArray()) }
    var selectedDeviceId by remember { mutableStateOf(0) } // 0: Auto/Default

    LaunchedEffect(audioManager) {
        audioManager?.let {
            try {
                devices = it.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
            } catch (e: Exception) {
                android.util.Log.e("AudioDevice", "Failed to get devices: ${e.message}")
            }
        }
    }

    fun getDeviceLabel(device: android.media.AudioDeviceInfo): String {
        // Special overrides for requested labels
        if (device.id == 18) return "both mics in mono"
        if (device.id == 12 || device.id == 13) return "right microphone"

        val typeLabel = when (device.type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "INT MIC"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "HEADSET"
            android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB HEADSET"
            android.media.AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE IN"
            android.media.AudioDeviceInfo.TYPE_LINE_DIGITAL -> "DIGITAL IN"
            android.media.AudioDeviceInfo.TYPE_BUS -> "BUS"
            else -> "OTHER (${device.type})"
        }
        return "$typeLabel [${device.id}]"
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("HARDWARE INPUT", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.weight(1f))
            Button(
                onClick = { 
                    audioManager?.let {
                        try {
                            devices = it.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                            android.util.Log.d("AudioDevice", "Rescanned. Found ${devices.size} inputs.")
                            android.widget.Toast.makeText(context, "Scanned: ${devices.size} input(s) found", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            android.util.Log.e("AudioDevice", "Failed to get devices on scan: ${e.message}")
                            android.widget.Toast.makeText(context, "Scan failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.height(24.dp),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) { Text("SCAN", fontSize = 8.sp, color = Color.White) }
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Button(
                    onClick = { 
                        selectedDeviceId = 0
                        nativeLib.setInputDevice(0)
                    },
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedDeviceId == 0) Color.Cyan else Color.DarkGray)
                ) { Text("DEFAULT", fontSize = 9.sp, color = if (selectedDeviceId == 0) Color.Black else Color.White) }
            }
            items(devices) { device ->
                Button(
                    onClick = { 
                        selectedDeviceId = device.id
                        nativeLib.setInputDevice(device.id)
                    },
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedDeviceId == device.id) Color.Cyan else Color.DarkGray)
                ) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(getDeviceLabel(device), fontSize = 9.sp, color = if (selectedDeviceId == device.id) Color.Black else Color.White)
                        val name = device.productName
                        if (name != null && name.isNotEmpty() && name.toString() != "TB351FU") {
                            Text(name.toString().take(10), fontSize = 7.sp, color = if (selectedDeviceId == device.id) Color.DarkGray else Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AutoPannerParameters(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("AUTO-PANNER FX", style = MaterialTheme.typography.titleMedium, color = Color.Yellow)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knob(label = "PAN", initialValue = 0.5f, parameterId = 2100, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "RATE", initialValue = 0.5f, parameterId = 2101, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "DEPTH", initialValue = 0.5f, parameterId = 2102, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
            Knob(label = "MIX", initialValue = 0.5f, parameterId = 2104, state = state, onStateChange = onStateChange, nativeLib = nativeLib)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        val shapes = listOf("SINE", "TRI", "SQR")
        val currentShapeValue = state.globalParameters[2103] ?: 0.0f
        val currentShapeIdx = (currentShapeValue * 2.0f + 0.5f).toInt().coerceIn(0, 2)
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("LFO SHAPE", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.width(80.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                shapes.forEachIndexed { i, label ->
                    Button(
                        onClick = {
                            val newVal = i / 2.0f
                            nativeLib.setParameter(-1, 2103, newVal) // -1 for global
                            onStateChange(state.copy(globalParameters = state.globalParameters + (2103 to newVal)))
                        },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (currentShapeIdx == i) Color.Yellow else Color.DarkGray)
                    ) {
                        Text(label, fontSize = 10.sp, color = if (currentShapeIdx == i) Color.Black else Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SubtractiveParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // TestTriggerButton removed (redundant)
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // OSC ENGINE (Square Cluster)
            ParameterGroup("Oscillators", modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("SHP 1", 0f, 104, state, onStateChange, nativeLib, knobSize = 48.dp)
                        Knob("SHP 2", 0f, 105, state, onStateChange, nativeLib, knobSize = 48.dp)
                        Knob("SUB SHP", 0f, 155, state, onStateChange, nativeLib, knobSize = 48.dp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("DETUNE", 0.1f, 106, state, onStateChange, nativeLib, knobSize = 48.dp)
                        Knob("CHORD", 0f, 111, state, onStateChange, nativeLib, knobSize = 48.dp, valueFormatter = { v ->
                            val idx = (v * 5.99f).toInt()
                            when(idx) {
                                0 -> "OFF"
                                1 -> "OCT"
                                2 -> "5TH"
                                3 -> "MAJ"
                                4 -> "MIN"
                                5 -> "SUS4"
                                else -> "OFF"
                            }
                        })
                    }
                }
            }

            // MIXER & TWEAK (Combined Cluster)
            ParameterGroup("Mixer & Tweak", modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("OSC 1", 0.8f, 107, state, onStateChange, nativeLib, knobSize = 36.dp)
                        Knob("OSC 2", 0.5f, 108, state, onStateChange, nativeLib, knobSize = 36.dp)
                        Knob("SUB", 0.6f, 109, state, onStateChange, nativeLib, knobSize = 36.dp)
                        Knob("NOISE", 0.1f, 110, state, onStateChange, nativeLib, knobSize = 36.dp)
                    }
                    Divider(color = Color.White.copy(alpha = 0.1f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ToggleIcon("Sync", 150, state, onStateChange, nativeLib)
                        ToggleIcon("Ring", 151, state, onStateChange, nativeLib)
                        Knob("FM", 0.0f, 152, state, onStateChange, nativeLib, knobSize = 36.dp)
                        Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 36.dp)
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
             // SOUND DESIGN (New Cluster)
             ParameterGroup("Osc Sound Design", modifier = Modifier.weight(1.5f)) {
                 Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                     Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             Text("OSC 1", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
                             Row {
                                 Knob("Pitch", 0.25f, 160, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("Drive", 0.0f, 170, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("Fold", 0.0f, 180, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("PW", 0.5f, 190, state, onStateChange, nativeLib, knobSize = 48.dp)
                             }
                         }
                         Box(
                            modifier = Modifier
                                .height(40.dp)
                                .width(1.dp)
                                .background(Color.White.copy(alpha = 0.2f))
                        )
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             Text("OSC 2", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
                             Row {
                                 Knob("Pitch", 0.25f, 161, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("Drive", 0.0f, 171, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("Fold", 0.0f, 181, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("PW", 0.5f, 191, state, onStateChange, nativeLib, knobSize = 48.dp)
                             }
                         }
                         Box(
                             modifier = Modifier
                                 .height(40.dp)
                                 .width(1.dp)
                                 .background(Color.White.copy(alpha = 0.2f))
                         )
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             Text("SUB OSC", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
                             Row {
                                 Knob("Pitch", 0.5f, 162, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("Drive", 0.0f, 172, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("Fold", 0.0f, 182, state, onStateChange, nativeLib, knobSize = 48.dp)
                                 Knob("PW", 0.5f, 192, state, onStateChange, nativeLib, knobSize = 48.dp)
                             }
                         }
                     }
                 }
             }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // AMP ENVELOPE (Square Cluster)
            ParameterGroup("Amp Envelope", modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("ATK", 0.1f, 100, state, onStateChange, nativeLib, knobSize = 48.dp)
                        Knob("DCY", 0.5f, 101, state, onStateChange, nativeLib, knobSize = 48.dp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("SUS", 0.8f, 102, state, onStateChange, nativeLib, knobSize = 48.dp)
                        Knob("REL", 0.2f, 103, state, onStateChange, nativeLib, knobSize = 48.dp)
                    }
                }
            }

            // FILTER & ENV (Square Cluster)
            ParameterGroup("Filter & Env", modifier = Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Knob("CUTOFF", 0.5f, 112, state, onStateChange, nativeLib, knobSize = 48.dp)
                            
                            val track = state.tracks[trackIndex]
                            val modeNames = listOf("LP", "HP", "BP")
                            val modeColors = listOf(Color(0xFF4CAF50), Color(0xFF03A9F4), Color(0xFFFFEB3B)) // Green, Blue, Yellow
                            
                            Button(
                                onClick = {
                                    val newMode = (track.filterMode + 1) % 3
                                    val newTracks = state.tracks.toMutableList()
                                    newTracks[trackIndex] = track.copy(filterMode = newMode)
                                    onStateChange(state.copy(tracks = newTracks))
                                    nativeLib.setFilterMode(trackIndex, newMode)
                                },
                                modifier = Modifier.height(20.dp).width(36.dp).padding(top = 2.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = modeColors.getOrElse(track.filterMode) { Color.Gray }),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(modeNames.getOrElse(track.filterMode) { "LP" }, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Black)
                            }
                        }
                        Knob("RESO", 0.0f, 113, state, onStateChange, nativeLib, knobSize = 48.dp)
                        Knob("F.AMT", 0.0f, 118, state, onStateChange, nativeLib, knobSize = 48.dp)
                    }
                    Divider(color = Color.White.copy(alpha = 0.2f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("F.ATK", 0.01f, 114, state, onStateChange, nativeLib, knobSize = 36.dp)
                        Knob("F.DCY", 0.1f, 115, state, onStateChange, nativeLib, knobSize = 36.dp)
                        Knob("F.SUS", 0.0f, 116, state, onStateChange, nativeLib, knobSize = 36.dp)
                        Knob("F.REL", 0.5f, 117, state, onStateChange, nativeLib, knobSize = 36.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun TestTriggerButton(trackIndex: Int, nativeLib: NativeLib, color: Color, state: GrooveboxState) {
    var isPressed by remember { mutableStateOf(false) }
    val track = state.tracks[trackIndex]
    
    // Choose the best note to trigger based on engine
    val note = when(track.engineType) {
        EngineType.FM_DRUM -> 60 + track.selectedFmDrumInstrument
        EngineType.ANALOG_DRUM -> 60 // Default to Kick for header button
        EngineType.SAMPLER -> {
            val mode = (track.parameters[320] ?: 0f)
            if (mode > 0.6f) 60 else 60 // Logic remains 60 for now, but aware
        }
        else -> 60
    }
    
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isPressed) color else color.copy(alpha = 0.2f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .pointerInput(trackIndex, note) { // Responsive to track/note changes
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        isPressed = true
                        nativeLib.triggerNote(trackIndex, note, 127)
                        waitForUpOrCancellation()
                        isPressed = false
                        nativeLib.releaseNote(trackIndex, note)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Test", tint = color, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun DiceIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val size = size.minDimension
        val dotRadius = size * 0.1f
        val padding = size * 0.2f
        
        // Draw 5 dots (Quincunx)
        val center = Offset(size / 2, size / 2)
        val tl = Offset(padding, padding)
        val tr = Offset(size - padding, padding)
        val bl = Offset(padding, size - padding)
        val br = Offset(size - padding, size - padding)
        
        drawCircle(color, radius = dotRadius, center = center)
        drawCircle(color, radius = dotRadius, center = tl)
        drawCircle(color, radius = dotRadius, center = tr)
        drawCircle(color, radius = dotRadius, center = bl)
        drawCircle(color, radius = dotRadius, center = br)
        
        // Optional: Draw border if needed, but the button has a border.
        // Let's keep it simple dots.
    }
}

@Composable
fun RandomizeButton(trackIndex: Int, state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, color: Color) {
    val track = state.tracks[trackIndex]
    
    // Check if this engine supports randomization as per user request
    val supportedEngines = listOf(
        EngineType.SUBTRACTIVE, EngineType.FM, EngineType.WAVETABLE, 
        EngineType.SAMPLER, EngineType.GRANULAR, EngineType.FM_DRUM, 
        EngineType.ANALOG_DRUM
    )
    
    if (track.engineType !in supportedEngines) return

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable {
                randomizeTrackParameters(trackIndex, track.engineType, state, onStateChange, nativeLib)
            },
        contentAlignment = Alignment.Center
    ) {
        DiceIcon(modifier = Modifier.size(24.dp), color = color)
    }
}

fun randomizeTrackParameters(trackIndex: Int, engineType: EngineType, state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val paramIds = when(engineType) {
        EngineType.SUBTRACTIVE -> {
            // Added 160-193 for Osc Sound Design (Pitch, Drive, Fold, PW)
             listOf(100, 101, 102, 103, 104, 105, 106, 111, 107, 108, 109, 110, 112, 113, 114, 115, 116, 117, 118,
                 160, 161, 170, 171, 180, 181, 190, 191) // 160-193 range supported in C++
        }
        EngineType.FM -> {
            val ids = mutableListOf(150, 154, 159, 157, 151, 152, 153, 155, 158)
            repeat(6) { op ->
                val base = 160 + op * 6
                ids.addAll(listOf(base, base+1, base+2, base+3, base+4, base+5))
            }
            ids
        }
        EngineType.WAVETABLE -> listOf(450, 467, 465, 466, 458, 459, 464, 461, 454, 455, 456, 457, 451, 530, 531)
        EngineType.SAMPLER -> listOf(330, 331, 340, 320, 300, 301, 302, 303, 304, 310, 311, 312, 313, 314)
        EngineType.GRANULAR -> listOf(429, 400, 406, 407, 401, 410, 415, 425, 426, 427, 428, 402, 403, 404, 405, 416, 417, 418, 419, 420, 412, 413)
        EngineType.FM_DRUM -> {
            val ids = mutableListOf<Int>()
            repeat(8) { i ->
                val base = 200 + i * 10
                ids.addAll(listOf(base, base+1, base+2, base+3))
            }
            ids
        }
        EngineType.ANALOG_DRUM -> listOf(600, 601, 602, 610, 613, 612, 620, 621, 622, 630, 631, 640, 641)
        else -> emptyList()
    }

    val newParams = state.tracks[trackIndex].parameters.toMutableMap()
    paramIds.forEach { id ->
        val newValue = (0..100).random() / 100f
        newParams[id] = newValue
        nativeLib.setParameter(trackIndex, id, newValue)
    }
    
    // special case for FM mask randomization
    if (engineType == EngineType.FM) {
        // Randomize FM masks in state as well
        val newActiveMask = (0..63).random()
        val newCarrierMask = (1..63).random() // ensure at least one carrier
        
        val newTracks = state.tracks.mapIndexed { i, t ->
            if (i == trackIndex) t.copy(parameters = newParams, fmActiveMask = newActiveMask, fmCarrierMask = newCarrierMask) else t
        }
        onStateChange(state.copy(tracks = newTracks))
        
        // sync masks to native (IDs 153 and 155 are used for this in FM Engine UI)
        nativeLib.setParameter(trackIndex, 153, newCarrierMask.toFloat())
        nativeLib.setParameter(trackIndex, 155, newActiveMask.toFloat())
    } else {
        val newTracks = state.tracks.mapIndexed { i, t ->
            if (i == trackIndex) t.copy(parameters = newParams) else t
        }
        onStateChange(state.copy(tracks = newTracks))
    }
}

@Composable
fun DrumTriggerButton(label: String, trackIndex: Int, note: Int, nativeLib: NativeLib, color: Color) {
    var isPressed by remember { mutableStateOf(false) }
    Button(
        onClick = { },
        modifier = Modifier
            .width(50.dp)
            .height(36.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        isPressed = true
                        nativeLib.triggerNote(trackIndex, note, 100)
                        waitForUpOrCancellation()
                        isPressed = false
                        nativeLib.releaseNote(trackIndex, note)
                    }
                }
            },
        colors = ButtonDefaults.buttonColors(containerColor = if (isPressed) color else color.copy(alpha = 0.2f)),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Visible, fontSize = 10.sp)
    }
}

@Composable
fun MidiParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val track = state.tracks[trackIndex]
    
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        ParameterGroup("MIDI Configuration") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // MIDI IN Selector
                Knob(
                    label = "MIDI IN",
                    initialValue = track.midiInChannel.toFloat() / 17f,
                    parameterId = 800,
                    state = state,
                    onStateChange = { newState ->
                        val newVal = newState.tracks[trackIndex].midiInChannel
                        nativeLib.setParameter(trackIndex, 800, newVal.toFloat())
                        onStateChange(newState)
                    },
                    nativeLib = nativeLib,
                    onValueChangeOverride = { v ->
                        val chan = (v * 17.1f).toInt().coerceIn(0, 17)
                        val newTracks = state.tracks.mapIndexed { i, t ->
                            if (i == trackIndex) t.copy(midiInChannel = chan) else t
                        }
                        nativeLib.setParameter(trackIndex, 800, chan.toFloat())
                        onStateChange(state.copy(tracks = newTracks, focusedValue = if (chan == 0) "IN: NONE" else if (chan == 17) "IN: ALL" else "IN: CH $chan"))
                    },
                    overrideValue = track.midiInChannel.toFloat() / 17f,
                    valueFormatter = { v ->
                        val chan = (v * 17.1f).toInt().coerceIn(0, 17)
                        if (chan == 0) "IN: NONE" else if (chan == 17) "IN: ALL" else "IN: CH $chan"
                    }
                )

                // MIDI OUT Selector
                Knob(
                    label = "MIDI OUT",
                    initialValue = (track.midiOutChannel - 1).toFloat() / 15f,
                    parameterId = 801,
                    state = state,
                    onStateChange = { newState ->
                        val newVal = newState.tracks[trackIndex].midiOutChannel
                        nativeLib.setParameter(trackIndex, 801, newVal.toFloat())
                        onStateChange(newState)
                    },
                    nativeLib = nativeLib,
                    onValueChangeOverride = { v ->
                        val chan = (v * 15.1f).toInt().coerceIn(0, 15) + 1
                        val newTracks = state.tracks.mapIndexed { i, t ->
                            if (i == trackIndex) t.copy(midiOutChannel = chan) else t
                        }
                        nativeLib.setParameter(trackIndex, 801, chan.toFloat())
                        onStateChange(state.copy(tracks = newTracks, focusedValue = "OUT: CH $chan"))
                    },
                    overrideValue = (track.midiOutChannel - 1).toFloat() / 15f,
                    valueFormatter = { v ->
                         val chan = (v * 15.1f).toInt().coerceIn(0, 15) + 1
                         "OUT: CH $chan"
                    }
                )
            }
        }
    }
}

@Composable
fun ParameterGroup(title: String, modifier: Modifier = Modifier, titleSize: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.02f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(4.dp, 12.dp).background(Color.Cyan, RoundedCornerShape(2.dp)))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = titleSize.sp, fontWeight = FontWeight.ExtraBold),
                color = Color.White,
                letterSpacing = 1.sp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        content()
    }
}

@Composable
fun FmDrumParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val instruments = listOf("KICK", "SNARE", "TOM", "HIHAT", "OHH", "CYMB", "PERC", "NOISE")
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        instruments.forEachIndexed { i, name ->
            val baseId = 200 + i * 10
            Column(
                modifier = Modifier
                    .width(90.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                // Removed Play Buttons per request
                // Instrument Header
                val isSelectedForSidechain = state.isSelectingSidechain && state.sidechainSourceTrack == trackIndex && state.sidechainSourceDrumIdx == i
                val hotPink = Color(0xFFFF69B4)
                Text(name, style = MaterialTheme.typography.labelMedium, color = if (isSelectedForSidechain) hotPink else Color.White, maxLines = 1)
                
                // Specialized Percussion Icon
                EngineIcon(
                    type = EngineType.FM_DRUM,
                    drumType = name,
                    modifier = Modifier.size(36.dp)
                        .then(if (state.isSelectingSidechain) Modifier.border(2.dp, hotPink, CircleShape).padding(4.dp) else Modifier)
                        .clickable {
                        if (state.isSelectingSidechain) {
                            nativeLib.setParameter(0, 585, trackIndex.toFloat())
                            nativeLib.setParameter(0, 586, i.toFloat())
                            onStateChange(state.copy(isSelectingSidechain = false, sidechainSourceTrack = trackIndex, sidechainSourceDrumIdx = i, selectedTab = 3))
                        } else {
                            nativeLib.setSelectedFmDrumInstrument(trackIndex, i)
                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t -> if (idx == trackIndex) t.copy(selectedFmDrumInstrument = i) else t }))
                            nativeLib.triggerNote(trackIndex, 60 + i, 100)
                        }
                    },
                    color = if (isSelectedForSidechain) hotPink else getEngineColor(EngineType.FM_DRUM)
                )
                
                Divider(color = Color.White.copy(alpha = 0.1f))

                // Parameters
                Knob("PITCH", 0.5f, baseId, state, onStateChange, nativeLib, knobSize = 44.dp)
                Knob("CLICK", 0.1f, baseId + 1, state, onStateChange, nativeLib, knobSize = 44.dp)
                Knob("DECAY", 0.3f, baseId + 2, state, onStateChange, nativeLib, knobSize = 44.dp)
                Knob("GAIN", 0.8f, baseId + 5, state, onStateChange, nativeLib, knobSize = 44.dp)
            }
        }
    }
}



data class FmPreset(val id: Int, val name: String, val category: String)

val fmPresets = listOf(
    FmPreset(0, "Brass", "Brass"),
    FmPreset(1, "Strings", "Strings"),
    FmPreset(2, "Orchestra", "Ens"),
    FmPreset(3, "Piano", "Keys"),
    FmPreset(4, "E. Piano", "Keys"),
    FmPreset(5, "Guitar", "Pluck"),
    FmPreset(6, "Bass", "Bass"),
    FmPreset(7, "Organ", "Keys"),
    FmPreset(8, "Pipes", "Wind"),
    FmPreset(9, "Harpsichord", "Keys"),
    FmPreset(10, "Clav", "Keys"),
    FmPreset(11, "Vibe", "Mallet"),
    FmPreset(12, "Marimba", "Mallet"),
    FmPreset(13, "Koto", "Pluck"),
    FmPreset(14, "Flute", "Wind"),
    FmPreset(15, "Tubular Bells", "Bell"),
    FmPreset(16, "Voice", "Vox"),
    FmPreset(17, "Choir", "Vox"),
    FmPreset(18, "Calliope", "Wind"),
    FmPreset(19, "Oboe", "Wind"),
    FmPreset(20, "Bassoon", "Wind"),
    FmPreset(21, "Xylophone", "Mallet"),
    FmPreset(22, "Church Bells", "Bell"),
    FmPreset(23, "Synth Lead", "Lead")
)

@Composable
fun FmParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRefresh: () -> Unit) {
    val track = state.tracks[trackIndex]
    var showPresetDrawer by remember { mutableStateOf(false) }

    if (showPresetDrawer) {
        Dialog(onDismissRequest = { showPresetDrawer = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF222222),
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Select FM Preset", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(fmPresets) { preset ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.DarkGray)
                                    .clickable {
                                        nativeLib.loadFmPreset(trackIndex, preset.id)
                                        showPresetDrawer = false
                                        onRefresh()
                                    }
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    // Simple generic icon based on category with vibrant colors
                                    val iconColor = when(preset.category) {
                                        "Brass" -> Color(0xFFFFD700)   // Gold
                                        "Strings" -> Color(0xFF9370DB) // Medium Purple
                                        "Keys" -> Color(0xFF00BFFF)    // Deep Sky Blue
                                        "Pad" -> Color(0xFF20B2AA)     // Light Sea Green
                                        "Bass" -> Color(0xFFFF4500)    // Orange Red
                                        "Ens" -> Color(0xFFFF69B4)     // Hot Pink
                                        "Pluck" -> Color(0xFF32CD32)   // Lime Green
                                        "Wind" -> Color(0xFF00FA9A)    // Medium Spring Green
                                        "Mallet" -> Color(0xFFFF6347)  // Tomato
                                        "Bell" -> Color(0xFFE0FFFF)    // Light Cyan (Bright)
                                        "Vox" -> Color(0xFFF08080)     // Light Coral
                                        "Lead" -> Color(0xFFFF00FF)    // Magenta
                                        else -> Color(0xFF7FFFD4)      // Aquamarine
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(iconColor, CircleShape)
                                            .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(preset.name, style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showPresetDrawer = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("Close")
                    }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Global Controls: Algorithm + Dynamics + Filter + Unison
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ParameterGroup("Routing & Dynamics", modifier = Modifier.weight(2f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Knob("ALGO", 0.0f, 150, state, onStateChange, nativeLib, knobSize = 42.dp)
                    }
                    Knob("FBK", 0.0f, 154, state, onStateChange, nativeLib, knobSize = 42.dp)
                    Knob("DRIVE", 0.0f, 159, state, onStateChange, nativeLib, knobSize = 42.dp)
                    Knob("BRT", 0.5f, 157, state, onStateChange, nativeLib, knobSize = 42.dp)
                    Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 42.dp)
                }
            }
            
            ParameterGroup("Filter & Unison", modifier = Modifier.weight(2f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MODE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(4.dp))
                        val currentMode = (track.parameters[156] ?: 0.0f).toInt().coerceIn(0, 2)
                        Button(
                            onClick = { 
                                val nextMode = (currentMode + 1) % 3
                                val newVal = nextMode.toFloat()
                                nativeLib.setParameter(trackIndex, 156, newVal)
                                onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(parameters = t.parameters + (156 to newVal)) else t }))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            modifier = Modifier.height(32.dp).width(44.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(when(currentMode) { 0 -> "LP"; 1 -> "HP"; else -> "BP" }, fontSize = 10.sp, color = Color.White)
                        }
                    }
                    Knob("FILT", 0.5f, 151, state, onStateChange, nativeLib, knobSize = 42.dp)
                    Knob("RES", 0.0f, 152, state, onStateChange, nativeLib, knobSize = 42.dp)
                    Knob("DET", 0.0f, 158, state, onStateChange, nativeLib, knobSize = 42.dp)
                }
            }
            
            ParameterGroup("Amp Envelope", modifier = Modifier.weight(2f), titleSize = 10) {
                 Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                     Knob("A", 0.01f, 100, state, onStateChange, nativeLib, knobSize = 42.dp)
                     Knob("D", 0.1f, 101, state, onStateChange, nativeLib, knobSize = 42.dp)
                     Knob("S", 0.8f, 102, state, onStateChange, nativeLib, knobSize = 42.dp)
                     Knob("R", 0.5f, 103, state, onStateChange, nativeLib, knobSize = 42.dp)
                 }
            }
        }

        // 6-Operator Grid
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {
            repeat(6) { opIdx ->
                val baseId = 160 + opIdx * 6
                Column(
                    modifier = Modifier
                        .width(140.dp) 
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Operator Toggle Button (3 States: OFF, MOD, CARRIER)
                    val isActive = (track.fmActiveMask and (1 shl opIdx)) != 0
                    val isCarrier = (track.fmCarrierMask and (1 shl opIdx)) != 0
                    
                    val (buttonColor, textColor, stateLabel) = when {
                        !isActive -> Triple(Color.Black, Color.White, "OFF")
                        isCarrier -> Triple(Color(0xFF00FF00), Color.Black, "CAR")
                        else -> Triple(Color(0xFFFF00FF), Color.White, "MOD")
                    }

                    Button(
                        onClick = { 
                            var nextActive = isActive
                            var nextCarrier = isCarrier
                            
                            // Cycle: MOD (Active=1, Carr=0) -> CAR (Active=1, Carr=1) -> OFF (Active=0, Carr=0) -> MOD...
                            if (isActive && !isCarrier) { // MOD -> CAR
                                nextCarrier = true
                            } else if (isActive && isCarrier) { // CAR -> OFF
                                nextActive = false
                                nextCarrier = false
                            } else { // OFF -> MOD
                                nextActive = true
                                nextCarrier = false
                            }

                            onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                if (idx == trackIndex) t.copy(fmActiveMask = if (nextActive) t.fmActiveMask or (1 shl opIdx) else t.fmActiveMask and (1 shl opIdx).inv(),
                                                            fmCarrierMask = if (nextCarrier) t.fmCarrierMask or (1 shl opIdx) else t.fmCarrierMask and (1 shl opIdx).inv()) else t
                            }))
                            // NativeLib: we need to pass both masks or handle them in setParameter
                            // For now, let's use param 153 for CarrierMask and assume another param for ActiveMask or combine them
                            nativeLib.setParameter(trackIndex, 153, (if (nextCarrier) track.fmCarrierMask or (1 shl opIdx) else track.fmCarrierMask and (1 shl opIdx).inv()).toFloat())
                            nativeLib.setParameter(trackIndex, 155, (if (nextActive) track.fmActiveMask or (1 shl opIdx) else track.fmActiveMask and (1 shl opIdx).inv()).toFloat())
                        },

                        modifier = Modifier.size(width = 56.dp, height = 36.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                        border = if (!isActive) BorderStroke(1.dp, Color.Gray) else null
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("${opIdx + 1}", color = textColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stateLabel, color = textColor, style = MaterialTheme.typography.labelSmall, fontSize = 8.sp)
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f))

                    // Operator Parameter Knobs (made slightly larger)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("LVL", 0.5f, baseId, state, onStateChange, nativeLib, knobSize = 40.dp)
                        Knob("RATIO", 0.0625f, baseId + 5, state, onStateChange, nativeLib, knobSize = 40.dp)
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("A", 0.01f, baseId + 1, state, onStateChange, nativeLib, knobSize = 40.dp)
                        Knob("D", 0.1f, baseId + 2, state, onStateChange, nativeLib, knobSize = 40.dp)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Knob("S", 0.8f, baseId + 3, state, onStateChange, nativeLib, knobSize = 40.dp)
                        Knob("R", 0.5f, baseId + 4, state, onStateChange, nativeLib, knobSize = 40.dp)
                    }
                }
            }

        }

        // Preset Button (Bottom)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
             Button(
                onClick = { showPresetDrawer = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ADB5)), // Cyan/Teal
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp).fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Menu, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("BROWSE FM PRESETS", color = Color.Black, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}


@Composable
fun GranularParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var playheads by remember { mutableStateOf(floatArrayOf()) }
    var isRecordingSample by remember { mutableStateOf(false) }

    // Animation loop for playheads and waveform - 60fps
    // Animation loop for playheads and waveform
    LaunchedEffect(trackIndex) {
        waveform = nativeLib.getWaveform(trackIndex)
        while (true) {
            // Poll playheads
            playheads = nativeLib.getGranularPlayheads(trackIndex)
            
            // Only poll waveform if recording
            if (isRecordingSample) {
                waveform = nativeLib.getWaveform(trackIndex)
            }
            delay(33) // 30fps
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // TestTriggerButton removed (redundant)

        // Recording Strip for Granular
        RecordingStrip(
            trackIndex = trackIndex,
            isRecording = isRecordingSample,
            isResampling = state.isResampling,
            waveform = waveform,
            track = state.tracks[trackIndex],
            onStartRecording = { 
                isRecordingSample = true
                nativeLib.startRecordingSample(trackIndex) 
            },
            onStopRecording = { 
                nativeLib.stopRecordingSample(trackIndex)
                isRecordingSample = false
                waveform = nativeLib.getWaveform(trackIndex)
            },
            onToggleResampling = {
                val newResampling = !state.isResampling
                onStateChange(state.copy(isResampling = newResampling))
                nativeLib.setResampling(newResampling)
            },
            onWaveformRefresh = { waveform = nativeLib.getWaveform(trackIndex) },
            granularPlayheads = playheads,
            grainSize = state.tracks[trackIndex].parameters[406],
            nativeLib = nativeLib,
            state = state,
            onStateChange = onStateChange,
            extraControls = {
                // Gain Knob visible in Recording Strip
                Knob("GAIN", 0.4f, 429, state, onStateChange, nativeLib, knobSize = 44.dp)
            }
        )

        // Removed "Grain Playheads" preview as per user request to use main sampler window.
        
        // Row 1: The Cloud & Motion
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ParameterGroup("Cloud", modifier = Modifier.weight(1f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("POS", 0.0f, 400, state, onStateChange, nativeLib, knobSize = 44.dp)
                    Knob("SIZE", 0.2f, 406, state, onStateChange, nativeLib, knobSize = 44.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("DENS", 0.5f, 407, state, onStateChange, nativeLib, knobSize = 44.dp)
                    Knob("SPRAY", 0.0f, 415, state, onStateChange, nativeLib, knobSize = 44.dp)
                }
            }
            ParameterGroup("Motion", modifier = Modifier.weight(1f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("SPEED", 0.5f, 401, state, onStateChange, nativeLib, knobSize = 44.dp)
                    Knob("JITTER", 0.0f, 411, state, onStateChange, nativeLib, knobSize = 44.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("REVERSE", 0.0f, 412, state, onStateChange, nativeLib, knobSize = 44.dp)
                    Knob("PITCH", 0.5f, 410, state, onStateChange, nativeLib, knobSize = 44.dp)
                    Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 44.dp)
                }
            }
        }

        // Row 2: Envelope & Advanced
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ParameterGroup("Envelope", modifier = Modifier.weight(1f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("ATK", 0.0f, 425, state, onStateChange, nativeLib, knobSize = 40.dp)
                    Knob("DEC", 0.1f, 426, state, onStateChange, nativeLib, knobSize = 40.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("SUS", 1.0f, 427, state, onStateChange, nativeLib, knobSize = 40.dp)
                    Knob("REL", 0.1f, 428, state, onStateChange, nativeLib, knobSize = 40.dp)
                }
            }
            ParameterGroup("Advanced", modifier = Modifier.weight(1f), titleSize = 10) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("DETUN", 0.0f, 416, state, onStateChange, nativeLib, knobSize = 40.dp)
                    Knob("RAND", 0.0f, 417, state, onStateChange, nativeLib, knobSize = 40.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("COUNT", 0.2f, 418, state, onStateChange, nativeLib, knobSize = 40.dp)
                    Knob("WIDTH", 0.5f, 419, state, onStateChange, nativeLib, knobSize = 40.dp)
                }
            }
        }
    }
}



@Composable
fun SoundFontParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, onRefresh: () -> Unit) {
    var showLoadDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val track = state.tracks[trackIndex]

    val soundFontsDir = File(PersistenceManager.getLoomFolder(context), "soundfonts")
    if (!soundFontsDir.exists()) soundFontsDir.mkdirs()

    if (showLoadDialog) {
        NativeFileDialog(
            directory = soundFontsDir,
            onDismiss = { showLoadDialog = false },
            state = state,
            onFileSelected = { path ->
                nativeLib.loadSoundFont(trackIndex, path)
                val newTracks = state.tracks.mapIndexed { i, t ->
                    if (i == trackIndex) t.copy(soundFontPath = path) else t
                }
                onStateChange(state.copy(tracks = newTracks))
                onRefresh()
            },
            isSave = false,
            trackIndex = trackIndex,
            extensions = listOf("sf2"),
            title = "LOAD SOUNDFONT"
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Header with Load Button
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), 
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
             Text("SOUNDFONT SOURCE", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.Bold)
             
             Button(
                onClick = { showLoadDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha=0.1f)),
                border = BorderStroke(1.dp, Color.Magenta.copy(alpha=0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp)
             ) {
                 Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Magenta)
                 Spacer(modifier = Modifier.width(8.dp))
                 val displayName = if ((track.soundFontPath ?: "").isNotEmpty()) File(track.soundFontPath ?: "").name else "Select SF2..."
                 Text(displayName, color = Color.Magenta, style = MaterialTheme.typography.labelLarge)
             }
         }

        // Preset Selection
        if ((track.soundFontPath ?: "").isNotEmpty()) {
            ParameterGroup("Preset Selection", titleSize = 12) {
                var showPresetMenu by remember { mutableStateOf(false) }
                val presetCount = remember(track.soundFontPath, track.id) { nativeLib.getSoundFontPresetCount(trackIndex) }
                
                Column(modifier = Modifier.padding(8.dp)) {
                    Button(
                        onClick = { showPresetMenu = true },
                        modifier = Modifier.fillMaxWidth().height(45.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.05f)),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(track.soundFontPresetName, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.Gray)
                        }
                    }

                    if (showPresetMenu) {
                        AlertDialog(
                            onDismissRequest = { showPresetMenu = false },
                            confirmButton = {},
                            dismissButton = { TextButton(onClick = { showPresetMenu = false }) { Text("Close") } },
                            title = { Text("SELECT PRESET", style = MaterialTheme.typography.titleMedium) },
                            text = {
                                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                                    modifier = Modifier.heightIn(max = 400.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(presetCount) { i ->
                                        val name = nativeLib.getSoundFontPresetName(trackIndex, i)
                                        val isSelected = i == track.soundFontPresetIndex
                                        
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSelected) Color.Magenta else Color.White.copy(alpha = 0.1f))
                                                .clickable {
                                                    nativeLib.setSoundFontPreset(trackIndex, i)
                                                    val newTracks = state.tracks.mapIndexed { idx, t ->
                                                        if (idx == trackIndex) t.copy(soundFontPresetIndex = i, soundFontPresetName = name) else t
                                                    }
                                                    onStateChange(state.copy(tracks = newTracks))
                                                    showPresetMenu = false
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = name,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelected) Color.Black else Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        // Link to external collections
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        TextButton(
            onClick = { uriHandler.openUri("https://github.com/Co-oscillator/loom-groovebox/blob/main/UserManual.md#recommended-external-collections") },
            modifier = Modifier.padding(horizontal = 4.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Download Extra Collections (VSCO, SGM...)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }

        // Standard Loom Macro Controls (Mapped to SF2 Generators)
        ParameterGroup("Performance Macros", titleSize = 12) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("GAIN", 0.8f, 0, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("PAN", 0.5f, 9, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("CUTOFF", 0.5f, 1, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("RESO", 0.0f, 2, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("ENV AMT", 0.0f, 3, state, onStateChange, nativeLib, knobSize = 55.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("DETUNE", 0.0f, 6, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("LFO RAT", 0.0f, 7, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("LFO DEP", 0.0f, 8, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("REVERB", 0.0f, 2000, state, onStateChange, nativeLib, knobSize = 55.dp) // FX Send
                    Knob("DELAY", 0.0f, 2010, state, onStateChange, nativeLib, knobSize = 55.dp) // FX Send
                }
            }
        }

        // ADSR Section (Automatically affects SF2 voices)
        ParameterGroup("Amp Envelope", titleSize = 12) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("ATK", 0.01f, 100, state, onStateChange, nativeLib, knobSize = 55.dp)
                Knob("DCY", 0.1f, 101, state, onStateChange, nativeLib, knobSize = 55.dp)
                Knob("SUS", 0.8f, 102, state, onStateChange, nativeLib, knobSize = 55.dp)
                Knob("REL", 0.5f, 103, state, onStateChange, nativeLib, knobSize = 55.dp)
                Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 55.dp)
            }
        }

        // Custom Generator Mapping Section
        ParameterGroup("Custom Mapping", titleSize = 12) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Map Loom Knobs to SoundFont Generators", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                // Example: Map Knob 1 (ID 1) to a generator
                GeneratorMappingRow("Knob 1 (Osc Wave)", 1, (track.soundFontMapping ?: emptyMap())[1] ?: 0, trackIndex, nativeLib, onStateChange, state)
                GeneratorMappingRow("Knob 2 (Detune)", 6, (track.soundFontMapping ?: emptyMap())[6] ?: 0, trackIndex, nativeLib, onStateChange, state)
            }
        }
    }
}

@Composable
fun GeneratorMappingRow(label: String, knobId: Int, currentGenId: Int, trackIndex: Int, nativeLib: NativeLib, onStateChange: (GrooveboxState) -> Unit, state: GrooveboxState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
        
        // This would be better as a dropdown, but for simplicity we'll use a numeric selector or a common set of generators
        val generators = listOf(
            "None" to 0,
            "Filter Cutoff" to 8,
            "Filter Q" to 9,
            "Mod LFO -> Pitch" to 5,
            "Vib LFO -> Pitch" to 7,
            "Mod Env -> Pitch" to 11,
            "Pan" to 17
        )
        
        var showMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { showMenu = true }) {
                Text(generators.find { it.second == currentGenId }?.first ?: "Gen $currentGenId", color = Color.Cyan)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                generators.forEach { (name, id) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            nativeLib.setSoundFontMapping(trackIndex, knobId, id)
                            val newMapping = (state.tracks[trackIndex].soundFontMapping ?: emptyMap()).toMutableMap()
                            newMapping[knobId] = id
                            val newTracks = state.tracks.mapIndexed { i, t ->
                                if (i == trackIndex) t.copy(soundFontMapping = newMapping) else t
                            }
                            onStateChange(state.copy(tracks = newTracks))
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}
@Composable
fun SamplerParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val track = state.tracks[trackIndex]
    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var granularPlayheads by remember { mutableStateOf<FloatArray?>(null) }
    
    // Poll waveform
    val isRecordingSample = state.isRecordingSample && state.recordingTrackIndex == trackIndex

    LaunchedEffect(trackIndex, track.engineType, isRecordingSample) {
        nativeLib.setEngineType(trackIndex, 2) // Force Sampler Engine (Type 2) to prevent Subtractive fallback
        waveform = nativeLib.getWaveform(trackIndex)
        while(true) {
            if (isRecordingSample) {
                waveform = nativeLib.getWaveform(trackIndex)
            }
            granularPlayheads = nativeLib.getGranularPlayheads(trackIndex)
            delay(33) 
        }
    }
    
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // TestTriggerButton removed (redundant)

        RecordingStrip(
            trackIndex = trackIndex,
            isRecording = isRecordingSample,
            isResampling = state.isResampling, 
            waveform = waveform,
            track = track,
            onStartRecording = { nativeLib.startRecordingSample(trackIndex); onStateChange(state.copy(isRecordingSample = true, recordingTrackIndex = trackIndex)) },
            onStopRecording = { 
                nativeLib.stopRecordingSample(trackIndex)
                onStateChange(state.copy(isRecordingSample = false, recordingTrackIndex = -1))
                waveform = nativeLib.getWaveform(trackIndex)
            },
            onToggleResampling = {
                val newResampling = !state.isResampling
                onStateChange(state.copy(isResampling = newResampling))
                nativeLib.setResampling(newResampling)
            },
            onWaveformRefresh = { waveform = nativeLib.getWaveform(trackIndex) },
            trimStart = track.parameters[330],
            trimEnd = track.parameters[331],
            slices = ((track.parameters[340] ?: 0f) * 14f).toInt() + 2,
            granularPlayheads = granularPlayheads,
            grainSize = track.parameters[406],
            nativeLib = nativeLib,
            state = state,
            onStateChange = onStateChange
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Sample Properties & Play Mode
        ParameterGroup("Sample Properties & Play Mode", titleSize = 12) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Knob("START", 0.0f, 330, state, onStateChange, nativeLib, knobSize = 50.dp)
                Knob("END", 1.0f, 331, state, onStateChange, nativeLib, knobSize = 50.dp)
                Knob("SLICES", 0.0f, 340, state, onStateChange, nativeLib, knobSize = 50.dp, valueFormatter = { v -> "${(v * 14f).toInt() + 2}" })
                Knob("MODE", 0.0f, 320, state, onStateChange, nativeLib, knobSize = 50.dp, valueFormatter = { v ->
                    if (v < 0.16f) "1 SHOT"
                    else if (v < 0.33f) "SUSTAIN"
                    else if (v < 0.5f) "LOOP"
                    else if (v < 0.66f) "CHOP"
                    else if (v < 0.83f) "1 CHOP"
                    else "CHOP LOOP"
                })
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 10 Knobs (2 rows of 5)
        ParameterGroup("Synthesis & Envelopes", titleSize = 12) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("PITCH", 0.5f, 300, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("STRETCH", 0.25f, 301, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("SPEED", 0.5f, 302, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("FILTER", 0.5f, 303, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("RESO", 0.2f, 304, state, onStateChange, nativeLib, knobSize = 55.dp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("ATK", 0.01f, 310, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("DCY", 0.2f, 311, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("SUS", 1.0f, 312, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("REL", 0.5f, 313, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("GLIDE", 0.0f, 355, state, onStateChange, nativeLib, knobSize = 55.dp)
                    Knob("EG INT", 0.5f, 314, state, onStateChange, nativeLib, knobSize = 55.dp)
                }
            }
        }
    }
}

@Composable
fun TransportControls(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Cluster 1: BPM and Swing
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Knob("BPM", (latestState.tempo - 12f) / 228f, -1, latestState, latestOnStateChange, nativeLib, knobSize = 50.dp, onValueChangeOverride = {
                val newBpm = 12f + (it * 228f)
                latestOnStateChange(latestState.copy(tempo = newBpm))
                nativeLib.setTempo(newBpm)
            })
            
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
                Text("TAP", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White)
            }
            Text("${latestState.tempo.toInt()}", style = MaterialTheme.typography.labelSmall, color = Color.White)
            
            Spacer(modifier = Modifier.height(2.dp))
            
            Knob("SWNG", latestState.swing, -1, latestState, latestOnStateChange, nativeLib, knobSize = 50.dp, onValueChangeOverride = {
                latestOnStateChange(latestState.copy(swing = it))
                nativeLib.setSwing(it)
            })
            Text("${(latestState.swing * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 2: Play, Record, Stop
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Record Button
            Box(
                modifier = Modifier
                    .size(44.dp)
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
                    Box(modifier = Modifier.size(12.dp).background(Color.White, RoundedCornerShape(6.dp)))
                } else {
                    Box(modifier = Modifier.size(12.dp).background(Color.Red, RoundedCornerShape(6.dp)))
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
                modifier = Modifier.size(44.dp).background(if (latestState.isPlaying) Color.Green.copy(alpha = 0.2f) else Color.DarkGray, RoundedCornerShape(22.dp))
            ) {
                Icon(
                    Icons.Filled.PlayArrow, 
                    contentDescription = "Play", 
                    tint = if (latestState.isPlaying) Color.Green else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            // Stop Button
            var stopFlash by remember { mutableStateOf(false) }
            val stopColor by animateColorAsState(if (stopFlash) Color.Red else Color.Gray)
            
            Box(
                modifier = Modifier
                    .size(44.dp)
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
                Box(modifier = Modifier.size(16.dp).background(Color.White, RoundedCornerShape(2.dp)))
            }
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 3: Master Volume
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Knob("VOL", latestState.masterVolume, -1, latestState, latestOnStateChange, nativeLib, knobSize = 56.dp, onValueChangeOverride = {
                latestOnStateChange(latestState.copy(masterVolume = it))
                nativeLib.setMasterVolume(it)
            })
            Text("MASTER", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
        }

        Divider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(horizontal = 8.dp))

        // Cluster 4: REV, RND, Jump (Vertical Stack)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally, 
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            val buttonModifier = Modifier.size(52.dp, 36.dp)
            val buttonShape = RoundedCornerShape(8.dp)
            val unselectedColor = Color.DarkGray.copy(alpha = 0.6f)

            // REVERSE
            Button(
                onClick = { 
                    val current = latestState
                    val nextDir = if (current.playbackDirection == 1) 0 else 1
                    latestOnStateChange(current.copy(playbackDirection = nextDir))
                    nativeLib.setPlaybackDirection(current.selectedTrackIndex, nextDir)
                },
                modifier = buttonModifier,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (latestState.playbackDirection == 1) Color.Red else unselectedColor),
                shape = buttonShape
            ) { Text("REV", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White) }

            // PING-PONG
            Button(
                onClick = { 
                    val current = latestState
                    val nextDir = if (current.playbackDirection == 2) 0 else 2
                    latestOnStateChange(current.copy(playbackDirection = nextDir))
                    nativeLib.setPlaybackDirection(current.selectedTrackIndex, nextDir)
                },
                modifier = buttonModifier,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (latestState.playbackDirection == 2) Color.Cyan else unselectedColor),
                shape = buttonShape
            ) { Text("PNG", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White) }

            // RANDOM
            Button(
                onClick = { 
                    val current = latestState
                    val nextRand = !current.isRandomOrder
                    latestOnStateChange(current.copy(isRandomOrder = nextRand))
                    nativeLib.setIsRandomOrder(current.selectedTrackIndex, nextRand)
                },
                modifier = buttonModifier,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (latestState.isRandomOrder) Color.Magenta else unselectedColor),
                shape = buttonShape
            ) { Text("RND", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White) }

            // JUMP
            Button(
                onClick = { 
                    val current = latestState
                    val nextJumpWaiting = !current.jumpModeWaitingForTap
                    latestOnStateChange(current.copy(jumpModeWaitingForTap = nextJumpWaiting))
                    // Also trigger native jump mode immediately if we want it to repeat CURRENT step
                    nativeLib.setIsJumpMode(current.selectedTrackIndex, nextJumpWaiting)
                },
                modifier = buttonModifier,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (latestState.jumpModeWaitingForTap) Color.Yellow else unselectedColor),
                shape = buttonShape
            ) { Text("JUMP", style = MaterialTheme.typography.labelSmall, color = if (latestState.jumpModeWaitingForTap) Color.Black else Color.White, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun Knob(
    label: String, 
    initialValue: Float, 
    parameterId: Int, 
    state: GrooveboxState, 
    onStateChange: (GrooveboxState) -> Unit, 
    nativeLib: NativeLib,
    knobSize: Dp = 50.dp,
    onValueChangeOverride: ((Float) -> Unit)? = null,
    overrideValue: Float? = null,
    overrideColor: Color? = null,
    valueFormatter: ((Float) -> String)? = null,
    isBold: Boolean = false,
    showValue: Boolean = true,
    onLocalValueChange: (String?) -> Unit = {} // New callback for performance
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val latestOnValueChangeOverride by rememberUpdatedState(onValueChangeOverride)
    val trackIndex = state.selectedTrackIndex
    
    val localFocusedSetter = LocalFocusedValue.current
    // Derived value logic
    val trackValue = if (parameterId != -1) (state.tracks[trackIndex].parameters[parameterId] ?: initialValue) else initialValue
    val effectiveValue = overrideValue ?: trackValue
    
    // local value state: 
    // - reset when parameterId changes
    // - reset when trackIndex changes (unless it's a global/shared override)
    // - reset when effectiveValue changes IF NOT HELD (to avoid fighting UI vs global state)
    var value by remember(parameterId, trackIndex, effectiveValue) { mutableStateOf(effectiveValue) }
    var isHeld by remember { mutableStateOf(false) }

    val engineColor = overrideColor ?: getEngineColor(state.tracks[state.selectedTrackIndex].engineType)
    
    // Check if this parameter is locked on the current track (any step)
    val hasLocks = remember(trackIndex, parameterId, state.tracks[trackIndex].steps, state.tracks[trackIndex].drumSteps) {
        val t = state.tracks[trackIndex]
        if (t.engineType == EngineType.FM_DRUM) {
            t.drumSteps.any { steps -> steps.any { it.parameterLocks.containsKey(parameterId) } }
        } else {
            t.steps.any { it.parameterLocks.containsKey(parameterId) }
        }
    }

    // Check if specifically locked on currently targeted step (if in p-lock mode)
    val isLockedOnTarget = remember(state.isParameterLocking, state.lockingTarget, trackIndex, parameterId, state.tracks) {
        if (!state.isParameterLocking || state.lockingTarget == null) false
        else if (state.tracks[trackIndex].engineType == EngineType.FM_DRUM) {
            val drumInstrumentIndex = state.lockingTarget!!.first // Assuming first is drum instrument index
            val stepIdx = state.lockingTarget!!.second // Assuming second is step index
            state.tracks[trackIndex].drumSteps.getOrNull(drumInstrumentIndex)?.getOrNull(stepIdx)?.parameterLocks?.containsKey(parameterId) ?: false
        } else {
             val stepIdx = state.lockingTarget!!.second // Simplified for quick lookup
             state.tracks[trackIndex].steps.getOrNull(stepIdx)?.parameterLocks?.containsKey(parameterId) ?: false
        }
    }

    LaunchedEffect(effectiveValue) {
        if (!isHeld) {
            value = effectiveValue
        }
    }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .background(
                    color = if (isHeld) engineColor.copy(alpha = 0.4f) 
                            else if (state.isParameterLocking) Color.Magenta.copy(alpha = 0.3f) 
                            else if (state.midiLearnActive && state.midiLearnStep == 2) Color.Yellow.copy(alpha = 0.2f)
                            else if (state.lfoLearnActive || state.macroLearnActive) Color.Green.copy(alpha = 0.1f)
                            else if (isBold) Color.Transparent
                            else Color.DarkGray, 
                    shape = RoundedCornerShape(knobSize / 2)
                )
                .border(
                    width = if ((isHeld || state.isParameterLocking || (state.midiLearnActive && state.midiLearnStep == 2)) && !isBold) 2.dp else 0.dp,
                    color = if (isHeld) engineColor 
                            else if (state.isParameterLocking) Color.Magenta 
                            else if (state.midiLearnActive && state.midiLearnStep == 2) Color.Yellow
                            else if (state.lfoLearnActive || state.macroLearnActive) Color.Green
                            else Color.Transparent,
                    shape = RoundedCornerShape(knobSize / 2)
                )
                .pointerInput(parameterId, state.isParameterLocking, state.midiLearnActive, state.midiLearnStep, state.lfoLearnActive, state.macroLearnActive) {
                    if (state.midiLearnActive && state.midiLearnStep == 2) {
                        detectTapGestures {
                            val stripIdx = latestState.midiLearnSelectedStrip ?: return@detectTapGestures
                            val newState = if (stripIdx < 4) {
                                val newRoutings = latestState.stripRoutings.map {
                                    if (it.stripIndex == stripIdx) it.copy(targetType = 1, targetId = parameterId, parameterName = label)
                                    else it
                                }
                                latestState.copy(stripRoutings = newRoutings, midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null, selectedTab = 0)
                            } else {
                                val knobIdx = stripIdx - 4
                                val newRoutings = latestState.knobRoutings.mapIndexed { idx, item ->
                                    if (idx == knobIdx) item.copy(targetType = 1, targetId = parameterId, parameterName = label)
                                    else item
                                }
                                latestState.copy(knobRoutings = newRoutings, midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null, selectedTab = 0)
                            }
                            latestOnStateChange(newState)
                        }
                    } else if (state.lfoLearnActive || state.macroLearnActive) {
                        detectTapGestures {
                            if (parameterId != -1) {
                                if (state.lfoLearnActive) {
                                    val lfoIdx = state.lfoLearnLfoIndex
                                    if (lfoIdx != -1) {
                                        // LFO1=2, LFO2=3... LFO5=6
                                        nativeLib.setRouting(latestState.selectedTrackIndex, -1, 2 + lfoIdx, 5, 1.0f, parameterId)
                                        val newLfos = state.lfos.toMutableList()
                                        newLfos[lfoIdx] = newLfos[lfoIdx].copy(targetType = 1, targetId = parameterId, targetLabel = label)
                                        latestOnStateChange(latestState.copy(lfos = newLfos, lfoLearnActive = false))
                                    }
                                } else if (state.macroLearnActive) {
                                    val macroIdx = state.macroLearnMacroIndex
                                    val tIdx = state.macroLearnTargetIndex
                                    if (macroIdx != -1 && tIdx != -1) {
                                         // Macro1=9, Macro2=10...
                                         nativeLib.setRouting(latestState.selectedTrackIndex, -1, 9 + macroIdx, 5, 1.0f, parameterId)
                                         val newMacros = state.macros.toMutableList()
                                         val currentTargets = newMacros[macroIdx].targets.toMutableList()
                                         if (tIdx < currentTargets.size) {
                                             currentTargets[tIdx] = currentTargets[tIdx].copy(targetId = parameterId, targetLabel = label)
                                             newMacros[macroIdx] = newMacros[macroIdx].copy(targets = currentTargets)
                                             latestOnStateChange(latestState.copy(macros = newMacros, macroLearnActive = false))
                                         }
                                    }
                                }
                            }
                        }
                    } else {
                        forEachGesture {
                            awaitPointerEventScope {
                                val down = awaitFirstDown()
                                isHeld = true
                                var lastY = down.position.y
                                
                                val getValStr: (Float) -> String = { v ->
                                    if (valueFormatter != null) {
                                        valueFormatter(v)
                                    } else if (parameterId != -1 && (parameterId in 100..103 || parameterId in 150..152)) { // ADSR/Filter special handling
                                        "%.2f".format(v)
                                    } else if (label == "MODE" && (parameterId == 320 || parameterId == 1531)) {
                                        val modeIdx = (v * 2.9f).toInt()
                                        when(modeIdx) {
                                            0 -> "1 SHOT"
                                            1 -> "SUSTAIN"
                                            else -> "CHOP"
                                        }
                                    } else {
                                        val upperLabel = label.uppercase()
                                        when {
                                            parameterId == -1 -> String.format("%.2f", v)
                                        upperLabel == "MODE" -> {
                                            val modeIdx = (v * 11.9f).toInt()
                                            when(modeIdx) {
                                                0 -> "UP"
                                                1 -> "UP+"
                                                2 -> "DOWN"
                                                3 -> "SUB+"
                                                4 -> "DUAL"
                                                5 -> "5TH"
                                                6 -> "MAJ"
                                                7 -> "MIN"
                                                8 -> "DIM"
                                                9 -> "AUG"
                                                10 -> "SUS"
                                                else -> "ALL"
                                            }
                                        }
                                        upperLabel == "SHAPE" || upperLabel == "SHP" -> {
                                             if (v < 0.2f) "SINE" 
                                             else if (v < 0.4f) "TRI" 
                                             else if (v < 0.6f) "SAW" 
                                             else if (v < 0.8f) "SQR" 
                                             else "RND"
                                        }
                                        upperLabel == "BPM" -> String.format("BPM: %.1f", 12f + (v * 228f))
                                        upperLabel == "ALGO" || upperLabel == "ROUTING" -> {
                                           val algo = (v * 3f).toInt() + 1
                                           val name = when(algo) {
                                               1 -> "PIANO"
                                               2 -> "ORGAN"
                                               3 -> "BRASS"
                                               else -> "SERIAL"
                                           }
                                           "ALGO: $name"
                                        }
                                        upperLabel == "SLICES" -> "SLICES: ${(v * 14f).toInt() + 2}"
                                        upperLabel == "WAVE" || upperLabel == "WAVETABLE" || upperLabel == "MORPH" -> {
                                           if (v < 0.25f) "SINE" else if (v < 0.5f) "TRI" else if (v < 0.75f) "SQU" else "SAW"
                                        }
                                        else -> String.format("%s: %.2f", upperLabel, v)
                                    }
                                 }
                                }
                                
                                isHeld = true
                                localFocusedSetter(getValStr(value))

                                do {
                                    val event = awaitPointerEvent()
                                    val dragAmount = lastY - event.changes.first().position.y
                                    lastY = event.changes.first().position.y
                                    
                                    val sensitivity = 0.005f
                                    var rawNextValue = (value + dragAmount * sensitivity)
                                    
                                    // Snap to center (0.5) for Pan/Balance knobs (approx 10% deadzone)
                                    val upperLabel = label.uppercase()
                                    val nextValue = if (upperLabel == "PAN" || upperLabel == "P" || upperLabel == "BALANCE" || upperLabel == "BAL") {
                                        if (rawNextValue in 0.45f..0.55f) 0.5f else rawNextValue.coerceIn(0f, 1f)
                                    } else {
                                        rawNextValue.coerceIn(0f, 1f)
                                    }
                                    
                                    if (nextValue != value) {
                                        value = nextValue
                                        val valStr = getValStr(value)
                                        
                                        // Update local visual feedback immediately (Performance!)
                                        localFocusedSetter(valStr)

                                        if (latestOnValueChangeOverride != null) {
                                            latestOnValueChangeOverride!!(nextValue)
                                        } else if (parameterId != -1) {
                                            val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                            val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                            if (isLockingMode) {
                                                nativeLib.setParameterPreview(trackIndex, parameterId, nextValue)
                                            } else {
                                                nativeLib.setParameter(trackIndex, parameterId, nextValue)
                                            }
                                            
                                            // Trigger debounced state update for everything else
                                            
                                            // For better real-time FEEL without blocking UI:
                                            // Only push FULL state if locking or on end of gesture
                                            if (isAutoLocking || latestState.isParameterLocking) {
                                                 val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                                 if (targetIdx != null) {
                                                     nativeLib.setParameterLock(trackIndex, targetIdx, parameterId, nextValue)
                                                 }
                                                 
                                                 val newState = latestState.copy(
                                                     focusedValue = valStr,
                                                     tracks = latestState.tracks.mapIndexed { tIdx, t ->
                                                         if (tIdx == trackIndex) {
                                                             val sIdx = targetIdx ?: -1
                                                             if (sIdx != -1) {
                                                                 if (t.engineType == EngineType.FM_DRUM) {
                                                                     val inst = t.selectedFmDrumInstrument
                                                                     t.copy(drumSteps = t.drumSteps.mapIndexed { di, ds ->
                                                                         if (di == inst) ds.mapIndexed { si, s ->
                                                                             if (si == sIdx) s.copy(parameterLocks = s.parameterLocks + (parameterId to nextValue))
                                                                             else s
                                                                         } else ds
                                                                     })
                                                                 } else {
                                                                     t.copy(steps = t.steps.mapIndexed { si, s ->
                                                                         if (si == sIdx) s.copy(parameterLocks = s.parameterLocks + (parameterId to nextValue))
                                                                         else s
                                                                     })
                                                                 }
                                                             } else t
                                                         } else t
                                                     }
                                                 )
                                                 latestOnStateChange(newState)
                                            }
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                                
                                isHeld = false
                                localFocusedSetter(null)
                                
                                // Finalize state on release
                                if (parameterId != -1) {
                                    val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                    val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                    
                                    if (!isLockingMode) {
                                        val finalValue = value
                                        latestOnStateChange(latestState.copy(
                                            focusedValue = null,
                                            tracks = latestState.tracks.mapIndexed { idx, t ->
                                                if (idx == trackIndex) t.copy(parameters = t.parameters + (parameterId to finalValue))
                                                else t
                                            }
                                        ))
                                    } else {
                                        latestOnStateChange(latestState.copy(focusedValue = null))
                                    }
                                } else {
                                    latestOnStateChange(latestState.copy(focusedValue = null))
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Knob Visuals
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                val radius = knobSize.toPx() / 2 - 8.dp.toPx()
                val center = Offset(size.width / 2, size.height / 2)
                
                if (isBold) {
                    // Bold Style: Solid colored knob with white indicator
                    drawCircle(
                        color = engineColor,
                        radius = radius + 4.dp.toPx(),
                        center = center
                    )
                    
                    val angle = 135f + (value * 270f)
                    val angleRad = Math.toRadians(angle.toDouble())
                    val indicatorLen = radius + 2.dp.toPx()
                    val indicatorX = center.x + Math.cos(angleRad).toFloat() * indicatorLen
                    val indicatorY = center.y + Math.sin(angleRad).toFloat() * indicatorLen
                    
                    drawLine(
                        color = Color.White,
                        start = center,
                        end = Offset(indicatorX, indicatorY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    
                    // Subtle 3D effect
                    drawCircle(
                        color = Color.White.copy(alpha = 0.2f),
                        radius = radius + 4.dp.toPx(),
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                    )
                } else {
                    // Standard Style: Gray knob with colored arc
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.3f),
                        radius = radius,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                    )

                    // Detent visualization for bipolar knobs
                    val upperLabel = label.uppercase()
                    if (upperLabel == "PAN" || upperLabel == "P" || upperLabel == "BALANCE" || upperLabel == "BAL") {
                        val detentLen = 6.dp.toPx()
                        drawLine(
                            color = Color.White.copy(alpha = 0.4f),
                            start = Offset(center.x, center.y - radius - 2.dp.toPx()),
                            end = Offset(center.x, center.y - radius + detentLen),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                    
                    // Value Arc
                    drawArc(
                        color = engineColor,
                        startAngle = 135f,
                        sweepAngle = value * 270f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Marker dot if locked specifically on target
                if (isLockedOnTarget) {
                    drawCircle(
                        color = Color.Magenta,
                        radius = 4.dp.toPx(),
                        center = center
                    )
                }
            }
            
            // Indicator dot if parameter has ANY locks in the sequence
            if (hasLocks) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(6.dp)
                        .background(Color.Magenta, CircleShape)
                )
            }
            
            val displayText = if (valueFormatter != null) {
                valueFormatter(value)
            } else when {
                label.uppercase() == "MODE" -> {
                    if (parameterId == 320) { // Sampler Mode
                         val modeIdx = (value * 2.9f).toInt()
                         when(modeIdx) {
                             0 -> "1 SHOT"
                             1 -> "SUSTAIN"
                             else -> "CHOP"
                         }
                    } else { // Octaver Mode
                        val modeIdx = (value * 11.9f).toInt()
                        when(modeIdx) {
                            0 -> "UP"
                            1 -> "UP+"
                            2 -> "DOWN"
                            3 -> "SUB+"
                            4 -> "DUAL"
                            5 -> "PWR"
                            6 -> "MAJ"
                            7 -> "MIN"
                            8 -> "DIM"
                            9 -> "AUG"
                            10 -> "SUS"
                            else -> "ALL"
                        }
                    }
                }
                label.uppercase().startsWith("SHP") || label.uppercase().contains("SHAPE") -> { // LFO / Osc Shapes
                     if (value < 0.2f) "SINE" 
                     else if (value < 0.4f) "TRI" 
                     else if (value < 0.6f) "SAW" 
                     else if (value < 0.8f) "SQR" 
                     else "RND"
                }
                label.uppercase().startsWith("MIDI") || label.uppercase().contains("CHANNEL") || label.uppercase() == "CH" -> {
                    val ch = (value * 17.0f).toInt()
                    when(ch) {
                        0 -> "NONE"
                        17 -> "ALL"
                        else -> "$ch"
                    }
                }
                label.uppercase() == "BPM" -> "${(12f + (value * 228f)).toInt()}"
                label.uppercase() == "ALGO" || label.uppercase() == "ROUTING" -> {
                    val algo = (value * 3f).toInt() + 1
                    when(algo) {
                        1 -> "PIANO"
                        2 -> "ORGAN"
                        3 -> "BRASS"
                        else -> "SERIAL"
                    }
                }
                label.uppercase() == "SLICES" -> "${(value * 14f).toInt() + 2}"
                label.uppercase() == "WAVE" || label.uppercase() == "WAVETABLE" || label.uppercase() == "MORPH" -> {
                    if (value < 0.25f) "SINE" else if (value < 0.5f) "TRI" else if (value < 0.75f) "SQU" else "SAW"
                }
                else -> "${(value * 100).toInt()}"
            }

            if (showValue) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isHeld) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
        }
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (isHeld) engineColor else Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
fun SequencingScreen(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, empledManager: EmpledManager) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    Column(modifier = Modifier.fillMaxSize()) {
        // Main UI Components
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Main Sequencing Area
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
            val selectedTrackIndex = state.selectedTrackIndex
            val track = state.tracks[selectedTrackIndex]
            // Fix float comparison: check if parameter 320 (Sampler Mode) is CHOP (>0.6)
            val isSamplerChops = track.engineType == EngineType.SAMPLER && (track.parameters[320] ?: 0f) > 0.6f
            val isDrum = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM
            val isMultiTrack = isDrum || isSamplerChops
    
                Row(modifier = Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    // Bank Selection
                    Row(modifier = Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("A", "B", "C", "D").forEachIndexed { i, label ->
                            Button(
                                onClick = { latestOnStateChange(latestState.copy(currentSequencerBank = i, is64StepView = false)) },
                                modifier = Modifier.width(42.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!latestState.is64StepView && latestState.currentSequencerBank == i) getEngineColor(track.engineType) 
                                    else when(i) {
                                        0 -> Color(0xFF222222)
                                        1 -> Color(0xFF333333)
                                        2 -> Color(0xFF444444)
                                        3 -> Color(0xFF555555)
                                        else -> Color.DarkGray
                                    }
                                )
                            ) { Text(label, color = if (!latestState.is64StepView && latestState.currentSequencerBank == i) Color.Black else Color.White, fontSize = 12.sp) }
                        }

                        // 64 Step Grid Button
                        Button(
                            onClick = { 
                                latestOnStateChange(latestState.copy(is64StepView = true, currentSequencerBank = 0, patternLength = 64)) 
                                nativeLib.setPatternLength(64)
                            },
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (latestState.is64StepView) getEngineColor(track.engineType) else Color(0xFF222222)
                            )
                        ) {
                            Text("64", fontSize = 12.sp, color = if (latestState.is64StepView) Color.Black else Color.White)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // COPY
                        val currentContext = androidx.compose.ui.platform.LocalContext.current
                        Button(
                            onClick = {
                                val bankStart = latestState.currentSequencerBank * 16
                                if (isMultiTrack) {
                                    val currentSteps = track.drumSteps.map { it.subList(bankStart, bankStart + 16) }
                                    latestOnStateChange(latestState.copy(copiedDrumSteps = currentSteps, copiedSteps = null))
                                } else {
                                    val currentSteps = track.steps.subList(bankStart, bankStart + 16)
                                    latestOnStateChange(latestState.copy(copiedSteps = currentSteps, copiedDrumSteps = null))
                                }
                                android.widget.Toast.makeText(currentContext, "Page Copied", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.width(55.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f))
                        ) { Text("COPY", fontSize = 10.sp, color = Color.White) }

                        // PASTE
                        Button(
                            onClick = {
                                val bankStart = latestState.currentSequencerBank * 16
                                val copiedDrumSteps = latestState.copiedDrumSteps
                                if (isMultiTrack && copiedDrumSteps != null) {
                                    val newDrumSteps = track.drumSteps.mapIndexed { di, dsteps ->
                                        val copied = copiedDrumSteps.getOrNull(di)
                                        if (copied != null) {
                                            dsteps.toMutableList().apply {
                                                for (idx in 0 until 16) {
                                                    this[bankStart + idx] = copied[idx]
                                                    val s = copied[idx]
                                                    nativeLib.setStep(selectedTrackIndex, bankStart + idx, s.active, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped)
                                                }
                                            }
                                        } else dsteps
                                    }
                                    latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { i, t -> if (i == selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                } else if (!isMultiTrack && latestState.copiedSteps != null) {
                                    val newSteps = track.steps.toMutableList().apply {
                                        for (idx in 0 until 16) {
                                            this[bankStart + idx] = latestState.copiedSteps!![idx]
                                            val s = latestState.copiedSteps!![idx]
                                            nativeLib.setStep(selectedTrackIndex, bankStart + idx, s.active, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, s.isSkipped)
                                        }
                                    }
                                    latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { i, t -> if (i == selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                }
                            },
                            modifier = Modifier.width(55.dp),
                            contentPadding = PaddingValues(0.dp),
                            enabled = (isMultiTrack && latestState.copiedDrumSteps != null) || (!isMultiTrack && latestState.copiedSteps != null),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f))
                        ) { Text("PASTE", fontSize = 10.sp, color = Color.White) }
                    }
                    
                    // Engine Label and Clock Divider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                         Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             Text("TRACK ${selectedTrackIndex + 1}: ${track.engineType.name}", style = MaterialTheme.typography.labelMedium, color = getEngineColor(track.engineType))
                             Spacer(modifier = Modifier.height(2.dp))
                             Text("CLOCK DIV", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 9.sp)
                         }
                         
                         Spacer(modifier = Modifier.width(16.dp))
                         
                         // Clock Divider Knob
                         val multipliers = listOf(0.5f, 0.666f, 0.75f, 0.8f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
                         val labels = listOf("1/2", "2/3", "3/4", "4/5", "1/1", "1.25x", "1.5x", "2x", "3x")
                         
                         key(selectedTrackIndex) {
                             Knob(
                                 label = "",
                                 initialValue = 0.5f,
                                 parameterId = -3,
                                 state = state,
                                 onStateChange = onStateChange,
                                 nativeLib = nativeLib,
                                 knobSize = 36.dp,
                                 overrideValue = let {
                                     val idx = multipliers.indexOfFirst { abs(it - track.clockMultiplier) < 0.01f }
                                     if (idx != -1) idx.toFloat() / (multipliers.size - 1) else 0.5f
                                 },
                                 valueFormatter = { v ->
                                     val idx = (v * (multipliers.size - 1) + 0.5f).toInt()
                                     labels.getOrElse(idx) { "1/1" }
                                 },
                                 onValueChangeOverride = { newVal ->
                                     val idx = (newVal * (multipliers.size - 1) + 0.5f).toInt()
                                     val m = multipliers[idx]
                                     if (abs(m - track.clockMultiplier) > 0.01f) {
                                         val newTracks = latestState.tracks.mapIndexed { idx, t -> 
                                             if (idx == selectedTrackIndex) t.copy(clockMultiplier = m) else t 
                                         }
                                         latestOnStateChange(latestState.copy(tracks = newTracks))
                                         nativeLib.setClockMultiplier(selectedTrackIndex, m)
                                     }
                                 }
                             )
                         }

                         Spacer(modifier = Modifier.width(16.dp))
                         var clearAllProgress by remember { mutableStateOf(0f) }
                         var showClearAllConfirm by remember { mutableStateOf(false) }
                         
                         if (showClearAllConfirm) {
                             AlertDialog(
                                 onDismissRequest = { showClearAllConfirm = false },
                                 title = { Text("Clear All Sequences?") },
                                 text = { Text("This will permanently erase all steps across all tracks.") },
                                 confirmButton = {
                                     Button(onClick = {
                                         for (i in 0 until 8) {
                                             nativeLib.clearSequencer(i)
                                         }
                                         val newTracks = latestState.tracks.map { t ->
                                             t.copy(
                                                 steps = List(64) { StepState() },
                                                 drumSteps = List(16) { List(64) { StepState() } }
                                             )
                                         }
                                         latestOnStateChange(latestState.copy(tracks = newTracks))
                                         showClearAllConfirm = false
                                     }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("CLEAR ALL") }
                                 },
                                 dismissButton = {
                                     Button(onClick = { showClearAllConfirm = false }) { Text("CANCEL") }
                                 }
                             )
                         }

                         var isClearPressed by remember { mutableStateOf(false) }
                         Box(
                             modifier = Modifier
                                 .height(32.dp)
                                 .width(70.dp)
                                 .clip(RoundedCornerShape(4.dp))
                                 .background(if (isClearPressed) Color.Red else Color.Red.copy(alpha = 0.3f))
                                 .pointerInput(selectedTrackIndex) {
                                     awaitPointerEventScope {
                                         while (true) {
                                             val down = awaitFirstDown()
                                             val startTime = System.currentTimeMillis()
                                             while (true) {
                                                 val event = withTimeoutOrNull(40) { awaitPointerEvent() }
                                                 val elapsed = System.currentTimeMillis() - startTime
                                                 if (elapsed >= 1000) {
                                                     showClearAllConfirm = true
                                                     break
                                                 }
                                                 if (event != null && event.changes.any { !it.pressed }) {
                                                     nativeLib.clearSequencer(selectedTrackIndex)
                                                     val newTracks = latestState.tracks.mapIndexed { idx, t ->
                                                         if (idx == selectedTrackIndex) {
                                                             t.copy(steps = List(64) { StepState() }, drumSteps = List(16) { List(64) { StepState() } })
                                                         } else t
                                                     }
                                                     latestOnStateChange(latestState.copy(tracks = newTracks))
                                                     break
                                                 }
                                             }
                                             isClearPressed = false
                                             while (true) {
                                                 val event = withTimeoutOrNull(100) { awaitPointerEvent() }
                                                 if (event == null || event.changes.all { !it.pressed }) break
                                             }
                                         }
                                     }
                                 },
                             contentAlignment = Alignment.Center
                         ) {
                             Text("CLEAR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White)
                         }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (track.engineType == EngineType.FM_DRUM) {
                    Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("KICK", "SNARE", "TOM", "HH", "OHH", "CYMB", "PERC", "NOISE").forEachIndexed { i, label ->
                            Button(
                                onClick = { 
                                    latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> 
                                        if (idx == selectedTrackIndex) t.copy(selectedFmDrumInstrument = i) else t 
                                    })) 
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (track.selectedFmDrumInstrument == i) getEngineColor(track.engineType) else Color.DarkGray)
                            ) { Text(label, style = MaterialTheme.typography.labelSmall, color = if (track.selectedFmDrumInstrument == i) Color.Black else Color.White) }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (track.engineType == EngineType.ANALOG_DRUM) {
                     Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("KICK", "SNARE", "OHH", "CHH", "CLAP").forEachIndexed { i, label ->
                            Button(
                                onClick = { 
                                    latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> 
                                        if (idx == selectedTrackIndex) t.copy(selectedFmDrumInstrument = i) else t 
                                    })) 
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (track.selectedFmDrumInstrument == i) getEngineColor(track.engineType) else Color.DarkGray)
                            ) { Text(label, style = MaterialTheme.typography.labelSmall, color = if (track.selectedFmDrumInstrument == i) Color.Black else Color.White) }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (isSamplerChops) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                         val buttonColors = ButtonDefaults.buttonColors(containerColor = getEngineColor(track.engineType))
                         val inactiveColors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                         
                         // Rows of 8
                         for (row in 0..1) {
                             Row(modifier = Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                 for (col in 0..7) {
                                     val i = row * 8 + col
                                     Button(
                                         onClick = { 
                                            latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> 
                                                if (idx == selectedTrackIndex) t.copy(selectedFmDrumInstrument = i) else t 
                                            })) 
                                         },
                                         modifier = Modifier.weight(1f),
                                         contentPadding = PaddingValues(0.dp),
                                         colors = if (track.selectedFmDrumInstrument == i) buttonColors else inactiveColors
                                     ) { 
                                         Text("S${i + 1}", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = if (track.selectedFmDrumInstrument == i) Color.Black else Color.White) 
                                     }
                                 }
                             }
                             if (row == 0) Spacer(modifier = Modifier.height(4.dp))
                         }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 16 Step Pads or 64 Step Grid
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints {
                        val is64 = latestState.is64StepView
                        val columns = if (is64) 8 else 4
                        val padCount = if (is64) 64 else 16
                        val spacing = if (is64) 4.dp else 8.dp
                        val padSize = minOf(maxWidth / (columns + 0.2f), maxHeight / (columns + 0.2f))
                        
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.size(padSize * (columns + 0.2f)),
                            verticalArrangement = Arrangement.spacedBy(spacing),
                            horizontalArrangement = Arrangement.spacedBy(spacing)
                        ) {
                            items(padCount, key = { i -> "${state.selectedTrackIndex}_${track.selectedFmDrumInstrument}_${state.currentSequencerBank}_${state.is64StepView}_$i" }) { i ->
                                val stepIndex = if (is64) i else i + (state.currentSequencerBank * 16)
                                if (stepIndex >= 64) return@items

                                val step = if (isMultiTrack) track.drumSteps[track.selectedFmDrumInstrument][stepIndex] else track.steps[stepIndex]
                                
                                var showStepPopup by remember { mutableStateOf(false) }
                                
                                val engineColor = getEngineColor(track.engineType)
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(
                                            when {
                                                step.isSkipped -> Color.Black
                                                step.active -> engineColor
                                                else -> androidx.compose.ui.graphics.lerp(Color.DarkGray, engineColor, 0.2f)
                                            }, 
                                            RoundedCornerShape(if (is64) 4.dp else 8.dp)
                                        )
                                        .then(
                                            if (latestState.isPlaying && latestState.currentStep == stepIndex) {
                                                Modifier.border(2.dp, Color.White, RoundedCornerShape(if (is64) 4.dp else 8.dp))
                                            } else Modifier
                                        )
                                        .pointerInput(i, state.currentSequencerBank, state.selectedTrackIndex, track.selectedFmDrumInstrument, is64) {
                                            detectTapGestures(
                                                onTap = {
                                                    if (step.isSkipped) return@detectTapGestures
                                                    val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
                                                    val currentStep = if (isMultiTrack) currentTrack.drumSteps[currentTrack.selectedFmDrumInstrument][stepIndex] else currentTrack.steps[stepIndex]
                                                    val newActive = !currentStep.active
                                                    
                                                    if (isMultiTrack) {
                                                        val instIdx = currentTrack.selectedFmDrumInstrument
                                                        val drumNote = 60 + instIdx
                                                        val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(drumNote) else currentStep.notes
                                                        val newDrumSteps = currentTrack.drumSteps.mapIndexed { di, dsteps ->
                                                            if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIndex) s.copy(active = newActive, notes = finalNotes) else s }
                                                            else dsteps
                                                        }
                                                        latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == latestState.selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                                        nativeLib.setStep(latestState.selectedTrackIndex, stepIndex, newActive, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped)
                                                    } else {
                                                        val rootNote = 60
                                                        val finalNotes = if (newActive && currentStep.notes.isEmpty()) listOf(rootNote) else currentStep.notes
                                                        val newSteps = currentTrack.steps.mapIndexed { si, s -> if (si == stepIndex) s.copy(active = newActive, notes = finalNotes) else s }
                                                        latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == latestState.selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                                        val isActiveWithNotes = newActive && finalNotes.isNotEmpty()
                                                        nativeLib.setStep(latestState.selectedTrackIndex, stepIndex, isActiveWithNotes, finalNotes.toIntArray(), currentStep.velocity, currentStep.ratchet, currentStep.punch, currentStep.probability, currentStep.gate, currentStep.isSkipped)
                                                    }
                                                },
                                                onLongPress = { 
                                                    if (step.isSkipped) {
                                                        // RESTORE STEP
                                                        val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
                                                        if (isMultiTrack) {
                                                            val instIdx = currentTrack.selectedFmDrumInstrument
                                                            val newDrumSteps = currentTrack.drumSteps.mapIndexed { di, dsteps ->
                                                                if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIndex) s.copy(isSkipped = false) else s }
                                                                else dsteps
                                                            }
                                                            latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == latestState.selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                                            val s = newDrumSteps[instIdx][stepIndex]
                                                            nativeLib.setStep(latestState.selectedTrackIndex, stepIndex, s.active, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, false)
                                                        } else {
                                                            val newSteps = currentTrack.steps.mapIndexed { si, s -> if (si == stepIndex) s.copy(isSkipped = false) else s }
                                                            latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == latestState.selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                                            val s = newSteps[stepIndex]
                                                            nativeLib.setStep(latestState.selectedTrackIndex, stepIndex, s.active, s.notes.toIntArray(), s.velocity, s.ratchet, s.punch, s.probability, s.gate, false)
                                                        }
                                                    } else {
                                                        showStepPopup = true 
                                                    }
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!is64 && !step.isSkipped) {
                                        Text("${stepIndex + 1}", color = if (step.active) Color.Black else Color.White)
                                    }
                                    if (showStepPopup) {
                                        PadOptionPopup(
                                            onDismiss = { showStepPopup = false },
                                            stepState = step,
                                             onApply = { ratchet: Int, punch: Boolean, probability: Float, gate: Float, notes: List<Int>, velocity: Float, isSkipped: Boolean, parameterLocks: Map<Int, Float> ->
                                                val currentTrack = latestState.tracks[latestState.selectedTrackIndex]
                                                val currentStep = if (isMultiTrack) currentTrack.drumSteps[currentTrack.selectedFmDrumInstrument][stepIndex] else currentTrack.steps[stepIndex]
                                                if (isMultiTrack) {
                                                    val instIdx = currentTrack.selectedFmDrumInstrument
                                                    val newDrumSteps = currentTrack.drumSteps.mapIndexed { di, dsteps ->
                                                        if (di == instIdx) dsteps.mapIndexed { si, s -> if (si == stepIndex) s.copy(ratchet = ratchet, punch = punch, probability = probability, gate = gate, velocity = velocity, notes = notes, isSkipped = isSkipped, parameterLocks = parameterLocks) else s }
                                                        else dsteps
                                                    }
                                                    latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == latestState.selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                                    nativeLib.setStep(latestState.selectedTrackIndex, stepIndex, currentStep.active, notes.toIntArray(), velocity, ratchet, punch, probability, gate, isSkipped)
                                                } else {
                                                    val newSteps = currentTrack.steps.mapIndexed { si, s -> if (si == stepIndex) s.copy(ratchet = ratchet, punch = punch, probability = probability, gate = gate, notes = notes, velocity = velocity, isSkipped = isSkipped, parameterLocks = parameterLocks) else s }
                                                    latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == latestState.selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                                    nativeLib.setStep(latestState.selectedTrackIndex, stepIndex, currentStep.active, notes.toIntArray(), velocity, ratchet, punch, probability, gate, isSkipped)
                                                }
                                            },
                                            onParamLock = {
                                                latestOnStateChange(latestState.copy(isParameterLocking = true, lockingTarget = latestState.selectedTrackIndex to stepIndex))
                                                showStepPopup = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayingPad(
    padIndex: Int,
    note: Int,
    padSize: Dp,
    padColor: Color,
    isPlaying: Boolean,
    currentStep: Int,
    nativeLib: NativeLib,
    latestState: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    empledManager: EmpledManager? = null,
    isChopMode: Boolean = false,
    isNoteActive: Boolean = false
) {
                // Fix for stale state capture: Maintain reference to the most recent state
                val currentState by rememberUpdatedState(latestState)
                var isLocallyPressed by remember { mutableStateOf(false) }
                // Visual state follows local press but with release delay
                var isVisuallyPressed by remember { mutableStateOf(false) }
                
                LaunchedEffect(isLocallyPressed) {
                    if (isLocallyPressed) {
                        isVisuallyPressed = true
                    } else {
                        // User requested ~100ms highlight tail
                        delay(100)
                        isVisuallyPressed = false
                    }
                }

                val isHeld = latestState.heldNotes.contains(note)
                val isMidiTriggered = latestState.lastMidiNote == note && latestState.lastMidiVelocity > 0
                
                // LED Sync for EMP16
                LaunchedEffect(isVisuallyPressed, isHeld, isMidiTriggered, isPlaying, currentStep, latestState.currentSequencerBank) {
                    if (latestState.currentSequencerBank == 0) {
                        val finalColor = if (isVisuallyPressed || isHeld || isMidiTriggered) padColor 
                                        else if (isPlaying && (currentStep % 16) == padIndex) androidx.compose.ui.graphics.Color.White
                                        else padColor.copy(alpha = 0.2f)
                        
                        empledManager?.updatePadColorCompose(padIndex, finalColor)
                    }
                }

                val cols = if (latestState.is6x6Grid) 6 else 4
                val row = padIndex / cols
                val col = padIndex % cols
                val isPlayheadHighlight = if (latestState.is6x6Grid) {
                    // Map playhead to the top-left 4x4 grid of the 6x6 (representing first 16 steps)
                    row < 4 && col < 4 && (currentStep % 16) == (row * 4 + col)
                } else {
                    currentStep % 16 == padIndex
                }

                Box(
                    modifier = Modifier
                        .size(padSize)
                        .background(
                            if (isVisuallyPressed || isHeld || isMidiTriggered || isNoteActive) padColor.copy(alpha = 0.9f)
                            else if (isPlaying && isPlayheadHighlight) Color.White.copy(alpha = 0.3f)
                            else if (latestState.tracks[latestState.selectedTrackIndex].engineType == EngineType.FM_DRUM && 
                                     latestState.tracks[latestState.selectedTrackIndex].selectedFmDrumInstrument == (note - 60)) padColor.copy(alpha = 0.2f)
                            else Color.Transparent, 
                            RoundedCornerShape(8.dp)
                        )
                        .border(1.5.dp, padColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    // Redundant TopStart label removed as per v1.3.1 request
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(padIndex, note, block = {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    isLocallyPressed = true
                                    
                                    val currentTIdx = currentState.selectedTrackIndex
                                    val currentBank = currentState.currentSequencerBank
                                    val triggeredNote = note // Capture for finally block
                                    
                                    try {
                                        if (currentState.jumpModeWaitingForTap) {
                                            nativeLib.jumpToStep(currentBank * 16 + padIndex)
                                            onStateChange(currentState.copy(jumpModeWaitingForTap = false))
                                        } else {
                                            nativeLib.triggerNote(currentTIdx, triggeredNote, 100)
                                            // onStateChange removed to prevent race condition with UI stickiness
                                            // Visual feedback is now handled via local isLocallyPressed state
                                            
                                            if (currentState.isRecording) {
                                                val stepIdx = if (currentState.isPlaying) currentState.currentStep else (currentBank * 16 + padIndex) % 64
                                                val currentTrack = currentState.tracks[currentTIdx]
                                                if (currentTrack.engineType == EngineType.FM_DRUM) {
                                                    val drumIdx = triggeredNote - 60
                                                    if (drumIdx in 0..7) {
                                                        val newDrumSteps = currentTrack.drumSteps.mapIndexed { idx, steps ->
                                                            if (idx == drumIdx) steps.mapIndexed { sIdx, s -> if (sIdx == stepIdx) s.copy(active = true, notes = listOf(triggeredNote)) else s }
                                                            else steps
                                                        }
                                                        onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentTIdx) t.copy(drumSteps = newDrumSteps) else t }))
                                                        nativeLib.setStep(currentTIdx, stepIdx, true, intArrayOf(triggeredNote), 0.8f, 1, false, 1.0f, 1.0f, false)
                                                    }
                                                } else {
                                                    val newSteps = currentTrack.steps.mapIndexed { sIdx, s -> if (sIdx == stepIdx) s.copy(active = true, notes = (s.notes + triggeredNote).distinct()) else s }
                                                    onStateChange(currentState.copy(tracks = currentState.tracks.mapIndexed { idx, t -> if (idx == currentTIdx) t.copy(steps = newSteps) else t }))
                                                    nativeLib.setStep(currentTIdx, stepIdx, true, intArrayOf(triggeredNote), 0.8f, 1, false, 1.0f, 1.0f, false)
                                                }
                                            }
                                        }
                                        waitForUpOrCancellation()
                                    } finally {
                                        isLocallyPressed = false
                                        if (!currentState.jumpModeWaitingForTap) {
                                            nativeLib.releaseNote(currentTIdx, triggeredNote)
                                        }
                                    }
                                }
                            }
                        })
                    ,
                    contentAlignment = Alignment.Center
                ) {
        val label = when (latestState.tracks[latestState.selectedTrackIndex].engineType) {
            EngineType.FM_DRUM -> {
                val names = listOf("Kick", "Snare", "Tom", "HH", "OHH", "CYMB", "PERC", "NOISE")
                names.getOrElse(note - 60) { "Pad $padIndex" }
            }
            EngineType.ANALOG_DRUM -> {
                val drumMap = mapOf(
                    60 to "Kick",
                    62 to "Snare",
                    64 to "Cymbal",
                    66 to "Hat",
                    67 to "Open"
                )
                drumMap[note] ?: "Pad $padIndex"
            }
            else -> if (isChopMode) "${padIndex + 1}" else getNoteLabel(note)
        }
                            if (!isChopMode || label.isNotEmpty()) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                            }
    }
}
}





@Composable
fun PlayingScreen(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, empledManager: EmpledManager? = null, midiManager: MidiManager? = null) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val track = state.tracks[state.selectedTrackIndex]
    val isArpOn = track.arpConfig.mode != ArpMode.OFF
    val isLatched = track.arpConfig.isLatched
    val engineColor = getEngineColor(track.engineType)
    var showTransposeMenu by remember { mutableStateOf(false) }
    var showScaleMenu by remember { mutableStateOf(false) }
    var showArpMenu by remember { mutableStateOf(false) }

    
    val scaleNotes = remember(state.rootNote, state.scaleType, state.is6x6Grid) {
        val count = if (state.is6x6Grid) 36 else 16
        ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, count)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        TouchStripsPanel(state, onStateChange, nativeLib, engineColor)
        AssignableKnobsPanel(state, onStateChange, nativeLib, engineColor)
        // Removed redundant 20dp spacer to widen mixer area
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Pad Grid and Overlays Area
                // Pad Grid and Overlays Area
                val padSize = if (state.is6x6Grid) 90.dp else 135.dp
                val spacing = 8.dp
                val gridWidth = if (state.is6x6Grid) (padSize * 6) + (spacing * 5) else (padSize * 4) + (spacing * 3)

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Top Row: ROOT/SCALE buttons (Left) and LEARN button (Right)
                    Row(modifier = Modifier.width(gridWidth), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { showTransposeMenu = true },
                                    modifier = Modifier.height(40.dp).width(60.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(getNoteLabel(state.rootNote).filter { !it.isDigit() }, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                }
                                Text("ROOT", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { showScaleMenu = true },
                                    modifier = Modifier.height(40.dp).width(80.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(state.scaleType.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1, color = Color.White)
                                }
                                Text("SCALE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            
                            // Octave Buttons
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(
                                        onClick = { 
                                            val newRoot = (state.rootNote - 12).coerceAtLeast(0)
                                            onStateChange(state.copy(rootNote = newRoot))
                                            nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                                        },
                                        modifier = Modifier.size(40.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("-", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                                    
                                    Button(
                                        onClick = { 
                                            val newRoot = (state.rootNote + 12).coerceAtMost(110)
                                            onStateChange(state.copy(rootNote = newRoot))
                                            nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                                        },
                                        modifier = Modifier.size(40.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("+", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                                }
                                Text("OCTAVE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }

                            // Grid Toggle
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { onStateChange(state.copy(is6x6Grid = !state.is6x6Grid)) },
                                    modifier = Modifier.height(40.dp).width(60.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (state.is6x6Grid) Color(0xFF6200EE) else Color.DarkGray),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(if (state.is6x6Grid) "6x6" else "4x4", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                                Text("GRID", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            }

                        // Arp Toggle (New Top Row Placement)
                        val currentState by rememberUpdatedState(latestState)
                        // HIDE ARP for Drum Engines
                        if (track.engineType != EngineType.FM_DRUM && track.engineType != EngineType.ANALOG_DRUM) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val isArpOn = track.arpConfig.mode != ArpMode.OFF
                                val isLatched = track.arpConfig.isLatched
                                
                                Box(
                                    modifier = Modifier
                                        .width(80.dp).height(40.dp)
                                        .background(if (isLatched) Color.Yellow else if (isArpOn) Color.Yellow.copy(alpha = 0.3f) else Color.DarkGray, RoundedCornerShape(8.dp))
                                        .border(1.dp, if (isArpOn) Color.Yellow else Color.Transparent, RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                val t = currentState.tracks[currentState.selectedTrackIndex]
                                                val isOn = t.arpConfig.mode != ArpMode.OFF
                                                val isL = t.arpConfig.isLatched
    
                                                val (newMode, newLatched) = if (!isOn) {
                                                    Pair(ArpMode.UP, false)
                                                } else if (!isL) {
                                                    Pair(t.arpConfig.mode, true)
                                                } else {
                                                    Pair(ArpMode.OFF, false)
                                                }
    
                                                val newArpConfig = t.arpConfig.copy(mode = newMode, isLatched = newLatched)
                                                val newTracks = currentState.tracks.mapIndexed { idx, tr ->
                                                    if (idx == currentState.selectedTrackIndex) tr.copy(arpConfig = newArpConfig) else tr
                                                }
                                                latestOnStateChange(currentState.copy(tracks = newTracks))
                                                nativeLib.setArpConfig(currentState.selectedTrackIndex, newMode.ordinal, newArpConfig.octaves, newArpConfig.inversion, newLatched, newArpConfig.isMutated, newArpConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), newArpConfig.randomSequence.toIntArray())
                                            },
                                            onLongClick = { showArpMenu = true }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("ARP", color = if (isLatched) Color.Black else if (isArpOn) Color.Yellow else Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Text("ARP", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = { 
                                    if (state.midiLearnActive) {
                                        onStateChange(state.copy(midiLearnActive = false, midiLearnStep = 0, midiLearnSelectedStrip = null))
                                    } else {
                                        onStateChange(state.copy(midiLearnActive = true, midiLearnStep = 1))
                                    }
                                },
                                modifier = Modifier.height(40.dp).width(80.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (state.midiLearnActive) Color.Yellow else Color.DarkGray),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("LEARN", style = MaterialTheme.typography.labelMedium, color = if (state.midiLearnActive) Color.Black else Color.White)
                            }
                            Text("MIDI", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }

            Spacer(modifier = Modifier.height(16.dp))

            // The Pad Grid
            var activeNoteMask by remember { mutableStateOf(0) }
            LaunchedEffect(state.selectedTrackIndex, state.isPlaying) {
                while(true) {
                    activeNoteMask = nativeLib.getActiveNoteMask(state.selectedTrackIndex)
                    kotlinx.coroutines.delay(32)
                }
            }

            Box(modifier = Modifier.size(gridWidth), contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    val rows = if (state.is6x6Grid) 6 else 4
                    val cols = if (state.is6x6Grid) 6 else 4
                    
                    repeat(rows) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                            repeat(cols) { col ->
                                val padIndex = row * cols + col
                                val samplerMode = track.parameters[320] ?: 0f
                                val isChopMode = track.engineType == EngineType.SAMPLER && samplerMode >= 0.6f
                                val numSlices = if (isChopMode) (((track.parameters[340] ?: 0f) * 14f).toInt() + 2) else 0

                                val note = if (track.engineType == EngineType.FM_DRUM) {
                                    60 + (padIndex % 16)
                                } else if (isChopMode) {
                                    if (padIndex < numSlices) 60 + padIndex else -1
                                } else if (track.engineType == EngineType.ANALOG_DRUM) {
                                    val localIdx = if (padIndex >= 8) padIndex - 8 else padIndex
                                    if (localIdx < 5) {
                                        when(localIdx) {
                                            0 -> 60 // Kick
                                            1 -> 62 // Snare
                                            2 -> 64 // Cymbal
                                            3 -> 66 // Hat Closed
                                            4 -> 67 // Hat Open
                                            else -> -1
                                        }
                                    } else -1
                                } else {
                                    scaleNotes.getOrElse(padIndex) { state.rootNote + padIndex }
                                }
                                val isBlack = if (track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || isChopMode) false else isBlackKey(note)
                                val padColor = if (isBlack) androidx.compose.ui.graphics.lerp(Color.DarkGray, engineColor, 0.4f) else engineColor
                                val isNoteActive = (note >= 60 && note < 92) && ((activeNoteMask and (1 shl (note - 60))) != 0)
                                
                                Box(modifier = Modifier.size(padSize)) {
                                    if (note != -1) {
                                        val isInactiveDrumPad = (track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM) && padIndex >= 16
                                        if (!isInactiveDrumPad) {
                                            key(state.selectedTrackIndex, padIndex) {
                                                PlayingPad(
                                                    padIndex = padIndex,
                                                    note = note,
                                                    padSize = padSize,
                                                    padColor = padColor,
                                                    isPlaying = state.isPlaying,
                                                    currentStep = state.currentStep,
                                                    nativeLib = nativeLib,
                                                    latestState = latestState,
                                                    onStateChange = onStateChange,
                                                    isChopMode = isChopMode,
                                                    isNoteActive = isNoteActive
                                                )
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.size(padSize))
                                    }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
}

    // Scale Selection Bottom Sheet
    val sheetState = rememberModalBottomSheetState()
    if (showScaleMenu) {
        ModalBottomSheet(
            onDismissRequest = { showScaleMenu = false },
            sheetState = sheetState,
            containerColor = Color(0xFF111111),
            scrimColor = Color.Black.copy(alpha = 0.5f),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp) // Extra padding for system bars
            ) {
                Text(
                    "SELECT SCALE",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(ScaleType.values()) { type ->
                        val isSelected = state.scaleType == type
                        Surface(
                            onClick = {
                                onStateChange(state.copy(scaleType = type))
                                nativeLib.setScaleConfig(state.rootNote, type.intervals.toIntArray())
                                showScaleMenu = false
                            },
                            color = if (isSelected) Color.Cyan.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp),
                            border = if (isSelected) BorderStroke(1.dp, Color.Cyan) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier.padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    type.displayName.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) Color.Cyan else Color.White,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Transpose Menu
    if (showTransposeMenu) {
        androidx.compose.ui.window.Popup(
            onDismissRequest = { showTransposeMenu = false },
            alignment = Alignment.BottomStart
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                    noteNames.forEachIndexed { i, name ->
                        val currentOctave = state.rootNote / 12
                        Surface(
                            onClick = {
                                val newRoot = currentOctave * 12 + i
                                onStateChange(state.copy(rootNote = newRoot))
                                nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                                showTransposeMenu = false
                            },
                            color = if (state.rootNote % 12 == i) Color.Cyan else Color.Transparent,
                            border = if (state.rootNote % 12 == i) null else BorderStroke(1.dp, Color.Gray.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(name, style = MaterialTheme.typography.labelSmall, color = if (state.rootNote % 12 == i) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    ArpSettingsSheet(
        isOpen = showArpMenu,
        onDismiss = { showArpMenu = false },
        state = latestState,
        onStateChange = latestOnStateChange,
        nativeLib = nativeLib
    )
}

@Composable
fun TouchStripsPanel(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, engineColor: Color) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    // Sidebar Area (Strips + Transport)
    Row(
        modifier = Modifier
            .width(235.dp) // Total 235dp for wider mixer area (220 + 15)
            .fillMaxHeight()
            .padding(vertical = 16.dp, horizontal = 5.dp), // 5dp buffer
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Touch Strips Area (Side-by-side)
        Row(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
        ) {
            state.stripRoutings.forEachIndexed { i, routing ->
                if (i > 0) Spacer(modifier = Modifier.width(5.dp)) // 5dp space between ALL strips
                val stripValue = state.stripValues[i]
                val isSelectedInLearn = state.midiLearnActive && state.midiLearnStep == 2 && state.midiLearnSelectedStrip == i
                val isLearnWait = state.midiLearnActive && state.midiLearnStep == 1
                
                // Check if this strip is driving a Macro
                val assignedMacro = state.macros.find { it.sourceType == 1 && it.sourceIndex == i }
                val displayLabel = if (assignedMacro != null) assignedMacro.label.uppercase()
                                   else if (routing.parameterName == "None") "S${i+1}" 
                                   else routing.parameterName.uppercase()
                
                Column(
                    modifier = Modifier.fillMaxHeight().width(34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(displayLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, fontSize = 8.sp, color = if (assignedMacro != null) Color.Cyan else Color.Gray)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .background(
                                if (isSelectedInLearn) Color.Yellow.copy(alpha = 0.5f)
                                else if (isLearnWait) Color.Yellow.copy(alpha = 0.3f)
                                else Color.DarkGray.copy(alpha = 0.5f), 
                                RoundedCornerShape(16.dp)
                            )
                            .border(
                                if (isSelectedInLearn || isLearnWait) 2.dp else 1.dp, 
                                if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Gray.copy(alpha = 0.3f), 
                                RoundedCornerShape(16.dp)
                            )
                            .pointerInput(i, state.midiLearnActive, state.midiLearnStep, state.lfoLearnActive, state.macroLearnActive) {
                                if (state.lfoLearnActive || state.macroLearnActive) {
                                    detectTapGestures {
                                        if (state.lfoLearnActive) {
                                            val lfoIdx = state.lfoLearnLfoIndex
                                            if (lfoIdx != -1) {
                                                nativeLib.setRouting(latestState.selectedTrackIndex, -1, 2 + lfoIdx, 5, 1.0f, routing.targetId)
                                                val newLfos = state.lfos.toMutableList()
                                                newLfos[lfoIdx] = newLfos[lfoIdx].copy(targetType = 1, targetId = routing.targetId, targetLabel = routing.parameterName)
                                                latestOnStateChange(latestState.copy(lfos = newLfos, lfoLearnActive = false))
                                            }
                                        } else if (state.macroLearnActive) {
                                            val macroIdx = state.macroLearnMacroIndex
                                            val tIdx = state.macroLearnTargetIndex
                                            if (macroIdx != -1 && tIdx != -1) {
                                                 val newMacros = state.macros.toMutableList()
                                                 val currentTargets = newMacros[macroIdx].targets.toMutableList()
                                                 if (tIdx < currentTargets.size) {
                                                      nativeLib.setRouting(latestState.selectedTrackIndex, -1, 9 + macroIdx, 5, 1.0f, routing.targetId)
                                                      currentTargets[tIdx] = currentTargets[tIdx].copy(targetId = routing.targetId, targetLabel = routing.parameterName, enabled = true)
                                                      newMacros[macroIdx] = newMacros[macroIdx].copy(targets = currentTargets)
                                                      latestOnStateChange(latestState.copy(macros = newMacros, macroLearnActive = false))
                                                 }
                                            }
                                        }
                                    }
                                } else if (state.midiLearnActive && state.midiLearnStep == 1) {
                                    detectTapGestures(
                                        onTap = {
                                            latestOnStateChange(latestState.copy(midiLearnStep = 2, midiLearnSelectedStrip = i))
                                        },
                                        onLongPress = {
                                            // UN-ASSIGN: Clear routing for this strip
                                            val newRoutings = latestState.stripRoutings.map {
                                                if (it.stripIndex == i) it.copy(targetType = 0, targetId = -1, parameterName = "None")
                                                else it
                                            }
                                            latestOnStateChange(latestState.copy(stripRoutings = newRoutings))
                                        }
                                    )
                                } else {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            val delta = dragAmount.y / -200f
                                            val currentVal = latestState.stripValues[i]
                                            val newVal = (currentVal + delta).coerceIn(0f, 1f)
                                            val newValues = latestState.stripValues.toMutableList().apply { set(i, newVal) }
                                            
                                            val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                            val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                            
                                            if (routing.targetType == 1) {
                                                if (isLockingMode) {
                                                    nativeLib.setParameterPreview(latestState.selectedTrackIndex, routing.targetId, newVal)
                                                    val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                                    if (targetIdx != null) {
                                                        nativeLib.setParameterLock(latestState.selectedTrackIndex, targetIdx, routing.targetId, newVal)
                                                    }
                                                } else {
                                                    nativeLib.setParameter(latestState.selectedTrackIndex, routing.targetId, newVal)
                                                }
                                            }

                                            latestOnStateChange(latestState.copy(
                                                stripValues = newValues,
                                                focusedParameter = if (routing.targetType == 1) routing.targetId else null,
                                                focusedValue = String.format("%.2f", newVal),
                                                tracks = if (isLockingMode && routing.targetType == 1) {
                                                    val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                                     if (targetIdx != null) {
                                                        latestState.tracks.mapIndexed { tIdx, t ->
                                                            if (tIdx == latestState.selectedTrackIndex) {
                                                                if (t.engineType == EngineType.FM_DRUM) {
                                                                    val inst = t.selectedFmDrumInstrument
                                                                    t.copy(drumSteps = t.drumSteps.mapIndexed { di, ds ->
                                                                        if (di == inst) ds.mapIndexed { si, s ->
                                                                            if (si == targetIdx) s.copy(parameterLocks = s.parameterLocks + (routing.targetId to newVal))
                                                                            else s
                                                                        } else ds
                                                                    })
                                                                } else {
                                                                    t.copy(steps = t.steps.mapIndexed { si, s ->
                                                                        if (si == targetIdx) s.copy(parameterLocks = s.parameterLocks + (routing.targetId to newVal))
                                                                        else s
                                                                    })
                                                                }
                                                            } else t
                                                        }
                                                    } else latestState.tracks
                                                } else latestState.tracks
                                            ))
                                            change.consume()
                                        },
                                        onDragEnd = {
                                            val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                            val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                            if (!isLockingMode && routing.targetType == 1) {
                                                val finalVal = latestState.stripValues[i]
                                                latestOnStateChange(latestState.copy(
                                                    focusedValue = null,
                                                    tracks = latestState.tracks.mapIndexed { idx, t ->
                                                        if (idx == latestState.selectedTrackIndex) t.copy(parameters = t.parameters + (routing.targetId to finalVal))
                                                        else t
                                                    }
                                                ))
                                            } else {
                                                latestOnStateChange(latestState.copy(focusedValue = null))
                                            }
                                        },
                                        onDragCancel = {
                                            latestOnStateChange(latestState.copy(focusedValue = null))
                                        }
                                    )
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(stripValue)
                                .align(Alignment.BottomCenter)
                                .background(if (isSelectedInLearn || isLearnWait) Color.Yellow.copy(alpha = 0.6f) else engineColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        )
                        
                        Text(
                            if (routing.parameterName == "None") "STRIP ${i+1}" else routing.parameterName,
                            modifier = Modifier
                                .graphicsLayer { rotationZ = -90f }
                                .padding(bottom = 12.dp)
                                .align(Alignment.Center),
                            color = if (isSelectedInLearn || isLearnWait) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Visible,
                            softWrap = false
                        )
                    }
                    Text("${(stripValue * 100).toInt()}", style = MaterialTheme.typography.labelSmall, color = if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Gray)
                }
            }
        }
            // Transport Sidebar (Far Right) Removed for redundancy
    }
}

@Composable
fun EngineIcon(type: EngineType, modifier: Modifier = Modifier, color: Color = Color.White, drumType: String? = null) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        when {
            type == EngineType.FM_DRUM && drumType != null -> {
                when (drumType) {
                    "KICK" -> {
                        // Jagged Ring (Star-like or ZigZag circle)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radiusOuter = size.minDimension * 0.45f
                        val radiusInner = size.minDimension * 0.35f
                        val points = 16
                        
                        val path = androidx.compose.ui.graphics.Path().apply {
                            for (i in 0 until points * 2) {
                                val angle = (Math.PI * 2 * i) / (points * 2)
                                val r = if (i % 2 == 0) radiusOuter else radiusInner
                                val x = center.x + r * Math.cos(angle).toFloat()
                                val y = center.y + r * Math.sin(angle).toFloat()
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            close()
                        }
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                        
                        // Inner solid circle
                        drawCircle(color, radius = size.minDimension * 0.15f, center = center)
                    }
                    "SNARE" -> {
                        drawRect(color, size = Size(size.width, size.height * 0.4f), topLeft = Offset(0f, size.height * 0.3f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                        drawLine(color, start = Offset(0f, size.height * 0.5f), end = Offset(size.width, size.height * 0.5f), strokeWidth = 1f)
                    }
                    "HIHAT", "HIHAT OPEN" -> {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.1f, size.height * 0.7f)
                            quadraticBezierTo(size.width * 0.5f, size.height * 0.3f, size.width * 0.9f, size.height * 0.7f)
                            if (drumType == "HIHAT OPEN") {
                                moveTo(size.width * 0.2f, size.height * 0.4f)
                                lineTo(size.width * 0.8f, size.height * 0.4f)
                            }
                        }
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                    }
                    "TOM" -> {
                        drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(0f, size.height * 0.2f), size = Size(size.width, size.height * 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                        drawLine(color, start = Offset(0f, size.height * 0.5f), end = Offset(size.width, size.height * 0.5f), strokeWidth = strokeWidth)
                    }
                    "CYMBAL" -> {
                        drawArc(color, startAngle = 200f, sweepAngle = 140f, useCenter = false, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                        drawLine(color, start = center, end = Offset(center.x, size.height), strokeWidth = strokeWidth)
                    }
                    else -> {
                        // Diamond for Perc/Noise
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width / 2f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(size.width / 2f, size.height)
                            lineTo(0f, size.height / 2f)
                            close()
                        }
                        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                    }
                }
            }
            type == EngineType.SUBTRACTIVE -> {
                // Sawtooth line
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, size.height * 0.8f)
                    lineTo(size.width * 0.8f, size.height * 0.2f)
                    lineTo(size.width * 0.8f, size.height * 0.8f)
                    moveTo(size.width * 0.8f, size.height * 0.8f)
                    lineTo(size.width, size.height * 0.8f)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
            }
            type == EngineType.FM -> {
                // Overlapping Sine waves (Reverted)
                for (offset in listOf(0f, size.height * 0.2f)) {
                    val path = androidx.compose.ui.graphics.Path()
                    for (i in 0..60) {
                        val x = (i / 60f) * size.width
                        val y = (size.height / 2f + offset / 2f) + Math.sin(i * 0.3).toFloat() * (size.height * 0.2f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color.copy(alpha = if (offset == 0f) 1f else 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                }
            }
            type == EngineType.FM_DRUM && drumType == null -> {
                // Analog Drum style (Kick, Snare, Hihat) but with Jagged Ring for Kick
                val centerKick = Offset(size.width * 0.3f, size.height * 0.7f)
                val radiusOuter = size.minDimension * 0.22f
                val radiusInner = size.minDimension * 0.16f
                val points = 12

                val path = androidx.compose.ui.graphics.Path().apply {
                    for (i in 0 until points * 2) {
                        val angle = (Math.PI * 2 * i) / (points * 2)
                        val r = if (i % 2 == 0) radiusOuter else radiusInner
                        val x = centerKick.x + r * Math.cos(angle).toFloat()
                        val y = centerKick.y + r * Math.sin(angle).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))

                // Snare (Analog style)
                drawRect(color, size = Size(size.width * 0.3f, size.height * 0.15f), topLeft = Offset(size.width * 0.6f, size.height * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                // HiHat (Analog style)
                drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.3f), end = Offset(size.width * 0.5f, size.height * 0.3f), strokeWidth = strokeWidth)
                drawLine(color, start = Offset(size.width * 0.35f, size.height * 0.3f), end = Offset(size.width * 0.35f, size.height * 0.5f), strokeWidth = strokeWidth)
            }
            type == EngineType.SAMPLER -> {
                // Waveform snippet
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, size.height / 2f)
                    lineTo(size.width * 0.2f, size.height * 0.1f)
                    lineTo(size.width * 0.4f, size.height * 0.9f)
                    lineTo(size.width * 0.6f, size.height * 0.3f)
                    lineTo(size.width * 0.8f, size.height * 0.7f)
                    lineTo(size.width, size.height / 2f)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
            }
            type == EngineType.GRANULAR -> {
                // Cloud of points/lines
                val random = java.util.Random(42)
                repeat(12) {
                    val x = random.nextFloat() * size.width
                    val y = random.nextFloat() * size.height
                    drawCircle(color, radius = 2f, center = Offset(x, y))
                }
            }
            type == EngineType.WAVETABLE -> {
                // Stacked isometric lines
                repeat(3) { i ->
                    val yOff = i * 8f
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height * 0.7f - yOff)
                        lineTo(size.width * 0.4f, size.height * 0.4f - yOff)
                        lineTo(size.width, size.height * 0.6f - yOff)
                    }
                    drawPath(path, color.copy(alpha = 1f - i * 0.3f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                }
            }
            type == EngineType.ANALOG_DRUM -> {
                // Drum Kit Icon
                // Kick
                drawCircle(color, radius = size.minDimension * 0.2f, center = Offset(size.width * 0.3f, size.height * 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                // Snare
                drawRect(color, size = Size(size.width * 0.3f, size.height * 0.15f), topLeft = Offset(size.width * 0.6f, size.height * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                // HiHat
                drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.3f), end = Offset(size.width * 0.5f, size.height * 0.3f), strokeWidth = strokeWidth)
                drawLine(color, start = Offset(size.width * 0.35f, size.height * 0.3f), end = Offset(size.width * 0.35f, size.height * 0.5f), strokeWidth = strokeWidth)
            }
            type == EngineType.FM_DRUM -> { // This is the general FM_DRUM case when drumType is null
                // Percussive spike
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, size.height * 0.9f)
                    lineTo(size.width * 0.2f, size.height * 0.1f)
                    lineTo(size.width * 0.5f, size.height * 0.6f)
                    lineTo(size.width, size.height * 0.9f)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
            }
            type == EngineType.MIDI -> {
                // MIDI 5-Pin DIN Icon
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension * 0.45f
                
                // Outer Ring
                drawCircle(color, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                
                // Pins (5 dots in a semicircle)
                val pinRadius = size.minDimension * 0.06f
                val pinDist = radius * 0.6f
                // Angles for 5 pins: -90, -45, 0, 45, 90 (relative to bottom-center? No, standard layout is 180 degree spread)
                // Let's do: -140, -110, -90, -70, -40 (degrees from bottom, facing up?)
                // Standard MIDI is a smiley face arrangement.
                val angles = listOf(45f, 90f, 135f, 225f, 315f) // This is getting complicated.
                // Simple version: 5 dots in an arc at the bottom
                val arcRadius = radius * 0.65f
                for (i in 0..4) {
                    val angleDeg = 180f + (30f * (i - 2)) // 120, 150, 180, 210, 240
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val x = center.x + arcRadius * Math.sin(angleRad).toFloat()
                    val y = center.y + arcRadius * Math.cos(angleRad).toFloat()
                    drawCircle(color, radius = pinRadius, center = Offset(x, y))
                }
                // Key notch at top
                 drawRect(color, size = Size(size.width * 0.15f, size.height * 0.1f), topLeft = Offset(center.x - size.width * 0.075f, center.y - radius), style = androidx.compose.ui.graphics.drawscope.Fill)
            }
            type == EngineType.AUDIO_IN -> {
                // Waveform entering a circle/mic
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(color, radius = size.minDimension * 0.4f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.5f)
                    lineTo(size.width * 0.4f, size.height * 0.3f)
                    lineTo(size.width * 0.6f, size.height * 0.7f)
                    lineTo(size.width * 0.8f, size.height * 0.5f)
                }
                drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
            }
            type == EngineType.SOUNDFONT -> {
                // Beamed Note Icon with Offset Square
                val headRadius = size.minDimension * 0.15f
                val stemHeight = size.height * 0.5f
                val note1Center = Offset(size.width * 0.3f, size.height * 0.75f)
                val note2Center = Offset(size.width * 0.7f, size.height * 0.65f)
                
                // Background Square (Offset) - Drawn first
                val squareSize = size.minDimension * 0.6f
                val squareOffset = size.minDimension * 0.1f // Shift slightly right/down? Or left/up?
                // User said "behind and offset". Let's put it top-left or center-offset.
                drawRect(
                    color = color.copy(alpha = 0.3f), // Slightly dimmer for background? Or outlined? Icon is usually solid color.
                    topLeft = Offset(size.width * 0.45f, size.height * 0.1f), // Offset to the right/top
                    size = Size(squareSize, squareSize),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth)
                )

                // Heads
                drawOval(color, topLeft = Offset(note1Center.x - headRadius, note1Center.y - headRadius * 0.8f), size = Size(headRadius * 2, headRadius * 1.6f))
                drawOval(color, topLeft = Offset(note2Center.x - headRadius, note2Center.y - headRadius * 0.8f), size = Size(headRadius * 2, headRadius * 1.6f))
                
                // Stems
                val stemWidth = strokeWidth
                drawLine(color, start = Offset(note1Center.x + headRadius * 0.8f, note1Center.y), end = Offset(note1Center.x + headRadius * 0.8f, note1Center.y - stemHeight), strokeWidth = stemWidth)
                drawLine(color, start = Offset(note2Center.x + headRadius * 0.8f, note2Center.y), end = Offset(note2Center.x + headRadius * 0.8f, note2Center.y - stemHeight), strokeWidth = stemWidth)
                
                // Beam
                val beamStart = Offset(note1Center.x + headRadius * 0.8f, note1Center.y - stemHeight)
                val beamEnd = Offset(note2Center.x + headRadius * 0.8f, note2Center.y - stemHeight)
                drawLine(color, start = beamStart, end = beamEnd, strokeWidth = stemWidth * 2.5f)
            }
            else -> {
                drawRect(color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth))
            }
        }
    }
}

@Composable
fun EngineSideSheet(
    isOpen: Boolean,
    trackIndex: Int,
    onDismiss: () -> Unit,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim
        androidx.compose.animation.AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }

        // Side Sheet Content
        androidx.compose.animation.AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it })
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
                    .clickable(enabled = false) {}, // Prevent click-through
                color = Color(0xFF1E1E1E),
                tonalElevation = 16.dp,
                shadowElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        "TRACK ${trackIndex + 1} ENGINE",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.Cyan
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Mute Action
                    val isMuted = state.tracks[trackIndex].isMuted
                    Surface(
                        onClick = {
                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(isMuted = !isMuted) else t }
                            onStateChange(state.copy(tracks = newTracks))
                        },
                        color = if (isMuted) Color.Red.copy(alpha = 0.2f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (isMuted) Icons.Filled.Close else Icons.Filled.Notifications, contentDescription = null, tint = if (isMuted) Color.Red else Color.White)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(if (isMuted) "UNMUTE" else "MUTE", color = if (isMuted) Color.Red else Color.White)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(16.dp))

                    // Engine List
                    val engineTypes = EngineType.values()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        engineTypes.forEach { type ->
                            val isSelected = state.tracks[trackIndex].engineType == type
                            Surface(
                                onClick = {
                                    val newTracks = state.tracks.mapIndexed { i, t -> if (i == trackIndex) t.copy(engineType = type) else t }
                                    onStateChange(state.copy(tracks = newTracks))
                                    nativeLib.setParameter(trackIndex, 1, type.ordinal.toFloat())
                                    onDismiss()
                                },
                                color = if (isSelected) Color.Cyan.copy(alpha = 0.15f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    EngineIcon(type, modifier = Modifier.size(32.dp), color = if (isSelected) Color.Cyan else Color.White)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        type.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) Color.Cyan else Color.White
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.weight(1f))
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}

@Composable
fun PadOptionPopup(
    onDismiss: () -> Unit,
    stepState: StepState,
    onApply: (Int, Boolean, Float, Float, List<Int>, Float, Boolean, Map<Int, Float>) -> Unit,
    onParamLock: () -> Unit
) {
    androidx.compose.ui.window.Popup(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(280.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header: Step Details
                Text("Step Options", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Divider(color = Color.White.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))

                // Ratchet & Punch
                Text("Ratchet", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..4).forEach { r ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(if (stepState.ratchet == r) Color.Cyan else Color.DarkGray, CircleShape)
                                .clickable { onApply(r, stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("x$r", style = MaterialTheme.typography.labelSmall, color = if (stepState.ratchet == r) Color.Black else Color.White)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(
                        onClick = { onApply(stepState.ratchet, !stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (stepState.punch) Color(0xFFFF4500) else Color.DarkGray), // OrangeRed
                        modifier = Modifier.weight(1f)
                    ) { Text("PUNCH", color = Color.White) }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(onClick = onParamLock, modifier = Modifier.weight(1f)) { Text("P-LOCK") }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Velocity Slider
                Text("Velocity: ${(stepState.velocity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = stepState.velocity,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, stepState.notes, it, stepState.isSkipped, stepState.parameterLocks) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Gate Selection
                val currentGate = stepState.gate.coerceIn(0.1f, 8.0f)
                val gateText = if (currentGate > 1.0f) {
                    "Gate: ${String.format("%.1f", currentGate)} Steps"
                } else {
                    "Gate: ${(currentGate * 100).toInt()}%"
                }
                
                // Cubic Mapping: y = 0.1 + 7.9 * x^3 
                // Cubic Mapping: y = 0.1 + 7.9 * x^3 
                // Inverse for slider position: x = ((y - 0.1) / 7.9)^(1/3)
                val diff = (currentGate - 0.1f).coerceAtLeast(0.0f)
                val sliderPosRaw = Math.pow((diff / 7.9f).toDouble(), 1.0/3.0).toFloat()
                val sliderPos = if (sliderPosRaw.isNaN()) 0f else sliderPosRaw.coerceIn(0f, 1f)

                Text(gateText, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = sliderPos,
                    onValueChange = { x ->
                        val newGate = 0.1f + 7.9f * (x * x * x)
                        onApply(stepState.ratchet, stepState.punch, stepState.probability, newGate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f).forEach { value ->
                        val isSelected = kotlin.math.abs(stepState.gate - value) < 0.05f
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(if (isSelected) Color.Cyan else Color.DarkGray, RoundedCornerShape(4.dp))
                                .clickable { onApply(stepState.ratchet, stepState.punch, stepState.probability, value, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks) },
                            contentAlignment = Alignment.Center
                        ) {
                            val label = if (value > 1.0f) "${value.toInt()}S" else "${(value * 100).toInt()}%"
                            Text(label, style = MaterialTheme.typography.labelSmall, color = if (isSelected) Color.Black else Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Probability Slider
                Text("Probability: ${(stepState.probability * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = stepState.probability,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, it, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks) },
                    valueRange = 0.01f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Manual Note Selection
                Text("Note Selection", style = MaterialTheme.typography.labelMedium)
                
                // Track Displayed Octave State
                var displayedOctave by remember { 
                    mutableStateOf((stepState.notes.firstOrNull() ?: 60) / 12) 
                }

                // Octave Controls (Changes View Only)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { displayedOctave = (displayedOctave - 1).coerceAtLeast(0) }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Oct Down", tint = Color.White)
                    }
                    Text("Oct $displayedOctave", style = MaterialTheme.typography.titleMedium, color = Color.Cyan)
                    IconButton(onClick = { displayedOctave = (displayedOctave + 1).coerceAtMost(9) }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Oct Up", tint = Color.White)
                    }
                }
                
                // 12 Semitones Grid
                val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                val currentOctaveStart = displayedOctave * 12
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.height(140.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(12) { i ->
                        val noteVal = currentOctaveStart + i
                        val isSelectedInCurrentOctave = stepState.notes.contains(noteVal)
                        
                        // Check if this pitch class (e.g. C) is selected in ANY other octave
                        val isSelectedInOtherOctave = !isSelectedInCurrentOctave && stepState.notes.any { it % 12 == i }
                        
                        val isBlack = isBlackKey(noteVal)
                        
                        val backgroundColor = when {
                            isSelectedInCurrentOctave -> Color.Cyan
                            isSelectedInOtherOctave -> Color.Cyan.copy(alpha = 0.3f)
                            isBlack -> Color.Black
                            else -> Color.Gray
                        }
                        
                        val borderColor = if (isSelectedInCurrentOctave) Color.White else Color.Transparent
                        
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .background(backgroundColor, RoundedCornerShape(4.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                                .clickable { 
                                    val newNotes = if (isSelectedInCurrentOctave) {
                                        stepState.notes.filter { it != noteVal }
                                    } else {
                                        (stepState.notes + noteVal).distinct().sorted()
                                    }
                                    onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, newNotes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks) 
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                noteNames[i], 
                                style = MaterialTheme.typography.labelSmall, 
                                color = if (isSelectedInCurrentOctave || isBlack) Color.White else Color.Black
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 9. Remove/Restore Step
                Button(
                    onClick = { 
                        onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, !stepState.isSkipped, stepState.parameterLocks)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (stepState.isSkipped) Color.Red else Color.DarkGray),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (stepState.isSkipped) "RESTORE STEP" else "REMOVE STEP", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun ArpSettingsSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = isOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val track = state.tracks[state.selectedTrackIndex]
            val config = track.arpConfig
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .clickable(enabled = false) {},
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 16.dp
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("ARPEGGIATOR", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White) }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Mode Selection
                    Text("MODE", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArpMode.values().forEach { mode ->
                            val isSelected = config.mode == mode
                            Button(
                                onClick = {
                                    val newConfig = config.copy(mode = mode)
                                    val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                    onStateChange(state.copy(tracks = newTracks))
                                    nativeLib.setArpConfig(
                                        state.selectedTrackIndex, 
                                        mode.ordinal, 
                                        newConfig.octaves, 
                                        newConfig.inversion, 
                                        newConfig.isLatched, 
                                        newConfig.isMutated,
                                        newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(),
                                        newConfig.randomSequence.toIntArray()
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray)
                            ) { Text(mode.name, color = if (isSelected) Color.Black else Color.White) }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        // Rate & Division
                        Column(modifier = Modifier.weight(1.2f)) {
                            Text("RATE & DIVISION", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Rate Knob
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val rates = listOf("1/32", "1/16", "1/8", "1/4", "1/2")
                                    val numericRates = listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f)
                                    val currentIndex = numericRates.indexOfFirst { it >= config.arpRate }.coerceAtLeast(0)
                                    val initialV = currentIndex.toFloat() / (numericRates.size - 1)

                                    Knob(
                                        label = "RATE",
                                        initialValue = initialV,
                                        parameterId = -99, // Custom
                                        state = state,
                                        onStateChange = { newState ->
                                            val v = newState.tracks[state.selectedTrackIndex].parameters[-99] ?: initialV
                                            val idx = ((v * 0.99f) * numericRates.size).toInt().coerceIn(0, numericRates.size - 1)
                                            val newRate = numericRates[idx]
                                            val newConfig = config.copy(arpRate = newRate)
                                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                            onStateChange(state.copy(tracks = newTracks))
                                            nativeLib.setArpRate(state.selectedTrackIndex, newRate, config.arpDivisionMode)
                                        },
                                        nativeLib = nativeLib,
                                        knobSize = 60.dp,
                                        valueFormatter = { v -> rates[((v * 0.99f) * rates.size).toInt().coerceIn(0, rates.size - 1)] }
                                    )
                                }

                                // Division Mode (Reg, Dot, Tri)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("REG", "DOT", "TRI").forEachIndexed { index, label ->
                                        val isSelected = config.arpDivisionMode == index
                                        Button(
                                            onClick = {
                                                val newConfig = config.copy(arpDivisionMode = index)
                                                val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                onStateChange(state.copy(tracks = newTracks))
                                                nativeLib.setArpRate(state.selectedTrackIndex, config.arpRate, index)
                                            },
                                            modifier = Modifier.height(32.dp).width(60.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall, color = if (isSelected) Color.Black else Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        // Octaves & Inversion Stacked
                        Column(modifier = Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Octaves
                            Column {
                                Text("OCTAVES (+/- 3)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    (-3..3).forEach { oct ->
                                        val isSelected = config.octaves == oct
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(if (isSelected) Color.Cyan else Color.Transparent, CircleShape)
                                                .clickable {
                                                    val newConfig = config.copy(octaves = oct)
                                                    val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                    onStateChange(state.copy(tracks = newTracks))
                                                    nativeLib.setArpConfig(state.selectedTrackIndex, config.mode.ordinal, oct, newConfig.inversion, newConfig.isLatched, newConfig.isMutated, newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), newConfig.randomSequence.toIntArray())
                                                },
                                            contentAlignment = Alignment.Center
                                        ) { Text("$oct", color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp) }
                                    }
                                }
                            }
                            
                            // Inversion
                            Column {
                                Text("INVERSION", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    (-2..2).forEach { inv ->
                                        val isSelected = config.inversion == inv
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(if (isSelected) Color.Cyan else Color.Transparent, CircleShape)
                                                .clickable {
                                                    val newConfig = config.copy(inversion = inv)
                                                    val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                    onStateChange(state.copy(tracks = newTracks))
                                                    nativeLib.setArpConfig(state.selectedTrackIndex, config.mode.ordinal, config.octaves, inv, newConfig.isLatched, newConfig.isMutated, newConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), newConfig.randomSequence.toIntArray())
                                                },
                                            contentAlignment = Alignment.Center
                                        ) { Text("$inv", color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp) }
                                    }
                                }
                            }
                        }

                        // chord Progression Section
                        Column(modifier = Modifier.weight(1.5f)) {
                            Text("CHORD PROGRESSION", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Toggle
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("ON/OFF", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                                    Switch(
                                        checked = config.isChordProgEnabled,
                                        onCheckedChange = { enabled ->
                                            val newConfig = config.copy(isChordProgEnabled = enabled)
                                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                            onStateChange(state.copy(tracks = newTracks))
                                            nativeLib.setChordProgConfig(state.selectedTrackIndex, enabled, config.chordProgMood, config.chordProgComplexity)
                                            nativeLib.setScaleConfig(state.rootNote, state.scaleType.intervals.toIntArray())
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan)
                                    )
                                }

                                // Mood Knob
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val moods = listOf("Calm", "Happy", "Sad", "Spooky", "Angry", "Excited", "Grand", "Tense")
                                    Knob(
                                        label = "MOOD",
                                        initialValue = config.chordProgMood.toFloat() / 7f,
                                        parameterId = -100, // Custom for UI
                                        state = state,
                                        onStateChange = { newState ->
                                            val v = newState.tracks[state.selectedTrackIndex].parameters[-100] ?: (config.chordProgMood.toFloat() / 7f)
                                            val moodIdx = (v * 7.99f).toInt().coerceIn(0, 7)
                                            val newConfig = config.copy(chordProgMood = moodIdx)
                                            val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                            onStateChange(state.copy(tracks = newTracks))
                                            nativeLib.setChordProgConfig(state.selectedTrackIndex, config.isChordProgEnabled, moodIdx, config.chordProgComplexity)
                                        },
                                        nativeLib = nativeLib,
                                        knobSize = 50.dp,
                                        valueFormatter = { v -> moods[(v * 7.99f).toInt().coerceIn(0, 7)] }
                                    )
                                }

                                // Complexity Buttons
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("SIMPLE", "COMPLEX", "COLTRANE").forEachIndexed { index, label ->
                                        val isSelected = config.chordProgComplexity == index
                                        Button(
                                            onClick = {
                                                val newConfig = config.copy(chordProgComplexity = index)
                                                val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                onStateChange(state.copy(tracks = newTracks))
                                                nativeLib.setChordProgConfig(state.selectedTrackIndex, config.isChordProgEnabled, config.chordProgMood, index)
                                            },
                                            modifier = Modifier.height(28.dp).width(80.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = if (isSelected) Color.Black else Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    Spacer(modifier = Modifier.height(24.dp))

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Mutation Button & Rhythm Editor Container
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val track = state.tracks[state.selectedTrackIndex]
                                val config = track.arpConfig
                                // Randomize Rhythms (3 lanes x 16 steps)
                                val newRhythms = config.rhythms.mapIndexed { laneIdx, row ->
                                    if (laneIdx == 0) { // Root only
                                        // Randomize
                                        List(16) { Math.random() < 0.7 }
                                    } else {
                                        // Clear upper lanes to avoid unintended polyphony
                                        List(16) { false }
                                    }
                                }
                                
                                val newConfig = config.copy(rhythms = newRhythms)
                                val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                onStateChange(state.copy(tracks = newTracks))
                                
                                nativeLib.setArpConfig(
                                    state.selectedTrackIndex,
                                    newConfig.mode.ordinal,
                                    newConfig.octaves,
                                    newConfig.inversion,
                                    newConfig.isLatched,
                                    newConfig.isMutated,
                                    newRhythms.map { it.toBooleanArray() }.toTypedArray(),
                                    newConfig.randomSequence.toIntArray()
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = if (track.arpConfig.isMutated) Color.Magenta else Color.DarkGray)
                        ) { Text("RANDOMIZE RHYTHM", color = Color.White) }

                        Spacer(modifier = Modifier.height(24.dp))

                         // Rhythm Editor (3 Lanes)
                         Text("RHYTHM PATTERNS (16 STEPS)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                         Text("(Bottom=Root, Upper=Polyphonic Cycle)", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Color.DarkGray)
                         Spacer(modifier = Modifier.height(8.dp))
                         
                         // 3 Lanes (Reverse order: Lane 2 (Top) -> Lane 0 (Bottom/Root))
                         // Swap Labels to ensure Root is clearly bottom (which is Lane 0)
                         // Lane 2=Top, Lane 0=Bottom. The loop iterates 2 downTo 0. 
                         // So Lane 2 (Top) is rendered first.
                         val laneLabels = listOf("ROOT", "UP 1", "UP 2") // Indices 0,1,2.
                         // We display Lane 2 first (Top), so we want label "UP 2".
                         // laneLabels[2] is "UP 2", laneLabels[0] is "ROOT".
                         val laneColors = listOf(Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA)) // Grn, Blu, Purp
                                                  (2 downTo 0).forEach { laneIdx ->
                             Row(
                                 verticalAlignment = Alignment.CenterVertically, 
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .padding(vertical = 4.dp)
                             ) {
                                 Text(laneLabels[laneIdx], style = MaterialTheme.typography.labelSmall, color = laneColors[laneIdx], modifier = Modifier.width(40.dp))
                                 
                                 val lanePattern = config.rhythms.getOrElse(laneIdx) { List(16) { false } }
                                 Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                     // Force 16 steps display
                                     val displaySteps = if (lanePattern.size < 16) lanePattern + List(16 - lanePattern.size) { false } else lanePattern
                                     displaySteps.take(16).forEachIndexed { step, isActive ->
                                         Box(
                                             modifier = Modifier
                                                 .weight(1f) // Distribute equally
                                                 .aspectRatio(1.2f) // Slightly taller than wide for better touch
                                                 .background(
                                                     if (isActive) laneColors[laneIdx] else Color.DarkGray,
                                                     RoundedCornerShape(2.dp)
                                                 )
                                                 .clickable {
                                                     val newLane = lanePattern.toMutableList()
                                                     while (newLane.size <= step) newLane.add(false)
                                                     if (step < newLane.size) {
                                                        newLane[step] = !newLane[step]
                                                        val newRhythms = config.rhythms.toMutableList()
                                                        while(newRhythms.size <= laneIdx) newRhythms.add(List(16) { false })
                                                        newRhythms[laneIdx] = newLane.take(16)
                                                        
                                                        val newConfig = config.copy(rhythms = newRhythms)
                                                        val newTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = newConfig) else t }
                                                        onStateChange(state.copy(tracks = newTracks))
                                                        nativeLib.setArpConfig(
                                                            state.selectedTrackIndex, 
                                                            newConfig.mode.ordinal, 
                                                            newConfig.octaves, 
                                                            newConfig.inversion, 
                                                            newConfig.isLatched, 
                                                            newConfig.isMutated,
                                                            newRhythms.map { it.toBooleanArray() }.toTypedArray(), 
                                                            newConfig.randomSequence.toIntArray()
                                                        )
                                                     }
                                                 }
                                         )
                                     }
                                 }
                             }
                             if (laneIdx > 0) {
                                 Divider(
                                     thickness = 1.dp, 
                                     color = Color.Black.copy(alpha = 0.5f), 
                                     modifier = Modifier.padding(vertical = 4.dp)
                                 )
                             }
                          }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                    }
                }
            }
        }
    }
}

@Composable
fun RecordingStrip(
    trackIndex: Int, // Added
    isRecording: Boolean,
    isResampling: Boolean, // New
    waveform: FloatArray?,
    track: TrackState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleResampling: () -> Unit,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    onWaveformRefresh: () -> Unit = {},
    trimStart: Float? = null,
    trimEnd: Float? = null,
    slices: Int? = null,
    granularPlayheads: FloatArray? = null,
    grainSize: Float? = null,
    nativeLib: NativeLib? = null,
    extraControls: (@Composable () -> Unit)? = null 
) {
    val engineColor = getEngineColor(track.engineType)
    val scope = rememberCoroutineScope()
    
    // For Sampler, fetch real slice points
    val slicePoints = if (track.engineType == EngineType.SAMPLER && nativeLib != null) {
        remember(trackIndex, track.parameters[340], isRecording) {
            nativeLib.getSlicePoints(trackIndex)
        }
    } else null
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.clickable { onToggleResampling() }) {
                Text("RECORDING SOURCE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    if (isResampling) "RESAMPLING (MIX)" else "INTERNAL MICROPHONE", 
                    style = MaterialTheme.typography.bodySmall, 
                    color = if (isResampling) Color.Cyan else Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            
            // Central Control Cluster (Save/Load/Trim)
            // Use weight to fill space and keep items centered
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val context = androidx.compose.ui.platform.LocalContext.current
                var showSaveDialog by remember { mutableStateOf(false) }
                var showLoadDialog by remember { mutableStateOf(false) }

                if (showSaveDialog) {
                    val folderName = when(track.engineType) {
                        EngineType.GRANULAR -> "granular"
                        EngineType.SAMPLER -> "samples"
                        EngineType.AUDIO_IN -> "recordings"
                        else -> "samples"
                    }
                    val defaultDir = File(PersistenceManager.getLoomFolder(context), folderName).apply { if (!exists()) mkdirs() }
                    
                    NativeFileDialog(
                        directory = defaultDir, 
                        onDismiss = { showSaveDialog = false }, 
                        state = state, 
                        onFileSelected = { path ->
                             nativeLib?.saveSample(trackIndex, path)
                             onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                 if (idx == trackIndex) t.copy(lastSamplePath = path) else t
                             }))
                        }, 
                        isSave = true,
                        trackIndex = trackIndex
                    )
                }
                if (showLoadDialog) {
                    val defaultDir = when(track.engineType) {
                        EngineType.GRANULAR -> File(PersistenceManager.getLoomFolder(context), "granular").apply { if (!exists()) mkdirs() }
                        EngineType.SAMPLER -> File(PersistenceManager.getLoomFolder(context), "samples").apply { if (!exists()) mkdirs() }
                        else -> PersistenceManager.getLoomFolder(context)
                    }

                    NativeFileDialog(
                        directory = defaultDir, 
                        onDismiss = { showLoadDialog = false }, 
                        state = state, 
                        onFileSelected = { path ->
                             nativeLib?.loadSample(trackIndex, path)
                             onStateChange(state.copy(tracks = state.tracks.mapIndexed { idx, t ->
                                 if (idx == trackIndex) t.copy(lastSamplePath = path) else t
                             }))
                             // REFRESH WAVEFORM
                             onWaveformRefresh()
                        }, 
                        isSave = false,
                        trackIndex = trackIndex,
                        extensions = listOf("wav"),
                        onExport = { index, path, format ->
                             scope.launch(Dispatchers.IO) {
                                 val pcmData = nativeLib?.getRecordedSampleData(index, 44100f)
                                 if (pcmData != null) {
                                     val exportPath = path.removeSuffix(".wav") + if (format == "AAC") ".m4a" else ".flac"
                                     if (format == "AAC") {
                                         AudioExporter.encodeToAAC(pcmData, exportPath)
                                     } else {
                                         AudioExporter.encodeToFLAC(pcmData, exportPath)
                                     }
                                     withContext(Dispatchers.Main) {
                                         Toast.makeText(context, "Exported to: $exportPath", Toast.LENGTH_LONG).show()
                                     }
                                 } else {
                                     withContext(Dispatchers.Main) {
                                         Toast.makeText(context, "Export failed: No recorded data found for this track.", Toast.LENGTH_LONG).show()
                                     }
                                 }
                             }
                        }
                    )
                }

                Button(onClick = { showSaveDialog = true }, modifier = Modifier.size(50.dp, 28.dp), contentPadding = PaddingValues(0.dp)) { Text("SAVE", fontSize = 9.sp) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { showLoadDialog = true }, modifier = Modifier.size(50.dp, 28.dp), contentPadding = PaddingValues(0.dp)) { Text("LOAD", fontSize = 9.sp) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { 
                    nativeLib?.trimSample(trackIndex)
                    onWaveformRefresh()
                }, modifier = Modifier.size(50.dp, 28.dp), contentPadding = PaddingValues(0.dp)) { Text("TRIM", fontSize = 9.sp) }
                
                if (extraControls != null) {
                    Spacer(modifier = Modifier.width(16.dp))
                    extraControls()
                }
            }
            
            val infiniteTransition = rememberInfiniteTransition()
            val animScale by infiniteTransition.animateFloat(
                initialValue = 1f, targetValue = 1.15f,
                animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse)
            )

            // Record Button Row (Lock + Button)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // LOCK TOGGLE (Moved here)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LOCK", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 8.sp)
                    androidx.compose.material3.Switch(
                        checked = state.isRecordingLocked,
                        onCheckedChange = { locked ->
                            onStateChange(state.copy(isRecordingLocked = locked))
                            nativeLib?.setRecordingLocked(locked)
                        },
                        modifier = Modifier.scale(0.6f).height(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))
                
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .graphicsLayer { if (isRecording) { scaleX = animScale; scaleY = animScale } }
                        .background(if (isRecording) Color.Red else Color.Red.copy(alpha = 0.3f), CircleShape)
                        .border(2.dp, Color.Red, CircleShape)
                        .pointerInput(trackIndex, state.isRecordingLocked, isRecording) {
                            detectTapGestures(
                                onPress = {
                                    if (state.isRecordingLocked) {
                                        if (isRecording) {
                                            // UNLOCK AND STOP
                                            onStateChange(state.copy(isRecordingLocked = false))
                                            nativeLib?.setRecordingLocked(false)
                                            onStopRecording()
                                        } else {
                                            onStartRecording()
                                        }
                                    } else {
                                        try {
                                            onStartRecording()
                                            awaitRelease()
                                        } finally {
                                            onStopRecording()
                                        }
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRecording) {
                        Box(modifier = Modifier.size(16.dp).background(Color.White, RoundedCornerShape(2.dp)))
                    } else {
                        Box(modifier = Modifier.size(20.dp).background(Color.Red, CircleShape))
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color.Black, RoundedCornerShape(8.dp))
                .border(1.dp, Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val wave = waveform
                if (wave != null && wave.isNotEmpty()) {
                    val step = size.width / wave.size
                    wave.forEachIndexed { i, amp ->
                        val x = i * step
                        val h = Math.abs(amp) * size.height * 0.95f
                        drawLine(engineColor, Offset(x, size.height/2 - h/2), Offset(x, size.height/2 + h/2), strokeWidth = 1.5.dp.toPx())
                    }
                } else if (isRecording) {
                    drawLine(Color.Red.copy(alpha = 0.3f), Offset(0f, size.height/2), Offset(size.width, size.height/2), strokeWidth = 1.dp.toPx())
                }
                
                // Draw Slice Lines
                // Draw Slice Lines with Numbering
                if (track.engineType == EngineType.SAMPLER && slicePoints != null) {
                    val paint = Paint().apply {
                        color = android.graphics.Color.MAGENTA
                        textSize = 24f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    slicePoints.forEachIndexed { index, point ->
                        val x = point * size.width
                        drawLine(Color.Magenta, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        
                        drawIntoCanvas {
                            it.nativeCanvas.drawText((index + 1).toString(), x + 8f, 30f, paint)
                        }
                    }
                } else if (slices != null && slices > 1) {
                    val sliceStep = size.width / slices
                    for (i in 1 until slices) {
                         val x = i * sliceStep
                         drawLine(Color.Magenta, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                }

                // Draw Granular Playheads
                if (granularPlayheads != null && granularPlayheads.isNotEmpty()) {
                    val gSizeVal = grainSize ?: 0.1f 
                    val widthPx = gSizeVal * size.width 
                    
                    for (i in 0 until granularPlayheads.size step 2) {
                        val pos = granularPlayheads[i]
                        val vol = granularPlayheads[i + 1]
                        if (pos >= 0f && vol > 0.01f) {
                             val x = pos * size.width
                             val alpha = (vol * 0.9f).coerceIn(0.2f, 1f) // Ensure min visibility
                             
                             // Draw Main Playhead Line (High Contrast)
                             drawLine(
                                 Color.Yellow, // No alpha for core line
                                 Offset(x, 0f), 
                                 Offset(x, size.height), 
                                 strokeWidth = 2.dp.toPx()
                             )
                             
                             // Optional: Draw grain width window
                             if (widthPx > 2f) {
                                 // Draw faint window to show grain size
                                 drawRect(
                                     Color.Yellow.copy(alpha = (alpha * 0.3f).coerceIn(0.0f, 1.0f)),
                                     topLeft = Offset(x, 0f),
                                     size = Size(widthPx, size.height)
                                 )
                             }
                        }
                    }
                }

                // Draw Trim Lines
                if (trimStart != null) {
                    val x = trimStart * size.width
                    drawLine(Color.Green, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                }
                if (trimEnd != null) {
                    val x = trimEnd * size.width
                    drawLine(Color.Red, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
            
            if (isRecording) {
                Text("REC", modifier = Modifier.padding(4.dp).align(Alignment.TopEnd), color = Color.Red, style = MaterialTheme.typography.labelSmall)
            }
        }}

    }
@Composable
fun EffectsScreen(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Mixer Sidebar Removed
        
        // Pedalboard Grid

        // Pedalboard Grid
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(minSize = 180.dp),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(8.dp),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                val hotPink = Color(0xFFFF69B4)
                Pedal("COMPRESSOR", hotPink, state, 8, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("THR", 0.5f, 580, state, onStateChange, nativeLib)
                        GlobalKnob("RATIO", 0.5f, 581, state, onStateChange, nativeLib)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("ATK", 0.5f, 582, state, onStateChange, nativeLib)
                        GlobalKnob("REL", 0.5f, 583, state, onStateChange, nativeLib)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        GlobalKnob("GAIN", 0.0f, 584, state, onStateChange, nativeLib)
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { 
                                val drumTrackIdx = state.tracks.indexOfFirst { it.engineType == EngineType.FM_DRUM }
                                if (drumTrackIdx != -1) {
                                    onStateChange(state.copy(selectedTrackIndex = drumTrackIdx, isSelectingSidechain = true))
                                } else {
                                    onStateChange(state.copy(isSelectingSidechain = true))
                                }
                            },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = hotPink.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, hotPink),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            val scText = if (state.sidechainSourceTrack != -1) {
                                val instruments = listOf("KICK", "SNARE", "TOM", "HIHAT", "OHH", "CYMB", "PERC", "NOISE")
                                val name = instruments.getOrNull(state.sidechainSourceDrumIdx) ?: ""
                                "SC: $name"
                            } else "SC TRG"
                            Text(scText, color = hotPink, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item {
                Pedal("BITCRUSH", Color.Yellow, state, 1, onStateChange, nativeLib) {
                   Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                       GlobalKnob("BITS", 0.5f, 530, state, onStateChange, nativeLib, valueFormatter = { v -> "${(v * 15 + 1).toInt()} bits" })
                       GlobalKnob("SRATE", 0.2f, 531, state, onStateChange, nativeLib, valueFormatter = { v -> "${String.format("%.1f", 1.0f + v * 7.0f)}x" })
                   }
                   Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                       GlobalKnob("MIX", 1.0f, 532, state, onStateChange, nativeLib)
                   }
                }
            }
            item {
                Pedal("CHORUS", Color(0xFF03DAC6), state, 2, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("RATE", 0.4f, 510, state, onStateChange, nativeLib)
                        GlobalKnob("DPTH", 0.5f, 511, state, onStateChange, nativeLib)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("MIX", 0.5f, 512, state, onStateChange, nativeLib)
                        GlobalKnob("VOC", 0.0f, 513, state, onStateChange, nativeLib)
                    }
                }
            }
            item {
                Pedal("PHASER", Color.Magenta, state, 3, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("RATE", 0.2f, 550, state, onStateChange, nativeLib)
                        GlobalKnob("DPTH", 0.5f, 551, state, onStateChange, nativeLib)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("MIX", 1.0f, 552, state, onStateChange, nativeLib)
                        GlobalKnob("INTEN", 0.5f, 553, state, onStateChange, nativeLib)
                    }
                }
            }
            item {
                Pedal("WOBBLE", Color(0xFFFFA500), state, 4, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("RATE", 0.1f, 560, state, onStateChange, nativeLib)
                        GlobalKnob("DPTH", 0.5f, 561, state, onStateChange, nativeLib)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("SAT", 0.0f, 562, state, onStateChange, nativeLib)
                        GlobalKnob("MIX", 1.0f, 563, state, onStateChange, nativeLib)
                    }
                }
            }
            item {
                Pedal("OVERDRIVE", Color.Red, state, 0, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("DRIVE", 0.3f, 540, state, onStateChange, nativeLib)
                        GlobalKnob("DIST", 1.0f, 541, state, onStateChange, nativeLib)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("LEVEL", 0.5f, 542, state, onStateChange, nativeLib)
                        GlobalKnob("TONE", 0.5f, 543, state, onStateChange, nativeLib)
                    }
                }
            }
            item {
                Pedal("REVERB", Color.Cyan, state, 6, onStateChange, nativeLib) {
                   Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("SIZE", 0.5f, 500, state, onStateChange, nativeLib)
                            GlobalKnob("MIX", 0.3f, 503, state, onStateChange, nativeLib)
                            GlobalKnob("TYPE", 0.0f, 505, state, onStateChange, nativeLib, valueFormatter = { v ->
                                when {
                                    v < 0.25f -> "PLATE"
                                    v < 0.5f -> "ROOM"
                                    v < 0.75f -> "HALL"
                                    else -> "SPACE"
                                }
                            })
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("DAMP", 0.5f, 501, state, onStateChange, nativeLib)
                            GlobalKnob("MOD", 0.0f, 502, state, onStateChange, nativeLib)
                            GlobalKnob("TONE", 0.5f, 506, state, onStateChange, nativeLib)
                            GlobalKnob("P.DLY", 0.0f, 504, state, onStateChange, nativeLib)
                        }
                   }
                }
            }
            item {
                Pedal("FLANGER", Color(0xFF9C27B0), state, 11, onStateChange, nativeLib) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("RATE", 0.5f, 1500, state, onStateChange, nativeLib)
                            GlobalKnob("DPTH", 0.7f, 1501, state, onStateChange, nativeLib)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("MIX", 0.5f, 1502, state, onStateChange, nativeLib) 
                            GlobalKnob("FEED", 0.6f, 1503, state, onStateChange, nativeLib) 
                        }
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                            GlobalKnob("DLAY", 0.2f, 1504, state, onStateChange, nativeLib) 
                        }
                    }
                }
            }
            item {
                Pedal("DELAY", Color.Blue, state, 5, onStateChange, nativeLib) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("TIME", 0.25f, 520, state, onStateChange, nativeLib)
                            GlobalKnob("FEED", 0.5f, 521, state, onStateChange, nativeLib)
                            GlobalKnob("MIX", 0.5f, 522, state, onStateChange, nativeLib)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("FILT", 0.5f, 523, state, onStateChange, nativeLib) 
                            GlobalKnob("RES", 0.0f, 524, state, onStateChange, nativeLib)
                            GlobalKnob("TYPE", 0.0f, 525, state, onStateChange, nativeLib, valueFormatter = { v ->
                                when((v * 3.9f).toInt()) {
                                    0 -> "DIGITAL"
                                    1 -> "TAPE"
                                    2 -> "P-PONG"
                                    else -> "REVERSE"
                                }
                            })
                            GlobalKnob("MODE", 0.0f, 526, state, onStateChange, nativeLib, valueFormatter = { v ->
                                when((v * 2.9f).toInt()) {
                                    0 -> "LP"
                                    1 -> "HP"
                                    else -> "BP"
                                }
                            })
                        }
                    }
                }
            }
            item {
                Pedal("TAPE ECHO", Color(0xFFFFC107), state, 13, onStateChange, nativeLib) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("TIME", 0.3f, 1510, state, onStateChange, nativeLib)
                            GlobalKnob("FEED", 0.5f, 1511, state, onStateChange, nativeLib)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("MIX", 0.5f, 1512, state, onStateChange, nativeLib)
                            GlobalKnob("DRV", 0.4f, 1513, state, onStateChange, nativeLib)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("WOW", 0.1f, 1514, state, onStateChange, nativeLib)
                            GlobalKnob("FLUT", 0.1f, 1515, state, onStateChange, nativeLib)
                        }
                    }
                }
            }
            item {
                Pedal("HP LFO", Color(0xFFFF4081), state, 9, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         GlobalKnob("RATE", 0.5f, 590, state, onStateChange, nativeLib)
                         GlobalKnob("DPTH", 0.0f, 591, state, onStateChange, nativeLib)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         GlobalKnob("SHAPE", 0.0f, 592, state, onStateChange, nativeLib)
                         GlobalKnob("CUT", 0.5f, 593, state, onStateChange, nativeLib)
                         GlobalKnob("RES", 0.0f, 594, state, onStateChange, nativeLib)
                    }
                }
            }
            item {
                Pedal("LP LFO", Color(0xFF18FFFF), state, 10, onStateChange, nativeLib) {
                     Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         GlobalKnob("RATE", 0.5f, 490, state, onStateChange, nativeLib)
                         GlobalKnob("DPTH", 0.0f, 491, state, onStateChange, nativeLib)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         GlobalKnob("SHAPE", 0.0f, 492, state, onStateChange, nativeLib)
                         GlobalKnob("CUT", 0.5f, 493, state, onStateChange, nativeLib)
                         GlobalKnob("RES", 0.0f, 494, state, onStateChange, nativeLib)
                    }
                }
            }
            item {
                Pedal("AUTOPAN", Color(0xFFE91E63), state, 12, onStateChange, nativeLib) { // Moved to 12 to avoid LP LFO (10) collision
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("PAN", 0.5f, 2100, state, onStateChange, nativeLib)
                        GlobalKnob("RATE", 0.2f, 2101, state, onStateChange, nativeLib)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("DPTH", 0.5f, 2102, state, onStateChange, nativeLib)
                        GlobalKnob("MIX", 1.0f, 2104, state, onStateChange, nativeLib)
                    }
                    // SHAPE selector as a small row or knob
                    GlobalKnob("SHAPE", 0.0f, 2103, state, onStateChange, nativeLib, valueFormatter = { v ->
                        when {
                            v < 0.33f -> "SINE"
                            v < 0.66f -> "TRI"
                            else -> "SQR"
                        }
                    })
                }
            }
            item {
                Pedal("SLICER", Color.Green, state, 7, onStateChange, nativeLib) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SlicerKnob("1/4", 570, 573, state, onStateChange, nativeLib)
                            SlicerKnob("1/3", 571, 574, state, onStateChange, nativeLib)
                            SlicerKnob("1/5", 572, 575, state, onStateChange, nativeLib)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        GlobalKnob("DPTH", 1.0f, 576, state, onStateChange, nativeLib)
                    }
                }
            }
            item {
                Pedal("OCTAVER", Color(0xFF3F51B5), state, 14, onStateChange, nativeLib) {
                    Column {
                         Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                             GlobalKnob("MIX", 0.5f, 1530, state, onStateChange, nativeLib)
                         }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("MODE", 0.5f, 1531, state, onStateChange, nativeLib, valueFormatter = { v ->
                                val mode = (v * 11.99f).toInt()
                                when(mode) {
                                    0 -> "OCT UP"
                                    1 -> "2 OCT UP"
                                    2 -> "OCT DWN"
                                    3 -> "2 OCT DWN"
                                    4 -> "UP/DWN"
                                    5 -> "MAJ"
                                    6 -> "MIN"
                                    7 -> "SUS4"
                                    8 -> "DOM7"
                                    9 -> "MAJ7"
                                    10 -> "MIN7"
                                    11 -> "DIM"
                                    else -> "PWR"
                                }
                            })
                            GlobalKnob("UNISON", 0.0f, 1532, state, onStateChange, nativeLib) 
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SlicerKnob(
    label: String,
    rateParamId: Int,
    activeParamId: Int,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val isActive = (latestState.globalParameters[activeParamId] ?: 0.0f) > 0.5f
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlobalKnob(label, 0.25f, rateParamId, latestState, latestOnStateChange, nativeLib)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(if (isActive) Color.Green else Color.DarkGray, CircleShape)
                .border(1.dp, Color.White, CircleShape)
                .clickable {
                    val newValue = if (isActive) 0.0f else 1.0f
                    nativeLib.setParameter(0, activeParamId, newValue)
                    latestOnStateChange(latestState.copy(globalParameters = latestState.globalParameters + (activeParamId to newValue)))
                }
        )
    }
}

@Composable
fun Pedal(
    name: String,
    borderColor: Color,
    state: GrooveboxState,
    fxIdx: Int,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib,
    content: @Composable ColumnScope.() -> Unit
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val track = latestState.tracks[latestState.selectedTrackIndex]
    val sendLevel = track.fxSends.getOrNull(fxIdx) ?: 0.0f
    val isOn = sendLevel > 0.0f
    
    Card(
        modifier = Modifier.padding(4.dp),
        border = BorderStroke(3.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = borderColor.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold, color = borderColor)
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Signal Path Arrow
                    Icon(
                        imageVector = Icons.Default.ArrowForward, 
                        contentDescription = "Signal Path",
                        tint = borderColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Orange light indicator
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(if (isOn) Color(0xFFFFA500) else Color.DarkGray)
                        if (isOn) {
                            drawCircle(Color(0xFFFFA500).copy(alpha = 0.4f), radius = size.minDimension * 0.8f)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Per-track SEND knob (Power/Volume)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Per-track SEND knob (Power/Volume)
                Knob(
                    label = "SEND",
                    initialValue = 0.0f,
                    parameterId = -1,
                    state = state,
                    onStateChange = onStateChange,
                    nativeLib = nativeLib,
                    knobSize = 32.dp,
                    overrideValue = sendLevel,
                    onValueChangeOverride = { newValue ->
                        nativeLib.setParameter(latestState.selectedTrackIndex, 2000 + (fxIdx * 10), newValue)
                        val newTracks = latestState.tracks.mapIndexed { i, t ->
                            if (i == latestState.selectedTrackIndex) {
                                val newSends = t.fxSends.toMutableList()
                                newSends[fxIdx] = newValue
                                t.copy(fxSends = newSends)
                            } else t
                        }
                        latestOnStateChange(latestState.copy(tracks = newTracks))
                    }
                )

                // Global MIX Knob (Wet/Dry Return Level)
                Knob(
                    label = "MIX",
                    initialValue = 1.0f,
                    parameterId = 3000 + fxIdx, // Mapped to mFxMixLevels in AudioEngine
                    state = state,
                    onStateChange = onStateChange,
                    nativeLib = nativeLib,
                    knobSize = 32.dp
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun GlobalKnob(
    label: String,
    initialValue: Float,
    parameterId: Int,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib,
    valueFormatter: ((Float) -> String)? = null,
    onValueChangeOverride: ((Float) -> Unit)? = null
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val globalValue = latestState.globalParameters[parameterId] ?: initialValue
    Knob(
        label = label,
        initialValue = initialValue,
        parameterId = parameterId,
        state = latestState,
        onStateChange = latestOnStateChange,
        nativeLib = nativeLib,
        overrideValue = globalValue,
        overrideColor = Color.Magenta,
        onValueChangeOverride = onValueChangeOverride ?: { newVal ->
            // Update Audio Engine (Track 0 convention for Global)
            nativeLib.setParameter(0, parameterId, newVal)
            
            val isAutoLocking = latestState.isRecording && latestState.isPlaying
            if (isAutoLocking) {
                // Parameter Locking Logic for Global Knobs
                val trackIndex = latestState.selectedTrackIndex
                val currentStep = latestState.currentStep
                nativeLib.setParameterLock(trackIndex, currentStep, parameterId, newVal)
                
                val newTracks = latestState.tracks.mapIndexed { tIdx, track ->
                    if (tIdx == trackIndex) {
                        track.copy(steps = track.steps.mapIndexed { sIdx, step ->
                            if (sIdx == currentStep) step.copy(parameterLocks = step.parameterLocks + (parameterId to newVal))
                            else step
                        })
                    } else track
                }
                latestOnStateChange(latestState.copy(
                    tracks = newTracks, 
                    globalParameters = latestState.globalParameters + (parameterId to newVal), 
                    focusedValue = String.format("%s: %.2f", label.uppercase(), newVal)
                ))
            } else {
                // Standard Global Update
                latestOnStateChange(latestState.copy(
                    globalParameters = latestState.globalParameters + (parameterId to newVal),
                    focusedValue = String.format("%s: %.2f", label.uppercase(), newVal)
                ))
            }
        },
        valueFormatter = valueFormatter,
        knobSize = 30.dp
    )
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

@Composable
fun AssignableKnobsPanel(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, engineColor: Color) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val track = state.tracks[state.selectedTrackIndex]
    val engineType = track.engineType
    val currentRoutings = (state.engineTypeKnobAssignments ?: emptyMap())[engineType] ?: emptyList()
    
    // Column of 4 Knobs
    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val knobCount = 4
        for (i in 0 until knobCount) {
            val routing = currentRoutings.getOrNull(i)
            val knobValue = state.knobValues[i]
            val isSelectedInLearn = state.midiLearnActive && state.midiLearnStep == 2 && state.midiLearnSelectedStrip == i + 4 // Offset 4 for Knobs
            val isLearnWait = state.midiLearnActive && state.midiLearnStep == 1
            
            // Check if this knob is driving a Macro from this slot
            val assignedMacro = state.macros.find { it.sourceType == 2 && it.sourceIndex == i }
            
            val displayLabel = if (assignedMacro != null) assignedMacro.label.uppercase()
                               else if (routing != null && routing.parameterName != "None") routing.parameterName.uppercase()
                               else "K${i+1}"
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Wrapper to handle Tap for Learning
                Box(
                    modifier = Modifier
                        .border(
                            if (isSelectedInLearn || isLearnWait) 2.dp else 0.dp, 
                            if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Transparent, 
                            RoundedCornerShape(8.dp)
                        )
                        .padding(2.dp)
                ) {
                    Knob(
                        label = displayLabel,
                        initialValue = 0.5f,
                        parameterId = -1,
                        state = state,
                        onStateChange = onStateChange,
                        nativeLib = nativeLib,
                        knobSize = 56.dp,
                        overrideValue = knobValue,
                        overrideColor = if (assignedMacro != null) Color.Cyan else if (isSelectedInLearn || isLearnWait) Color.Yellow else engineColor,
                        onValueChangeOverride = { newVal ->
                             val newValues = latestState.knobValues.toMutableList().apply { set(i, newVal) }
                             
                                 if (routing != null && routing.targetType == 1) {
                                     val isAutoLocking = latestState.isRecording && latestState.isPlaying
                                     val isLockingMode = latestState.isParameterLocking || isAutoLocking
                                     
                                     if (isLockingMode) {
                                         nativeLib.setParameterPreview(latestState.selectedTrackIndex, routing.targetId, newVal)
                                         // P-Lock
                                         val targetIdx = if (isAutoLocking) latestState.currentStep else latestState.lockingTarget?.second
                                         if (targetIdx != null) {
                                             nativeLib.setParameterLock(latestState.selectedTrackIndex, targetIdx, routing.targetId, newVal)
                                         }
                                         latestOnStateChange(latestState.copy(knobValues = newValues))
                                     } else {
                                         nativeLib.setParameter(latestState.selectedTrackIndex, routing.targetId, newVal)
                                         val updatedTracks = latestState.tracks.mapIndexed { idx, t ->
                                             if (idx == latestState.selectedTrackIndex) {
                                                 val newParams = t.parameters.toMutableMap()
                                                 newParams[routing.targetId] = newVal
                                                 t.copy(parameters = newParams)
                                             } else t
                                         }
                                         latestOnStateChange(latestState.copy(knobValues = newValues, tracks = updatedTracks))
                                     }
                                 } else {
                                     latestOnStateChange(latestState.copy(knobValues = newValues))
                                 }
                        }
                    )
                    
                    if (isLearnWait) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Transparent)
                                .pointerInput(i) {
                                    detectTapGestures(
                                        onTap = {
                                            latestOnStateChange(latestState.copy(midiLearnStep = 2, midiLearnSelectedStrip = i + 4))
                                        },
                                        onLongPress = {
                                            // UN-ASSIGN: Clear routing for this knob
                                            val newRoutings = latestState.knobRoutings.mapIndexed { idx, item ->
                                                if (idx == i) item.copy(targetType = 0, targetId = -1, parameterName = "None")
                                                else item
                                            }
                                            latestOnStateChange(latestState.copy(knobRoutings = newRoutings))
                                        }
                                    )
                                }
                        )
                    }
                }
                // Value Display below knob (like Strips)
                Text(
                     text = "${(knobValue * 100).toInt()}",
                     style = MaterialTheme.typography.labelSmall,
                     color = if (isSelectedInLearn || isLearnWait) Color.Yellow else Color.Gray,
                     fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun VerticalScrollbar(
    modifier: Modifier,
    state: LazyListState
) {
    val totalItems = state.layoutInfo.totalItemsCount
    if (totalItems == 0) return
    
    Canvas(modifier = modifier.width(3.dp)) {
        val visibleItems = state.layoutInfo.visibleItemsInfo.size
        if (visibleItems < totalItems) {
            val scrollbarHeight = size.height * (visibleItems.toFloat() / totalItems)
            val scrollbarOffset = size.height * (state.firstVisibleItemIndex.toFloat() / totalItems)
            
            drawRoundRect(
                color = Color.Cyan.copy(alpha = 0.3f),
                topLeft = Offset(0f, scrollbarOffset),
                size = Size(size.width, scrollbarHeight),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}

@Composable
fun NativeFileDialog(
    directory: File,
    onDismiss: () -> Unit,
    onFileSelected: (String) -> Unit,
    isSave: Boolean,
    extraOptions: List<Pair<String, String>> = emptyList(),
    onExport: ((Int, String, String) -> Unit)? = null,
    trackIndex: Int = -1,
    extensions: List<String> = listOf("wav"),
    title: String? = null,
    state: GrooveboxState? = null
) {
    val context = LocalContext.current
    var currentDir by remember { mutableStateOf(directory) }
    var refreshKey by remember { mutableStateOf(0) }
    var currentExtensions by remember { mutableStateOf(extensions) }
    var fileName by remember { mutableStateOf("") }
    
    val files: List<String> = remember(currentDir, refreshKey, currentExtensions) { 
        currentDir.listFiles { file -> 
            currentExtensions.any { ext -> file.extension.equals(ext, ignoreCase = true) } 
        }?.sortedBy { it.name }?.map { it.name } ?: emptyList()
    }

    Dialog(onDismissRequest = onDismiss) {
        val loomFolders = listOf("samples", "granular", "wavetables", "recordings", "sessions", "soundfonts")
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val headerText = title ?: (if (isSave) "SAVE" else "LOAD")
                
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        headerText,
                        style = MaterialTheme.typography.titleMedium,
                        color = getEngineColor(if (trackIndex != -1 && state != null) state.tracks[trackIndex].engineType else EngineType.SAMPLER)
                    )
                    Text(currentDir.name.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                // Folder Navigation Bar
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    loomFolders.forEach { folderName ->
                        val rootDir = PersistenceManager.getLoomFolder(context)
                        val folderFile = File(rootDir, folderName).apply { if (!exists()) mkdirs() }
                        val isSelected = currentDir.absolutePath == folderFile.absolutePath
                        
                        Surface(
                            onClick = { 
                                currentDir = folderFile
                                currentExtensions = when(folderName) {
                                    "wavetables" -> listOf("wav", "wt")
                                    "samples", "granular", "recordings" -> listOf("wav")
                                    "sessions" -> listOf("gbx")
                                    "soundfonts" -> listOf("sf2")
                                    else -> listOf("wav")
                                }
                                refreshKey++ 
                            },
                            color = if (isSelected) Color.White.copy(alpha = 0.1f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (isSelected) Color.White else Color.Gray.copy(alpha = 0.3f)),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                folderName.uppercase(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) Color.White else Color.Gray
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                if (isSave) {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("Filename") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Cyan,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                var renamingFile by remember { mutableStateOf<File?>(null) }
                var newName by remember { mutableStateOf("") }
                
                if (renamingFile != null) {
                    AlertDialog(
                        onDismissRequest = { renamingFile = null },
                        title = { Text("RENAME FILE") },
                        text = {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { newName = it },
                                label = { Text("New Name") }
                            )
                        },
                        confirmButton = {
                            Button(onClick = {
                                renamingFile?.let { file ->
                                    val ext = file.extension
                                    val dest = File(file.parentFile, if (newName.endsWith(".$ext")) newName else "$newName.$ext")
                                    file.renameTo(dest)
                                }
                                renamingFile = null
                                refreshKey++
                            }) { Text("RENAME") }
                        },
                        dismissButton = { TextButton(onClick = { renamingFile = null }) { Text("CANCEL") } }
                    )
                }

                val listState = rememberLazyListState()
                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        // Extra Options (Direct Actions)
                        if (extraOptions.isNotEmpty()) {
                            items(extraOptions) { (label, value) ->
                                Text(
                                    label,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onFileSelected(value)
                                            onDismiss()
                                        }
                                        .padding(vertical = 8.dp),
                                    color = Color.Yellow,
                                    fontWeight = FontWeight.Bold
                                )
                                Divider(color = Color.White.copy(alpha = 0.1f))
                            }
                        }

                        items(files) { name ->
                            var showFileMenu by remember { mutableStateOf(false) }
                            val file = File(currentDir, name)
                            
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                if (isSave) fileName = name.removeSuffix(".wav")
                                                else {
                                                    onFileSelected(file.absolutePath)
                                                    onDismiss()
                                                }
                                            },
                                            onLongClick = { showFileMenu = true }
                                        )
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Menu,
                                        contentDescription = "Handle",
                                        tint = Color.Gray.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(name, color = Color.White, modifier = Modifier.weight(1f))
                                }

                                DropdownMenu(
                                    expanded = showFileMenu,
                                    onDismissRequest = { showFileMenu = false },
                                    offset = DpOffset(x = 40.dp, y = 0.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Load") },
                                        onClick = {
                                            showFileMenu = false
                                            onFileSelected(file.absolutePath)
                                            onDismiss()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rename") },
                                        onClick = {
                                            showFileMenu = false
                                            newName = name.removeSuffix(".wav")
                                            renamingFile = file
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Copy") },
                                        onClick = {
                                            val newFile = File(directory, name.removeSuffix(".wav") + "_copy.wav")
                                            file.copyTo(newFile, overwrite = true)
                                            showFileMenu = false
                                            refreshKey++
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = Color.Red) },
                                        onClick = {
                                            file.delete()
                                            showFileMenu = false
                                            refreshKey++
                                        }
                                    )
                                    if (!isSave) {
                                        Divider(color = Color.DarkGray)
                                        DropdownMenuItem(
                                            text = { Text("Export to AAC") },
                                            onClick = {
                                                showFileMenu = false
                                                onExport?.invoke(trackIndex, file.absolutePath, "AAC")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Export to FLAC") },
                                            onClick = {
                                                showFileMenu = false
                                                onExport?.invoke(trackIndex, file.absolutePath, "FLAC")
                                            }
                                        )
                                    }
                                }
                            }
                            Divider(color = Color.White.copy(alpha = 0.05f))
                        }
                    }
                    
                    // Simple custom scrollbar indicator
                    if (files.size > 5) {
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            state = listState
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.Gray) }
                    if (isSave) {
                        Button(
                            onClick = {
                                val finalName = if (fileName.endsWith(".wav")) fileName else "$fileName.wav"
                                onFileSelected(File(directory, finalName).absolutePath)
                                onDismiss()
                            },
                            enabled = fileName.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                        ) {
                            Text("SAVE", color = Color.Black)
                        }
                    }
                }
            }
        }
    }
}

