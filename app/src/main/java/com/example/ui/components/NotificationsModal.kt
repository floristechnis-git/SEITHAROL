package com.example.ui.components

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

data class NotificationItem(
    val id: String,
    val title: String,
    val message: String,
    val timeAgo: String,
    val icon: ImageVector,
    val isSpecialEvent: Boolean = false,
    val isUnread: Boolean = false,
    val timestampMs: Long = 0L
)

/**
 * Manipur Solar & Meitei Lunar Calendar Engine + Past Notification Store & Device Status-Bar Dispatcher.
 *
 * Rules:
 * 1. NEVER shows future / unpassed notifications. Notifications are pulled/created ONLY on or after
 *    their actual date (`date <= today`), and stored in persistent past notification history.
 * 2. Fires a device status-bar notification with the exact same title & text on that date,
 *    enables app icon launcher badges outside the app (`setShowBadge(true)` + `setNumber`),
 *    and exposes `hasUnreadAlerts` / `unreadCount` for the in-app Notification SVG Alert UI.
 */
object ManipurCalendarNotificationEngine {

    private const val SYNODIC_MONTH = 29.530588853
    private const val NEW_MOON_EPOCH_JD = 2451550.1 // 2000-01-06 18:14 UTC
    private const val FESTIVAL_CHANNEL_ID = "seitharol_manipur_alerts_channel"
    private const val PREFS_NAME = "seitharol_calendar_notifs"
    private const val KEY_STORED_NOTIFS = "stored_past_notifications_v2"

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _hasUnreadAlerts = MutableStateFlow(false)
    val hasUnreadAlerts: StateFlow<Boolean> = _hasUnreadAlerts.asStateFlow()

    data class CalendarEventNotification(
        val id: String,
        val title: String,
        val body: String,
        val dateLabel: String,
        val iconName: String,
        val icon: ImageVector,
        val isToday: Boolean,
        val timestampMs: Long,
        val isUnread: Boolean = false
    )

    fun iconFromName(name: String): ImageVector = when (name) {
        "CAKE" -> Icons.Outlined.Cake
        "SUNNY" -> Icons.Outlined.WbSunny
        "EVENT" -> Icons.Outlined.Event
        "CELEBRATION" -> Icons.Outlined.Celebration
        "QUEUE" -> Icons.Outlined.QueueMusic
        "RELEASES" -> Icons.Outlined.NewReleases
        "MUSIC" -> Icons.Outlined.MusicNote
        else -> Icons.Outlined.NotificationsActive
    }

    /**
     * Converts a Gregorian date (year, month 1..12, day 1..31 at 12:00 IST) to Julian Day Number.
     */
    fun toJulianDay(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + 0.5 + b - 1524.5
    }

    /**
     * Returns the lunar age in days (`0.0 .. 29.530588`) and 1-indexed lunar tithi (`1 .. 30`)
     * for the given Gregorian date using standard Julian Day epoch math.
     */
    fun calculateLunarTithi(year: Int, month: Int, day: Int): Pair<Double, Int> {
        val jd = toJulianDay(year, month, day)
        val daysSinceEpoch = jd - NEW_MOON_EPOCH_JD
        var phase = (daysSinceEpoch / SYNODIC_MONTH) % 1.0
        if (phase < 0) phase += 1.0
        val lunarAgeDays = phase * SYNODIC_MONTH
        val tithi = (floor(phase * 30.0).toInt() + 1).coerceIn(1, 30)
        return lunarAgeDays to tithi
    }

