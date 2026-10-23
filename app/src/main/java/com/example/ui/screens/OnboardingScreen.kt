package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.SyneFontFamily
import kotlin.math.cos
import kotlin.math.sin

// Bespoke Non-Red Luxury Onboarding Palette (Obsidian, Champagne Gold, Luminous Teal & Pearl)
private val OnboardDeepBg = Color(0xFF0A0C10)
private val OnboardCardSurface = Color(0xFF131720)
private val OnboardCardElevated = Color(0xFF191E2B)
private val OnboardBorderSubtle = Color(0xFF262D3D)
private val OnboardChampagneGold = Color(0xFFE5B869)
private val OnboardSoftGold = Color(0xFFF3D292)
private val OnboardLuminousTeal = Color(0xFF4FD1C5)
private val OnboardPearlWhite = Color(0xFFF8FAFC)
private val OnboardSlateMuted = Color(0xFF94A3B8)
private val OnboardDeepInk = Color(0xFF0B0D12)

/**
 * Flagship 2-Step Aesthetic & Professional Onboarding Experience (Zero Red):
 * - Step 1 of 2 (First Impression Intro Showcase): Showcases the visual identity of Seitharol with
 *   an animated acoustic halo framing the app emblem, expressive typography, and a 2x2 Bento grid
 *   highlighting Studio Master Streaming, Synced Lyrics, Offline Vault, and Cultural Curation.
 * - Step 2 of 2 (Personalize Your Experience): Glassmorphic profile card with live monogram avatar
 *   preview, Name & Age setup, and 100% local on-device privacy assurance.
 */
