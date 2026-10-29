package dev.timekeeper.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReviewRegressionTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val base = LocalDate.of(2026, 10, 29).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(minute: Long) = Stamp(base + minute * MINUTE, 1_000 + minute * MINUTE, 1)
    private val rule = Rule("app.example", "Example", 30 * MINUTE, MINUTE, 3 * MINUTE)

    @Test fun borrowingWholeSmallIntervalsNeverProducesZeroBudgetAvailable() {
        for (chunk in 1L..2L) {
            val r = rule.copy(chunkMs = chunk * MINUTE)
            var state = AllowanceEngine.consume(r, null, at(0), at(chunk), 0, zone)
            state = requireNotNull(AllowanceEngine.emergency(r, state, at(chunk), 0, zone))
            state = AllowanceEngine.consume(r, state, at(chunk), at(2 * chunk), 0, zone)
            val expiry = chunk + 3
            val blocked = AllowanceEngine.inspect(r, state, at(expiry), 0, zone)
            assertEquals(Gate.COOLDOWN, blocked.gate)
            assertEquals(3 * MINUTE, blocked.cooldownRemainingMs)
            val available = AllowanceEngine.inspect(r, blocked.usage, at(expiry + 3), 0, zone)
            assertEquals(Gate.AVAILABLE, available.gate)
            assertEquals(chunk * MINUTE, available.availableMs)
            assertEquals(2 * chunk * MINUTE, available.usage.usedMs)
        }
    }

    @Test fun lateObservationDoesNotRestartAnAlreadyCompletedDebtCooldown() {
        var used = AllowanceEngine.consume(rule, null, at(0), at(1), 0, zone)
        used = requireNotNull(AllowanceEngine.emergency(rule, used, at(1), 0, zone))
        used = AllowanceEngine.consume(rule, used, at(1), at(2), 0, zone)
        assertEquals(MINUTE, AllowanceEngine.inspect(rule, used, at(30), 0, zone).availableMs)
    }

    @Test fun longCooldownSurvivesResetAndReboot() {
        val r = rule.copy(cooldownMs = 48 * 60 * MINUTE)
        val used = AllowanceEngine.consume(r, null, at(0), at(1), 0, zone)
        val nextDay = AllowanceEngine.inspect(r, used, at(12 * 60), 0, zone)
        assertEquals(0L, nextDay.usage.usedMs)
        assertEquals(Gate.COOLDOWN, nextDay.gate)
        val rebooted = at(12 * 60).copy(elapsedMs = 10_000, boot = 2)
        assertEquals(nextDay.cooldownRemainingMs, AllowanceEngine.inspect(r, used, rebooted, 0, zone).cooldownRemainingMs)
    }

    @Test fun fullBorrowingDebtSurvivesSeveralMissedResets() {
        val r = rule.copy(cooldownMs = 48 * 60 * MINUTE)
        var used = AllowanceEngine.consume(r, null, at(0), at(1), 0, zone)
        used = requireNotNull(AllowanceEngine.emergency(r, used, at(1), 0, zone))
        used = AllowanceEngine.consume(r, used, at(1), at(2), 0, zone)
        assertEquals(Gate.COOLDOWN, AllowanceEngine.inspect(r, used, at(72 * 60), 0, zone).gate)
        val tomorrow = AllowanceEngine.inspect(r, used, at(24 * 60), 0, zone)
        assertNull(AllowanceEngine.emergency(r, tomorrow.usage, at(24 * 60), 0, zone))
    }

    @Test fun resetEditExpiresRemovedAppUsageBeforeRebasing() {
        val old = Usage(AllowanceEngine.periodStart(base - 24 * 60 * MINUTE, 0, zone), 10 * MINUTE)
        val rebased = RuleChanges.rebase(emptyMap(), mapOf(rule.packageName to old), at(0), 0, zone, 694, zone)
        assertEquals(0L, AllowanceEngine.inspect(rule, rebased[rule.packageName], at(0), 694, zone).usage.usedMs)
    }

    @Test fun resetEditPreservesCurrentUsageAndFutureCooldown() {
        val old = Usage(AllowanceEngine.periodStart(base, 0, zone), 10 * MINUTE,
            cooldown = Deadline(base, 1_000, 1, 180 * MINUTE))
        val rebased = RuleChanges.rebase(mapOf(rule.packageName to rule), mapOf(rule.packageName to old), at(0), 0, zone, 694, zone)
        assertEquals(10 * MINUTE, rebased.getValue(rule.packageName).usedMs)
        assertEquals(180 * MINUTE, rebased.getValue(rule.packageName).cooldown!!.remaining(at(0)))
    }

    @Test fun cooldownEditsTakeEffectImmediatelyWithoutErasingUsage() {
        val oldRule = rule.copy(chunkMs = 10 * MINUTE, cooldownMs = 180 * MINUTE)
        val used = AllowanceEngine.consume(oldRule, null, at(0), at(10), 0, zone)
        val none = RuleChanges.apply(oldRule, oldRule.copy(cooldownMs = 0), used, at(20), 0, zone)
        assertNull(none.cooldown)
        assertEquals(10 * MINUTE, none.usedMs)
        val shorter = RuleChanges.apply(oldRule, oldRule.copy(cooldownMs = 30 * MINUTE), used, at(20), 0, zone)
        assertEquals(20 * MINUTE, shorter.cooldown!!.remaining(at(20)))
    }

    @Test fun repeatDaysUseConfiguredResetBoundary() {
        val friday = rule.copy(activeDays = 1 shl 4)
        val before = Stamp(LocalDate.of(2026, 10, 30).atTime(11, 33).atZone(zone).toInstant().toEpochMilli(), 1000, 1)
        val after = before.copy(wallMs = before.wallMs + MINUTE, elapsedMs = 1000 + MINUTE)
        assertEquals(Gate.SCHEDULED_OFF, AllowanceEngine.inspect(friday, null, before, 694, zone).gate)
        assertEquals(Gate.AVAILABLE, AllowanceEngine.inspect(friday, null, after, 694, zone).gate)
    }

    @Test fun offDaysDoNotConsumeOrImportAllowanceButCooldownStillElapses() {
        val weekends = rule.copy(activeDays = Weekdays.WEEKENDS)
        val live = AllowanceEngine.consume(weekends, null, at(0), at(10), 0, zone)
        assertEquals(0L, live.usedMs)
        assertEquals(0L, AllowanceEngine.reconcile(weekends, live, 10 * MINUTE, at(10).wallMs, at(10), 0, zone).usedMs)
    }

    @Test fun repeatDaysCannotBeEmptyOrChangedUnderStrictMode() {
        assertEquals(1, Weekdays.toggle(1, java.time.DayOfWeek.MONDAY))
        assertFalse(RulePolicy.canSave(rule, rule.copy(activeDays = Weekdays.WEEKENDS), true))
        assertThrows(IllegalArgumentException::class.java) { rule.copy(activeDays = 0) }
    }

    @Test fun backupRetryDelayIsBounded() {
        assertEquals(5_000L, BackupRetryPolicy.delayMs(1))
        assertEquals(10_000L, BackupRetryPolicy.delayMs(2))
        assertEquals(300_000L, BackupRetryPolicy.delayMs(100))
    }

    @Test fun availableAlwaysHasPositiveBudgetAroundEmergencyExpiry() {
        for (chunk in 1L..10L) {
            val r = rule.copy(chunkMs = chunk * MINUTE)
            var state = AllowanceEngine.consume(r, null, at(0), at(chunk), 0, zone)
            state = requireNotNull(AllowanceEngine.emergency(r, state, at(chunk), 0, zone))
            state = AllowanceEngine.consume(r, state, at(chunk), at(chunk + minOf(chunk, 2)), 0, zone)
            for (minute in chunk..(chunk + 30)) {
                val status = AllowanceEngine.inspect(r, state, at(minute), 0, zone)
                if (status.gate == Gate.AVAILABLE) assertTrue(status.availableMs > 0)
            }
        }
    }
}
