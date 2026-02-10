@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.groovebox.ui.views

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.components.EngineIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.filled.Close

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.ArpMode
import com.groovebox.ScaleLogic
import com.groovebox.ScaleType
import com.groovebox.midi.EmpledManager
import com.groovebox.midi.MidiManager
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.theme.getEngineColor
import com.groovebox.ui.LocalFocusedValue
import com.groovebox.*
// IMPORTANT: We need to copy isBlackKey and getNoteLabel here or import them if they remain in MainActivity (which is bad practice if extracted).
// For now I will redefine them here as private or internal to unlink dependency.

fun isBlackKey(midiNote: Int): Boolean {
    val noteInOctave = midiNote % 12
    return noteInOctave == 1 || noteInOctave == 3 || noteInOctave == 6 || noteInOctave == 8 || noteInOctave == 10
}

fun getNoteLabel(midiNote: Int): String {
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[midiNote % 12] + (midiNote / 12 - 1)
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
    isNoteActive: Boolean = false,
    shape: Shape = RoundedCornerShape(8.dp),
    isSolidStyle: Boolean = false,
    customWidth: Dp? = null,
    customHeight: Dp? = null
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
                val isActive = isVisuallyPressed || isHeld || isMidiTriggered || isNoteActive // Combined "Active" state
                
                // LED Sync for EMP16
                LaunchedEffect(isVisuallyPressed, isHeld, isMidiTriggered, isPlaying, currentStep, latestState.currentSequencerBank) {
                    if (latestState.currentSequencerBank == 0) {
                        val finalColor = if (isActive) padColor 
                                        else if (isPlaying && (currentStep % 16) == padIndex) androidx.compose.ui.graphics.Color.White
                                        else padColor.copy(alpha = 0.2f)
                        
                        empledManager?.updatePadColorCompose(padIndex, finalColor)
                    }
                }

                val cols = if (latestState.gridMode == GridMode.GRID_6X6) 6 else 4
                val row = padIndex / cols
                val col = padIndex % cols
                val isPlayheadHighlight = if (latestState.gridMode == GridMode.GRID_6X6) {
                    // Map playhead to the top-left 4x4 grid of the 6x6 (representing first 16 steps)
                    row < 4 && col < 4 && (currentStep % 16) == (row * 4 + col)
                } else {
                    currentStep % 16 == padIndex
                }

                val backgroundColor = if (isSolidStyle) {
                    if (isActive) Color.Black else padColor.copy(alpha = 0.8f) 
                } else {
                    if (isActive) padColor.copy(alpha = 0.9f)
                    else if (isPlaying && isPlayheadHighlight) Color.White.copy(alpha = 0.3f)
                    else if (latestState.tracks[latestState.selectedTrackIndex].engineType == EngineType.FM_DRUM && 
                             latestState.tracks[latestState.selectedTrackIndex].selectedFmDrumInstrument == (note - 60)) padColor.copy(alpha = 0.2f)
                    else Color.Transparent
                }

                val borderColor = if (isSolidStyle) {
                    if (isActive) padColor else Color.Transparent // Active = Black Body + Color Border
                } else {
                    padColor.copy(alpha = 0.6f)
                }
                
                val borderWidth = if (isSolidStyle && isActive) 3.dp else 1.5.dp

                Box(
                    modifier = Modifier
                        .size(width = customWidth ?: padSize, height = customHeight ?: padSize)
                        .background(backgroundColor, shape)
                        .border(borderWidth, borderColor, shape),
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
                                            if (triggeredNote in 0..127) {
                                                nativeLib.triggerNote(currentTIdx, triggeredNote, 100)
                                            }
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
                                            if (triggeredNote in 0..127) {
                                                nativeLib.releaseNote(currentTIdx, triggeredNote)
                                            }
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
                    61 to "Snare",
                    62 to "Cymb",
                    63 to "Hat C",
                    64 to "Hat O"
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


    val HexagonShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
        val width = size.width
        val height = size.height
        // Pointy-topped hexagon
        moveTo(width * 0.5f, 0f)
        lineTo(width, height * 0.25f)
        lineTo(width, height * 0.75f)
        lineTo(width * 0.5f, height)
        lineTo(0f, height * 0.75f)
        lineTo(0f, height * 0.25f)
        close()
    }

    @Composable
    fun TonnetzGrid(
        state: GrooveboxState,
        nativeLib: NativeLib,
        engineColor: Color,
        onStateChange: (GrooveboxState) -> Unit,
        empledManager: EmpledManager? = null
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
            val density = LocalDensity.current.density
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            
            // Determine device type with override
            val configuration = LocalConfiguration.current
            val isTabletDetected = configuration.screenWidthDp >= 600 && configuration.screenHeightDp >= 480
            
            // Layout Mode: 0=Auto, 1=Phone, 2=Tablet
            val isTablet = when(state.uiLayoutMode) {
                1 -> false // Force Phone
                2 -> true  // Force Tablet
                else -> isTabletDetected // Auto
            }
            
            // Target Density: 
            // Tablet: ~10 rows, 10 cols (alternating 10/9)
            // Phone: 9 rows, alternating 8/9 pads
            val targetRows = if (isTablet) 10 else 9
            val targetCols = if (isTablet) 10 else 9 // Max cols

            // Geometry constants (normalized to radius 1)
            // Hex Width (point to point horizontal) = 2 * radius (No, flat top?)
            // Wait, previous shape was Pointy Topped.
            // Pointy Topped:
            //   Width = sqrt(3) * radius
            //   Height = 2 * radius
            //   Horiz spacing = Width
            //   Vert spacing = 3/4 * Height
            // My previous code:
            //   hexHeight = hexRadiusPx * 2f (Height)
            //   colWidth = hexHeight * 0.866f (Width? No. 2 * r * sqrt(3)/2 = r * sqrt(3). Correct.)
            
            // To fit targetRows/Cols:
            // Width needed = targetCols * (sqrt(3) * r) + (0.5 * sqrt(3) * r for offset)
            // Height needed = targetRows * (1.5 * r) + (0.5 * r)
            
            val sqrt3 = 1.732f
            val widthFactor = targetCols * sqrt3 + (0.5f * sqrt3)
            val heightFactor = targetRows * 1.5f + 0.5f
            
            val radiusByWidth = widthPx / widthFactor
            val radiusByHeight = heightPx / heightFactor
            
            // Use the smaller radius to fit within bounds
            val hexRadiusPx = minOf(radiusByWidth, radiusByHeight) * 0.95f // 5% margin
            val hexRadius = (hexRadiusPx / density).dp
            
            val hexHeight = hexRadiusPx * 2f
            val rowHeight = hexHeight * 0.75f
            val colWidth = hexHeight * 0.866f // sqrt(3)/2
            
            val rows = targetRows
            val cols = targetCols // We iterate 0..9, but modify checks
            
            // Center the grid content
            val totalGridWidth = (cols) * colWidth + (colWidth * 0.5f)
            val totalGridHeight = rows * rowHeight + (hexHeight * 0.25f) // Correct total height calc
            val startX = (widthPx - totalGridWidth) / 2f
            val startY = (heightPx - totalGridHeight) / 2f
            
            val rootNote = state.rootNote
            
            (0 until rows).forEach { r ->
                // Tablet: Alternating 10, 9, 10, 9...
                // Phone: Alternating 8, 9, 8, 9... (Top row 8)
                val numColsInRow = if (isTablet) {
                    if (r % 2 == 0) 10 else 9
                } else {
                    if (r % 2 == 0) 8 else 9
                }
                
                (0 until numColsInRow).forEach { c ->
                    // Center the shorter rows based on configuration
                    
                    val isShortRow = if (isTablet) (r % 2 != 0) else (r % 2 == 0)
                    val xOffset = if (isShortRow) colWidth * 0.5f else 0f
                    
                    val xPos = startX + c * colWidth + xOffset
                    val yPos = startY + r * rowHeight
                    
                    // Note Mapping
                    // Center (r=rows/2, c=cols/2) to Root.
                    val rCenter = rows / 2
                    val cCenter = if (isTablet) 5 else 4 // Approx center col
                    
                    val rRel = (rows - 1 - r) - rCenter // Invert Y so up is higher pitch
                    val cRel = c - cCenter
                    
                    val noteVal = rootNote + (cRel * 7) + (rRel * 4)
                    
                    if (noteVal in 0..127) {
                        val isBlack = isBlackKey(noteVal)
                        val isRootRel = (noteVal % 12) == (state.rootNote % 12)
                        
                        // New Shading Logic
                        val pColor = if (isRootRel) {
                            engineColor // Root: Full color
                        } else if (isBlack) {
                            // "Black" key: Darker shade of engine color
                            androidx.compose.ui.graphics.lerp(Color.Black, engineColor, 0.6f)
                        } else {
                            // "White" key: Slightly desaturated or standard engine color
                            engineColor.copy(alpha = 0.6f) 
                        }
                        
                        // Spacing: Shrink visual pad within grid cell
                        val spacing = 6.dp
                        // Aspect Ratio Fix: Hexagon is 0.866 wide relative to height
                        // We calculate Visual Height first (vertical constraint)
                        val visualH = (hexRadius * 2 - spacing).coerceAtLeast(10.dp)
                        // Then calculate Visual Width to maintain 0.866 aspect ratio
                        val visualW = (visualH * 0.866f)
                        
                        Box(modifier = Modifier
                            .offset { IntOffset(xPos.toInt(), yPos.toInt()) }
                            .size(hexRadius * 2), // Grid cell size
                            contentAlignment = Alignment.Center
                        ) {
                             PlayingPad(
                                padIndex = -1,
                                note = noteVal,
                                padSize = 0.dp, // Ignore square size, use custom
                                customWidth = visualW,
                                customHeight = visualH,
                                padColor = pColor, // Use the computed shaded color
                                isPlaying = state.isPlaying,
                                currentStep = state.currentStep,
                                nativeLib = nativeLib,
                                latestState = state,
                                onStateChange = onStateChange,
                                empledManager = empledManager,
                                shape = HexagonShape,
                                isSolidStyle = true
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
fun PadGrid(
    rows: Int, cols: Int, padSize: Dp, spacing: Dp, 
    track: com.groovebox.TrackState, state: GrooveboxState, scaleNotes: List<Int>, 
    engineColor: Color, nativeLib: NativeLib, 
    latestState: GrooveboxState, onStateChange: (GrooveboxState) -> Unit,
    latestOnStateChange: (GrooveboxState) -> Unit,
    empledManager: EmpledManager? = null
) {
    if (state.gridMode == GridMode.TONNETZ) {
         TonnetzGrid(
            state = latestState,
            nativeLib = nativeLib,
            engineColor = engineColor,
            onStateChange = onStateChange,
            empledManager = empledManager
        )
    } else {
        var activeNoteMask by remember { mutableStateOf(0) }
        LaunchedEffect(state.selectedTrackIndex, state.isPlaying) {
            while(true) {
                activeNoteMask = nativeLib.getActiveNoteMask(state.selectedTrackIndex)
                kotlinx.coroutines.delay(32)
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) {
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
                                        1 -> 61 // Snare
                                        2 -> 62 // "Cymbal" (Clap engine voice, matches Param Screen)
                                        3 -> 63 // Hat Closed
                                        4 -> 64 // Hat Open
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
                                                empledManager = empledManager,
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
        }
    }
}

@Composable
fun TouchStripsPanel(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, engineColor: Color) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val config = LocalConfiguration.current
    val isWideScreenAuto = (config.screenWidthDp.toFloat() / config.screenHeightDp.toFloat()) > 1.7f
    
    val isWideScreen = when(state.uiLayoutMode) {
        1 -> false // Force Phone (Narrower/Taller strip panel)
        2 -> true  // Force Tablet (Wider/Shorter strip panel)
        else -> isWideScreenAuto
    }

    // Sidebar Area (Strips + Transport)
    Row(
        modifier = Modifier
            .width(if (isWideScreen) 165.dp else 220.dp) 
            .fillMaxHeight()
            .padding(vertical = if (isWideScreen) 12.dp else 16.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (isWideScreen) 4.dp else 12.dp)
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
                    modifier = Modifier.fillMaxHeight().width(if (isWideScreen) 24.dp else 36.dp),
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
                                                      nativeLib.setRouting(latestState.selectedTrackIndex, -1, 10 + macroIdx, 5, 1.0f, routing.targetId)
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
fun AssignableKnobsPanel(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib, engineColor: Color) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val track = state.tracks[state.selectedTrackIndex]
    val engineType = track.engineType
    val currentRoutings = (state.engineTypeKnobAssignments ?: emptyMap())[engineType] ?: emptyList()
    
    val config = LocalConfiguration.current
    val ratio = config.screenWidthDp.toFloat() / config.screenHeightDp.toFloat()
    // STRICTER WideScreen: Must be > 600dp width AND > 480dp height (Tablet)
    val isTabletAuto = config.screenWidthDp >= 600 && config.screenHeightDp >= 480 && ratio > 1.3f
    
    val isWideScreen = when(state.uiLayoutMode) {
        1 -> false // Force Phone
        2 -> true  // Force Tablet
        else -> isTabletAuto
    }

    // Column of 4 Knobs
    Column(
        modifier = Modifier
            .padding(start = if (isWideScreen) 0.dp else 24.dp) // Force 24dp on Phone
            .width(if (isWideScreen) 56.dp else 80.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp),
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
                               else "VOL"
            
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
                     fontSize = if (isWideScreen) 9.sp else 11.sp
                )
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
    if (!isOpen) return
    val track = state.tracks[trackIndex]

    key(trackIndex) { // Force recomposition when track changes
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight(0.9f)
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Track ${trackIndex + 1} Settings", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Engine Type", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val engines = EngineType.values()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(engines.size) { idx ->
                            val engine = engines[idx]
                            val isSelected = track.engineType == engine
                            
                            Card(
                                modifier = Modifier
                                    .height(80.dp)
                                    .clickable {
                                        if (!isSelected) {
                                            nativeLib.setEngineType(trackIndex, engine.ordinal)
                                            val newTrack = track.copy(engineType = engine)
                                            val newTracks = state.tracks.toMutableList()
                                            newTracks[trackIndex] = newTrack
                                            onStateChange(state.copy(tracks = newTracks))
                                        }
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) getEngineColor(engine) else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    EngineIcon(engine, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        engine.name.replace("_", " "),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mute Track")
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = track.isMuted,
                            onCheckedChange = { muted ->
                                nativeLib.setTrackMute(trackIndex, muted)
                                val newTrack = track.copy(isMuted = muted)
                                val newTracks = state.tracks.toMutableList()
                                newTracks[trackIndex] = newTrack
                                onStateChange(state.copy(tracks = newTracks))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
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
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("ARPEGGIATOR", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White) }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val allModes = ArpMode.values().filter { it != ArpMode.OFF }
                    val row1 = allModes.take(5)
                    val row2 = allModes.drop(5)
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { // SpaceBetween for full width
                            row1.forEach { mode ->
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
                                    contentPadding = PaddingValues(horizontal = 8.dp), // Compact padding
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp) // Equal width
                                ) { 
                                    Text(
                                        mode.name.replace("_", " "), 
                                        color = if (isSelected) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) 
                                }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row2.forEach { mode ->
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
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                                ) { 
                                    Text(
                                        mode.name.replace("_", " "), 
                                        color = if (isSelected) Color.Black else Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) 
                                }
                            }
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
                                            modifier = Modifier.height(28.dp).width(54.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color.Cyan else Color.DarkGray),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = if (isSelected) Color.Black else Color.White)
                                        }
                                    }
                                }
                                
                                // Randomize Rhythm (Refinement v1.11.2)
                                Column(verticalArrangement = Arrangement.Center, modifier = Modifier.padding(start = 8.dp)) {
                                    Button(
                                        onClick = {
                                            val currentTrack = state.tracks[state.selectedTrackIndex]
                                            val currentConfig = currentTrack.arpConfig
                                            val newRhythms = currentConfig.rhythms.mapIndexed { laneIdx, _ ->
                                                if (laneIdx == 0) List(16) { Math.random() < 0.7 }
                                                else List(16) { false }
                                            }
                                            val updatedConfig = currentConfig.copy(rhythms = newRhythms)
                                            val updatedTracks = state.tracks.mapIndexed { i, t -> if (i == state.selectedTrackIndex) t.copy(arpConfig = updatedConfig) else t }
                                            onStateChange(state.copy(tracks = updatedTracks))
                                            nativeLib.setArpConfig(
                                                state.selectedTrackIndex,
                                                updatedConfig.mode.ordinal,
                                                updatedConfig.octaves,
                                                updatedConfig.inversion,
                                                updatedConfig.isLatched,
                                                updatedConfig.isMutated,
                                                newRhythms.map { it.toBooleanArray() }.toTypedArray(),
                                                updatedConfig.randomSequence.toIntArray()
                                            )
                                        },
                                        modifier = Modifier.height(28.dp).width(64.dp), // Slightly wider, matching height
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.Magenta),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text("RAND RHY", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = Color.White)
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
                    

                        // Rhythm Editor (3 Lanes)
                        Text("RHYTHM PATTERNS (16 STEPS)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text("(Bottom=Root, Upper=Polyphonic Cycle)", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // 3 Lanes (Reverse order: Lane 2 (Top) -> Lane 0 (Bottom/Root))
                        // Swap Labels to ensure Root is clearly bottom (which is Lane 0)
                        // Lane 2=Top, Lane 0=Bottom. The loop iterates 2 downTo 0. 
                        // So Lane 2 (Top) is rendered first.
                        val laneLabels = listOf("ROOT", "UP 1", "UP 2") // Indices 0,1,2.
                        val laneColors = listOf(Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA)) 
                        (2 downTo 0).forEach { laneIdx ->
                             Row(
                                 verticalAlignment = Alignment.CenterVertically, 
                                 modifier = Modifier
                                     .fillMaxWidth()
                                     .padding(vertical = 2.dp) // Reduced vertical padding
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
                                                 .aspectRatio(1.2f) 
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
                             // No Divider here - clean spacing only
                          }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                    }
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
    
    val scaleNotes = remember(state.rootNote, state.scaleType, state.gridMode) {
        val count = if (state.gridMode == GridMode.GRID_6X6) 36 else 16
        ScaleLogic.generateScaleNotes(state.rootNote, state.scaleType, count)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        TouchStripsPanel(state, onStateChange, nativeLib, engineColor)
        AssignableKnobsPanel(state, onStateChange, nativeLib, engineColor)
        
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val screenConfig = LocalConfiguration.current
                val screenRatio = screenConfig.screenWidthDp.toFloat() / screenConfig.screenHeightDp.toFloat()
                
                val currentMaxWidth = maxWidth
                val currentMaxHeight = maxHeight
                val rows = if (state.gridMode == GridMode.GRID_6X6) 6 else 4
                val cols = if (state.gridMode == GridMode.GRID_6X6) 6 else 4
                val isWideScreen = screenRatio > 1.8f && screenConfig.screenHeightDp < 500
                val spacing = 6.dp
                
                Column(modifier = Modifier.fillMaxSize()) {
                    // Spacer(modifier = Modifier.height(8.dp))
                    
                    Box(modifier = Modifier.weight(1f)) {
                        if (isWideScreen) {
                    // --- PHONE LAYOUT (Side Column) ---
                    val controlsWidth = 90.dp
                    val padSize = minOf(
                        (currentMaxWidth - controlsWidth - (spacing * (cols - 1))) / cols,
                        (currentMaxHeight - (spacing * (rows - 1))) / rows,
                        120.dp
                    ).coerceAtLeast(40.dp)
                    val gridWidth = (padSize * cols) + (spacing * (cols - 1))

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // --- Main Pad Area (Center) ---
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val padSize = minOf(
                                (currentMaxWidth - controlsWidth - (spacing * (cols + 1))) / cols,
                                (currentMaxHeight - (spacing * (rows + 1))) / rows,
                                120.dp
                            ).coerceAtLeast(40.dp)
                            
                            val is64 = latestState.is64StepView
                            val columns = if (is64) 8 else if (state.gridMode == GridMode.GRID_6X6) 6 else 4
                            val gridSpacing = if (is64) 4.dp else spacing

                            LazyVerticalGrid(
                                columns = GridCells.Fixed(columns),
                                modifier = Modifier.size(padSize * columns + (gridSpacing * (columns - 1))),
                                verticalArrangement = Arrangement.spacedBy(gridSpacing),
                                horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                                userScrollEnabled = false
                            ) {
                                // Pad items logic... (rest remains similar, just ensuring it's centered)
                                items(if (is64) 64 else if (state.gridMode == GridMode.GRID_6X6) 36 else 16) { i ->
                                    val samplerMode = track.parameters[320] ?: 0f
                                    val isChopMode = track.engineType == EngineType.SAMPLER && samplerMode >= 0.6f
                                    val numSlices = if (isChopMode) (((track.parameters[340] ?: 0f) * 14f).toInt() + 2) else 0

                                    val note = if (track.engineType == EngineType.FM_DRUM) {
                                        60 + (i % 16)
                                    } else if (isChopMode) {
                                        if (i < numSlices) 60 + i else -1
                                    } else if (track.engineType == EngineType.ANALOG_DRUM) {
                                        val localIdx = if (i >= 8) i - 8 else i
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
                                        scaleNotes.getOrElse(i) { state.rootNote + i }
                                    }

                                    if (note != -1) {
                                        val isInactiveDrumPad = (track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM) && i >= 16
                                        if (!isInactiveDrumPad) {
                                            val isBlack = if (track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM || isChopMode) false else isBlackKey(note)
                                            val padColor = if (isBlack) androidx.compose.ui.graphics.lerp(Color.DarkGray, engineColor, 0.4f) else engineColor

                                            key(state.selectedTrackIndex, i) {
                                                PlayingPad(
                                                    padIndex = i,
                                                    note = note,
                                                    padSize = padSize,
                                                    padColor = padColor,
                                                    isPlaying = state.isPlaying,
                                                    currentStep = state.currentStep,
                                                    nativeLib = nativeLib,
                                                    latestState = latestState,
                                                    onStateChange = onStateChange,
                                                    isChopMode = isChopMode
                                                )
                                            }
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.size(padSize))
                                    }
                                }
                            }
                        }

                        // --- Vertical Control Column (Right Side on Phone) ---
                        Column(
                            modifier = Modifier.width(controlsWidth).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // ROOT
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { showTransposeMenu = true },
                                    modifier = Modifier.height(36.dp).width(70.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(getNoteLabel(state.rootNote).filter { !it.isDigit() }, style = MaterialTheme.typography.titleMedium, fontSize = 14.sp, color = Color.White)
                                }
                                Text("ROOT", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                            }
                            // SCALE
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { showScaleMenu = true },
                                    enabled = state.gridMode != GridMode.TONNETZ,
                                    modifier = Modifier.height(36.dp).width(70.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (state.gridMode == GridMode.TONNETZ) Color.DarkGray.copy(alpha=0.3f) else Color.DarkGray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(state.scaleType.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, maxLines = 1, 
                                         color = if (state.gridMode == GridMode.TONNETZ) Color.Gray else Color.White)
                                }
                                Text("SCALE", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                            }
                            // OCTAVE
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(
                                        onClick = { 
                                            val newRoot = (state.rootNote - 12).coerceAtLeast(0)
                                            onStateChange(state.copy(rootNote = newRoot))
                                            nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                                        },
                                        modifier = Modifier.size(32.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("-", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                                    Button(
                                        onClick = { 
                                            val newRoot = (state.rootNote + 12).coerceAtMost(110)
                                            onStateChange(state.copy(rootNote = newRoot))
                                            nativeLib.setScaleConfig(newRoot, state.scaleType.intervals.toIntArray())
                                        },
                                        modifier = Modifier.size(32.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("+", style = MaterialTheme.typography.titleMedium, color = Color.White) }
                                }
                                Text("OCTAVE", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                            }
                            // GRID
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(
                                    onClick = { 
                                        val newMode = when(state.gridMode) {
                                            GridMode.GRID_4X4 -> GridMode.GRID_6X6
                                            GridMode.GRID_6X6 -> GridMode.TONNETZ
                                            GridMode.TONNETZ -> GridMode.GRID_4X4
                                        }
                                        onStateChange(state.copy(gridMode = newMode)) 
                                    },
                                    modifier = Modifier.height(36.dp).width(70.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (state.gridMode != GridMode.GRID_4X4) Color(0xFF6200EE) else Color.DarkGray),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(when(state.gridMode) {
                                        GridMode.GRID_4X4 -> "4x4"
                                        GridMode.GRID_6X6 -> "6x6"
                                        GridMode.TONNETZ -> "TON"
                                    }, style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                                Text("GRID", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                            }
                            // ARP
                            val currentState by rememberUpdatedState(latestState)
                            if (track.engineType != EngineType.FM_DRUM && track.engineType != EngineType.ANALOG_DRUM) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    val isArpOn = track.arpConfig.mode != ArpMode.OFF
                                    val isLatched = track.arpConfig.isLatched
                                    Box(
                                        modifier = Modifier
                                            .width(70.dp).height(36.dp)
                                            .background(if (isLatched) Color.Yellow else if (isArpOn) Color.Yellow.copy(alpha = 0.3f) else Color.DarkGray, RoundedCornerShape(8.dp))
                                            .border(1.dp, if (isArpOn) Color.Yellow else Color.Transparent, RoundedCornerShape(8.dp))
                                            .combinedClickable(
                                                onClick = {
                                                    val t = currentState.tracks[currentState.selectedTrackIndex]
                                                    val isOn = t.arpConfig.mode != ArpMode.OFF
                                                    val isL = t.arpConfig.isLatched
                                                    val (newMode, newLatched) = if (!isOn) Pair(ArpMode.UP, false) else if (!isL) Pair(t.arpConfig.mode, true) else Pair(ArpMode.OFF, false)
                                                    val newArpConfig = t.arpConfig.copy(mode = newMode, isLatched = newLatched)
                                                    val newTracks = currentState.tracks.mapIndexed { idx, tr -> if (idx == currentState.selectedTrackIndex) tr.copy(arpConfig = newArpConfig) else tr }
                                                    latestOnStateChange(currentState.copy(tracks = newTracks))
                                                    nativeLib.setArpConfig(currentState.selectedTrackIndex, newMode.ordinal, newArpConfig.octaves, newArpConfig.inversion, newLatched, newArpConfig.isMutated, newArpConfig.rhythms.map { it.toBooleanArray() }.toTypedArray(), newArpConfig.randomSequence.toIntArray())
                                                },
                                                onLongClick = { showArpMenu = true }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("ARP", color = if (isLatched) Color.Black else if (isArpOn) Color.Yellow else Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    }
                                    Text("ARP", style = MaterialTheme.typography.labelSmall, fontSize = 8.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                } else {
                    // --- TABLET LAYOUT (Top Row Controls) ---
                    val padSize = minOf(
                        (currentMaxWidth - (spacing * (cols - 1))) / cols,
                        (currentMaxHeight - (spacing * (rows - 1)) - 65.dp) / rows,
                        135.dp
                    ).coerceAtLeast(40.dp)
                    val gridWidth = (padSize * cols) + (spacing * (cols - 1))

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top Row Controls
                        val isTablet = screenConfig.screenWidthDp >= 600
                        val rowModifier = if (isTablet) Modifier.width(gridWidth) else Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        val rowArrangement = if (isTablet) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp)

                        Row(modifier = rowModifier, horizontalArrangement = rowArrangement, verticalAlignment = Alignment.CenterVertically) {
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
                                        enabled = state.gridMode != GridMode.TONNETZ,
                                        modifier = Modifier.height(40.dp).width(80.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (state.gridMode == GridMode.TONNETZ) Color.DarkGray.copy(alpha=0.3f) else Color.DarkGray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(state.scaleType.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1, 
                                             color = if (state.gridMode == GridMode.TONNETZ) Color.Gray else Color.White)
                                    }
                                    Text("SCALE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
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
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Button(
                                        onClick = { 
                                            val newMode = when(state.gridMode) {
                                                GridMode.GRID_4X4 -> GridMode.GRID_6X6
                                                GridMode.GRID_6X6 -> GridMode.TONNETZ
                                                GridMode.TONNETZ -> GridMode.GRID_4X4
                                            }
                                            onStateChange(state.copy(gridMode = newMode)) 
                                        },
                                        modifier = Modifier.height(40.dp).width(60.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = if (state.gridMode != GridMode.GRID_4X4) Color(0xFF6200EE) else Color.DarkGray),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(when(state.gridMode) {
                                            GridMode.GRID_4X4 -> "4x4"
                                            GridMode.GRID_6X6 -> "6x6"
                                            GridMode.TONNETZ -> "TON"
                                        }, style = MaterialTheme.typography.labelSmall, color = Color.White)
                                    }
                                    Text("GRID", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }

                            val currentState by rememberUpdatedState(latestState)
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
                                                    val (newMode, newLatched) = if (!isOn) Pair(ArpMode.UP, false) else if (!isL) Pair(t.arpConfig.mode, true) else Pair(ArpMode.OFF, false)
                                                    val newArpConfig = t.arpConfig.copy(mode = newMode, isLatched = newLatched)
                                                    val newTracks = currentState.tracks.mapIndexed { idx, tr -> if (idx == currentState.selectedTrackIndex) tr.copy(arpConfig = newArpConfig) else tr }
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

                        }


                        // The Pad Grid
                        PadGrid(rows, cols, padSize, spacing, track, state, scaleNotes, engineColor, nativeLib, latestState, onStateChange, latestOnStateChange, empledManager)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            } // End of weighted Box
            } // End of Column (PlayheadStrip wrapper)
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
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    "SELECT SCALE",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
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
        val config = LocalConfiguration.current
        val isWide = config.screenWidthDp > 600

        androidx.compose.ui.window.Popup(
            onDismissRequest = { showTransposeMenu = false },
            alignment = Alignment.BottomStart
        ) {
            val popupHeight = if (isWide) 84.dp else 120.dp
            
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(popupHeight)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
                border = BorderStroke(1.dp, Color.Gray.copy(alpha = 0.3f))
            ) {
                val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                
                // Helper for button rendering
                val renderButton: @Composable (Int, String) -> Unit = { i, name ->
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
                            // Only show text, octave is implied
                            Text(name, style = MaterialTheme.typography.labelSmall, color = if (state.rootNote % 12 == i) Color.Black else Color.White)
                        }
                    }
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        noteNames.forEachIndexed { i, name -> renderButton(i, name) }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            noteNames.take(6).forEachIndexed { i, name -> renderButton(i, name) }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            noteNames.drop(6).forEachIndexed { i, name -> renderButton(i + 6, name) }
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
