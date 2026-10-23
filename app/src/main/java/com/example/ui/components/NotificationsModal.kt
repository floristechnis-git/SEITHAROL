package com.example.ui.components

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.ui.theme.*
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

data class NotificationItem(
    val title: String,
    val message: String,
    val timeAgo: String,
    val icon: ImageVector,
    val isSpecialEvent: Boolean = false
)

/**
 * Manipur Solar & Meitei Lunar Calendar Engine + Special Birthday Notification Dispatcher.
 *
 * 1. Fixed Solar Dates (`MM-DD` matching):
 *    - February 15 (`02-15`): Lui-Ngai-Ni (Manipuri Naga Seed Sowing Festival)
 *    - August 13 (`08-13`): Patriot's Day (Athoubasinggi Numit)
 *    - November 1 (`11-01`): Chavang Kut (Post-Harvest Festival)
 *    - November 27 (`11-27`): Happy Birthday Johnson!
 *    - December 12 (`12-12`): Nupi Lal Numit (Manipuri Women's War Memorial Day)
 *
 * 2. Meitei Lunar Festivals (Julian Day epoch + synodic month `29.530588853` days):
 *    - Reference New Moon Epoch: January 6, 2000 18:14 UTC (`JD = 2451550.1`).
 *    - Sajibu Nongma Panba (Cheiraoba): Day 1 (`tithi == 1`, first day after New Moon) of Sajibu
 *      (first new moon falling on or after March 21 through April 20).
 *    - Yaoshang: Full Moon (`Purnima / Thapiba`, `tithi == 15`) of Lamta (falling in March).
 *    - Mera Hou Chongba: Full Moon (`Purnima / Thapiba`, `tithi == 15`) of Mera (falling in October).
 *    - Ningol Chakkouba: Day 2 (`Nini Panba`, `tithi == 2`, second day after New Moon) of Hiyangei
 *      (falling in late October – November).
 */
object ManipurCalendarNotificationEngine {

    private const val SYNODIC_MONTH = 29.530588853
    private const val NEW_MOON_EPOCH_JD = 2451550.1 // 2000-01-06 18:14 UTC
    private const val FESTIVAL_CHANNEL_ID = "seitharol_manipur_calendar_channel"

    data class CalendarEventNotification(
        val id: String,
        val title: String,
        val body: String,
        val dateLabel: String,
        val icon: ImageVector,
        val isToday: Boolean
    )

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
    fun getEventForDate(year: Int, month: Int, day: Int, isToday: Boolean): CalendarEventNotification? {
        val mmDd = String.format(Locale.US, "%02d-%02d", month, day)

        // 1. Special Birthday: November 27
        if (mmDd == "11-27") {
            return CalendarEventNotification(
                id = "bday_johnson_$year",
                title = "Happy Birthday Johnson! 🎂",
                body = "Wishing you a wonderful year ahead filled with soulful melodies, good health, boundless creativity, and unforgettable moments with Seitharol!",
                dateLabel = if (isToday) "Today • Nov 27" else "Nov 27",
                icon = Icons.Outlined.Cake,
                isToday = isToday
            )
        }

        // 2. Fixed Solar Dates (MM-DD string matching)
        when (mmDd) {
            "02-15" -> return CalendarEventNotification(
                id = "solar_luingaini_$year",
                title = "Happy Lui-Ngai-Ni!",
                body = "Celebrating the Manipuri Naga seed-sowing festival with traditional folk rhythms and unity.",
                dateLabel = if (isToday) "Today • Feb 15" else "Feb 15 (Fixed Solar)",
                icon = Icons.Outlined.WbSunny,
                isToday = isToday
            )
            "08-13" -> return CalendarEventNotification(
                id = "solar_patriots_day_$year",
                title = "Patriot's Day (Athoubasinggi Numit)",
                body = "Honoring the brave heroes of Manipur on August 13 with classic patriotic and cultural compositions.",
                dateLabel = if (isToday) "Today • Aug 13" else "Aug 13 (Fixed Solar)",
                icon = Icons.Outlined.Event,
                isToday = isToday
            )
            "11-01" -> return CalendarEventNotification(
                id = "solar_chavang_kut_$year",
                title = "Happy Chavang Kut!",
                body = "Warm wishes on the autumn post-harvest festival of Kuki-Chin-Mizo communities in Manipur.",
                dateLabel = if (isToday) "Today • Nov 1" else "Nov 1 (Fixed Solar)",
                icon = Icons.Outlined.Celebration,
                isToday = isToday
            )
            "12-12" -> return CalendarEventNotification(
                id = "solar_nupi_lal_$year",
                title = "Nupi Lal Numit (Dec 12)",
                body = "Saluting the courage and resilience of Manipuri women who stood for justice and dignity.",
                dateLabel = if (isToday) "Today • Dec 12" else "Dec 12 (Fixed Solar)",
                icon = Icons.Outlined.Event,
                isToday = isToday
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
                dateLabel = if (isToday) "Today • Sajibu Nongma Panba" else "$year-$mmDd (Meitei Lunar)",
                icon = Icons.Outlined.Celebration,
                isToday = isToday
            )
        }

        // Yaoshang: Full Moon (Purnima / Thapiba) of Lamta (March)
        if (isFullMoon && month == 3) {
            return CalendarEventNotification(
                id = "lunar_yaoshang_$year",
                title = "Happy Yaoshang Festival!",
                body = "Full moon (Thapiba) of Lamta — celebrate Manipur's vibrant spring festival and Thabal Chongba.",
                dateLabel = if (isToday) "Today • Lamta Thapiba" else "$year-$mmDd (Meitei Lunar)",
                icon = Icons.Outlined.Celebration,
                isToday = isToday
            )
        }

        // Mera Hou Chongba: Full Moon (Purnima / Thapiba) of Mera (October)
        if (isFullMoon && month == 10) {
            return CalendarEventNotification(
                id = "lunar_mera_hou_chongba_$year",
                title = "Mera Hou Chongba Wishes!",
                body = "Full moon of Mera — celebrating the timeless bond of brotherhood between hills and valley of Manipur.",
                dateLabel = if (isToday) "Today • Mera Full Moon" else "$year-$mmDd (Meitei Lunar)",
                icon = Icons.Outlined.Celebration,
                isToday = isToday
            )
        }

        // Ningol Chakkouba: Day 2 (Nini Panba) of Hiyangei (late October – November)
        if (isNewMoonDay2 && ((month == 10 && day >= 22) || (month == 11 && day <= 22))) {
            return CalendarEventNotification(
                id = "lunar_ningol_chakkouba_$year",
                title = "Happy Ningol Chakkouba!",
                body = "Day 2 (Nini Panba) of Hiyangei — celebrating love, family blessings, and togetherness across Manipur.",
                dateLabel = if (isToday) "Today • Hiyangei Nini Panba" else "$year-$mmDd (Meitei Lunar)",
                icon = Icons.Outlined.Celebration,
                isToday = isToday
            )
        }

        return null
    }

