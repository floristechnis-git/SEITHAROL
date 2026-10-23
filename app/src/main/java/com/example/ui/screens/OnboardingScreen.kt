package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.SyneFontFamily

/**
 * Flat, full-screen 2-step Onboarding Experience (Zero popup/modal dialogs):
 * - Step 1 of 2 (Sign Up): Flat full-screen profile setup (Name & Age) with explicit local-only
 *   privacy reassurance ("This information is not shared with any third party...").
 * - Step 2 of 2 (Welcome): Flat full-screen welcome & open-source overview before entering Seitharol.
 */
@Composable
fun OnboardingScreen(
    onContinue: (name: String, age: String) -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var expandedMore by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(targetValue = if (expandedMore) 180f else 0f, label = "triangleRotation")
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Top Step Indicator Header ("Step 1 out of 2 • Sign Up" / "Step 2 out of 2 • Welcome")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (currentStep == 1) "STEP 1 OF 2 • SIGN UP" else "STEP 2 OF 2 • GET STARTED",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Text(
                text = "$currentStep / 2",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Flat 2-Step Progress Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (currentStep >= 2) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        if (currentStep == 1) {
            // STEP 1 OF 2: Flat Sign Up Screen
            Text(
                text = "Create your profile",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Personalize your greeting, recommendations, and regional music experience on Seitharol.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontFamily = OutfitFontFamily,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your Name", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                placeholder = { Text("Enter your name") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = age,
                onValueChange = { if (it.all { ch -> ch.isDigit() } && it.length <= 3) age = it },
                label = { Text("Your Age", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                placeholder = { Text("Enter your age") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Flat Privacy Notice Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = "Privacy",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "This information is not shared with any third party or external server. It stays 100% locally on your device only to personalize your greetings and music experience.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    if (name.isNotBlank() && age.isNotBlank()) {
                        currentStep = 2
                    }
                },
                enabled = name.isNotBlank() && age.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "Continue to Step 2",
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        } else {
            // STEP 2 OF 2: Flat Full-Screen Welcome & Architecture Overview (No Dialog Popup)
            Text(
                text = "Welcome, ${name.trim()}",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 28.sp,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Seitharol is an open-source music streaming experience crafted for studio-quality playback, synchronized lyrics, and regional discovery.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp,
                fontFamily = OutfitFontFamily,
                lineHeight = 22.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            val highlights = listOf(
                "Studio-Grade Streaming" to "Direct high-bitrate AAC & Opus audio streams with automatic fallback.",
                "Offline Downloads" to "Save songs directly to your device for zero-data offline listening.",
                "Time-Synced Lyrics" to "Smooth center-aligned real-time lyrics with instant tap-to-seek.",
                "Manipur Cultural Calendar" to "Built-in solar and Meitei lunar festival notifications."
            )

            highlights.forEach { (title, desc) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = desc,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .clickable { expandedMore = !expandedMore }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlusSvgIcon(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Developer & Collaboration Info",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                InvertedTriangleSvgIcon(
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(rotation)
                )
            }

            AnimatedVisibility(visible = expandedMore) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:floristechnis@proton.me")
                                }
                                context.startActivity(intent)
                            }
                        }
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Have feedback or collaboration inquiries? Reach out anytime at floristechnis@proton.me",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(28.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { currentStep = 1 },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(0.38f)
                        .height(52.dp)
                ) {
                    Text(
                        text = "Back",
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Button(
                    onClick = { onContinue(name.trim(), age.trim()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
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

@Composable
fun PlusSvgIcon(
    tint: Color,
    modifier: Modifier = Modifier.size(16.dp)
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.width * 0.14f, cap = StrokeCap.Round)
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.18f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.82f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.5f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.5f),
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
