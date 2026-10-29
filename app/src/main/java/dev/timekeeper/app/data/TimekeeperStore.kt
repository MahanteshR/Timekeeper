package dev.timekeeper.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.timekeeper.core.*
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class Appearance { SYSTEM, LIGHT, DARK }

data class Preferences(
    val resetMinute: Int = 0,
    val zoneId: String = ZoneId.systemDefault().id,
    val strictUntil: Deadline? = null,
    val notifications: Boolean = false,
    val emergency: Boolean = false,
    val appearance: Appearance = Appearance.SYSTEM,
)

data class Snapshot(
    val rules: Map<String, Rule> = emptyMap(),
    val usage: Map<String, Usage> = emptyMap(),
    val preferences: Preferences = Preferences(),
    val storageIssue: String? = null,
    val covered: List<UsageSession> = emptyList(),
)

/** Anchors calendar time to elapsed time during each boot, resisting manual clock edits. */
class DeviceClock(context: Context) {
    private val prefs = context.getSharedPreferences("timekeeper-clock", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private val anchorElapsed: Long
    private val anchorWall: Long
    init {
        val elapsed = SystemClock.elapsedRealtime()
        if (prefs.getInt("boot", -1) == boot && prefs.getLong("elapsed", Long.MAX_VALUE) <= elapsed) {
            anchorElapsed = prefs.getLong("elapsed", elapsed)
            anchorWall = prefs.getLong("wall", System.currentTimeMillis())
        } else {
            anchorElapsed = elapsed
            anchorWall = System.currentTimeMillis()
            prefs.edit().putInt("boot", boot).putLong("elapsed", anchorElapsed)
                .putLong("wall", anchorWall).commit()
        }
    }
    fun now(): Stamp {
        val elapsed = SystemClock.elapsedRealtime()
        return Stamp(anchorWall + elapsed - anchorElapsed, elapsed, boot)
    }
}

class TimekeeperStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val disk = context.getSharedPreferences("timekeeper", Context.MODE_PRIVATE)
    val clock = DeviceClock(context)
    private val mutable = MutableStateFlow(load())
    val snapshots = mutable.asStateFlow()
    val current: Snapshot get() = mutable.value
    val zone: ZoneId get() = ZoneId.of(current.preferences.zoneId)
    private val historyMutable = MutableStateFlow<UsageReport?>(null)
    val history = historyMutable.asStateFlow()
    private val refreshingMutable = MutableStateFlow(false)
    val refreshingHistory = refreshingMutable.asStateFlow()
    private val historyMutex = Mutex()
    private var historyAttemptElapsed = -30_000L
    private var weekAttemptElapsed = -60_000L
    private var latestAllowanceReport: UsageReport? = null
    var lastError: String? = null
        private set
    private fun fail(message: String): Boolean { lastError = message; return false }
    val backups = TimerBackups(appContext, this)

    init { backups.start() }

    fun configuration(snapshot: Snapshot = current): TimerConfiguration {
        val p = snapshot.preferences
        return TimerConfiguration(snapshot.rules.values.sortedBy { it.packageName }, p.resetMinute, p.zoneId,
            p.notifications, p.emergency, p.appearance.name,
            p.strictUntil?.remaining(clock.now())?.let { it > 0 } ?: false)
    }

    /** One durable write; no partial restore and no resetting today's usage. Called on the main thread. */
    fun restoreConfiguration(configuration: TimerConfiguration): Int {
        val homes = appContext.packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet() + appContext.packageName
        require(configuration.rules.none { it.packageName in homes }) { "Timekeeper and the current Home app cannot have limits. Choose another backup." }
        val old = current
        val now = clock.now()
        val restored = TimerRestore.plan(configuration, old.rules, old.usage,
            old.preferences.resetMinute, zone, isStrict(), now)
        val p = Preferences(configuration.resetMinute, configuration.zoneId, restored.strictUntil,
            configuration.notifications, configuration.emergency, Appearance.valueOf(configuration.appearance))
        val next = old.copy(rules = restored.rules, usage = restored.usage, preferences = p, storageIssue = null)
        // Reconcile with a fresh report after the transaction. An old report may use a different reset/zone.
        check(publish(next, durable = true)) { "The setup could not be saved. Your previous setup is unchanged." }
        historyMutable.value = null
        latestAllowanceReport = null
        return configuration.rules.size
    }

