package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.SyneFontFamily

/**
 * Editorial, Theme-Matched 2-Step Onboarding Screen inspired by Claude & ChatGPT mobile apps:
 * - Matches the active `CascadeTheme` (`MaterialTheme.colorScheme.background`, `surfaceVariant`,
 *   `onBackground`, `onSurfaceVariant`) with high-contrast monochrome CTAs (zero red).
 * - Zero truncated `...` (`TextOverflow.Ellipsis`) anywhere — all headings and descriptions wrap
 *   naturally with generous line height.
 * - Step 1 presents the app image (`ic_splash_logo`) as a clean visual first impression followed by
 *   editorial typography and three full-wrap capability notes.
 * - Step 2 asks for the listener's name and age with clean, minimal inputs.
 */
@Composable
fun OnboardingScreen(
    onContinue: (name: String, age: String) -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }

    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val primaryText = MaterialTheme.colorScheme.onBackground
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        // Minimal Top Progress Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(if (currentStep == 1) 24.dp else 8.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 1) primaryText else borderColor)
                )
                Box(
                    modifier = Modifier
                        .width(if (currentStep == 2) 24.dp else 8.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 2) primaryText else borderColor)
                )
            }

            if (currentStep == 1) {
                Text(
                    text = "Skip",
                    color = secondaryText,
                    fontSize = 14.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { currentStep = 2 }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        AnimatedContent(
            targetState = currentStep,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "editorial_onboarding"
        ) { step ->
            if (step == 1) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(28.dp))

                        // App Image for First Impression
                        Surface(
                            modifier = Modifier
                                .size(84.dp)
                                .border(1.dp, borderColor, RoundedCornerShape(22.dp)),
                            shape = RoundedCornerShape(22.dp),
                            color = surfaceColor
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_splash_logo),
                                contentDescription = "Seitharol",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = "Welcome to Seitharol",
                            color = primaryText,
                            fontSize = 30.sp,
                            lineHeight = 36.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "An uncompromising music player crafted for high-resolution streaming, time-synced lyrics, and offline listening.",
                            color = secondaryText,
                            fontSize = 15.sp,
                            lineHeight = 23.sp,
                            fontFamily = OutfitFontFamily
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(22.dp)
                        ) {
                            EditorialPoint(
                                title = "Pure Studio Audio",
                                body = "Stream high-bitrate tracks with a customizable 5-band equalizer.",
                                primaryText = primaryText,
                                secondaryText = secondaryText,
                                borderColor = borderColor
                            )
                            EditorialPoint(
                                title = "Synchronized Lyrics",
                                body = "Read along in real time and tap any line to jump directly to that moment.",
                                primaryText = primaryText,
                                secondaryText = secondaryText,
                                borderColor = borderColor
                            )
                            EditorialPoint(
                                title = "Offline Library",
                                body = "Save songs directly to your device for uninterrupted playback without internet.",
                                primaryText = primaryText,
                                secondaryText = secondaryText,
                                borderColor = borderColor
                            )
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = { currentStep = 2 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryText,
                                contentColor = bgColor
                            ),
                            shape = RoundedCornerShape(27.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                        ) {
                            Text(
                                text = "Continue",
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            text = "How should we greet you?",
                            color = primaryText,
                            fontSize = 28.sp,
                            lineHeight = 34.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Your name personalizes your home screen. Your profile is stored locally on your device.",
                            color = secondaryText,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontFamily = OutfitFontFamily
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Text(
                            text = "Your name",
                            color = primaryText,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = {
                                Text(
                                    text = "Enter your name",
                                    color = secondaryText.copy(alpha = 0.6f),
                                    fontFamily = OutfitFontFamily
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryText,
                                unfocusedBorderColor = borderColor,
                                focusedContainerColor = surfaceColor,
                                unfocusedContainerColor = surfaceColor,
                                focusedTextColor = primaryText,
                                unfocusedTextColor = primaryText,
                                cursorColor = primaryText
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Age",
                            color = primaryText,
                            fontSize = 14.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 8.dp)
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
                                    text = "Enter your age",
                                    color = secondaryText.copy(alpha = 0.6f),
                                    fontFamily = OutfitFontFamily
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryText,
                                unfocusedBorderColor = borderColor,
                                focusedContainerColor = surfaceColor,
                                unfocusedContainerColor = surfaceColor,
                                focusedTextColor = primaryText,
                                unfocusedTextColor = primaryText,
                                cursorColor = primaryText
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(28.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { currentStep = 1 },
                                shape = RoundedCornerShape(27.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryText),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .weight(0.38f)
                                    .height(54.dp)
                            ) {
                                Text(
                                    text = "Back",
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp
                                )
                            }

                            Button(
                                onClick = {
                                    val finalName = name.trim().ifBlank { "Listener" }
                                    val finalAge = age.trim().ifBlank { "18" }
                                    onContinue(finalName, finalAge)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryText,
                                    contentColor = bgColor
                                ),
                                shape = RoundedCornerShape(27.dp),
                                modifier = Modifier
                                    .weight(0.62f)
                                    .height(54.dp)
                            ) {
                                Text(
                                    text = "Continue",
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
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
private fun EditorialPoint(
    title: String,
    body: String,
    primaryText: androidx.compose.ui.graphics.Color,
    secondaryText: androidx.compose.ui.graphics.Color,
    borderColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(primaryText)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = primaryText,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = body,
                color = secondaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = OutfitFontFamily
            )
        }
    }
}
