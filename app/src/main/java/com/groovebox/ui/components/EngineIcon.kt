package com.groovebox.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.groovebox.EngineType

@Composable
fun EngineIcon(type: EngineType, modifier: Modifier = Modifier, color: Color = Color.White, drumType: String? = null) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        when {
            type == EngineType.FM_DRUM && drumType != null -> {
                when (drumType) {
                    "KICK" -> {
                        // Jagged Ring (Star-like or ZigZag circle)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radiusOuter = size.minDimension * 0.45f
                        val radiusInner = size.minDimension * 0.35f
                        val points = 16
                        
                        val path = Path().apply {
                            for (i in 0 until points * 2) {
                                val angle = (Math.PI * 2 * i) / (points * 2)
                                val r = if (i % 2 == 0) radiusOuter else radiusInner
                                val x = center.x + r * Math.cos(angle).toFloat()
                                val y = center.y + r * Math.sin(angle).toFloat()
                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                            }
                            close()
                        }
                        drawPath(path, color, style = Stroke(width = strokeWidth))
                        
                        // Inner solid circle
                        drawCircle(color, radius = size.minDimension * 0.15f, center = center)
                    }
                    "SNARE" -> {
                        drawRect(color, size = Size(size.width, size.height * 0.4f), topLeft = Offset(0f, size.height * 0.3f), style = Stroke(width = strokeWidth))
                        drawLine(color, start = Offset(0f, size.height * 0.5f), end = Offset(size.width, size.height * 0.5f), strokeWidth = 1f)
                    }
                    "HIHAT", "HIHAT OPEN" -> {
                        val path = Path().apply {
                            moveTo(size.width * 0.1f, size.height * 0.7f)
                            quadraticBezierTo(size.width * 0.5f, size.height * 0.3f, size.width * 0.9f, size.height * 0.7f)
                            if (drumType == "HIHAT OPEN") {
                                moveTo(size.width * 0.2f, size.height * 0.4f)
                                lineTo(size.width * 0.8f, size.height * 0.4f)
                            }
                        }
                        drawPath(path, color, style = Stroke(width = strokeWidth))
                    }
                    "TOM" -> {
                        drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(0f, size.height * 0.2f), size = Size(size.width, size.height * 0.6f), style = Stroke(width = strokeWidth))
                        drawLine(color, start = Offset(0f, size.height * 0.5f), end = Offset(size.width, size.height * 0.5f), strokeWidth = strokeWidth)
                    }
                    "CYMBAL" -> {
                        drawArc(color, startAngle = 200f, sweepAngle = 140f, useCenter = false, style = Stroke(width = strokeWidth))
                        drawLine(color, start = center, end = Offset(center.x, size.height), strokeWidth = strokeWidth)
                    }
                    else -> {
                        // Diamond for Perc/Noise
                        val path = Path().apply {
                            moveTo(size.width / 2f, 0f)
                            lineTo(size.width, size.height / 2f)
                            lineTo(size.width / 2f, size.height)
                            lineTo(0f, size.height / 2f)
                            close()
                        }
                        drawPath(path, color, style = Stroke(width = strokeWidth))
                    }
                }
            }
            type == EngineType.SUBTRACTIVE -> {
                // Sawtooth line
                val path = Path().apply {
                    moveTo(0f, size.height * 0.8f)
                    lineTo(size.width * 0.8f, size.height * 0.2f)
                    lineTo(size.width * 0.8f, size.height * 0.8f)
                    moveTo(size.width * 0.8f, size.height * 0.8f)
                    lineTo(size.width, size.height * 0.8f)
                }
                drawPath(path, color, style = Stroke(width = strokeWidth))
            }
            type == EngineType.FM -> {
                // Overlapping Sine waves (Reverted)
                for (offset in listOf(0f, size.height * 0.2f)) {
                    val path = Path()
                    for (i in 0..60) {
                        val x = (i / 60f) * size.width
                        val y = (size.height / 2f + offset / 2f) + Math.sin(i * 0.3).toFloat() * (size.height * 0.2f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color.copy(alpha = if (offset == 0f) 1f else 0.5f), style = Stroke(width = strokeWidth))
                }
            }
            type == EngineType.FM_DRUM && drumType == null -> {
                // Analog Drum style (Kick, Snare, Hihat) but with Jagged Ring for Kick
                val centerKick = Offset(size.width * 0.3f, size.height * 0.7f)
                val radiusOuter = size.minDimension * 0.22f
                val radiusInner = size.minDimension * 0.16f
                val points = 12

                val path = Path().apply {
                    for (i in 0 until points * 2) {
                        val angle = (Math.PI * 2 * i) / (points * 2)
                        val r = if (i % 2 == 0) radiusOuter else radiusInner
                        val x = centerKick.x + r * Math.cos(angle).toFloat()
                        val y = centerKick.y + r * Math.sin(angle).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(path, color, style = Stroke(width = strokeWidth))

                // Snare (Analog style)
                drawRect(color, size = Size(size.width * 0.3f, size.height * 0.15f), topLeft = Offset(size.width * 0.6f, size.height * 0.5f), style = Stroke(width = strokeWidth))
                // HiHat (Analog style)
                drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.3f), end = Offset(size.width * 0.5f, size.height * 0.3f), strokeWidth = strokeWidth)
                drawLine(color, start = Offset(size.width * 0.35f, size.height * 0.3f), end = Offset(size.width * 0.35f, size.height * 0.5f), strokeWidth = strokeWidth)
            }
            type == EngineType.SAMPLER -> {
                // Waveform snippet
                val path = Path().apply {
                    moveTo(0f, size.height / 2f)
                    lineTo(size.width * 0.2f, size.height * 0.1f)
                    lineTo(size.width * 0.4f, size.height * 0.9f)
                    lineTo(size.width * 0.6f, size.height * 0.3f)
                    lineTo(size.width * 0.8f, size.height * 0.7f)
                    lineTo(size.width, size.height / 2f)
                }
                drawPath(path, color, style = Stroke(width = strokeWidth))
            }
            type == EngineType.GRANULAR -> {
                // Cloud of points/lines
                val random = java.util.Random(42)
                repeat(12) {
                    val x = random.nextFloat() * size.width
                    val y = random.nextFloat() * size.height
                    drawCircle(color, radius = 2f, center = Offset(x, y))
                }
            }
            type == EngineType.WAVETABLE -> {
                // Stacked isometric lines
                repeat(3) { i ->
                    val yOff = i * 8f
                    val path = Path().apply {
                        moveTo(0f, size.height * 0.7f - yOff)
                        lineTo(size.width * 0.4f, size.height * 0.4f - yOff)
                        lineTo(size.width, size.height * 0.6f - yOff)
                    }
                    drawPath(path, color.copy(alpha = 1f - i * 0.3f), style = Stroke(width = strokeWidth))
                }
            }
            type == EngineType.ANALOG_DRUM -> {
                // Drum Kit Icon
                // Kick
                drawCircle(color, radius = size.minDimension * 0.2f, center = Offset(size.width * 0.3f, size.height * 0.7f), style = Stroke(width = strokeWidth))
                // Snare
                drawRect(color, size = Size(size.width * 0.3f, size.height * 0.15f), topLeft = Offset(size.width * 0.6f, size.height * 0.5f), style = Stroke(width = strokeWidth))
                // HiHat
                drawLine(color, start = Offset(size.width * 0.2f, size.height * 0.3f), end = Offset(size.width * 0.5f, size.height * 0.3f), strokeWidth = strokeWidth)
                drawLine(color, start = Offset(size.width * 0.35f, size.height * 0.3f), end = Offset(size.width * 0.35f, size.height * 0.5f), strokeWidth = strokeWidth)
            }
            type == EngineType.MIDI -> {
                // MIDI 5-Pin DIN Icon
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension * 0.45f
                
                // Outer Ring
                drawCircle(color, radius = radius, style = Stroke(width = strokeWidth))
                
                // Pins (5 dots in a semicircle)
                val pinRadius = size.minDimension * 0.06f
                // Simple version: 5 dots in an arc at the bottom
                val arcRadius = radius * 0.65f
                for (i in 0..4) {
                    val angleDeg = 180f + (30f * (i - 2)) // 120, 150, 180, 210, 240
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val x = center.x + arcRadius * Math.sin(angleRad).toFloat()
                    val y = center.y + arcRadius * Math.cos(angleRad).toFloat()
                    drawCircle(color, radius = pinRadius, center = Offset(x, y))
                }
                // Key notch at top
                 drawRect(color, size = Size(size.width * 0.15f, size.height * 0.1f), topLeft = Offset(center.x - size.width * 0.075f, center.y - radius), style = Fill)
            }
            type == EngineType.AUDIO_IN -> {
                // Waveform entering a circle/mic
                drawCircle(color, radius = size.minDimension * 0.4f, style = Stroke(width = strokeWidth))
                val path = Path().apply {
                    moveTo(size.width * 0.2f, size.height * 0.5f)
                    lineTo(size.width * 0.4f, size.height * 0.3f)
                    lineTo(size.width * 0.6f, size.height * 0.7f)
                    lineTo(size.width * 0.8f, size.height * 0.5f)
                }
                drawPath(path, color, style = Stroke(width = strokeWidth))
            }
            type == EngineType.SOUNDFONT -> {
                // Beamed Note Icon with Offset Square
                val headRadius = size.minDimension * 0.15f
                val stemHeight = size.height * 0.5f
                val note1Center = Offset(size.width * 0.3f, size.height * 0.75f)
                val note2Center = Offset(size.width * 0.7f, size.height * 0.65f)
                
                // Background Square (Offset) - Drawn first
                val squareSize = size.minDimension * 0.6f
                drawRect(
                    color = color.copy(alpha = 0.3f),
                    topLeft = Offset(size.width * 0.45f, size.height * 0.1f),
                    size = Size(squareSize, squareSize),
                    style = Stroke(width = strokeWidth)
                )

                // Heads
                drawOval(color, topLeft = Offset(note1Center.x - headRadius, note1Center.y - headRadius * 0.8f), size = Size(headRadius * 2, headRadius * 1.6f))
                drawOval(color, topLeft = Offset(note2Center.x - headRadius, note2Center.y - headRadius * 0.8f), size = Size(headRadius * 2, headRadius * 1.6f))
                
                // Stems
                val stemWidth = strokeWidth
                drawLine(color, start = Offset(note1Center.x + headRadius * 0.8f, note1Center.y), end = Offset(note1Center.x + headRadius * 0.8f, note1Center.y - stemHeight), strokeWidth = stemWidth)
                drawLine(color, start = Offset(note2Center.x + headRadius * 0.8f, note2Center.y), end = Offset(note2Center.x + headRadius * 0.8f, note2Center.y - stemHeight), strokeWidth = stemWidth)
                
                // Beam
                val beamStart = Offset(note1Center.x + headRadius * 0.8f, note1Center.y - stemHeight)
                val beamEnd = Offset(note2Center.x + headRadius * 0.8f, note2Center.y - stemHeight)
                drawLine(color, start = beamStart, end = beamEnd, strokeWidth = stemWidth * 2.5f)
            }
            else -> {
                drawRect(color, style = Stroke(width = strokeWidth))
            }
        }
    }
}
