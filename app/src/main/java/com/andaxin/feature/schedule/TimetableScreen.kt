package com.andaxin.feature.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.schedule.Periods
import com.andaxin.core.schedule.shortRoom
import com.andaxin.data.TimetableSlot
import com.andaxin.shell.Refreshable
import com.andaxin.shell.Sheet
import com.andaxin.shell.pressClick
import com.andaxin.shell.adxViewModel
import com.andaxin.shell.ErrorLine
import com.andaxin.shell.ShelfTail
import com.andaxin.shell.registerShelfNavigationViewport
import com.andaxin.state.TimetableModel
import io.github.neoruaa.glasmio.ui.GlasmioSegmentedControl
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay

private val DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val PERIOD_TIMES = Periods.START_AT
private val GRID_CELL_SHAPE = RoundedCornerShape(5.dp)
private const val ROW_HEIGHT = 34
private const val GUTTER = 30
private const val HEADER_HEIGHT = 32
private val VIEW_MODES = listOf(true to "单日", false to "整周")

private fun rowHeightFor(viewport: Dp, extraRows: Int, fontScale: Float): Dp =
    ((viewport - 256.dp) / (PERIOD_TIMES.size + extraRows).toFloat())
        .coerceIn(ROW_HEIGHT.dp, 44.dp)
        .coerceAtLeast(ROW_HEIGHT.dp * fontScale)

