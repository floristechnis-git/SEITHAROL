package com.praxper.seitharol.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class DownloadNotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "seitharol_download_channel"
        const val PLAYBACK_NOTIFICATION_ID = 4001
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Song Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgress(notificationId: Int, songTitle: String, progress: Int) {
        val clamped = progress.coerceIn(0, 99)
        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $songTitle")
            .setContentText("$clamped%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, clamped, false)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    fun showCompleted(notificationId: Int, songTitle: String) {
        notificationManager.cancel(notificationId)
        val completedNotification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(songTitle)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        notificationManager.notify(notificationId + 10_000, completedNotification)
    }

    fun showFailed(notificationId: Int, songTitle: String) {
        // CRITICAL: Always cancel ongoing progress notification on error so it never gets stuck
        notificationManager.cancel(notificationId)
        val failedNotification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText(songTitle)
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
        notificationManager.notify(notificationId + 20_000, failedNotification)
    }

    fun clearAllNotifications() {
        notificationManager.cancelAll()
    }
}
