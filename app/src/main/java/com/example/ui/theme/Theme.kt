package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalIsDarkTheme = compositionLocalOf { false }

private val KangleiLightColorScheme = lightColorScheme(
    primary = Color(0xFF0D0D0D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF0D0D0D),
    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF8A8A8A),
    background = Color(0xFFF2F2F2),
    onBackground = Color(0xFF0D0D0D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF5C5C5C),
    outline = Color(0xFFE5E5E5)
)

private val KangleiDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF0D0D0D),
    primaryContainer = Color(0xFF1E1E1E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF0D0D0D),
    tertiary = Color(0xFF8A8A8A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF333333)
)

@Composable
fun CascadeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) KangleiDarkColorScheme else KangleiLightColorScheme

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
