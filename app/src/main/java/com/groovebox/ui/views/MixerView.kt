package com.groovebox.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.EngineType
import com.groovebox.ui.components.Knob
import com.groovebox.ui.components.EngineIcon
import com.groovebox.ui.theme.getEngineColor

@Composable
fun MixerView(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    val engineTypes = remember { EngineType.values() }

    Column(
        modifier = Modifier
            .width(200.dp) // Expanded from 180.dp
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
                        else Color.White.copy(alpha = 0.1f),
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

                    // Engine Icon (Center) - Expanded Size
                    EngineIcon(
                        type = track.engineType,
                        modifier = Modifier.size(42.dp), // Increased from 32dp
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
                                focusedValue = "CH ${i+1} V51: ${(newVal * 100).toInt()}%"
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
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = track.engineType.name.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.4f),
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
