// java/com/seitharol/ui/search/DiscoverEntryCard.kt
package com.seitharol.ui.search

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CompassSvgIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(24.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(
            color = tint,
            radius = 10f * sx,
            center = Offset(12f * sx, 12f * sy),
            style = stroke
        )
        val needle = Path().apply {
            moveTo(16.24f * sx, 7.76f * sy)
            lineTo(14.12f * sx, 14.12f * sy)
            lineTo(7.76f * sx, 16.24f * sy)
            lineTo(9.88f * sx, 9.88f * sy)
            close()
        }
        drawPath(path = needle, color = tint, style = stroke)
    }
}

@Composable
fun ChevronRightSvgIcon(
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val path = Path().apply {
            moveTo(9f * sx, 6f * sy)
            lineTo(15f * sx, 12f * sy)
            lineTo(9f * sx, 18f * sy)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = 2.2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun DiscoverEntryCard(
    onOpenDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                onOpenDiscover()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                CompassSvgIcon(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Discover & Download",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Swipe previews & lossless downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ChevronRightSvgIcon(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun BackArrowSvgIcon(
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier.size(22.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(19f * sx, 12f * sy)
            lineTo(5f * sx, 12f * sy)
            moveTo(12f * sx, 19f * sy)
            lineTo(5f * sx, 12f * sy)
            lineTo(12f * sx, 5f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

@Composable
fun DownloadSvgIcon(
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(12f * sx, 3f * sy)
            lineTo(12f * sx, 15f * sy)
            moveTo(7f * sx, 10f * sy)
            lineTo(12f * sx, 15f * sy)
            lineTo(17f * sx, 10f * sy)
            moveTo(4f * sx, 19f * sy)
            lineTo(20f * sx, 19f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}

@Composable
fun QueueAddSvgIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(20.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val path = Path().apply {
            moveTo(3f * sx, 6f * sy)
            lineTo(15f * sx, 6f * sy)
            moveTo(3f * sx, 12f * sy)
            lineTo(11f * sx, 12f * sy)
            moveTo(3f * sx, 18f * sy)
            lineTo(11f * sx, 18f * sy)
            moveTo(18f * sx, 13f * sy)
            lineTo(18f * sx, 19f * sy)
            moveTo(15f * sx, 16f * sy)
            lineTo(21f * sx, 16f * sy)
        }
        drawPath(path = path, color = tint, style = stroke)
    }
}
