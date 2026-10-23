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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import coil.ImageLoader
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

/**
 * Foreground Service for background audio playback with rich media notification.
 *
 * Key fixes:
 * 1. Filters `playerManager.state` via `distinctUntilChanged` on track identity and play/pause/buffering
 *    state so `updateNotification` is NEVER spammed on 200ms `currentPositionMs` ticks.
 * 2. Uses a single consistent small icon (`applicationInfo.icon` fallback to `ic_stat_music_note`)
 *    with `setOnlyAlertOnce(true)` and no duplicate unlinked MediaSession, eliminating the icon
 *    conflict where the music SVG and app icon fought for place.
 * 3. Holds CPU WakeLock and WifiLock with a grace period across track transitions so background
 *    playback never stops when the screen is off.
 */
class MediaPlaybackService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception in MediaPlaybackService: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lockReleaseJob: Job? = null
    private var currentArtBitmap: Bitmap? = null
    private var lastLoadedArtUrl: String? = null
    @Volatile private var isTerminating = false

    companion object {
        private const val TAG = "MediaPlaybackService"
        const val CHANNEL_ID = "seitharol_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.example.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.example.ACTION_PREV"
        const val ACTION_NEXT = "com.example.ACTION_NEXT"
        const val ACTION_STOP = "com.example.ACTION_STOP"

        var playerManager: AudioPlayerManager? = null

        fun startService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "startForegroundService exception: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            try {
                context.stopService(intent)
            } catch (_: Throwable) {
                // Ignore
            }
        }
    }

    private fun resolveConsistentSmallIcon(): Int {
        return applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_stat_music_note
    }

    override fun onCreate() {
        super.onCreate()
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
                    val track = state.currentTrack
                    val activePlayback = state.isPlaying || state.isBuffering
                    if (track != null) {
                        if (activePlayback) {
                            lockReleaseJob?.cancel()
                            try {
                                if (wakeLock?.isHeld != true) {
                                    wakeLock?.acquire(4 * 60 * 60 * 1000L)
                                }
                                if (wifiLock?.isHeld != true) {
                                    wifiLock?.acquire()
                                }
                            } catch (_: Throwable) {}
                        } else {
                            // Delay lock release by 20s so track transitions or brief pauses never drop background CPU/Wi-Fi
                            lockReleaseJob?.cancel()
                            lockReleaseJob = serviceScope.launch {
                                delay(20_000L)
                                val stillPaused = !(playerManager?.state?.value?.let { it.isPlaying || it.isBuffering } ?: false)
                                if (stillPaused) {
                                    try {
                                        if (wakeLock?.isHeld == true) wakeLock?.release()
                                        if (wifiLock?.isHeld == true) wifiLock?.release()
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                        loadArtAndNotify(track, state.isPlaying, state.isBuffering)
                    } else {
                        lockReleaseJob?.cancel()
                        try {
                            ServiceCompat.stopForeground(this@MediaPlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            nm?.cancel(NOTIFICATION_ID)
                        } catch (_: Throwable) {}
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        if (playerManager == null) {
            playerManager = (application as? com.example.CascadeApplication)?.audioPlayerManager
        }
        playerManager?.ensurePlayerInitialized()

        // Always satisfy Android's 5-second startForegroundService contract immediately
        startForegroundSynchronously()

        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playerManager?.togglePlayPause()
            ACTION_PREV -> playerManager?.previous()
            ACTION_NEXT -> playerManager?.next()
            ACTION_STOP -> {
                playerManager?.pause()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startForegroundSynchronously() {
        try {
            val currentTrack = playerManager?.state?.value?.currentTrack
            val isPlaying = playerManager?.state?.value?.isPlaying ?: false
            val isBuffering = playerManager?.state?.value?.isBuffering ?: false

            if (currentTrack != null) {
                val notification = buildMediaNotification(currentTrack, isPlaying, isBuffering)
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
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
                .setOngoing(isPlaying || isBuffering)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed startForegroundSynchronously: ${t.message}", t)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadArtAndNotify(track: Track, isPlaying: Boolean, isBuffering: Boolean) {
        if (isTerminating) return
        if (track.artUrl != lastLoadedArtUrl && track.artUrl.isNotBlank()) {
            lastLoadedArtUrl = track.artUrl
            // Update text & controls immediately before artwork finishes downloading
            updateNotification(track, isPlaying, isBuffering)
            serviceScope.launch(Dispatchers.IO) {
                if (isTerminating) return@launch
                try {
                    val loader = ImageLoader(this@MediaPlaybackService)
                    val request = ImageRequest.Builder(this@MediaPlaybackService)
                        .data(track.artUrl)
                        .size(256, 256)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(request).drawable
                    if (result is BitmapDrawable) {
                        currentArtBitmap = result.bitmap
                    }
                } catch (_: Exception) {
                    currentArtBitmap = null
                }
                val latestState = playerManager?.state?.value
                val latestTrack = latestState?.currentTrack ?: track
                val latestPlaying = latestState?.isPlaying ?: isPlaying
                val latestBuffering = latestState?.isBuffering ?: isBuffering
                if (latestTrack.id == track.id) {
                    updateNotification(latestTrack, latestPlaying, latestBuffering)
                }
            }
        } else {
            updateNotification(track, isPlaying, isBuffering)
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

        val showPauseIcon = isPlaying || isBuffering
        val playPauseIcon = if (showPauseIcon) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (showPauseIcon) "Pause" else "Play"
        val keepForeground = isPlaying || isBuffering

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(resolveConsistentSmallIcon())
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(
                when {
                    track.isDownloaded -> "Offline"
                    isBuffering -> "Buffering..."
                    isPlaying -> "Playing"
                    else -> "Paused"
                }
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(keepForeground)
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

        if (currentArtBitmap != null) {
            builder.setLargeIcon(currentArtBitmap)
        }

        return builder.build()
    }

    private fun updateNotification(track: Track, isPlaying: Boolean, isBuffering: Boolean) {
        if (isTerminating) return
        try {
            val keepForeground = isPlaying || isBuffering
            val notification = buildMediaNotification(track, isPlaying, isBuffering)
            if (keepForeground) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                // Detach foreground requirement when paused so user can swipe away if desired, while updating play/pause state
                runCatching {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                }
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, notification)
            }
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val isActivelyPlaying = playerManager?.state?.value?.let { it.isPlaying || it.isBuffering } == true
        if (!isActivelyPlaying) {
            isTerminating = true
            serviceScope.cancel()
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.cancel(NOTIFICATION_ID)
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
            }
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isTerminating = true
        lockReleaseJob?.cancel()
        serviceScope.cancel()
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
        super.onDestroy()
    }
}
