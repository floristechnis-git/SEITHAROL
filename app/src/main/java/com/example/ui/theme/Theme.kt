package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF262727)
)

private val SeitharolLightColorScheme = lightColorScheme(
    primary = Color(0xFF121212),
    onPrimary = PureWhite,
    primaryContainer = Color(0xFFECEEF0),
    onPrimaryContainer = Color(0xFF121212),
    secondary = Color(0xFF121212),
    onSecondary = PureWhite,
    tertiary = Color(0xFF333333),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF121212),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFECEEF0),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFFE0E0E0),
    outlineVariant = Color(0xFFE8E8E8)
)

@Composable
fun CascadeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) SeitharolDarkColorScheme else SeitharolLightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
