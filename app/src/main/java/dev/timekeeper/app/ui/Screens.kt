package dev.timekeeper.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.NotificationManager
import android.content.*
import android.os.*
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.timekeeper.app.data.*
import dev.timekeeper.app.service.TimekeeperService
import dev.timekeeper.core.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.*

@Composable
fun TimekeeperApp(store: TimekeeperStore) {
    val snapshot by store.snapshots.collectAsState()
    val health by TimekeeperService.health.collectAsState()
    var screen by rememberSaveable { mutableStateOf("home") }
    var parents by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun navigate(next: String) { parents = parents + screen; screen = next }
    fun pop() { screen = parents.lastOrNull() ?: "home"; parents = parents.dropLast(1) }
    var selected by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableStateOf(store.clock.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { now = store.clock.now(); store.refresh(now); delay(1000) }
        }
    }
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appsLoaded by remember { mutableStateOf(false) }
    var inventoryVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { inventoryVersion++ }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(lifecycle, inventoryVersion) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            store.backups.retryPending()
            apps = withContext(Dispatchers.IO) { AppIcons.clear(); installedApps(context) }
            appsLoaded = true
            awaitCancellation()
        }
    }
    val back = { pop() }
    BackHandler(screen != "home", onBack = back)
    val c = LocalOneUi.current
    val savedScreens = rememberSaveableStateHolder()
    val destination = if (screen == "edit") "edit:$selected" else screen
    Column(Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(targetState = destination, modifier = Modifier.fillMaxSize(), label = "navigation",
            transitionSpec = {
                fun depth(route: String) = when (route.substringBefore(':')) { "home" -> 0; "picker", "settings" -> 1; else -> 2 }
                val direction = if (depth(targetState) >= depth(initialState)) 1 else -1
                (slideInHorizontally(tween(220)) { direction * it / 10 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(180)) { -direction * it / 10 } + fadeOut(tween(140)))
            }) { route ->
            savedScreens.SaveableStateProvider(route) {
                Column(Modifier.fillMaxSize()) {
                    when (route.substringBefore(':')) {
                        "picker" -> AppPicker(apps, appsLoaded, snapshot.rules.keys, back) { app -> selected = app.packageName; navigate("edit") }
                        "edit" -> {
                            val packageName = route.substringAfter(':')
                            RuleEditor(store, packageName, apps.firstOrNull { it.packageName == packageName }, back,
                                onSaved = { parents = emptyList(); screen = "home" })
                        }
                        "settings" -> SettingsPage(store, now, back, onSetup = { navigate("setup") }, onBackup = { navigate("backup") })
                        "backup" -> BackupPage(store, apps.map { it.packageName }.toSet(), appsLoaded,
                            onBack = back, onSetup = { navigate("setup") })
                        "setup" -> SetupPage(store, now, back)
                        else -> HomeRoot(store, snapshot, now, health.isProtecting(SystemClock.elapsedRealtime(), snapshot.storageIssue == null), health.interruption,
                            onAdd = { navigate("picker") }, onSettings = { navigate("settings") },
                            onSetup = { navigate("setup") }, onBackup = { navigate("backup") }, onEdit = { selected = it; navigate("edit") })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRoot(store: TimekeeperStore, snapshot: Snapshot, now: Stamp, protection: Boolean, interrupted: Boolean,
    onAdd: () -> Unit, onSettings: () -> Unit, onSetup: () -> Unit, onBackup: () -> Unit, onEdit: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("dashboard") }
    val savedTabs = rememberSaveableStateHolder()
    val c = LocalOneUi.current
    BackHandler(tab != "dashboard") { tab = "dashboard" }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            savedTabs.SaveableStateProvider(tab) {
                if (tab == "dashboard") DashboardPage(store, snapshot, protection, onSettings, onSetup,
                    onLimits = { tab = "limits" }, onEdit = onEdit, onBackup = onBackup)
                else HomePage(store, snapshot, now, protection, interrupted, onAdd, onSettings, onSetup, onBackup, onEdit)
            }
        }
        HorizontalDivider(color = c.divider)
        Row(Modifier.fillMaxWidth().background(c.group).selectableGroup()) {
            listOf("dashboard" to "Dashboard", "limits" to "App limits").forEach { (key, label) ->
                val selected = tab == key
                Column(Modifier.weight(1f).selectable(selected, role = Role.Tab, onClick = { tab = key })
                    .heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    OneIcon(if (key == "dashboard") UiSymbol.Dashboard else UiSymbol.Limits, if (selected) c.accent else c.secondary, Modifier.size(20.dp))
                    Text(label, color = if (selected) c.accent else c.secondary,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun HomePage(store: TimekeeperStore, snapshot: Snapshot, now: Stamp, protection: Boolean, interrupted: Boolean,
    onAdd: () -> Unit, onSettings: () -> Unit, onSetup: () -> Unit, onBackup: () -> Unit, onEdit: (String) -> Unit) {
    val c = LocalOneUi.current
    val context = LocalContext.current
    val scroll = rememberScrollState()
    var expanded by rememberSaveable { mutableFloatStateOf(1f) }
    val density = LocalDensity.current.density
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(LocalDensity.current) { windowSize.height.toDp().value }
    val expansionHeight = if (windowHeight < 580 || windowSize.width > windowSize.height) 0f
        else (windowHeight * .19f).coerceIn(120f, 180f)
    val visibleExpansion = if (expansionHeight == 0f) 0f else expanded
    val connection = remember(scroll, density, expansionHeight) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (expansionHeight == 0f || source != NestedScrollSource.UserInput || (available.y > 0 && scroll.value > 0)) return Offset.Zero
                val before = expanded
                expanded = (expanded + available.y / (expansionHeight * density)).coerceIn(0f, 1f)
                return Offset(0f, (expanded - before) * expansionHeight * density)
            }
        }
    }
    Column(Modifier.fillMaxSize().nestedScroll(connection)) {
        val titleHeight = with(LocalDensity.current) { (28.sp * (1f + .4f * visibleExpansion)).toDp() } + 28.dp
        Column(Modifier.fillMaxWidth().heightIn(min = titleHeight).height((56 + expansionHeight * visibleExpansion).dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Timekeeper", fontSize = (22 + 12 * visibleExpansion).sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (visibleExpansion > .45f) Text("${snapshot.rules.size} ${if (snapshot.rules.size == 1) "app" else "apps"} limited",
                color = c.secondary, modifier = Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("App limits", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.weight(1f))
            ToolbarIcon(UiSymbol.Add, "Add app", onClick = onAdd)
            ToolbarIcon(UiSymbol.More, "Settings", onClick = onSettings)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            if (!protection || snapshot.storageIssue != null) {
                Group(Modifier.padding(bottom = 16.dp)) {
                    SettingsRow("Protection needs attention", description = snapshot.storageIssue ?: "Open setup to enable app blocking.", onClick = onSetup)
                }
            }
            if (snapshot.rules.isEmpty()) Group {
                SettingsRow("Choose your first app", description = "Set a daily allowance, an interval, and a cooldown.", onClick = onAdd)
                GroupDivider()
                SettingsRow("Restore saved timers", description = "Recover a setup from a backup.", onClick = onBackup)
            } else Group {
                val rules = snapshot.rules.values.sortedBy { it.label.lowercase() }
                rules.forEachIndexed { index, rule ->
                    AppLimitRow(rule, store.status(rule, now), now, store.zone, protection,
                        snapshot.preferences.resetMinute == 0) { onEdit(rule.packageName) }
                    if (index < rules.lastIndex) GroupDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
            Group {
                SettingsRow("Reset time", value = UiTime.reset(context, snapshot.preferences.resetMinute, store.zone),
                    description = "Limits count from the last reset. Dashboard totals count from midnight.", onClick = onSettings)
            }
            if (store.isStrict()) Text("Strict mode is on until the next reset.", color = c.secondary,
                modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().clickable(onClick = onSetup).padding(vertical = 26.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (protection) Color(0xFF36AC37) else Color(0xFFBF7525), RoundedCornerShape(8.dp)))
                Text(if (protection) "Protection active" else "Protection inactive", color = c.secondary,
                    modifier = Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (interrupted) Text("Tracking resumed after an interruption. Usage Access reconciles available foreground history.",
                color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 20.dp))
        }
    }
}

@Composable
private fun AppLimitRow(rule: Rule, status: Allowance, now: Stamp, zone: java.time.ZoneId, protection: Boolean, calendarDay: Boolean, onClick: () -> Unit) {
    val c = LocalOneUi.current
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        AppIcon(rule.packageName)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(rule.label, style = MaterialTheme.typography.bodyLarge, color = c.text, fontWeight = FontWeight.SemiBold)
            val description = when (status.gate) {
                Gate.AVAILABLE -> if (protection) "${durationLabel(status.availableMs)} available" else "Blocking inactive"
                Gate.COOLDOWN -> "Available ${UiTime.available(context, now.wallMs + status.cooldownRemainingMs, now.wallMs, zone)}"
                Gate.SCHEDULED_OFF -> "No limit this reset day · ${repeatDaysLabel(rule.activeDays)}"
                Gate.DAILY_LIMIT -> "Daily limit reached"
                Gate.DISABLED -> "Limit paused"
            }
            Text(description, color = if (status.gate == Gate.AVAILABLE || status.gate == Gate.COOLDOWN) c.accent else c.secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Text("${durationLabel(status.usage.usedMs)} of ${durationLabel(rule.dailyMs)} used ${if (calendarDay) "today" else "since reset"}", color = c.secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
            LinearProgressIndicator(progress = { (status.usage.usedMs.toFloat() / rule.dailyMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp).height(4.dp), color = c.accent,
                trackColor = c.divider, drawStopIndicator = {})
        }
    }
}

@Composable
internal fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val revision by AppIcons.revision.collectAsState()
    var icon by remember(packageName, revision) { mutableStateOf(AppIcons.cached(packageName)) }
    LaunchedEffect(packageName, revision) {
        if (icon == null) icon = withContext(Dispatchers.IO) { AppIcons.load(context, packageName) }
    }
    AndroidView(modifier = Modifier.size(32.dp).clearAndSetSemantics {}, factory = { ImageView(it) },
        update = { if (it.drawable !== icon) it.setImageDrawable(icon) })
}

@Composable
private fun AppPicker(apps: List<InstalledApp>, loaded: Boolean, added: Set<String>, onBack: () -> Unit,
    onSelect: (InstalledApp) -> Unit) {
    val c = LocalOneUi.current
    var query by rememberSaveable { mutableStateOf("") }
    PageBar("Add app", onBack)
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(query, { query = it }, placeholder = { Text("Search apps", color = c.secondary) }, singleLine = true,
        leadingIcon = { OneIcon(UiSymbol.Search, c.secondary) },
        trailingIcon = { if (query.isNotEmpty()) ToolbarIcon(UiSymbol.Close, "Clear search") { query = "" } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(focusedContainerColor = c.group, unfocusedContainerColor = c.group,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent),
        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
    val filtered = remember(apps, added, query) {
        apps.filter { it.packageName !in added && it.label.contains(query, ignoreCase = true) }
    }
    fun initial(label: String) = appInitial(label)
    val sections = remember(filtered) {
        filtered.mapIndexed { index, app -> initial(app.label) to index }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.first() }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var jumpJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var railHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val minimumThumbPx = with(density) { 36.dp.toPx() }
    val rowHeight = maxOf(56.dp, with(density) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() } + 20.dp)
    val rowHeightPx = with(density) { rowHeight.toPx() }
    val scrollGeometry by remember(list, railHeight, minimumThumbPx, rowHeightPx) {
        derivedStateOf {
            val info = list.layoutInfo
            val visible = info.visibleItemsInfo
            val averageHeight = rowHeightPx
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1).toFloat()
            val totalHeight = averageHeight * info.totalItemsCount
            val range = (totalHeight - viewport).coerceAtLeast(0f)
            val progress = if (range > 0) ((list.firstVisibleItemIndex * averageHeight +
                list.firstVisibleItemScrollOffset) / range).coerceIn(0f, 1f) else 0f
            val thumb = if (totalHeight > 0) (railHeight * viewport / totalHeight)
                .coerceIn(minimumThumbPx.coerceAtMost(railHeight.toFloat()), railHeight.toFloat()) else 0f
            Triple(thumb, progress * (railHeight - thumb).coerceAtLeast(0f), range > 0)
        }
    }
    fun jump(index: Int, offset: Int = 0) {
        jumpJob?.cancel()
        jumpJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { list.scrollToItem(index, offset) }
    }
    fun scrub(y: Float) {
        val info = list.layoutInfo
        if (railHeight <= 0 || filtered.isEmpty() || info.visibleItemsInfo.isEmpty()) return
        val averageHeight = rowHeightPx
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val range = (averageHeight * filtered.size - viewport).coerceAtLeast(0f)
        val thumb = scrollGeometry.first
        val fraction = ((y - thumb / 2) / (railHeight - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val position = fraction * range
        val index = (position / averageHeight).toInt().coerceIn(0, filtered.lastIndex)
        jump(index, (position - index * averageHeight).toInt().coerceAtLeast(0))
    }
    LaunchedEffect(query) { list.scrollToItem(0) }
    val firstVisible by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            info.visibleItemsInfo.firstOrNull { it.offset >= info.viewportStartOffset }?.index
                ?: list.firstVisibleItemIndex
        }
    }
    val activeLetter = filtered.getOrNull(firstVisible)?.let { initial(it.label) }.orEmpty()
    var showLetter by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress, dragging) {
        if (list.isScrollInProgress || dragging) showLetter = true
        else { delay(350); showLetter = false }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        androidx.compose.foundation.lazy.LazyColumn(state = list,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(c.group)) {
            if (!loaded) item { Text("Loading apps…", color = c.secondary, modifier = Modifier.padding(20.dp)) }
            else if (filtered.isEmpty()) item { Text("No matching apps.", color = c.secondary, modifier = Modifier.padding(20.dp)) }
            items(filtered.size, key = { filtered[it].packageName }, contentType = { "app" }) { index ->
                val app = filtered[index]
                Row(Modifier.fillMaxWidth().height(rowHeight).clickable { onSelect(app) }
                    .padding(start = 16.dp, end = 52.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName)
                    Text(app.label, modifier = Modifier.weight(1f).padding(start = 16.dp),
                        color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight().padding(vertical = 12.dp)
            .onSizeChanged { railHeight = it.height }
            .semantics {
                contentDescription = "App list scroll bar"
                stateDescription = activeLetter
                customActions = sections.map { (letter, index) ->
                    CustomAccessibilityAction("Jump to $letter") { jump(index); true }
                }
            }
            .pointerInput(filtered, railHeight, rowHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    dragging = true
                    try {
                        scrub(down.position.y)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) { change.consume(); scrub(change.position.y) }
                        } while (event.changes.any { it.id == down.id && it.pressed })
                    } finally { dragging = false }
                }
            }) {
            if (scrollGeometry.third) Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp)
                .offset(y = with(density) { scrollGeometry.second.toDp() })
                .width(if (dragging) 5.dp else 3.dp)
                .height(with(density) { scrollGeometry.first.toDp() })
                .background(if (dragging) c.accent else c.secondary.copy(alpha = .35f), RoundedCornerShape(8.dp))
                .clearAndSetSemantics {})
        }
        if (showLetter && activeLetter.isNotEmpty()) {
            val bubbleOffset = with(density) {
                (scrollGeometry.second + scrollGeometry.first / 2 - 28.dp.toPx()).coerceIn(0f,
                    (railHeight - 56.dp.toPx()).coerceAtLeast(0f)).toDp()
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 56.dp).offset(y = bubbleOffset).size(56.dp)
                .background(c.accent, RoundedCornerShape(28.dp)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
                Text(activeLetter, color = if (c.background == Color.Black) Color.Black else Color.White,
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun RuleEditor(store: TimekeeperStore, packageName: String, app: InstalledApp?, onBack: () -> Unit, onSaved: () -> Unit) {
    val snapshot by store.snapshots.collectAsState()
    val original = snapshot.rules[packageName]
    val initial = original ?: Rule(packageName, app?.label ?: packageName)
    val saver = remember { androidx.compose.runtime.saveable.listSaver<Rule, Any>(
        save = { listOf(it.packageName, it.label, it.dailyMs, it.chunkMs, it.cooldownMs, it.enabled, it.activeDays) },
        restore = { Rule(it[0] as String, it[1] as String, it[2] as Long, it[3] as Long,
            it[4] as Long, it[5] as Boolean, it[6] as Int) }) }
    var draft by rememberSaveable(packageName, original, stateSaver = saver) { mutableStateOf(initial) }
    var discard by rememberSaveable { mutableStateOf(false) }
    fun leave() { if (draft.copy(label = initial.label) != initial) discard = true else onBack() }
    BackHandler(onBack = { leave() })
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf(false) }
    val locked = store.isStrict() && original != null
    val c = LocalOneUi.current
    var adjustment by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
    PageBar(draft.label, { leave() })
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ToggleStrip(draft.enabled, !store.isStrict()) { draft = draft.copy(enabled = it) }
        Spacer(Modifier.height(20.dp))
        Group {
            SettingsRow("Daily allowance", durationLabel(draft.dailyMs), enabled = !locked, onClick = { dialog = "daily" })
            GroupDivider()
            SettingsRow("Usage per interval", durationLabel(draft.chunkMs), enabled = !locked, onClick = { dialog = "chunk" })
            GroupDivider()
            SettingsRow("Cooldown", if (draft.cooldownMs == 0L) "Off" else durationLabel(draft.cooldownMs), enabled = !locked, onClick = { dialog = "cooldown" })
            GroupDivider()
            SettingsRow("Repeat days", repeatDaysLabel(draft.activeDays), enabled = !locked, onClick = { dialog = "days" })
        }
        Text("Time counts only while you use the app on selected reset days. Cooldowns continue across daily resets. Cooldown edits take effect immediately; recorded usage is kept.",
            color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
        if (locked) SecondaryText("Strict mode locks these settings until the next reset.", color = c.secondary, modifier = Modifier.padding(20.dp))
        adjustment?.let { SecondaryText(it, color = c.secondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        error?.let { SecondaryText(it, color = c.error, modifier = Modifier.padding(20.dp)) }
        if (original != null) Group(Modifier.padding(top = 4.dp, bottom = 24.dp)) {
            FlatAction("Remove limit", onClick = { remove = true }, enabled = !locked,
                destructive = true, modifier = Modifier.fillMaxWidth())
        }
    }
    HorizontalDivider(color = c.divider)
    Button(onClick = { if (store.saveRule(draft)) onSaved() else error = store.lastError ?: "Could not save changes." },
        enabled = !locked, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 52.dp),
        shape = RoundedCornerShape(26.dp)) {
        Text("Save", style = MaterialTheme.typography.labelLarge)
    }
    }
    if (discard) BottomChoice("Discard unsaved changes?", { discard = false }) {
        SecondaryText("Your saved timer will stay as it was.")
        FlatAction("Keep editing", onClick = { discard = false }, modifier = Modifier.fillMaxWidth())
        FlatAction("Discard", onClick = { draft = initial; dialog = null; discard = false; onBack() },
            destructive = true, modifier = Modifier.fillMaxWidth())
    }
    if (dialog == "days") RepeatDaysDialog(draft.activeDays, enabled = !locked,
        resetLabel = UiTime.reset(LocalContext.current, snapshot.preferences.resetMinute, store.zone),
        zoneLabel = store.zone.id, onDismiss = { dialog = null }) {
        draft = draft.copy(activeDays = it); dialog = null
    }
    dialog?.takeIf { it != "days" }?.let { field ->
        val initial = when (field) { "daily" -> draft.dailyMs; "chunk" -> draft.chunkMs; else -> draft.cooldownMs }
        DurationDialog(when (field) { "daily" -> "Daily allowance"; "chunk" -> "Usage per interval"; else -> "Cooldown" },
            (initial / MINUTE).toInt(), maximumMinutes = if (field == "cooldown") 10080 else if (field == "chunk") (draft.dailyMs / MINUTE).toInt() else 1440,
            allowFullDay = field != "cooldown" && (field != "chunk" || draft.dailyMs == 1440 * MINUTE),
            allowZero = field == "cooldown", onDismiss = { dialog = null }) { minutes ->
            val value = minutes * MINUTE
            when {
                field != "cooldown" && minutes > 1440 -> error = "The daily allowance cannot exceed 24 hours."
                field == "cooldown" && minutes > 10080 -> error = "The cooldown cannot exceed 7 days."
                field == "chunk" && value > draft.dailyMs -> error = "An interval cannot exceed the daily allowance."
                else -> {
                    adjustment = if (field == "daily" && value < draft.chunkMs)
                        "Usage per interval adjusted to ${durationLabel(value)} to fit your daily allowance." else null
                    draft = when (field) {
                        "daily" -> draft.withDailyAllowance(value)
                        "chunk" -> draft.copy(chunkMs = value)
                        else -> draft.copy(cooldownMs = value)
                    }
                    error = null
                }
            }
            dialog = null
        }
    }
    if (remove) BottomChoice("Remove ${draft.label} limit?", { remove = false }) {
        Text("Current-period usage is preserved if you add this app again.", color = c.secondary)
        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            FlatAction("Cancel", onClick = { remove = false }, modifier = Modifier.weight(1f))
            FlatAction("Remove", onClick = { if (store.removeRule(packageName)) onSaved() else error = store.lastError; remove = false },
                destructive = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RepeatDaysDialog(initial: Int, enabled: Boolean, resetLabel: String, zoneLabel: String,
    onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    var selection by rememberSaveable { mutableIntStateOf(initial) }
    BottomChoice("Repeat days", onDismiss, actions = {
        ChoiceActions(onDismiss, onConfirm = { onDone(selection) })
    }) {
        WeekdayPicker(selection, enabled, onChange = { selection = it })
        SecondaryText("Days start at $resetLabel ($zoneLabel). Unselected days have no limit.",
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 20.dp))
    }
}

@Composable
private fun SettingsPage(store: TimekeeperStore, now: Stamp, onBack: () -> Unit, onSetup: () -> Unit, onBackup: () -> Unit) {
    val snapshot by store.snapshots.collectAsState()
    val p = snapshot.preferences
    val c = LocalOneUi.current
    val context = LocalContext.current
    var settingsError by rememberSaveable { mutableStateOf<String?>(null) }
    var resetDialog by remember { mutableStateOf(false) }
    var appearanceDialog by remember { mutableStateOf(false) }
    var strictDialog by remember { mutableStateOf(false) }
    val backup by store.backups.status.collectAsState()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!store.setPreferences(store.current.preferences.copy(notifications = granted))) settingsError = store.lastError
    }
    PageBar("Settings", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionLabel("Allowances")
        Group {
            SettingsRow("Reset time", UiTime.reset(context, p.resetMinute, store.zone), description = "Reset uses ${p.zoneId}.",
                enabled = !store.isStrict(), onClick = { settingsError = null; resetDialog = true })
            GroupDivider()
            ToggleSettingsRow("Strict mode", store.isStrict(), enabled = !store.isStrict(),
                description = if (store.isStrict()) "Rules stay locked until the next reset." else "Lock rule changes until the next reset.") {
                if (it) { settingsError = null; strictDialog = true }
            }
            GroupDivider()
            ToggleSettingsRow("Emergency allowance", p.emergency, enabled = !store.isStrict(),
                description = "Once per app each reset day, borrow up to 2 minutes from the remaining next interval during a cooldown.") {
                if (!store.setPreferences(p.copy(emergency = it))) settingsError = store.lastError
            }
        }
        SectionLabel("Display")
        Group {
            SettingsRow("Appearance", p.appearance.name.lowercase().replaceFirstChar { it.titlecase() }, onClick = { settingsError = null; appearanceDialog = true })
            GroupDivider()
            ToggleSettingsRow("Remaining-time notification", p.notifications,
                description = "Show time left while a limited app is active.") { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (!store.setPreferences(p.copy(notifications = enabled))) settingsError = store.lastError
            }
        }
        if (p.notifications) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val blocked = remember(now.wallMs / 1000, p.notifications) {
                (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) ||
                    !manager.areNotificationsEnabled() || manager.getNotificationChannel("remaining")?.importance == NotificationManager.IMPORTANCE_NONE
            }
            if (blocked) Group(Modifier.padding(top = 12.dp)) {
                SettingsRow("Notifications blocked by Android", description = "The preference is on, but Android is preventing notifications. Open notification settings to allow them.", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                })
            }
        }
        SectionLabel("Backup")
        Group {
            SettingsRow("Back up and restore", value = when {
                backup.error != null -> "Backup needs attention"
                backup.writing || backup.changed -> "Updating backup"
                backup.connected && backup.savedAtMs > 0 -> "Backup saved"
                else -> "Not set up"
            }, description = "Keep your app timers when reinstalling or changing phones.", onClick = onBackup)
        }
        SectionLabel("Protection")
        Group {
            SettingsRow("Setup and permissions", onClick = onSetup)
            GroupDivider()
            SettingsRow("Your data", description = "No account, ads, analytics, or direct internet access. Optional timer backups go only to the folder you choose. A cloud storage provider may sync that folder.")
        }
        val versionName = remember(context) {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "Unknown"
        }
        settingsError?.let { SecondaryText(it, color = c.error, modifier = Modifier.padding(20.dp)) }
        Text("Timekeeper $versionName", color = c.secondary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
    }
    if (resetDialog) DurationDialog("Daily reset time", p.resetMinute, maximumMinutes = 1439, clockTime = true, allowZero = true, externalError = settingsError,
        onDismiss = { resetDialog = false }) {
        if (store.setPreferences(store.current.preferences.copy(resetMinute = it))) resetDialog = false
        else settingsError = store.lastError
    }
    if (appearanceDialog) BottomChoice("Appearance", { appearanceDialog = false }) {
        settingsError?.let { SecondaryText(it, color = c.error) }
        Appearance.entries.forEach { value ->
            Row(Modifier.fillMaxWidth().selectable(selected = value == p.appearance, role = Role.RadioButton, onClick = {
                    if (store.setPreferences(store.current.preferences.copy(appearance = value))) appearanceDialog = false
                    else settingsError = store.lastError
                }).heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == p.appearance, onClick = null)
                Text(if (value == Appearance.SYSTEM) "Use system setting" else value.name.lowercase().replaceFirstChar { it.titlecase() },
                    color = c.text, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
    if (strictDialog) BottomChoice("Turn on strict mode?", { strictDialog = false }) {
        settingsError?.let { SecondaryText(it, color = c.error) }
        Text("Existing limits cannot be changed, paused, or removed until the next reset. You can still add new app limits. Emergency access must be enabled beforehand. Android still lets you disable this service or uninstall Timekeeper.", color = c.secondary)
        if (snapshot.rules.isEmpty()) Text("Add an app limit first.", color = c.secondary, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            FlatAction("Cancel", onClick = { strictDialog = false }, modifier = Modifier.weight(1f))
            FlatAction("Turn on", onClick = { if (store.activateStrict()) strictDialog = false else settingsError = store.lastError ?: "Add an enabled limit first." },
                enabled = snapshot.rules.isNotEmpty(), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SetupPage(store: TimekeeperStore, now: Stamp, onBack: () -> Unit) {
    val context = LocalContext.current
    val health by TimekeeperService.health.collectAsState()
    val c = LocalOneUi.current
    val enabledServices = remember(now.wallMs / 1000) { context.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) }
    val usageAllowed = remember(now.wallMs / 1000) { hasUsageAccess(context) }
    val enabled = enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName &&
        it.resolveInfo.serviceInfo.name == TimekeeperService::class.java.name }
    val power = context.getSystemService(PowerManager::class.java)
    PageBar("Setup", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        SectionLabel("Dashboard")
        Group {
            SettingsRow("Usage access", value = if (usageAllowed) "Allowed" else "Not allowed",
                description = "Optional access for screen-time charts and app usage. Choose Timekeeper in Usage access to allow it.", onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                })
        }
        SectionLabel("App blocking")
        Group {
            SettingsRow("Accessibility service", value = if (enabled) "Enabled" else "Not enabled",
                description = "Open Installed apps in Accessibility, select Timekeeper, and enable its service.", onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                })
            GroupDivider()
            SettingsRow("How access is used", description = "Timekeeper checks window package names to identify the active app and shows a blocking overlay. Android grants broad accessibility access; Timekeeper does not read your messages, passwords, on-screen text, or images.")
        }
        SectionLabel(if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) "Samsung background settings" else "Background settings")
        Group {
            SettingsRow("Battery restrictions", value = if (power.isIgnoringBatteryOptimizations(context.packageName)) "Unrestricted" else "Check battery settings",
                description = "In App info, open Battery and select Unrestricted if available.", onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
                })
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                GroupDivider()
                SettingsRow("Sleeping apps", description = "In Samsung Battery settings, open Background usage limits. Keep Timekeeper out of Sleeping and Deep sleeping apps; add it to Never auto sleeping apps if available.")
            }
        }
        SectionLabel("Status")
        Group {
            SettingsRow("Tracking service", value = if (health.isProtecting(SystemClock.elapsedRealtime(), store.current.storageIssue == null)) "Protection active" else "Needs attention")
            if (health.overlayError) SettingsRow("Blocking screen unavailable", description = "Turn the accessibility service off and on, then reopen Timekeeper.")
            store.current.storageIssue?.let { SettingsRow("Saved data unavailable", description = it) }
        }
        Text("If Android blocks enabling a sideloaded accessibility service, App info may offer Allow restricted settings in its menu. Enable this yourself only after reviewing the app's access.\n\nThis version tracks the focused app. Background playback and picture-in-picture do not count. Split screen and Samsung pop-up view still need testing on your phone.\n\nUsage Access imports earlier foreground usage, including available history from service interruptions. Cooldowns and saved usage remain stored.",
            color = c.secondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
    }
}
