package dev.timekeeper.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageImportTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val midnight = LocalDate.of(2026, 10, 28).atStartOfDay(zone).toInstant().toEpochMilli()
    private val rule = Rule("app.test", "Test", dailyMs = 10 * MINUTE)
    private fun at(minutes: Long) = Stamp(midnight + minutes * MINUTE, (1440 + minutes) * MINUTE, 1)
    private fun session(start: Long, end: Long) = UsageSession(rule.packageName,
        midnight + start * MINUTE, midnight + end * MINUTE)

    @Test fun customResetCanIncludeYesterdayWhileDashboardUsesMidnight() {
        val sessions = listOf(session(-700, -504), session(600, 610))
        val now = at(692) // 11:32, two minutes before the selected reset.
        val state = UsageImport.reconcile(rule, null, sessions, now.wallMs, now, 694, zone)
        assertEquals(206 * MINUTE, state.usedMs)
        assertEquals(10 * MINUTE, ForegroundHistory.totals(sessions, midnight, now.wallMs)[rule.packageName])
    }

    @Test fun readdingDoesNotChargeTheSameHistoryTwice() {
        val sessions = listOf(session(600, 610))
        val now = at(692)
        val first = UsageImport.reconcile(rule, null, sessions, now.wallMs, now, 0, zone)
        val readded = UsageImport.reconcile(rule, first, sessions, now.wallMs, now, 0, zone)
        assertEquals(10 * MINUTE, readded.usedMs)
        assertEquals(Gate.DAILY_LIMIT, AllowanceEngine.inspect(rule, readded, now, 0, zone).gate)
    }

    @Test fun readdingAfterResetCannotRestoreYesterdaysCachedUsage() {
        val sessions = listOf(session(600, 610))
        val before = at(692)
        val used = UsageImport.reconcile(rule, null, sessions, before.wallMs, before, 694, zone)
        val now = at(695)
        val readded = UsageImport.reconcile(rule, used, sessions, before.wallMs, now, 694, zone)
        assertEquals(0L, readded.usedMs)
        assertEquals(now.wallMs - MINUTE, readded.periodStartMs)
    }

    @Test fun olderSnapshotCannotMoveTheImportCursorBackwards() {
        val now = at(700)
        val live = Usage(midnight, 5 * MINUTE, accountedThroughMs = at(699).wallMs)
        val result = UsageImport.reconcile(rule, live, listOf(session(600, 610)), at(698).wallMs, now, 0, zone)
        assertEquals(live, result)
    }

    @Test fun cachedCooldownIsInspectedAtCurrentTime() {
        val longer = rule.copy(dailyMs = 30 * MINUTE, cooldownMs = MINUTE)
        val result = UsageImport.reconcile(longer, null, listOf(session(600, 610)), at(610).wallMs,
            at(612), 0, zone)
        assertNull(result.cooldown)
        assertEquals(10 * MINUTE, result.usedMs)
        assertEquals(0L, result.chunkUsedMs)
    }
}
