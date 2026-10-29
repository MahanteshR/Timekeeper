package dev.timekeeper.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.timekeeper.app.data.InstalledApp
import dev.timekeeper.app.data.TimekeeperStore
import dev.timekeeper.app.shizuku.ShizukuLab
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun ShizukuLabPage(store: TimekeeperStore, apps: List<InstalledApp>, onBack: () -> Unit) {
    val context = LocalContext.current
    val lab = remember(context) { ShizukuLab.get(context) }
    val state by lab.state.collectAsState()
    val snapshot by store.snapshots.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val c = LocalOneUi.current
    var selected by rememberSaveable { mutableStateOf("") }
    var choose by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var details by rememberSaveable { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val targets = apps.filter { it.packageName in snapshot.rules }.sortedBy { it.label.lowercase() }
    val label = targets.firstOrNull { it.packageName == selected }?.label
    val available = state.connected && !state.busy && !state.journalIssue && snapshot.storageIssue == null
    val canTest = available && state.pendingPackage == null && label != null
    LaunchedEffect(snapshot.rules.keys) { lab.setTargets(snapshot.rules.keys) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { lab.refresh(); delay(1000) }
        }
    }
    fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        localMessage = "$label copied"
    }
    fun openShizuku() {
        runCatching { context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let(context::startActivity)
            ?: context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
            .onFailure { localMessage = "Install or open Shizuku, then start it using wireless debugging." }
    }
    PageBar("Shizuku lab", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionLabel("Experimental · manual tests")
        Group {
            SettingsRow("Non-root Shizuku", value = state.status,
                description = "Test stronger app controls. Existing timers still use Accessibility.")
            GroupDivider()
            SettingsRow("Open Shizuku", description = "Start through wireless debugging. Root mode is unsupported.", onClick = { openShizuku() })
            GroupDivider()
            SettingsRow(if (state.connected) "Reconnect after restarting Shizuku" else "Connect and check recovery",
                enabled = !state.busy && !state.connected, onClick = { lab.connect() })
            if (state.connected) {
                GroupDivider()
                SettingsRow("Disconnect lab", enabled = !state.busy && state.pendingPackage == null && !state.journalIssue,
                    onClick = { lab.disconnect() })
            }
        }
        SecondaryText("Shizuku gives Timekeeper broad shell privileges. Enable it only for this evaluation. Shizuku must be restarted after a reboot.", modifier = Modifier.padding(20.dp))
        if (state.pendingPackage != null || state.journalIssue) {
            SectionLabel("Recovery")
            Group {
                SettingsRow("Suspension recovery", value = state.pendingPackage ?: "Recovery record could not be read",
                    description = "The record stays until release is verified. Restart Shizuku and reconnect if it stopped.")
                GroupDivider()
                SettingsRow("Recover now", enabled = state.connected && !state.busy && state.pendingPackage != null,
                    onClick = { lab.recover() })
                state.pendingPackage?.let { pkg ->
                    GroupDivider()
                    SettingsRow("Copy manual recovery command", description = "For an ADB shell on this device. This removes the shell suspension for this app, including another shell tool’s restriction.",
                        onClick = { copy("ADB recovery command", "pm unsuspend --user 0 $pkg") })
                }
            }
        }
        SectionLabel("Test app")
        Group {
            SettingsRow("Choose app", value = label ?: "Select from App limits", enabled = !state.busy && state.pendingPackage == null,
                description = "Choose a nonessential app with time remaining. Only enabled third-party apps are eligible; system and Home apps are excluded.", onClick = { choose = true })
            GroupDivider()
            SettingsRow("Check current state", description = "Inspect suspension, stopped state and eligibility.", enabled = state.connected && !state.busy && label != null,
                onClick = { lab.inspect(selected) })
            GroupDivider()
            if (state.journalIssue && label != null) {
                GroupDivider()
                SettingsRow("Copy manual recovery for selected app", description = "ADB shell command. Removes this app’s shell suspension, including another shell tool’s restriction.",
                    onClick = { copy("ADB recovery command", "pm unsuspend --user 0 $selected") })
                GroupDivider()
            }
            SettingsRow("Open selected app", enabled = label != null, onClick = {
                runCatching { context.packageManager.getLaunchIntentForPackage(selected)?.let(context::startActivity)
                    ?: error("No launch activity") }.onFailure { localMessage = "Android could not open this app." }
            })
        }
        SectionLabel("App controls")
        Group {
            SettingsRow("Suspend for 10 seconds", description = "Check reopening, launcher appearance and playback. Recovery is attempted by a separate helper.",
                enabled = canTest && state.canSuspend, onClick = { confirm = "suspend" })
            GroupDivider()
            SettingsRow("Test stop-app", description = "Stop the app without putting it into Android’s force-stopped state. Compare playback and notifications.",
                enabled = canTest && state.canStop, onClick = { confirm = "stop" })
            GroupDivider()
            SettingsRow("Test force-stop", description = "Stronger stop: background work and notifications may stay inactive until you reopen the app.",
                enabled = canTest && state.canForce, onClick = { confirm = "force" })
        }
        SectionLabel("Foreground observation")
        Group {
            SettingsRow("Observe tasks for 30 seconds", description = "Switch apps and test the notification shade, split screen and picture-in-picture. Records app and activity names locally, up to 60 samples.",
                enabled = available && state.canObserve, onClick = { lab.observe() })
        }
        SecondaryText("Observation is an experiment and does not replace Accessibility or change recorded usage. Samples stay in memory until replaced or the helper stops.", modifier = Modifier.padding(20.dp))
        state.message?.let { SecondaryText(it, modifier = Modifier.padding(20.dp)) }
        localMessage?.let { SecondaryText(it, modifier = Modifier.padding(20.dp)) }
        if (state.journalIssue) SecondaryText("Tests are disabled because recovery storage needs attention. Do not clear Timekeeper data while an app is suspended.", color = c.error, modifier = Modifier.padding(20.dp))
        Group {
            SettingsRow(if (details) "Hide diagnostic report" else "Show diagnostic report", onClick = { details = !details })
            GroupDivider()
            SettingsRow("Copy diagnostic report", description = "Includes selected app state and observed app names. Review before sharing.", onClick = {
                copy("Diagnostic report", "Timekeeper Shizuku lab\n${state.status}\n${state.capabilities}\n${state.inspection}\n${state.report}\n${state.message.orEmpty()}")
            })
        }
        if (details) Text("${state.capabilities}\n${state.inspection}\n${state.report}", color = c.secondary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(20.dp))
        Spacer(Modifier.height(28.dp))
    }
    if (choose) BottomChoice("Choose a test app", { choose = false }) {
        if (targets.isEmpty()) SecondaryText("Add a third-party app in App limits first.")
        targets.forEach { app -> SettingsRow(app.label, description = app.packageName, onClick = { selected = app.packageName; choose = false }) }
    }
    confirm?.let { action ->
        val testPackage = selected
        val title = when (action) { "suspend" -> "Suspend $label?"; "force" -> "Force-stop $label?"; else -> "Stop $label?" }
        BottomChoice(title, { confirm = null }, actions = {
            ChoiceActions(onCancel = { confirm = null }, onConfirm = {
                confirm = null
                if (action == "suspend") lab.suspendTrial(testPackage) else lab.stop(testPackage, action == "force")
            }, confirmLabel = "Run test")
        }) {
            SecondaryText(when (action) {
                "suspend" -> "Save any work first. Access should return after 10 seconds while the helper is running. If Shizuku stops or the phone reboots, suspension can remain until you restart Shizuku and reconnect here. Avoid testing an app controlled by another freezer. Manual ADB recovery is available from this screen."
                "force" -> "Save any work first. This ends the app’s processes and can cancel pending work and notifications. Force-stop has no automatic undo; open the app yourself afterward. Test on a nonessential app."
                else -> "Save any work first. This ends the app’s processes; scheduled work can remain. Open the app yourself afterward and compare playback with force-stop."
            })
        }
    }
}
