package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppPreferences
import com.example.ui.components.ManipurCalendarNotificationEngine

class BirthdayNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val userName = intent.getStringExtra(BirthdayNotificationScheduler.EXTRA_USER_NAME)
            ?.ifBlank { "Listener" }
            ?: AppPreferences.getInstance(context).settings.value.userName.ifBlank { "Listener" }

        val birthdate = intent.getStringExtra(BirthdayNotificationScheduler.EXTRA_BIRTHDATE)
            ?: AppPreferences.getInstance(context).settings.value.userBirthdate

        Log.d("BirthdayReceiver", "Happy Birthday alarm triggered for $userName at 5:30 AM!")

        // 1. Deliver System Status Bar Notification
        fireBirthdayNotification(context, userName)

        // 2. Dispatch/refresh in-app Notifications center
        runCatching {
            ManipurCalendarNotificationEngine.checkAndDispatchDailyNotification(context)
        }

        // 3. Automatically arm alarm for next year's birthday at 5:30 AM
        if (!birthdate.isNullOrBlank()) {
            BirthdayNotificationScheduler.scheduleBirthdayAlarm(context, userName, birthdate)
        }
    }

    private fun fireBirthdayNotification(context: Context, userName: String) {
        val channelId = "seitharol_birthday_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Birthday Wishes & Celebrations",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Annual personalized Happy Birthday wishes delivered at 5:30 AM"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_NOTIFICATIONS_MODAL", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            BirthdayNotificationScheduler.BIRTHDAY_ALARM_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Happy Birthday $userName! 🎂🎉"
        val body = "Wishing you a wonderful birthday filled with soulful melodies, joy, good health, and memorable moments with Seitharol!"

        val smallIconRes = context.applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_stat_music_note

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(BirthdayNotificationScheduler.BIRTHDAY_ALARM_REQUEST_CODE, notification)
    }
}
