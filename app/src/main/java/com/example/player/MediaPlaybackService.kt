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
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.MainActivity
import com.example.R
import com.example.data.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service for background audio playback with rich media notification.
 * Supports lock-screen controls, Play/Pause/Skip actions, and ongoing background playback.
 */
class MediaPlaybackService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentArtBitmap: Bitmap? = null
    private var lastLoadedArtUrl: String? = null

    companion object {
        const val CHANNEL_ID = "kanglei_playback_channel"
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
            } catch (e: Exception) {
                // Ignore background start exceptions
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()

            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = try {
                powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KangleiTune::PlaybackWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            } catch (t: Throwable) {
                null
            }

            // Initial foreground notification to satisfy Android 14 requirements
            showInitialNotification()

            // Start listening to PlayerState changes
            serviceScope.launch {
                playerManager?.state?.collect { state ->
                    val track = state.currentTrack
                    if (track != null) {
                        if (state.isPlaying) {
                            try {
                                if (wakeLock?.isHeld != true) {
                                    wakeLock?.acquire(3 * 60 * 60 * 1000L) // 3 hours max
                                }
                            } catch (e: Throwable) {
                                // Ignored
                            }
                        } else {
                            try {
                                if (wakeLock?.isHeld == true) wakeLock?.release()
                            } catch (e: Throwable) {
                                // Ignored
                            }
                        }
                        loadArtAndNotify(track, state.isPlaying)
                    } else {
                        try {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } catch (t: Throwable) {
                            // Ignored
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Do not allow service initialization to crash process
        }
    }

    private fun showInitialNotification() {
        try {
            val openAppIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("Kanglei Tune")
                .setContentText("Ready to play")
                .setContentIntent(openPendingIntent)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Ignore if foreground service restrictions apply
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playerManager?.togglePlayPause()
            ACTION_PREV -> playerManager?.previous()
            ACTION_NEXT -> playerManager?.next()
            ACTION_STOP -> {
                playerManager?.togglePlayPause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadArtAndNotify(track: Track, isPlaying: Boolean) {
        if (track.artUrl != lastLoadedArtUrl) {
            lastLoadedArtUrl = track.artUrl
            serviceScope.launch(Dispatchers.IO) {
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
                updateNotification(track, isPlaying)
            }
        } else {
            updateNotification(track, isPlaying)
        }
    }

    private fun updateNotification(track: Track, isPlaying: Boolean) {
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(if (track.isDownloaded) "Offline" else "Streaming")
            .setContentIntent(openPendingIntent)
            .setOngoing(isPlaying)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cascade Music Playback",
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignored
        }
        serviceScope.cancel()
    }
}
