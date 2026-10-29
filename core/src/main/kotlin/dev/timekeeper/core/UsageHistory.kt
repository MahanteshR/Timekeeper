package dev.timekeeper.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One Android daily statistics bucket, not an arbitrarily clipped time range. */
data class UsageBucket(val packageName: String, val startMs: Long, val endMs: Long, val foregroundMs: Long)

data class UsageDay(val date: LocalDate, val apps: Map<String, Long>, val reported: Boolean) {
    val totalMs: Long get() = apps.values.sum()
}

object UsageHistory {
    /** Android can expand queries to whole intervals. Ignore buckets outside the requested calendar days. */
    fun summarize(buckets: List<UsageBucket>, nowMs: Long, zone: ZoneId, dayCount: Int = 7): List<UsageDay> {
        require(dayCount in 1..31)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val dates = (dayCount - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val byDate = buckets.distinct().filter {
            it.packageName.isNotBlank() && it.foregroundMs >= 0 && it.startMs <= nowMs && it.endMs >= it.startMs
        }.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        return dates.map { date ->
            val samples = byDate[date].orEmpty()
            val apps = samples.groupBy { it.packageName }.mapValues { (_, values) -> values.sumOf { it.foregroundMs } }
                .filterValues { it > 0 }
            UsageDay(date, apps, reported = samples.isNotEmpty())
        }
    }
}
