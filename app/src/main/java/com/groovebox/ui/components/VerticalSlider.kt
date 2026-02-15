package com.groovebox.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groovebox.GrooveboxState
import com.groovebox.NativeLib

@Composable
fun VerticalSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color = Color.Cyan,
    height: Dp = 80.dp,
    width: Dp = 24.dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(width)
    ) {
        Text(
            label, 
            style = MaterialTheme.typography.labelSmall, 
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        Box(
            modifier = Modifier
                .height(height)
                .width(width)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = -dragAmount.y / size.height.toFloat()
                        val newValue = (value + delta).coerceIn(0.0f, 1.0f)
                        onValueChange(newValue)
                    }
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackWidth = 2.dp.toPx()
                val centerX = size.width / 2
                
                // Track
                drawLine(
                    color = Color.Gray.copy(alpha = 0.3f),
                    start = Offset(centerX, 4.dp.toPx()),
                    end = Offset(centerX, size.height - 4.dp.toPx()),
                    strokeWidth = trackWidth
                )
                
                // Handle
                val handleHeight = 12.dp.toPx()
                val handleWidth = size.width - 4.dp.toPx()
                val y = (1.0f - value) * (size.height - handleHeight)
                
                drawRoundRect(
                    color = color,
                    topLeft = Offset((size.width - handleWidth) / 2, y),
                    size = Size(handleWidth, handleHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
                
                // Center Line on handle
                drawLine(
                    color = Color.Black.copy(alpha = 0.5f),
                    start = Offset(centerX - handleWidth/4, y + handleHeight/2),
                    end = Offset(centerX + handleWidth/4, y + handleHeight/2),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}
