package com.seitharol.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

data class PlaylistSummary(
    val id: String,
    val title: String,
    val trackCount: Int = 0,
    val firstVideoThumbnailUrl: String? = null,
    val isLikedSongsCard: Boolean = false
)

@Composable
fun SearchMagnifierSvgIcon(tint: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2f * sx, cap = StrokeCap.Round)
        drawCircle(
            color = tint,
            radius = 7.5f * sx,
            center = Offset(10.5f * sx, 10.5f * sy),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(16f * sx, 16f * sy),
            end = Offset(21f * sx, 21f * sy),
            strokeWidth = 2f * sx,
            cap = StrokeCap.Round
        )
    }
}
