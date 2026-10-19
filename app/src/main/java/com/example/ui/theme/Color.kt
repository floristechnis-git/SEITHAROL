package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Seitharol Pure White & Adaptive Theme Tokens
val SeitharolBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF0F0F0F) else Color(0xFFF8F9FA)

val SeitharolWhite: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF1B1C1C) else Color(0xFFFFFFFF)

// Primary foreground / text / icon color
val SeitharolDark: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFFFFFFF) else Color(0xFF121212)

val SeitharolDarkCard: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF1B1C1C) else Color(0xFFFFFFFF)

val SeitharolDarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF262727) else Color(0xFFF0F2F5)

// Dedicated Navigation Bar Tokens
val SeitharolNavBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF1B1C1C) else Color(0xFFFFFFFF)

val SeitharolNavActive: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF38383A) else Color(0xFFE2E4E8)

val SeitharolMiniPlayerBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF1B1C1C) else Color(0xFFFFFFFF)

val SeitharolPillBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFFFFFFF) else Color(0xFF121212)

val SeitharolPillText: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF000000) else Color(0xFFFFFFFF)

val SeitharolCardElevated: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF262727) else Color(0xFFECEEF0)

val SeitharolDarkButton: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF2E2E30) else Color(0xFFE2E4E8)

val SeitharolTextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFFFFFFF) else Color(0xFF121212)

val SeitharolTextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFA0A0A0) else Color(0xFF5F6368)

val SeitharolTextTertiary: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF757575) else Color(0xFF888888)

val SeitharolTextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF555555) else Color(0xFFAAAAAA)

val SeitharolTextLight: Color = Color(0xFFFFFFFF)
val SeitharolTextLightMuted: Color = Color(0xFFD0D0D0)

val SeitharolBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF2C2C2E) else Color(0xFFE0E0E0)

val SeitharolDivider: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF262727) else Color(0xFFE8E8E8)

val SeitharolGlassDark: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0x33FFFFFF) else Color(0x33000000)

val SeitharolGlassDarkActive: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0x4DFFFFFF) else Color(0x4D000000)

val SeitharolModalScrim: Color = Color(0x8C000000)

val SeitharolHandleBar: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF555555) else Color(0xFFCCCCCC)

// Accent highlight
val SeitharolAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFFFFFFF) else Color(0xFF121212)

val SeitharolRedHeart: Color = Color(0xFFFF3B56)
val SeitharolGreen: Color = Color(0xFF00E676)
