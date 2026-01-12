package com.groovebox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.lang.Math.cos
import java.lang.Math.sin

@Composable
fun RoutingScreen(
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Section 1: Modulation Bank (5 LFOs)
        Text("MODULATION BANK (LFOs)", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(5) { index ->
                LfoModule(
                    index = index,
                    lfoState = state.lfos[index],
                    isLearning = state.lfoLearnActive && state.lfoLearnLfoIndex == index,
                    onUpdate = { newState ->
                        val newLfos = state.lfos.toMutableList()
                        newLfos[index] = newState
                        onStateChange(state.copy(lfos = newLfos))
                        
                        // Native updates
                        nativeLib.setGenericLfoParam(index, 0, newState.rate)
                        nativeLib.setGenericLfoParam(index, 1, newState.depth)
                        nativeLib.setGenericLfoParam(index, 2, newState.shape.toFloat())
                        // Sync handled by UI param mapping? Or just passed as 1.0 logic
                        nativeLib.setGenericLfoParam(index, 3, if (newState.sync) 1.0f else 0.0f)
                    },
                    onToggleLearn = {
                         if (state.lfoLearnActive && state.lfoLearnLfoIndex == index) {
                             // Tapping a second time: Cancel learn AND reset to None
                             val newLfos = state.lfos.toMutableList()
                             newLfos[index] = newLfos[index].copy(targetType = 0, targetId = -1, targetLabel = "None")
                             onStateChange(state.copy(lfos = newLfos, lfoLearnActive = false, lfoLearnLfoIndex = -1))
                             // Also update native routing for this source to None
                             // LFO1=2, LFO2=3... LFO5=6
                             nativeLib.setRouting(state.selectedTrackIndex, -1, 2 + index, 5, 0.0f, -1)
                         } else {
                             onStateChange(state.copy(lfoLearnActive = true, lfoLearnLfoIndex = index))
                         }
                    },
                    appState = state,
                    onStateChange = onStateChange,
                    nativeLib = nativeLib
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Section 2: Patch Bay (Macros)
        Text("PATCH BAY (MACROS)", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        // Grid Layout: 2 Columns of 3
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Row 1: 0, 1
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
               listOf(0, 1).forEach { idx ->
                   Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                       MacroUnit(
                           index = idx,
                           macroState = state.macros[idx],
                           grooveboxState = state,
                           onUpdate = { newState ->
                               val newMacros = state.macros.toMutableList()
                               newMacros[idx] = newState
                               onStateChange(state.copy(macros = newMacros))
                               nativeLib.setMacroSource(idx, newState.sourceType, newState.sourceIndex)
                           },
                           onStateChange = onStateChange
                       )
                   }
               }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Row 2: 2, 3
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
               listOf(2, 3).forEach { idx ->
                   Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                       MacroUnit(
                           index = idx,
                           macroState = state.macros[idx],
                           grooveboxState = state,
                           onUpdate = { newState ->
                               val newMacros = state.macros.toMutableList()
                               newMacros[idx] = newState
                               onStateChange(state.copy(macros = newMacros))
                               nativeLib.setMacroSource(idx, newState.sourceType, newState.sourceIndex)
                           },
                           onStateChange = onStateChange
                       )
                   }
               }
            }
             Spacer(modifier = Modifier.height(4.dp))
            // Row 3: 4, 5
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
               listOf(4, 5).forEach { idx ->
                   Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                       MacroUnit(
                           index = idx,
                           macroState = state.macros[idx],
                           grooveboxState = state,
                           onUpdate = { newState ->
                               val newMacros = state.macros.toMutableList()
                               newMacros[idx] = newState
                               onStateChange(state.copy(macros = newMacros))
                               nativeLib.setMacroSource(idx, newState.sourceType, newState.sourceIndex)
                           },
                           onStateChange = onStateChange
                       )
                   }
               }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Section 3: FX Chain
        Text("FX CHAIN (SERIAL)", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        FxChainEditor(state, onStateChange, nativeLib)
    }
}

