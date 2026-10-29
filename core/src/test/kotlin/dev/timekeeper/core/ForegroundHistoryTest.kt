package dev.timekeeper.core

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ForegroundHistoryTest {
    private fun event(time: Long, kind: ForegroundKind, activity: String = "Main") =
        ForegroundEvent(time, kind, "app.test", activity)

    @Test fun overlappingActivitiesCountOnce() {
        val sessions = ForegroundHistory.sessions(listOf(event(10, ForegroundKind.RESUME),
            event(20, ForegroundKind.RESUME, "Other"), event(30, ForegroundKind.PAUSE),
            event(40, ForegroundKind.STOP, "Other")), 50)
        assertEquals(listOf(UsageSession("app.test", 10, 40)), sessions)
    }
    @Test fun lockClosesOpenSession() {
        assertEquals(20L, ForegroundHistory.totals(ForegroundHistory.sessions(listOf(
            event(10, ForegroundKind.RESUME), event(30, ForegroundKind.LOCK)), 100), 0, 100)["app.test"])
    }
    @Test fun openSessionIsClippedToQuery() {
        val sessions = ForegroundHistory.sessions(listOf(event(10, ForegroundKind.RESUME)), 100)
        assertEquals(40L, ForegroundHistory.totals(sessions, 60, 120)["app.test"])
    }
    @Test fun blockingCoverageSubtractsOnlyMatchingPackage() {
        val sessions = listOf(UsageSession("app.test", 0, 100), UsageSession("other", 0, 100))
        val covered = listOf(UsageSession("app.test", 20, 40), UsageSession("app.test", 30, 60))
        val totals = ForegroundHistory.totals(ForegroundHistory.excluding(sessions, covered), 0, 100)
        assertEquals(60L, totals["app.test"])
        assertEquals(100L, totals["other"])
    }
    @Test fun midnightSplitsSession() {
        val days = ForegroundHistory.days(listOf(UsageSession("app.test", 86_000_000, 87_000_000)),
            90_000_000, ZoneId.of("UTC"), 0, 2)
        assertEquals(400_000L, days.first().totalMs)
        assertEquals(600_000L, days.last().totalMs)
    }
    @Test fun strictAllowsOnlyEnabledNewRules() {
        val rule = Rule("app.test", "Test")
        assertTrue(RulePolicy.canSave(null, rule, true))
        assertFalse(RulePolicy.canSave(null, rule.copy(enabled = false), true))
        assertFalse(RulePolicy.canSave(rule, rule.copy(dailyMs = 60 * MINUTE), true))
        assertTrue(RulePolicy.canSave(rule, rule, false))
    }
}
