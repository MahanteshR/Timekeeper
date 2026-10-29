package dev.timekeeper.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AllowanceEngineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val base = LocalDate.of(2026, 10, 27).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
    private val rule = Rule("app.test", "Test app")
    private fun at(minutes: Long) = Stamp(base + minutes * MINUTE, 1000 + minutes * MINUTE, 1)

    @Test fun partialUsageSurvivesLeavingAndReturning() {
        val first = AllowanceEngine.consume(rule, null, at(0), at(4), 0, zone)
        val second = AllowanceEngine.consume(rule, first, at(60), at(61), 0, zone)
        val status = AllowanceEngine.inspect(rule, second, at(61), 0, zone)
        assertEquals(5 * MINUTE, status.usage.usedMs)
        assertEquals(5 * MINUTE, status.availableMs)
    }

    @Test fun chunkExhaustionStartsCooldownAtTheBoundary() {
        val used = AllowanceEngine.consume(rule, null, at(0), at(10), 0, zone)
        val status = AllowanceEngine.inspect(rule, used, at(11), 0, zone)
        assertEquals(Gate.COOLDOWN, status.gate)
        assertEquals(179 * MINUTE, status.cooldownRemainingMs)
        assertEquals(0L, status.availableMs)
    }

    @Test fun waitingLongerDoesNotAccumulateSeveralChunks() {
        val used = AllowanceEngine.consume(rule, null, at(0), at(10), 0, zone)
        val status = AllowanceEngine.inspect(rule, used, at(600), 0, zone)
        assertEquals(10 * MINUTE, status.availableMs)
        assertEquals(10 * MINUTE, status.usage.usedMs)
    }

    @Test fun threeChunksExhaustTheDailyAllowance() {
        var state = AllowanceEngine.consume(rule, null, at(0), at(10), 0, zone)
        state = AllowanceEngine.consume(rule, state, at(190), at(200), 0, zone)
        state = AllowanceEngine.consume(rule, state, at(380), at(390), 0, zone)
        val status = AllowanceEngine.inspect(rule, state, at(390), 0, zone)
        assertEquals(Gate.DAILY_LIMIT, status.gate)
        assertEquals(30 * MINUTE, status.usage.usedMs)
    }

    @Test fun lastChunkCanBeSmallerThanTheConfiguredChunk() {
        val custom = rule.copy(dailyMs = 25 * MINUTE)
        var state = AllowanceEngine.consume(custom, null, at(0), at(10), 0, zone)
        state = AllowanceEngine.consume(custom, state, at(190), at(200), 0, zone)
        assertEquals(5 * MINUTE, AllowanceEngine.inspect(custom, state, at(380), 0, zone).availableMs)
    }

    @Test fun emergencyTimeIsBorrowedFromTheNextChunkAndGrantedOnlyOnce() {
        var state = AllowanceEngine.consume(rule, null, at(0), at(10), 0, zone)
        state = requireNotNull(AllowanceEngine.emergency(rule, state, at(11), 0, zone))
        state = AllowanceEngine.consume(rule, state, at(11), at(13), 0, zone)
        val status = AllowanceEngine.inspect(rule, state, at(190), 0, zone)
        assertEquals(12 * MINUTE, status.usage.usedMs)
        assertEquals(8 * MINUTE, status.availableMs)
        assertNull(AllowanceEngine.emergency(rule, state, at(14), 0, zone))
    }

    @Test fun newCalendarDayResetsDailyUsage() {
        val state = AllowanceEngine.consume(rule, null, at(0), at(10), 0, zone)
        val status = AllowanceEngine.inspect(rule, state, at(24 * 60), 0, zone)
        assertEquals(0L, status.usage.usedMs)
        assertEquals(10 * MINUTE, status.availableMs)
    }

    @Test fun earlierUsageCanExhaustDailyBudget() {
        val state = AllowanceEngine.reconcile(rule, null, 120 * MINUTE, at(120).wallMs, at(120), 0, zone)
        assertEquals(Gate.DAILY_LIMIT, AllowanceEngine.inspect(rule, state, at(120), 0, zone).gate)
    }

    @Test fun historyAndLiveOverlapAreNotChargedTwice() {
        val state = AllowanceEngine.reconcile(rule, null, 4 * MINUTE, at(4).wallMs, at(4), 0, zone)
        val live = AllowanceEngine.consume(rule, state, at(3), at(5), 0, zone)
        assertEquals(5 * MINUTE, live.usedMs)
    }

    @Test fun historyNeverReducesLiveUsage() {
        val live = AllowanceEngine.consume(rule, null, at(0), at(5), 0, zone)
        val state = AllowanceEngine.reconcile(rule, live, 3 * MINUTE, at(3).wallMs, at(5), 0, zone)
        assertEquals(5 * MINUTE, state.usedMs)
    }
}
