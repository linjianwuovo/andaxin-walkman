package com.andaxin.feature.schedule

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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.schedule.Periods
import com.andaxin.core.schedule.TimetableIcs
import com.andaxin.core.schedule.shortRoom
import com.andaxin.data.TimetableSlot
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.RowLine
import com.andaxin.shell.Refreshable
import com.andaxin.shell.Sheet
import com.andaxin.shell.pressClick
import com.andaxin.shell.adxViewModel
import com.andaxin.shell.ErrorLine
import com.andaxin.shell.ShelfTail
import com.andaxin.shell.registerShelfNavigationViewport
import com.andaxin.state.TimetableModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.delay

private val DAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 每节课的起止时刻。表在 `Periods` 里，和首页"下一节"共用一份。 */
private val PERIOD_TIMES = Periods.START_AT

/**
 * 一行多高。58 → 44：11 节课原来 638dp，一屏装不下必须滚，
 * 而中午 12:00–14:00 那几行空档也是 58dp，看着像课表缺了一大块。
 */
private const val ROW_HEIGHT = 44
private const val GUTTER = 28

/** 星期头那一行的高度：左列也要垫同样高，不然时间轴和格子对不齐。 */
private const val HEADER_HEIGHT = 36

private fun rowHeightFor(viewport: Dp, extraRows: Int): Dp {
    return ((viewport - 164.dp) / (PERIOD_TIMES.size + extraRows).toFloat())
        .coerceIn(ROW_HEIGHT.dp, 66.dp)
}

