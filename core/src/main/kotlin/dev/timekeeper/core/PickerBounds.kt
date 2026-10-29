package dev.timekeeper.core

data class PickerBounds(val maximumMinutes: Int, val allowFullDay: Boolean = false) {
    init { require(maximumMinutes in 0..10080) }
    val wheelMaximum: Int get() = if (allowFullDay && maximumMinutes == 1440) 1439 else maximumMinutes
    val maximumHour: Int get() = wheelMaximum / 60
    fun maximumMinute(hour: Int): Int = if (hour == maximumHour) wheelMaximum % 60 else 59
    fun valid(hour: Int, minute: Int): Boolean = hour in 0..maximumHour && minute in 0..maximumMinute(hour)
    fun parse(hours: String, minutes: String): Int? {
        val h = hours.toIntOrNull() ?: return null
        val m = minutes.toIntOrNull() ?: return null
        return if (valid(h, m)) h * 60 + m else null
    }
}
