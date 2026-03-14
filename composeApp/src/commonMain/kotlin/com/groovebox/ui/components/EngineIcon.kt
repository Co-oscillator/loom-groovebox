package com.groovebox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.groovebox.EngineType
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size

@Composable
fun EngineIcon(
    engineType: EngineType,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    size: Dp = 24.dp
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cX = w / 2f
        val cY = h / 2f
        
        when (engineType) {
            EngineType.SUBTRACTIVE -> {
                // Square
                drawRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.15f),
                    size = androidx.compose.ui.geometry.Size(w * 0.7f, h * 0.7f)
                )
            }
            EngineType.FM -> {
                // Triangle
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cX, h * 0.1f)
                    lineTo(w * 0.9f, h * 0.9f)
                    lineTo(w * 0.1f, h * 0.9f)
                    close()
                }
                drawPath(path = path, color = tint)
            }
            EngineType.SAMPLER -> {
                // Waveform / Zig-zag
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.1f, h * 0.5f)
                    lineTo(w * 0.3f, h * 0.2f)
                    lineTo(w * 0.7f, h * 0.8f)
                    lineTo(w * 0.9f, h * 0.5f)
                }
                drawPath(
                    path = path, 
                    color = tint, 
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f)
                )
            }
            EngineType.GRANULAR -> {
                // 3 Small dots (particles)
                drawCircle(color = tint, radius = w * 0.15f, center = androidx.compose.ui.geometry.Offset(cX, h * 0.3f))
                drawCircle(color = tint, radius = w * 0.15f, center = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.7f))
                drawCircle(color = tint, radius = w * 0.15f, center = androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.7f))
            }
            EngineType.FM_DRUM -> {
                // Hexagon
                val r = w * 0.45f
                val path = androidx.compose.ui.graphics.Path().apply {
                    for (i in 0 until 6) {
                        val angle = (i * 60 + 30) * kotlin.math.PI / 180f
                        val x = cX + r * kotlin.math.cos(angle).toFloat()
                        val y = cY + r * kotlin.math.sin(angle).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(path = path, color = tint)
            }
            EngineType.ANALOG_DRUM -> {
                // Circle
                drawCircle(
                    color = tint,
                    radius = w * 0.4f,
                    center = androidx.compose.ui.geometry.Offset(cX, cY)
                )
            }
            EngineType.AUDIO_IN -> {
                // Open circle with a dot in middle (target/input)
                drawCircle(
                    color = tint,
                    radius = w * 0.4f,
                    center = androidx.compose.ui.geometry.Offset(cX, cY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.1f)
                )
                drawCircle(
                    color = tint,
                    radius = w * 0.15f,
                    center = androidx.compose.ui.geometry.Offset(cX, cY)
                )
            }
            EngineType.SOUNDFONT -> {
                // Diamond
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(cX, h * 0.1f)
                    lineTo(w * 0.9f, cY)
                    lineTo(cX, h * 0.9f)
                    lineTo(w * 0.1f, cY)
                    close()
                }
                drawPath(path = path, color = tint)
            }
            else -> {
                // Cross / Plus
                drawRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.4f, h * 0.1f),
                    size = androidx.compose.ui.geometry.Size(w * 0.2f, h * 0.8f)
                )
                drawRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.1f, h * 0.4f),
                    size = androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.2f)
                )
            }
        }
    }
}
