package dev.timekeeper.core

import java.time.ZoneId

/** Portable settings only: no usage, clock anchors, live deadlines, or permission grants. */
data class TimerConfiguration(
    val rules: List<Rule>,
    val resetMinute: Int,
    val zoneId: String,
    val notifications: Boolean,
    val emergency: Boolean,
    val appearance: String,
    val strict: Boolean,
) {
    init {
        require(rules.size <= 500) { "Too many app limits in this backup." }
        require(rules.all { it.dailyMs >= MINUTE && it.chunkMs >= MINUTE &&
            it.dailyMs % MINUTE == 0L && it.chunkMs % MINUTE == 0L && it.cooldownMs % MINUTE == 0L }) {
            "Backup timer settings must use whole minutes."
        }
        require(rules.map { it.packageName }.distinct().size == rules.size) { "Duplicate apps in this backup." }
        require(rules.all { it.packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) &&
            it.packageName.length <= 255 && it.label.isNotBlank() && it.label.length <= 200 }) { "Invalid app details in this backup." }
        require(resetMinute in 0..1439) { "Invalid reset time in this backup." }
        require(runCatching { ZoneId.of(zoneId) }.isSuccess) { "Invalid time zone in this backup." }
        require(appearance in setOf("SYSTEM", "LIGHT", "DARK")) { "Invalid appearance in this backup." }
        require(!strict || rules.isNotEmpty()) { "Strict mode needs at least one app limit." }
    }
}

data class RestoredTimers(
    val rules: Map<String, Rule>,
    val usage: Map<String, Usage>,
    val strictUntil: Deadline?,
)

object TimerRestore {
    /** Apply all rules together, retain other limits, and never restore old usage over live usage. */
    fun plan(config: TimerConfiguration, rules: Map<String, Rule>, usage: Map<String, Usage>,
        resetMinute: Int, zone: ZoneId, strictActive: Boolean, now: Stamp): RestoredTimers {
        check(!strictActive) { "Wait until strict mode ends before restoring a setup." }
        val restoredRules = rules + config.rules.associateBy { it.packageName }
        val restoredZone = ZoneId.of(config.zoneId)
        val newPeriod = AllowanceEngine.periodStart(now.wallMs, config.resetMinute, restoredZone)
        val changedReset = resetMinute != config.resetMinute || zone != restoredZone
        val kept = usage.mapValues { (key, value) ->
            // First expire yesterday under the original reset, then preserve today's accounting.
            val rule = rules[key] ?: restoredRules[key] ?: Rule(key, key)
            val inspected = AllowanceEngine.inspect(rule, value, now, resetMinute, zone).usage
            if (changedReset) inspected.copy(periodStartMs = newPeriod) else inspected
        }.toMutableMap()
        config.rules.forEach { rule ->
            val state = RuleChanges.apply(rules[rule.packageName], rule, kept[rule.packageName], now,
                config.resetMinute, restoredZone)
            kept[rule.packageName] = state
        }
        val strictUntil = if (config.strict) Deadline(now.wallMs, now.elapsedMs, now.boot,
            AllowanceEngine.nextReset(newPeriod, config.resetMinute, restoredZone) - now.wallMs) else null
        return RestoredTimers(restoredRules, kept, strictUntil)
    }
}
