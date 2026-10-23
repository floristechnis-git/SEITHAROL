package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.SyneFontFamily

// Clean, Minimal Dark Neutral & Warm Gold Palette (Zero Red)
private val MinimalBg = Color(0xFF0B0D11)
private val MinimalSurface = Color(0xFF141820)
private val MinimalBorder = Color(0xFF232936)
private val MinimalGold = Color(0xFFE5B869)
private val MinimalTeal = Color(0xFF4FD1C5)
private val MinimalTextPrimary = Color(0xFFF8FAFC)
private val MinimalTextSecondary = Color(0xFF94A3B8)
private val MinimalInk = Color(0xFF0B0D11)

/**
 * Clean, Minimal 2-Step Onboarding Screen (Zero Red, No Broken Grid Wrapping):
 * - Step 1: Centered app logo (`R.drawable.ic_splash_logo`) as the first impression, clean title &
 *   subtitle, three full-width minimal feature rows, and a bottom "Continue" CTA button.
 * - Step 2: Minimal profile setup (Name & Age) with 100% local on-device privacy assurance.
 */
@Composable
fun OnboardingScreen(
    onContinue: (name: String, age: String) -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Minimal Top Bar: Step Indicator & Skip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(if (currentStep == 1) 22.dp else 8.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 1) MinimalGold else MinimalBorder)
                )
                Box(
                    modifier = Modifier
                        .width(if (currentStep == 2) 22.dp else 8.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 2) MinimalGold else MinimalBorder)
                )
            }

            if (currentStep == 1) {
                Text(
                    text = "Skip",
                    color = MinimalTextSecondary,
                    fontSize = 13.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { currentStep = 2 }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else {
                Text(
                    text = "Step 2 of 2",
                    color = MinimalTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        AnimatedContent(
            targetState = currentStep,
            transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(180)) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "minimal_onboarding_step"
        ) { step ->
            if (step == 1) {
                // ────────────────────────────────────────────────────────────
                // STEP 1: Clean App Image First Impression + 3 Minimal Rows
                // ────────────────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Centered App Image & Title Block
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(96.dp)
                                .border(
                                    width = 1.dp,
                                    color = MinimalBorder,
                                    shape = RoundedCornerShape(24.dp)
                                ),
                            shape = RoundedCornerShape(24.dp),
                            color = MinimalSurface,
                            shadowElevation = 12.dp
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_splash_logo),
                                contentDescription = "Seitharol App Logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Seitharol",
                            color = MinimalTextPrimary,
                            fontSize = 28.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "High-resolution streaming, synchronized lyrics, and pure offline playback.",
                            color = MinimalTextSecondary,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 3 Clean Full-Width Minimal Feature Rows (never overflows or wraps awkwardly)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MinimalFeatureRow(
                            iconIndex = 0,
                            accent = MinimalGold,
                            title = "Studio-Grade Audio",
                            subtitle = "Pure 320kbps streaming with 5-band equalizer"
                        )
                        MinimalFeatureRow(
                            iconIndex = 1,
                            accent = MinimalTeal,
                            title = "Time-Synced Lyrics",
                            subtitle = "Interactive synchronized lyrics with tap-to-seek"
                        )
                        MinimalFeatureRow(
                            iconIndex = 2,
                            accent = MinimalGold,
                            title = "True Offline Mode",
                            subtitle = "Save tracks on-device for zero-data listening"
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { currentStep = 2 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MinimalGold,
                            contentColor = MinimalInk
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            text = "Continue",
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                // ────────────────────────────────────────────────────────────
                // STEP 2: Minimal Profile Setup (Zero Red, No Support Email)
                // ────────────────────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(48.dp)
                                    .border(1.dp, MinimalBorder, RoundedCornerShape(14.dp)),
                                shape = RoundedCornerShape(14.dp),
                                color = MinimalSurface
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_splash_logo),
                                    contentDescription = "Seitharol",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column {
                                Text(
                                    text = "Your Profile",
                                    color = MinimalTextPrimary,
                                    fontSize = 22.sp,
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Personalize your home screen greeting",
                                    color = MinimalTextSecondary,
                                    fontSize = 13.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "Name",
                            color = MinimalTextPrimary,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = {
                                Text(
                                    text = "Enter your name",
                                    color = MinimalTextSecondary.copy(alpha = 0.6f),
                                    fontFamily = OutfitFontFamily
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MinimalGold,
                                unfocusedBorderColor = MinimalBorder,
                                focusedContainerColor = MinimalSurface,
                                unfocusedContainerColor = MinimalSurface,
                                focusedTextColor = MinimalTextPrimary,
                                unfocusedTextColor = MinimalTextPrimary,
                                cursorColor = MinimalGold
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Age (Optional)",
                            color = MinimalTextPrimary,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        OutlinedTextField(
                            value = age,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() } && input.length <= 3) {
                                    age = input
                                }
                            },
                            placeholder = {
                                Text(
                                    text = "e.g., 21",
                                    color = MinimalTextSecondary.copy(alpha = 0.6f),
                                    fontFamily = OutfitFontFamily
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MinimalGold,
                                unfocusedBorderColor = MinimalBorder,
                                focusedContainerColor = MinimalSurface,
                                unfocusedContainerColor = MinimalSurface,
                                focusedTextColor = MinimalTextPrimary,
                                unfocusedTextColor = MinimalTextPrimary,
                                cursorColor = MinimalGold
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        // Compact Local Privacy Note
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MinimalBorder, RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            color = MinimalSurface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MinimalTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Saved locally on your device. No account required.",
                                    color = MinimalTextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { currentStep = 1 },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MinimalTextPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MinimalBorder),
                            modifier = Modifier
                                .weight(0.38f)
                                .height(52.dp)
                        ) {
                            Text(
                                text = "Back",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }

                        Button(
                            onClick = {
                                val finalName = name.trim().ifBlank { "Music Lover" }
                                val finalAge = age.trim().ifBlank { "18" }
                                onContinue(finalName, finalAge)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MinimalGold,
                                contentColor = MinimalInk
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(0.62f)
                                .height(52.dp)
                        ) {
                            Text(
                                text = "Start Listening",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalFeatureRow(
    iconIndex: Int,
    accent: Color,
    title: String,
    subtitle: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MinimalBorder, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = MinimalSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                MinimalFeatureSvgIcon(
                    index = iconIndex,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MinimalTextPrimary,
                    fontSize = 14.sp,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = MinimalTextSecondary,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MinimalFeatureSvgIcon(
    index: Int,
    tint: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.10f, cap = StrokeCap.Round)
        when (index) {
            0 -> {
                val bars = listOf(0.35f, 0.70f, 0.95f, 0.60f, 0.40f)
                bars.forEachIndexed { idx, ratio ->
                    val x = w * (0.18f + idx * 0.16f)
                    val barH = h * ratio
                    val topY = (h - barH) / 2f
                    drawLine(
                        color = tint,
                        start = Offset(x, topY),
                        end = Offset(x, topY + barH),
                        strokeWidth = w * 0.10f,
                        cap = StrokeCap.Round
                    )
                }
            }
            1 -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.16f),
                    size = Size(w * 0.76f, h * 0.68f),
                    cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
                    style = stroke
                )
                drawLine(
                    color = tint,
                    start = Offset(w * 0.28f, h * 0.40f),
                    end = Offset(w * 0.72f, h * 0.40f),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = tint,
                    start = Offset(w * 0.28f, h * 0.60f),
                    end = Offset(w * 0.56f, h * 0.60f),
                    strokeWidth = w * 0.09f,
                    cap = StrokeCap.Round
                )
            }
            else -> {
                drawLine(
                    color = tint,
                    start = Offset(w * 0.50f, h * 0.16f),
                    end = Offset(w * 0.50f, h * 0.66f),
                    strokeWidth = w * 0.10f,
                    cap = StrokeCap.Round
                )
                val arrow = Path().apply {
                    moveTo(w * 0.30f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.68f)
                    lineTo(w * 0.70f, h * 0.48f)
                }
                drawPath(arrow, color = tint, style = stroke)
                drawLine(
                    color = tint,
                    start = Offset(w * 0.22f, h * 0.84f),
                    end = Offset(w * 0.78f, h * 0.84f),
                    strokeWidth = w * 0.10f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
