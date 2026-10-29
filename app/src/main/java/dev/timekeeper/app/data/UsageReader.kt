package dev.timekeeper.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserManager
import dev.timekeeper.core.*
import java.time.Instant
import java.time.ZoneId

data class UsageApp(val packageName: String, val label: String, val canLimit: Boolean)
data class UsageReport(
    val accessGranted: Boolean,
    val days: List<UsageDay> = emptyList(),
    val apps: Map<String, UsageApp> = emptyMap(),
    val error: String? = null,
    val sessions: List<UsageSession> = emptyList(),
    val asOfMs: Long = 0,
    val firstEventMs: Long? = null,
)

fun hasUsageAccess(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
    .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

/** Timestamped events are clipped to local days; aggregate buckets can straddle those boundaries. */
fun readUsageReport(context: Context, nowMs: Long = System.currentTimeMillis(), covered: List<UsageSession> = emptyList(), lookbackStartMs: Long? = null): UsageReport {
    if (!hasUsageAccess(context)) return UsageReport(accessGranted = false)
    if (!context.getSystemService(UserManager::class.java).isUserUnlocked)
        return UsageReport(true, error = "Unlock the device to read usage history.")
    return try {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // Carry already-open activities across the first displayed midnight.
        val begin = lookbackStartMs ?: today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val raw = context.getSystemService(UsageStatsManager::class.java).queryEvents(begin, nowMs)
            ?: return UsageReport(true, error = "Android could not provide usage history. Try again after unlocking the device.")
        val events = mutableListOf<ForegroundEvent>()
        val item = UsageEvents.Event()
        var firstEvent: Long? = null
        while (raw.hasNextEvent()) {
            raw.getNextEvent(item)
            if (firstEvent == null) firstEvent = item.timeStamp
            val kind = when (item.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundKind.RESUME
                UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundKind.PAUSE
                UsageEvents.Event.ACTIVITY_STOPPED -> ForegroundKind.STOP
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ForegroundKind.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_SHOWN -> ForegroundKind.LOCK
                UsageEvents.Event.DEVICE_SHUTDOWN -> ForegroundKind.SHUTDOWN
                UsageEvents.Event.DEVICE_STARTUP -> ForegroundKind.STARTUP
                else -> null
            }
            if (kind != null) events += ForegroundEvent(item.timeStamp, kind,
                item.packageName.orEmpty(), item.className.orEmpty())
        }
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val excluded = homes + setOf(context.packageName, "com.android.systemui")
        val sessions = ForegroundHistory.excluding(ForegroundHistory.sessions(events, nowMs), covered)
            .filter { it.packageName !in excluded }
        val days = ForegroundHistory.days(sessions, nowMs, zone, firstEvent)
        val apps = sessions.map { it.packageName }.toSet().associateWith { name ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString() }.getOrDefault(name)
            UsageApp(name, label, name in launchable)
        }
        UsageReport(true, days, apps, sessions = sessions, asOfMs = nowMs, firstEventMs = firstEvent)
    } catch (_: SecurityException) {
        UsageReport(false)
    } catch (_: RuntimeException) {
        UsageReport(true, error = "Usage history is temporarily unavailable. Try again.")
    }
}
