package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.SyneFontFamily
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Refined 2-Step Studio Onboarding Screen:
 * - Clean tonal cards, balanced typography, and responsive layout that fits mobile & tablet viewports.
 * - Single-line DOB selector ("Select Date of Birth") with native DatePickerDialog.
 * - "Get Started" button has no overlay — simply disabled and desaturated until inputs are complete.
 */
@Composable
fun OnboardingScreen(
    onContinue: (name: String, birthday: String, age: String) -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableIntStateOf(1) }
    var name by remember { mutableStateOf("") }
    var birthdateIso by remember { mutableStateOf("") }
    var birthdateDisplay by remember { mutableStateOf("") }
    var calculatedAge by remember { mutableStateOf("") }

    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val primaryText = MaterialTheme.colorScheme.onBackground
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // Top Step Bar & Skip Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val step1Width by animateDpAsState(
                    targetValue = if (currentStep == 1) 28.dp else 10.dp,
                    animationSpec = tween(220),
                    label = "step1_w"
                )
                val step2Width by animateDpAsState(
                    targetValue = if (currentStep == 2) 28.dp else 10.dp,
                    animationSpec = tween(220),
                    label = "step2_w"
                )
                Box(
                    modifier = Modifier
                        .width(step1Width)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 1) primaryColor else borderColor.copy(alpha = 0.6f))
                )
                Box(
                    modifier = Modifier
                        .width(step2Width)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 2) primaryColor else borderColor.copy(alpha = 0.6f))
                )
            }

            if (currentStep == 1) {
                Surface(
                    onClick = { currentStep = 2 },
                    shape = RoundedCornerShape(12.dp),
                    color = surfaceColor.copy(alpha = 0.6f)
                ) {
                    Text(
                        text = "Skip",
                        color = secondaryText,
                        fontSize = 12.5.sp,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            } else {
                Text(
                    text = "Step 2 of 2",
                    color = secondaryText,
                    fontSize = 12.sp,
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        AnimatedContent(
            targetState = currentStep,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "onboarding_flow"
        ) { step ->
            if (step == 1) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        // Brand Header Card
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(58.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = surfaceColor,
                                border = BorderStroke(1.dp, borderColor.copy(alpha = 0.5f))
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_splash_logo),
                                    contentDescription = "Seitharol",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Column {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = primaryColor.copy(alpha = 0.14f)
                                ) {
                                    Text(
                                        text = "SEITHAROL AUDIO",
                                        color = primaryColor,
                                        fontSize = 10.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.9.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Welcome to Seitharol",
                                    color = primaryText,
                                    fontSize = 23.sp,
                                    lineHeight = 28.sp,
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Ad-free high-bitrate streaming, time-synced lyrics, and offline playback tailored to your taste.",
                            color = secondaryText,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = OutfitFontFamily
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OnboardingFeatureCard(
                                icon = Icons.Outlined.GraphicEq,
                                title = "Pure Studio Audio",
                                body = "High-bitrate audio streaming with a built-in 5-band studio equalizer.",
                                primaryColor = primaryColor,
                                surfaceColor = surfaceColor,
                                borderColor = borderColor,
                                primaryText = primaryText,
                                secondaryText = secondaryText
                            )
                            OnboardingFeatureCard(
                                icon = Icons.Outlined.Subtitles,
                                title = "Synchronized Lyrics",
                                body = "Real-time scrolling lyrics with instant tap-to-seek and fullscreen view.",
                                primaryColor = primaryColor,
                                surfaceColor = surfaceColor,
                                borderColor = borderColor,
                                primaryText = primaryText,
                                secondaryText = secondaryText
                            )
                            OnboardingFeatureCard(
                                icon = Icons.Outlined.CloudDownload,
                                title = "Offline Playback",
                                body = "Save songs and videos directly to your device for uninterrupted offline listening.",
                                primaryColor = primaryColor,
                                surfaceColor = surfaceColor,
                                borderColor = borderColor,
                                primaryText = primaryText,
                                secondaryText = secondaryText
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Button(
                            onClick = { currentStep = 2 },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryColor,
                                contentColor = onPrimaryColor
                            ),
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text(
                                text = "Continue",
                                color = onPrimaryColor,
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                val currentCal = Calendar.getInstance()
                val defaultYear = currentCal.get(Calendar.YEAR) - 20
                val defaultMonth = currentCal.get(Calendar.MONTH)
                val defaultDay = currentCal.get(Calendar.DAY_OF_MONTH)

                val showDatePicker = {
                    val datePickerDialog = DatePickerDialog(
                        context,
                        { _, year, monthOfYear, dayOfMonth ->
                            val selectedCal = Calendar.getInstance().apply {
                                set(Calendar.YEAR, year)
                                set(Calendar.MONTH, monthOfYear)
                                set(Calendar.DAY_OF_MONTH, dayOfMonth)
                            }
                            val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            val displayFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)
                            birthdateIso = isoFormat.format(selectedCal.time)
                            birthdateDisplay = displayFormat.format(selectedCal.time)

                            val today = Calendar.getInstance()
                            var ageYears = today.get(Calendar.YEAR) - year
                            if (today.get(Calendar.DAY_OF_YEAR) < selectedCal.get(Calendar.DAY_OF_YEAR)) {
                                ageYears--
                            }
                            calculatedAge = ageYears.coerceAtLeast(0).toString()
                        },
                        defaultYear,
                        defaultMonth,
                        defaultDay
                    )
                    datePickerDialog.datePicker.maxDate = System.currentTimeMillis()
                    datePickerDialog.show()
                }

                val isFormValid = name.trim().isNotBlank() && birthdateIso.isNotBlank()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Personalize your profile",
                            color = primaryText,
                            fontSize = 23.sp,
                            lineHeight = 28.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Tell us your name and date of birth to customize your home feed.",
                            color = secondaryText,
                            fontSize = 13.5.sp,
                            lineHeight = 19.sp,
                            fontFamily = OutfitFontFamily
                        )

                        Spacer(modifier = Modifier.height(22.dp))

                        Text(
                            text = "Your Name",
                            color = primaryText,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = {
                                Text(
                                    text = "Enter your name",
                                    color = secondaryText.copy(alpha = 0.6f),
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = null,
                                    tint = if (name.isNotBlank()) primaryColor else secondaryText,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = borderColor.copy(alpha = 0.6f),
                                focusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                                unfocusedContainerColor = surfaceColor.copy(alpha = 0.5f),
                                focusedTextColor = primaryText,
                                focusedPlaceholderColor = secondaryText.copy(alpha = 0.6f),
                                unfocusedTextColor = primaryText,
                                cursorColor = primaryColor
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "Date of Birth",
                            color = primaryText,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { showDatePicker() }
                        ) {
                            OutlinedTextField(
                                value = birthdateDisplay,
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                placeholder = {
                                    Text(
                                        text = "Select Date of Birth",
                                        color = secondaryText.copy(alpha = 0.6f),
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Cake,
                                        contentDescription = "Select Date of Birth",
                                        tint = if (birthdateIso.isNotBlank()) primaryColor else secondaryText,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = primaryText,
                                    disabledBorderColor = if (birthdateIso.isNotBlank()) primaryColor else borderColor.copy(alpha = 0.6f),
                                    disabledContainerColor = surfaceColor.copy(alpha = 0.5f),
                                    disabledPlaceholderColor = secondaryText.copy(alpha = 0.6f),
                                    disabledLeadingIconColor = if (birthdateIso.isNotBlank()) primaryColor else secondaryText
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { currentStep = 1 },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = primaryText
                                ),
                                border = BorderStroke(1.dp, borderColor.copy(alpha = 0.7f)),
                                modifier = Modifier
                                    .weight(0.36f)
                                    .height(52.dp)
                            ) {
                                Text(
                                    text = "Back",
                                    color = primaryText,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }

                            // "Get Started" button: no overlay, simply disabled & loses color until valid
                            Button(
                                onClick = {
                                    if (isFormValid) {
                                        onContinue(
                                            name.trim(),
                                            birthdateIso,
                                            calculatedAge.ifBlank { "18" }
                                        )
                                    }
                                },
                                enabled = isFormValid,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryColor,
                                    contentColor = onPrimaryColor,
                                    disabledContainerColor = surfaceColor,
                                    disabledContentColor = secondaryText.copy(alpha = 0.45f)
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 0.dp,
                                    pressedElevation = 0.dp,
                                    disabledElevation = 0.dp
                                ),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(0.64f)
                                    .height(52.dp)
                            ) {
                                Text(
                                    text = "Get Started",
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
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
private fun OnboardingFeatureCard(
    icon: ImageVector,
    title: String,
    body: String,
    primaryColor: Color,
    surfaceColor: Color,
    borderColor: Color,
    primaryText: Color,
    secondaryText: Color
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = primaryColor.copy(alpha = 0.14f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = primaryText,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = body,
                    color = secondaryText,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    fontFamily = OutfitFontFamily
                )
            }
        }
    }
}
