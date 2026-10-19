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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Custom Compass SVG Icon for the Discover entry card.
 */
@Composable
fun CompassSvgIcon(
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier.size(24.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Outer compass circle
        drawCircle(
            color = tint,
            radius = 10f * sx,
            center = androidx.compose.ui.geometry.Offset(12f * sx, 12f * sy),
            style = stroke
        )

        // Compass diamond needle: polygon(16.24 7.76, 14.12 14.12, 7.76 16.24, 9.88 9.88)
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

/**
 * Placed in SearchScreen directly above the "Artists" section.
 * Matches the Artist item card layout, but replaces the thumbnail image with a Compass SVG icon.
 */
@Composable
fun DiscoverEntryCard(
    onOpenDiscover: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onOpenDiscover() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading circular icon container (no image thumbnail)
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
                    text = "Swipe 30s previews & lossless downloads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
