package dev.timekeeper.app.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.timekeeper.core.Weekdays
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier, color: Color = LocalOneUi.current.secondary) {
    Text(text, modifier = modifier, color = color, style = MaterialTheme.typography.bodyMedium)
}

object UiTime {
    fun time(context: Context, wallMs: Long, zone: ZoneId): String =
        DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(wallMs))
    fun date(context: Context, wallMs: Long, zone: ZoneId): String =
        DateFormat.getMediumDateFormat(context).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(wallMs))
    fun stamp(context: Context, wallMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "${date(context, wallMs, zone)}, ${time(context, wallMs, zone)}"
    fun reset(context: Context, minute: Int, zone: ZoneId): String = time(context,
        LocalDate.now(zone).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli(), zone)
    fun available(context: Context, wallMs: Long, nowMs: Long, zone: ZoneId): String {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val target = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()
        val day = when (target) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> date(context, wallMs, zone) }
        val suffix = if (zone != ZoneId.systemDefault()) " (${zone.id})" else ""
        return "$day at ${time(context, wallMs, zone)}$suffix"
    }
}

fun repeatDaysLabel(mask: Int): String = when (mask) {
    Weekdays.EVERY_DAY -> "Every day"
    Weekdays.WEEKDAYS -> "Weekdays"
    Weekdays.WEEKENDS -> "Weekends"
    else -> localeWeekdays().filter { Weekdays.includes(mask, it) }
        .joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

fun localeWeekdays(): List<DayOfWeek> {
    val first = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0L..6L).map { first.plus(it) }
}