    suspend fun refreshHistory(force: Boolean = false, includeWeek: Boolean = true) = historyMutex.withLock {
        val elapsed = SystemClock.elapsedRealtime()
        if (!force && (if (includeWeek) elapsed - weekAttemptElapsed < 60_000 else elapsed - historyAttemptElapsed < 30_000)) return@withLock
        historyAttemptElapsed = elapsed
        if (includeWeek) weekAttemptElapsed = elapsed
        refreshingMutable.value = true
        try {
            val covered = current.covered
            val now = clock.now()
            val start = if (includeWeek) null else AllowanceEngine.periodStart(now.wallMs, current.preferences.resetMinute, zone) - 24 * 60 * MINUTE
            val report = withContext(Dispatchers.IO) { readUsageReport(appContext, now.wallMs, covered, start) }
            latestAllowanceReport = report
            if (report.accessGranted && report.error == null && current.storageIssue == null) {
                val old = current
                val usage = old.usage + old.rules.mapValues { (_, rule) -> importUsage(rule, old.usage[rule.packageName], report) }
                if (usage != old.usage) publish(old.copy(usage = usage))
            }
            val cached = historyMutable.value
            if (includeWeek) historyMutable.value = if (report.error != null && cached?.accessGranted == true && cached.days.isNotEmpty())
                cached.copy(error = report.error) else report
        } finally {
            refreshingMutable.value = false
        }
    }

    private fun importUsage(rule: Rule, old: Usage?, report: UsageReport): Usage {
        val now = clock.now()
        val period = AllowanceEngine.periodStart(now.wallMs, current.preferences.resetMinute, zone)
        if (!report.accessGranted || report.error != null || report.asOfMs < period)
            return AllowanceEngine.inspect(rule, old, now, current.preferences.resetMinute, zone).usage
        return UsageImport.reconcile(rule, old, report.sessions, report.asOfMs, now,
            current.preferences.resetMinute, zone)
    }

    fun status(rule: Rule, at: Stamp = clock.now()): Allowance = AllowanceEngine.inspect(
        rule, current.usage[rule.packageName], at, current.preferences.resetMinute, zone)

    fun refresh(at: Stamp = clock.now()) {
        val old = current
        val usage = old.usage + old.rules.mapValues { (_, rule) -> status(rule, at).usage }
        val strict = old.preferences.strictUntil?.takeIf { it.remaining(at) > 0 }
        val next = old.copy(usage = usage, preferences = old.preferences.copy(strictUntil = strict))
        if (next != old) publish(next)
    }

    fun consume(packageName: String, from: Stamp, to: Stamp) {
        val old = current
        val rule = old.rules[packageName] ?: return
        val state = AllowanceEngine.consume(rule, old.usage[packageName], from, to,
            old.preferences.resetMinute, zone)
        if (state != old.usage[packageName]) publish(old.copy(usage = old.usage + (packageName to state)))
    }

    fun recordCovered(packageName: String, fromMs: Long, toMs: Long) {
        if (current.storageIssue != null || toMs <= fromMs) return
        val kept = current.covered.filter { it.endMs >= toMs - 8 * 24 * 60 * MINUTE }
        val last = kept.lastOrNull()
        val next = if (last?.packageName == packageName && fromMs <= last.endMs + 1)
            kept.dropLast(1) + last.copy(endMs = maxOf(last.endMs, toMs))
        else kept + UsageSession(packageName, fromMs, toMs)
        publish(current.copy(covered = next))
    }

    fun isStrict(): Boolean = current.preferences.strictUntil?.remaining(clock.now())?.let { it > 0 } ?: false

    fun saveRule(rule: Rule): Boolean {
        lastError = null
        if (current.storageIssue != null) return fail("Saved data is unavailable. Restore a backup first.")
        if (!RulePolicy.canSave(current.rules[rule.packageName], rule, isStrict())) return fail("Limits are locked until the next reset.")
        val old = current
        var state = RuleChanges.apply(old.rules[rule.packageName], rule, old.usage[rule.packageName],
            clock.now(), old.preferences.resetMinute, zone)
        (latestAllowanceReport ?: historyMutable.value)?.let { state = importUsage(rule, state, it) }
        return publish(old.copy(rules = old.rules + (rule.packageName to rule),
            usage = old.usage + (rule.packageName to state)), durable = true)
    }

    fun removeRule(packageName: String): Boolean {
        lastError = null
        if (isStrict()) return fail("Limits are locked until the next reset.")
        if (current.storageIssue != null) return fail("Saved data is unavailable. Restore a backup first.")
        // Keep usage history locally so removing and re-adding doesn't silently reset today.
        return publish(current.copy(rules = current.rules - packageName), durable = true)
    }

    fun setPreferences(value: Preferences): Boolean {
        lastError = null
        if (current.storageIssue != null) return fail("Saved data is unavailable. Restore a backup first.")
        val old = current.preferences
        if (isStrict() && (value.resetMinute != old.resetMinute || value.zoneId != old.zoneId ||
                value.emergency != old.emergency || value.strictUntil != old.strictUntil)) return fail("These settings are locked until the next reset.")
        var usage = current.usage
        if (value.resetMinute != old.resetMinute || value.zoneId != old.zoneId) {
            usage = RuleChanges.rebase(current.rules, usage, clock.now(), old.resetMinute, ZoneId.of(old.zoneId),
                value.resetMinute, ZoneId.of(value.zoneId))
            latestAllowanceReport = null
        }
        return publish(current.copy(preferences = value, usage = usage), durable = true)
    }

