package dev.timekeeper.app.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.timekeeper.app.MainActivity
import dev.timekeeper.app.BlockActivity
import dev.timekeeper.app.R
import dev.timekeeper.app.data.TimekeeperStore
import dev.timekeeper.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*

data class MonitorHealth(val connected: Boolean = false, val lastHeartbeatMs: Long = 0,
    val overlayError: Boolean = false, val interruption: Boolean = false) {
    fun isProtecting(nowElapsedMs: Long, storageOkay: Boolean = true): Boolean =
        connected && !overlayError && storageOkay && nowElapsedMs - lastHeartbeatMs in 0..4_999
}

class TimekeeperService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: TimekeeperStore
    private lateinit var overlay: BlockOverlay
    private var activePackage: String? = null
    private var observedAt: Stamp? = null
    private var dismissBlock = false
    private var lastNotification: String? = null
    private var notificationPackage: String? = null
    private var coveredPackage: String? = null
    private var pendingBlock: String? = null
    private var pausedHistoryPackage: String? = null
    private var handoffJob: Job? = null
    private lateinit var homePackages: Set<String>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var historyJob: Job? = null
    private var historyTick = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { synchronizeWindows() }
    }
    private val tick = object : Runnable {
        override fun run() {
            synchronizeWindows()
            if (getSystemService(PowerManager::class.java).isInteractive && historyTick++ % 30 == 0 && historyJob?.isActive != true) {
                historyJob = scope.launch { store.refreshHistory(includeWeek = false); synchronizeWindows() }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onServiceConnected() {
        store = TimekeeperStore.get(this)
        overlay = BlockOverlay(this, store, ::goHome)
        homePackages = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        health.value = MonitorHealth(connected = true, lastHeartbeatMs = SystemClock.elapsedRealtime())
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::store.isInitialized) synchronizeWindows(event)
    }

    private fun synchronizeWindows(event: AccessibilityEvent? = null) {
        val now = store.clock.now()
        val last = observedAt
        var interruption = health.value.interruption
        if (last != null && now.elapsedMs - last.elapsedMs in 0..5_000) {
            activePackage?.let { store.consume(it, last, now) }
            if (overlay.isShowing) coveredPackage?.let { store.recordCovered(it, last.wallMs, now.wallMs) }
            else pausedHistoryPackage?.let { store.recordCovered(it, last.wallMs, now.wallMs) }
        } else if (last != null) {
            // A stalled/killed monitor can't know what was used. Never invent usage.
            interruption = true
        }
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        val interactive = power.isInteractive && !keyguard.isKeyguardLocked
        val packageName = if (interactive) foregroundPackage(event) else null
        pausedHistoryPackage = if (interactive && packageName == null && !overlay.isShowing)
            activePackage ?: pausedHistoryPackage else null
        // Draw the lightweight curtain before refreshing other rules or publishing UI state.
        val immediateRule = packageName?.let { store.current.rules[it] }?.takeIf { it.enabled }
        if (pendingBlock == null && immediateRule != null) {
            val status = store.status(immediateRule, now)
            if (status.gate == Gate.COOLDOWN || status.gate == Gate.DAILY_LIMIT) {
                if (overlay.show(immediateRule)) coveredPackage = immediateRule.packageName
            }
        }
        store.refresh(now)
        synchronizeNotification(packageName, interactive)
        observedAt = now
        if (pendingBlock != null) {
            activePackage = null
            finishHandoff(packageName, now, interruption)
            return
        }
        activePackage = packageName?.takeIf { store.current.rules[it]?.enabled == true }
        var overlayError = false
        val rule = activePackage?.let { store.current.rules[it] }
        if (rule != null) {
            val status = store.status(rule, now)
            if (status.gate == Gate.COOLDOWN || status.gate == Gate.DAILY_LIMIT) {
                activePackage = null
                dismissBlock = false
                pendingBlock = rule.packageName
                overlayError = !overlay.show(rule)
                coveredPackage = rule.packageName.takeUnless { overlayError }
                // First leave the limited task. The temporary curtain stays until a safe window is confirmed.
                if (!performGlobalAction(GLOBAL_ACTION_HOME)) overlayError = true
                handoffJob?.cancel()
                handoffJob = scope.launch {
                    repeat(30) {
                        delay(50)
                        if (pendingBlock == null) return@launch
                        synchronizeWindows()
                    }
                    if (pendingBlock != null) health.value = health.value.copy(overlayError = true)
                }
            } else { overlay.hide(); coveredPackage = null }
        } else {
            overlay.hide()
            coveredPackage = null
        }
        health.value = MonitorHealth(true, now.elapsedMs, overlayError, interruption)
    }

    private fun finishHandoff(foreground: String?, now: Stamp, interrupted: Boolean) {
        val target = pendingBlock ?: return
        val rule = store.current.rules[target]
        val status = rule?.let { store.status(it, now) }
        val blocked = status?.gate == Gate.COOLDOWN || status?.gate == Gate.DAILY_LIMIT
        val decision = BlockHandoffPolicy.decide(target, foreground, foreground in homePackages, blocked)
        var error = false
        when (decision) {
            BlockHandoff.HOLD_CURTAIN -> {
                if (rule != null && status != null) error = !overlay.show(rule)
            }
            BlockHandoff.OPEN_EXPLANATION -> {
                // The launcher is now behind the explanation; pulling system panels cannot uncover the limited task.
                pendingBlock = null
                try {
                    if (!dismissBlock) {
                        startActivity(Intent(this, BlockActivity::class.java)
                            .putExtra(BlockActivity.EXTRA_PACKAGE, target)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                } catch (_: RuntimeException) {
                    error = true // Home remains the safe fallback if Android refuses the launch.
                }
                overlay.hide()
                coveredPackage = null
            }
            BlockHandoff.RELEASE_CURTAIN -> {
                pendingBlock = null
                overlay.hide()
                coveredPackage = null
            }
        }
        health.value = MonitorHealth(true, now.elapsedMs, error, interrupted)
    }

    private fun foregroundPackage(event: AccessibilityEvent? = null): String? {
        val visible = windows
        // System windows such as the shade suspend counting; keyboard windows do not.
        if (visible.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused }) return null
        val apps = visible.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
        val candidate = apps.firstOrNull { it.isFocused }
            ?: apps.firstOrNull { it.isActive }
            ?: if (overlay.isShowing) apps.maxByOrNull { it.layer } else null
        // Read package identity only. Never inspect text, child nodes, or screen images.
        // An event can identify the focused window before its root is available during launch.
        val freshWindowEvent = event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            SystemClock.uptimeMillis() - event.eventTime in 0..250
        if (candidate != null && freshWindowEvent && event?.windowId == candidate.id) {
            event?.packageName?.toString()?.let { return it }
        }
        return candidate?.root?.packageName?.toString()
    }

    private fun synchronizeNotification(foreground: String?, interactive: Boolean) {
        val limited = foreground?.takeIf { store.current.rules[it]?.enabled == true }
        val previous = notificationPackage?.let { store.current.rules[it] }?.takeIf { it.enabled }
        val previousGate = previous?.let { store.status(it).gate }
        val blockedContext = interactive && (foreground in homePackages || foreground == packageName) &&
            (previousGate == Gate.COOLDOWN || previousGate == Gate.DAILY_LIMIT)
        val target = NotificationPolicy.target(limited, notificationPackage, foreground == null && interactive, blockedContext)
        val rule = target?.let { store.current.rules[it] }?.takeIf { it.enabled }
        if (rule == null) cancelNotification() else updateNotification(rule, store.status(rule))
    }

    private fun goHome() {
        dismissBlock = true
        activePackage = null
        observedAt = store.clock.now()
        // Keep the curtain until a safe foreground is confirmed; no grace period for reopening.
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun updateNotification(rule: Rule, status: Allowance) {
        if (!store.current.preferences.notifications ||
            (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
            cancelNotification(); return
        }
        val text = when (status.gate) {
            Gate.AVAILABLE -> "${(status.availableMs + 59_999) / MINUTE} min left in this interval"
            Gate.COOLDOWN -> "Next interval in ${(status.cooldownRemainingMs + 59_999) / MINUTE} min"
            else -> "Daily limit reached"
        }
        val signature = rule.packageName + rule.label + text
        if (lastNotification == signature) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("remaining", "Remaining time", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(this, "remaining")
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(rule.label).setContentText(text)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).build())
        lastNotification = signature
        notificationPackage = rule.packageName
    }

    private fun cancelNotification() {
        if (lastNotification != null) getSystemService(NotificationManager::class.java).cancel(1)
        lastNotification = null
        notificationPackage = null
    }

    override fun onInterrupt() {
        pendingBlock = null; handoffJob?.cancel()
        activePackage = null; observedAt = null
        if (::overlay.isInitialized) overlay.hide()
        cancelNotification()
        health.value = health.value.copy(interruption = true)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        if (::overlay.isInitialized) overlay.hide()
        runCatching { unregisterReceiver(screenReceiver) }
        cancelNotification()
        health.value = MonitorHealth()
        super.onDestroy()
    }

    companion object { val health = MutableStateFlow(MonitorHealth()) }
}
