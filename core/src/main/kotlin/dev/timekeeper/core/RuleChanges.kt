package dev.timekeeper.core

import java.time.DayOfWeek
import java.time.ZoneId

object Weekdays {
    const val EVERY_DAY = 127
    const val WEEKDAYS = 31
    const val WEEKENDS = 96
    fun includes(mask: Int, day: DayOfWeek): Boolean = mask and (1 shl (day.value - 1)) != 0
    fun toggle(mask: Int, day: DayOfWeek): Int = (mask xor (1 shl (day.value - 1))).takeIf { it != 0 } ?: mask
}

object RuleChanges {
    fun apply(previous: Rule?, next: Rule, old: Usage?, now: Stamp, resetMinute: Int, zone: ZoneId): Usage {
        var state = AllowanceEngine.inspect(previous ?: next, old, now, resetMinute, zone).usage
        if (previous != null && previous.cooldownMs != next.cooldownMs) {
            val deadline = state.cooldown
            if (next.cooldownMs == 0L) {
                state = state.copy(cooldown = null, chunkUsedMs = state.chunkUsedMs % next.chunkMs,
                    emergencyRemainingMs = 0, emergencyConsumedMs = 0)
            } else if (deadline != null) {
                val elapsed = (previous.cooldownMs - deadline.remaining(now)).coerceAtLeast(0)
                val remaining = (next.cooldownMs - elapsed).coerceAtLeast(0)
                state = if (remaining == 0L) state.copy(cooldown = null,
                    chunkUsedMs = state.emergencyConsumedMs.coerceAtMost(next.chunkMs),
                    emergencyRemainingMs = 0, emergencyConsumedMs = 0)
                else state.copy(cooldown = Deadline(now.wallMs, now.elapsedMs, now.boot, remaining))
            }
        }
        return AllowanceEngine.inspect(next, state, now, resetMinute, zone).usage
    }

    fun rebase(rules: Map<String, Rule>, usage: Map<String, Usage>, now: Stamp,
        oldReset: Int, oldZone: ZoneId, newReset: Int, newZone: ZoneId): Map<String, Usage> {
        val period = AllowanceEngine.periodStart(now.wallMs, newReset, newZone)
        return usage.mapValues { (key, value) ->
            AllowanceEngine.inspect(rules[key] ?: Rule(key, key), value, now, oldReset, oldZone)
                .usage.copy(periodStartMs = period)
        }
    }
}

object BackupRetryPolicy {
    fun delayMs(failures: Int): Long = (5_000L * (1L shl (failures - 1).coerceIn(0, 6))).coerceAtMost(300_000L)
}
