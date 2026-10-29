package dev.timekeeper.core

import java.time.ZoneId

/** Import only a snapshot belonging to the current allowance period, including after re-adding a rule. */
object UsageImport {
    fun reconcile(rule: Rule, old: Usage?, sessions: List<UsageSession>, asOfMs: Long,
        now: Stamp, resetMinute: Int, zone: ZoneId): Usage {
        val live = AllowanceEngine.inspect(rule, old, now, resetMinute, zone).usage
        val period = AllowanceEngine.periodStart(now.wallMs, resetMinute, zone)
        // Never replay yesterday's cache through today's rule or move the accounting cursor backwards.
        if (asOfMs !in period..now.wallMs || asOfMs < live.accountedThroughMs) return live
        val total = ForegroundHistory.totals(sessions, period, asOfMs)[rule.packageName] ?: 0
        val lastUsed = sessions.asSequence().filter {
            it.packageName == rule.packageName && it.endMs > period && it.startMs < asOfMs
        }.maxOfOrNull { minOf(it.endMs, asOfMs) } ?: period
        val age = now.wallMs - asOfMs
        val asOf = now.copy(wallMs = asOfMs, elapsedMs = (now.elapsedMs - age).coerceAtLeast(0))
        val imported = AllowanceEngine.reconcile(rule, live, total, lastUsed, asOf, resetMinute, zone)
        return AllowanceEngine.inspect(rule, imported, now, resetMinute, zone).usage
    }
}
