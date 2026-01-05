package com.groovebox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ToggleIcon(
    label: String,
    paramId: Int,
    state: GrooveboxState,
    onStateChange: (GrooveboxState) -> Unit,
    nativeLib: NativeLib,
    modifier: Modifier = Modifier,
    checkedColor: Color = Color.Cyan
) {
    val track = state.tracks[state.selectedTrackIndex]
    val value = track.parameters[paramId] ?: 0f
    val isChecked = value > 0.5f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable {
                val newValue = if (isChecked) 0f else 1f
                val newParams = track.parameters.toMutableMap()
                newParams[paramId] = newValue
                
                val newTracks = state.tracks.toMutableList()
                newTracks[state.selectedTrackIndex] = track.copy(parameters = newParams)
                
                onStateChange(state.copy(tracks = newTracks))
                nativeLib.setParameter(state.selectedTrackIndex, paramId, newValue)
            }
            .padding(4.dp)
    ) {
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
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.Black, RoundedCornerShape(2.dp))
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isChecked) checkedColor else Color.Gray
        )
    }
}
