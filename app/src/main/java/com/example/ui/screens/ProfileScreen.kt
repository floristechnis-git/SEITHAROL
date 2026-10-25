package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.data.AppPreferences
import com.example.ui.CascadeViewModel
import com.example.ui.components.ManipurCalendarNotificationEngine
import com.example.ui.theme.*
import com.seitharol.ui.components.NotificationBellAlertSvgIcon
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: CascadeViewModel,
    onNavigateToSoundSettings: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenInfoPage: (com.seitharol.ui.settings.InfoPageType) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appPreferences = remember { AppPreferences(context) }
    val userSettings by appPreferences.settings.collectAsState()

    val downloadedTracks by viewModel.downloadedTracks.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val currentPackVersion by viewModel.currentPackVersion.collectAsState()
    val totalDownloadedBytes by viewModel.totalDownloadedBytes.collectAsState()
    val hasUnreadNotifications by ManipurCalendarNotificationEngine.hasUnreadAlerts.collectAsState()

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showAccountInfo by remember { mutableStateOf(false) }
    var showSubInfo by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("profile_screen"),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(44.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            onClick = onOpenNotifications
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                NotificationBellAlertSvgIcon(
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    hasUnreadAlert = hasUnreadNotifications,
                                    alertDotColor = Color(0xFFE53935),
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(top = 90.dp, start = 24.dp, end = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val barHeights = listOf(6, 13, 20, 27, 12, 19, 26, 11, 18, 25, 10, 17, 24, 9, 16, 23, 8, 15, 22, 7, 14, 21, 6, 13, 20, 27)
                        barHeights.forEach { h ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 1.5.dp)
                                    .width(3.dp)
                                    .height(h.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile Photo",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = userSettings.userName.ifBlank { "User Profile" },
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = if (userSettings.userAge.isNotBlank()) "Age: ${userSettings.userAge} • Seitharol" else "Discover the features yourself.",
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showEditProfileDialog = true }
                                .testTag("profile_account_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Edit Profile",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showSubInfo = true }
                                .testTag("profile_sub_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Engine Status",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClick = onOpenSleepTimer)
                                .testTag("profile_timer_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Timer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Sleep Timer",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClick = onNavigateToSoundSettings)
                                .testTag("profile_settings_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Settings",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Airplane Mode Offline",
                                fontSize = 15.sp,
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isOfflineMode)
                                    "Active • Playing ${downloadedTracks.size} cached offline songs"
                                else
                                    "Forces pure on-device cached playback (${downloadedTracks.size} saved)",
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = isOfflineMode,
                            onCheckedChange = { enabled ->
                                viewModel.setOfflineMode(enabled)
                                coroutineScope.launch {
                                    val msg = if (enabled) {
                                        "Airplane Mode Offline enabled (${downloadedTracks.size} offline songs ready)."
                                    } else {
                                        "Airplane Mode Offline disabled. Online streaming restored."
                                    }
                                    snackbarHostState.showSnackbar(msg)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedBorderColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.testTag("profile_offline_switch")
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "About & Support",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        com.seitharol.ui.settings.InfoPageType.entries.forEachIndexed { index, page ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenInfoPage(page) }
                                    .padding(vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = page.title,
                                        fontFamily = OutfitFontFamily,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (page == com.seitharol.ui.settings.InfoPageType.HELP) {
                                        Text(
                                            text = "Contact support at floristechnis@proton.me",
                                            fontFamily = OutfitFontFamily,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                com.seitharol.ui.search.ChevronRightSvgIcon(
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (index < com.seitharol.ui.settings.InfoPageType.entries.lastIndex) {
                                HorizontalDivider(
                                    thickness = 0.6.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showClearCacheDialog = true }
                        .padding(vertical = 12.dp)
                        .testTag("profile_logout_btn"),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Clear Cache & Reset",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear Cache & Reset",
                        fontFamily = OutfitFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showSubInfo) {
            ModalBottomSheet(
                onDismissRequest = { showSubInfo = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Engine Architecture Status",
                        fontFamily = SyneFontFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "100% On-Device Standalone Client ($currentPackVersion)",
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    val (cachedFileCount, cachedBytesTotal) = remember {
                        com.seitharol.player.OuterTuneResolvingDataSource.getCacheStatsSummary(context)
                    }
                    val cachedMbFormatted = remember(cachedBytesTotal) {
                        "%.1f MB".format(cachedBytesTotal.toDouble() / (1024.0 * 1024.0))
                    }
                    val features = listOf(
                        Pair("YouTube InnerTube On-Device", "Direct on-device search & unciphered stream extraction (ANDROID_VR). Zero python server required."),
                        Pair("JioSaavn Native DES Decryptor", "On-device DES/ECB/PKCS5Padding hardware decryption with 320kbps upgrade."),
                        Pair("Spotify Metadata Importer", "Cross-source TrackMatcher matching catalog tracks and canonical audio releases."),
                        Pair("Active Media Stream Cache ($cachedMbFormatted)", "Live inline chunk + disk stream cache ($cachedFileCount complete streams cached) for zero-rebuffer seeking & replay."),
                        Pair("High-Fi Offline Storage", "Real direct MediaStore byte streaming with verified binary audio & MP4 containers.")
                    )

                    features.forEach { (title, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = title,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = desc,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showSubInfo = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Close", fontFamily = OutfitFontFamily)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = "Clear Stream Cache & History?",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Text(
                        text = "This will flush cached audio/video streams, clear temporary search history, and reset playback checkpoints. Your downloaded offline songs will be preserved.",
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.clearStreamCacheAndReset()
                            showClearCacheDialog = false
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Stream cache and search history cleared successfully.")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Clear Cache", fontFamily = OutfitFontFamily)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = OutfitFontFamily)
                    }
                }
            )
        }

        if (showEditProfileDialog) {
            EditProfileDialog(
                initialName = userSettings.userName,
                initialAge = userSettings.userAge,
                initialBirthdate = userSettings.userBirthdate,
                onDismiss = { showEditProfileDialog = false },
                onSave = { name, age, birthdate ->
                    appPreferences.updateAccount(name, age, birthdate)
                    if (birthdate.isNotBlank()) {
                        com.example.notification.BirthdayNotificationScheduler.scheduleBirthdayAlarm(
                            context = context,
                            userName = name,
                            birthdateIso = birthdate
                        )
                    }
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Profile details saved successfully.")
                    }
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 148.dp)
                .zIndex(100f),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF222426),
                    contentColor = Color.White,
                    actionColor = MaterialTheme.colorScheme.primary,
                    dismissActionContentColor = Color.White,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        )
    }
}

@Composable
fun EditProfileDialog(
    initialName: String,
    initialAge: String,
    initialBirthdate: String = "",
    onDismiss: () -> Unit,
    onSave: (name: String, age: String, birthdate: String) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    var age by remember { mutableStateOf(initialAge) }
    var birthdateIso by remember { mutableStateOf(initialBirthdate) }
    var birthdateDisplay by remember {
        mutableStateOf(
            if (initialBirthdate.isNotBlank()) {
                runCatching {
                    val sdfIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val sdfOut = SimpleDateFormat("MMMM d, yyyy", Locale.US)
                    sdfIn.parse(initialBirthdate)?.let { sdfOut.format(it) } ?: initialBirthdate
                }.getOrDefault(initialBirthdate)
            } else ""
        )
    }

    val showDatePicker = {
        val currentCal = Calendar.getInstance()
        val defaultYear = currentCal.get(Calendar.YEAR) - (age.toIntOrNull() ?: 20)
        val defaultMonth = currentCal.get(Calendar.MONTH)
        val defaultDay = currentCal.get(Calendar.DAY_OF_MONTH)

        val dpd = DatePickerDialog(
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
                age = ageYears.coerceAtLeast(0).toString()
            },
            defaultYear,
            defaultMonth,
            defaultDay
        )
        dpd.datePicker.maxDate = System.currentTimeMillis()
        dpd.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Edit Profile",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
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
                        label = { Text("Date of Birth", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        placeholder = {
                            Text(
                                text = "Select Date of Birth",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker() }) {
                                Icon(
                                    imageVector = Icons.Outlined.Cake,
                                    contentDescription = "Select Date of Birth",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = age,
                    onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) age = it },
                    label = { Text("Age", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(name.trim(), age.trim(), birthdateIso.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}
