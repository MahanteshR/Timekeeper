package dev.timekeeper.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

const val MINUTE = 60_000L

data class Rule(
    val packageName: String,
    val label: String,
    val dailyMs: Long = 30 * MINUTE,
    val chunkMs: Long = 10 * MINUTE,
    val cooldownMs: Long = 180 * MINUTE,
    val enabled: Boolean = true,
    val activeDays: Int = Weekdays.EVERY_DAY,
) {
    fun withDailyAllowance(value: Long): Rule = copy(dailyMs = value, chunkMs = minOf(chunkMs, value))

    init {
        require(packageName.isNotBlank())
        require(dailyMs in 1..(24 * 60 * MINUTE))
        require(chunkMs in 1..dailyMs)
        require(cooldownMs in 0..(7 * 24 * 60 * MINUTE))
        require(activeDays in 1..Weekdays.EVERY_DAY)
    }
}

data class Stamp(val wallMs: Long, val elapsedMs: Long, val boot: Int)

data class Deadline(val wallMs: Long, val elapsedMs: Long, val boot: Int, val durationMs: Long) {
    fun remaining(now: Stamp): Long {
        val delta = if (now.boot == boot && now.elapsedMs >= elapsedMs) {
            now.elapsedMs - elapsedMs
        } else now.wallMs - wallMs
        return (durationMs - delta.coerceAtLeast(0)).coerceIn(0, durationMs)
    }
}

data class Usage(
    val periodStartMs: Long,
    val usedMs: Long = 0,
    val chunkUsedMs: Long = 0,
    val cooldown: Deadline? = null,
    val emergencyRemainingMs: Long = 0,
    val emergencyConsumedMs: Long = 0,
    val emergencyGranted: Boolean = false,
    val accountedThroughMs: Long = 0,
)

enum class Gate { AVAILABLE, COOLDOWN, DAILY_LIMIT, DISABLED, SCHEDULED_OFF }

data class Allowance(
    val usage: Usage,
    val gate: Gate,
    val availableMs: Long,
    val cooldownRemainingMs: Long,
    val resetAtMs: Long,
)

/** Pure rules; never assumes that unobserved time was spent using an app. */
object AllowanceEngine {
    fun periodStart(nowMs: Long, resetMinute: Int, zone: ZoneId): Long {
        require(resetMinute in 0..1439)
        val local = Instant.ofEpochMilli(nowMs).atZone(zone)
        val reset = LocalTime.of(resetMinute / 60, resetMinute % 60)
        val date = if (local.toLocalTime() < reset) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return date.atTime(reset).atZone(zone).toInstant().toEpochMilli()
    }

    fun nextReset(startMs: Long, resetMinute: Int, zone: ZoneId): Long =
        Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate().plusDays(1)
            .atTime(resetMinute / 60, resetMinute % 60).atZone(zone).toInstant().toEpochMilli()

    fun inspect(rule: Rule, old: Usage?, now: Stamp, resetMinute: Int, zone: ZoneId): Allowance {
        val period = periodStart(now.wallMs, resetMinute, zone)
        var state = old ?: Usage(period)
        if (now.wallMs >= nextReset(state.periodStartMs, resetMinute, zone)) {
            // Daily budgets reset; a cooldown (and borrowed interval debt) can cross midnight.
            val crossesReset = state.cooldown?.let {
                val debtCooldown = if (state.emergencyConsumedMs >= rule.chunkMs) rule.cooldownMs else 0
                it.wallMs + it.durationMs + debtCooldown > period || it.remaining(now) > 0
            } == true
            state = if (crossesReset) state.copy(periodStartMs = period, usedMs = 0,
                emergencyGranted = false, accountedThroughMs = 0) else Usage(period)
        }
        val expired = state.cooldown?.takeIf { it.remaining(now) == 0L }
        if (expired != null) {
            val debt = state.emergencyConsumedMs
            // Borrowing a whole next interval incurs its cooldown from the original expiry,
            // not from whenever the monitor happens to run again.
            val next = if (debt >= rule.chunkMs && rule.cooldownMs > 0) Deadline(
                expired.wallMs + expired.durationMs, expired.elapsedMs + expired.durationMs,
                expired.boot, rule.cooldownMs).takeIf { it.remaining(now) > 0 } else null
            state = state.copy(chunkUsedMs = if (next != null) rule.chunkMs else if (debt >= rule.chunkMs) 0 else debt,
                cooldown = next, emergencyRemainingMs = 0, emergencyConsumedMs = 0)
        }
        if (state.cooldown == null && state.emergencyRemainingMs == 0L && state.chunkUsedMs >= rule.chunkMs) {
            state = if (rule.cooldownMs == 0L) state.copy(chunkUsedMs = state.chunkUsedMs % rule.chunkMs)
            else state.copy(chunkUsedMs = rule.chunkMs,
                cooldown = Deadline(now.wallMs, now.elapsedMs, now.boot, rule.cooldownMs))
        }
        val rest = state.cooldown?.remaining(now) ?: 0
        val dailyLeft = (rule.dailyMs - state.usedMs).coerceAtLeast(0)
        val chunkLeft = (rule.chunkMs - state.chunkUsedMs).coerceAtLeast(0)
        val gate = when {
            !rule.enabled -> Gate.DISABLED
            !Weekdays.includes(rule.activeDays, Instant.ofEpochMilli(period).atZone(zone).dayOfWeek) -> Gate.SCHEDULED_OFF
            dailyLeft == 0L -> Gate.DAILY_LIMIT
            state.emergencyRemainingMs > 0 -> Gate.AVAILABLE
            state.cooldown != null -> Gate.COOLDOWN
            else -> Gate.AVAILABLE
        }
        val available = when {
            gate != Gate.AVAILABLE -> 0L
            state.emergencyRemainingMs > 0 -> minOf(dailyLeft, state.emergencyRemainingMs)
            else -> minOf(dailyLeft, chunkLeft)
        }
        return Allowance(state, gate, available, rest,
            nextReset(state.periodStartMs, resetMinute, zone))
    }

