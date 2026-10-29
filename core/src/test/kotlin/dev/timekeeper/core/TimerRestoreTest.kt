package dev.timekeeper.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimerRestoreTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Stamp(Instant.parse("2026-10-29T06:30:00Z").toEpochMilli(), 50_000, 3)
    private val rule = Rule("app.example", "Example")
    private fun config(rules: List<Rule> = listOf(rule), reset: Int = 0, strict: Boolean = false) =
        TimerConfiguration(rules, reset, zone.id, true, false, "SYSTEM", strict)
    private fun usage(used: Long = 7 * MINUTE) = Usage(AllowanceEngine.periodStart(now.wallMs, 0, zone), used, used)

    @Test fun tenRulesRestoreTogetherAndOtherLimitsStay() {
        val rules = (1..10).map { Rule("app.number$it", "App $it") }
        val existing = Rule("app.other", "Other")
        val restored = TimerRestore.plan(config(rules), mapOf(existing.packageName to existing), emptyMap(), 0, zone, false, now)
        assertEquals(11, restored.rules.size)
        assertEquals(existing, restored.rules[existing.packageName])
        assertEquals(10, restored.usage.size)
    }

    @Test fun replacingRulePreservesUsageAndActiveCooldown() {
        val deadline = Deadline(now.wallMs, now.elapsedMs, now.boot, 30 * MINUTE)
        val used = usage().copy(cooldown = deadline)
        val restored = TimerRestore.plan(config(listOf(rule.copy(dailyMs = 60 * MINUTE))),
            mapOf(rule.packageName to rule), mapOf(rule.packageName to used), 0, zone, false, now)
        assertEquals(used, restored.usage[rule.packageName])
        assertEquals(60 * MINUTE, restored.rules.getValue(rule.packageName).dailyMs)
    }

    @Test fun deletedThenRestoredRuleKeepsItsRecordedUsage() {
        val used = usage()
        val restored = TimerRestore.plan(config(), emptyMap(), mapOf(rule.packageName to used), 0, zone, false, now)
        assertEquals(used, restored.usage[rule.packageName])
    }

    @Test fun changingResetPreservesUsageForRestoredAndUnrelatedRules() {
        val other = Rule("app.other", "Other")
        val rules = mapOf(rule.packageName to rule, other.packageName to other)
        val states = rules.mapValues { usage() }
        val restored = TimerRestore.plan(config(reset = 11 * 60 + 34), rules, states, 0, zone, false, now)
        restored.usage.values.forEach {
            assertEquals(7 * MINUTE, it.usedMs)
            assertEquals(AllowanceEngine.periodStart(now.wallMs, 694, zone), it.periodStartMs)
        }
    }

    @Test fun previousDayIsExpiredBeforeApplyingDifferentReset() {
        val yesterday = usage(25 * MINUTE).copy(periodStartMs = usage().periodStartMs - 24 * 60 * MINUTE)
        val restored = TimerRestore.plan(config(reset = 694), mapOf(rule.packageName to rule),
            mapOf(rule.packageName to yesterday), 0, zone, false, now)
        assertEquals(0L, restored.usage.getValue(rule.packageName).usedMs)
    }

    @Test fun smallerIntervalStartsCooldownWithoutGrantingExtraBudget() {
        val restored = TimerRestore.plan(config(listOf(rule.copy(chunkMs = 5 * MINUTE))),
            mapOf(rule.packageName to rule), mapOf(rule.packageName to usage()), 0, zone, false, now)
        val result = restored.usage.getValue(rule.packageName)
        assertEquals(7 * MINUTE, result.usedMs)
        assertEquals(rule.cooldownMs, result.cooldown!!.remaining(now))
    }

    @Test fun activeStrictModeRejectsWholeRestoreWithoutMutatingInput() {
        val rules = mapOf(rule.packageName to rule)
        val states = mapOf(rule.packageName to usage())
        assertThrows(IllegalStateException::class.java) {
            TimerRestore.plan(config(), rules, states, 0, zone, true, now)
        }
        assertEquals(rule, rules[rule.packageName])
        assertEquals(7 * MINUTE, states.getValue(rule.packageName).usedMs)
    }

    @Test fun restoredStrictModeUsesNextResetOnThisDevice() {
        val restored = TimerRestore.plan(config(strict = true), emptyMap(), emptyMap(), 0, zone, false, now)
        val expected = AllowanceEngine.nextReset(usage().periodStartMs, 0, zone) - now.wallMs
        assertEquals(expected, restored.strictUntil!!.remaining(now))
        assertEquals(now.boot, restored.strictUntil!!.boot)
    }

    @Test fun missingAppsAndDisabledRulesAreKept() {
        val absent = rule.copy(enabled = false)
        val restored = TimerRestore.plan(config(listOf(absent)), emptyMap(), emptyMap(), 0, zone, false, now)
        assertEquals(absent, restored.rules[absent.packageName])
    }

    @Test fun invalidConfigurationCannotBePlanned() {
        assertThrows(IllegalArgumentException::class.java) { config(listOf(rule, rule)) }
        assertThrows(IllegalArgumentException::class.java) { config(reset = 1440) }
        assertThrows(IllegalArgumentException::class.java) { config().copy(appearance = "UNKNOWN") }
        assertThrows(IllegalArgumentException::class.java) { config(listOf(rule.copy(packageName = "../invalid"))) }
        assertThrows(IllegalArgumentException::class.java) { config().copy(zoneId = "unknown/zone") }
    }
}