    /**
     * Checks if today matches any Manipur Solar/Lunar festival or November 27 (Johnson's Birthday)
     * and fires a system notification once per day. Also returns the active + upcoming calendar events
     * for the in-app Notifications sheet.
     */
    fun checkAndDispatchDailyNotification(context: Context): List<CalendarEventNotification> {
        val cal = Calendar.getInstance()
        val todayYear = cal.get(Calendar.YEAR)
        val todayMonth = cal.get(Calendar.MONTH) + 1
        val todayDay = cal.get(Calendar.DAY_OF_MONTH)

        val todayEvent = getEventForDate(todayYear, todayMonth, todayDay, isToday = true)
        if (todayEvent != null) {
            val prefs = context.getSharedPreferences("seitharol_calendar_notifs", Context.MODE_PRIVATE)
            val alreadyFired = prefs.getBoolean(todayEvent.id, false)
            if (!alreadyFired) {
                fireSystemNotification(context, todayEvent)
                prefs.edit().putBoolean(todayEvent.id, true).apply()
            }
        }

        // Scan upcoming 90 days so users can preview upcoming Manipur Lunar & Solar festivals and Nov 27 Birthday
        val events = mutableListOf<CalendarEventNotification>()
        if (todayEvent != null) events.add(todayEvent)

        val scanCal = Calendar.getInstance()
        for (offset in 1..90) {
            scanCal.add(Calendar.DAY_OF_YEAR, 1)
            val y = scanCal.get(Calendar.YEAR)
            val m = scanCal.get(Calendar.MONTH) + 1
            val d = scanCal.get(Calendar.DAY_OF_MONTH)
            val ev = getEventForDate(y, m, d, isToday = false)
            if (ev != null && events.none { it.title == ev.title }) {
                events.add(ev)
            }
        }
        return events
    }

    private fun fireSystemNotification(context: Context, event: CalendarEventNotification) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    FESTIVAL_CHANNEL_ID,
                    "Manipur Festivals & Special Events",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifications for Manipur Solar & Meitei Lunar festivals and birthdays"
                }
                nm.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, FESTIVAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music_note)
                .setContentTitle(event.title)
                .setContentText(event.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            nm.notify(event.id.hashCode(), notification)
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

    val calendarEvents = remember {
        ManipurCalendarNotificationEngine.checkAndDispatchDailyNotification(context)
    }

    val notifications = remember(calendarEvents) {
        val specialItems = calendarEvents.map { ev ->
            NotificationItem(
                title = ev.title,
                message = ev.body,
                timeAgo = ev.dateLabel,
                icon = ev.icon,
                isSpecialEvent = ev.isToday || ev.id.startsWith("bday_")
            )
        }
        specialItems + listOf(
            NotificationItem(
                title = "New Manipuri & Regional Releases",
                message = "Fresh studio tracks from your favorite artists are ready to stream.",
                timeAgo = "1 day ago",
                icon = Icons.Outlined.MusicNote
            ),
            NotificationItem(
                title = "Your Weekly Mix has been updated",
                message = "Personalized Automix queue refreshed based on your recent listening.",
                timeAgo = "3 days ago",
                icon = Icons.Outlined.QueueMusic
            ),
            NotificationItem(
                title = "Discover & Download Offline",
                message = "Swipe vertical previews in Discover and save 320kbps tracks offline.",
                timeAgo = "5 days ago",
                icon = Icons.Outlined.NewReleases
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
            Text(
                text = "Notifications & Manipur Calendar",
                fontFamily = SyneFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                color = SeitharolDark,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                items(notifications) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (item.isSpecialEvent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            SeitharolWhite
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = if (item.isSpecialEvent) MaterialTheme.colorScheme.primary else SeitharolCardElevated
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                        tint = if (item.isSpecialEvent) MaterialTheme.colorScheme.onPrimary else SeitharolDark,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontSize = 14.sp,
                                    fontFamily = SyneFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SeitharolDark
                                )
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
