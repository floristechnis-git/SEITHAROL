package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Semantic Theme Tokens (Theme-Adaptive & Contrast Guaranteed across Light & Dark Modes)
val SeitharolBg: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val SeitharolWhite: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

// Primary foreground / text / icon color (Always contrasts with SeitharolWhite / surface)
val SeitharolDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface

// Inverse foreground color when SeitharolDark is used as a filled container background
val SeitharolOnDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val SeitharolDarkCard: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val SeitharolDarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

// Dedicated Navigation Bar Tokens
val SeitharolNavBg: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val SeitharolNavActive: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer

val SeitharolMiniPlayerBg: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

val SeitharolPillBg: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val SeitharolPillText: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onPrimary

val SeitharolCardElevated: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

val SeitharolDarkButton: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

val SeitharolTextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurface

val SeitharolTextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val SeitharolTextTertiary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)

val SeitharolTextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)

val SeitharolTextLight: Color = Color(0xFFFFFFFF)
val SeitharolTextLightMuted: Color = Color(0xFFE0E0E0)

val SeitharolBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outline

val SeitharolDivider: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outlineVariant

val SeitharolGlassDark: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0x33FFFFFF) else Color(0x14000000)

val SeitharolGlassDarkActive: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0x4DFFFFFF) else Color(0x26000000)

val SeitharolModalScrim: Color = Color(0x8C000000)

val SeitharolHandleBar: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

// Accent highlight
val SeitharolAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val SeitharolRedHeart: Color = Color(0xFFB71C1C)
val SeitharolGreen: Color = Color(0xFF00C853)
