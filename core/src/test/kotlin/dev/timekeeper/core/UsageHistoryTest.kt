package dev.timekeeper.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UsageHistoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 27)
    private val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun bucket(date: LocalDate, minutes: Long, app: String = "app.one", timezone: ZoneId = zone) = UsageBucket(
        app, date.atStartOfDay(timezone).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(timezone).toInstant().toEpochMilli() - 1, minutes * MINUTE)

    @Test fun missingHistoryIsNotReportedAsZeroUsage() {
        val days = UsageHistory.summarize(emptyList(), now, zone)
        assertEquals(7, days.size)
        assertEquals(today.minusDays(6), days.first().date)
        assertEquals(today, days.last().date)
        assertTrue(days.none { it.reported })
    }

    @Test fun aReportedZeroIsDifferentFromMissingHistory() {
        val day = UsageHistory.summarize(listOf(bucket(today, 0)), now, zone).last()
        assertTrue(day.reported)
        assertEquals(0L, day.totalMs)
        assertTrue(day.apps.isEmpty())
    }

    @Test fun groupsAppsAndKeepsDifferentDaysSeparate() {
        val days = UsageHistory.summarize(listOf(bucket(today, 10), bucket(today, 20, "app.two"),
            bucket(today.minusDays(1), 7)), now, zone)
        assertEquals(30 * MINUTE, days.last().totalMs)
        assertEquals(10 * MINUTE, days.last().apps["app.one"])
        assertEquals(7 * MINUTE, days[5].totalMs)
    }

    @Test fun identicalReturnedBucketsAreNotCountedTwice() {
        val sample = bucket(today, 10)
        assertEquals(10 * MINUTE, UsageHistory.summarize(listOf(sample, sample), now, zone).last().totalMs)
    }

    @Test fun ignoresExpandedQueryBucketsOutsideRequestedWeek() {
        val days = UsageHistory.summarize(listOf(bucket(today.minusDays(7), 90), bucket(today.plusDays(1), 30),
            bucket(today, 5)), now, zone)
        assertEquals(5 * MINUTE, days.sumOf { it.totalMs })
        assertEquals(1, days.count { it.reported })
    }

    @Test fun assignsDailyBucketsUsingThePhonesCalendarDate() {
        val instant = Instant.parse("2026-10-26T19:00:00Z").toEpochMilli()
        val day = UsageHistory.summarize(listOf(bucket(today, 4)), instant, zone).last()
        assertEquals(today, day.date)
        assertEquals(4 * MINUTE, day.totalMs)
    }

    @Test fun invalidSamplesDoNotCreateUsageOrCoverage() {
        val sample = bucket(today, 1)
        val days = UsageHistory.summarize(listOf(sample.copy(foregroundMs = -1), sample.copy(packageName = ""),
            sample.copy(endMs = sample.startMs - 1), sample.copy(startMs = now + 1, endMs = now + 2)), now, zone)
        assertTrue(days.none { it.reported })
    }

    @Test fun calendarWeekStillContainsSevenDaysAcrossDaylightSavingChange() {
        val berlin = ZoneId.of("Europe/Berlin")
        val time = today.atTime(12, 0).atZone(berlin).toInstant().toEpochMilli()
        val days = UsageHistory.summarize(listOf(bucket(today.minusDays(2), 25, timezone = berlin)), time, berlin)
        assertEquals(7, days.size)
        assertEquals(25 * MINUTE, days.first { it.date == today.minusDays(2) }.totalMs)
    }
}
