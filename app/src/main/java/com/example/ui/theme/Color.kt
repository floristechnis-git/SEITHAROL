package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Kanglei Tune Dynamic Design Tokens adapting seamlessly to Light and Dark Mode
val KangleiBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF101010) else Color(0xFFF2F2F2)

val KangleiWhite: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

// Primary foreground / text / icon color (Dark in light theme, Light in dark theme)
val KangleiDark: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFF5F5F5) else Color(0xFF0D0D0D)

val KangleiDarkCard: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF242426) else Color(0xFF161616)

val KangleiDarkSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF28282A) else Color(0xFF222222)

// Dedicated Navigation Bar Tokens
val KangleiNavBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF1C1C1E) else Color(0xFF0D0D0D)

val KangleiNavActive: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF38383A) else Color(0xFF2B2B2B)

val KangleiMiniPlayerBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF1E1E20) else Color(0xFF141414)

val KangleiPillBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFF5F5F5) else Color(0xFF0D0D0D)

val KangleiPillText: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF0D0D0D) else Color(0xFFFFFFFF)

val KangleiCardElevated: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF28282B) else Color(0xFFF5F5F5)

val KangleiDarkButton: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF2E2E30) else Color(0xFF333333)

val KangleiTextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFF5F5F5) else Color(0xFF0D0D0D)

val KangleiTextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFA5A5A5) else Color(0xFF5C5C5C)

val KangleiTextTertiary: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF757575) else Color(0xFF8A8A8A)

val KangleiTextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF555555) else Color(0xFFBDBDBD)

val KangleiTextLight: Color = Color(0xFFFFFFFF)
val KangleiTextLightMuted: Color = Color(0xFFD0D0D0)

val KangleiBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF2C2C2E) else Color(0xFFE5E5E5)

val KangleiDivider: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF242426) else Color(0xFFEEEEEE)

val KangleiGlassDark: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0x33FFFFFF) else Color(0x2EFFFFFF)

val KangleiGlassDarkActive: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0x4DFFFFFF) else Color(0x33FFFFFF)

val KangleiModalScrim: Color = Color(0x8C000000)

val KangleiHandleBar: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFF555555) else Color(0xFFC4C4C4)

// Accent highlights
val KangleiAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalIsDarkTheme.current) Color(0xFFFFFFFF) else Color(0xFF0D0D0D)

val KangleiRedHeart: Color = Color(0xFFFF3B56)
val KangleiGreen: Color = Color(0xFF00E676)
