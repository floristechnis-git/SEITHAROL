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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service for background audio playback with rich media notification.
 * Fully compliant with Android 12–15 (API 31–36) foreground service lifecycle rules so
 * the OS never kills playback mid-song or throws ForegroundServiceDidNotStartInTimeException.
 */
class MediaPlaybackService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception in MediaPlaybackService: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    private var wakeLock: PowerManager.WakeLock? = null
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = try {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Seitharol::PlaybackWakeLock")?.apply {
                setReferenceCounted(false)
            }
        } catch (t: Throwable) {
            null
        }

        startForegroundSynchronously()

        serviceScope.launch {
            playerManager?.state?.collect { state ->
                val track = state.currentTrack
                val activePlayback = state.isPlaying || state.isBuffering
                if (track != null) {
                    if (activePlayback) {
                        try {
                            if (wakeLock?.isHeld != true) {
                                wakeLock?.acquire(3 * 60 * 60 * 1000L)
                            }
                        } catch (_: Throwable) {}
                    } else {
                        try {
                            if (wakeLock?.isHeld == true) wakeLock?.release()
                        } catch (_: Throwable) {}
                    }
                    loadArtAndNotify(track, state.isPlaying, state.isBuffering)
                } else {
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
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val currentTrack = playerManager?.state?.value?.currentTrack
            val isPlaying = playerManager?.state?.value?.isPlaying ?: false
            val isBuffering = playerManager?.state?.value?.isBuffering ?: false

            val title = currentTrack?.title ?: "Seitharol"
            val text = currentTrack?.artist ?: if (isBuffering) "Loading track..." else "Ready to play"

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPendingIntent)
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
            serviceScope.launch(Dispatchers.IO) {
                if (isTerminating) return@launch
                try {
                    val loader = ImageLoader(this@MediaPlaybackService)
                    val request = ImageRequest.Builder(this@MediaPlaybackService)
                        .data(track.artUrl)
                        .allowHardware(false)
                        .build()
                    val result = loader.execute(request).drawable
                    if (result is BitmapDrawable) {
                        currentArtBitmap = result.bitmap
                    }
                } catch (e: Exception) {
                    currentArtBitmap = null
                }
                updateNotification(track, isPlaying, isBuffering)
            }
        } else {
            updateNotification(track, isPlaying, isBuffering)
        }
    }

    private fun updateNotification(track: Track, isPlaying: Boolean, isBuffering: Boolean) {
        if (isTerminating) return
        try {
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
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

            val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            val playPauseTitle = if (isPlaying) "Pause" else "Play"
            val keepForeground = isPlaying || isBuffering

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setSubText(if (track.isDownloaded) "Offline" else if (isBuffering) "Buffering..." else "Streaming")
                .setContentIntent(openPendingIntent)
                .setOngoing(keepForeground)
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

            val notification = builder.build()
            if (keepForeground) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                // Keep notification updated without killing active playback if paused
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
        isTerminating = true
        serviceScope.cancel()
        runCatching {
            playerManager?.stopPlayback()
        }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
            nm?.cancelAll()
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isTerminating = true
        serviceScope.cancel()
        runCatching {
            // Only release WakeLock in onDestroy(); do NOT call playerManager?.stopPlayback() if the OS
            // recycles the service while music is still playing or buffering!
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        super.onDestroy()
    }
}
