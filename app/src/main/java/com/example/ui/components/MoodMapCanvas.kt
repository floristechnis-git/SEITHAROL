package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Track
import com.example.ui.theme.*

@Composable
fun MoodMapCanvas(
    tracks: List<Track>,
    onMoodSelected: (energy: Float, valence: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPoint by remember { mutableStateOf<Offset?>(null) }
    var selectedQuadrantLabel by remember { mutableStateOf("Tap any region to start Mood Radio") }
    val darkColor = SeitharolDark

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SeitharolWhite)
            .padding(16.dp)
            .testTag("mood_map_container")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "2D Local Mood Engine",
                    fontFamily = SyneFontFamily,
                    color = SeitharolDark,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = selectedQuadrantLabel,
                    fontFamily = OutfitFontFamily,
                    color = SeitharolTextSecondary,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SeitharolCardElevated)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(tracks) {
                        detectTapGestures { offset ->
                            val energy = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                            val valence = (offset.x / size.width).coerceIn(0f, 1f)
                            selectedPoint = offset

                            selectedQuadrantLabel = when {
                                energy >= 0.5f && valence >= 0.5f -> "Energetic & Euphoric Radio"
                                energy >= 0.5f && valence < 0.5f -> "Intense Rock Radio"
                                energy < 0.5f && valence >= 0.5f -> "Chill & Warm Folk Radio"
                                else -> "Melancholic & Lo-Fi Radio"
                            }

                            onMoodSelected(energy, valence)
                        }
                    }
                    .testTag("mood_canvas")
            ) {
                val w = size.width
                val h = size.height

                // Draw Axes
                drawLine(
                    color = Color(0xFFE5E5E5),
                    start = Offset(0f, h / 2f),
                    end = Offset(w, h / 2f),
                    strokeWidth = 1.5.dp.toPx()
                )
                drawLine(
                    color = Color(0xFFE5E5E5),
                    start = Offset(w / 2f, 0f),
                    end = Offset(w / 2f, h),
                    strokeWidth = 1.5.dp.toPx()
                )

                // Plot all tracks as dots
                tracks.forEach { track ->
                    val px = track.moodY * w
                    val py = (1f - track.moodX) * h
                    drawCircle(
                        color = if (track.isDownloaded) darkColor else Color(0xFF9E9E9E),
                        radius = 4.dp.toPx(),
                        center = Offset(px, py)
                    )
                }

                // Plot selected point
                selectedPoint?.let { pt ->
                    drawCircle(
                        color = darkColor.copy(alpha = 0.2f),
                        radius = 20.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = darkColor,
                        radius = 6.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 20.dp.toPx(),
                        center = pt,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // Quadrant labels overlay
            Text(
                text = "High Energy",
                color = SeitharolTextSecondary,
                fontSize = 10.sp,
                fontFamily = OutfitFontFamily,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            )
            Text(
                text = "Low Energy",
                color = SeitharolTextSecondary,
                fontSize = 10.sp,
                fontFamily = OutfitFontFamily,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp)
            )
            Text(
                text = "Calm",
                color = SeitharolTextSecondary,
                fontSize = 10.sp,
                fontFamily = OutfitFontFamily,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
            )
            Text(
                text = "Upbeat",
                color = SeitharolTextSecondary,
                fontSize = 10.sp,
                fontFamily = OutfitFontFamily,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
            )
        }
    }
}
