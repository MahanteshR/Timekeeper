package dev.timekeeper.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.timekeeper.app.data.TimekeeperStore
import dev.timekeeper.app.ui.*
import dev.timekeeper.core.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** A separate task above Home, so system panels never uncover the limited app. */
class BlockActivity : ComponentActivity() {
    private var target by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        enableEdgeToEdge()
        setContent {
            val store = remember { TimekeeperStore.get(this) }
            val snapshot by store.snapshots.collectAsState()
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            var now by remember { mutableStateOf(store.clock.now()) }
            LaunchedEffect(lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) { now = store.clock.now(); store.refresh(now); delay(1000) }
                }
            }
            TimekeeperTheme(snapshot.preferences.appearance) {
                val c = LocalOneUi.current
                val view = LocalView.current
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = c.background != Color.Black
                        isAppearanceLightNavigationBars = c.background != Color.Black
                    }
                }
                BackHandler { returnHome() }
                val rule = snapshot.rules[target]
                if (rule == null) LaunchedEffect(Unit) { returnHome() }
                else {
                    val status = store.status(rule, now)
                    val blocked = status.gate == Gate.COOLDOWN || status.gate == Gate.DAILY_LIMIT
                    val remaining = if (status.gate == Gate.DAILY_LIMIT) (status.resetAtMs - now.wallMs).coerceAtLeast(0)
                        else status.cooldownRemainingMs
                    val deadline = if (status.gate == Gate.DAILY_LIMIT) status.resetAtMs else now.wallMs + remaining
                    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AppIcon(rule.packageName)
                        Text(if (blocked) "${rule.label} paused" else "${rule.label} is available",
                            color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 20.dp))
                        if (blocked) {
                            Text(if (status.gate == Gate.DAILY_LIMIT) "Daily allowance used" else "Take a break until your next interval",
                                color = c.secondary, modifier = Modifier.padding(top = 12.dp))
                            val rounded = ((remaining + MINUTE - 1) / MINUTE) * MINUTE
                            Text(durationLabel(rounded), color = c.text, fontSize = 34.sp,
                                modifier = Modifier.padding(top = 24.dp))
                            Text("Available " + UiTime.available(this@BlockActivity, deadline, now.wallMs, store.zone),
                                color = c.secondary, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                        }
                        Group(Modifier.padding(vertical = 12.dp)) {
                            SettingsRow("Used since reset", "${durationLabel(status.usage.usedMs)} of ${durationLabel(rule.dailyMs)}",
                                description = "Since " + UiTime.stamp(this@BlockActivity, status.usage.periodStartMs, store.zone))
                        }
                        if (!blocked) Button(onClick = { openTarget(store) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 12.dp)) {
                            Text("Open ${rule.label}", style = MaterialTheme.typography.labelLarge)
                        }
                        if (snapshot.preferences.emergency && AllowanceEngine.emergency(rule, status.usage, now,
                                snapshot.preferences.resetMinute, store.zone) != null) {
                            FlatAction("Use emergency allowance", onClick = {
                                if (store.grantEmergency(target)) openTarget(store)
                            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        if (blocked) FlatAction("Close", onClick = ::returnHome,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
    }

    private fun returnHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finishAndRemoveTask()
    }

    private fun openTarget(store: TimekeeperStore) {
        val rule = store.current.rules[target] ?: return
        val gate = store.status(rule).gate
        if (gate == Gate.COOLDOWN || gate == Gate.DAILY_LIMIT) return
        packageManager.getLaunchIntentForPackage(target)?.let { startActivity(it); finishAndRemoveTask() }
    }

    companion object { const val EXTRA_PACKAGE = "dev.timekeeper.app.BLOCKED_PACKAGE" }
}
