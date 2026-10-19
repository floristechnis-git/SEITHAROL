package com.seitharol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.seitharol.data.preferences.ThemeMode

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFFFF4E45),
    onPrimary = Color.White,
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF1B1C1C),
    onSurface = Color(0xFFE3E4E2),
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFAAAAAA)
)

private val LightColorPalette = lightColorScheme(
    primary = Color(0xFFD93025),
    onPrimary = Color.White,
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF121212),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFECEEF0),
    onSurfaceVariant = Color(0xFF5F6368)
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

    MaterialTheme(
        colorScheme = if (isDark) DarkColorPalette else LightColorPalette,
        content = content
    )
}
