package com.praxper.seitharol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PureWhite = Color(0xFFFFFFFF)
val DarkBackground = Color(0xFF0F0F0F)
val DarkSurface = Color(0xFF1B1C1C)
val DarkSurfaceVariant = Color(0xFF262727)
val MutedText = Color(0xFFA0A0A0)

private val SeitharolDarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = Color.Black,
    secondary = PureWhite,
    onSecondary = Color.Black,
    tertiary = PureWhite,
    background = DarkBackground,
    onBackground = PureWhite,
    surface = DarkSurface,
    onSurface = PureWhite,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MutedText
)

@Composable
fun SeitharolTheme(content: @Composable () -> Unit) {
    // Always enforce Dark Theme with Pure White accents (no yellow)
    MaterialTheme(
        colorScheme = SeitharolDarkColorScheme,
        content = content
    )
}
