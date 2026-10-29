package dev.timekeeper.core

import java.time.Instant
import java.time.ZoneId

enum class ForegroundKind { RESUME, PAUSE, STOP, SCREEN_OFF, LOCK, SHUTDOWN, STARTUP }
data class ForegroundEvent(val atMs: Long, val kind: ForegroundKind,
    val packageName: String = "", val activity: String = "")
data class UsageSession(val packageName: String, val startMs: Long, val endMs: Long)

/** Package foreground intervals, unioning overlapping activities instead of counting them twice. */
object ForegroundHistory {
    fun excluding(sessions: List<UsageSession>, covered: List<UsageSession>): List<UsageSession> {
        val byPackage = covered.groupBy { it.packageName }
        return sessions.flatMap { session ->
            var pieces = listOf(session)
            byPackage[session.packageName].orEmpty().forEach { mask ->
                pieces = pieces.flatMap { piece ->
                    if (mask.endMs <= piece.startMs || mask.startMs >= piece.endMs) listOf(piece)
                    else listOfNotNull(
                        if (mask.startMs > piece.startMs) piece.copy(endMs = mask.startMs) else null,
                        if (mask.endMs < piece.endMs) piece.copy(startMs = mask.endMs) else null)
                }
            }
            pieces
        }
    }

    fun sessions(events: List<ForegroundEvent>, nowMs: Long): List<UsageSession> {
        val active = mutableMapOf<String, MutableSet<String>>()
        val starts = mutableMapOf<String, Long>()
        val result = mutableListOf<UsageSession>()
        fun close(pkg: String, at: Long) {
            val start = starts.remove(pkg) ?: return
            if (at > start) result += UsageSession(pkg, start, at)
        }
        var previous = 0L
        events.filter { it.atMs in 0..nowMs }.distinct().sortedBy { it.atMs }.forEach { e ->
            when (e.kind) {
                ForegroundKind.RESUME -> if (e.packageName.isNotBlank()) {
                    val activities = active.getOrPut(e.packageName) { mutableSetOf() }
                    if (activities.isEmpty()) starts[e.packageName] = e.atMs
                    activities += e.activity
                }
                ForegroundKind.PAUSE, ForegroundKind.STOP -> {
                    val activities = active[e.packageName]
                    if (activities?.remove(e.activity) == true && activities.isEmpty()) {
                        close(e.packageName, e.atMs)
                        active.remove(e.packageName)
                    }
                }
                else -> {
                    // A startup with no shutdown event must not charge powered-off time.
                    val end = if (e.kind == ForegroundKind.STARTUP) previous else e.atMs
                    starts.keys.toList().forEach { close(it, end) }
                    active.clear()
                }
            }
            previous = e.atMs
        }
        starts.keys.toList().forEach { close(it, nowMs) }
        return result.sortedBy { it.startMs }
    }

    fun totals(sessions: List<UsageSession>, fromMs: Long, toMs: Long): Map<String, Long> =
        sessions.groupBy { it.packageName }.mapValues { (_, list) ->
            list.sumOf { (minOf(it.endMs, toMs) - maxOf(it.startMs, fromMs)).coerceAtLeast(0) }
        }.filterValues { it > 0 }

    fun days(sessions: List<UsageSession>, nowMs: Long, zone: ZoneId,
        firstEventMs: Long?, dayCount: Int = 7): List<UsageDay> {
        require(dayCount in 1..31)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (dayCount - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = minOf(nowMs, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            UsageDay(date, totals(sessions, from, to), firstEventMs != null && firstEventMs < to)
        }
    }
}

object RulePolicy {
    fun canSave(existing: Rule?, proposed: Rule, strict: Boolean): Boolean =
        !strict || (existing == null && proposed.enabled)
}