@Composable
fun LfoModule(
    index: Int, 
    lfoState: LfoState, 
    isLearning: Boolean,
    onUpdate: (LfoState) -> Unit,
    onToggleLearn: () -> Unit,
    // Pass main state for Knobs
    appState: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isLearning) Color(0xFF444400) else Color(0xFF222222)),
        border = if (isLearning) BorderStroke(2.dp, Color.Yellow) else null,
        modifier = Modifier.width(160.dp).fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LFO ${index + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                
                // Target Display / Learn Button
                Button(
                    onClick = onToggleLearn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLearning) Color.Yellow else Color.DarkGray
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(
                        if (isLearning) "TAP TARGET..." else lfoState.targetLabel.take(12),
                        fontSize = 10.sp,
                        color = if (isLearning) Color.Black else Color.Cyan
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Knobs Row 1
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GlobalKnob("RATE", lfoState.rate, 2300 + index*10 + 0, appState, onStateChange, nativeLib, 
                    onValueChangeOverride = { onUpdate(lfoState.copy(rate = it)) })
                GlobalKnob("DPTH", lfoState.depth, 2301 + index*10 + 1, appState, onStateChange, nativeLib, 
                    onValueChangeOverride = { onUpdate(lfoState.copy(depth = it)) })
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Knobs Row 2
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // Shape Knob (Stepped)
                GlobalKnob("SHAPE", lfoState.shape / 4.0f, 2302 + index*10 + 2, appState, onStateChange, nativeLib, 
                    onValueChangeOverride = { 
                        val shapeIdx = (it * 4).toInt().coerceIn(0, 4)
                        onUpdate(lfoState.copy(shape = shapeIdx)) 
                    },
                    valueFormatter = { 
                        listOf("SIN", "TRI", "SQR", "SAW", "RND")[(it * 4.4).toInt().coerceIn(0, 4)]
                    }
                )
                
                // Intensity (Amount)
                GlobalKnob("AMT", lfoState.intensity, 2303 + index*10 + 3, appState, onStateChange, nativeLib, 
                    onValueChangeOverride = { onUpdate(lfoState.copy(intensity = it)) })
            }
        }
    }
}

