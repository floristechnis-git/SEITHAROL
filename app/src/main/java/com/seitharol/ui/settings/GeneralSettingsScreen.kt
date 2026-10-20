package com.seitharol.ui.settings

import androidx.compose.animation.AnimatedVisibility
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
import com.seitharol.data.preferences.ThumbnailQuality
import com.seitharol.data.resolver.AudioQuality
import com.seitharol.ui.search.BackArrowSvgIcon
import com.seitharol.ui.search.ChevronRightSvgIcon

enum class InfoPageType(val title: String) {
    ABOUT_APP("About Seitharol"),
    HELP("Help & Support"),
    PRIVACY_POLICY("Privacy Policy"),
    TERMS_CONDITIONS("Terms & Conditions")
}

private enum class SettingsDialogType {
    NONE, THEME, STREAM_QUALITY, DOWNLOAD_QUALITY, THUMBNAIL_QUALITY, SLEEP_TIMER
}

@Composable
fun GeneralSettingsScreen(
    settingsManager: AppSettingsManager,
    onOpenInfoPage: (InfoPageType) -> Unit,
    onNavigateBack: (() -> Unit)? = null
) {
    val state by settingsManager.state.collectAsState()
    var activeDialog by remember { mutableStateOf(SettingsDialogType.NONE) }
    var isEqualizerExpanded by remember { mutableStateOf(false) }
    val eqFrequencies = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onNavigateBack != null) {
                    IconButton(onClick = onNavigateBack) {
                        BackArrowSvgIcon(
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        // 1. APPEARANCE
        item {
            SettingsGroupCard(title = "Appearance") {
                SettingsValueRow(
                    title = "Theme",
                    subtitle = state.themeMode.label,
                    onClick = { activeDialog = SettingsDialogType.THEME }
                )
            }
        }

        // 2. DATA SAVER & QUALITY
        item {
            SettingsGroupCard(title = "Audio & Artwork Quality") {
                SettingsValueRow(
                    title = "Streaming Audio Quality",
                    subtitle = state.streamingQuality.label,
                    onClick = { activeDialog = SettingsDialogType.STREAM_QUALITY }
                )
                SettingsDivider()
                SettingsValueRow(
                    title = "Download Quality",
                    subtitle = state.downloadQuality.label,
                    onClick = { activeDialog = SettingsDialogType.DOWNLOAD_QUALITY }
                )
                SettingsDivider()
                SettingsValueRow(
                    title = "Thumbnail & Artwork Quality",
                    subtitle = state.thumbnailQuality.label,
                    onClick = { activeDialog = SettingsDialogType.THUMBNAIL_QUALITY }
                )
            }
        }

        // 3. PLAYBACK & EQUALIZER
        item {
            SettingsGroupCard(title = "Playback & Sound Effects") {
                SettingsToggleRow(
                    title = "Gapless Playback",
                    subtitle = "Seamless transitions between queued songs",
                    checked = state.gaplessPlayback,
                    onCheckedChange = { settingsManager.setGaplessPlayback(it) }
                )
                SettingsDivider()
                SettingsToggleRow(
                    title = "Normalize Volume",
                    subtitle = "Maintain consistent loudness across tracks",
                    checked = state.audioNormalization,
                    onCheckedChange = { settingsManager.setAudioNormalization(it) }
                )
                SettingsDivider()
                SettingsValueRow(
                    title = "Sleep Timer",
                    subtitle = if (state.sleepTimerMinutes == 0) "Off" else "${state.sleepTimerMinutes} minutes",
                    onClick = { activeDialog = SettingsDialogType.SLEEP_TIMER }
                )
                SettingsDivider()
                SettingsValueRow(
                    title = "5-Band Studio Equalizer",
                    subtitle = if (state.eqEnabled) "Active" else "Off — Tap to configure",
                    onClick = { isEqualizerExpanded = !isEqualizerExpanded }
                )

                AnimatedVisibility(visible = isEqualizerExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
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
                                Text(
                                    text = if (state.eqEnabled) "Equalizer On" else "Equalizer Off",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            OutlinedButton(onClick = { settingsManager.resetEqualizer() }) {
                                Text("Reset Flat")
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
                                    Text(freq, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                                    Text("${"%.1f".format(gain)} dB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            }
        }

        // 4. ABOUT & LEGAL
        item {
            SettingsGroupCard(title = "About & Legal") {
                InfoPageType.entries.forEachIndexed { index, page ->
                    SettingsValueRow(
                        title = page.title,
                        subtitle = null,
                        onClick = { onOpenInfoPage(page) }
                    )
                    if (index < InfoPageType.entries.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
            Spacer(modifier = Modifier.height(84.dp))
        }
    }

    // Selection Dialogs
    when (activeDialog) {
        SettingsDialogType.THEME -> SingleChoiceSettingsDialog(
            title = "Choose Theme",
            options = ThemeMode.entries.map { it.label },
            selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
            onSelect = { idx ->
                settingsManager.setThemeMode(ThemeMode.entries[idx])
                activeDialog = SettingsDialogType.NONE
            },
            onDismiss = { activeDialog = SettingsDialogType.NONE }
        )
        SettingsDialogType.STREAM_QUALITY -> SingleChoiceSettingsDialog(
            title = "Streaming Audio Quality",
            options = AudioQuality.entries.map { it.label },
            selectedIndex = AudioQuality.entries.indexOf(state.streamingQuality),
            onSelect = { idx ->
                settingsManager.setStreamingQuality(AudioQuality.entries[idx])
                activeDialog = SettingsDialogType.NONE
            },
            onDismiss = { activeDialog = SettingsDialogType.NONE }
        )
        SettingsDialogType.DOWNLOAD_QUALITY -> SingleChoiceSettingsDialog(
            title = "Download Audio Quality",
            options = AudioQuality.entries.map { it.label },
            selectedIndex = AudioQuality.entries.indexOf(state.downloadQuality),
            onSelect = { idx ->
                settingsManager.setDownloadQuality(AudioQuality.entries[idx])
                activeDialog = SettingsDialogType.NONE
            },
            onDismiss = { activeDialog = SettingsDialogType.NONE }
        )
        SettingsDialogType.THUMBNAIL_QUALITY -> SingleChoiceSettingsDialog(
            title = "Thumbnail & Artwork Quality",
            options = ThumbnailQuality.entries.map { "${it.label}\n${it.description}" },
            selectedIndex = ThumbnailQuality.entries.indexOf(state.thumbnailQuality),
            onSelect = { idx ->
                settingsManager.setThumbnailQuality(ThumbnailQuality.entries[idx])
                activeDialog = SettingsDialogType.NONE
            },
            onDismiss = { activeDialog = SettingsDialogType.NONE }
        )
        SettingsDialogType.SLEEP_TIMER -> {
            val sleepMins = listOf(0, 15, 30, 45, 60)
            SingleChoiceSettingsDialog(
                title = "Sleep Timer",
                options = sleepMins.map { if (it == 0) "Off" else "$it minutes" },
                selectedIndex = sleepMins.indexOf(state.sleepTimerMinutes).coerceAtLeast(0),
                onSelect = { idx ->
                    settingsManager.setSleepTimer(sleepMins[idx])
                    activeDialog = SettingsDialogType.NONE
                },
                onDismiss = { activeDialog = SettingsDialogType.NONE }
            )
        }
        SettingsDialogType.NONE -> Unit
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsValueRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ChevronRightSvgIcon(
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        thickness = 0.6.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    )
}

@Composable
private fun SingleChoiceSettingsDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEachIndexed { idx, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(idx) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = idx == selectedIndex,
                            onClick = { onSelect(idx) }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun InfoDetailScreen(
    pageType: InfoPageType,
    onBack: () -> Unit
) {
    val bodyContent = when (pageType) {
        InfoPageType.ABOUT_APP -> "Seitharol is an advanced audio streaming and playback application engineered to deliver studio-grade canonical releases, synchronized lyric integration, and personalized discovery feeds with uncompromising acoustic fidelity."
        InfoPageType.HELP -> "For technical assistance or playback optimization, verify your network connectivity. Audio stream bitrates and thumbnail data usage can be customized in Settings."
        InfoPageType.PRIVACY_POLICY -> "Your privacy is strictly preserved. All playback logs, customized playlists, and local application preferences are retained exclusively within on-device storage."
        InfoPageType.TERMS_CONDITIONS -> "By accessing this service, you acknowledge that all streamed and cached media materials are intended solely for personal, non-commercial listening."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                BackArrowSvgIcon(
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = pageType.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
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
