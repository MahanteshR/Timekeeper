package dev.timekeeper.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import dev.timekeeper.core.WheelValues
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.timekeeper.app.data.Appearance
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class OneUiColors(val background: Color, val group: Color, val text: Color, val secondary: Color,
    val accent: Color, val divider: Color, val enabledStrip: Color,
    val error: Color = Color(0xFFC53939))
val LocalOneUi = staticCompositionLocalOf { OneUiColors(Color.White, Color.White, Color.Black,
    Color.Gray, Color.Blue, Color.LightGray, Color.LightGray) }

@Composable
fun TimekeeperTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.DARK -> true
        Appearance.LIGHT -> false
        Appearance.SYSTEM -> isSystemInDarkTheme()
    }
    val family = FontFamily.Default
    val colors = if (dark) OneUiColors(Color.Black, Color(0xFF171717), Color(0xFFF6F6F6),
        Color(0xFFAAAAAA), Color(0xFF69A6FF), Color(0xFF303030), Color(0xFF172A43), Color(0xFFFF8A80))
        else OneUiColors(Color(0xFFF4F4F4), Color.White, Color(0xFF121212), Color(0xFF666666),
            Color(0xFF0068CE), Color(0xFFE6E6E6), Color(0xFFE3EEFC))
    val scheme = if (dark) darkColorScheme(primary = colors.accent, background = colors.background,
        surface = colors.group, onSurface = colors.text, onBackground = colors.text, error = colors.error)
        else lightColorScheme(primary = colors.accent, background = colors.background,
            surface = colors.group, onSurface = colors.text, onBackground = colors.text, error = colors.error)
    CompositionLocalProvider(LocalOneUi provides colors) {
        MaterialTheme(colorScheme = scheme, typography = Typography(
            bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 23.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 19.sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontFamily = family, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            labelLarge = androidx.compose.ui.text.TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        ), content = content)
    }
}

@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(LocalOneUi.current.group), content = content)
}

@Composable
fun SettingsRow(title: String, value: String? = null, description: String? = null,
    enabled: Boolean = true, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .padding(horizontal = 20.dp, vertical = 17.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) c.text else c.secondary, style = MaterialTheme.typography.bodyLarge)
            if (value != null) Text(value, color = if (enabled) c.accent else c.secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 3.dp))
            if (description != null) Text(description, color = c.secondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun GroupDivider() { HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = LocalOneUi.current.divider) }

@Composable
fun OneSwitch(checked: Boolean, enabled: Boolean = true, label: String, onChange: ((Boolean) -> Unit)? = null) {
    val c = LocalOneUi.current
    val position by animateDpAsState(if (checked) 25.dp else 3.dp, label = "switch")
    Box(Modifier.size(width = 52.dp, height = 48.dp)
        .then(if (onChange == null) Modifier.clearAndSetSemantics {} else Modifier
            .semantics { contentDescription = label }
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(20.dp))
            .background(if (checked) c.accent else Color(0xFF9C9FA4))) {
            val density = LocalDensity.current
            Box(Modifier.offset { IntOffset(with(density) { position.roundToPx() }, with(density) { 3.dp.roundToPx() }) }
                .size(22.dp).clip(RoundedCornerShape(20.dp)).background(Color.White))
        }
    }
}

@Composable
fun ToggleSettingsRow(title: String, checked: Boolean, description: String? = null,
    enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 20.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) c.text else c.secondary, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, color = c.secondary, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 5.dp)) }
        }
        OneSwitch(checked, enabled, title)
    }
}

@Composable
fun ToggleStrip(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalOneUi.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.enabledStrip)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = "App limit" }
        .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (checked) "On" else "Off", color = if (enabled) c.accent else c.secondary,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OneSwitch(checked, enabled, "App limit")
    }
}

@Composable
fun FlatAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, destructive: Boolean = false) {
    val c = LocalOneUi.current
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) c.error else c.accent,
            disabledContentColor = c.secondary)) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

enum class UiSymbol { Back, Add, More, Search, Close, Dashboard, Limits }

