package dev.timekeeper.core

import org.junit.Assert.*
import org.junit.Test

class PickerBoundsTest {
    @Test fun clockTimeUsesZeroToTwentyThreeHours() {
        val bounds = PickerBounds(1439)
        assertEquals(23, bounds.maximumHour)
        assertTrue(bounds.valid(23, 59))
        assertFalse(bounds.valid(24, 0))
    }
    @Test fun fullDayIsSeparateFromTheDurationWheel() {
        val bounds = PickerBounds(1440, allowFullDay = true)
        assertEquals(23, bounds.maximumHour)
        assertFalse(bounds.valid(24, 1))
        assertEquals(1439, bounds.parse("23", "59"))
    }
    @Test fun intervalCannotExceedItsDailyAllowance() {
        val bounds = PickerBounds(70)
        assertTrue(bounds.valid(1, 10))
        assertFalse(bounds.valid(1, 11))
        assertEquals(10, bounds.maximumMinute(1))
    }
    @Test fun cooldownUpperBoundHasOnlyZeroMinutes() {
        val bounds = PickerBounds(10080)
        assertEquals(168, bounds.maximumHour)
        assertEquals(0, bounds.maximumMinute(168))
        assertFalse(bounds.valid(168, 1))
    }
    @Test fun emptyAndMalformedKeypadFieldsAreNotZero() {
        val bounds = PickerBounds(1439)
        assertNull(bounds.parse("", "30"))
        assertNull(bounds.parse("1", ""))
        assertNull(bounds.parse("-1", "0"))
        assertEquals(0, bounds.parse("00", "00"))
    }
}
