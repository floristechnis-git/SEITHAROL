package com.example.ui.screens

import android.app.DatePickerDialog
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.OutfitFontFamily
import com.example.ui.theme.SyneFontFamily
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Clean, perfectly fitted 2-Step Onboarding Screen:
 * - Fits any standard mobile viewport cleanly without requiring scrolling or causing overflow.
 * - Zero useless whitespace: proportional padding, compact logo surface, and streamlined feature points.
 * - Asks for Birthday instead of Age to schedule 5:30 AM Happy Birthday notification.
 * - The Continue button is only clickable after entering Name and selecting Birthday.
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
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        // Top Progress Indicators & Skip Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
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
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 1) primaryColor else borderColor)
                )
                Box(
                    modifier = Modifier
                        .width(if (currentStep == 2) 24.dp else 8.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (currentStep == 2) primaryColor else borderColor)
                )
            }

            if (currentStep == 1) {
                Text(
                    text = "Skip",
                    color = secondaryText,
                    fontSize = 13.sp,
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
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
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
                        Spacer(modifier = Modifier.height(10.dp))

                        // App Brand Icon
                        Surface(
                            modifier = Modifier
                                .size(60.dp)
                                .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            color = surfaceColor
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_splash_logo),
                                contentDescription = "Seitharol",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Welcome to Seitharol",
                            color = primaryText,
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Studio-grade streaming, synchronized lyrics, and offline playback crafted for music enthusiasts.",
                            color = secondaryText,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = OutfitFontFamily
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            EditorialPoint(
                                title = "Pure Studio Audio",
                                body = "High-bitrate streams with an integrated 5-band equalizer.",
                                primaryText = primaryText,
                                secondaryText = secondaryText
                            )
                            EditorialPoint(
                                title = "Synchronized Lyrics",
                                body = "Real-time scrolling lyrics with interactive tap-to-seek.",
                                primaryText = primaryText,
                                secondaryText = secondaryText
                            )
                            EditorialPoint(
                                title = "Offline Downloads",
                                body = "Save tracks and videos to local device storage for offline play.",
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
                            shape = RoundedCornerShape(26.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = "Continue",
                                color = onPrimaryColor,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
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

                            // Calculate age
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
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "How should we greet you?",
                            color = primaryText,
                            fontSize = 24.sp,
                            lineHeight = 30.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Set up your profile to receive personalized Happy Birthday wishes delivered right at 5:30 AM.",
                            color = secondaryText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontFamily = OutfitFontFamily
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Your name",
                            color = primaryText,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = {
                                Text(
                                    text = "Enter your name",
                                    color = secondaryText.copy(alpha = 0.6f),
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 14.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = borderColor,
                                focusedContainerColor = surfaceColor,
                                unfocusedContainerColor = surfaceColor,
                                focusedTextColor = primaryText,
                                unfocusedTextColor = primaryText,
                                cursorColor = primaryColor
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Birthday",
                            color = primaryText,
                            fontSize = 13.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showDatePicker() }
                        ) {
                            OutlinedTextField(
                                value = birthdateDisplay,
                                onValueChange = {},
                                readOnly = true,
                                enabled = false,
                                placeholder = {
                                    Text(
                                        text = "Select your birthday (Date of Birth)",
                                        color = secondaryText.copy(alpha = 0.6f),
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 14.sp
                                    )
                                },
                                trailingIcon = {
                                    IconButton(onClick = { showDatePicker() }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Cake,
                                            contentDescription = "Select Birthday",
                                            tint = if (birthdateIso.isNotBlank()) primaryColor else secondaryText
                                        )
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    disabledTextColor = primaryText,
                                    disabledBorderColor = if (birthdateIso.isNotBlank()) primaryColor else borderColor,
                                    disabledContainerColor = surfaceColor,
                                    disabledPlaceholderColor = secondaryText.copy(alpha = 0.6f),
                                    disabledTrailingIconColor = if (birthdateIso.isNotBlank()) primaryColor else secondaryText
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (birthdateIso.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(start = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsActive,
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Happy Birthday notification set for 5:30 AM (Age: $calculatedAge)",
                                    color = primaryColor,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            Text(
                                text = "Our app delivers a personalized Happy Birthday wish at 5:30 AM on this exact date.",
                                color = secondaryText,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontFamily = OutfitFontFamily,
                                modifier = Modifier.padding(start = 2.dp)
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
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { currentStep = 1 },
                                shape = RoundedCornerShape(26.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = primaryText
                                ),
                                border = BorderStroke(1.dp, borderColor),
                                modifier = Modifier
                                    .weight(0.35f)
                                    .height(50.dp)
                            ) {
                                Text(
                                    text = "Back",
                                    color = primaryText,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }

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
                                    disabledContainerColor = primaryColor.copy(alpha = 0.35f),
                                    disabledContentColor = onPrimaryColor.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(26.dp),
                                modifier = Modifier
                                    .weight(0.65f)
                                    .height(50.dp)
                            ) {
                                Text(
                                    text = "Get Started",
                                    color = if (isFormValid) onPrimaryColor else onPrimaryColor.copy(alpha = 0.5f),
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
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
private fun EditorialPoint(
    title: String,
    body: String,
    primaryText: androidx.compose.ui.graphics.Color,
    secondaryText: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(primaryText)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = primaryText,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = body,
                color = secondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = OutfitFontFamily
            )
        }
    }
}
