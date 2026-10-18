package com.praxper.seitharol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.praxper.seitharol.data.UserSettings

@Composable
fun ProfileAndSettingsScreen(
    settings: UserSettings,
    onSaveAccount: (name: String, age: String) -> Unit,
    onSaveSoundSettings: (quality: String, normalize: Boolean, bass: Float, crossfade: Int) -> Unit
) {
    var name by remember(settings.userName) { mutableStateOf(settings.userName) }
    var age by remember(settings.userAge) { mutableStateOf(settings.userAge) }

    var audioQuality by remember(settings.audioQuality) { mutableStateOf(settings.audioQuality) }
    var normalizeVolume by remember(settings.normalizeVolume) { mutableStateOf(settings.normalizeVolume) }
    var bassBoost by remember(settings.bassBoostLevel) { mutableFloatStateOf(settings.bassBoostLevel) }
    var crossfade by remember(settings.crossfadeSeconds) { mutableIntStateOf(settings.crossfadeSeconds) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "ACCOUNT & SOUND SETTINGS",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold
        )

        statusBanner?.let { msg ->
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Account Section (Editable Name & Age, zero fake mockups)
        Surface(
            color = Color(0xFF1B1C1C),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Profile Details", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", color = Color.White.copy(alpha = 0.7f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = age,
                    onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) age = it },
                    label = { Text("Age", color = Color.White.copy(alpha = 0.7f)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        onSaveAccount(name, age)
                        statusBanner = "Account profile saved!"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Profile", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Sound Settings Section
        Surface(
            color = Color(0xFF1B1C1C),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Sound Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Normalize Volume", color = Color.White)
                    Switch(
                        checked = normalizeVolume,
                        onCheckedChange = { normalizeVolume = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White
                        )
                    )
                }

                Text("Bass Boost (${(bassBoost * 100).toInt()}%)", color = Color.White)
                Slider(
                    value = bassBoost,
                    onValueChange = { bassBoost = it },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White
                    )
                )

                Text("Crossfade: $crossfade seconds", color = Color.White)
                Slider(
                    value = crossfade.toFloat(),
                    onValueChange = { crossfade = it.toInt() },
                    valueRange = 0f..12f,
                    steps = 11,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White
                    )
                )

                Button(
                    onClick = {
                        onSaveSoundSettings(audioQuality, normalizeVolume, bassBoost, crossfade)
                        statusBanner = "Sound settings saved!"
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Sound Settings", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