/** Drawn at a fixed dp size so navigation icons don't grow with the text scale. */
@Composable
fun OneIcon(symbol: UiSymbol, tint: Color, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.size(24.dp)) {
        fun point(x: Float, y: Float) = Offset(
            (if (symbol == UiSymbol.Back && rtl) 24f - x else x) * size.width / 24f,
            y * size.height / 24f)
        val stroke = 2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, point(x1, y1), point(x2, y2), strokeWidth = stroke, cap = StrokeCap.Round)
        when (symbol) {
            UiSymbol.Back -> {
                val p = Path().apply {
                    val a = point(15f, 5f); val b = point(8f, 12f); val c = point(15f, 19f)
                    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y)
                }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            UiSymbol.Add -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            UiSymbol.More -> listOf(5f, 12f, 19f).forEach { drawCircle(tint, 1.5.dp.toPx(), point(12f, it)) }
            UiSymbol.Search -> { drawCircle(tint, size.width * 6.5f / 24f, point(10.5f, 10.5f), style = Stroke(stroke)); line(15.5f, 15.5f, 21f, 21f) }
            UiSymbol.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            UiSymbol.Dashboard -> { line(5f, 18f, 5f, 10f); line(12f, 18f, 12f, 5f); line(19f, 18f, 19f, 13f) }
            UiSymbol.Limits -> { drawCircle(tint, size.width * 8f / 24f, point(12f, 12f), style = Stroke(stroke)); line(12f, 7f, 12f, 12f); line(12f, 12f, 15f, 14f) }
        }
    }
}

@Composable
fun ToolbarIcon(symbol: UiSymbol, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalOneUi.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        OneIcon(symbol, if (enabled) c.text else c.secondary, Modifier.semantics { contentDescription = label })
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, color = LocalOneUi.current.secondary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
fun PageBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolbarIcon(UiSymbol.Back, "Back", onClick = onBack)
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), color = LocalOneUi.current.text)
        actions()
    }
}

@Composable
fun BottomChoice(title: String, onDismiss: () -> Unit,
    headerAction: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    val c = LocalOneUi.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            // The full-window dialog needs its own outside-tap target.
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss
            ).clearAndSetSemantics {})
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(26.dp)).background(c.group)
                    .pointerInput(Unit) { detectTapGestures { /* Consume taps on the card, not the scrim. */ } }) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() })
                        headerAction?.invoke()
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp).padding(bottom = if (actions == null) 20.dp else 16.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                    }
                    actions?.let {
                        HorizontalDivider(color = c.divider)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { it() }
                    }
                }
            }
        }
    }
}

/** A shared, stationary action row for setting pickers; content may scroll independently. */
@Composable
fun ChoiceActions(onCancel: () -> Unit, onConfirm: () -> Unit, confirmLabel: String = "Done") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlatAction("Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(24.dp).background(LocalOneUi.current.divider))
        FlatAction(confirmLabel, onClick = onConfirm, modifier = Modifier.weight(1f))
    }
}

