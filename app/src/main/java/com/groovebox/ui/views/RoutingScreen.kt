package com.groovebox.ui.views

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.groovebox.ui.components.Knob
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
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.LfoState
import com.groovebox.MacroState

@Composable
fun RoutingScreen(
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp
    val screenHeight = configuration.screenHeightDp
    val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
    
    val isTablet = minOf(screenWidth, screenHeight) >= 600
    val isPhoneLandscape = screenRatio > 1.4f && screenHeight < 600

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Section 1: Modulation Bank (6 LFOs)
        Text("MODULATION BANK (6 LFOs)", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().height(if (isPhoneLandscape) 140.dp else 180.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            (0 until 6).forEach { index ->
                Box(modifier = Modifier.weight(1f)) {
                    // Check if any Macro uses this LFO
                    val macroUser = state.macros.indexOfFirst { it.sourceType == 3 && it.sourceIndex == index }
                    val displayLabel = if (state.lfos[index].targetId != -1) state.lfos[index].targetLabel 
                                       else if (macroUser != -1) "Macro ${macroUser + 1}" 
                                       else "None"

                    LfoModule(
                        index = index,
                        lfoState = state.lfos[index],
                        isLearning = state.lfoLearnActive && state.lfoLearnLfoIndex == index,
                        displayLabelOverride = displayLabel,
                        onUpdate = { newState ->
                            val newLfos = state.lfos.toMutableList()
                            newLfos[index] = newState
                            onStateChange(state.copy(lfos = newLfos))
                            
                            // Native updates
                            nativeLib.setGenericLfoParam(index, 0, newState.rate)
                            nativeLib.setGenericLfoParam(index, 1, newState.depth)
                            nativeLib.setGenericLfoParam(index, 2, newState.shape.toFloat())
                            nativeLib.setGenericLfoParam(index, 3, if (newState.sync) 1.0f else 0.0f)
                        },
                        onToggleLearn = {
                             if (state.lfoLearnActive && state.lfoLearnLfoIndex == index) {
                                 onStateChange(state.copy(lfoLearnActive = false, lfoLearnLfoIndex = -1))
                             } else if (state.lfos[index].targetId != -1) {
                                 // Clear target (un-assign)
                                 val oldId = state.lfos[index].targetId
                                 if (oldId != -1) {
                                     nativeLib.setRouting(state.selectedTrackIndex, -1, 2 + index, 5, 0.0f, oldId)
                                 }
                                 
                                 val newLfos = state.lfos.toMutableList()
                                 newLfos[index] = state.lfos[index].copy(targetId = -1, targetLabel = "None", targetType = 0)
                                 onStateChange(state.copy(lfos = newLfos))
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
        }
    
        Spacer(modifier = Modifier.height(16.dp))
        
        // Section 2: Patch Bay (Macros)
        Text("PATCH BAY (MACROS)", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        
        // Adaptive Grid: 3 columns on tablet, 2 on phone landscape, 1 on phone portrait
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (isTablet) 2 else if (isPhoneLandscape) 2 else 1),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(state.macros) { idx, macro ->
                MacroUnit(
                    index = idx,
                    macroState = macro,
                    grooveboxState = state,
                    onUpdate = { newState ->
                        val newMacros = state.macros.toMutableList()
                        newMacros[idx] = newState
                        onStateChange(state.copy(macros = newMacros))
                        nativeLib.setMacroSource(idx, newState.sourceType, newState.sourceIndex, state.selectedTrackIndex)
                    },
                    onStateChange = onStateChange,
                    isTablet = isTablet,
                    nativeLib = nativeLib
                )
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
    displayLabelOverride: String? = null,
    onUpdate: (LfoState) -> Unit,
    onToggleLearn: () -> Unit,
    appState: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isLearning) Color(0xFF444400) else Color(0xFF222222)),
        border = if (isLearning) BorderStroke(2.dp, Color.Yellow) else null,
        modifier = Modifier.fillMaxWidth().fillMaxHeight()
    ) {
        Column(modifier = Modifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LFO ${index + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                
                Button(
                    onClick = onToggleLearn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLearning) Color.Yellow else Color.DarkGray
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(18.dp)
                ) {
                    Text(
                        if (isLearning) "TAP..." else (displayLabelOverride ?: lfoState.targetLabel).take(8),
                        fontSize = 8.sp,
                        color = if (isLearning) Color.Black else Color.Cyan
                    )
                }
            }
            
            // Row 1
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Box(modifier = Modifier.weight(1f)) {
                    Knob("RATE", lfoState.rate, 2300 + index*10 + 0, appState, onStateChange, nativeLib, 
                        onValueChangeOverride = { v -> onUpdate(lfoState.copy(rate = v)) }, 
                        valueFormatter = { v ->
                            if (lfoState.sync) {
                                listOf("8/1", "4/1", "2/1", "1/1", "1/2", "1/4", "1/8", "1/16", "1/32", "1/48", "1/64", "1/72", "1/96")[(v * 12.99f).toInt().coerceIn(0, 12)]
                            } else {
                                val hz = 0.1f * Math.pow(10.0, (v * 2.3f).toDouble()).toFloat()
                                String.format(java.util.Locale.US, "%.1f Hz", hz)
                            }
                        },
                        knobSize = 32.dp)
                }
                Box(modifier = Modifier.weight(1f)) {
                    Knob("DPTH", lfoState.depth, 2301 + index*10 + 1, appState, onStateChange, nativeLib, 
                        onValueChangeOverride = { v -> onUpdate(lfoState.copy(depth = v)) }, knobSize = 32.dp)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    Knob("SHAPE", lfoState.shape / 4.0f, 2302 + index*10 + 2, appState, onStateChange, nativeLib, 
                        onValueChangeOverride = { v ->
                            val shapeIdx = (v * 4).toInt().coerceIn(0, 4)
                            onUpdate(lfoState.copy(shape = shapeIdx)) 
                        },
                        valueFormatter = { v ->
                            listOf("SIN", "TRI", "SQR", "SAW", "RND")[(v * 4.4).toInt().coerceIn(0, 4)]
                        }, knobSize = 32.dp
                    )
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .clickable {
                                onUpdate(lfoState.copy(sync = !lfoState.sync))
                            }
                            .padding(4.dp)
                    ) {
                        val isChecked = lfoState.sync
                        val checkedColor = Color.Cyan
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(
                                    if (isChecked) checkedColor.copy(alpha = 0.8f) else Color.DarkGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isChecked) checkedColor else Color.Gray,
                                    RoundedCornerShape(4.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isChecked) {
                                Box(modifier = Modifier.size(12.dp).background(Color.Black, RoundedCornerShape(2.dp)))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "SYNC",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isChecked) checkedColor else Color.Gray,
                            fontSize = 8.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MacroUnit(
    index: Int,
    macroState: MacroState,
    grooveboxState: GrooveboxState,
    onUpdate: (MacroState) -> Unit,
    onStateChange: (GrooveboxState) -> Unit,
    isTablet: Boolean,
    nativeLib: NativeLib
) {
    val containerColor = Color(0xFF2A2A2A)
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth().height(if (isTablet) 140.dp else 72.dp)
    ) {
        val spacing = if (isTablet) 16.dp else 8.dp
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing)
        ) {
            // 1. Controller Block (Source) - Purple Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                var showSourceMenu by remember { mutableStateOf(false) }
                Button(
                    onClick = { showSourceMenu = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBB86FC)),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.size(width = 60.dp, height = if (isTablet) 36.dp else 40.dp)
                ) {
                    Text(
                        macroState.sourceLabel.ifEmpty { "SRC" }, 
                        fontSize = if (isTablet) 10.sp else 11.sp, 
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }) {
                        val options = listOf("None") + 
                                      (1..4).map { "STRIP $it" } + 
                                      (1..4).map { "KNOB $it" } + 
                                      (1..6).map { "LFO $it" } +
                                      listOf("MIDI PADS", "LEARN ENV...")
                        
                        options.forEachIndexed { i, label ->
                            DropdownMenuItem(
                                text = { Text(label, color = if (label == "LEARN ENV...") Color.Cyan else Color.Unspecified) },
                                onClick = {
                                    showSourceMenu = false
                                    if (label == "LEARN ENV...") {
                                        onStateChange(grooveboxState.copy(macroSourceLearnActive = true, macroSourceLearnIndex = index))
                                    } else {
                                        val (type, srcIdx) = when {
                                            i == 0 -> 0 to -1
                                            i <= 4 -> 1 to (i - 1) // Strip
                                            i <= 8 -> 2 to (i - 5) // Knob
                                            i <= 14 -> 3 to (i - 9) // LFO
                                            i == 15 -> 5 to -1 // MIDI PADS
                                            else -> 0 to -1
                                        }
                                        if (type == 5) { // MIDI PADS
                                            // Auto-bind the Pad Y-Axis to this exact Macro so it stops driving default parameters like Cutoff
                                            val newTracks = grooveboxState.tracks.mapIndexed { tIdx, t ->
                                                if (tIdx == grooveboxState.selectedTrackIndex) t.copy(padModTargetId = 2000 + index)
                                                else t
                                            }
                                            onStateChange(grooveboxState.copy(tracks = newTracks))
                                        }
                                        onUpdate(macroState.copy(sourceLabel = label, sourceType = type, sourceIndex = srcIdx))
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (isTablet) {
                // TABLET SPECIFIC: Column of 3 arrows, Column of 3 targets
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    macroState.targets.forEachIndexed { tIdx, target ->
                        IconButton(
                            onClick = {
                                val newTargets = macroState.targets.toMutableList()
                                newTargets[tIdx] = target.copy(isInverted = !target.isInverted)
                                onUpdate(macroState.copy(targets = newTargets))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = "Polarity",
                                tint = if (target.targetId == -1) Color.DarkGray else if (target.isInverted) Color.Red else Color.Cyan,
                                modifier = Modifier.rotate(if (target.isInverted) 180f else 0f)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    macroState.targets.forEachIndexed { tIdx, target ->
                        val isLearning = grooveboxState.macroLearnActive && grooveboxState.macroLearnMacroIndex == index && grooveboxState.macroLearnTargetIndex == tIdx
                        val engineColor = if (target.targetId != -1) {
                             // Extract engine color from target info if possible, or use a default
                             // Since we don't have the engine info easily here, we just use a heuristic or the user's request:
                             // "Red for FM drum, gold for sampler, etc"
                             // We'll use getEngineColor helper if available in scope
                             getEngineColorForTarget(target.targetId)
                        } else Color.Gray

                        Button(
                            onClick = {
                                if (isLearning) {
                                    onStateChange(grooveboxState.copy(macroLearnActive = false, macroLearnMacroIndex = -1, macroLearnTargetIndex = -1))
                                } else if (target.targetId != -1) {
                                    val newTargets = macroState.targets.toMutableList()
                                    newTargets[tIdx] = target.copy(targetId = -1, targetLabel = "None", enabled = false)
                                    onUpdate(macroState.copy(targets = newTargets))
                                } else {
                                    onStateChange(grooveboxState.copy(macroLearnActive = true, macroLearnMacroIndex = index, macroLearnTargetIndex = tIdx))
                                }
                            },
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isLearning) Color.Yellow else engineColor),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            modifier = Modifier.fillMaxWidth().height(24.dp)
                        ) {
                            Text(
                                if (isLearning) "TAP..." else target.targetLabel.take(8),
                                fontSize = 10.sp,
                                color = if (isLearning) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            } else {
                // PHONE: Flat Row of Targets
                Icon(Icons.Default.ArrowForward, contentDescription = null, tint = Color.Gray.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    macroState.targets.forEachIndexed { tIdx, target ->
                        val isLearning = grooveboxState.macroLearnActive && grooveboxState.macroLearnMacroIndex == index && grooveboxState.macroLearnTargetIndex == tIdx
                        Box(
                            modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (isLearning) Color.Yellow else Color.DarkGray)
                                .border(if (target.targetId != -1) 1.dp else 0.dp, if (target.isInverted) Color.Red else Color.Cyan, RoundedCornerShape(4.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (isLearning) onStateChange(grooveboxState.copy(macroLearnActive = false))
                                        else if (target.targetId != -1) {
                                            val newTargets = macroState.targets.toMutableList()
                                            newTargets[tIdx] = target.copy(targetId = -1, targetLabel = "None", enabled = false)
                                            onUpdate(macroState.copy(targets = newTargets))
                                        } else onStateChange(grooveboxState.copy(macroLearnActive = true, macroLearnMacroIndex = index, macroLearnTargetIndex = tIdx))
                                    },
                                    onLongClick = {
                                        val newTargets = macroState.targets.toMutableList()
                                        newTargets[tIdx] = target.copy(isInverted = !target.isInverted)
                                        onUpdate(macroState.copy(targets = newTargets))
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (target.targetId != -1) {
                                    Icon(
                                        Icons.Default.ArrowForward,
                                        contentDescription = null,
                                        tint = if (target.isInverted) Color.Red else Color.Cyan,
                                        modifier = Modifier.size(10.dp).rotate(if (target.isInverted) 180f else 0f)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                                Text(if (isLearning) "TAP..." else target.targetLabel.take(6), fontSize = 9.sp, color = if (isLearning) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper to resolve engine color for a target parameter
fun getEngineColorForTarget(targetId: Int): Color {
    return when {
         targetId in 0..99 -> Color.White // Generic / Standard (Vol, Pan, Sends)
         targetId in 100..199 -> Color(0xFFE91E63) // Subtractive (Pink)
         targetId in 200..299 -> Color(0xFFF44336) // FM Drum (Red)
         targetId in 300..399 -> Color(0xFFFFC107) // Sampler (Gold)
         targetId in 400..499 -> Color(0xFFFF9800) // Granular (Orange) - Note: check real ranges
         targetId in 1700..1799 -> Color(0xFFFF9800) // Granular (Real range?)
         targetId in 1800..1899 -> Color(0xFF4CAF50) // Wavetable (Green)
         targetId in 4000..4999 -> Color(0xFF2196F3) // Analog Drum (Blue)
         targetId in 500..599 -> Color(0xFF00BCD4) // Global FX (Teal/Cyan)
         else -> Color.Gray
    }
}

@Composable
fun FxChainEditor(state: GrooveboxState, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    val fxNames = listOf(
        0 to "Overdrive", 1 to "Bitcrush", 2 to "Chorus", 3 to "Phaser", 4 to "Wobble",
        5 to "Delay", 6 to "Reverb", 7 to "Slicer", 8 to "Compressor",
        9 to "HP LFO", 10 to "LP LFO", 11 to "Flanger", 12 to "Filter 1", 13 to "TapeEcho", 14 to "Octaver",
        15 to "Filter 2", 16 to "Filter 3", 17 to "5-Band EQ"
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
                12 -> Color(0xFF009688) // Filter 1 (Teal)
                13 -> Color(0xFFB8860B) // TapeEcho (Dark Goldenrod)
                14 -> Color(0xFF3F51B5) // Octaver (Indigo)
                15 -> Color(0xFFE91E63) // Filter 2
                16 -> Color(0xFFE91E63) // Filter 3
                17 -> Color(0xFF44AAFF) // 5-Band EQ
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
                    Text(fxName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    
    // First, clear all existing mappings (reset all 17 FX to -1)
    for (i in 0 until 17) {
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
