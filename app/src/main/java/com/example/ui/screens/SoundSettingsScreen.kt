package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.player.EqualizerPreset
import com.example.player.PlayerState
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*

@Composable
fun SoundSettingsScreen(
    viewModel: CascadeViewModel,
    playerState: PlayerState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var isHighQuality by remember { mutableStateOf(true) }
    var selectedPreset by remember { mutableStateOf(playerState.currentPresetName) }

    // 6-band frequency values: 60Hz, 150Hz, 400Hz, 1kHz, 2.4kHz, 15kHz in dB (-12 to +12)
    var band60 by remember { mutableFloatStateOf(4f) }
    var band150 by remember { mutableFloatStateOf(2f) }
    var band400 by remember { mutableFloatStateOf(0f) }
    var band1k by remember { mutableFloatStateOf(1f) }
    var band2_4k by remember { mutableFloatStateOf(3f) }
    var band15k by remember { mutableFloatStateOf(5f) }

    val presets = listOf(
        Pair("Acoustic", listOf(3f, 2f, 1f, 2f, 3f, 4f)),
        Pair("Bass Booster", listOf(9f, 6f, 3f, 0f, -2f, -3f)),
        Pair("Dance", listOf(7f, 4f, 0f, 3f, 5f, 6f)),
        Pair("Electronic", listOf(8f, 5f, -1f, 3f, 6f, 7f)),
        Pair("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f))
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KangleiBg)
            .statusBarsPadding()
            .testTag("sound_settings_screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 140.dp)
    ) {
        // Top Bar: Close (X) icon on left, Title centered
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = KangleiDark,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Text(
                    text = "Sound settings",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = KangleiDark,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // Quality Options Card (White card, radius 20dp)
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = KangleiWhite
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    // Option 1: Good quality
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHighQuality = false }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Radio circle
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (!isHighQuality) KangleiDark else Color.Transparent)
                                .padding(if (!isHighQuality) 5.dp else 1.5.dp)
                                .clip(CircleShape)
                                .background(if (!isHighQuality) Color.White else Color(0xFF8A8A8A))
                        )
                        Column {
                            Text(
                                text = "Good quality",
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = KangleiDark
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Optimal when connecting via a mobile network",
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                color = KangleiTextSecondary
                            )
                        }
                    }

                    HorizontalDivider(color = KangleiDivider, thickness = 1.dp)

                    // Option 2: High quality
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHighQuality = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Radio circle
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(if (isHighQuality) KangleiDark else Color.Transparent)
                                .padding(if (isHighQuality) 5.dp else 1.5.dp)
                                .clip(CircleShape)
                                .background(if (isHighQuality) Color.White else Color(0xFF8A8A8A))
                        )
                        Column {
                            Text(
                                text = "High quality",
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = KangleiDark
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Balanced sound at different frequencies. Requires high speed internet connection",
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                color = KangleiTextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Theme Appearance Card
        item {
            val isDark = LocalIsDarkTheme.current
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sound_settings_theme_card"),
                shape = RoundedCornerShape(20.dp),
                color = KangleiWhite
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Dark Mode",
                            fontSize = 15.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = KangleiDark
                        )
                        Text(
                            text = if (isDark) "Active: Sleek Dark theme" else "Active: Clean Light theme",
                            fontSize = 12.sp,
                            fontFamily = OutfitFontFamily,
                            color = KangleiTextSecondary
                        )
                    }

                    Switch(
                        checked = isDark,
                        onCheckedChange = { viewModel.setDarkMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = KangleiGreen,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFCCCCCC)
                        )
                    )
                }
            }
        }

        // Equalizer Section Card
        item {
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = KangleiWhite
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Equalizer",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        color = KangleiDark
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Presets horizontal row
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(presets) { (name, levels) ->
                            val isSelected = selectedPreset == name
                            Surface(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(17.dp))
                                    .clickable {
                                        selectedPreset = name
                                        band60 = levels[0]
                                        band150 = levels[1]
                                        band400 = levels[2]
                                        band1k = levels[3]
                                        band2_4k = levels[4]
                                        band15k = levels[5]
                                        viewModel.setEqualizerPreset(EqualizerPreset(name, levels))
                                    },
                                shape = RoundedCornerShape(17.dp),
                                color = if (isSelected) KangleiDark else Color(0xFFF2F2F2)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Text(
                                        text = name,
                                        color = if (isSelected) Color.White else KangleiDark,
                                        fontSize = 13.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Sliders header (+12 dB / -12 dB indicators)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("+12 dB", fontSize = 11.sp, color = KangleiTextSecondary, fontFamily = OutfitFontFamily)
                        Text("-12 dB", fontSize = 11.sp, color = KangleiTextSecondary, fontFamily = OutfitFontFamily)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 6 Sliders
                    val bands = listOf(
                        Pair("60Hz", band60 to { v: Float -> band60 = v }),
                        Pair("150Hz", band150 to { v: Float -> band150 = v }),
                        Pair("400Hz", band400 to { v: Float -> band400 = v }),
                        Pair("1kHz", band1k to { v: Float -> band1k = v }),
                        Pair("2.4kHz", band2_4k to { v: Float -> band2_4k = v }),
                        Pair("15kHz", band15k to { v: Float -> band15k = v })
                    )

                    bands.forEach { (freq, statePair) ->
                        val (valDb, setVal) = statePair
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = freq,
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                color = KangleiDark,
                                modifier = Modifier.width(52.dp)
                            )
                            Slider(
                                value = valDb,
                                onValueChange = setVal,
                                valueRange = -12f..12f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = KangleiDark,
                                    activeTrackColor = KangleiDark,
                                    inactiveTrackColor = Color(0xFFE0E0E0)
                                )
                            )
                            Text(
                                text = "${if (valDb > 0) "+" else ""}${valDb.toInt()}dB",
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                color = KangleiTextSecondary,
                                modifier = Modifier.width(42.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Save Button
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable {
                                viewModel.setEqualizerPreset(
                                    EqualizerPreset(
                                        "Custom",
                                        listOf(band60, band150, band400, band1k, band2_4k, band15k)
                                    )
                                )
                                onBack()
                            },
                        shape = RoundedCornerShape(24.dp),
                        color = KangleiDark
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Save",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
