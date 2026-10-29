package dev.timekeeper.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.timekeeper.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

@Composable
internal fun BackupPage(store: TimekeeperStore, installed: Set<String>, appsLoaded: Boolean,
    onBack: () -> Unit, onSetup: () -> Unit) {
    val status by store.backups.status.collectAsState()
    val snapshot by store.snapshots.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = LocalOneUi.current
    var reading by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<TimerBackup?>(null) }
    var disconnect by rememberSaveable { mutableStateOf(false) }
    var restored by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var allTimers by rememberSaveable { mutableStateOf(false) }
    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { error = null; store.backups.connect(it) }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected = it.toString(); allTimers = false }
    }
    LaunchedEffect(selected) {
        val value = selected ?: return@LaunchedEffect
        reading = true; error = null; preview = null
        try { preview = store.backups.read(Uri.parse(value)) }
        catch (_: TimeoutCancellationException) {
            error = "The storage provider did not respond. Choose another available backup file. No timers changed."; selected = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            error = "Choose a complete Timekeeper backup supported by this version. No timers changed."; selected = null
        } finally { reading = false }
    }
    PageBar("Back up and restore", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionLabel("Timer backup")
        Group {
            SettingsRow("Automatic backup", value = when {
                status.writing -> "Saving backup…"
                status.error != null -> "Needs attention"
                status.changed -> "Changes waiting to be backed up"
                status.connected -> "Up to date"
                else -> "Not set up"
            }, description = if (status.connected) "Folder: ${status.fileName}" else "Choose a private folder to keep your setup after reinstalling.")
            if (status.savedAtMs > 0) {
                GroupDivider()
                SettingsRow("Last successful backup", value = UiTime.stamp(context, status.savedAtMs))
            }
            GroupDivider()
            SettingsRow(if (status.connected) "Change backup folder" else "Choose backup folder",
                description = "Use a Timekeeper folder in Documents or an available cloud storage provider.",
                enabled = snapshot.storageIssue == null, onClick = { error = null; chooseFolder.launch(null) })
            if (status.connected) {
                GroupDivider()
                SettingsRow("Back up now", enabled = snapshot.storageIssue == null,
                    onClick = { error = null; store.backups.saveNow() })
                GroupDivider()
                SettingsRow("Disconnect backup", description = "Keep existing files and stop automatic updates.", onClick = { disconnect = true })
            }
            if (status.writing) {
                GroupDivider()
                SettingsRow("Cancel current backup", description = "Existing verified copies are kept.",
                    onClick = { store.backups.cancelCurrent() })
            }
        }
        status.error?.let { SecondaryText(it, color = c.error, modifier = Modifier.padding(20.dp)) }
        SecondaryText("Saves your app timers and preferences. Usage history, live cooldowns, and Android permissions are not included.", modifier = Modifier.padding(20.dp))
        FlatAction(if (details) "Hide backup details" else "What is saved?", onClick = { details = !details }, modifier = Modifier.fillMaxWidth())
        if (details) SecondaryText("Includes daily allowances, intervals, cooldown settings, repeat days, reset time and time zone, appearance, notifications, emergency preference, and strict-mode preference. Keeps recent verified copies. Backup files identify your selected apps; choose a private location. A cloud provider may sync the folder through its own account.", modifier = Modifier.padding(20.dp))
        SectionLabel("Restore")
        Group {
            SettingsRow(if (reading) "Reading backup…" else "Restore saved timers",
                description = "Choose a backup, review every change, and apply all timers together.",
                enabled = !reading && !store.isStrict(), onClick = { error = null; openBackup.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) })
            if (reading) {
                GroupDivider()
                SettingsRow("Cancel reading", onClick = { selected = null; reading = false })
            }
        }
        if (store.isStrict()) SecondaryText("Available when strict mode ends.", modifier = Modifier.padding(20.dp))
        if (snapshot.storageIssue != null) SecondaryText("Restore a backup to recover your saved timers.", color = c.error, modifier = Modifier.padding(20.dp))
        error?.let { SecondaryText(it, color = c.error, modifier = Modifier.padding(20.dp)) }
        if (restored) Group(Modifier.padding(top = 12.dp)) {
            SettingsRow("Timers restored", description = "Check Accessibility, Usage access, and notifications before relying on protection.", onClick = onSetup)
            GroupDivider()
            SettingsRow("Automatic backup", description = if (status.connected) "The connected folder will receive the restored setup."
                else "Choose a backup folder above to keep future changes.")
        }
        Spacer(Modifier.height(28.dp))
    }
    preview?.let { backup ->
        val config = backup.configuration
        val missing = config.rules.count { it.packageName !in installed }
        val newCount = config.rules.count { it.packageName !in snapshot.rules }
        val zone = ZoneId.of(config.zoneId)
        BottomChoice("Restore ${config.rules.size} app timers?", { preview = null; selected = null }) {
            SecondaryText("Saved ${UiTime.stamp(context, backup.savedAtMs)}")
            SecondaryText("$newCount new · ${config.rules.size - newCount} matching timers updated. Other timers stay. Current recorded usage is kept; cooldown-setting changes apply immediately.", modifier = Modifier.padding(top = 12.dp))
            FlatAction(if (allTimers) "Hide timer details" else "Review all ${config.rules.size} timers", onClick = { allTimers = !allTimers }, modifier = Modifier.fillMaxWidth())
            if (allTimers) config.rules.forEach { rule ->
                SettingsRow(rule.label, value = if (rule.enabled) "On" else "Paused", description =
                    "Daily ${durationLabel(rule.dailyMs)} · interval ${durationLabel(rule.chunkMs)} · cooldown ${durationLabel(rule.cooldownMs)}\n${repeatDaysLabel(rule.activeDays)}")
            }
            SectionLabel("Global settings — apply to all timers")
            SettingsRow("Reset time", UiTime.reset(context, config.resetMinute, zone), description = config.zoneId)
            SettingsRow("Appearance", config.appearance.lowercase().replaceFirstChar { it.titlecase() })
            SettingsRow("Notifications", if (config.notifications) "On — Android permission also required" else "Off")
            SettingsRow("Emergency allowance", if (config.emergency) "On" else "Off")
            SettingsRow("Strict mode", if (config.strict) "On until the next reset" else "Off")
            if (config.strict) SecondaryText("All limits, including existing timers, will be locked until the next reset.", color = c.accent, modifier = Modifier.padding(top = 12.dp))
            if (appsLoaded && missing > 0) SecondaryText("$missing unavailable apps: their timers will be kept for when you install them.", modifier = Modifier.padding(top = 12.dp))
            SecondaryText("Usage history and live cooldowns are not imported. Android permissions stay under your control.", modifier = Modifier.padding(top = 12.dp))
            error?.let { SecondaryText(it, color = c.error, modifier = Modifier.padding(top = 12.dp)) }
            Button(onClick = {
                try {
                    store.restoreConfiguration(config)
                    preview = null; selected = null; restored = true; error = null
                    scope.launch { store.refreshHistory(force = true) }
                } catch (e: Exception) { error = e.message ?: "Could not save the restored setup." }
            }, enabled = !store.isStrict(), shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 52.dp)) { Text("Apply all timers", style = MaterialTheme.typography.labelLarge) }
            FlatAction("Cancel", onClick = { preview = null; selected = null }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (disconnect) BottomChoice("Disconnect backup?", { disconnect = false }) {
        SecondaryText("Your timers and existing backup files will stay. Future changes will only be saved on this phone.")
        FlatAction("Disconnect", onClick = {
            try { store.backups.disconnect(); error = null }
            catch (_: Exception) { error = "Could not save the disconnection. Try again." }
            disconnect = false
        }, modifier = Modifier.fillMaxWidth())
        FlatAction("Cancel", onClick = { disconnect = false }, modifier = Modifier.fillMaxWidth())
    }
}
