package com.seitharol.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Exact requested Home Nav SVG:
 * <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="1.8" stroke-linejoin="round"><path d="M4 11l8-7 8 7v9H4z"/></svg>
 */
@Composable
fun HomeNavSvgIcon(
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(4f * sx, 11f * sy)
            lineTo(12f * sx, 4f * sy)
            lineTo(20f * sx, 11f * sy)
            lineTo(20f * sx, 20f * sy)
            lineTo(4f * sx, 20f * sy)
            close()
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 1.8f * sx, join = StrokeJoin.Round, cap = StrokeCap.Round)
        )
    }
}

/**
 * Upgraded Sleek 4-Corner Outward Expand SVG icon for Fullscreen Lyrics.
 */
@Composable
fun FullscreenExpandSvgIcon(
    tint: Color = Color.White,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.1f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            // Top-Left corner bracket + diagonal ray
            moveTo(3.5f * sx, 9f * sy)
            lineTo(3.5f * sx, 3.5f * sy)
            lineTo(9f * sx, 3.5f * sy)
            moveTo(3.5f * sx, 3.5f * sy)
            lineTo(9.5f * sx, 9.5f * sy)

            // Top-Right corner bracket + diagonal ray
            moveTo(15f * sx, 3.5f * sy)
            lineTo(20.5f * sx, 3.5f * sy)
            lineTo(20.5f * sx, 9f * sy)
            moveTo(20.5f * sx, 3.5f * sy)
            lineTo(14.5f * sx, 9.5f * sy)

            // Bottom-Left corner bracket + diagonal ray
            moveTo(3.5f * sx, 15f * sy)
            lineTo(3.5f * sx, 20.5f * sy)
            lineTo(9f * sx, 20.5f * sy)
            moveTo(3.5f * sx, 20.5f * sy)
            lineTo(9.5f * sx, 14.5f * sy)

            // Bottom-Right corner bracket + diagonal ray
            moveTo(15f * sx, 20.5f * sy)
            lineTo(20.5f * sx, 20.5f * sy)
            lineTo(20.5f * sx, 15f * sy)
            moveTo(20.5f * sx, 20.5f * sy)
            lineTo(14.5f * sx, 14.5f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

/**
 * Upgraded Sleek 4-Corner Inward Minimize / Collapse Fullscreen SVG icon.
 */
@Composable
fun FullscreenMinimizeSvgIcon(
    tint: Color = Color.White,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.1f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            // Top-Left inward arrow
            moveTo(4f * sx, 9.5f * sy)
            lineTo(9.5f * sx, 9.5f * sy)
            lineTo(9.5f * sx, 4f * sy)
            moveTo(3.5f * sx, 3.5f * sy)
            lineTo(9.5f * sx, 9.5f * sy)

            // Top-Right inward arrow
            moveTo(14.5f * sx, 4f * sy)
            lineTo(14.5f * sx, 9.5f * sy)
            lineTo(20f * sx, 9.5f * sy)
            moveTo(20.5f * sx, 3.5f * sy)
            lineTo(14.5f * sx, 9.5f * sy)

            // Bottom-Left inward arrow
            moveTo(4f * sx, 14.5f * sy)
            lineTo(9.5f * sx, 14.5f * sy)
            lineTo(9.5f * sx, 20f * sy)
            moveTo(3.5f * sx, 20.5f * sy)
            lineTo(9.5f * sx, 14.5f * sy)

            // Bottom-Right inward arrow
            moveTo(20f * sx, 14.5f * sy)
            lineTo(14.5f * sx, 14.5f * sy)
            lineTo(14.5f * sx, 20f * sy)
            moveTo(20.5f * sx, 20.5f * sy)
            lineTo(14.5f * sx, 14.5f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

/**
 * Animated Music Note SVG indicator for instrumental intros, outros, and vocal gaps >= 4.5s.
 */
@Composable
fun InstrumentalGapMusicNoteIndicator(
    tint: Color = Color.White,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "instrumental_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "note_scale"
    )

    Row(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(28.dp).scale(scale)) {
            val sx = size.width / 24f
            val sy = size.height / 24f
            val path = Path().apply {
                moveTo(9f * sx, 18f * sy)
                lineTo(9f * sx, 5f * sy)
                lineTo(21f * sx, 3f * sy)
                lineTo(21f * sx, 16f * sy)
            }
            drawPath(path, color = tint, style = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color = tint, radius = 3f * sx, center = Offset(6f * sx, 18f * sy))
            drawCircle(color = tint, radius = 3f * sx, center = Offset(18f * sx, 16f * sy))
        }
    }
}

/**
 * Playlist / Album Thumbnail with First Video Thumbnail + Floating Playlist SVG at Bottom-Right.
 * In Library: if empty, renders a Donut-shaped placeholder with the Playlist SVG at Bottom-Right.
 */
@Composable
fun PlaylistThumbnailWithBadge(
    firstTrackThumbnailUrl: String?,
    size: Dp = 140.dp,
    cornerRadius: Dp = 12.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!firstTrackThumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = firstTrackThumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Donut shape placeholder for empty playlists in Library
            Canvas(modifier = Modifier.size(size * 0.55f)) {
                val outerRadius = this.size.minDimension / 2f
                val strokeWidth = outerRadius * 0.42f
                drawCircle(
                    color = Color.Gray.copy(alpha = 0.38f),
                    radius = outerRadius - strokeWidth / 2f,
                    style = Stroke(width = strokeWidth)
                )
            }
        }

        // Floating Playlist SVG Badge at bottom-right
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                val sx = this.size.width / 24f
                val sy = this.size.height / 24f
                val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(3f * sx, 6f * sy), Offset(15f * sx, 6f * sy), strokeWidth = stroke.width)
                drawLine(Color.White, Offset(3f * sx, 12f * sy), Offset(15f * sx, 12f * sy), strokeWidth = stroke.width)
                drawLine(Color.White, Offset(3f * sx, 18f * sy), Offset(11f * sx, 18f * sy), strokeWidth = stroke.width)
                val playPath = Path().apply {
                    moveTo(16f * sx, 13f * sy)
                    lineTo(22f * sx, 17f * sy)
                    lineTo(16f * sx, 21f * sy)
                    close()
                }
                drawPath(playPath, color = Color.White)
            }
        }
    }
}
