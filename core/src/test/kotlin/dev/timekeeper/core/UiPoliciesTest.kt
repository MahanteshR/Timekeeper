package dev.timekeeper.core

import org.junit.Assert.*
import org.junit.Test

class UiPoliciesTest {
    @Test fun notificationSurvivesShadeButClearsOnHomeOrLock() {
        assertEquals("limited", NotificationPolicy.target(null, "limited", true))
        assertEquals("limited", NotificationPolicy.target(null, "limited", false, blockedContext = true))
        assertNull(NotificationPolicy.target(null, "limited", false))
        assertEquals("other", NotificationPolicy.target("other", "limited", true))
    }
    @Test fun hoursWrapAtEachSupportedMaximum() {
        for (maximum in listOf(23, 24, 168)) {
            val count = maximum + 1
            val zero = WheelValues.initialIndex(0, count)
            assertEquals(maximum, WheelValues.valueAt(zero - 1, count))
            assertEquals(0, WheelValues.valueAt(zero + count, count))
            assertEquals(1, WheelValues.valueAt(zero + count + 1, count))
        }
    }
    @Test fun minuteWheelWrapsBothDirections() {
        val zero = WheelValues.initialIndex(0, 60)
        assertEquals(59, WheelValues.valueAt(zero - 1, 60))
        assertEquals(0, WheelValues.valueAt(zero + 60, 60))
        assertEquals(1, WheelValues.valueAt(zero + 61, 60))
        assertEquals(59, WheelValues.valueAt(-1, 60))
    }
    @Test fun reducingDailyAllowanceKeepsRuleValidInOneEdit() {
        val original = Rule("app.test", "Test", dailyMs = 30 * MINUTE, chunkMs = 10 * MINUTE)
        val result = original.withDailyAllowance(MINUTE)
        assertEquals(MINUTE, result.dailyMs)
        assertEquals(MINUTE, result.chunkMs)
        assertEquals(original.cooldownMs, result.cooldownMs)
        assertEquals(10 * MINUTE, original.withDailyAllowance(60 * MINUTE).chunkMs)
    }
    @Test fun shadeDoesNotReleaseBlockedContent() {
        assertEquals(BlockHandoff.HOLD_CURTAIN, BlockHandoffPolicy.decide("blocked", null, false, true))
        assertEquals(BlockHandoff.HOLD_CURTAIN, BlockHandoffPolicy.decide("blocked", "blocked", false, true))
    }
    @Test fun explanationOnlyOpensAfterHomeIsConfirmed() {
        assertEquals(BlockHandoff.OPEN_EXPLANATION, BlockHandoffPolicy.decide("blocked", "launcher", true, true))
        assertEquals(BlockHandoff.RELEASE_CURTAIN, BlockHandoffPolicy.decide("blocked", "other", false, true))
    }
    @Test fun endingCooldownDuringHandoffReleasesCurtain() {
        assertEquals(BlockHandoff.RELEASE_CURTAIN, BlockHandoffPolicy.decide("blocked", null, false, false))
    }
}
