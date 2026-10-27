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
import androidx.compose.ui.graphics.drawscope.Fill
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
 * Studio Headphone SVG Icon for "Fresh New Music" cards on HomeScreen.
 */
@Composable
fun HeadphoneSvgIcon(
    tint: Color = Color.White,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val strokeWidth = 2.0f * sx

        // Headband arch
        val bandPath = Path().apply {
            moveTo(4f * sx, 14f * sy)
            lineTo(4f * sx, 11.5f * sy)
            cubicTo(
                4f * sx, 6.5f * sy,
                20f * sx, 6.5f * sy,
                20f * sx, 11.5f * sy
            )
            lineTo(20f * sx, 14f * sy)
        }
        drawPath(
            path = bandPath,
            color = tint,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Left ear cup
        drawRoundRect(
            color = tint,
            topLeft = Offset(3f * sx, 13f * sy),
            size = Size(4.2f * sx, 6.5f * sy),
            cornerRadius = CornerRadius(1.8f * sx, 1.8f * sy),
            style = Fill
        )

        // Right ear cup
        drawRoundRect(
            color = tint,
            topLeft = Offset(16.8f * sx, 13f * sy),
            size = Size(4.2f * sx, 6.5f * sy),
            cornerRadius = CornerRadius(1.8f * sx, 1.8f * sy),
            style = Fill
        )
    }
}

/**
 * Notification Bell SVG Icon with integrated Alert UI (ringing waves + live unread alert dot).
 */
@Composable
fun NotificationBellAlertSvgIcon(
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    hasUnreadAlert: Boolean = false,
    alertDotColor: Color = Color(0xFFFFB74D),
    modifier: Modifier = Modifier.size(22.dp)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "notif_alert_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = if (hasUnreadAlert) 1.22f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "notif_dot_scale"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = size.width / 24f
            val sy = size.height / 24f
            val stroke = Stroke(width = 1.9f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)

            // Bell body
            val bellPath = Path().apply {
                moveTo(18f * sx, 8.5f * sy)
                cubicTo(
                    18f * sx, 5.0f * sy,
                    15.3f * sx, 2.8f * sy,
                    12f * sx, 2.8f * sy
                )
                cubicTo(
                    8.7f * sx, 2.8f * sy,
                    6f * sx, 5.0f * sy,
                    6f * sx, 8.5f * sy
                )
                cubicTo(
                    6f * sx, 13.5f * sy,
                    4.0f * sx, 15.2f * sy,
                    4.0f * sx, 16.5f * sy
                )
                lineTo(20.0f * sx, 16.5f * sy)
                cubicTo(
                    20.0f * sx, 15.2f * sy,
                    18f * sx, 13.5f * sy,
                    18f * sx, 8.5f * sy
                )
                close()
            }
            drawPath(path = bellPath, color = tint, style = stroke)

            // Bell clapper
            val clapperPath = Path().apply {
                moveTo(10.0f * sx, 19.2f * sy)
                cubicTo(
                    10.6f * sx, 20.6f * sy,
                    13.4f * sx, 20.6f * sy,
                    14.0f * sx, 19.2f * sy
                )
            }
            drawPath(path = clapperPath, color = tint, style = stroke)

            if (hasUnreadAlert) {
                // Subtle acoustic alert arc on top-left of bell
                val wavePath = Path().apply {
                    moveTo(4.2f * sx, 4.5f * sy)
                    cubicTo(2.6f * sx, 6.0f * sy, 2.3f * sx, 8.2f * sy, 3.2f * sx, 10.0f * sy)
                }
                drawPath(
                    path = wavePath,
                    color = alertDotColor,
                    style = Stroke(width = 1.6f * sx, cap = StrokeCap.Round)
                )

                // Outer glow halo for unread alert badge
                drawCircle(
                    color = alertDotColor.copy(alpha = 0.32f),
                    radius = 4.2f * sx * pulseScale,
                    center = Offset(18.2f * sx, 5.5f * sy)
                )
                // Crisp inner alert dot
                drawCircle(
                    color = alertDotColor,
                    radius = 3.0f * sx,
                    center = Offset(18.2f * sx, 5.5f * sy)
                )
            }
        }
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

        // Floating Playlist SVG Badge at bottom-right (Square with border radius)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
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
