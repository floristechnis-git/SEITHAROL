package com.example.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.AppPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object BirthdayNotificationScheduler {

    private const val TAG = "BirthdayScheduler"
    const val BIRTHDAY_ALARM_REQUEST_CODE = 8827
    const val EXTRA_USER_NAME = "extra_user_name"
    const val EXTRA_BIRTHDATE = "extra_birthdate"

    /**
     * Calculates the next occurrence of the birthday at exactly 05:30:00 AM.
     * If 5:30 AM today has not yet passed, targets today.
     * Otherwise, targets next year's birthday at 5:30 AM.
     */
    fun calculateNextBirthdayTriggerMillis(month: Int, day: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) {
                add(Calendar.YEAR, 1)
            }
        }
        return target.timeInMillis
    }

    /**
     * Schedules the Happy Birthday alarm to fire at 5:30 AM on the given birthdate.
     * Accepts ISO "YYYY-MM-DD" or "MM-DD" formatted string.
     */
    fun scheduleBirthdayAlarm(context: Context, userName: String, birthdateIso: String) {
        if (birthdateIso.isBlank()) return
        val parts = birthdateIso.split("-")
        val (month, day) = when (parts.size) {
            3 -> (parts[1].toIntOrNull() ?: 1) to (parts[2].toIntOrNull() ?: 1)
            2 -> (parts[0].toIntOrNull() ?: 1) to (parts[1].toIntOrNull() ?: 1)
            else -> return
        }

        val triggerAtMillis = calculateNextBirthdayTriggerMillis(month, day)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, BirthdayNotificationReceiver::class.java).apply {
            putExtra(EXTRA_USER_NAME, userName)
            putExtra(EXTRA_BIRTHDATE, birthdateIso)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            BIRTHDAY_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Log.d(TAG, "Scheduled Birthday notification for $userName at $triggerAtMillis (target: 5:30 AM on month $month, day $day)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule exact alarm: ${e.message}")
        }
    }

    /**
     * Reads saved onboarding birthday from AppPreferences and arms the alarm.
     * Called on device boot and app startup.
     */
    fun scheduleFromPreferences(context: Context) {
        val prefs = AppPreferences.getInstance(context)
        val settings = prefs.settings.value
        if (settings.onboarded && settings.userBirthdate.isNotBlank()) {
            scheduleBirthdayAlarm(context, settings.userName, settings.userBirthdate)
        }
    }
}
