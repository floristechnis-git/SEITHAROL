package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalIsDarkTheme = compositionLocalOf { true }

val PureWhite = Color(0xFFFFFFFF)
val DarkBackground = Color(0xFF0F0F0F)
val DarkSurface = Color(0xFF1B1C1C)
val DarkSurfaceVariant = Color(0xFF262727)
val MutedText = Color(0xFFB3B3B3)
val SeitharolBrandRed = Color(0xFFFF4E45)
val SeitharolLightRed = Color(0xFFD93025)

private val SeitharolDarkColorScheme = darkColorScheme(
    primary = SeitharolBrandRed,
    onPrimary = PureWhite,
    primaryContainer = Color(0xFF3A1D1B),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF2E3032),
    onSecondary = PureWhite,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = PureWhite,
    tertiary = Color(0xFFFF6E66),
    onTertiary = PureWhite,
    tertiaryContainer = Color(0xFF2D2322),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = DarkBackground,
    onBackground = PureWhite,
    surface = DarkSurface,
    onSurface = PureWhite,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = MutedText,
    surfaceTint = Color.Transparent,
    inverseSurface = PureWhite,
    inverseOnSurface = Color(0xFF121212),
    outline = Color(0xFF3A3B3C),
    outlineVariant = Color(0xFF28292A)
)

private val SeitharolLightColorScheme = lightColorScheme(
    primary = SeitharolLightRed,
    onPrimary = PureWhite,
    primaryContainer = Color(0xFFFFE5E3),
    onPrimaryContainer = Color(0xFF8C1D18),
    secondary = Color(0xFFE4E7EB),
    onSecondary = Color(0xFF111315),
    secondaryContainer = Color(0xFFE8EAED),
    onSecondaryContainer = Color(0xFF111315),
    tertiary = Color(0xFF37474F),
    onTertiary = PureWhite,
    tertiaryContainer = Color(0xFFE3F2FD),
    onTertiaryContainer = Color(0xFF0D3B66),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF111315),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111315),
    surfaceVariant = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF3C4043),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF1B1C1C),
    inverseOnSurface = PureWhite,
    outline = Color(0xFFC4C7C5),
    outlineVariant = Color(0xFFDADCE0)
)

@Composable
fun CascadeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) SeitharolDarkColorScheme else SeitharolLightColorScheme,
            typography = Typography,
            content = content
        )
    }
}
