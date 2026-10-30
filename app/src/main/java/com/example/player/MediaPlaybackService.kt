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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Crash-proof Foreground Service for background audio playback with synchronized media notification.
 * Optimized for 2GB RAM phones with bounded RGB_565 notification artwork cache and memory trimming.
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
        @Volatile private var activeInstance: MediaPlaybackService? = null

        fun trimMemory() {
            runCatching {
                activeInstance?.artBitmapCache?.evictAll()
            }
        }

        fun startService(context: Context) {
            if (isRunning) return
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

        fun stopService(context: Context) {
            isRunning = false
            val appCtx = context.applicationContext
            runCatching {
                activeInstance?.isTerminating = true
                activeInstance?.serviceScope?.cancel()
                activeInstance?.stopAndRemoveNotification()
            }
            try {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)
                nm?.cancelAll()
                appCtx.stopService(Intent(appCtx, MediaPlaybackService::class.java))
            } catch (_: Throwable) {
            }
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
        activeInstance = this
        isRunning = true
        isTerminating = false
        createNotificationChannel()

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
            manager?.state
                ?.distinctUntilChanged { old, new ->
                    old.currentTrack?.id == new.currentTrack?.id &&
                        old.currentTrack?.title == new.currentTrack?.title &&
                        old.currentTrack?.artist == new.currentTrack?.artist &&
                        old.currentTrack?.artUrl == new.currentTrack?.artUrl &&
                        old.currentTrack?.isDownloaded == new.currentTrack?.isDownloaded &&
                        old.isPlaying == new.isPlaying &&
                        old.isBuffering == new.isBuffering
                }
                ?.collect { state ->
                    if (isTerminating) return@collect
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
                        loadArtAndNotify(track, state.isPlaying, state.isBuffering)
                    } else {
                        lockReleaseJob?.cancel()
                        stopAndRemoveNotification()
                    }
                }
        }

        // Background watchdog: keeps notification state 100% synchronized with ExoPlayer hardware state
        watchdogJob = serviceScope.launch {
            while (isActive && !isTerminating) {
                delay(2000L)
                if (isTerminating) break
                val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
                mgr?.verifyAndSyncBackgroundPlayback()
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
        if (isTerminating && intent?.action == ACTION_STOP) {
            stopAndRemoveNotification()
            return START_NOT_STICKY
        }
        isRunning = true
        isTerminating = false
        createNotificationChannel()
        if (playerManager == null) {
            playerManager = (application as? com.example.CascadeApplication)?.audioPlayerManager
        }
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
            ACTION_STOP -> {
                playerManager?.pause()
                stopAndRemoveNotification()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundSynchronously()
            }
        }

        if (!isInForeground && !isTerminating) {
            startForegroundSynchronously()
        }
        return START_NOT_STICKY
    }

    private fun promoteOrNotifySafely(notification: Notification, forceStartForeground: Boolean = false) {
        if (isTerminating) return
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
        if (!isTerminating) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            runCatching {
                nm?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun startForegroundSynchronously() {
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
                .setOngoing(isPlaying)
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
        if (isTerminating) return

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
            if (isTerminating) return@launch
            val fastBitmap = withTimeoutOrNull(320L) {
                fetchTrackArtBitmap(track.artUrl)
            }
            if (fastBitmap != null) {
                if (track.id.isNotBlank()) artBitmapCache.put(track.id, fastBitmap)
                if (track.artUrl.isNotBlank()) artBitmapCache.put(track.artUrl, fastBitmap)
                val latest = playerManager?.state?.value
                val latestTrack = latest?.currentTrack ?: track
                if (!isTerminating && latestTrack.id == track.id) {
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
                if (!isTerminating && latestTrack.id == track.id) {
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(resolveConsistentSmallIcon())
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(
                when {
                    isPlaying && track.isDownloaded -> "Offline • Playing"
                    isPlaying -> "Playing"
                    isBuffering -> "Loading..."
                    track.isDownloaded -> "Offline • Paused"
                    else -> "Paused"
                }
            )
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
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )

        val art = (if (track.id.isNotBlank()) artBitmapCache.get(track.id) else null)
            ?: (if (track.artUrl.isNotBlank()) artBitmapCache.get(track.artUrl) else null)
            ?: (if (currentTrackIdForArt == track.id) currentArtBitmap else null)
            ?: getOrDecodeAppLogoBitmap()

        if (art != null) {
            builder.setLargeIcon(art)
        }

        return builder.build()
    }

    private fun updateNotification(track: Track, isPlaying: Boolean, isBuffering: Boolean) {
        if (isTerminating) return
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
                description = "Controls and information for currently playing audio"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
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
            nm?.cancelAll()
        }
        runCatching {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        isTerminating = true
        isRunning = false
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
        runCatching {
            val mgr = playerManager ?: (application as? com.example.CascadeApplication)?.audioPlayerManager
            mgr?.onAppClearedFromRecents()
            com.seitharol.player.AudioPlayerManager.stopActivePreviewIfAny()
        }
        stopAndRemoveNotification()
        super.onDestroy()
    }
}