    /**
     * Evaluates whether a given date `(year, month, day)` matches a Fixed Solar Date,
     * Johnson's Birthday (Nov 27), or a Meitei Lunar Festival.
     */
    fun getEventForDate(
        year: Int,
        month: Int,
        day: Int,
        isToday: Boolean,
        eventTimestampMs: Long = System.currentTimeMillis()
    ): CalendarEventNotification? {
        val mmDd = String.format(Locale.US, "%02d-%02d", month, day)
        val formattedDate = formatMonthDay(year, month, day)

        // 1. Special Birthday: November 27
        if (mmDd == "11-27") {
            return CalendarEventNotification(
                id = "bday_johnson_$year",
                title = "Happy Birthday Johnson! 🎂",
                body = "Wishing you a wonderful year ahead filled with soulful melodies, good health, boundless creativity, and unforgettable moments with Seitharol!",
                dateLabel = if (isToday) "Today • Nov 27, $year" else "Passed • Nov 27, $year",
                iconName = "CAKE",
                icon = Icons.Outlined.Cake,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
        }

        // 2. Fixed Solar Dates (MM-DD string matching)
        when (mmDd) {
            "02-15" -> return CalendarEventNotification(
                id = "solar_luingaini_$year",
                title = "Happy Lui-Ngai-Ni!",
                body = "Celebrating the Manipuri Naga seed-sowing festival with traditional folk rhythms and unity.",
                dateLabel = if (isToday) "Today • Feb 15, $year" else "Passed • Feb 15, $year",
                iconName = "SUNNY",
                icon = Icons.Outlined.WbSunny,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
            "08-13" -> return CalendarEventNotification(
                id = "solar_patriots_day_$year",
                title = "Patriot's Day (Athoubasinggi Numit)",
                body = "Honoring the brave heroes of Manipur on August 13 with classic patriotic and cultural compositions.",
                dateLabel = if (isToday) "Today • Aug 13, $year" else "Passed • Aug 13, $year",
                iconName = "EVENT",
                icon = Icons.Outlined.Event,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
            "11-01" -> return CalendarEventNotification(
                id = "solar_chavang_kut_$year",
                title = "Happy Chavang Kut!",
                body = "Warm wishes on the autumn post-harvest festival of Kuki-Chin-Mizo communities in Manipur.",
                dateLabel = if (isToday) "Today • Nov 1, $year" else "Passed • Nov 1, $year",
                iconName = "CELEBRATION",
                icon = Icons.Outlined.Celebration,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
            "12-12" -> return CalendarEventNotification(
                id = "solar_nupi_lal_$year",
                title = "Nupi Lal Numit (Dec 12)",
                body = "Saluting the courage and resilience of Manipuri women who stood for justice and dignity.",
                dateLabel = if (isToday) "Today • Dec 12, $year" else "Passed • Dec 12, $year",
                iconName = "EVENT",
                icon = Icons.Outlined.Event,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
        }

        // 3. Meitei Lunar Festivals (Julian Day synodic cycle math)
        val (lunarAge, tithi) = calculateLunarTithi(year, month, day)
        val isNewMoonDay1 = tithi == 1 || lunarAge in 0.2..1.25
        val isNewMoonDay2 = tithi == 2 || lunarAge in 1.25..2.30
        val isFullMoon = tithi == 15 || abs(lunarAge - (SYNODIC_MONTH / 2.0)) <= 0.65

        // Sajibu Nongma Panba (Cheiraoba): Day 1 (Nongma Panba) after first new moon post-equinox (Mar 21 – Apr 20)
        if (isNewMoonDay1 && ((month == 3 && day >= 21) || (month == 4 && day <= 20))) {
            return CalendarEventNotification(
                id = "lunar_cheiraoba_$year",
                title = "Yaiphare Sajibu Nongma Panba Cheiraoba!",
                body = "Day 1 (Nongma Panba) of Sajibu — wishing you prosperity and joy on the Meitei Lunar New Year.",
                dateLabel = if (isToday) "Today • $formattedDate" else "Passed • $formattedDate",
                iconName = "CELEBRATION",
                icon = Icons.Outlined.Celebration,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
        }

        // Yaoshang: Full Moon (Purnima / Thapiba) of Lamta (March)
        if (isFullMoon && month == 3) {
            return CalendarEventNotification(
                id = "lunar_yaoshang_$year",
                title = "Happy Yaoshang Festival!",
                body = "Full moon (Thapiba) of Lamta — celebrate Manipur's vibrant spring festival and Thabal Chongba.",
                dateLabel = if (isToday) "Today • $formattedDate" else "Passed • $formattedDate",
                iconName = "CELEBRATION",
                icon = Icons.Outlined.Celebration,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
        }

        // Mera Hou Chongba: Full Moon (Purnima / Thapiba) of Mera (October)
        if (isFullMoon && month == 10) {
            return CalendarEventNotification(
                id = "lunar_mera_hou_chongba_$year",
                title = "Mera Hou Chongba Wishes!",
                body = "Full moon of Mera — celebrating the timeless bond of brotherhood between hills and valley of Manipur.",
                dateLabel = if (isToday) "Today • $formattedDate" else "Passed • $formattedDate",
                iconName = "CELEBRATION",
                icon = Icons.Outlined.Celebration,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
        }

        // Ningol Chakkouba: Day 2 (Nini Panba) of Hiyangei (late October – November)
        if (isNewMoonDay2 && ((month == 10 && day >= 22) || (month == 11 && day <= 22))) {
            return CalendarEventNotification(
                id = "lunar_ningol_chakkouba_$year",
                title = "Happy Ningol Chakkouba!",
                body = "Day 2 (Nini Panba) of Hiyangei — celebrating love, family blessings, and togetherness across Manipur.",
                dateLabel = if (isToday) "Today • $formattedDate" else "Passed • $formattedDate",
                iconName = "CELEBRATION",
                icon = Icons.Outlined.Celebration,
                isToday = isToday,
                timestampMs = eventTimestampMs
            )
        }

        return null
    }

    private fun formatMonthDay(year: Int, month: Int, day: Int): String {
        val c = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, day)
        }
        return SimpleDateFormat("MMM d, yyyy", Locale.US).format(c.time)
    }

    /**
     * Checks ONLY today and already-passed past dates (NEVER unpassed future dates).
     * Persists passed notifications in SharedPreferences, dispatches a device status-bar
     * notification with the exact same text for any newly pulled notification, and updates
     * both the in-app Alert UI state and outside launcher app icon badge.
     */
    fun checkAndDispatchDailyNotification(context: Context): List<CalendarEventNotification> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedMap = loadStoredNotifications(prefs).toMutableMap()

        val nowCal = Calendar.getInstance()
        val todayYear = nowCal.get(Calendar.YEAR)
        val todayMonth = nowCal.get(Calendar.MONTH) + 1
        val todayDay = nowCal.get(Calendar.DAY_OF_MONTH)
        val todayDateStr = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date())

        var newlyTriggeredEvent: CalendarEventNotification? = null

        // 1. Check if TODAY has a festival or special event
        val todayEvent = getEventForDate(
            year = todayYear,
            month = todayMonth,
            day = todayDay,
            isToday = true,
            eventTimestampMs = nowCal.timeInMillis
        )
        if (todayEvent != null && !storedMap.containsKey(todayEvent.id)) {
            val unreadToday = todayEvent.copy(isUnread = true)
            storedMap[todayEvent.id] = unreadToday
            newlyTriggeredEvent = unreadToday
        } else if (todayEvent != null) {
            val existing = storedMap[todayEvent.id]!!
            storedMap[todayEvent.id] = existing.copy(isToday = true, dateLabel = todayEvent.dateLabel)
        }

        // 2. Backfill ONLY passed past calendar events (scanning backwards 1..300 days in the past, NEVER future!)
        val pastCal = Calendar.getInstance()
        for (daysAgo in 1..300) {
            pastCal.add(Calendar.DAY_OF_YEAR, -1)
            val y = pastCal.get(Calendar.YEAR)
            val m = pastCal.get(Calendar.MONTH) + 1
            val d = pastCal.get(Calendar.DAY_OF_MONTH)
            val pastEv = getEventForDate(
                year = y,
                month = m,
                day = d,
                isToday = false,
                eventTimestampMs = pastCal.timeInMillis
            )
            if (pastEv != null && !storedMap.containsKey(pastEv.id)) {
                storedMap[pastEv.id] = pastEv.copy(isUnread = false)
            } else if (pastEv != null) {
                val existing = storedMap[pastEv.id]!!
                if (existing.isToday) {
                    storedMap[pastEv.id] = existing.copy(isToday = false, dateLabel = pastEv.dateLabel)
                }
            }
        }

        // 3. Ensure today's daily/initial app release notice is pulled on its date and stored
        val dailyReleaseId = "seitharol_release_notice_$todayYear"
        if (!storedMap.containsKey(dailyReleaseId)) {
            val releaseNotice = CalendarEventNotification(
                id = dailyReleaseId,
                title = "New Manipuri & Regional Releases",
                body = "Fresh studio tracks from your favorite artists are ready to stream and save offline.",
                dateLabel = "Pulled on $todayDateStr",
                iconName = "MUSIC",
                icon = Icons.Outlined.MusicNote,
                isToday = true,
                timestampMs = nowCal.timeInMillis - 1000L,
                isUnread = true
            )
            storedMap[dailyReleaseId] = releaseNotice
            if (newlyTriggeredEvent == null) {
                newlyTriggeredEvent = releaseNotice
            }
        }

        val sortedPastList = storedMap.values
            .sortedByDescending { it.timestampMs }

        saveStoredNotifications(prefs, sortedPastList)

        val unreadItems = sortedPastList.filter { it.isUnread }
        _unreadCount.value = unreadItems.size
        _hasUnreadAlerts.value = unreadItems.isNotEmpty()

        // 4. Fire device notification bar alert for newly pulled notification (or first unread notice not yet posted)
        val eventToNotify = newlyTriggeredEvent ?: unreadItems.firstOrNull {
            !prefs.getBoolean("device_bar_posted_${it.id}", false)
        }
        if (eventToNotify != null) {
            val alreadyPosted = prefs.getBoolean("device_bar_posted_${eventToNotify.id}", false)
            if (!alreadyPosted) {
                fireSystemNotification(context, eventToNotify, unreadItems.size.coerceAtLeast(1))
                prefs.edit().putBoolean("device_bar_posted_${eventToNotify.id}", true).apply()
            }
        }

        updateLauncherAppBadge(context, unreadItems.size)
        return sortedPastList
    }

    fun markAllNotificationsRead(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = loadStoredNotifications(prefs).values.map { it.copy(isUnread = false) }
            .sortedByDescending { it.timestampMs }
        saveStoredNotifications(prefs, current)
        _unreadCount.value = 0
        _hasUnreadAlerts.value = false
        updateLauncherAppBadge(context, 0)
    }

    private fun loadStoredNotifications(
        prefs: android.content.SharedPreferences
    ): Map<String, CalendarEventNotification> {
        val raw = prefs.getString(KEY_STORED_NOTIFS, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val map = LinkedHashMap<String, CalendarEventNotification>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isBlank()) continue
                val iconName = obj.optString("iconName", "EVENT")
                map[id] = CalendarEventNotification(
                    id = id,
                    title = obj.optString("title"),
                    body = obj.optString("body"),
                    dateLabel = obj.optString("dateLabel"),
                    iconName = iconName,
                    icon = iconFromName(iconName),
                    isToday = obj.optBoolean("isToday", false),
                    timestampMs = obj.optLong("timestampMs", 0L),
                    isUnread = obj.optBoolean("isUnread", false)
                )
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun saveStoredNotifications(
        prefs: android.content.SharedPreferences,
        list: List<CalendarEventNotification>
    ) {
        val arr = JSONArray()
        list.forEach { ev ->
            arr.put(
                JSONObject().apply {
                    put("id", ev.id)
                    put("title", ev.title)
                    put("body", ev.body)
                    put("dateLabel", ev.dateLabel)
                    put("iconName", ev.iconName)
                    put("isToday", ev.isToday)
                    put("timestampMs", ev.timestampMs)
                    put("isUnread", ev.isUnread)
                }
            )
        }
        prefs.edit().putString(KEY_STORED_NOTIFS, arr.toString()).apply()
    }

    /**
     * Posts a system notification to the device's status bar with the exact same title and body text,
     * and sets the notification badge count so the app icon on the launcher shows an alert badge outside.
     */
    fun fireSystemNotification(
        context: Context,
        event: CalendarEventNotification,
        badgeCount: Int = 1
    ) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    FESTIVAL_CHANNEL_ID,
                    "Seitharol Alerts & Manipur Calendar",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications and alerts from Seitharol and Manipur Cultural Calendar"
                    setShowBadge(true)
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("OPEN_NOTIFICATIONS_MODAL", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                event.id.hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val smallIconRes = context.applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_stat_music_note

            val notification = NotificationCompat.Builder(context, FESTIVAL_CHANNEL_ID)
                .setSmallIcon(smallIconRes)
                .setContentTitle(event.title)
                .setContentText(event.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setNumber(badgeCount.coerceAtLeast(1))
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(event.id.hashCode(), notification)
        }
    }

    /**
     * Updates the launcher app icon badge outside the app across Android OEM launchers.
     */
    private fun updateLauncherAppBadge(context: Context, count: Int) {
        runCatching {
            val launchClassName = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.component?.className ?: MainActivity::class.java.name

            // Standard Samsung / OneUI / generic Android badge broadcast
            val badgeIntent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
                putExtra("badge_count", count)
                putExtra("badge_count_package_name", context.packageName)
                putExtra("badge_count_class_name", launchClassName)
            }
            context.sendBroadcast(badgeIntent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsModal(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val modalSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var calendarEvents by remember {
        mutableStateOf(ManipurCalendarNotificationEngine.checkAndDispatchDailyNotification(context))
    }

    val notifications = remember(calendarEvents) {
        calendarEvents.map { ev ->
            NotificationItem(
                id = ev.id,
                title = ev.title,
                message = ev.body,
                timeAgo = ev.dateLabel,
                icon = ev.icon,
                isSpecialEvent = ev.isToday || ev.id.startsWith("bday_"),
                isUnread = ev.isUnread,
                timestampMs = ev.timestampMs
            )
        }
    }

    val unreadCount = remember(notifications) { notifications.count { it.isUnread } }

    ModalBottomSheet(
        onDismissRequest = {
            ManipurCalendarNotificationEngine.markAllNotificationsRead(context)
            onDismiss()
        },
        sheetState = modalSheetState,
        containerColor = SeitharolBg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 14.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SeitharolHandleBar)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("notifications_modal")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Notifications & Past Alerts",
                        fontFamily = SyneFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = SeitharolDark
                    )
                    Text(
                        text = "Showing today's and past notifications only",
                        fontFamily = OutfitFontFamily,
                        fontSize = 12.sp,
                        color = SeitharolTextSecondary
                    )
                }

                if (unreadCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                ManipurCalendarNotificationEngine.markAllNotificationsRead(context)
                                calendarEvents = calendarEvents.map { it.copy(isUnread = false) }
                            }
                    ) {
                        Text(
                            text = "Mark Read ($unreadCount)",
                            fontSize = 11.sp,
                            fontFamily = OutfitFontFamily,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(notifications, key = { it.id }) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (item.isSpecialEvent || item.isUnread) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            SeitharolWhite
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box {
                                Surface(
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    color = if (item.isSpecialEvent || item.isUnread) MaterialTheme.colorScheme.primary else SeitharolCardElevated
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            tint = if (item.isSpecialEvent || item.isUnread) MaterialTheme.colorScheme.onPrimary else SeitharolDark,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                if (item.isUnread) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .align(Alignment.TopEnd)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFFB74D))
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.title,
                                        fontSize = 14.sp,
                                        fontFamily = SyneFontFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        color = SeitharolDark,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (item.isUnread) {
                                        Surface(
                                            color = Color(0xFFFFB74D).copy(alpha = 0.22f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "ALERT",
                                                fontSize = 9.sp,
                                                fontFamily = OutfitFontFamily,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFFFFB74D),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = item.message,
                                    fontSize = 12.sp,
                                    fontFamily = OutfitFontFamily,
                                    color = SeitharolTextSecondary,
                                    lineHeight = 17.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = item.timeAgo,
                                    fontSize = 11.sp,
                                    fontFamily = OutfitFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
