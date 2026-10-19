package com.seitharol.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.preferences.ThemeMode
import com.seitharol.data.resolver.AudioQuality

enum class InfoPageType(val title: String) {
    ABOUT_APP("About the App"),
    HELP("Help & Support"),
    PRIVACY_POLICY("Privacy Policy"),
    TERMS_CONDITIONS("Terms & Conditions")
}

@Composable
fun GeneralSettingsScreen(
    settingsManager: AppSettingsManager,
    onOpenInfoPage: (InfoPageType) -> Unit
) {
    val state by settingsManager.state.collectAsState()
    val eqFrequencies = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    val sleepOptions = listOf(0, 15, 30, 45, 60)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Appearance (Use Device Theme as default)
        item {
            SettingsCard(title = "Appearance") {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { settingsManager.setThemeMode(mode) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.themeMode == mode,
                            onClick = { settingsManager.setThemeMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // 2. Streaming Sound Quality (Persisted)
        item {
            SettingsCard(title = "Sound Quality (Streaming)") {
                AudioQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { settingsManager.setStreamingQuality(quality) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.streamingQuality == quality,
                            onClick = { settingsManager.setStreamingQuality(quality) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(quality.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // 3. Default Download Quality (Persisted)
        item {
            SettingsCard(title = "Download Quality") {
                AudioQuality.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { settingsManager.setDownloadQuality(quality) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.downloadQuality == quality,
                            onClick = { settingsManager.setDownloadQuality(quality) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(quality.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // 4. Equalizer with Reset Button
        item {
            SettingsCard(title = "Equalizer") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.eqEnabled,
                            onCheckedChange = { settingsManager.setEqEnabled(it) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.eqEnabled) "Enabled" else "Disabled")
                    }
                    OutlinedButton(onClick = { settingsManager.resetEqualizer() }) {
                        Text("Reset")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                eqFrequencies.forEachIndexed { idx, freq ->
                    val gain = state.eqBandsDb.getOrElse(idx) { 0f }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(freq, style = MaterialTheme.typography.labelMedium)
                            Text("${"%.1f".format(gain)} dB", style = MaterialTheme.typography.labelMedium)
                        }
                        Slider(
                            value = gain,
                            onValueChange = { settingsManager.setEqBand(idx, it) },
                            valueRange = -12f..12f,
                            enabled = state.eqEnabled
                        )
                    }
                }
            }
        }

        // 5. Sleep Timer & Playback Features
        item {
            SettingsCard(title = "Sleep Timer & Playback") {
                Text("Sleep Timer", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sleepOptions.forEach { mins ->
                        FilterChip(
                            selected = state.sleepTimerMinutes == mins,
                            onClick = { settingsManager.setSleepTimer(mins) },
                            label = { Text(if (mins == 0) "Off" else "${mins}m") }
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Gapless Playback")
                    Switch(
                        checked = state.gaplessPlayback,
                        onCheckedChange = { settingsManager.setGaplessPlayback(it) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Normalize Volume")
                    Switch(
                        checked = state.audioNormalization,
                        onCheckedChange = { settingsManager.setAudioNormalization(it) }
                    )
                }
            }
        }

        // 6. "About Us" Clean Borderless & Background-Free Section
        item {
            Text(
                text = "About Us",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                InfoPageType.entries.forEachIndexed { index, page ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenInfoPage(page) }
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = page.title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                    if (index < InfoPageType.entries.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.6.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Dedicated screen for About the App, Help, Privacy Policy, and Terms & Conditions
 * with a Top-Left Back Arrow.
 */
@Composable
fun InfoDetailScreen(
    pageType: InfoPageType,
    onBack: () -> Unit
) {
    val bodyContent = when (pageType) {
        InfoPageType.ABOUT_APP -> "Seitharol is an advanced audio streaming and playback application engineered to deliver studio-grade canonical releases, synchronized lyric integration, and personalized discovery feeds with uncompromising acoustic fidelity."
        InfoPageType.HELP -> "For technical assistance or playback optimization, please verify your network connectivity. Audio stream bitrates can be customized in Sound Settings, and equalizer calibrations may be restored to baseline reference response via the dedicated Reset function."
        InfoPageType.PRIVACY_POLICY -> "Your privacy is strictly preserved. All playback logs, customized playlists, and local application preferences are retained exclusively within on-device storage. No personal listening telemetry is collected or distributed to third parties."
        InfoPageType.TERMS_CONDITIONS -> "By accessing this service, you acknowledge that all streamed and cached media materials are intended solely for personal, non-commercial listening in accordance with applicable intellectual property frameworks and terms of service."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = pageType.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = bodyContent,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
