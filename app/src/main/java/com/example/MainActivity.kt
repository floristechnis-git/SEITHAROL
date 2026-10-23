package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.data.AppPreferences
import com.example.ui.CascadeApp
import com.example.ui.CascadeViewModel
import com.example.ui.CascadeViewModelFactory
import com.example.ui.components.ManipurCalendarNotificationEngine
import com.example.ui.components.SeitharolDeepLinkHelper
import com.example.ui.screens.OnboardingScreen
import com.example.ui.splash.SplashScreen
import com.example.ui.theme.CascadeTheme
import com.seitharol.data.preferences.AppSettingsManager
import com.seitharol.data.preferences.ThemeMode

class MainActivity : ComponentActivity() {
    private val viewModel: CascadeViewModel by viewModels {
        val app = application as CascadeApplication
        CascadeViewModelFactory(
            app.musicRepository,
            app.downloadRepository,
            app.resolverEngine,
            app.audioPlayerManager
        )
    }

    private var pendingDeepLink by mutableStateOf<SeitharolDeepLinkHelper.DeepLinkTarget?>(null)
    private var openNotificationsRequest by mutableStateOf(false)

    companion object {
        var hasShownSplashThisProcess = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.decorView?.isForceDarkAllowed = false
        }

        // Ensure singleton ExoPlayer is initialized and restores any persisted checkpoint
        val playerMgr = (application as? CascadeApplication)?.audioPlayerManager
        playerMgr?.ensurePlayerInitialized()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Check Manipur Solar/Lunar Calendar & past notification store
        runCatching {
            ManipurCalendarNotificationEngine.checkAndDispatchDailyNotification(applicationContext)
        }

        // Parse initial deep-link or notification intent
        handleIncomingIntent(intent)

        // If returning from notification, deep link, saved instance state, or active/paused playback session,
        // skip the splash screen so the app never feels like it restarted from the beginning.
        val hasActiveOrRestoredTrack = playerMgr?.state?.value?.currentTrack != null
        val launchedFromNotification = intent?.getBooleanExtra("FROM_NOTIFICATION", false) == true ||
            intent?.getBooleanExtra("OPEN_NOTIFICATIONS_MODAL", false) == true
        if (savedInstanceState != null || hasActiveOrRestoredTrack || launchedFromNotification || pendingDeepLink != null) {
            hasShownSplashThisProcess = true
        }

        setContent {
            val context = LocalContext.current
            val appPreferences = remember { AppPreferences(context) }
            val settingsManager = remember { AppSettingsManager.getInstance(context) }

            val userSettings by appPreferences.settings.collectAsState()
            val persistedSettings by settingsManager.state.collectAsState()
            val systemDark = isSystemInDarkTheme()

            val effectiveDark = when (persistedSettings.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> when (userSettings.themeMode.uppercase()) {
                    "LIGHT" -> false
                    "DARK" -> true
                    else -> systemDark
                }
            }

            var isSplashFinished by remember { mutableStateOf(hasShownSplashThisProcess) }

            CascadeTheme(darkTheme = effectiveDark) {
                if (!isSplashFinished) {
                    SplashScreen(
                        onSplashFinished = {
                            isSplashFinished = true
                            hasShownSplashThisProcess = true
                        }
                    )
                } else if (!userSettings.onboarded) {
                    OnboardingScreen(
                        onContinue = { name, age ->
                            appPreferences.saveOnboarding(name, age)
                        }
                    )
                } else {
                    LaunchedEffect(pendingDeepLink) {
                        when (val target = pendingDeepLink) {
                            is SeitharolDeepLinkHelper.DeepLinkTarget.TrackTarget -> {
                                pendingDeepLink = null
                                viewModel.openSharedTrackById(
                                    trackId = target.trackId,
                                    fallbackTitle = target.title,
                                    fallbackArtist = target.artist,
                                    fallbackArtUrl = target.artUrl
                                )
                            }
                            is SeitharolDeepLinkHelper.DeepLinkTarget.ArtistTarget -> {
                                pendingDeepLink = null
                                viewModel.navigateToArtist(target.artistName)
                            }
                            is SeitharolDeepLinkHelper.DeepLinkTarget.AudioFileTarget -> {
                                pendingDeepLink = null
                                viewModel.playExternalAudioUri(
                                    uri = target.uri,
                                    title = target.title,
                                    artist = target.artist,
                                    durationMs = target.durationMs,
                                    album = target.album
                                )
                            }
                            null -> Unit
                        }
                    }
                    CascadeApp(
                        viewModel = viewModel,
                        openNotificationsRequest = openNotificationsRequest,
                        onNotificationsOpened = { openNotificationsRequest = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hasShownSplashThisProcess = true
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("OPEN_NOTIFICATIONS_MODAL", false)) {
            openNotificationsRequest = true
        }
        SeitharolDeepLinkHelper.parseIncomingIntent(intent, this)?.let { target ->
            pendingDeepLink = target
        }
    }

    override fun onStop() {
        super.onStop()
        // Persist current playback position whenever the user leaves the foreground
        runCatching {
            (application as? CascadeApplication)?.audioPlayerManager?.persistCurrentPlaybackState()
        }
    }

    override fun onDestroy() {
        // Do NOT call audioPlayerManager.release() or stopPlayback() when Android reclaims MainActivity
        // in the background; MediaPlaybackService owns background playback lifecycle.
        super.onDestroy()
    }
}
