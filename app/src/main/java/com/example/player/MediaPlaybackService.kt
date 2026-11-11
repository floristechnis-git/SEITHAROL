package com.example.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import coil.request.ImageRequest
import com.example.MainActivity
import com.example.R
import com.example.data.model.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Crash-proof Foreground Service for background audio & video playback with synchronized media notification.
 * Properly terminates the MediaSession, notification play controls, and background service when the app is closed.
 */
class MediaPlaybackService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception in MediaPlaybackService: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lockReleaseJob: Job? = null
    private var artLoadJob: Job? = null
    private var watchdogJob: Job? = null
    private var mediaSession: MediaSessionCompat? = null
    private val artBitmapCache = LruCache<String, Bitmap>(6)
    private var currentArtBitmap: Bitmap? = null
    private var fallbackLogoBitmap: Bitmap? = null
    private var currentTrackIdForArt: String? = null
    private var lastLoadedArtUrl: String? = null
    @Volatile private var isTerminating = false
    @Volatile private var isInForeground = false

    companion object {
        private const val TAG = "MediaPlaybackService"
        const val CHANNEL_ID = "seitharol_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.example.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.example.ACTION_PREV"
        const val ACTION_NEXT = "com.example.ACTION_NEXT"
        const val ACTION_STOP = "com.example.ACTION_STOP"

        var playerManager: AudioPlayerManager? = null
        @Volatile var isRunning: Boolean = false
        @Volatile private var isShuttingDownApp: Boolean = false
        @Volatile private var activeInstance: MediaPlaybackService? = null

        fun trimMemory() {
            runCatching {
                activeInstance?.artBitmapCache?.evictAll()
            }
        }

        fun startService(context: Context) {
            if (isShuttingDownApp) return
            val mgr = playerManager ?: (context.applicationContext as? com.example.CascadeApplication)?.audioPlayerManager
            if (mgr?.state?.value?.currentTrack == null) return
            if (isRunning && activeInstance != null && activeInstance?.isTerminating == false) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, MediaPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to start MediaPlaybackService from background: ${e.message}")
            }
        }

        fun stopService(context: Context, isAppClosing: Boolean = false) {
            isRunning = false
            if (isAppClosing) {
                isShuttingDownApp = true
            }
            val appCtx = context.applicationContext
            runCatching {
                activeInstance?.isTerminating = true
                activeInstance?.serviceScope?.cancel()
                activeInstance?.stopAndRemoveNotification()
            }
            try {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)
                appCtx.stopService(Intent(appCtx, MediaPlaybackService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun clearShutdownFlag() {
            isShuttingDownApp = false
        }
    }

    private fun resolveConsistentSmallIcon(): Int {
        return applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_stat_music_note
    }

    private fun getOrDecodeAppLogoBitmap(): Bitmap? {
        fallbackLogoBitmap?.let { return it }
        return runCatching {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeResource(resources, R.drawable.ic_splash_logo, opts) ?: run {
                val drawable = ContextCompat.getDrawable(this, R.drawable.ic_splash_logo) ?: return@run null
                val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
        }.getOrNull()?.also { fallbackLogoBitmap = it }
    }

    private fun acquireWakeAndWifiLocks() {
        lockReleaseJob?.cancel()
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(6 * 60 * 60 * 1000L)
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (_: Throwable) {}
    }

    override fun onCreate() {
        super.onCreate()
        if (isShuttingDownApp) {
            stopAndRemoveNotification()
            return
        }
        activeInstance = this
        isRunning = true
        isTerminating = false
        createNotificationChannel()
        ensureMediaSessionInitialized()

        if (playerManager == null) {
            playerManager = (application as? com.example.CascadeApplication)?.audioPlayerManager
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Seitharol::PlaybackWakeLock")?.apply {
                setReferenceCounted(false)
            }
        } catch (t: Throwable) {
            null
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = try {
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiManager?.createWifiLock(lockMode, "Seitharol::PlaybackWifiLock")?.apply {
                setReferenceCounted(false)
            }
        } catch (t: Throwable) {
            null
        }

        startForegroundSynchronously()

        serviceScope.launch {
            val manager = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
            var lastNotifiedTrackId: String? = null
            var lastNotifiedPlaying: Boolean? = null
            var lastNotifiedBuffering: Boolean? = null
            var lastNotifiedDurationBucket: Long = -1L
            var lastNotifiedPositionSec: Long = -1L
            manager?.state
                ?.collect { state ->
                    if (isTerminating || isShuttingDownApp) return@collect
                    val track = state.currentTrack
                    val activePlayback = state.isPlaying || state.isBuffering
                    if (track != null) {
                        if (activePlayback) {
                            acquireWakeAndWifiLocks()
                        } else {
                            lockReleaseJob?.cancel()
                            lockReleaseJob = serviceScope.launch {
                                delay(60_000L)
                                val stillPaused = !(playerManager?.state?.value?.let { it.isPlaying || it.isBuffering } ?: false)
                                if (stillPaused) {
                                    releaseWakeAndWifiLocks()
                                }
                            }
                        }
                        // Always keep MediaSessionCompat position & duration synced for both Audio and Video tracks
                        syncMediaSessionState(track, state.isPlaying, state.isBuffering)
                        val livePlayerDur = runCatching { manager.playerInstance?.duration?.takeIf { it > 0L } }.getOrNull()
                        val effectiveDurationMs = state.durationMs.takeIf { it > 0L }
                            ?: livePlayerDur
                            ?: track.durationMs.takeIf { it > 0L }
                            ?: 210_000L
                        val livePlayerPos = runCatching { manager.playerInstance?.currentPosition?.takeIf { it >= 0L } }.getOrNull()
                        val effectivePosMs = state.currentPositionMs.takeIf { it > 0L } ?: livePlayerPos ?: 0L
                        val durBucket = effectiveDurationMs / 1000L
                        val posSec = effectivePosMs.coerceAtLeast(0L) / 1000L
                        val posJumped = kotlin.math.abs(posSec - lastNotifiedPositionSec) >= 1L
                        val stateChanged = lastNotifiedTrackId != track.id ||
                            lastNotifiedPlaying != state.isPlaying ||
                            lastNotifiedBuffering != state.isBuffering ||
                            lastNotifiedDurationBucket != durBucket ||
                            posJumped
                        if (stateChanged) {
                            lastNotifiedTrackId = track.id
                            lastNotifiedPlaying = state.isPlaying
                            lastNotifiedBuffering = state.isBuffering
                            lastNotifiedDurationBucket = durBucket
                            lastNotifiedPositionSec = posSec
                            loadArtAndNotify(track, state.isPlaying, state.isBuffering)
                        }
                    } else {
                        lockReleaseJob?.cancel()
                        stopAndRemoveNotification()
                    }
                }
        }

        // Background watchdog: keeps notification state 100% synchronized with ExoPlayer hardware state
        watchdogJob = serviceScope.launch {
            while (isActive && !isTerminating && !isShuttingDownApp) {
                delay(1500L)
                if (isTerminating || isShuttingDownApp) break
                val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                mgr?.verifyAndSyncBackgroundPlayback()
                val st = mgr?.state?.value
                val tr = st?.currentTrack
                if (tr != null && (st.isPlaying || st.isBuffering)) {
                    syncMediaSessionState(tr, st.isPlaying, st.isBuffering)
                    updateNotification(tr, st.isPlaying, st.isBuffering)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { artBitmapCache.evictAll() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { artBitmapCache.evictAll() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isTerminating || isShuttingDownApp || intent?.action == ACTION_STOP) {
            if (intent?.action == ACTION_STOP) {
                val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                runCatching { mgr?.stopPlayback() }
            }
            stopAndRemoveNotification()
            return START_NOT_STICKY
        }

        if (playerManager == null) {
            playerManager = (application as? com.example.CascadeApplication)?.audioPlayerManager
        }
        val currentTrack = playerManager?.state?.value?.currentTrack
        if (currentTrack == null && intent?.action == null) {
            stopAndRemoveNotification()
            return START_NOT_STICKY
        }

        isRunning = true
        isTerminating = false
        createNotificationChannel()
        playerManager?.ensurePlayerInitialized()

        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                acquireWakeAndWifiLocks()
                playerManager?.togglePlayPause()
            }
            ACTION_PREV -> {
                acquireWakeAndWifiLocks()
                playerManager?.previous()
            }
            ACTION_NEXT -> {
                acquireWakeAndWifiLocks()
                playerManager?.next()
            }
            else -> {
                startForegroundSynchronously()
            }
        }

        if (!isInForeground && !isTerminating && !isShuttingDownApp) {
            startForegroundSynchronously()
        }
        return START_NOT_STICKY
    }

    private fun promoteOrNotifySafely(notification: Notification, forceStartForeground: Boolean = false) {
        if (isTerminating || isShuttingDownApp) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isInForeground = true
            return
        } catch (_: Throwable) {
            try {
                startForeground(NOTIFICATION_ID, notification)
                isInForeground = true
                return
            } catch (e2: Throwable) {
                Log.w(TAG, "Foreground promotion fallback to notify: ${e2.message}")
            }
        }
        if (!isTerminating && !isShuttingDownApp) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            runCatching {
                nm?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun startForegroundSynchronously() {
        if (isTerminating || isShuttingDownApp) return
        try {
            val currentTrack = playerManager?.state?.value?.currentTrack
            val isPlaying = playerManager?.state?.value?.isPlaying ?: false
            val isBuffering = playerManager?.state?.value?.isBuffering ?: false

            if (currentTrack != null) {
                val notification = buildMediaNotification(currentTrack, isPlaying, isBuffering)
                promoteOrNotifySafely(notification, forceStartForeground = true)
                return
            }

            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("FROM_NOTIFICATION", true)
            }
            val openPendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(resolveConsistentSmallIcon())
                .setContentTitle("Seitharol")
                .setContentText(if (isBuffering) "Loading track..." else "Ready to play")
                .setLargeIcon(getOrDecodeAppLogoBitmap())
                .setContentIntent(openPendingIntent)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            promoteOrNotifySafely(notification, forceStartForeground = true)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed startForegroundSynchronously: ${t.message}", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun fetchTrackArtBitmap(artUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val loader = coil.Coil.imageLoader(this@MediaPlaybackService)
            val request = ImageRequest.Builder(this@MediaPlaybackService)
                .data(artUrl)
                .size(128, 128)
                .allowHardware(false)
                .allowRgb565(true)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .build()
            val result = loader.execute(request).drawable
            (result as? BitmapDrawable)?.bitmap
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadArtAndNotify(track: Track, isPlaying: Boolean, isBuffering: Boolean) {
        if (isTerminating || isShuttingDownApp) return

        val cached = (if (track.id.isNotBlank()) artBitmapCache.get(track.id) else null)
            ?: (if (track.artUrl.isNotBlank()) artBitmapCache.get(track.artUrl) else null)
            ?: (if (currentTrackIdForArt == track.id) currentArtBitmap else null)

        if (cached != null) {
            if (track.id.isNotBlank()) artBitmapCache.put(track.id, cached)
            if (track.artUrl.isNotBlank()) artBitmapCache.put(track.artUrl, cached)
            currentArtBitmap = cached
            currentTrackIdForArt = track.id
            lastLoadedArtUrl = track.artUrl
            updateNotification(track, isPlaying, isBuffering)
            return
        }

        if (currentTrackIdForArt != track.id) {
            currentArtBitmap = null
            currentTrackIdForArt = track.id
            lastLoadedArtUrl = null
        }

        if (track.artUrl.isBlank()) {
            updateNotification(track, isPlaying, isBuffering)
            return
        }

        lastLoadedArtUrl = track.artUrl
        artLoadJob?.cancel()
        artLoadJob = serviceScope.launch {
            if (isTerminating || isShuttingDownApp) return@launch
            val fastBitmap = withTimeoutOrNull(320L) {
                fetchTrackArtBitmap(track.artUrl)
            }
            if (fastBitmap != null) {
                if (track.id.isNotBlank()) artBitmapCache.put(track.id, fastBitmap)
                if (track.artUrl.isNotBlank()) artBitmapCache.put(track.artUrl, fastBitmap)
                val latest = playerManager?.state?.value
                val latestTrack = latest?.currentTrack ?: track
                if (!isTerminating && !isShuttingDownApp && latestTrack.id == track.id) {
                    currentArtBitmap = fastBitmap
                    currentTrackIdForArt = track.id
                    updateNotification(latestTrack, latest?.isPlaying ?: isPlaying, latest?.isBuffering ?: isBuffering)
                }
                return@launch
            }

            updateNotification(track, isPlaying, isBuffering)
            val slowBitmap = fetchTrackArtBitmap(track.artUrl)
            if (slowBitmap != null) {
                if (track.id.isNotBlank()) artBitmapCache.put(track.id, slowBitmap)
                if (track.artUrl.isNotBlank()) artBitmapCache.put(track.artUrl, slowBitmap)
                val latest = playerManager?.state?.value
                val latestTrack = latest?.currentTrack ?: track
                if (!isTerminating && !isShuttingDownApp && latestTrack.id == track.id) {
                    currentArtBitmap = slowBitmap
                    currentTrackIdForArt = track.id
                    updateNotification(latestTrack, latest?.isPlaying ?: isPlaying, latest?.isBuffering ?: isBuffering)
                }
            }
        }
    }

    private fun buildMediaNotification(track: Track, isPlaying: Boolean, isBuffering: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("FROM_NOTIFICATION", true)
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PREV }
        val prevPending = PendingIntent.getService(
            this, 1, prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE }
        val playPausePending = PendingIntent.getService(
            this, 2, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPending = PendingIntent.getService(
            this, 3, nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MediaPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 4, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val art = (if (track.id.isNotBlank()) artBitmapCache.get(track.id) else null)
            ?: (if (track.artUrl.isNotBlank()) artBitmapCache.get(track.artUrl) else null)
            ?: (if (currentTrackIdForArt == track.id) currentArtBitmap else null)
            ?: getOrDecodeAppLogoBitmap()

        ensureMediaSessionInitialized()
        syncMediaSessionState(track, isPlaying, isBuffering, art)

        val pState = playerManager?.state?.value
        val hwPlayer = playerManager?.playerInstance
        val hwDur = runCatching { hwPlayer?.duration?.takeIf { it > 0L } }.getOrNull()
        val hwPos = runCatching { hwPlayer?.currentPosition?.takeIf { it >= 0L } }.getOrNull()
        val durationMs = (pState?.durationMs?.takeIf { it > 0L } ?: hwDur ?: track.durationMs.takeIf { it > 0L } ?: 210_000L).coerceAtLeast(1000L)
        val positionMs = (pState?.currentPositionMs?.takeIf { it > 0L } ?: hwPos ?: 0L).coerceIn(0L, durationMs)
        val maxSec = (durationMs / 1000L).toInt().coerceAtLeast(1)
        val curSec = (positionMs / 1000L).toInt().coerceIn(0, maxSec)
        val isVideoPlaying = pState?.isVideoMode == true || track.isVideoTrack

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setCancelButtonIntent(stopPending)
            .setShowCancelButton(true)
        mediaSession?.sessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(resolveConsistentSmallIcon())
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(
                when {
                    isPlaying && isVideoPlaying -> "Video • Playing"
                    isPlaying && track.isDownloaded -> "Offline • Playing"
                    isPlaying -> "Playing"
                    isBuffering && isVideoPlaying -> "Video • Loading..."
                    isBuffering -> "Loading..."
                    isVideoPlaying -> "Video • Paused"
                    track.isDownloaded -> "Offline • Paused"
                    else -> "Paused"
                }
            )
            .setColor(0xFF850101.toInt())
            .setColorized(true)
            .setProgress(maxSec, curSec, isBuffering && curSec == 0)
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(stopPending)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevPending)
            .addAction(playPauseIcon, playPauseTitle, playPausePending)
            .addAction(R.drawable.ic_skip_next, "Next", nextPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setStyle(mediaStyle)

        if (art != null) {
            builder.setLargeIcon(art)
        }

        return builder.build()
    }

    private fun updateNotification(track: Track, isPlaying: Boolean, isBuffering: Boolean) {
        if (isTerminating || isShuttingDownApp) return
        try {
            val notification = buildMediaNotification(track, isPlaying, isBuffering)
            promoteOrNotifySafely(notification)
        } catch (e: Throwable) {
            Log.e(TAG, "updateNotification failed: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Seitharol Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls and information for currently playing audio and video"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun ensureMediaSessionInitialized() {
        if (mediaSession != null) return
        runCatching {
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("FROM_NOTIFICATION", true)
            }
            val sessionActivityPending = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mediaSession = MediaSessionCompat(this, "SeitharolMediaSession").apply {
                setSessionActivity(sessionActivityPending)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        acquireWakeAndWifiLocks()
                        val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                        if (mgr?.state?.value?.isPlaying != true) {
                            mgr?.togglePlayPause()
                        }
                    }

                    override fun onPause() {
                        val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                        mgr?.pause()
                    }

                    override fun onSkipToNext() {
                        acquireWakeAndWifiLocks()
                        val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                        mgr?.next()
                    }

                    override fun onSkipToPrevious() {
                        acquireWakeAndWifiLocks()
                        val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                        mgr?.previous()
                    }

                    override fun onSeekTo(pos: Long) {
                        val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                        val targetMs = pos.coerceAtLeast(0L)
                        mgr?.seekTo(targetMs)
                        val latestState = mgr?.state?.value
                        val latestTrack = latestState?.currentTrack
                        if (latestTrack != null) {
                            syncMediaSessionState(
                                track = latestTrack,
                                isPlaying = latestState.isPlaying,
                                isBuffering = false,
                                overridePositionMs = targetMs
                            )
                            updateNotification(latestTrack, latestState.isPlaying, false)
                        }
                    }

                    override fun onStop() {
                        val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                        mgr?.stopPlayback()
                        stopAndRemoveNotification()
                    }
                })
                isActive = true
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to initialize MediaSessionCompat: ${e.message}")
        }
    }

    private fun syncMediaSessionState(
        track: Track,
        isPlaying: Boolean,
        isBuffering: Boolean,
        artBitmap: Bitmap? = null,
        overridePositionMs: Long? = null
    ) {
        if (isTerminating || isShuttingDownApp) return
        val session = mediaSession ?: return
        runCatching {
            val pState = playerManager?.state?.value
            val hwPlayer = playerManager?.playerInstance
            val hwDur = runCatching { hwPlayer?.duration?.takeIf { it > 0L } }.getOrNull()
            val hwPos = runCatching { hwPlayer?.currentPosition?.takeIf { it >= 0L } }.getOrNull()
            val durationMs = (pState?.durationMs?.takeIf { it > 0L } ?: hwDur ?: track.durationMs.takeIf { it > 0L } ?: 210_000L).coerceAtLeast(1000L)
            val positionMs = (overridePositionMs ?: pState?.currentPositionMs?.takeIf { it > 0L } ?: hwPos ?: 0L).coerceIn(0L, durationMs)
            val speed = if (isPlaying && !isBuffering) (pState?.playbackSpeed ?: 1f).coerceIn(0.25f, 3f) else 0f

            val art = artBitmap
                ?: (if (track.id.isNotBlank()) artBitmapCache.get(track.id) else null)
                ?: (if (track.artUrl.isNotBlank()) artBitmapCache.get(track.artUrl) else null)
                ?: (if (currentTrackIdForArt == track.id) currentArtBitmap else null)
                ?: getOrDecodeAppLogoBitmap()

            val metaBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album.ifBlank { "Seitharol" })
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            if (art != null) {
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            }
            session.setMetadata(metaBuilder.build())

            val pbStateCode = when {
                isBuffering && !isPlaying -> PlaybackStateCompat.STATE_BUFFERING
                isPlaying -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_PAUSED
            }
            val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP

            val playbackState = PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(pbStateCode, positionMs, speed, SystemClock.elapsedRealtime())
                .build()
            session.setPlaybackState(playbackState)
            if (!session.isActive) {
                session.isActive = true
            }
        }.onFailure { e ->
            Log.w(TAG, "syncMediaSessionState error: ${e.message}")
        }
    }

    private fun releaseWakeAndWifiLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun stopAndRemoveNotification() {
        isTerminating = true
        isRunning = false
        isInForeground = false
        lockReleaseJob?.cancel()
        artLoadJob?.cancel()
        watchdogJob?.cancel()
        releaseWakeAndWifiLocks()
        runCatching {
            mediaSession?.let { session ->
                val stoppedState = PlaybackStateCompat.Builder()
                    .setActions(0L)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f, SystemClock.elapsedRealtime())
                    .build()
                session.setPlaybackState(stoppedState)
                val noneState = PlaybackStateCompat.Builder()
                    .setActions(0L)
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f, SystemClock.elapsedRealtime())
                    .build()
                session.setPlaybackState(noneState)
                session.setMetadata(null)
                session.isActive = false
                session.release()
            }
            mediaSession = null
        }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        }
        runCatching {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        isTerminating = true
        isRunning = false
        isShuttingDownApp = true
        lockReleaseJob?.cancel()
        artLoadJob?.cancel()
        watchdogJob?.cancel()
        serviceScope.cancel()
        runCatching {
            val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
            mgr?.onAppClearedFromRecents()
            com.seitharol.player.AudioPlayerManager.stopActivePreviewIfAny()
        }
        stopAndRemoveNotification()
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isTerminating = true
        isRunning = false
        if (activeInstance === this) activeInstance = null
        lockReleaseJob?.cancel()
        artLoadJob?.cancel()
        watchdogJob?.cancel()
        serviceScope.cancel()
        stopAndRemoveNotification()
        super.onDestroy()
    }
}
