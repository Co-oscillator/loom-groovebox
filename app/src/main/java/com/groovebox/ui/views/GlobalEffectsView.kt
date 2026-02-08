package com.groovebox.ui.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.ui.components.Knob

@Composable
fun GlobalEffectsView(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    if (state.isSelectingSidechain) {
        SidechainSelectorDialog(
            state = state,
            onDismiss = { onStateChange(state.copy(isSelectingSidechain = false)) },
            onSelect = { trackIdx, drumIdx ->
                nativeLib.setSidechainConfig(trackIdx, drumIdx)
                onStateChange(state.copy(
                    isSelectingSidechain = false,
                    sidechainSourceTrack = trackIdx,
                    sidechainSourceDrumIdx = drumIdx
                ))
            }
        )
    }
    Row(modifier = Modifier.fillMaxSize()) {
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
                                if (state.sidechainSourceDrumIdx != -1) {
                                    val instruments = listOf("KICK", "SNARE", "TOM", "HIHAT", "OHH", "CYMB", "PERC", "NOISE")
                                    val name = instruments.getOrNull(state.sidechainSourceDrumIdx) ?: "??"
                                    "SC: T${state.sidechainSourceTrack + 1} $name"
                                } else {
                                    "SC: TRACK ${state.sidechainSourceTrack + 1}"
                                }
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
                        GlobalKnob("RATE", 0.2f, 550, state, onStateChange, nativeLib, fullLabel = "Phaser Rate")
                        GlobalKnob("DPTH", 0.5f, 551, state, onStateChange, nativeLib, fullLabel = "Phaser Depth")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("MIX", 1.0f, 552, state, onStateChange, nativeLib, fullLabel = "Phaser Mix")
                        GlobalKnob("INTEN", 0.5f, 553, state, onStateChange, nativeLib, fullLabel = "Phaser Intensity")
                    }
                }
            }
            item {
                Pedal("WOBBLE", Color(0xFFFFA500), state, 4, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("RATE", 0.1f, 560, state, onStateChange, nativeLib, fullLabel = "Wobble Rate")
                        GlobalKnob("DPTH", 0.5f, 561, state, onStateChange, nativeLib, fullLabel = "Wobble Depth")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("SAT", 0.0f, 562, state, onStateChange, nativeLib, fullLabel = "Wobble Saturation")
                        GlobalKnob("MIX", 1.0f, 563, state, onStateChange, nativeLib, fullLabel = "Wobble Mix")
                    }
                }
            }
            item {
                Pedal("OVERDRIVE", Color.Red, state, 0, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("DRIVE", 0.3f, 540, state, onStateChange, nativeLib, fullLabel = "Overdrive")
                        GlobalKnob("DIST", 1.0f, 541, state, onStateChange, nativeLib, fullLabel = "Distortion")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        GlobalKnob("LEVEL", 0.5f, 542, state, onStateChange, nativeLib, fullLabel = "Overdrive Level")
                        GlobalKnob("TONE", 0.5f, 543, state, onStateChange, nativeLib, fullLabel = "Overdrive Tone")
                    }
                }
            }
            item {
                Pedal("REVERB", Color.Cyan, state, 6, onStateChange, nativeLib) {
                   Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("SIZE", 0.5f, 500, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Reverb Size")
                            GlobalKnob("MIX", 0.3f, 503, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Reverb Mix")
                            GlobalKnob("TYPE", 0.0f, 505, state, onStateChange, nativeLib, knobSize = 36.dp, valueFormatter = { v ->
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
                            GlobalKnob("DAMP", 0.5f, 501, state, onStateChange, nativeLib, knobSize = 36.dp)
                            GlobalKnob("MOD", 0.0f, 502, state, onStateChange, nativeLib, knobSize = 36.dp)
                            GlobalKnob("TONE", 0.5f, 506, state, onStateChange, nativeLib, knobSize = 36.dp)
                            GlobalKnob("P.DLY", 0.0f, 504, state, onStateChange, nativeLib, knobSize = 36.dp)
                        }
                   }
                }
            }
            item {
                Pedal("FLANGER", Color(0xFF9C27B0), state, 11, onStateChange, nativeLib) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("RATE", 0.5f, 1500, state, onStateChange, nativeLib, fullLabel = "Flanger Rate")
                            GlobalKnob("DPTH", 0.7f, 1501, state, onStateChange, nativeLib)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("MIX", 0.5f, 1502, state, onStateChange, nativeLib, fullLabel = "Flanger Mix")
                            GlobalKnob("FEED", 0.6f, 1503, state, onStateChange, nativeLib) 
                        }
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                            GlobalKnob("DLAY", 0.2f, 1504, state, onStateChange, nativeLib, fullLabel = "Flanger Delay")
                        }
                    }
                }
            }
            item {
                Pedal("DELAY", Color.Blue, state, 5, onStateChange, nativeLib) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("TIME", 0.25f, 520, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Delay Time")
                            GlobalKnob("FEED", 0.5f, 521, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Delay Feedback")
                            GlobalKnob("MIX", 0.5f, 522, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Delay Mix")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("FILT", 0.5f, 523, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Delay Filter") 
                            GlobalKnob("RES", 0.0f, 524, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Delay Resonance")
                            GlobalKnob("TYPE", 0.0f, 525, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Delay Type", valueFormatter = { v ->
                                when((v * 3.9f).toInt()) {
                                    0 -> "DIGITAL"
                                    1 -> "TAPE"
                                    2 -> "P-PONG"
                                    else -> "REVERSE"
                                }
                            })
                            GlobalKnob("MODE", 0.0f, 526, state, onStateChange, nativeLib, knobSize = 36.dp, fullLabel = "Delay Mode", valueFormatter = { v ->
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
                            GlobalKnob("TIME", 0.3f, 1510, state, onStateChange, nativeLib, fullLabel = "Echo Time")
                            GlobalKnob("FEED", 0.5f, 1511, state, onStateChange, nativeLib, fullLabel = "Echo Feedback")
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("MIX", 0.5f, 1512, state, onStateChange, nativeLib, fullLabel = "Echo Mix")
                            GlobalKnob("DRV", 0.4f, 1513, state, onStateChange, nativeLib, fullLabel = "Echo Drive")
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("WOW", 0.1f, 1514, state, onStateChange, nativeLib, fullLabel = "Echo Wow")
                            GlobalKnob("FLUT", 0.1f, 1515, state, onStateChange, nativeLib, fullLabel = "Echo Flutter")
                        }
                    }
                }
            }
            item {
                Pedal("HP LFO", Color(0xFFFF4081), state, 9, onStateChange, nativeLib) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         GlobalKnob("RATE", 0.5f, 590, state, onStateChange, nativeLib, fullLabel = "HP LFO Rate")
                         GlobalKnob("DPTH", 0.0f, 591, state, onStateChange, nativeLib, fullLabel = "HP LFO Depth")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                         GlobalKnob("SHAPE", 0.0f, 592, state, onStateChange, nativeLib, fullLabel = "HP LFO Shape")
                         GlobalKnob("CUT", 0.5f, 593, state, onStateChange, nativeLib, fullLabel = "HP LFO Cutoff")
                         GlobalKnob("RES", 0.0f, 594, state, onStateChange, nativeLib, fullLabel = "HP LFO Resonance")
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                         GlobalKnob("MIX", 0.0f, 595, state, onStateChange, nativeLib, fullLabel = "HP LFO Mix") // Added Mix
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
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
                         GlobalKnob("MIX", 0.0f, 495, state, onStateChange, nativeLib, fullLabel = "LP LFO Mix") // Added Mix
                    }
                }
            }
            item {
                Pedal("FILT 1", Color(0xFFE91E63), state, 12, onStateChange, nativeLib) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("CUT", 0.5f, 2200, state, onStateChange, nativeLib, fullLabel = "Filter 1 Cutoff")
                            GlobalKnob("RES", 0.0f, 2201, state, onStateChange, nativeLib, fullLabel = "Filter 1 Res")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Mode Cycle Button
                        val paramId = 2202
                        val currentMode = (state.globalParameters[paramId] ?: 0f).toInt().coerceIn(0, 2)
                        val modeLabels = listOf("LP", "HP", "BP")
                        Button(
                            onClick = {
                                val nextMode = (currentMode + 1) % 3
                                nativeLib.setParameter(-1, paramId, nextMode.toFloat())
                                onStateChange(state.copy(globalParameters = state.globalParameters + (paramId to nextMode.toFloat())))
                            },
                            modifier = Modifier.height(32.dp).width(60.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(modeLabels[currentMode], style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
                        }
                    }
                }
            }
            item {
                Pedal("FILT 2", Color(0xFFE91E63), state, 15, onStateChange, nativeLib) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("CUT", 0.5f, 2205, state, onStateChange, nativeLib, fullLabel = "Filter 2 Cutoff")
                            GlobalKnob("RES", 0.0f, 2206, state, onStateChange, nativeLib, fullLabel = "Filter 2 Res")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Mode Cycle Button
                        val paramId = 2207
                        val currentMode = (state.globalParameters[paramId] ?: 0f).toInt().coerceIn(0, 2)
                        val modeLabels = listOf("LP", "HP", "BP")
                        Button(
                            onClick = {
                                val nextMode = (currentMode + 1) % 3
                                nativeLib.setParameter(-1, paramId, nextMode.toFloat())
                                onStateChange(state.copy(globalParameters = state.globalParameters + (paramId to nextMode.toFloat())))
                            },
                            modifier = Modifier.height(32.dp).width(60.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(modeLabels[currentMode], style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
                        }
                    }
                }
            }
            item {
                Pedal("SLICER", Color.Green, state, 7, onStateChange, nativeLib) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            SlicerKnob("1/4", 570, 573, state, onStateChange, nativeLib, fullLabel = "Slicer 1/4")
                            SlicerKnob("1/3", 571, 574, state, onStateChange, nativeLib, fullLabel = "Slicer 1/3")
                            SlicerKnob("1/5", 572, 575, state, onStateChange, nativeLib, fullLabel = "Slicer 1/5")
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
                             GlobalKnob("MIX", 0.5f, 1520, state, onStateChange, nativeLib, fullLabel = "Octaver Mix")
                         }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("MODE", 0.5f, 1521, state, onStateChange, nativeLib, fullLabel = "Octave Mode", valueFormatter = { v ->
                                val mode = (v * 11.99f).toInt()
                                when(mode) {
                                    0 -> "OCT UP"
                                    1 -> "2 OCT UP"
                                    2 -> "OCT DWN"
                                    3 -> "2 OCT DWN"
                                    4 -> "UP/DWN"
                                    5 -> "MAJ"
                                    6 -> "Cymbal"
                                    7 -> "Cowbell"
                                    8 -> "DOM7"
                                    9 -> "MAJ7"
                                    10 -> "MIN7"
                                    11 -> "DIM"
                                    else -> "PWR"
                                }
                            })
                            GlobalKnob("UNISON", 0.0f, 1522, state, onStateChange, nativeLib) 
                        }
                    }
                }
            }
            item {
                Pedal("FILT 3", Color(0xFFE91E63), state, 16, onStateChange, nativeLib) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            GlobalKnob("CUT", 0.5f, 2210, state, onStateChange, nativeLib, fullLabel = "Filter 3 Cutoff")
                            GlobalKnob("RES", 0.0f, 2211, state, onStateChange, nativeLib, fullLabel = "Filter 3 Res")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Mode Cycle Button
                        val paramId = 2212
                        val currentMode = (state.globalParameters[paramId] ?: 0f).toInt().coerceIn(0, 2)
                        val modeLabels = listOf("LP", "HP", "BP")
                        Button(
                            onClick = {
                                val nextMode = (currentMode + 1) % 3
                                nativeLib.setParameter(-1, paramId, nextMode.toFloat())
                                onStateChange(state.copy(globalParameters = state.globalParameters + (paramId to nextMode.toFloat())))
                            },
                            modifier = Modifier.height(32.dp).width(60.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(modeLabels[currentMode], style = MaterialTheme.typography.labelSmall, color = Color.Cyan)
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
    nativeLib: NativeLib,
    fullLabel: String? = null
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val isActive = (latestState.globalParameters[activeParamId] ?: 0.0f) > 0.5f
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlobalKnob(label, 0.25f, rateParamId, latestState, latestOnStateChange, nativeLib, fullLabel = fullLabel)
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(if (isActive) Color.Green else Color.DarkGray, CircleShape)
                .border(1.dp, Color.White, CircleShape)
                .clickable {
                    val newValue = if (isActive) 0.0f else 1.0f
                    nativeLib.setParameter(-1, activeParamId, newValue) // Use -1 for Global
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
                // Per-track MIX knob (Wet/Dry Balance)
                Knob(
                    label = "MIX",
                    initialValue = 0.0f,
                    parameterId = -1,
                    state = state,
                    onStateChange = onStateChange,
                    nativeLib = nativeLib,
                    knobSize = 32.dp,
                    overrideValue = track.fxMix.getOrNull(fxIdx) ?: 0.0f,
                    onValueChangeOverride = { newValue ->
                        nativeLib.setParameter(latestState.selectedTrackIndex, 2000 + (fxIdx * 10) + 1, newValue)
                        val newTracks = latestState.tracks.mapIndexed { i, t ->
                            if (i == latestState.selectedTrackIndex) {
                                val newMix = t.fxMix.toMutableList()
                                newMix[fxIdx] = newValue
                                t.copy(fxMix = newMix)
                            } else t
                        }
                        latestOnStateChange(latestState.copy(tracks = newTracks))
                    }
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
    onValueChangeOverride: ((Float) -> Unit)? = null,
    knobSize: Dp = 40.dp,
    fullLabel: String? = null
) {
    val latestState by rememberUpdatedState(state)
    val latestOnStateChange by rememberUpdatedState(onStateChange)
    
    val globalValue = latestState.globalParameters[parameterId] ?: initialValue
    Knob(
        label = label,
        fullLabel = fullLabel,
        initialValue = initialValue,
        parameterId = parameterId,
        state = latestState,
        onStateChange = latestOnStateChange,
        nativeLib = nativeLib,
        knobSize = knobSize,
        overrideValue = globalValue,
        overrideColor = Color.Magenta,
        onValueChangeOverride = onValueChangeOverride ?: { newVal ->
            // Update Audio Engine (Track -1 convention for Global)
            nativeLib.setParameter(-1, parameterId, newVal)
            
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
        valueFormatter = valueFormatter
    )
}

@Composable
fun SidechainSelectorDialog(
    state: GrooveboxState,
    onDismiss: () -> Unit,
    onSelect: (Int, Int) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF222222),
            border = BorderStroke(1.dp, Color.Gray)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sidechain Source",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val scrollState = rememberScrollState()

                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        state.tracks.forEachIndexed { index, track ->
                            val isSelected = state.sidechainSourceTrack == index && state.sidechainSourceDrumIdx == -1
                            val isDrum = track.engineType == EngineType.FM_DRUM || track.engineType == EngineType.ANALOG_DRUM
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .border(1.dp, if (isSelected || (state.sidechainSourceTrack == index && isDrum)) Color.Cyan else Color.Gray, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "Track ${index + 1}: ${track.engineType}",
                                    color = if (isSelected) Color.Cyan else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(index, -1) }
                                        .padding(4.dp)
                                )
                                
                                if (isDrum) { // Expanded options for Drums
                                    Spacer(modifier = Modifier.height(8.dp))
                                    val drumNames = if (track.engineType == EngineType.FM_DRUM) {
                                        listOf("KICK", "SNARE", "TOM", "HIHAT", "OHH", "CYMB", "PERC", "NOISE")
                                    } else {
                                        // ANALOG_DRUM UI only exposes 5 voices
                                        listOf("KICK", "SNARE", "CYMBAL", "HAT C", "HAT O")
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val rows = drumNames.chunked(4)
                                        rows.forEach { rowNames ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                rowNames.forEach { name ->
                                                    val dIdx = drumNames.indexOf(name)
                                                    val isDrumSelected = state.sidechainSourceTrack == index && state.sidechainSourceDrumIdx == dIdx
                                                    Box(
                                                        modifier = Modifier
                                                            .background(if (isDrumSelected) Color.Cyan else Color.DarkGray, RoundedCornerShape(4.dp))
                                                            .clickable { onSelect(index, dIdx) }
                                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    ) {
                                                        Text(name, color = if (isDrumSelected) Color.Black else Color.White, fontSize = 10.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } 
                    
                    // Scrollbar
                    if (scrollState.maxValue > 0) {
                        Canvas(modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(6.dp)
                            .fillMaxHeight()
                        ) {
                            val viewportH = size.height
                            val contentH = viewportH + scrollState.maxValue
                            
                            val thumbHeight = maxOf(20.dp.toPx(), (viewportH / contentH) * viewportH)
                            
                            val scrollRatio = if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue.toFloat() else 0f
                            val thumbY = scrollRatio * (viewportH - thumbHeight)
                            
                            drawRoundRect(
                                color = Color.Gray.copy(alpha = 0.3f),
                                size = size,
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                            
                            drawRoundRect(
                                color = Color.Cyan.copy(alpha = 0.5f),
                                topLeft = Offset(0f, thumbY),
                                size = Size(size.width, thumbHeight),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close")
                }
            }
        }
    }
}
