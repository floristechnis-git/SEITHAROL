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
import android.graphics.drawable.BitmapDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Crash-proof Foreground Service for background audio playback with synchronized media notification.
 *
 * Key fixes:
 * 1. Safe 2-stage foreground promotion (`ServiceCompat.startForeground` with fallback to 2-arg
 *    `startForeground` and `NotificationManager.notify`) so Android 12–15 NEVER throws
 *    `ForegroundServiceDidNotStartInTimeException` or `ForegroundServiceStartNotAllowedException`.
 * 2. Syncs Play/Pause icon and subtext strictly with `isPlaying` and `isBuffering`.
 * 3. Cancels `serviceScope` and sets `isTerminating = true` FIRST in `onTaskRemoved` before stopping
 *    playback, preventing any state emission race from re-posting the notification after swipe-close.
 * 4. Eliminates the 1-second artwork blink on uncached tracks by awaiting fast disk/memory Coil loads
 *    (up to 350ms) before posting, while strictly clearing any prior song's artwork on track change.
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
    private val artBitmapCache = LruCache<String, Bitmap>(24)
    private var currentArtBitmap: Bitmap? = null
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
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, MediaPlaybackService::class.java)
            try {
                val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)
                appCtx.stopService(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveConsistentSmallIcon(): Int {
        return applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_stat_music_note
    }

    private fun acquireWakeAndWifiLocks() {
        lockReleaseJob?.cancel()
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (_: Throwable) {}
    }

    override fun onCreate() {
        super.onCreate()
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
            @Suppress("DEPRECATION")
            wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Seitharol::PlaybackWifiLock")?.apply {
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
                                delay(45_000L)
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
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (!isInForeground || forceStartForeground) {
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
        }
        runCatching {
            nm?.notify(NOTIFICATION_ID, notification)
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
                .size(144, 144)
                .allowHardware(false)
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

        // Switching to a new track without cached artwork: clear previous song's bitmap immediately
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
            // Try fast disk/memory load within 320ms so notification posts with art on the first update (no blink!)
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

            // If network fetch takes longer than 320ms, post notification immediately and update when art arrives
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
            ?: if (currentTrackIdForArt == track.id) currentArtBitmap else null

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
        }
        runCatching {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Set terminating flags and cancel collectors FIRST so onAppClearedFromRecents()
        // cannot trigger a state-collection notification repost!
        isTerminating = true
        isRunning = false
        lockReleaseJob?.cancel()
        artLoadJob?.cancel()
        serviceScope.cancel()
        runCatching {
            playerManager?.onAppClearedFromRecents()
        }
        stopAndRemoveNotification()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isTerminating = true
        isRunning = false
        serviceScope.cancel()
        stopAndRemoveNotification()
        super.onDestroy()
    }
}
