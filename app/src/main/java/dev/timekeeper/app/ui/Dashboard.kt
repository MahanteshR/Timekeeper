package dev.timekeeper.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.timekeeper.app.data.*
import dev.timekeeper.core.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.*

private val usageColors = listOf(Color(0xFF3979EC), Color(0xFF2A9D8F), Color(0xFFAA70D9), Color(0xFFDB8C38), Color(0xFFDB657B))

@Composable
fun DashboardPage(store: TimekeeperStore, snapshot: Snapshot, protection: Boolean,
    onSettings: () -> Unit, onSetup: () -> Unit, onLimits: () -> Unit, onEdit: (String) -> Unit, onBackup: () -> Unit) {
    val c = LocalOneUi.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val report by store.history.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var followToday by rememberSaveable { mutableStateOf(true) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                store.refreshHistory(force = refresh > 0)
                refresh = 0
                delay(30_000)
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Timekeeper", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text("Your day, at a glance", style = MaterialTheme.typography.bodyMedium, color = c.secondary,
                modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Dashboard", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
            ToolbarIcon(UiSymbol.More, "Settings", onClick = onSettings)
        }
        val current = report
        when {
            current == null -> Group { SettingsRow("Loading screen time…", description = "Reading usage reported by Android.") }
            !current.accessGranted -> Group {
                SettingsRow("See your screen time", description = "Allow Usage Access for Timekeeper to see app usage and your weekly history. Your data stays on this device.")
                FlatAction("Allow usage access", onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                Text("Choose Timekeeper in the next screen and turn on usage access.", color = c.secondary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp))
            }
            current.error != null && current.days.isEmpty() -> Group {
                SettingsRow("Screen time unavailable", description = current.error)
                FlatAction("Try again", onClick = { refresh++ }, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                if (current.error != null) Text("Showing saved screen time. ${current.error}",
                    color = c.secondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                val day = if (followToday) current.days.lastOrNull() else current.days.firstOrNull { it.date.toString() == selected } ?: current.days.lastOrNull()
                if (day != null) {
                    UsageOverview(day, current.apps)
                    Spacer(Modifier.height(16.dp))
                    WeekUsage(current.days, day.date) { selected = it.toString(); followToday = it == current.days.lastOrNull()?.date; showAll = false }
                    SectionLabel("Most used apps")
                    val ordered = remember(day.apps) { day.apps.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }) }
                    Group {
                        if (ordered.isEmpty()) SettingsRow("No usage reported", description = if (day.reported)
                            "Android has not reported any foreground app time for this day."
                            else "Android has no usage history available for this day.")
                        val visible = if (showAll) ordered else ordered.take(5)
                        visible.forEachIndexed { index, entry ->
                            val app = current.apps[entry.key] ?: UsageApp(entry.key, entry.key, false)
                            val rule = snapshot.rules[entry.key]
                            UsageAppRow(app, entry.value, day.totalMs, usageColors.getOrElse(index) { c.secondary },
                                canEdit = rule != null || app.canLimit, limited = rule != null,
                                onClick = { onEdit(entry.key) })
                            if (index < visible.lastIndex) GroupDivider()
                        }
                        if (ordered.size > 5) FlatAction(if (showAll) "Show fewer apps" else "Show all ${ordered.size} apps",
                            onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Group {
            val enabled = snapshot.rules.values.count { it.enabled }
            val cooling = snapshot.rules.values.count { store.status(it).gate == Gate.COOLDOWN }
            SettingsRow("App limits", value = "$enabled on · $cooling cooling down", onClick = onLimits)
            if (snapshot.rules.isEmpty()) {
                GroupDivider()
                SettingsRow("Restore saved timers", description = "Recover an existing timer setup.", onClick = onBackup)
            }
            GroupDivider()
            SettingsRow(if (protection) "Protection active" else "Protection needs attention",
                description = if (protection) "Your app limits are running." else "Open setup to check app blocking.", onClick = onSetup)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun UsageOverview(day: UsageDay, apps: Map<String, UsageApp>) {
    val c = LocalOneUi.current
    val ordered = remember(day.apps) { day.apps.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }) }
    val slices = ordered.take(3).map { it.value } + if (ordered.size > 3) listOf(ordered.drop(3).sumOf { it.value }) else emptyList()
    // Animate proportions only when data changes; no spinning/replayed loading animation.
    val animatedSlices = slices.map { value ->
        animateFloatAsState(if (day.totalMs > 0) (value.toDouble() / day.totalMs * 360).toFloat() else 0f,
            animationSpec = tween(300), label = "usage segment")
    }
    Group {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(180)).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (day.date == LocalDate.now()) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())),
                        color = c.secondary, style = MaterialTheme.typography.bodyLarge)
                    Text(if (day.reported) screenTimeLabel(day.totalMs) else "—", fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(top = 8.dp))
                    Text("Screen time", color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                }
                Canvas(Modifier.padding(start = 12.dp).size(104.dp).clearAndSetSemantics {}) {
                    val stroke = 12.dp.toPx()
                    val bounds = Size(size.width - stroke, size.height - stroke)
                    val origin = Offset(stroke / 2, stroke / 2)
                    drawArc(c.divider, 0f, 360f, false, origin, bounds, style = Stroke(stroke))
                    var start = -90f
                    slices.forEachIndexed { i, value ->
                        val angle = animatedSlices[i].value
                        val gap = if (slices.size > 1) minOf(2f, angle / 3) else 0f
                        drawArc(if (i == 3) c.secondary else usageColors[i], start + gap / 2, angle - gap,
                            false, origin, bounds, style = Stroke(stroke))
                        start += angle
                    }
                }
            }
            ordered.take(3).forEachIndexed { index, entry ->
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(usageColors[index], RoundedCornerShape(4.dp)))
                    Text(apps[entry.key]?.label ?: entry.key, color = c.text,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(screenTimeLabel(entry.value), color = c.secondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun WeekUsage(days: List<UsageDay>, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val c = LocalOneUi.current
    val peak = days.maxOfOrNull { it.totalMs }?.coerceAtLeast(1) ?: 1
    Group {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Last 7 days", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = c.text,
                modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val width = (maxWidth / 7).coerceAtLeast(48.dp)
                LazyRow(state = rememberLazyListState(),
                    modifier = Modifier.fillMaxWidth().selectableGroup()) {
                    items(days.size, key = { days[it].date.toString() }) { i ->
                        val day = days[i]
                        val barHeight by animateFloatAsState((day.totalMs.toDouble() / peak * 110).toFloat().coerceAtLeast(2f), label = "usage bar")
                        Column(Modifier.width(width).selectable(day.date == selected, role = Role.RadioButton, onClick = { onSelect(day.date) })
                            .semantics(mergeDescendants = true) {
                                contentDescription = day.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
                                stateDescription = if (day.reported) screenTimeLabel(day.totalMs) else "No usage reported"
                            }.padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.BottomCenter) {
                                if (!day.reported) Text("—", color = c.secondary)
                                else Box(Modifier.width(16.dp).height(barHeight.dp)
                                    .background(if (day.date == selected) c.accent else c.divider, RoundedCornerShape(8.dp)))
                            }
                            Text(day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                style = MaterialTheme.typography.bodyMedium, color = if (day.date == selected) c.accent else c.secondary,
                                modifier = Modifier.padding(top = 8.dp))
                            Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (day.date == selected) FontWeight.SemiBold else FontWeight.Normal, color = c.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageAppRow(app: UsageApp, milliseconds: Long, totalMs: Long, color: Color,
    canEdit: Boolean, limited: Boolean, onClick: () -> Unit) {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxWidth().then(if (canEdit) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.packageName)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(app.label, color = c.text, style = MaterialTheme.typography.bodyLarge)
            Text(screenTimeLabel(milliseconds), color = c.secondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 3.dp))
            LinearProgressIndicator(progress = { if (totalMs > 0) milliseconds.toFloat() / totalMs else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp), color = color,
                trackColor = c.divider, drawStopIndicator = {})

        }
        if (canEdit) Text(if (limited) "Edit" else "Set limit", color = c.accent,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

internal fun screenTimeLabel(milliseconds: Long): String {
    val minutes = milliseconds / MINUTE
    return when {
        milliseconds > 0 && minutes == 0L -> "<1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}