@Composable
fun TimetableScreen(modifier: Modifier = Modifier) {
    val model = adxViewModel { TimetableModel(it.timetable, it.store) }
    val state by model.state.collectAsStateWithLifecycle()
    val ink = LocalInk.current

    /*
     * 周几、现在第几节，都跟着真实时钟走：每分钟重新取一次。
     * 以前这里是 `remember { Calendar... }` 一次算完就不动了 —— 网格开着跨过一节课，
     * 高亮还停在上一节，看着像课表坏了。
     */
    var minute by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minute++
        }
    }
    val clock = remember(minute) { Calendar.getInstance() }
    val today = Periods.today(clock)
    val weekOffset = state.loadingWeekOffset ?: state.weekOffset
    val todayInWeek = today.takeIf { weekOffset == 0 }
    val periodNow = todayInWeek?.let { Periods.periodNow(clock) }
    val showSlots = state.slotsWeekOffset == weekOffset
    val slots = if (showSlots) state.slots else emptyList()
    val loading = state.loadingWeekOffset != null || (state.firstLoad && slots.isEmpty())
    val error = state.error.takeUnless { loading }
    val weekOffsets = remember(state.weeks, state.now) {
        if (state.now > 0 && state.weeks.isNotEmpty()) {
            state.weeks.map { it - state.now }.plus(0).distinct().sorted()
        } else listOf(0, 1)
    }
    val monday = remember(clock, weekOffset) { Periods.weekMonday(clock, weekOffset) }
    val calendarDates = remember(monday) {
        DAYS.indices.map { delta ->
            (monday.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, delta) }
        }
    }
    val dateNumbers = remember(calendarDates) { calendarDates.map { it.get(Calendar.DAY_OF_MONTH) } }
    val dateLabels = remember(calendarDates) {
        val formatter = SimpleDateFormat("M月d日", Locale.SIMPLIFIED_CHINESE).apply { timeZone = monday.timeZone }
        calendarDates.map { formatter.format(it.time) }
    }
    var dayOverride by rememberSaveable { mutableIntStateOf(0) }
    var showDayMode by rememberSaveable { mutableStateOf(false) }
    val day = dayOverride.takeIf { it in 1..7 } ?: today
    val pickDay: (Int) -> Unit = {
        dayOverride = it
        showDayMode = true
    }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val lessons = remember(slots) { slots.filter { it.startPeriod >= 1 } }
    val mornings = remember(slots) { slots.filter { it.startPeriod < 1 } }
    val morningRows = remember(mornings) { mornings.groupingBy { it.day }.eachCount().values.maxOrNull() ?: 0 }
    val courseColors = remember(state.courseColors) {
        state.courseColors.mapValues { (_, index) -> COURSE_COLORS[index.mod(COURSE_COLORS.size)] }
    }
    val dayLessons = remember(lessons, day) { lessons.filter { it.day == day } }
    val dayMornings = remember(mornings, day) { mornings.filter { it.day == day } }

    Refreshable({ model.refresh() }, modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            val scroll = rememberScrollState()
            registerShelfNavigationViewport(scroll)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = Gap.screen),
            ) {
                Spacer(Modifier.height(Gap.xs))
                WeekNavigation(
                    offset = weekOffset,
                    offsets = weekOffsets,
                    currentWeek = state.now,
                    onWeek = { if (it != weekOffset) model.load(it) },
                    onToday = {
                        dayOverride = 0
                        showDayMode = true
                        if (weekOffset != 0 || error != null) model.load(0)
                    },
                )
                Spacer(Modifier.height(Gap.xs))
                WeekDateStrip(dateNumbers, dateLabels, todayInWeek, day, slots, pickDay)
                Spacer(Modifier.height(Gap.sm))
                TimetableViewHeader(
                    title = if (showDayMode) DAYS[day - 1] else weekOverviewLabel(weekOffset),
                    subtitle = if (showDayMode) {
                        dateLabels[day - 1] + if (dayLessons.isEmpty()) "" else " · ${dayLessons.size} 门课"
                    } else "${dateLabels.first()} – ${dateLabels.last()}",
                    showDayMode = showDayMode,
                    onMode = { showDayMode = it },
                )
                Spacer(Modifier.height(Gap.sm))

                when {
                    loading && slots.isEmpty() -> Sheet { com.andaxin.shell.EmptyState("正在加载课表…") }
                    error != null && slots.isEmpty() -> Unit
                    showDayMode && dayLessons.isEmpty() && dayMornings.isEmpty() ->
                        TimetableEmpty("${DAYS[day - 1]}无课")
                    showDayMode -> DayList(dayLessons, dayMornings, courseColors)
                    slots.isEmpty() -> TimetableEmpty("这一周没有课")
                    else -> {
                        if (lessons.isEmpty()) {
                            Text(
                                "这一周没有普通课程，仅有早间活动",
                                fontSize = Type.meta,
                                color = ink.muted,
                                modifier = Modifier.padding(bottom = Gap.xs),
                            )
                        }
                        Grid(
                            lessons, mornings, todayInWeek, periodNow,
                            rowHeightFor(viewport, morningRows, fontScale),
                            dateNumbers, dateLabels, courseColors, pickDay,
                        )
                    }
                }
                if (!loading && lessons.isNotEmpty()) {
                    Text(
                        "${weekOverviewLabel(weekOffset)} ${lessons.sumOf { it.periodCount }} 节 · ${lessons.size} 门课",
                        fontSize = Type.micro,
                        color = ink.muted,
                        modifier = Modifier.padding(top = Gap.sm),
                    )
                }
                error?.let { ErrorLine(it, retry = { model.load(weekOffset) }) }
                ShelfTail()
            }
        }
    }
}

@Composable
private fun WeekNavigation(
    offset: Int,
    offsets: List<Int>,
    currentWeek: Int,
    onWeek: (Int) -> Unit,
    onToday: () -> Unit,
) {
    val ink = LocalInk.current
    var expanded by remember { mutableStateOf(false) }
    val previous = offsets.lastOrNull { it < offset }
    val next = offsets.firstOrNull { it > offset }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Text(
                weekLabel(currentWeek, offset),
                fontSize = Type.body,
                fontWeight = FontWeight.SemiBold,
                color = ink.strong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically)
                    .semantics { contentDescription = "选择周次" }
                    .pressClick(enabled = offsets.size > 1, shrink = false) { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                offsets.forEach { value ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                weekLabel(currentWeek, value),
                                color = if (value == offset) MaterialTheme.colorScheme.primary else ink.strong,
                            )
                        },
                        onClick = { expanded = false; onWeek(value) },
                    )
                }
            }
        }
        WeekAction("‹", "上一周", previous != null) { previous?.let(onWeek) }
        WeekAction("回到今天", "回到今天", true, onToday)
        WeekAction("›", "下一周", next != null) { next?.let(onWeek) }
    }
}

