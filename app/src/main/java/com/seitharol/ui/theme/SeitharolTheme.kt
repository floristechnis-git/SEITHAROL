package com.seitharol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.LocalIsDarkTheme
import com.seitharol.data.preferences.ThemeMode

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFFFF4E45),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1D1B),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF2E3032),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF262727),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFFF6E66),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF2D2322),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1B1C1C),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF262727),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.Transparent,
    outline = Color(0xFF3A3B3C),
    outlineVariant = Color(0xFF28292A)
)

private val LightColorPalette = lightColorScheme(
    primary = Color(0xFFD93025),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5E3),
    onPrimaryContainer = Color(0xFF8C1D18),
    secondary = Color(0xFFE4E7EB),
    onSecondary = Color(0xFF111315),
    secondaryContainer = Color(0xFFE8EAED),
    onSecondaryContainer = Color(0xFF111315),
    tertiary = Color(0xFF37474F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3F2FD),
    onTertiaryContainer = Color(0xFF0D3B66),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF111315),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111315),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF3C4043),
    surfaceTint = Color.Transparent,
    outline = Color(0xFFC4C7C5),
    outlineVariant = Color(0xFFDADCE0)
)

@Composable
fun SeitharolTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    CompositionLocalProvider(LocalIsDarkTheme provides isDark) {
        MaterialTheme(
            colorScheme = if (isDark) DarkColorPalette else LightColorPalette,
            content = content
        )
    }
}
