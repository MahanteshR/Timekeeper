package dev.timekeeper.core

import org.junit.Assert.*
import org.junit.Test

class DayDurationBoundsTest {
    private val bounds = DayDurationBounds(10080)

    @Test fun everyExistingCooldownRoundTripsWithoutLoss() {
        for (total in 0..10080) {
            val value = bounds.split(total)
            assertTrue(bounds.valid(value.days, value.hours, value.minutes))
            assertEquals(total, value.totalMinutes)
            assertEquals(value, bounds.parse(value.days.toString(), value.hours.toString(), value.minutes.toString()))
        }
    }
    @Test fun maximumIsSevenDaysWithNoExtraHoursOrMinutes() {
        assertEquals(DurationParts(7, 0, 0), bounds.split(168 * 60))
        assertEquals(7, bounds.maximumDay)
        assertEquals(0, bounds.maximumHour(7))
        assertEquals(0, bounds.maximumMinute(7, 0))
        assertFalse(bounds.valid(7, 0, 1))
        assertFalse(bounds.valid(7, 1, 0))
        assertFalse(bounds.valid(8, 0, 0))
    }
    @Test fun hoursAreAlwaysARemainderBelowTwentyFour() {
        assertTrue(bounds.valid(6, 23, 59))
        assertFalse(bounds.valid(0, 24, 0))
        assertEquals(DurationParts(1, 3, 25), bounds.split(27 * 60 + 25))
    }
    @Test fun emptyAndOutOfRangeKeypadValuesAreRejected() {
        assertNull(bounds.parse("", "3", "0"))
        assertNull(bounds.parse("0", "", "0"))
        assertNull(bounds.parse("0", "3", ""))
        assertNull(bounds.parse("-1", "3", "0"))
        assertNull(bounds.parse("0", "3", "60"))
        assertEquals(DurationParts(0, 0, 0), bounds.parse("00", "00", "00"))
    }
    @Test fun partialLastDayUsesTheActualMaximum() {
        val partial = DayDurationBounds(1440 + 70)
        assertEquals(1, partial.maximumHour(1))
        assertEquals(10, partial.maximumMinute(1, 1))
        assertTrue(partial.valid(1, 1, 10))
        assertFalse(partial.valid(1, 1, 11))
    }
}