@Composable
fun MacroUnit(
    index: Int,
    macroState: MacroState,
    grooveboxState: GrooveboxState,
    onUpdate: (MacroState) -> Unit,
    onStateChange: (GrooveboxState) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
        modifier = Modifier.fillMaxWidth().fillMaxHeight()
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Controller Circle (Left)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                var showSourceMenu by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFBB86FC))
                        .clickable { showSourceMenu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        macroState.sourceLabel.ifEmpty { "SRC" }, 
                        fontSize = 9.sp, 
                        textAlign = TextAlign.Center, 
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(2.dp)
                    )
                     DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }) {
                        val options = listOf("None") + 
                                      (1..4).map { "Strip $it" } + 
                                      (1..4).map { "Knob $it" } + 
                                      (1..5).map { "LFO $it" }
                        
                        options.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    showSourceMenu = false
                                    val (type, srcIdx) = when {
                                        i == 0 -> 0 to -1
                                        i <= 4 -> 1 to (i - 1) // Strip
                                        i <= 8 -> 2 to (i - 5) // Knob
                                        else -> 3 to (i - 9) // LFO
                                    }
                                    onUpdate(macroState.copy(sourceLabel = label, sourceType = type, sourceIndex = srcIdx))
                                }
                            )
                        }
                    }
                }
                Text("CTRL", fontSize = 8.sp, color = Color.Gray)
            }

            // 2. Middle: Arrows pointing Right
             Column(
                verticalArrangement = Arrangement.SpaceAround,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp)
            ) {
                 macroState.targets.forEachIndexed { tIdx, target ->
                     Box(
                         modifier = Modifier
                             .fillMaxWidth()
                             .height(24.dp)
                             .clickable { 
                                  // Toggle Inversion logic
                                  val newTargets = macroState.targets.toMutableList()
                                  newTargets[tIdx] = newTargets[tIdx].copy(isInverted = !target.isInverted)
                                  onUpdate(macroState.copy(targets = newTargets))
                             },
                         contentAlignment = Alignment.Center
                     ) {
                         // Line
                         Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if(target.enabled) Color.Cyan else Color.Gray))
                         // Arrow Head
                         Icon(
                             Icons.Default.ArrowForward, 
                             contentDescription = "Flow", 
                             tint = if (target.isInverted) Color.Red else (if(target.enabled) Color.Cyan else Color.Gray),
                             modifier = Modifier.align(Alignment.CenterEnd).size(16.dp).rotate(if (target.isInverted) 180f else 0f)
                         )
                     }
                 }
            }

            // 3. Right: Vertical Target Stack
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.width(100.dp) // Fixed width for lozenges
            ) {
                macroState.targets.forEachIndexed { tIdx, target ->
                    val isLeaningThis = grooveboxState.macroLearnActive && grooveboxState.macroLearnMacroIndex == index && grooveboxState.macroLearnTargetIndex == tIdx
                    Button(
                        onClick = {
                             // Toggle Learn Mode
                             if (grooveboxState.macroLearnActive && isLeaningThis) {
                                  onStateChange(grooveboxState.copy(macroLearnActive = false, macroLearnMacroIndex = -1, macroLearnTargetIndex = -1))
                             } else {
                                  onStateChange(grooveboxState.copy(macroLearnActive = true, macroLearnMacroIndex = index, macroLearnTargetIndex = tIdx))
                             }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLeaningThis) Color.Yellow else Color.DarkGray
                        ),
                        shape = RoundedCornerShape(50), // Lozenge
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.fillMaxWidth().height(28.dp)
                    ) {
                         Text(
                            if (isLeaningThis) "TAP..." else target.targetLabel.take(10),
                            fontSize = 10.sp,
                            color = if (isLeaningThis) Color.Black else Color.White,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FxChainEditor(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val fxNames = listOf(
        0 to "Overdrive", 1 to "Bitcrush", 2 to "Chorus", 3 to "Phaser", 4 to "Wobble",
        5 to "Delay", 6 to "Reverb", 7 to "Slicer", 8 to "Compressor",
        9 to "HP LFO", 10 to "LP LFO", 11 to "Flanger", 12 to "Spread", 13 to "TapeEcho", 14 to "Octaver"
    )

    // Serial Chain: Slot 0 -> Slot 1 -> Slot 2 -> Slot 3 -> Slot 4
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF111111), RoundedCornerShape(8.dp)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        state.fxChainSlots.forEachIndexed { slotIdx, fxId ->
            // Arrow between slots
            if (slotIdx > 0) {
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
            
            // Slot
            val isFilled = fxId != -1
            val fxName = fxNames.find { it.first == fxId }?.second ?: "Unknown"
            // Color based on FX ID (simple hash or lookup)
            val fxColor = when(fxId) {
                -1 -> Color.DarkGray
                0 -> Color.Red // Overdrive
                1 -> Color.Yellow // Bitcrush
                2 -> Color(0xFF03DAC6) // Chorus
                3 -> Color.Magenta // Phaser
                4 -> Color(0xFFFFA500) // Wobble
                5 -> Color.Blue // Delay
                6 -> Color.Cyan // Reverb
                7 -> Color.Green // Slicer
                8 -> Color(0xFFFF69B4) // Compressor
                9 -> Color(0xFF00BFFF) // HP LFO (Deep Sky Blue)
                10 -> Color(0xFFFF4500) // LP LFO (Orange Red)
                11 -> Color(0xFF9C27B0) // Flanger (Purple)
                12 -> Color(0xFF009688) // Spread (Teal)
                13 -> Color(0xFFB8860B) // TapeEcho (Dark Goldenrod)
                14 -> Color(0xFF3F51B5) // Octaver (Indigo)
                else -> Color.White
            }
            
            var showMenu by remember { mutableStateOf(false) }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(fxColor.copy(alpha = if(isFilled) 0.8f else 0.2f))
                    .border(if (isFilled) 2.dp else 1.dp, fxColor, RoundedCornerShape(8.dp))
                    .clickable { showMenu = true },
                contentAlignment = Alignment.Center
            ) {
                if (isFilled) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(fxName, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("${slotIdx+1}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    }
                } else {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.Gray)
                }
                
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (isFilled) {
                        DropdownMenuItem(
                            text = { Text("Remove", color = Color.Red) },
                            onClick = {
                                val newSlots = state.fxChainSlots.toMutableList()
                                newSlots[slotIdx] = -1
                                onStateChange(state.copy(fxChainSlots = newSlots))
                                // Rebuild entire chain in Native
                                updateNativeFxChain(nativeLib, newSlots)
                                showMenu = false
                            }
                        )
                        Divider()
                    }
                    
                    fxNames.forEach { (id, name) ->
                        // Don't show if already used elsewhere in chain?
                        // Actually parallel instances might be allowed, but usually 1 instance per engine.
                        // Filter out used IDs:
                        if (!state.fxChainSlots.contains(id) || id == fxId) {
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    val newSlots = state.fxChainSlots.toMutableList()
                                    newSlots[slotIdx] = id
                                    onStateChange(state.copy(fxChainSlots = newSlots))
                                    updateNativeFxChain(nativeLib, newSlots)
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun updateNativeFxChain(nativeLib: NativeLib, slots: List<Int>) {
    // Clear all routing first?
    // The native engine uses mFxChainDest[src] = dest.
    // If we have A -> B -> C:
    // setFxChain(A, B)
    // setFxChain(B, C)
    // setFxChain(C, -1)
    
    // First, clear all existing mappings (reset all 15 FX to -1)
    for (i in 0 until 15) {
        nativeLib.setFxChain(i, -1)
    }
    
    // Now build chain from non-empty slots
    val activeSlots = slots.filter { it != -1 }
    for (i in 0 until activeSlots.size - 1) {
        val src = activeSlots[i]
        val dest = activeSlots[i+1]
        nativeLib.setFxChain(src, dest)
    }
}
