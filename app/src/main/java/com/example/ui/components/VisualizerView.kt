package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KangleiDark
import kotlin.math.sin

@Composable
fun AudioVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_bars")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val darkColor = KangleiDark

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        val barCount = 30
        val spacing = 3.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(2f)

        val gradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF888888),
                Color(0xFF555555),
                darkColor
            ),
            startY = 0f,
            endY = size.height
        )

        for (i in 0 until barCount) {
            val normalizedIndex = i.toFloat() / barCount
            val rawHeight = if (isPlaying) {
                val wave1 = sin(normalizedIndex * 8f + phase) * 0.4f
                val wave2 = sin(normalizedIndex * 14f - phase * 1.5f) * 0.3f
                val wave3 = sin(normalizedIndex * 4f + phase * 0.7f) * 0.3f
                (0.2f + (wave1 + wave2 + wave3).coerceIn(0.05f, 0.95f))
            } else {
                0.08f
            }

            val barHeight = (size.height * rawHeight).coerceIn(4f, size.height)
            val x = i * (barWidth + spacing)
            val y = size.height - barHeight

            drawRoundRect(
                brush = gradient,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}
