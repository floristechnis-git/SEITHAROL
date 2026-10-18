package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalIsDarkTheme = compositionLocalOf { true }

val PureWhite = Color(0xFFFFFFFF)
val DarkBackground = Color(0xFF0F0F0F)
val DarkSurface = Color(0xFF1B1C1C)
val DarkSurfaceVariant = Color(0xFF262727)
val MutedText = Color(0xFFA0A0A0)

private val SeitharolDarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = Color(0xFF0D0D0D),
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = PureWhite,
    secondary = PureWhite,
    onSecondary = Color(0xFF0D0D0D),
    tertiary = PureWhite,
    background = DarkBackground,
    onBackground = PureWhite,
    surface = DarkSurface,
    onSurface = PureWhite,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MutedText,
    outline = Color(0xFF333333)
)

@Composable
fun CascadeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    // Always enforce Pure White & Deep Dark Theme
    CompositionLocalProvider(LocalIsDarkTheme provides true) {
        MaterialTheme(
            colorScheme = SeitharolDarkColorScheme,
            typography = Typography,
            content = content
        )
    }
}
