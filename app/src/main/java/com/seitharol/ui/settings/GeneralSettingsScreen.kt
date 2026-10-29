package com.seitharol.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onOpenInfoPage: (InfoPageType) -> Unit = {},
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

        // 1. APPEARANCE & ALWAYS ON SCREEN
        item {
            SettingsGroupCard(title = "Appearance & Display") {
                SettingsValueRow(
                    title = "Theme",
                    subtitle = state.themeMode.label,
                    onClick = { activeDialog = SettingsDialogType.THEME }
                )
                SettingsDivider()
                SettingsToggleRow(
                    title = "Always On Screen: Video",
                    subtitle = "Keep screen awake while watching video tracks (Default: On)",
                    checked = state.alwaysOnScreenVideo,
                    onCheckedChange = { settingsManager.setAlwaysOnScreenVideo(it) }
                )
                SettingsDivider()
                SettingsToggleRow(
                    title = "Always On Screen: Music",
                    subtitle = "Keep screen awake while listening to music tracks (Default: Off)",
                    checked = state.alwaysOnScreenMusic,
                    onCheckedChange = { settingsManager.setAlwaysOnScreenMusic(it) }
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
                    title = "Stop audio immediately on new selection",
                    subtitle = if (state.stopImmediatelyOnNewTrack)
                        "Stops currently playing audio immediately when a new song is picked (Default)"
                    else
                        "Keeps existing audio playing in background until the new song finishes loading",
                    checked = state.stopImmediatelyOnNewTrack,
                    onCheckedChange = { settingsManager.setStopImmediatelyOnNewTrack(it) }
                )
                SettingsDivider()
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
                                    onCheckedChange = { settingsManager.setEqEnabled(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        uncheckedBorderColor = MaterialTheme.colorScheme.outline
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (state.eqEnabled) "Equalizer On" else "Equalizer Off",
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            OutlinedButton(
                                onClick = { settingsManager.resetEqualizer() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Reset Flat", color = MaterialTheme.colorScheme.primary)
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

        // 4. HELP & SUPPORT
        item {
            SettingsGroupCard(title = "Support & Information") {
                InfoPageType.entries.forEachIndexed { idx, page ->
                    SettingsValueRow(
                        title = page.title,
                        subtitle = if (page == InfoPageType.HELP) "Direct support & collaboration: floristechnis@proton.me" else null,
                        onClick = { onOpenInfoPage(page) }
                    )
                    if (idx < InfoPageType.entries.lastIndex) {
                        SettingsDivider()
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(225.dp))
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
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
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
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
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = MaterialTheme.colorScheme.primary) }
        }
    )
}

@Composable
fun InfoDetailScreen(
    pageType: InfoPageType,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val supportEmail = "floristechnis@proton.me"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
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

        Spacer(modifier = Modifier.height(18.dp))

        when (pageType) {
            InfoPageType.ABOUT_APP -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Seitharol is an advanced audio streaming and playback application engineered to deliver studio-grade canonical releases, synchronized lyric integration, and personalized discovery feeds with uncompromising acoustic fidelity.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 24.sp
                    )

                    Text(
                        text = "Key Engineering Highlights",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "• Pure Studio Audio: High-resolution audio stream extraction with configurable low/medium/high quality tiers and an integrated 5-band equalizer.\n\n" +
                            "• Synchronized Lyrics Engine: Interactive real-time synchronized LRC lyrics with seamless tap-to-seek, offline caching, and sub-second timing offset adjustments.\n\n" +
                            "• Unified Media Discovery: Seamlessly explore tracks, albums, artists, and playlists across streaming providers, trending charts, and local storage.\n\n" +
                            "• Offline Downloads Vault: High-fidelity audio and MP4 video downloads saved directly to device storage with MediaStore registration for playback anywhere.\n\n" +
                            "• Privacy by Architecture: Zero advertising frameworks, zero telemetry trackers, and zero user data harvesting. Your library stays yours.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.6.dp
                    )

                    Text(
                        text = "Developed by Yumkhaibam Johnson Singh • Version 2.0.1 Public Release",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            InfoPageType.HELP -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Welcome to Seitharol Support. Here you will find quick solutions to common playback questions, performance tips, and direct contact details for developer assistance.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 24.sp
                    )

                    Text(
                        text = "Frequently Asked Questions & Tips",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "• Background Playback Stopping: If playback stops when the screen is turned off, navigate to Android Settings > Apps > Seitharol > Battery, and select 'Unrestricted' or disable aggressive Battery Optimization.\n\n" +
                            "• Audio & Video Downloads: Downloaded songs and videos are saved to your device's Music and Video folders and are immediately available in the 'Local' tab and your device's file manager.\n\n" +
                            "• Lyrics Out of Sync: While viewing synchronized lyrics in the player screen, tap the +/- buttons to adjust timing offsets in 100ms increments for perfect vocal alignment.\n\n" +
                            "• Streaming Bitrates: You can customize default streaming and download bitrates under Settings > Audio Quality.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.6.dp
                    )

                    Text(
                        text = "Developer Support Contact",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "For direct bug reports, questions, or feedback, contact us at:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:$supportEmail?subject=${Uri.encode("Seitharol App Support / Feedback")}")
                                    }
                                    context.startActivity(intent)
                                }.onFailure {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Support Email", supportEmail))
                                    Toast.makeText(context, "Email copied: $supportEmail", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Email,
                                contentDescription = "Email",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = supportEmail,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        IconButton(
                            onClick = {
                                runCatching {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Support Email", supportEmail))
                                    Toast.makeText(context, "Support email copied: $supportEmail", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = "Copy Email",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            InfoPageType.PRIVACY_POLICY -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "At Seitharol, privacy is not an afterthought—it is foundational to our design. We believe that your music listening habits and personal preferences belong solely to you.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 24.sp
                    )

                    Text(
                        text = "Our Privacy Commitments",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "• Zero Telemetry & Tracking: We do not log, track, sell, or transmit any user behavior or personally identifiable information.\n\n" +
                            "• On-Device Exclusivity: All created playlists, playback queues, liked tracks, listening history, and local equalization settings remain strictly on your local device storage.\n\n" +
                            "• Direct Content Resolution: When querying track information or streaming audio, requests communicate directly with source resolvers without intermediate proxy inspection.\n\n" +
                            "• Full Data Control: You retain complete authority to clear recent searches, reset preferences, or wipe all cached metadata at any moment via Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }

            InfoPageType.TERMS_CONDITIONS -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "By downloading, installing, or using Seitharol, you agree to these Terms & Conditions. Please read them carefully.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        lineHeight = 24.sp
                    )

                    Text(
                        text = "Terms of Use",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    Text(
                        text = "• Personal & Non-Commercial Use: Seitharol is licensed exclusively for private, personal, non-commercial listening.\n\n" +
                            "• Intellectual Property: All track titles, artist identities, musical audio recordings, album artwork, and lyric texts are the exclusive intellectual property of their respective creators and copyright owners.\n\n" +
                            "• User Responsibility: You agree to use streaming and offline caching in full compliance with all relevant copyright guidelines, intellectual property legislation, and local legal statutes.\n\n" +
                            "• As-Is Availability: Seitharol is distributed on an 'as-is' and 'as-available' basis without warranties of uninterrupted service or third-party stream persistence.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(160.dp))
    }
}