@Composable
fun TimetableScreen(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
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
    val shownWeek = if (state.now > 0) state.now + weekOffset else 0
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
    val dates = remember(monday) {
        val format = SimpleDateFormat("M/d", Locale.US)
        DAYS.indices.map { delta ->
            val date = (monday.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, delta) }
            format.format(date.time)
        }
    }
    var dayOverride by rememberSaveable { mutableIntStateOf(0) }
    var showDayMode by rememberSaveable { mutableStateOf(false) }
    val day = dayOverride.takeIf { it in 1..7 } ?: today
    val pickDay: (Int) -> Unit = {
        dayOverride = it
        showDayMode = true
    }
    val fontScale = LocalDensity.current.fontScale

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
                Spacer(Modifier.height(Gap.md))
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
                if (showDayMode || slots.isEmpty()) {
                    WeekDateStrip(dates, todayInWeek, day.takeIf { showDayMode }, slots, pickDay)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Gap.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (showDayMode) "${DAYS[day - 1]} · ${dates[day - 1]}"
                        else "${dates.first()} – ${dates.last()}",
                        fontSize = Type.meta,
                        color = ink.muted,
                        modifier = Modifier.weight(1f),
                    )
                    Chip("单日", showDayMode, Modifier.semantics { selected = showDayMode }) { showDayMode = true }
                    Chip("整周", !showDayMode, Modifier.semantics { selected = !showDayMode }) { showDayMode = false }
                }

                /*
                 * 导出这一周的课表（他 10-28 那句「能不能给个导出课程表的功能」）。
                 * 只导这一周：校方那条接口是按周给表的，每条课里没有"1-16周"这种范围，编一个就是假数据，
                 * 所以文件名带周次，想整学期就每周各导一份（这句也写在提示里，不装全）。
                 * 落 cache/export/ 再交给系统分享面板 —— 不申请任何存储权限，存哪儿、发给谁他自己定。
                 */
                if (!loading && slots.any { it.startPeriod >= 1 }) {
                    val ctx = LocalContext.current
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = "导出这份课表（.ics）",
                            fontSize = Type.meta,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .pressClick(shrink = false) {
                                    runCatching {
                                        val dir = java.io.File(ctx.cacheDir, "export").apply { mkdirs() }
                                        val f = java.io.File(dir, TimetableIcs.fileName(shownWeek, weekOffset))
                                        f.writeText(TimetableIcs.build(slots, weekOffset, shownWeek))
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            ctx, ctx.packageName + ".fileprovider", f,
                                        )
                                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/calendar"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, f.name)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        ctx.startActivity(
                                            android.content.Intent.createChooser(send, "导出课表")
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                }
                                .padding(vertical = Gap.sm),
                        )
                    }
                }

                Spacer(Modifier.height(Gap.md))

                val lessons = slots.filter { it.startPeriod >= 1 }
                val mornings = slots.filter { it.startPeriod < 1 }
                val morningRows = mornings.groupingBy { it.day }.eachCount().values.maxOrNull() ?: 0
                when {
                    loading && slots.isEmpty() -> Sheet { com.andaxin.shell.EmptyState("正在加载课表…") }
                    error != null && slots.isEmpty() -> Unit
                    showDayMode -> {
                        val dayLessons = lessons.filter { it.day == day }
                        val dayMornings = mornings.filter { it.day == day }
                        if (dayLessons.isEmpty() && dayMornings.isEmpty()) {
                            Sheet { com.andaxin.shell.EmptyState("${DAYS[day - 1]}没有课") }
                        } else {
                            DayList(dayLessons, dayMornings)
                        }
                    }
                    slots.isEmpty() -> Sheet { com.andaxin.shell.EmptyState("这一周没有课") }
                    else -> {
                        if (lessons.isEmpty()) {
                            Text(
                                "这一周没有普通课程，仅有早间活动",
                                fontSize = Type.meta,
                                color = ink.muted,
                                modifier = Modifier.padding(bottom = Gap.sm, start = Gap.xxs),
                            )
                        }
                        Grid(
                            lessons, mornings, todayInWeek, periodNow,
                            rowHeightFor(viewport, morningRows)
                                .coerceAtLeast(ROW_HEIGHT.dp * fontScale),
                            dates, state.courseColors, pickDay,
                        )
                    }
                }
                Spacer(Modifier.height(Gap.lg))
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
                fontSize = Type.title,
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
        color = if (enabled) MaterialTheme.colorScheme.primary else LocalInk.current.faint,
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
private fun WeekDateStrip(
    dates: List<String>,
    today: Int?,
    day: Int?,
    slots: List<TimetableSlot>,
    onPick: (Int) -> Unit,
) {
    val counts = remember(slots) { slots.groupingBy { it.day }.eachCount() }
    val fontScale = LocalDensity.current.fontScale
    val ink = LocalInk.current
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxOf(maxWidth, 48.dp * DAYS.size * fontScale.coerceAtLeast(1f))
        Row(Modifier.horizontalScroll(rememberScrollState()).width(width)) {
            DAYS.forEachIndexed { i, label ->
                val picked = day == i + 1
                val current = today == i + 1
                val fg = if (picked || current) accent else ink.muted
                Column(
                    Modifier.weight(1f).padding(horizontal = 1.dp)
                        .heightIn(min = 58.dp)
                        .clip(Radius.field)
                        .then(if (picked) Modifier.background(accent.copy(alpha = 0.12f)) else Modifier)
                        .semantics { selected = picked; contentDescription = "$label ${dates[i]}" }
                        .pressClick { onPick(i + 1) }
                        .padding(vertical = Gap.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(label, color = fg, fontSize = Type.tinier, maxLines = 1)
                    Text(dates[i], color = fg, fontSize = Type.micro, maxLines = 1)
                    Box(Modifier.height(5.dp), contentAlignment = Alignment.Center) {
                        if (counts.getOrDefault(i + 1, 0) > 0) {
                            Box(Modifier.size(4.dp).background(fg, Radius.tag))
                        }
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
    dates: List<String>,
    courseColors: Map<String, Int>,
    onPickDay: (Int) -> Unit,
) {
    val ink = LocalInk.current
    val accent = MaterialTheme.colorScheme.primary
    val colors = remember(courseColors) {
        courseColors.mapValues { (_, i) -> COURSE_COLORS[i.mod(COURSE_COLORS.size)] }
    }

    val byDay = remember(slots) {
        slots.groupBy { it.day }.mapValues { (_, v) -> v.sortedBy { it.startPeriod } }
    }
    // 早上那一行的条目：同一天可能两条（晨跑 + 早自习），按名字排，别按接口回来的顺序
    val morningByDay = remember(mornings) {
        mornings.groupBy { it.day }.mapValues { (_, v) -> v.sortedBy { it.course } }
    }
    val morningRows = morningByDay.values.maxOfOrNull { it.size } ?: 0
    val days = (1..5).toList() + (6..7).filter { it in byDay || it in morningByDay || it == today }

    val fontScale = LocalDensity.current.fontScale
    val headerHeight = maxOf(48.dp, HEADER_HEIGHT.dp * fontScale)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gridWidth = if (fontScale > 1.3f) maxOf(maxWidth, (GUTTER.dp + Gap.xxs + 72.dp * days.size) * fontScale) else maxWidth
        Row(Modifier.horizontalScroll(rememberScrollState()).width(gridWidth)) {
            // 左列：先垫一个和星期头等高的占位，节次才对得齐 —— 这一处就是原来错位的根
            Column(Modifier.width(GUTTER.dp * fontScale)) {
                Spacer(Modifier.height(headerHeight))
                // 「早」这一格：只有两个字的位置，没有钟点 —— 校方那几条 `ksjc=0` 本来就没给时间
                if (morningRows > 0) {
                    Box(
                        Modifier.height(rowH * morningRows).fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Text(
                            "早",
                            fontSize = Type.micro,
                            color = ink.muted,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                PERIOD_TIMES.forEachIndexed { i, start ->
                    /*
                     * 左边这格：**节次 + 开始 + 结束三行，全部贴这一格的上沿**。
                     *
                     * 他 08:50 那句"时间和色块没对齐、乍一看容易看错位"：原来三行是居中的，
                     * 64.5dp 一格下 08:30 落在课块的腰部 —— 眼睛对不上"这一块从几点开始"。
                     * 上沿对齐以后，课块的顶边和"1 / 08:30"是同一条线，跟官方那版一样。
                     * 也别把结束时刻挪到下沿：那样它会和下一行的节次、开始挤成一小坨，
                     * 反而更像错位（这一版中间试过，截图里 09:15 / 2 / 09:20 三行挨在一起）。
                     */
                    Box(Modifier.height(rowH).fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxHeight().padding(top = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            val on = periodNow == i + 1
                            Text("${i + 1}", fontSize = Type.micro, color = if (on) accent else ink.faint)
                            Text(start, fontSize = Type.tinier, color = ink.ghost)
                            Periods.endAt(i + 1)?.let { end ->
                                Text(end, fontSize = Type.tinier, color = ink.ghost)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(Gap.xxs))

            days.forEach { day ->
                val isToday = day == today
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 1.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().height(headerHeight).clip(Radius.tag)
                            .semantics { contentDescription = "${DAYS[day - 1]} ${dates[day - 1]}" }
                            .clickable(onClickLabel = "看${DAYS[day - 1]}日程") { onPickDay(day) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = DAYS.getOrElse(day - 1) { "周" },
                            fontSize = Type.micro,
                            fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                            color = if (isToday) accent else ink.muted,
                        )
                        // 日期是这一周的，不是今天的 —— 翻到下周时它会跟着走
                        Text(
                            text = dates[day - 1],
                            fontSize = Type.tinier,
                            color = if (isToday) accent.copy(alpha = 0.8f) else ink.faint,
                        )
                    }
                    // 今天这一列：只在课表格子那一段铺一层淡底，圆角收住，别连星期头一起糊。
                    // 底色走强调色而不是灰底 —— 夜里 `hairline` 是 8% 白，铺在 12 行高的整列上
                    // 就是一块灰板，比课格子本身还抢眼（10-21 凌晨深色扫屏实测）；
                    // 强调色压到百分之几只是"这一列被点了一下"。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (isToday) Modifier.background(
                                    accent.copy(alpha = if (ink.dark) 0.07f else 0.05f),
                                    Radius.tag,
                                ) else Modifier
                            ),
                    ) {
                        // 「早」那一行：一条一格，名字用校方给的 `kcmc` 原文（阳光晨跑 / 早自习…）。
                        // 这一天一条都没有也要把高度垫满，否则各列的第 1 节就错开了。
                        val am = morningByDay[day].orEmpty()
                        am.forEach { slot ->
                            CourseCell(
                                slot, 1, false, rowH,
                                colors[slot.course] ?: COURSE_COLORS[0],
                            )
                        }
                        if (morningRows > 0 && am.size < morningRows) {
                            Spacer(Modifier.height(rowH * (morningRows - am.size)))
                        }
                        val list = byDay[day].orEmpty()
                        var cursor = 1
                        list.forEachIndexed { i, slot ->
                            if (slot.startPeriod > cursor) {
                                Spacer(Modifier.height(rowH * (slot.startPeriod - cursor)))
                            }
                            // 连排几节就占几行；万一校方排出两门课同一节起，前一门裁到一行，
                            // 后一门照排 —— 宁可挤，也不许把课藏掉（藏掉看着像没排课）。
                            val nextStart = list.getOrNull(i + 1)?.startPeriod ?: Int.MAX_VALUE
                            val span = minOf(slot.periodCount, nextStart - slot.startPeriod).coerceAtLeast(1)
                            CourseCell(
                                slot, span,
                                isToday && periodNow != null &&
                                    periodNow in slot.startPeriod until (slot.startPeriod + span),
                                rowH,
                                colors[slot.course] ?: COURSE_COLORS[0],
                            )
                            cursor = slot.startPeriod + span
                        }
                        // 尾巴补满，几列才一样高
                        if (cursor <= PERIOD_TIMES.size) {
                            Spacer(Modifier.height(rowH * (PERIOD_TIMES.size - cursor + 1)))
                        }
                    }
                }
            }
    }
}
}

/** 一门课一格。[span] 是连排几节，就占几行高。 */
@Composable
private fun CourseCell(slot: TimetableSlot, span: Int, now: Boolean, rowH: Dp, solid: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(rowH * span - 3.dp)
            .padding(horizontal = 1.5.dp, vertical = 1.5.dp)
            .background(solid, Radius.tag)
            // 正在上的那一节：实心块上用一圈白环点出来（蓝环压在蓝块上看不见）
            .border(
                width = if (now) 2.dp else 0.dp,
                color = if (now) Color.White.copy(alpha = 0.92f) else Color.Transparent,
                Radius.tag,
            )
            .padding(start = 4.dp, end = 3.dp, top = 3.dp, bottom = 2.dp),
    ) {
        Text(
            text = slot.course,
            fontSize = Type.tiny,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            // 三节往上的块高出一大截，课名多给一行（"大学生心理健康教育" 不再被掐断）
            maxLines = if (span >= 3) 4 else if (span >= 2) 3 else 2,
            lineHeight = Type.tinyLine,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = shortRoom(slot.room),
            fontSize = Type.tinier,
            color = Color.White.copy(alpha = 0.86f),
            // 教室只给一行，而一列才五十几 dp ——「博雅A1区 102」这种就被掐成「博雅A1 …」，
            // 块的下半截却空着一大片。让它按块高换行：两节两行、三节往上三行。
            // 单节那一格反过来卡在一行：块高就那么多，再把字号档调大一点就要溢到块外面去了。
            // （行宽不够是列宽定的，六列除屏幕宽，改不了；能改的是别浪费块里已有的高度。）
            maxLines = if (span >= 3) 3 else if (span >= 2) 2 else 1,
            lineHeight = Type.tinierLine,
            overflow = TextOverflow.Ellipsis,
        )
        // 不再写"第 1-2 节"：块占几行本身已经说了这件事，而格子很窄，
        // 多这一行就是把课名挤成"高等数…"的那只手（10-20 装机他嫌难看的就是这个）。
    }
}

/**
 * 课表色块用的十二颗实心色，白字对比度都在 4.9:1 以上（10-21 早上逐颗量过）。
 *
 * 为什么从五颗加到十二颗：他 10-21 09:5x 一句「每个科目不要重复一个颜色」——
 * 五颗按课名排序轮流发，一周七门课必然有两门撞色，而撞在一起的常常还是相邻两天。
 * 现在一门一颗，十二颗够一张课表用；真选了十几门课才会绕回来，那时候也只能绕。
 *
 * 顺序是故意冷暖交替的：按课名排序一路发下去，相邻两门不会一个青接一个蓝，
 * 远看像同一门课的两个时间。
 */
private val COURSE_COLORS = listOf(
    Color(0xFF0F766E), // 墨绿
    Color(0xFFB45309), // 琥珀
    Color(0xFF1D4ED8), // 蓝
    Color(0xFFBE123C), // 玫红
    Color(0xFF15803D), // 绿
    Color(0xFF6D28D9), // 紫
    Color(0xFF0E7490), // 青
    Color(0xFFA16207), // 赭
    Color(0xFF4338CA), // 靛
    Color(0xFF9D174D), // 洋红
    Color(0xFF4D7C0F), // 橄榄
    Color(0xFF475569), // 石板
)

/*
 * 「课名 → 第几颗色」这张表不在这一屏算 —— 它在 `state.TimetableModel` 里，本机存着。
 *
 * 以前这一屏自己算：把**这一周**的课名排个序，一颗一颗往下发。于是同一门课换一周就可能
 * 换个号（那一周多了两门课、排序一变，它就从第 0 颗挪到第 2 颗），他 10-24 晚撞见的就是这个：
 * 第八周绿的课，第九周变紫。现在改成"一门课第一次见到时占一个最小没用过的号，往后哪一周都认它"。
 * 十二颗以内一门一色；真超过十二门，多的按 `mod` 回头重用 —— 那是色板到头，不是算错。
 */

@Composable
private fun DayList(slots: List<TimetableSlot>, mornings: List<TimetableSlot>) {
    val ink = LocalInk.current
    val sorted = slots.sortedBy { it.startPeriod }
    Text(
        text = "${sorted.size} 门课",
        fontSize = Type.meta,
        color = ink.muted,
        modifier = Modifier.padding(bottom = Gap.xs, start = Gap.xxs),
    )
    // 今天有晨跑 / 早自习这种没有节次的条目时，在这一档里也明说一句：它不是"漏了一节"。
    // 一条一句，名字用校方给的那条 —— 以前写死"另有晨跑活动"，早自习进来就被念成晨跑了。
    mornings.forEach { slot ->
        Text(
            text = listOf("早", slot.course, slot.room).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = Type.meta,
            lineHeight = Type.metaLine,
            color = ink.muted,
            modifier = Modifier.padding(bottom = Gap.sm, start = Gap.xxs),
        )
    }
    if (sorted.isEmpty()) return
    Sheet {
        sorted.forEachIndexed { i, slot ->
            RowLine(
                title = slot.course,
                sub = listOf(
                    // 这一屏原来只有"第 1-2 节"，没有钟点；时间放最前面，扫一眼就是几点到几点。
                    // 右边那句已经写了"第 1-2 节"，所以这里不再重复"N 节连排"。
                    Periods.rangeText(slot.startPeriod, slot.periodCount).orEmpty(),
                    shortRoom(slot.room),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                right = if (slot.periodCount > 1)
                    "第 ${slot.startPeriod}-${slot.startPeriod + slot.periodCount - 1} 节"
                else "第 ${slot.startPeriod} 节",
            )
            if (i != sorted.lastIndex) {
                Spacer(Modifier.height(Gap.xs))
                Hairline()
                Spacer(Modifier.height(Gap.xs))
            }
        }
    }
}

private fun weekLabel(currentWeek: Int, offset: Int): String = when {
    currentWeek + offset > 0 && currentWeek > 0 -> "第 ${currentWeek + offset} 周"
    offset == 0 -> "本周"
    offset == 1 -> "下周"
    offset == -1 -> "上周"
    else -> "${if (offset > 0) "后" else "前"}${kotlin.math.abs(offset)}周"
}
