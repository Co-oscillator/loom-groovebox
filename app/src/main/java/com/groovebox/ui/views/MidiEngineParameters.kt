package com.groovebox.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groovebox.EngineType
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib
import com.groovebox.ui.theme.getEngineColor
import kotlin.math.absoluteValue

@Composable
fun MidiEngineParameters(state: GrooveboxState, trackIndex: Int, onStateChange: (GrooveboxState) -> Unit, nativeLib: NativeLib) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text("MIDI CHANNEL ROUTING", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(modifier = Modifier.height(16.dp))

        // Header Row
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text("TRACK", modifier = Modifier.width(60.dp), color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.weight(1f))
            Text("MIDI IN", modifier = Modifier.width(80.dp), color = Color.Gray, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.width(16.dp))
            Text("MIDI OUT", modifier = Modifier.width(80.dp), color = Color.Gray, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        Spacer(modifier = Modifier.height(8.dp))

        (0 until 8).forEach { idx ->
            val track = state.tracks[idx]
            val engineColor = getEngineColor(track.engineType)
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(Color(0xFF222222), RoundedCornerShape(4.dp))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Track Label
                Column(modifier = Modifier.width(60.dp)) {
                    Text("TRK ${idx + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(track.engineType.name, color = engineColor, fontSize = 8.sp, maxLines = 1)
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // MIDI IN Select
                // Values: 1-16, 17=ALL
                MidiChannelSelector(
                    value = track.midiInChannel,
                    onValueChange = { ch ->
                         onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> 
                             if (i == idx) t.copy(midiInChannel = ch) else t 
                         }))
                    },
                    modifier = Modifier.width(80.dp),
                    allowAll = true
                )

                Spacer(modifier = Modifier.width(16.dp))
                
                // MIDI OUT Select
                // Values: 1-16, 17=ALL (Passthrough)
                MidiChannelSelector(
                    value = track.midiOutChannel,
                    onValueChange = { ch ->
                         onStateChange(state.copy(tracks = state.tracks.mapIndexed { i, t -> 
                             if (i == idx) t.copy(midiOutChannel = ch) else t 
                         }))
                    },
                    modifier = Modifier.width(80.dp),
                    allowAll = true
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
fun MidiChannelSelector(
    value: Int, 
    onValueChange: (Int) -> Unit, 
    modifier: Modifier = Modifier,
    allowAll: Boolean = false
) {
    var isDragging by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(Color.Black, RoundedCornerShape(4.dp))
            .border(1.dp, if (isDragging) Color.Cyan else Color.DarkGray, RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                  detectVerticalDragGestures { change, dragAmount ->
                       // Drag sensitivity
                       if (dragAmount.absoluteValue > 2f) {
                           val direction = if (dragAmount < 0) 1 else -1 // Drag up = increment
                           val newValue = (value + direction).coerceIn(1, if (allowAll) 17 else 16)
                           if (newValue != value) onValueChange(newValue)
                       }
                  }
            },
        contentAlignment = Alignment.Center
    ) {
        // User said "Options should include 1-16 and also All."
        val displayLabel = if (value == 17) "ALL" else "$value"
        
        Text(
            displayLabel, 
            color = if (value == 17) Color.Cyan else Color.White, 
            fontWeight = FontWeight.Bold, 
            fontSize = 14.sp
        )
    }
}

// Custom Drag Helper
suspend fun PointerInputScope.detectVerticalDragGestures(
    onDrag: (change: PointerInputChange, dragAmount: Float) -> Unit
) {
    detectDragGestures { change, dragAmount ->
        change.consume()
        onDrag(change, dragAmount.y)
    }
}
