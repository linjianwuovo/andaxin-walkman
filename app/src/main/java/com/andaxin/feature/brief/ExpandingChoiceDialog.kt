package com.andaxin.feature.brief

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.LocalSamplingBackdrop
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.design.safeGlassSurface
import com.andaxin.data.brief.Field
import com.andaxin.shell.pressClick
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem

@Composable
internal fun RepairChoiceMenu(field: Field.Choice, value: String, onPick: (String) -> Unit) {
    var open by remember(field.key) { mutableStateOf(false) }
    val rowCount = field.groups.sumOf { it.options.size }
    val titleHeight = 32.dp
    val groupHeight = 30.dp
    val rowHeight = 48.dp
    RepairMenu(
        expanded = open,
        onExpandedChange = { open = it },
        anchorLabel = field.label,
        value = value,
        hint = "点这里选",
        height = minOf(344.dp, 20.dp + titleHeight + groupHeight * field.groups.size + rowHeight * rowCount),
    ) { close ->
        val activeGroup = field.groups.indexOfFirst { value in it.options }
        val firstRow = if (activeGroup < 0) 0 else field.groups.take(activeGroup).sumOf { 1 + it.options.size }
        MenuCaption(field.label, titleHeight, title = true)
        LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = firstRow)) {
            field.groups.forEach { group ->
                item { MenuCaption(group.name, groupHeight) }
                items(group.options) { option ->
                    GlasmioMenuItem(
                        title = optionTail(group.name, option),
                        rowHeight = rowHeight,
                        systemName = "checkmark".takeIf { value == option },
                        onClick = { onPick(option); close() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun RepairTimeMenu(
    field: Field.TimeRange,
    start: String,
    end: String,
    onApply: (String, String) -> Unit,
) {
    var open by remember(field.key, field.endKey) { mutableStateOf(false) }
    var draftStart by remember(field.key) { mutableStateOf("") }
    var draftEnd by remember(field.endKey) { mutableStateOf("") }
    val value = when {
        start.isNotBlank() && end.isNotBlank() -> "$start – $end"
        start.isNotBlank() -> "开始 $start"
        end.isNotBlank() -> "结束 $end"
        else -> ""
    }
    RepairMenu(
        expanded = open,
        onExpandedChange = { expanded ->
            if (expanded && !open) {
                draftStart = start
                draftEnd = end
            }
            open = expanded
        },
        anchorLabel = field.label,
        value = value,
        hint = "选时间（可选）",
        height = 266.dp,
    ) { close ->
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TimeColumn("开始", field.options, draftStart, Modifier.weight(1f)) { draftStart = it }
            TimeColumn("结束", field.options, draftEnd, Modifier.weight(1f)) { draftEnd = it }
        }
        GlasmioMenuItem(
            title = "完成",
            rowHeight = 48.dp,
            systemName = "checkmark",
            fontWeight = FontWeight.SemiBold,
            onClick = { onApply(draftStart, draftEnd); close() },
        )
    }
}

@Composable
private fun TimeColumn(
    label: String,
    options: List<String>,
    value: String,
    modifier: Modifier,
    onPick: (String) -> Unit,
) {
    Column(modifier) {
        MenuCaption(label, 30.dp, title = true)
        val first = options.indexOf(value) + 1
        LazyColumn(Modifier.weight(1f), state = rememberLazyListState(initialFirstVisibleItemIndex = first)) {
            item {
                TimeItem("不选", value.isBlank()) { onPick("") }
            }
            items(options) { time ->
                TimeItem(time, value == time) { onPick(time) }
            }
        }
    }
}

@Composable
private fun TimeItem(title: String, selected: Boolean, onClick: () -> Unit) {
    GlasmioMenuItem(
        title = title,
        rowHeight = 44.dp,
        systemName = "checkmark".takeIf { selected },
        fontWeight = FontWeight.SemiBold.takeIf { selected },
        onClick = onClick,
    )
}

@Composable
private fun RepairMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    anchorLabel: String,
    value: String,
    hint: String,
    height: Dp,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val ink = LocalInk.current
    GlasmioMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        itemCount = 1,
        modifier = Modifier.fillMaxWidth(),
        backdrop = LocalSamplingBackdrop.current ?: LocalGlassBackdrop.current,
        alignToAnchorStart = true,
        menuWidth = 304.dp,
        heightOverride = height,
        exportChildBackdrop = false,
        anchor = { toggle ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .safeGlassSurface(Radius.field)
                    .pressClick(shrink = false, onClick = toggle)
                    .padding(horizontal = Gap.md, vertical = Gap.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gap.sm),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(anchorLabel, fontSize = Type.meta, color = ink.muted)
                    Text(
                        value.ifBlank { hint },
                        fontSize = Type.title,
                        color = if (value.isBlank()) ink.faint else ink.strong,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("⌄", fontSize = Type.body, color = ink.faint)
            }
        },
    ) { _, close -> content(close) }
}

@Composable
private fun MenuCaption(label: String, height: Dp, title: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = Gap.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = if (title) Type.meta else Type.micro,
            fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal,
            color = if (title) MaterialTheme.colorScheme.onSurface else LocalInk.current.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun optionTail(group: String, opt: String): String =
    opt.removePrefix("$group · ").removePrefix("$group ").ifBlank { opt }
