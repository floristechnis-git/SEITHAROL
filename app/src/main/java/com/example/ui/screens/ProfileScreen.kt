package com.example.ui.screens

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.AppPreferences
import com.example.ui.CascadeViewModel
import com.example.ui.theme.*
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
                .background(SeitharolBg)
                .testTag("profile_screen"),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            // Top 300dp Dark Header: Uses SeitharolNavBg (#0D0D0D in light, #1C1C1E in dark)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                        .background(SeitharolNavBg)
                ) {
                    // Top Notification button on right
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
                            color = SeitharolDarkButton,
                            onClick = onOpenNotifications
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = "Notifications",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Decorative waveform audio bars behind avatar
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
                                    .background(Color(0xFF555555))
                            )
                        }
                    }

                    // Centered 100dp Avatar + User Name + Status
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
                                .background(Color(0xFF333333)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile Photo",
                                tint = Color.White,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = userSettings.userName.ifBlank { "User Profile" },
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 24.sp,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = if (userSettings.userAge.isNotBlank()) "Age: ${userSettings.userAge} • Seitharol" else "Discover the features yourself.",
                            fontFamily = OutfitFontFamily,
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                }
            }

            // 2x2 Grid of White Cards (Account, Subscription, Payment, Settings)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Row 1: Account & Subscription
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Account Card (Opens Edit Name & Age dialog)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showEditProfileDialog = true }
                                .testTag("profile_account_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = SeitharolWhite
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
                                    tint = SeitharolDark,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Edit Profile",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                            }
                        }

                        // Subscription Card: Opens Standalone Multi-Source Engine Status
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showSubInfo = true }
                                .testTag("profile_sub_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = SeitharolWhite
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CardMembership,
                                    contentDescription = null,
                                    tint = SeitharolDark,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Subscription",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                            }
                        }
                    }

                    // Row 2: Sleep Timer & Settings
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Sleep Timer Card
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClick = onOpenSleepTimer)
                                .testTag("profile_payment_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = SeitharolWhite
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
                                    tint = SeitharolDark,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Sleep Timer",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                            }
                        }

                        // Settings Card (opens Sound settings & Equalizer)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(96.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(onClick = onNavigateToSoundSettings)
                                .testTag("profile_settings_card"),
                            shape = RoundedCornerShape(20.dp),
                            color = SeitharolWhite
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
                                    tint = SeitharolDark,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Settings",
                                    fontSize = 14.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                            }
                        }
                    }
                }
            }

            // Account / Cache details if toggled
            if (showAccountInfo) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = SeitharolWhite
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Offline Storage Stats",
                                fontFamily = SyneFontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                                color = SeitharolDark
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val mb = (totalDownloadedBytes / (1024 * 1024.0))
                            Text(
                                text = "${downloadedTracks.size} verified offline tracks (${String.format("%.1f", mb)} MB) in local Room SQLite database.",
                                fontSize = 12.sp,
                                color = SeitharolTextSecondary,
                                fontFamily = OutfitFontFamily
                            )
                        }
                    }
                }
            }

            // Appearance Section (Use Device Theme as default, Dark Mode, Light Mode)
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .testTag("profile_appearance_card"),
                    shape = RoundedCornerShape(20.dp),
                    color = SeitharolWhite
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Appearance",
                            fontSize = 15.sp,
                            fontFamily = SyneFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = SeitharolDark
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val modes = listOf(
                            Triple("SYSTEM", "Use Device Theme (System Default)", "Follows your device system dark/light appearance"),
                            Triple("DARK", "Dark Mode", "Deep OLED Dark Theme"),
                            Triple("LIGHT", "Light Mode", "Clean Stone Light Theme")
                        )

                        modes.forEach { (modeKey, title, subtitle) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { appPreferences.setThemeMode(modeKey) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = userSettings.themeMode == modeKey,
                                    onClick = { appPreferences.setThemeMode(modeKey) }
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

            // Airplane Offline Mode Quick Toggle Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = SeitharolWhite
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
                                color = SeitharolDark
                            )
                            Text(
                                text = "Forces pure on-device cached playback",
                                fontSize = 12.sp,
                                fontFamily = OutfitFontFamily,
                                color = SeitharolTextSecondary
                            )
                        }

                        Switch(
                            checked = isOfflineMode,
                            onCheckedChange = { viewModel.setOfflineMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SeitharolDark
                            )
                        )
                    }
                }
            }

            // "About Us" Clean Borderless & Background-Free Section
            item {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "About Us",
                    fontFamily = SyneFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = SeitharolDark,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = SeitharolDarkCard,
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
                                Text(
                                    text = page.title,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = SeitharolDark
                                )
                                com.seitharol.ui.search.ChevronRightSvgIcon(
                                    tint = SeitharolTextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (index < com.seitharol.ui.settings.InfoPageType.entries.lastIndex) {
                                HorizontalDivider(
                                    thickness = 1.dp,
                                    color = SeitharolDivider
                                )
                            }
                        }
                    }
                }
            }

            // Log Out / Clear Cache Button
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
                        tint = SeitharolDark,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear Cache & Reset",
                        fontFamily = OutfitFontFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = SeitharolDark
                    )
                }
            }
        }

        // Informative Bottom Sheet for Subscription & Standalone Engine Status
        if (showSubInfo) {
            ModalBottomSheet(
                onDismissRequest = { showSubInfo = false },
                containerColor = SeitharolWhite,
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
                        color = SeitharolDark
                    )
                    Text(
                        text = "100% On-Device Standalone Client ($currentPackVersion)",
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        color = SeitharolTextSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = SeitharolBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    val features = listOf(
                        Pair("YouTube InnerTube On-Device", "Direct on-device search & stream deciphering (ANDROID_VR / IOS). Zero python server required."),
                        Pair("JioSaavn Native DES Decryptor", "On-device DES/ECB/PKCS5Padding hardware decryption with 320kbps upgrade."),
                        Pair("Spotify Metadata Importer", "Cross-source TrackMatcher matching ISRC, title, and duration proximity."),
                        Pair("ExoPlayer Custom OkHttp Headers", "Full HTTP User-Agent and Range support eliminating 403 Forbidden errors."),
                        Pair("High-Fi Offline Storage", "Real HTTP byte streaming with verified binary audio containers (>10KB).")
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
                                tint = SeitharolGreen,
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
                                    color = SeitharolDark
                                )
                                Text(
                                    text = desc,
                                    fontFamily = OutfitFontFamily,
                                    fontSize = 12.sp,
                                    color = SeitharolTextSecondary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showSubInfo = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SeitharolPillBg,
                            contentColor = SeitharolPillText
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Close", color = SeitharolPillText, fontFamily = OutfitFontFamily)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        // Confirmation Dialog for Clearing Stream Cache & History
        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                containerColor = SeitharolWhite,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = "Clear Stream Cache & History?",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = SeitharolDark
                    )
                },
                text = {
                    Text(
                        text = "This will flush in-memory resolved audio streams, clear temporary search history, and reset playback checkpoints. Your downloaded offline songs will be preserved.",
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        color = SeitharolTextSecondary
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
                            containerColor = SeitharolRedHeart,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Clear Cache", color = Color.White, fontFamily = OutfitFontFamily)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text("Cancel", color = SeitharolTextSecondary, fontFamily = OutfitFontFamily)
                    }
                }
            )
        }

        // Edit Name & Age Profile Dialog
        if (showEditProfileDialog) {
            EditProfileDialog(
                initialName = userSettings.userName,
                initialAge = userSettings.userAge,
                onDismiss = { showEditProfileDialog = false },
                onSave = { name, age ->
                    appPreferences.updateAccount(name, age)
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
                .padding(bottom = 100.dp)
        )
    }
}

@Composable
fun EditProfileDialog(
    initialName: String,
    initialAge: String,
    onDismiss: () -> Unit,
    onSave: (name: String, age: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var age by remember { mutableStateOf(initialAge) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1B1C1C),
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = "Edit Profile",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", color = Color.White.copy(alpha = 0.7f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White
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
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(name.trim(), age.trim())
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        }
    )
}