@Composable
fun OnboardingScreen(
    onContinue: (name: String, age: String) -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }

    val infiniteTransition = rememberInfiniteTransition(label = "onboard_ambient")
    val haloPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "halo_phase"
    )
    val logoBreath by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_breath"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OnboardDeepBg)
    ) {
        // Ambient background radial mesh glow (Champagne Gold & Luminous Teal — Zero Red)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        OnboardChampagneGold.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.82f, size.height * 0.14f),
                    radius = size.minDimension * 0.72f
                ),
                center = Offset(size.width * 0.82f, size.height * 0.14f),
                radius = size.minDimension * 0.72f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        OnboardLuminousTeal.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.18f, size.height * 0.42f),
                    radius = size.minDimension * 0.68f
                ),
                center = Offset(size.width * 0.18f, size.height * 0.42f),
                radius = size.minDimension * 0.68f
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Top Step Indicator Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = OnboardCardElevated,
                    modifier = Modifier.border(
                        width = 1.dp,
                        color = OnboardChampagneGold.copy(alpha = 0.30f),
                        shape = RoundedCornerShape(20.dp)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(OnboardChampagneGold)
                        )
                        Text(
                            text = if (currentStep == 1) "FIRST IMPRESSION • INTRO" else "PERSONALIZE • PROFILE",
                            color = OnboardSoftGold,
                            fontSize = 11.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }
                }

                Text(
                    text = "0$currentStep / 02",
                    color = OnboardSlateMuted,
                    fontSize = 12.sp,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dual-Step Champagne Gold & Teal Progress Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(OnboardChampagneGold, OnboardLuminousTeal)
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (currentStep >= 2) {
                                Brush.horizontalGradient(
                                    listOf(OnboardLuminousTeal, OnboardChampagneGold)
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(OnboardBorderSubtle, OnboardBorderSubtle)
                                )
                            }
                        )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) },
                label = "onboarding_step_content"
            ) { step ->
                if (step == 1) {
                    // ────────────────────────────────────────────────────────────
                    // STEP 1 OF 2: Visual First Impression Showcase of Seitharol
                    // ────────────────────────────────────────────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Hero App Emblem Stage with Animated Acoustic Soundstage Halo
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(216.dp)) {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val baseRadius = size.minDimension * 0.34f

                                // Outer orbital rings
                                drawCircle(
                                    color = OnboardChampagneGold.copy(alpha = 0.14f),
                                    radius = baseRadius * 1.34f,
                                    center = center,
                                    style = Stroke(width = 1.2.dp.toPx())
                                )
                                drawCircle(
                                    color = OnboardLuminousTeal.copy(alpha = 0.18f),
                                    radius = baseRadius * 1.15f,
                                    center = center,
                                    style = Stroke(width = 1.5.dp.toPx())
                                )

                                // Dynamic radial acoustic bars around emblem
                                val barCount = 36
                                for (i in 0 until barCount) {
                                    val angle = (i.toFloat() / barCount) * (2f * Math.PI).toFloat()
                                    val wave = (sin(angle * 3f + haloPhase) + 1f) * 0.5f
                                    val innerR = baseRadius * 1.06f
                                    val outerR = innerR + (6.dp.toPx() + wave * 12.dp.toPx())
                                    val start = Offset(
                                        x = center.x + cos(angle) * innerR,
                                        y = center.y + sin(angle) * innerR
                                    )
                                    val end = Offset(
                                        x = center.x + cos(angle) * outerR,
                                        y = center.y + sin(angle) * outerR
                                    )
                                    val barColor = if (i % 2 == 0) {
                                        OnboardChampagneGold.copy(alpha = 0.45f + wave * 0.45f)
                                    } else {
                                        OnboardLuminousTeal.copy(alpha = 0.38f + wave * 0.45f)
                                    }
                                    drawLine(
                                        color = barColor,
                                        start = start,
                                        end = end,
                                        strokeWidth = 2.4.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                }
                            }

                            // Center App Image / Emblem Card
                            Surface(
                                modifier = Modifier
                                    .size(116.dp)
                                    .scale(logoBreath)
                                    .border(
                                        width = 1.8.dp,
                                        brush = Brush.linearGradient(
                                            listOf(OnboardChampagneGold, OnboardLuminousTeal)
                                        ),
                                        shape = RoundedCornerShape(28.dp)
                                    ),
                                shape = RoundedCornerShape(28.dp),
                                color = OnboardCardSurface,
                                shadowElevation = 18.dp
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_splash_logo),
                                    contentDescription = "Seitharol Emblem",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "SEITHAROL",
                            color = OnboardPearlWhite,
                            fontSize = 30.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 3.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Where Studio-Grade Sound Meets Regional & Global Discovery",
                            color = OnboardSoftGold,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Crafted for pure high-bitrate audio streaming, real-time synchronized lyrics, and zero-data offline playback.",
                            color = OnboardSlateMuted,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 2x2 Bento Showcase Grid
                        val bentoFeatures = listOf(
                            Triple(
                                "Studio Master Audio",
                                "Direct 320kbps AAC & Opus streams with 5-band studio equalizer.",
                                OnboardChampagneGold
                            ),
                            Triple(
                                "Live Synced Lyrics",
                                "Time-synced interactive lyrics with instant tap-to-seek.",
                                OnboardLuminousTeal
                            ),
                            Triple(
                                "Offline Opus Vault",
                                "Save songs on-device for pure zero-data Airplane Mode listening.",
                                OnboardLuminousTeal
                            ),
                            Triple(
                                "Cultural Curation",
                                "Personalized Manipuri, regional & global music discovery.",
                                OnboardChampagneGold
                            )
                        )

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            bentoFeatures.chunked(2).forEachIndexed { rowIdx, rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowItems.forEachIndexed { colIdx, (title, desc, accentColor) ->
                                        val iconIndex = rowIdx * 2 + colIdx
                                        Surface(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(132.dp)
                                                .border(
                                                    width = 1.dp,
                                                    color = OnboardBorderSubtle,
                                                    shape = RoundedCornerShape(18.dp)
                                                ),
                                            shape = RoundedCornerShape(18.dp),
                                            color = OnboardCardSurface
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(14.dp),
                                                verticalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(accentColor.copy(alpha = 0.15f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    OnboardingBentoSvgIcon(
                                                        index = iconIndex,
                                                        tint = accentColor,
                                                        modifier = Modifier.size(17.dp)
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        text = title,
                                                        color = OnboardPearlWhite,
                                                        fontSize = 14.sp,
                                                        fontFamily = SyneFontFamily,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1
                                                    )
                                                    Spacer(modifier = Modifier.height(3.dp))
                                                    Text(
                                                        text = desc,
                                                        color = OnboardSlateMuted,
                                                        fontSize = 11.sp,
                                                        fontFamily = OutfitFontFamily,
                                                        lineHeight = 15.sp,
                                                        maxLines = 3
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Button(
                            onClick = { currentStep = 2 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OnboardChampagneGold,
                                contentColor = OnboardDeepInk
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Text(
                                text = "Get Started",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                } else {
                    // ────────────────────────────────────────────────────────────
                    // STEP 2 OF 2: Personalize Your Profile (Zero Red, No Support Email)
                    // ────────────────────────────────────────────────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Live Profile Preview Banner
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    brush = Brush.horizontalGradient(
                                        listOf(
                                            OnboardChampagneGold.copy(alpha = 0.45f),
                                            OnboardLuminousTeal.copy(alpha = 0.35f)
                                        )
                                    ),
                                    shape = RoundedCornerShape(22.dp)
                                ),
                            shape = RoundedCornerShape(22.dp),
                            color = OnboardCardSurface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(OnboardChampagneGold, OnboardLuminousTeal)
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "S"
                                    Text(
                                        text = initial,
                                        color = OnboardDeepInk,
                                        fontSize = 24.sp,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (name.isNotBlank()) "Welcome, ${name.trim()}" else "Create Your Listener Profile",
                                        color = OnboardPearlWhite,
                                        fontSize = 18.sp,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (age.isNotBlank()) "Age $age • Tailored Acoustic Feed" else "Personalize your daily greetings & smart mixes",
                                        color = OnboardSoftGold,
                                        fontSize = 12.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Tell us about yourself",
                            color = OnboardPearlWhite,
                            fontSize = 24.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.ExtraBold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Your name and age tailor your time-of-day home greetings and regional music shelves.",
                            color = OnboardSlateMuted,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Your Name", color = OnboardSlateMuted) },
                            placeholder = { Text("Enter your name", color = OnboardSlateMuted.copy(alpha = 0.6f)) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OnboardChampagneGold,
                                unfocusedBorderColor = OnboardBorderSubtle,
                                focusedTextColor = OnboardPearlWhite,
                                unfocusedTextColor = OnboardPearlWhite,
                                cursorColor = OnboardChampagneGold,
                                focusedContainerColor = OnboardCardSurface,
                                unfocusedContainerColor = OnboardCardSurface
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = age,
                            onValueChange = { if (it.all { ch -> ch.isDigit() } && it.length <= 3) age = it },
                            label = { Text("Your Age", color = OnboardSlateMuted) },
                            placeholder = { Text("Enter your age", color = OnboardSlateMuted.copy(alpha = 0.6f)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OnboardChampagneGold,
                                unfocusedBorderColor = OnboardBorderSubtle,
                                focusedTextColor = OnboardPearlWhite,
                                unfocusedTextColor = OnboardPearlWhite,
                                cursorColor = OnboardChampagneGold,
                                focusedContainerColor = OnboardCardSurface,
                                unfocusedContainerColor = OnboardCardSurface
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // On-Device Privacy Shield Card
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = OnboardLuminousTeal.copy(alpha = 0.28f),
                                    shape = RoundedCornerShape(14.dp)
                                ),
                            shape = RoundedCornerShape(14.dp),
                            color = OnboardCardElevated
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = "Privacy",
                                    tint = OnboardLuminousTeal,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "100% On-Device Privacy Guarantee",
                                        color = OnboardPearlWhite,
                                        fontSize = 13.sp,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "This information is never uploaded to any external server or shared with third parties. It stays locally on your device solely to personalize your music experience.",
                                        color = OnboardSlateMuted,
                                        fontSize = 12.sp,
                                        fontFamily = OutfitFontFamily,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { currentStep = 1 },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = OnboardPearlWhite
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, OnboardBorderSubtle),
                                modifier = Modifier
                                    .weight(0.36f)
                                    .height(54.dp)
                            ) {
                                Text(
                                    text = "Back",
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color = OnboardPearlWhite
                                )
                            }

                            val canContinue = name.isNotBlank() && age.isNotBlank()
                            Button(
                                onClick = {
                                    if (canContinue) {
                                        onContinue(name.trim(), age.trim())
                                    }
                                },
                                enabled = canContinue,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OnboardChampagneGold,
                                    contentColor = OnboardDeepInk,
                                    disabledContainerColor = OnboardChampagneGold.copy(alpha = 0.28f),
                                    disabledContentColor = OnboardPearlWhite.copy(alpha = 0.45f)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(0.64f)
                                    .height(54.dp)
                            ) {
                                Text(
                                    text = "Start Listening",
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingBentoSvgIcon(
    index: Int,
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    Canvas(modifier = modifier) {
        val sx = size.width / 24f
        val sy = size.height / 24f
        val stroke = Stroke(width = 2.0f * sx, cap = StrokeCap.Round)
        when (index) {
            0 -> {
                // Equalizer / Studio Waveform SVG
                drawLine(tint, Offset(4f * sx, 15f * sy), Offset(4f * sx, 9f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(9f * sx, 19f * sy), Offset(9f * sx, 5f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(14f * sx, 16f * sy), Offset(14f * sx, 8f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(19f * sx, 13f * sy), Offset(19f * sx, 11f * sy), stroke.width, StrokeCap.Round)
            }
            1 -> {
                // Lyrics Alignment Lines SVG
                drawLine(tint, Offset(4f * sx, 7f * sy), Offset(20f * sx, 7f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(4f * sx, 12f * sy), Offset(16f * sx, 12f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(4f * sx, 17f * sy), Offset(12f * sx, 17f * sy), stroke.width, StrokeCap.Round)
            }
            2 -> {
                // Offline Shield / Download Vault SVG
                drawLine(tint, Offset(12f * sx, 4f * sy), Offset(12f * sx, 15f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(8f * sx, 11f * sy), Offset(12f * sx, 15f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(16f * sx, 11f * sy), Offset(12f * sx, 15f * sy), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(5f * sx, 19f * sy), Offset(19f * sx, 19f * sy), stroke.width, StrokeCap.Round)
            }
            else -> {
                // Sparkle / Cultural Star SVG
                val path = Path().apply {
                    moveTo(12f * sx, 3f * sy)
                    lineTo(14.5f * sx, 9.5f * sy)
                    lineTo(21f * sx, 12f * sy)
                    lineTo(14.5f * sx, 14.5f * sy)
                    lineTo(12f * sx, 21f * sy)
                    lineTo(9.5f * sx, 14.5f * sy)
                    lineTo(3f * sx, 12f * sy)
                    lineTo(9.5f * sx, 9.5f * sy)
                    close()
                }
                drawPath(path, color = tint, style = stroke)
            }
        }
    }
}

@Composable
fun PlusSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(16.dp)
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.width * 0.14f, cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, size.height * 0.18f),
            end = Offset(size.width * 0.5f, size.height * 0.82f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.18f, size.height * 0.5f),
            end = Offset(size.width * 0.82f, size.height * 0.5f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun InvertedTriangleSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(14.dp)
) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.25f)
            lineTo(size.width * 0.85f, size.height * 0.25f)
            lineTo(size.width * 0.50f, size.height * 0.80f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
    }
}
