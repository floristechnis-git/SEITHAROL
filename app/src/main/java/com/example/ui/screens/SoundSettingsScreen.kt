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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppPreferences
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

    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    val userSettings by appPreferences.settings.collectAsState()

    var selectedPreset by remember { mutableStateOf(playerState.currentPresetName) }

    // 5-band frequency values: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz in dB (-12 to +12)
    var band60 by remember { mutableFloatStateOf(userSettings.eqBandsDb.getOrElse(0) { 0f }) }
    var band230 by remember { mutableFloatStateOf(userSettings.eqBandsDb.getOrElse(1) { 0f }) }
    var band910 by remember { mutableFloatStateOf(userSettings.eqBandsDb.getOrElse(2) { 0f }) }
    var band3_6k by remember { mutableFloatStateOf(userSettings.eqBandsDb.getOrElse(3) { 0f }) }
    var band14k by remember { mutableFloatStateOf(userSettings.eqBandsDb.getOrElse(4) { 0f }) }

    val presets = listOf(
        Pair("Acoustic", listOf(3f, 2f, 1f, 3f, 4f)),
        Pair("Bass Booster", listOf(8f, 5f, 2f, -1f, -2f)),
        Pair("Dance", listOf(6f, 4f, 1f, 4f, 6f)),
        Pair("Electronic", listOf(7f, 5f, 0f, 5f, 7f)),
        Pair("Flat", listOf(0f, 0f, 0f, 0f, 0f))
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SeitharolBg)
            .statusBarsPadding()
            .testTag("sound_settings_screen"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        tint = SeitharolDark,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Text(
                    text = "Settings",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = SeitharolDark,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // 1. Appearance (System Default / Dark / Light theme)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SeitharolWhite
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Appearance",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = SeitharolDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val themeOptions = listOf(
                        Triple("SYSTEM", "Use Device Theme (System Default)", "Automatically matches system light/dark theme"),
                        Triple("DARK", "Dark Mode", "Deep OLED dark appearance"),
                        Triple("LIGHT", "Light Mode", "Clean stone light appearance")
                    )

                    themeOptions.forEach { (key, title, subtitle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    appPreferences.setThemeMode(key)
                                    viewModel.setDarkMode(key == "DARK")
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = userSettings.themeMode == key,
                                onClick = {
                                    appPreferences.setThemeMode(key)
                                    viewModel.setDarkMode(key == "DARK")
                                }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = title,
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                                Text(
                                    text = subtitle,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    color = SeitharolTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Sound Quality (Streaming bitrate)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SeitharolWhite
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Sound Quality (Streaming)",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = SeitharolDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val streamQualities = listOf(
                        Triple("LOW", "Low (96 kbps)", "Data saver mode for slow mobile connections"),
                        Triple("NORMAL", "Normal (160 kbps)", "Standard AAC streaming quality"),
                        Triple("HIGH", "High (320 kbps)", "Studio fidelity with canonical audio resolution")
                    )

                    streamQualities.forEach { (key, label, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { appPreferences.setStreamingQuality(key) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = userSettings.streamingQuality == key,
                                onClick = { appPreferences.setStreamingQuality(key) }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    color = SeitharolTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Download Quality
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SeitharolWhite
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Download Quality",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = SeitharolDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val downloadQualities = listOf(
                        Triple("NORMAL", "Normal (160 kbps)", "Faster downloads, compact storage footprint"),
                        Triple("HIGH", "High (320 kbps)", "Maximum offline audio fidelity")
                    )

                    downloadQualities.forEach { (key, label, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { appPreferences.setDownloadQuality(key) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = userSettings.downloadQuality == key,
                                onClick = { appPreferences.setDownloadQuality(key) }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    color = SeitharolTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Equalizer with 0 dB Reset Button
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SeitharolWhite
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Equalizer",
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = SeitharolDark
                        )
                        // Reset EQ button: Resets all bands to 0 dB flat response and persists immediately
                        Surface(
                            modifier = Modifier
                                .height(30.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .clickable {
                                    selectedPreset = "Flat"
                                    band60 = 0f; band230 = 0f; band910 = 0f
                                    band3_6k = 0f; band14k = 0f
                                    appPreferences.resetEqualizer()
                                    viewModel.setEqualizerPreset(EqualizerPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f)))
                                }
                                .testTag("eq_reset_button"),
                            shape = RoundedCornerShape(15.dp),
                            color = SeitharolCardElevated
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Text(
                                    text = "Reset",
                                    color = SeitharolDark,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

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
                                        band230 = levels[1]
                                        band910 = levels[2]
                                        band3_6k = levels[3]
                                        band14k = levels[4]
                                        levels.forEachIndexed { i, db -> appPreferences.setEqBand(i, db) }
                                        viewModel.setEqualizerPreset(EqualizerPreset(name, levels))
                                    },
                                shape = RoundedCornerShape(17.dp),
                                color = if (isSelected) SeitharolPillBg else SeitharolCardElevated
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Text(
                                        text = name,
                                        color = if (isSelected) SeitharolPillText else SeitharolDark,
                                        fontSize = 13.sp,
                                        fontFamily = OutfitFontFamily,
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("+12 dB", fontSize = 11.sp, color = SeitharolTextSecondary, fontFamily = OutfitFontFamily)
                        Text("-12 dB", fontSize = 11.sp, color = SeitharolTextSecondary, fontFamily = OutfitFontFamily)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val bands = listOf(
                        Triple("60Hz", band60) { v: Float ->
                            band60 = v
                            appPreferences.setEqBand(0, v)
                            viewModel.setEqualizerPreset(EqualizerPreset("Custom", listOf(band60, band230, band910, band3_6k, band14k)))
                        },
                        Triple("230Hz", band230) { v: Float ->
                            band230 = v
                            appPreferences.setEqBand(1, v)
                            viewModel.setEqualizerPreset(EqualizerPreset("Custom", listOf(band60, band230, band910, band3_6k, band14k)))
                        },
                        Triple("910Hz", band910) { v: Float ->
                            band910 = v
                            appPreferences.setEqBand(2, v)
                            viewModel.setEqualizerPreset(EqualizerPreset("Custom", listOf(band60, band230, band910, band3_6k, band14k)))
                        },
                        Triple("3.6kHz", band3_6k) { v: Float ->
                            band3_6k = v
                            appPreferences.setEqBand(3, v)
                            viewModel.setEqualizerPreset(EqualizerPreset("Custom", listOf(band60, band230, band910, band3_6k, band14k)))
                        },
                        Triple("14kHz", band14k) { v: Float ->
                            band14k = v
                            appPreferences.setEqBand(4, v)
                            viewModel.setEqualizerPreset(EqualizerPreset("Custom", listOf(band60, band230, band910, band3_6k, band14k)))
                        }
                    )

                    bands.forEach { (freq, valDb, setVal) ->
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
                                color = SeitharolDark,
                                modifier = Modifier.width(56.dp)
                            )
                            Slider(
                                value = valDb,
                                onValueChange = setVal,
                                valueRange = -12f..12f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${if (valDb > 0) "+" else ""}${valDb.toInt()}dB",
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                color = SeitharolTextSecondary,
                                modifier = Modifier.width(44.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }

        // 5. Sleep Timer & Playback
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SeitharolWhite
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Sleep Timer",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = SeitharolDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val sleepOptions = listOf(0, 15, 30, 45, 60)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sleepOptions.forEach { mins ->
                            FilterChip(
                                selected = userSettings.sleepTimerMinutes == mins,
                                onClick = {
                                    appPreferences.saveSleepTimer(mins)
                                    viewModel.setSleepTimer(mins)
                                },
                                label = { Text(if (mins == 0) "Off" else "${mins}m") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = SeitharolDivider, thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Gapless Playback",
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = SeitharolDark
                            )
                            Text(
                                text = "Seamless transitions without silence between songs",
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                color = SeitharolTextSecondary
                            )
                        }
                        Switch(
                            checked = userSettings.gaplessPlayback,
                            onCheckedChange = { appPreferences.setGaplessPlayback(it) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Normalize Volume",
                                fontSize = 14.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = SeitharolDark
                            )
                            Text(
                                text = "Equalize loudness across different tracks and albums",
                                fontSize = 11.sp,
                                fontFamily = OutfitFontFamily,
                                color = SeitharolTextSecondary
                            )
                        }
                        Switch(
                            checked = userSettings.audioNormalization,
                            onCheckedChange = { appPreferences.setAudioNormalization(it) }
                        )
                    }
                }
            }
        }
    }
}

