package dev.timekeeper.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.timekeeper.core.Weekdays
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekdayPicker(mask: Int, enabled: Boolean = true, onChange: (Int) -> Unit) {
    val c = LocalOneUi.current
    val presets = listOf("Every day" to Weekdays.EVERY_DAY, "Weekdays" to Weekdays.WEEKDAYS, "Weekends" to Weekdays.WEEKENDS)
    val measure = rememberTextMeasurer()
    val presetWidth = with(LocalDensity.current) {
        presets.maxOf { measure.measure(it.first, MaterialTheme.typography.bodyMedium).size.width }.toDp()
    } + 32.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val rowWidth = maxOf(maxWidth, presetWidth * 3)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            SingleChoiceSegmentedButtonRow(Modifier.width(rowWidth).heightIn(min = 48.dp)) {
                presets.forEachIndexed { index, (label, value) ->
                    SegmentedButton(selected = mask == value, onClick = { onChange(value) }, enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, presets.size), icon = {},
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = c.accent.copy(alpha = .12f),
                            activeContentColor = c.accent, inactiveContainerColor = c.group, inactiveContentColor = c.secondary),
                        border = BorderStroke(1.dp, if (mask == value) c.accent else c.divider),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
        }
    }
    val days = localeWeekdays()
    val dayStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val daySize = days.map { measure.measure(it.getDisplayName(TextStyle.NARROW, Locale.getDefault()), dayStyle).size }
    val circleSize = maxOf(40.dp, with(LocalDensity.current) { daySize.maxOf { maxOf(it.width, it.height) }.toDp() } + 12.dp)
    val targetSize = maxOf(48.dp, circleSize + 8.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        val columns = when {
            maxWidth >= targetSize * 7 -> 7
            maxWidth >= targetSize * 4 -> 4
            maxWidth >= targetSize * 3 -> 3
            maxWidth >= targetSize * 2 -> 2
            else -> 1
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            days.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    row.forEach { day ->
                        val selected = Weekdays.includes(mask, day)
                        Box(Modifier.size(targetSize).clip(CircleShape)
                            .toggleable(selected, enabled = enabled, role = Role.Checkbox,
                                onValueChange = { onChange(Weekdays.toggle(mask, day)) })
                            .semantics { contentDescription = day.getDisplayName(TextStyle.FULL, Locale.getDefault()) },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(circleSize).clip(CircleShape)
                                .background(if (selected) c.accent.copy(alpha = .10f) else c.group)
                                .border(if (selected) 1.5.dp else 1.dp,
                                    if (selected) c.accent else c.secondary.copy(alpha = .8f), CircleShape),
                                contentAlignment = Alignment.Center) {
                                Text(day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                    color = if (selected) c.accent else c.secondary, style = dayStyle)
                            }
                        }
                    }
                }
            }
        }
    }
}