    fun activateStrict(): Boolean {
        if (isStrict() || current.rules.isEmpty()) return false
        val now = clock.now()
        val p = current.preferences
        val start = AllowanceEngine.periodStart(now.wallMs, p.resetMinute, zone)
        val duration = AllowanceEngine.nextReset(start, p.resetMinute, zone) - now.wallMs
        return setPreferences(p.copy(strictUntil = Deadline(now.wallMs, now.elapsedMs, now.boot, duration)))
    }

    fun grantEmergency(packageName: String): Boolean {
        val old = current
        if (!old.preferences.emergency) return false
        val rule = old.rules[packageName] ?: return false
        val state = AllowanceEngine.emergency(rule, old.usage[packageName], clock.now(),
            old.preferences.resetMinute, zone) ?: return false
        publish(old.copy(usage = old.usage + (packageName to state)))
        return true
    }

    private fun publish(value: Snapshot, durable: Boolean = false): Boolean {
        val edit = disk.edit().putString("snapshot", encode(value).toString())
        if (durable) { if (!edit.commit()) return fail("Could not save changes. Check free storage and try again.") } else edit.apply()
        mutable.value = value
        return true
    }

    private fun load(): Snapshot {
        val raw = disk.getString("snapshot", null) ?: return Snapshot()
        return try {
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val rules = root.getJSONArray("rules").objects().map { r -> Rule(
                r.getString("package"), r.getString("label"), r.getLong("daily"), r.getLong("chunk"),
                r.getLong("cooldown"), r.getBoolean("enabled"), r.optInt("days", Weekdays.EVERY_DAY)) }.associateBy { it.packageName }
            val usage = root.getJSONObject("usage").let { all -> all.keys().asSequence().associateWith { key ->
                val u = all.getJSONObject(key)
                Usage(u.getLong("period"), u.getLong("used"), u.getLong("chunk"), readDeadline(u, "cooldown"),
                    u.optLong("emergencyRemaining"), u.optLong("emergencyConsumed"), u.optBoolean("emergencyGranted"),
                    u.optLong("accountedThrough"))
            } }
            val p = root.getJSONObject("preferences")
            val preferences = Preferences(p.getInt("reset"), p.getString("zone"), readDeadline(p, "strict"),
                p.optBoolean("notifications"), p.optBoolean("emergency"), Appearance.valueOf(p.getString("appearance")))
            require(preferences.resetMinute in 0..1439)
            ZoneId.of(preferences.zoneId)
            val covered = root.optJSONArray("covered")?.objects().orEmpty().map {
                UsageSession(it.getString("package"), it.getLong("from"), it.getLong("to"))
            }
            Snapshot(rules, usage, preferences, covered = covered)
        } catch (_: Exception) {
            // Leave corrupt bytes untouched; never present lost rules as active protection.
            Snapshot(storageIssue = "Saved limits could not be read. Protection is unavailable. Restore a timer backup in Settings, or clear app data to start again.")
        }
    }

    private fun encode(s: Snapshot): JSONObject = JSONObject().apply {
        put("version", 1)
        put("covered", JSONArray().apply { s.covered.forEach { interval -> put(JSONObject().apply {
            put("package", interval.packageName); put("from", interval.startMs); put("to", interval.endMs)
        }) } })
        put("rules", JSONArray().apply { s.rules.values.forEach { r -> put(JSONObject().apply {
            put("package", r.packageName); put("label", r.label); put("daily", r.dailyMs)
            put("chunk", r.chunkMs); put("cooldown", r.cooldownMs); put("enabled", r.enabled); put("days", r.activeDays)
        }) } })
        put("usage", JSONObject().apply { s.usage.forEach { (key, u) -> put(key, JSONObject().apply {
            put("period", u.periodStartMs); put("used", u.usedMs); put("chunk", u.chunkUsedMs)
            put("cooldown", deadlineJson(u.cooldown)); put("emergencyRemaining", u.emergencyRemainingMs)
            put("emergencyConsumed", u.emergencyConsumedMs); put("emergencyGranted", u.emergencyGranted)
            put("accountedThrough", u.accountedThroughMs)
        }) } })
        put("preferences", JSONObject().apply {
            val p = s.preferences
            put("reset", p.resetMinute); put("zone", p.zoneId); put("strict", deadlineJson(p.strictUntil))
            put("notifications", p.notifications); put("emergency", p.emergency); put("appearance", p.appearance.name)
        })
    }

    private fun deadlineJson(d: Deadline?): Any = d?.let { JSONObject().apply {
        put("wall", it.wallMs); put("elapsed", it.elapsedMs); put("boot", it.boot); put("duration", it.durationMs)
    } } ?: JSONObject.NULL

    private fun readDeadline(o: JSONObject, key: String): Deadline? = o.optJSONObject(key)?.let {
        Deadline(it.getLong("wall"), it.getLong("elapsed"), it.getInt("boot"), it.getLong("duration"))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    companion object {
        @Volatile private var instance: TimekeeperStore? = null
        fun get(context: Context): TimekeeperStore = instance ?: synchronized(this) {
            instance ?: TimekeeperStore(context.applicationContext).also { instance = it }
        }
    }
}
