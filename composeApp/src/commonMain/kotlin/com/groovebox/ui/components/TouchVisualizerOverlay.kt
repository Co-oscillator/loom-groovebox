package com.groovebox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.groovebox.GrooveboxState

@Composable
fun TouchVisualizerOverlay(
    state: GrooveboxState,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }

    var touchPoints by remember { mutableStateOf<Map<Long, Offset>>(emptyMap()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        
                        val newPoints = touchPoints.toMutableMap()
                        
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                newPoints[change.id.value] = change.position
                            } else {
                                newPoints.remove(change.id.value)
                            }
                        }
                        
                        touchPoints = newPoints
                    }
                }
            }
    ) {
        content()

        Canvas(modifier = Modifier.fillMaxSize()) {
            touchPoints.values.forEach { position ->
                // Draw a glowing circle at the touch point
                drawCircle(
                    color = Color.Cyan.copy(alpha = 0.3f),
                    radius = 30.dp.toPx(),
                    center = position
                )
                drawCircle(
                    color = Color.Cyan,
                    radius = 25.dp.toPx(),
                    center = position,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.5f),
                    radius = 5.dp.toPx(),
                    center = position
                )
            }
        }
    }
}