@Composable
fun DurationDialog(title: String, initialMinutes: Int, maximumMinutes: Int = 1439,
    allowZero: Boolean = false, allowFullDay: Boolean = false, clockTime: Boolean = false,
    externalError: String? = null,
    onDismiss: () -> Unit, onDone: (Int) -> Unit) {
    val bounds = dev.timekeeper.core.PickerBounds(maximumMinutes, allowFullDay)
    val useDays = maximumMinutes > 1440 && !clockTime
    val dayBounds = dev.timekeeper.core.DayDurationBounds(maximumMinutes)
    val initial = initialMinutes.coerceIn(0, maximumMinutes)
    var fullDay by rememberSaveable { mutableStateOf(allowFullDay && initial == 1440) }
    var days by rememberSaveable { mutableIntStateOf(if (useDays) initial / 1440 else 0) }
    var hours by rememberSaveable { mutableIntStateOf(if (useDays) initial % 1440 / 60 else (initial / 60).coerceAtMost(bounds.maximumHour)) }
    var minutes by rememberSaveable { mutableIntStateOf(if (fullDay) 0 else initial % 60) }
    var typed by rememberSaveable { mutableStateOf(false) }
    var daysText by rememberSaveable { mutableStateOf(days.toString()) }
    var hoursText by rememberSaveable { mutableStateOf(hours.toString()) }
    var minutesText by rememberSaveable { mutableStateOf(minutes.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val c = LocalOneUi.current
    val focus = LocalFocusManager.current
    fun maxHour(d: Int) = if (useDays) dayBounds.maximumHour(d) else bounds.maximumHour
    fun maxMinute(d: Int, h: Int) = if (useDays) dayBounds.maximumMinute(d, h) else bounds.maximumMinute(h)
    fun readTyped(): Int? {
        val result = if (useDays) dayBounds.parse(daysText, hoursText, minutesText)?.totalMinutes
            else bounds.parse(hoursText, minutesText)
        if (result == null) error = when {
            hoursText.isBlank() || minutesText.isBlank() || (useDays && daysText.isBlank()) -> "Fill in each time field."
            clockTime -> "Use hours 00–23 and minutes 00–59."
            else -> "Choose ${durationLabel(maximumMinutes * 60_000L)} or less."
        }
        return result
    }
    fun confirm() {
        val result = if (fullDay) 1440 else if (typed) readTyped() else days * 1440 + hours * 60 + minutes
        if (result != null) {
            if (!allowZero && result == 0) error = "Choose at least 1 minute."
            else { focus.clearFocus(); onDone(result) }
        }
    }
    BottomChoice(title, onDismiss,
        headerAction = {
            if (!fullDay) TextButton(onClick = {
                if (typed) readTyped()?.let { value ->
                    days = if (useDays) value / 1440 else 0
                    hours = if (useDays) value % 1440 / 60 else value / 60
                    minutes = value % 60; typed = false; error = null; focus.clearFocus()
                } else { daysText = days.toString(); hoursText = hours.toString(); minutesText = minutes.toString(); typed = true }
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (typed) "Wheels" else "Keypad", style = MaterialTheme.typography.bodyMedium, color = c.accent)
            }
        },
        actions = { ChoiceActions(onDismiss, ::confirm) }) {
        if (!fullDay) {
            val labels = if (useDays) listOf("Days", "Hours", "Minutes") else listOf("Hours", "Minutes")
            val measure = rememberTextMeasurer()
            val labelWidth = labels.maxOf { measure.measure(it, MaterialTheme.typography.bodyMedium).size.width }
            val numberWidth = measure.measure("00", androidx.compose.ui.text.TextStyle(fontSize = 36.sp)).size.width
            val columnWidth = maxOf(84.dp, with(LocalDensity.current) { maxOf(labelWidth, numberWidth).toDp() } + 24.dp)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val rowWidth = maxOf(maxWidth, columnWidth * labels.size)
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Row(Modifier.width(rowWidth).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (useDays) {
                            if (typed) OutlinedTextField(daysText, { daysText = it.filter(Char::isDigit).take(1); error = null },
                                label = { Text("Days") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                            else NumberWheel("Days", days, dayBounds.maximumDay, Modifier.weight(1f)) {
                                days = it; hours = hours.coerceAtMost(maxHour(it)); minutes = minutes.coerceAtMost(maxMinute(it, hours)); error = null
                            }
                        }
                        if (typed) {
                            OutlinedTextField(hoursText, { hoursText = it.filter(Char::isDigit).take(2); error = null },
                                label = { Text("Hours") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }))
                            OutlinedTextField(minutesText, { minutesText = it.filter(Char::isDigit).take(2); error = null },
                                label = { Text("Minutes") }, singleLine = true, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { confirm() }))
                        } else {
                            NumberWheel("Hours", hours, maxHour(days), Modifier.weight(1f), circular = true) {
                                hours = it; minutes = minutes.coerceAtMost(maxMinute(days, it)); error = null
                            }
                            NumberWheel("Minutes", minutes, maxMinute(days, hours), Modifier.weight(1f), circular = true) { minutes = it; error = null }
                        }
                    }
                    if (!typed && !useDays) {
                        val labelHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() } + 8.dp
                        Text(":", color = c.text, fontSize = 32.sp,
                            modifier = Modifier.align(Alignment.Center).offset(y = labelHeight / 2 + 4.dp).clearAndSetSemantics {})
                    }
                }
            }
        }
        if (allowFullDay && maximumMinutes == 1440) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)
                .toggleable(fullDay, role = Role.Switch, onValueChange = { fullDay = it; error = null; focus.clearFocus() })
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Full day · 24 hours", style = MaterialTheme.typography.bodyMedium, color = c.text, modifier = Modifier.weight(1f))
                OneSwitch(fullDay, label = "Full day")
            }
        }
        if (useDays) SecondaryText("Set all values to 0 for no cooldown.", modifier = Modifier.padding(top = 12.dp, start = 8.dp))
        error?.let { SecondaryText(it, color = c.error, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
        externalError?.let { SecondaryText(it, color = c.error, modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun NumberWheel(label: String, number: Int, maximum: Int, modifier: Modifier = Modifier,
    circular: Boolean = false, onChange: (Int) -> Unit) {
    val c = LocalOneUi.current
    val count = maximum + 1
    val first = if (circular) WheelValues.initialIndex(number, count) else number
    val state = key(count) { rememberLazyListState(initialFirstVisibleItemIndex = first) }
    val scope = rememberCoroutineScope()
    val latestChange by rememberUpdatedState(onChange)
    val itemHeight = maxOf(56.dp, with(LocalDensity.current) { 42.sp.toDp() } + 16.dp)
    fun centeredIndex(): Int? {
        val info = state.layoutInfo
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
        return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
    }
    val selected by remember(state) { derivedStateOf { centeredIndex() ?: first } }
    LaunchedEffect(state, count) {
        snapshotFlow { centeredIndex() }.distinctUntilChanged().collect { index ->
            index?.let { latestChange(if (circular) WheelValues.valueAt(it, count) else it) }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = c.secondary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp).clearAndSetSemantics {})
        Box(Modifier.fillMaxWidth().height(itemHeight * 3)) {
            LazyColumn(state = state, flingBehavior = rememberSnapFlingBehavior(state),
                contentPadding = PaddingValues(vertical = itemHeight),
                modifier = Modifier.fillMaxSize().clearAndSetSemantics {
                    contentDescription = label
                    stateDescription = number.toString()
                    progressBarRangeInfo = ProgressBarRangeInfo(number.toFloat(), 0f..maximum.toFloat(), (maximum - 1).coerceAtLeast(0))
                    setProgress { requested ->
                        val value = requested.toInt().coerceIn(0, maximum)
                        val target = if (circular) selected - WheelValues.valueAt(selected, count) + value else value
                        latestChange(value)
                        scope.launch { state.scrollToItem(target) }
                        true
                    }
                }) {
                items(if (circular) count * 1000 else count, key = { it }) { index ->
                    val value = if (circular) WheelValues.valueAt(index, count) else index
                    Box(Modifier.fillMaxWidth().height(itemHeight).clickable {
                        scope.launch { state.animateScrollToItem(index) }
                    }, contentAlignment = Alignment.Center) {
                        Text(String.format(Locale.getDefault(), "%02d", value),
                            fontSize = if (index == selected) 36.sp else 28.sp,
                            fontWeight = if (index == selected) FontWeight.Medium else FontWeight.Normal,
                            color = if (index == selected) c.text else c.secondary.copy(alpha = .65f))
                    }
                }
            }
        }
    }
}

fun durationLabel(milliseconds: Long): String {
    val total = milliseconds / 60_000
    return when {
        total == 0L && milliseconds > 0 -> "${milliseconds / 1000} sec"
        total >= 1440 -> buildList {
            val days = total / 1440
            add("$days ${if (days == 1L) "day" else "days"}")
            if (total % 1440 / 60 > 0) add("${total % 1440 / 60} hr")
            if (total % 60 > 0) add("${total % 60} min")
        }.joinToString(" ")
        total < 60 -> "$total min"
        total % 60 == 0L -> "${total / 60} hr"
        else -> "${total / 60} hr ${total % 60} min"
    }
}