    /** Consume one observed foreground segment, stopping precisely at a budget boundary. */
    fun consume(rule: Rule, old: Usage?, from: Stamp, to: Stamp,
        resetMinute: Int, zone: ZoneId): Usage {
        if (to.boot != from.boot || to.elapsedMs < from.elapsedMs) {
            return inspect(rule, old, to, resetMinute, zone).usage
        }
        val skip = ((old?.accountedThroughMs ?: 0) - from.wallMs)
            .coerceIn(0, to.elapsedMs - from.elapsedMs)
        val observedFrom = from.copy(wallMs = from.wallMs + skip, elapsedMs = from.elapsedMs + skip)
        var status = inspect(rule, old, observedFrom, resetMinute, zone)
        var start = observedFrom
        // Split a live observation at reset, so yesterday's last seconds stay yesterday.
        if (to.wallMs >= status.resetAtMs && observedFrom.wallMs < status.resetAtMs) {
            val before = (status.resetAtMs - observedFrom.wallMs).coerceAtMost(to.elapsedMs - observedFrom.elapsedMs)
            val boundary = Stamp(status.resetAtMs, observedFrom.elapsedMs + before, from.boot)
            status = inspect(rule, spend(rule, status, before, observedFrom), boundary, resetMinute, zone)
            start = boundary
        }
        val result = spend(rule, status, (to.elapsedMs - start.elapsedMs).coerceAtLeast(0), start)
        return inspect(rule, result, to, resetMinute, zone).usage
    }

    private fun spend(rule: Rule, status: Allowance, durationMs: Long, from: Stamp): Usage {
        val state = status.usage
        if (status.gate != Gate.AVAILABLE) return state
        if (rule.cooldownMs == 0L && state.emergencyRemainingMs == 0L) {
            val spent = minOf(durationMs, (rule.dailyMs - state.usedMs).coerceAtLeast(0))
            return state.copy(usedMs = state.usedMs + spent, chunkUsedMs = (state.chunkUsedMs + spent) % rule.chunkMs)
        }
        val spent = minOf(durationMs, status.availableMs)
        if (spent == 0L) return state
        if (state.emergencyRemainingMs > 0) {
            return state.copy(usedMs = state.usedMs + spent,
                emergencyRemainingMs = state.emergencyRemainingMs - spent,
                emergencyConsumedMs = state.emergencyConsumedMs + spent)
        }
        val chunk = state.chunkUsedMs + spent
        val daily = state.usedMs + spent
        if (chunk >= rule.chunkMs && daily < rule.dailyMs) {
            if (rule.cooldownMs == 0L) return state.copy(usedMs = daily, chunkUsedMs = 0)
            val boundary = Stamp(from.wallMs + spent, from.elapsedMs + spent, from.boot)
            return state.copy(usedMs = daily, chunkUsedMs = chunk,
                cooldown = Deadline(boundary.wallMs, boundary.elapsedMs, boundary.boot, rule.cooldownMs))
        }
        return state.copy(usedMs = daily, chunkUsedMs = chunk)
    }

    /** Historical usage is a floor, never an additional charge on top of live tracking. */
    fun reconcile(rule: Rule, old: Usage?, foregroundMs: Long, lastUsedMs: Long,
        asOf: Stamp, resetMinute: Int, zone: ZoneId): Usage {
        val inspected = inspect(rule, old, asOf, resetMinute, zone)
        if (inspected.gate == Gate.SCHEDULED_OFF) return inspected.usage
        val state = inspected.usage
        if (foregroundMs < state.usedMs) return state
        val delta = foregroundMs - state.usedMs
        var next = state.copy(usedMs = foregroundMs,
            accountedThroughMs = maxOf(state.accountedThroughMs, asOf.wallMs))
        if (delta > 0 && state.cooldown == null) {
            val chunk = state.chunkUsedMs + delta
            next = next.copy(chunkUsedMs = if (rule.cooldownMs == 0L) chunk % rule.chunkMs else minOf(chunk, rule.chunkMs))
            if (chunk >= rule.chunkMs && rule.cooldownMs > 0 && foregroundMs < rule.dailyMs) {
                // Previously untracked use starts a cooldown from the last recorded use.
                val age = (asOf.wallMs - lastUsedMs).coerceAtLeast(0)
                val remaining = (rule.cooldownMs - age).coerceAtLeast(0)
                next = if (remaining == 0L) next.copy(chunkUsedMs = 0) else next.copy(
                    cooldown = Deadline(asOf.wallMs, asOf.elapsedMs, asOf.boot, remaining))
            }
        }
        return inspect(rule, next, asOf, resetMinute, zone).usage
    }

    fun emergency(rule: Rule, old: Usage?, now: Stamp, resetMinute: Int, zone: ZoneId,
        requestedMs: Long = 2 * MINUTE): Usage? {
        val status = inspect(rule, old, now, resetMinute, zone)
        if (status.gate != Gate.COOLDOWN || status.usage.emergencyGranted || requestedMs <= 0) return null
        val capacity = (rule.chunkMs - status.usage.emergencyConsumedMs - status.usage.emergencyRemainingMs).coerceAtLeast(0)
        val granted = minOf(requestedMs, capacity, rule.dailyMs - status.usage.usedMs)
        if (granted <= 0) return null
        return status.usage.copy(emergencyGranted = true, emergencyRemainingMs = granted)
    }
}
