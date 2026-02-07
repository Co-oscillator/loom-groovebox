package com.groovebox.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.awaitFirstDown

import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.StepState
import com.groovebox.midi.EmpledManager
import com.groovebox.ui.components.Knob
import com.groovebox.ui.theme.getEngineColor

@Composable
fun SequencerView(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, empledManager: EmpledManager) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    // Ensure we have a valid selection
    val selectedTrackIndex = state.selectedTrackIndex.coerceIn(0, state.tracks.lastIndex)
    val track = state.tracks[selectedTrackIndex]
    
    // Calculations for layout
    val screenConfig = LocalConfiguration.current
    val screenRatio = screenConfig.screenWidthDp.toFloat() / screenConfig.screenHeightDp.toFloat()
    val isWideScreen = screenRatio > 1.8f && screenConfig.screenHeightDp < 500
    
    Column(modifier = Modifier.fillMaxSize()) {
        // Main UI Components
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Main Sequencing Area
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                 // Header: Clear / Bank / Grid Controls
                 Row(
                     modifier = Modifier.fillMaxWidth().height(40.dp),
                     horizontalArrangement = Arrangement.SpaceBetween,
                     verticalAlignment = Alignment.CenterVertically
                 ) {
                     // Bank Select
                     Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                         (0..3).forEach { bank ->
                             val isSelected = state.currentSequencerBank == bank && !state.is64StepView
                             val hasSteps = if (track.engineType == EngineType.FM_DRUM) {
                                  // Check drum steps for this bank
                                  val drumInst = track.selectedFmDrumInstrument
                                  track.drumSteps.getOrNull(drumInst)?.subList(bank * 16, (bank + 1) * 16)?.any { it.active } ?: false
                             } else {
                                  track.steps.subList(bank * 16, (bank + 1) * 16).any { it.active }
                             }
                             
                             val engineColor = getEngineColor(track.engineType)
                             
                             Button(
                                 onClick = { latestOnStateChange(latestState.copy(currentSequencerBank = bank, is64StepView = false)) },
                                 modifier = Modifier.height(32.dp).width(32.dp),
                                 contentPadding = PaddingValues(0.dp),
                                 colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) engineColor else if (hasSteps) Color.Gray else Color.DarkGray)
                             ) { Text("${bank + 1}", color = if (isSelected) Color.Black else Color.White, fontSize = 10.sp) }
                         }
                     }
                     
                     // Sequencer Tools (Copy/Paste/64)
                     Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                         // COPY
                         Button(
                             onClick = {
                                 if (track.engineType == EngineType.FM_DRUM) {
                                     latestOnStateChange(latestState.copy(copiedDrumSteps = track.drumSteps, copiedSteps = null))
                                 } else {
                                     latestOnStateChange(latestState.copy(copiedSteps = track.steps, copiedDrumSteps = null))
                                 }
                             },
                             modifier = Modifier.height(32.dp).width(45.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                         ) { Text("CPY", fontSize = 10.sp, color = Color.White) }

                         // PASTE
                         val hasClipboard = latestState.copiedSteps != null || latestState.copiedDrumSteps != null
                         Button(
                             onClick = {
                                 if (hasClipboard) {
                                     if (track.engineType == EngineType.FM_DRUM && latestState.copiedDrumSteps != null) {
                                         val newDrumSteps = latestState.copiedDrumSteps!!
                                         // Update State
                                         latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == selectedTrackIndex) t.copy(drumSteps = newDrumSteps) else t }))
                                         // Update Native Engine (Sync)
                                         nativeLib.clearSequencer(selectedTrackIndex)
                                         newDrumSteps.forEachIndexed { drumIdx, steps ->
                                              if (drumIdx < 8) { // Safety check
                                                  steps.forEachIndexed { stepIdx, step -> 
                                                      if (step.active) {
                                                           // Note: For drum tracks, we need to correctly trigger the specific drum voice note
                                                           val drumNote = 60 + drumIdx
                                                           // We assume the copied steps have valid notes, but for drums we enforce the drum note mapping
                                                           val finalNotes = if (step.notes.isNotEmpty()) step.notes.toIntArray() else intArrayOf(drumNote)
                                                           nativeLib.setStep(selectedTrackIndex, stepIdx, true, finalNotes, step.velocity, step.ratchet, step.punch, step.probability, step.gate, step.isSkipped)
                                                      }
                                                  }
                                              }
                                         }
                                     } else if (track.engineType != EngineType.FM_DRUM && latestState.copiedSteps != null) {
                                         val newSteps = latestState.copiedSteps!!
                                         latestOnStateChange(latestState.copy(tracks = latestState.tracks.mapIndexed { idx, t -> if (idx == selectedTrackIndex) t.copy(steps = newSteps) else t }))
                                         nativeLib.clearSequencer(selectedTrackIndex)
                                         newSteps.forEachIndexed { stepIdx, step ->
                                              if (step.active) {
                                                  nativeLib.setStep(selectedTrackIndex, stepIdx, true, step.notes.toIntArray(), step.velocity, step.ratchet, step.punch, step.probability, step.gate, step.isSkipped)
                                              }
                                         }
                                     }
                                 }
                             },
                             enabled = hasClipboard,
                             modifier = Modifier.height(32.dp).width(45.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = if (hasClipboard) Color.Gray else Color.DarkGray.copy(alpha = 0.5f))
                         ) { Text("PST", fontSize = 10.sp, color = Color.White) }
                        
                         // 64-Step View Toggle
                         Button(
                            onClick = { latestOnStateChange(latestState.copy(is64StepView = !latestState.is64StepView)) },
                            modifier = Modifier.height(32.dp).width(32.dp),
                             contentPadding = PaddingValues(0.dp),
                             colors = ButtonDefaults.buttonColors(containerColor = if (latestState.is64StepView) Color.Cyan else Color.DarkGray)
                         ) { Text("64", fontSize = 10.sp, color = if (latestState.is64StepView) Color.Black else Color.White) }
                     }
                     
                     // Right Side: Clear Button with "Hold to Clear" / "Confirm" logic
                     // simplified for extraction: using original logic
                     var showClearAllConfirm by remember { mutableStateOf(false) }
                     
                     if (showClearAllConfirm) {
                          AlertDialog(
                              onDismissRequest = { showClearAllConfirm = false },
                              title = { Text("Clear All Tracks?") },
                              text = { Text("This will clear sequences for ALL tracks. Are you sure?") },
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
                 
                 Spacer(modifier = Modifier.height(8.dp))

                 // Drum Instrument Selectors (for Drum Engines)
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
                        listOf("KICK", "SNARE", "OHH", "CHH", "CYMB").forEachIndexed { i, label ->
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
                } else {
                     // Check for Sampler Chop Mode
                     val samplerMode = track.parameters[320] ?: 0f
                     val isSamplerChops = track.engineType == EngineType.SAMPLER && samplerMode >= 0.6f
                     
                     if (isSamplerChops) {
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
                }
                
                // 16 Step Pads or 64 Step Grid
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BoxWithConstraints {
                        val is64 = latestState.is64StepView
                        val columns = if (is64) 8 else 4
                        val padCount = if (is64) 64 else 16
                        val spacing = if (is64) 4.dp else 8.dp
                        val padSize = minOf(maxWidth / (columns + 0.2f), maxHeight / (columns + 0.2f))
                        
                        // We need `isMultiTrack` to determine correct step indexing
                        val isMultiTrack = track.engineType == EngineType.FM_DRUM 
                        
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
                                
                                // Check for Ghost Notes (active steps on other drum voices)
                                val isGhostActive = if (isMultiTrack && !step.active && !step.isSkipped) {
                                    track.drumSteps.indices.any { idx ->
                                        idx != track.selectedFmDrumInstrument && 
                                        track.drumSteps.getOrNull(idx)?.getOrNull(stepIndex)?.active == true
                                    }
                                } else false

                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(
                                            when {
                                                step.isSkipped -> Color.Black
                                                step.active -> engineColor
                                                isGhostActive -> engineColor.copy(alpha = 0.3f) // Ghost Note Highlight
                                                else -> lerp(Color.DarkGray, engineColor, 0.2f)
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
fun PadOptionPopup(
    onDismiss: () -> Unit,
    stepState: StepState,
    onApply: (Int, Boolean, Float, Float, List<Int>, Float, Boolean, Map<Int, Float>) -> Unit,
    onParamLock: () -> Unit
) {
    Popup(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.width(300.dp).wrapContentHeight().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                // Header: Step Details
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Step Options", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }
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

                // Piano & Octave Control
                // Determine active octaves for highlighting
                val activeOctaves = stepState.notes.map { it / 12 }.toSet()
                
                // Safe initial octave: Prioritize first note, else default to 5
                var viewingOctave by remember(stepState.notes) { 
                    mutableStateOf(if (stepState.notes.isNotEmpty()) stepState.notes.first() / 12 else 5) 
                }
                
                // Octave Selector with Highlight
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (viewingOctave > 0) viewingOctave-- },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(40.dp)
                    ) { Text("▼", color = if (viewingOctave > 0 && activeOctaves.any { it < viewingOctave }) Color.Cyan else Color.Gray, fontSize = 20.sp) }
                    
                    Text("C$viewingOctave", color = if (activeOctaves.contains(viewingOctave)) Color.Cyan else Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    
                    Button(
                        onClick = { if (viewingOctave < 9) viewingOctave++ },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp),
                         modifier = Modifier.width(40.dp)
                    ) { Text("▲", color = if (viewingOctave < 9 && activeOctaves.any { it > viewingOctave }) Color.Cyan else Color.Gray, fontSize = 20.sp) }
                }

                Spacer(modifier = Modifier.height(8.dp))
                
                // 4x3 Note Grid
                val noteLabels = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                val blackKeys = setOf(1, 3, 6, 8, 10)
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Rows of 4
                    for (row in 0..2) {
                        Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0..3) {
                                val noteOffset = row * 4 + col
                                val currentNote = viewingOctave * 12 + noteOffset
                                val isBright = stepState.notes.contains(currentNote)
                                // Show "Dim" if this note exists in ANY other octave
                                val isDim = !isBright && stepState.notes.any { it % 12 == noteOffset }
                                val isBlackKey = blackKeys.contains(noteOffset)
                                
                                // Color Logic
                                val bgColor = when {
                                    isBright -> Color.Cyan
                                    isDim -> Color.Cyan.copy(alpha = 0.3f)
                                    isBlackKey -> Color.Black
                                    else -> Color.LightGray
                                }
                                
                                val textColor = when {
                                    isBright -> Color.Black
                                    isDim -> Color.White
                                    isBlackKey -> Color.White
                                    else -> Color.Black
                                }
                                
                                Button(
                                    onClick = {
                                        val newNotes = if (stepState.notes.contains(currentNote)) {
                                            stepState.notes - currentNote
                                        } else {
                                            stepState.notes + currentNote
                                        }
                                        onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, newNotes.sorted(), stepState.velocity, stepState.isSkipped, stepState.parameterLocks)
                                    },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    colors = ButtonDefaults.buttonColors(containerColor = bgColor),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(noteLabels[noteOffset], color = textColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
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
                val gateLabel = if (currentGate < 1.0f) "${(currentGate * 100).toInt()}%" else "${currentGate}x"
                Text("Gate Length: $gateLabel", style = MaterialTheme.typography.labelMedium)
                 Slider(
                    value = currentGate,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, stepState.probability, it, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks) },
                    valueRange = 0.1f..4.0f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Probability
                Text("Probability: ${(stepState.probability * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = stepState.probability,
                    onValueChange = { onApply(stepState.ratchet, stepState.punch, it, stepState.gate, stepState.notes, stepState.velocity, stepState.isSkipped, stepState.parameterLocks) },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                 Button(
                    onClick = { onApply(stepState.ratchet, stepState.punch, stepState.probability, stepState.gate, stepState.notes, stepState.velocity, !stepState.isSkipped, stepState.parameterLocks) },
                    colors = ButtonDefaults.buttonColors(containerColor = if (stepState.isSkipped) Color.Green else Color.DarkGray), 
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (stepState.isSkipped) "RESTORE STEP (UNSKIP)" else "SKIP STEP", color = Color.White) }
                
                 Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
