package dev.timekeeper.core

data class DurationParts(val days: Int, val hours: Int, val minutes: Int) {
    val totalMinutes: Int get() = days * 1440 + hours * 60 + minutes
}

/** A duration has days and an hour remainder; it is not a time of day. */
class DayDurationBounds(val maximumMinutes: Int) {
    init { require(maximumMinutes in 0..10080) }
    val maximumDay: Int get() = maximumMinutes / 1440
    fun maximumHour(day: Int): Int = if (day == maximumDay) maximumMinutes % 1440 / 60 else 23
    fun maximumMinute(day: Int, hour: Int): Int =
        if (day == maximumDay && hour == maximumHour(day)) maximumMinutes % 60 else 59
    fun valid(day: Int, hour: Int, minute: Int): Boolean = day in 0..maximumDay &&
        hour in 0..maximumHour(day) && minute in 0..maximumMinute(day, hour)
    fun split(totalMinutes: Int): DurationParts {
        require(totalMinutes in 0..maximumMinutes)
        return DurationParts(totalMinutes / 1440, totalMinutes % 1440 / 60, totalMinutes % 60)
    }
    fun parse(days: String, hours: String, minutes: String): DurationParts? {
        val d = days.toIntOrNull() ?: return null
        val h = hours.toIntOrNull() ?: return null
        val m = minutes.toIntOrNull() ?: return null
        return if (valid(d, h, m)) DurationParts(d, h, m) else null
    }
}
