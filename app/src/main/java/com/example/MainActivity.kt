package com.example

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    var isInPiPMode by mutableStateOf(false)
        private set
    private var videoSourceRectHint: android.graphics.Rect? = null

    fun updateVideoSourceRect(rect: android.graphics.Rect) {
        if (!rect.isEmpty) {
            videoSourceRectHint = rect
        }
    }

    fun enterPiPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                isInPiPMode = true
                val rawRatio = viewModel.playerState.value.videoAspectRatio
                    .takeIf { !it.isNaN() && !it.isInfinite() && it > 0.25f } ?: (16f / 9f)
                val clampedRatio = rawRatio.coerceIn(0.42f, 2.38f)
                val builder = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational((clampedRatio * 1000f).toInt(), 1000))
                videoSourceRectHint?.takeIf { !it.isEmpty }?.let { rect ->
                    builder.setSourceRectHint(rect)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setSeamlessResizeEnabled(true)
                }
                val entered = enterPictureInPictureMode(builder.build())
                if (!entered) {
                    isInPiPMode = isInPictureInPictureMode
                }
            }.onFailure {
                isInPiPMode = isInPictureInPictureMode
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val s = viewModel.playerState.value
        if (s.isPlaying && (s.isVideoMode || s.currentTrack?.isVideoTrack == true)) {
            enterPiPMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPiPMode = isInPictureInPictureMode
    }

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
        runCatching { playerMgr?.ensurePlayerInitialized() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            }
        }

        // Run Manipur Solar/Lunar Calendar & past notification store off the UI thread
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                ManipurCalendarNotificationEngine.checkAndDispatchDailyNotification(appCtx)
                com.example.notification.BirthdayNotificationScheduler.scheduleFromPreferences(appCtx)
            }
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
            val view = LocalView.current
            val appPreferences = remember { AppPreferences(context) }
            val settingsManager = remember { AppSettingsManager.getInstance(context) }

            val userSettings by appPreferences.settings.collectAsState()
            val persistedSettings by settingsManager.state.collectAsState()
            val playerState by viewModel.playerState.collectAsState()
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

            // Always On Screen control for Video (default ON) and Music (default OFF)
            val isVideoActive = playerState.isVideoMode || playerState.currentTrack?.isVideoTrack == true
            val isPlaybackActive = playerState.currentTrack != null && (playerState.isPlaying || playerState.isBuffering)
            val shouldKeepScreenOn = isPlaybackActive && (
                if (isVideoActive) persistedSettings.alwaysOnScreenVideo
                else persistedSettings.alwaysOnScreenMusic
            )

            SideEffect {
                view.keepScreenOn = shouldKeepScreenOn
                runCatching {
                    if (shouldKeepScreenOn) {
                        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
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
                        onContinue = { name, birthday, age ->
                            appPreferences.saveOnboarding(name = name, age = age, birthdate = birthday)
                            com.example.notification.BirthdayNotificationScheduler.scheduleBirthdayAlarm(
                                context = context,
                                userName = name,
                                birthdateIso = birthday
                            )
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
                                    album = target.album,
                                    isVideo = target.isVideo
                                )
                            }
                            is SeitharolDeepLinkHelper.DeepLinkTarget.PlaylistTarget -> {
                                pendingDeepLink = null
                                if (target.sourcePlatform == "SEITHAROL_PERSONAL" ||
                                    target.sourcePlatform == "SPOTIFY" ||
                                    target.sourcePlatform == "APPLE_MUSIC"
                                ) {
                                    val reconstructedInput = intent?.dataString ?: target.playlistId
                                    viewModel.importPlaylistFromUrlOrShare(reconstructedInput, target.title)
                                } else {
                                    viewModel.navigateToPlaylist(target.playlistId)
                                }
                            }
                            is SeitharolDeepLinkHelper.DeepLinkTarget.NotFoundTarget -> {
                                pendingDeepLink = null
                                viewModel.navigateToNotFound(target.rawUrl)
                            }
                            null -> Unit
                        }
                    }
                    CascadeApp(
                        viewModel = viewModel,
                        openNotificationsRequest = openNotificationsRequest,
                        onNotificationsOpened = { openNotificationsRequest = false },
                        isInPiPMode = isInPiPMode
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
        runCatching {
            SeitharolDeepLinkHelper.parseIncomingIntent(intent, this)?.let { target ->
                pendingDeepLink = target
            }
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
        if (!isChangingConfigurations) {
            val playerMgr = (application as? CascadeApplication)?.audioPlayerManager
            val isActivelyPlaying = playerMgr?.state?.value?.isPlaying == true ||
                playerMgr?.state?.value?.isBuffering == true
            // Only tear down playback if the user explicitly finished the activity while NOT actively playing
            // in the background. When backgrounded during playback, MediaPlaybackService owns the foreground
            // lifecycle and handles swipe-away via onTaskRemoved().
            if (!isActivelyPlaying && isFinishing) {
                val appCtx = applicationContext
                com.example.player.MediaPlaybackService.stopService(appCtx)
                runCatching { playerMgr?.onAppClearedFromRecents() }
                runCatching {
                    val nm = appCtx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                    nm?.cancel(com.example.player.MediaPlaybackService.NOTIFICATION_ID)
                }
            } else {
                runCatching { playerMgr?.persistCurrentPlaybackState() }
            }
        }
        super.onDestroy()
    }
}