@Composable
private fun WeekAction(text: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = if (text == label) Type.meta else Type.title,
        color = if (enabled) LocalInk.current.muted else LocalInk.current.ghost,
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { if (text != label) contentDescription = label }
            .pressClick(enabled = enabled, shrink = false, onClick = onClick)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = Gap.xs),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TimetableViewHeader(
    title: String,
    subtitle: String,
    showDayMode: Boolean,
    onMode: (Boolean) -> Unit,
) {
    val ink = LocalInk.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Gap.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(Gap.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = Type.meta, fontWeight = FontWeight.SemiBold, color = ink.strong, maxLines = 1)
            Text(subtitle, fontSize = Type.micro, color = ink.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        GlasmioSegmentedControl(
            items = VIEW_MODES,
            selected = showDayMode,
            onSelected = onMode,
            modifier = Modifier.width(112.dp * fontScale.coerceAtMost(1.45f)),
            height = 38.dp * fontScale,
        ) { dayMode, picked ->
            Text(
                if (dayMode) "单日" else "整周",
                fontSize = Type.meta,
                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (picked) ink.strong else ink.muted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WeekDateStrip(
    numbers: List<Int>,
    labels: List<String>,
    today: Int?,
    day: Int,
    slots: List<TimetableSlot>,
    onPick: (Int) -> Unit,
) {
    val counts = remember(slots) { slots.groupingBy { it.day }.eachCount() }
    val items = remember(labels) { DAYS.mapIndexed { index, name -> index + 1 to "$name ${labels[index]}" } }
    val ink = LocalInk.current
    val accent = MaterialTheme.colorScheme.primary
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(maxWidth, 38.dp * DAYS.size * fontScale)
        GlasmioSegmentedControl(
            items = items,
            selected = day,
            onSelected = onPick,
            modifier = Modifier.horizontalScroll(rememberScrollState()).width(width),
            height = 58.dp * fontScale,
        ) { value, picked ->
            val dateColor = if (value == today) accent else if (picked) ink.strong else ink.body
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(DAYS[value - 1].takeLast(1), color = ink.muted, fontSize = Type.tiny, lineHeight = Type.tinyLine)
                Text(
                    numbers[value - 1].toString(), color = dateColor, fontSize = Type.body,
                    style = Type.tabular, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
                Row(Modifier.height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(counts.getOrDefault(value, 0).coerceAtMost(3)) {
                        Box(Modifier.size(2.5.dp).background(dateColor.copy(alpha = 0.67f), Radius.pill))
                    }
                }
            }
        }
    }
}

@Composable
private fun Grid(
    slots: List<TimetableSlot>,
    mornings: List<TimetableSlot>,
    today: Int?,
    periodNow: Int?,
    rowH: Dp,
    numbers: List<Int>,
    labels: List<String>,
    courseColors: Map<String, Color>,
    onPickDay: (Int) -> Unit,
) {
    val ink = LocalInk.current
    val accent = MaterialTheme.colorScheme.primary
    val byDay = remember(slots) { slots.groupBy { it.day }.mapValues { (_, v) -> v.sortedBy { it.startPeriod } } }
    val morningByDay = remember(mornings) {
        mornings.groupBy { it.day }.mapValues { (_, v) -> v.sortedBy { it.course } }
    }
    val morningRows = morningByDay.values.maxOfOrNull { it.size } ?: 0
    val days = (1..5).toList() + (6..7).filter { it in byDay || it in morningByDay }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val headerHeight = HEADER_HEIGHT.dp * fontScale
    val gutterWidth = GUTTER.dp * fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gridWidth = maxOf(maxWidth, gutterWidth + 31.dp * days.size * fontScale)
        Row(Modifier.horizontalScroll(rememberScrollState()).width(gridWidth)) {
            Column(Modifier.width(gutterWidth)) {
                Spacer(Modifier.height(headerHeight))
                if (morningRows > 0) {
                    Box(Modifier.fillMaxWidth().height(rowH * morningRows), contentAlignment = Alignment.TopCenter) {
                        Text("早", fontSize = Type.micro, color = ink.muted, modifier = Modifier.padding(top = Gap.xxs))
                    }
                }
                PERIOD_TIMES.forEachIndexed { index, start ->
                    val activePeriod = periodNow == index + 1
                    Column(
                        Modifier.fillMaxWidth().height(rowH).padding(top = Gap.xxs),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(start, fontSize = Type.tinier, lineHeight = Type.tinierLine, color = ink.muted, style = Type.tabular)
                        Text(
                            (index + 1).toString(), fontSize = Type.tiny,
                            lineHeight = Type.tinyLine, color = if (activePeriod) accent else ink.body,
                        )
                    }
                }
            }
            days.forEach { day ->
                val isToday = day == today
                Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                    Column(
                        Modifier.fillMaxWidth().height(headerHeight).clip(Radius.tag)
                            .semantics { contentDescription = "${DAYS[day - 1]} ${labels[day - 1]} 整周课表" }
                            .clickable(onClickLabel = "看${DAYS[day - 1]}日程") { onPickDay(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(DAYS[day - 1].takeLast(1), fontSize = Type.tiny, color = ink.muted, lineHeight = Type.tinyLine)
                        Text(
                            numbers[day - 1].toString(), fontSize = Type.meta, lineHeight = Type.microLine,
                            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isToday) accent else ink.strong,
                        )
                        Box(Modifier.height(3.dp).padding(top = 1.dp)) {
                            if (isToday) Box(Modifier.width(13.dp).height(2.dp).background(accent, Radius.tag))
                        }
                    }
                    val early = morningByDay[day].orEmpty()
                    early.forEach { slot ->
                        CourseCell(slot, 1, false, rowH, courseColors[slot.course] ?: COURSE_COLORS[0])
                    }
                    if (early.size < morningRows) Spacer(Modifier.height(rowH * (morningRows - early.size)))
                    val daily = byDay[day].orEmpty()
                    var cursor = 1
                    daily.forEachIndexed { index, slot ->
                        val nextStart = daily.getOrNull(index + 1)?.startPeriod ?: Int.MAX_VALUE
                        val span = minOf(slot.periodCount, nextStart - slot.startPeriod).coerceAtLeast(1)
                        val start = maxOf(cursor, slot.startPeriod)
                        if (start > cursor) Spacer(Modifier.height(rowH * (start - cursor)))
                        CourseCell(
                            slot, span,
                            isToday && periodNow != null && periodNow in slot.startPeriod until (slot.startPeriod + span),
                            rowH, courseColors[slot.course] ?: COURSE_COLORS[0],
                        )
                        cursor = start + span
                    }
                    if (cursor <= PERIOD_TIMES.size) Spacer(Modifier.height(rowH * (PERIOD_TIMES.size - cursor + 1)))
                }
            }
        }
    }
}

@Composable
private fun CourseCell(slot: TimetableSlot, span: Int, now: Boolean, rowH: Dp, color: Color) {
    val ink = LocalInk.current
    val stripe = courseStripe(color, ink.dark)
    Box(
        Modifier.fillMaxWidth().height(rowH * span).padding(bottom = 2.dp)
            .clip(GRID_CELL_SHAPE)
            .background(stripe.copy(alpha = if (now) 0.23f else if (ink.dark) 0.14f else 0.085f)),
    ) {
        Row(Modifier.matchParentSize()) {
            Box(Modifier.width(if (now) 2.5.dp else 1.5.dp).fillMaxHeight().background(stripe))
        }
        Column(Modifier.padding(start = 4.5.dp, end = 2.dp, top = 2.dp, bottom = 1.dp)) {
            Text(
                slot.course, fontSize = Type.tiny, lineHeight = Type.tinyLine,
                fontWeight = FontWeight.SemiBold, color = ink.strong,
                maxLines = if (span < 2) 1 else if (span < 3) 2 else 3, overflow = TextOverflow.Ellipsis,
            )
            if (slot.room.isNotBlank()) Text(
                shortRoom(slot.room), fontSize = Type.tinier, lineHeight = Type.tinierLine, color = ink.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun courseStripe(color: Color, dark: Boolean): Color =
    if (dark) androidx.compose.ui.graphics.lerp(color, Color.White, 0.28f) else color

private val COURSE_COLORS = listOf(
    Color(0xFF0F766E),
    Color(0xFFB45309),
    Color(0xFF1D4ED8),
    Color(0xFFBE123C),
    Color(0xFF15803D),
    Color(0xFF6D28D9),
    Color(0xFF0E7490),
    Color(0xFFA16207),
    Color(0xFF4338CA),
    Color(0xFF9D174D),
    Color(0xFF4D7C0F),
    Color(0xFF475569),
)

@Composable
private fun DayList(slots: List<TimetableSlot>, mornings: List<TimetableSlot>, courseColors: Map<String, Color>) {
    val ink = LocalInk.current
    val all = remember(slots, mornings) { mornings.sortedBy { it.course } + slots.sortedBy { it.startPeriod } }
    val timeWidth = 52.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(verticalArrangement = Arrangement.spacedBy(Gap.xs)) {
        all.forEach { slot ->
            val early = slot.startPeriod < 1
            val last = slot.startPeriod + slot.periodCount - 1
            val startTime = if (early) "早" else Periods.startAt(slot.startPeriod).orEmpty()
            val endTime = if (early) null else Periods.endAt(last)
            val stripe = courseStripe(courseColors[slot.course] ?: COURSE_COLORS[0], ink.dark)
            val details = listOf(shortRoom(slot.room), slot.teacher).filter { it.isNotBlank() }.joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().padding(vertical = Gap.xs),
                horizontalArrangement = Arrangement.spacedBy(Gap.xs),
            ) {
                Column(Modifier.width(timeWidth).padding(top = 1.dp)) {
                    Text(startTime, fontSize = Type.meta, lineHeight = Type.metaLine, fontWeight = FontWeight.SemiBold, color = ink.body, style = Type.tabular)
                    if (endTime != null) Text(endTime, fontSize = Type.micro, color = ink.muted, style = Type.tabular)
                }
                Column(
                    Modifier.weight(1f).clip(GRID_CELL_SHAPE)
                        .drawBehind {
                            val x = 1.dp.toPx()
                            drawLine(stripe.copy(alpha = 0.72f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                        }
                        .padding(start = Gap.sm, bottom = Gap.xs),
                    verticalArrangement = Arrangement.spacedBy(Gap.xxs),
                ) {
                    Text(slot.course, fontSize = Type.body, lineHeight = Type.metaLine, fontWeight = FontWeight.SemiBold, color = ink.strong)
                    if (details.isNotEmpty()) Text(details, fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
                    if (!early) {
                        val periods = if (last > slot.startPeriod) "${slot.startPeriod}–$last 节" else "${slot.startPeriod} 节"
                        Text(periods, fontSize = Type.micro, lineHeight = Type.microLine, color = ink.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableEmpty(title: String) {
    val ink = LocalInk.current
    val theme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(vertical = Gap.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gap.md, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.size(68.dp)) {
            drawCircle(theme.surface.copy(alpha = 0.75f), radius = size.minDimension / 2f - 1.dp.toPx())
            drawCircle(ink.faint.copy(alpha = 0.48f), radius = size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.dp.toPx()))
            for (x in listOf(0.39f, 0.61f)) {
                drawCircle(ink.body, radius = 2.7.dp.toPx(), center = Offset(size.width * x, size.height * 0.49f))
            }
        }
        Text(title, fontSize = Type.meta, color = ink.muted)
    }
}

private fun weekOverviewLabel(offset: Int): String = when (offset) {
    0 -> "本周"
    1 -> "下周"
    -1 -> "上周"
    else -> "整周"
}

private fun weekLabel(currentWeek: Int, offset: Int): String = when {
    currentWeek + offset > 0 && currentWeek > 0 -> "第 ${currentWeek + offset} 周"
    offset == 0 -> "本周"
    offset == 1 -> "下周"
    offset == -1 -> "上周"
    else -> "${if (offset > 0) "后" else "前"}${kotlin.math.abs(offset)}周"
}
