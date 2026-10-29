package com.seitharol.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.ui.theme.LocalIsDarkTheme
import com.seitharol.data.preferences.ThemeMode

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFFB71C1C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A0E0E),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF2E3032),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF262727),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFC62828),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF3B1010),
    onTertiaryContainer = Color(0xFFFFDAD6),
    error = Color(0xFFB71C1C),
    onError = Color.White,
    errorContainer = Color(0xFF4A0E0E),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF5F5F5),
    surface = Color(0xFF1B1C1C),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF262727),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceTint = Color.Transparent,
    inverseSurface = Color.White,
    inverseOnSurface = Color(0xFF121212),
    outline = Color(0xFF3A3B3C),
    outlineVariant = Color(0xFF28292A),
    surfaceBright = Color(0xFF2C2D2E),
    surfaceDim = Color(0xFF0F0F0F),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF161717),
    surfaceContainer = Color(0xFF1B1C1C),
    surfaceContainerHigh = Color(0xFF242526),
    surfaceContainerHighest = Color(0xFF2E3031)
)

private val LightColorPalette = lightColorScheme(
    primary = Color(0xFF9B0000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFFE4E7EB),
    onSecondary = Color(0xFF111315),
    secondaryContainer = Color(0xFFE8EAED),
    onSecondaryContainer = Color(0xFF111315),
    tertiary = Color(0xFF8E0000),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD6),
    onTertiaryContainer = Color(0xFF410002),
    error = Color(0xFF9B0000),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF111315),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111315),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF3C4043),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF1B1C1C),
    inverseOnSurface = Color.White,
    outline = Color(0xFFC4C7C5),
    outlineVariant = Color(0xFFDADCE0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDADCE0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F7F9),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFE8EAED),
    surfaceContainerHighest = Color(0xFFE0E3E7)
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
    val colorScheme = if (isDark) DarkColorPalette else LightColorPalette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.isForceDarkAllowed = false
            }
            val window = (view.context as? Activity)?.window
            if (window != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.decorView.isForceDarkAllowed = false
                }
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides isDark,
        LocalContentColor provides colorScheme.onBackground
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
