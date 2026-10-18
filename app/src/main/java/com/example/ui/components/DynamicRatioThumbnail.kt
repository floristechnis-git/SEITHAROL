package com.example.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

@Composable
fun DynamicRatioThumbnail(
    imageUrl: String,
    fallbackRatio: Float = 1f,
    cornerRadius: Dp = 12.dp,
    modifier: Modifier = Modifier
) {
    var dynamicRatio by remember(imageUrl) { mutableFloatStateOf(fallbackRatio) }

    AsyncImage(
        model = imageUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        onState = { state ->
            if (state is AsyncImagePainter.State.Success) {
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    // Clamp between 0.75 (portrait) and 1.78 (16:9 video thumbnail)
                    dynamicRatio = (size.width / size.height).coerceIn(0.75f, 1.78f)
                }
            }
        },
        modifier = modifier
            .aspectRatio(dynamicRatio)
            .clip(RoundedCornerShape(cornerRadius))
    )
}
