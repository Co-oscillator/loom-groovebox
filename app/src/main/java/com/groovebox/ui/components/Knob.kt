package com.groovebox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.ui.LocalFocusedValue
import com.groovebox.ui.theme.getEngineColor

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
    onLocalValueChange: (String?) -> Unit = {}, // New callback for performance
    fullLabel: String? = null,
    detentValue: Float? = null
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val latestOnValueChangeOverride by rememberUpdatedState(onValueChangeOverride)
    val trackIndex = state.selectedTrackIndex
    val context = LocalContext.current
    
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
                            android.widget.Toast.makeText(context, "Bound $label to MIDI Strip ${stripIdx + 1}", android.widget.Toast.LENGTH_SHORT).show()
                            latestOnStateChange(newState)
                        }
                    } else if (state.lfoLearnActive || state.macroLearnActive) {
                        detectTapGestures {
                            if (parameterId != -1) {
                                if (state.lfoLearnActive) {
                                    val lfoIdx = state.lfoLearnLfoIndex
                                    if (lfoIdx != -1) {
                                        // Unassign previous target if different
                                        val oldTargetId = state.lfos[lfoIdx].targetId
                                        if (oldTargetId != -1 && oldTargetId != parameterId) {
                                            // Send 0.0 amount to remove routing
                                            nativeLib.setRouting(latestState.selectedTrackIndex, -1, 2 + lfoIdx, 5, 0.0f, oldTargetId)
                                        }

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
                                         val currentMacro = state.macros[macroIdx]
                                         val currentTargets = currentMacro.targets.toMutableList()
                                         
                                         if (tIdx < currentTargets.size) {
                                             val oldTargetId = currentTargets[tIdx].targetId
                                             if (oldTargetId != -1 && oldTargetId != parameterId) {
                                                 nativeLib.setRouting(latestState.selectedTrackIndex, -1, 10 + macroIdx, 5, 0.0f, oldTargetId)
                                             }

                                             // Macro1=10, Macro2=11...
                                             nativeLib.setRouting(latestState.selectedTrackIndex, -1, 10 + macroIdx, 5, 1.0f, parameterId)
                                             
                                             currentTargets[tIdx] = currentTargets[tIdx].copy(targetId = parameterId, targetLabel = label)
                                             val newMacros = state.macros.toMutableList()
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
                                            2 -> "CHOP"
                                            else -> "REV"
                                        }
                                    } else {
                                        val upperLabel = label.uppercase()
                                        val displayLabel = (fullLabel ?: label).uppercase()
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
                                        else -> String.format("%s: %.2f", displayLabel, v)
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
                                    
                                    // Snap to detent
                                    val upperLabel = label.uppercase()
                                    val effectiveDetent = detentValue ?: if (upperLabel == "PAN" || upperLabel == "P" || upperLabel == "BALANCE" || upperLabel == "BAL") 0.5f else null
                                    
                                    val nextValue = if (effectiveDetent != null) {
                                        if (rawNextValue in (effectiveDetent - 0.05f)..(effectiveDetent + 0.05f)) effectiveDetent else rawNextValue.coerceIn(0f, 1f)
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
                    val effectiveDetent = detentValue ?: if (upperLabel == "PAN" || upperLabel == "P" || upperLabel == "BALANCE" || upperLabel == "BAL") 0.5f else null
                    
                    if (effectiveDetent != null) {
                        val detentLen = 6.dp.toPx()
                        // Calculate detent angle
                        val detentAngle = 135f + (effectiveDetent * 270f)
                        val detentRad = Math.toRadians(detentAngle.toDouble())
                        val dStartX = center.x + Math.cos(detentRad).toFloat() * (radius + 2.dp.toPx())
                        val dStartY = center.y + Math.sin(detentRad).toFloat() * (radius + 2.dp.toPx())
                        // Actually original code drew it vertical for Pan. Let's make it radial for generic detents.
                        // But original was top-center vertical.
                        if (effectiveDetent == 0.5f) {
                             drawLine(
                                color = Color.White.copy(alpha = 0.4f),
                                start = Offset(center.x, center.y - radius - 2.dp.toPx()),
                                end = Offset(center.x, center.y - radius + detentLen),
                                strokeWidth = 2.dp.toPx()
                            )
                        } else {
                            // Radial detent
                            val outer = radius + 4.dp.toPx()
                            val inner = radius - 2.dp.toPx()
                            drawLine(
                                color = Color.White.copy(alpha = 0.4f),
                                start = Offset(center.x + Math.cos(detentRad).toFloat() * inner, center.y + Math.sin(detentRad).toFloat() * inner),
                                end = Offset(center.x + Math.cos(detentRad).toFloat() * outer, center.y + Math.sin(detentRad).toFloat() * outer),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
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
                             2 -> "CHOP"
                             else -> "REV"
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
                    fontSize = if (displayText.length > 4) 8.sp else 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = if (isHeld) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
        }
        val isTablet = LocalConfiguration.current.screenWidthDp >= 600
        Text(
            label.uppercase(), 
            style = MaterialTheme.typography.labelSmall, 
            color = if (isHeld) Color.White else if (isTablet) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.8f),
            fontSize = (if (isTablet) 8.sp else 10.sp).let { base -> if (label.length > 5) (base.value - 1).sp else base },
            maxLines = 1, 
            overflow = TextOverflow.Ellipsis
        )
    }
}
