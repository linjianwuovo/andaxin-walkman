package com.andaxin.feature.notify

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.andaxin.core.concurrency.nowMillis
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.core.schedule.CLASS_REMIND_KEY_LEAD
import com.andaxin.core.schedule.CLASS_REMIND_KEY_ON
import com.andaxin.core.schedule.CLASS_REMIND_LEADS
import com.andaxin.core.schedule.LessonPeriods
import com.andaxin.core.schedule.classReminderLeadOf
import com.andaxin.core.schedule.planClassReminder
import com.andaxin.data.TimetableSlot
import com.andaxin.feature.brief.BriefEnv
import com.andaxin.feature.brief.ReminderHost
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.RowLine
import com.andaxin.shell.Sheet
import kotlinx.coroutines.launch

/*
 * 通知设置（共享层那一屏）。
 *
 * 排程口径不在这一屏：那是 logicMain 的 planClassReminder，一份纯算术两端共用。
 * 这一屏只做三件事：读写那两条首选项（remind.on / remind.leadMinutes）、拉两周课表、
 * 把算好的链递给宿主那台落点（BriefEnv.reminder）。落点是平台的：
 * iOS = 把整条离散链提前交给 UNUserNotificationCenter 的日历触发器；
 * 安卓 = AlarmManager 每分钟一颗接一颗（app 模块那份屏今天仍直读 ClassReminder）。
 *
 * 落点没插、或那端回 available=false，这一行不画开关只写一句人话 ——
 * 摆一颗按不动的哑按钮比少一格糟，跟 BriefEnv.canOpen 同一个规矩。
 *
 * 文案对齐 app 侧那四句里两端都说得出口的部分：节次表照校方作息表抄、提前量从哪一分钟起、
 * 全程不出声。"状态栏那颗闹钟图标""重启后要开一次 App"那两句是安卓物，不搬。
 */
@Composable
fun NotifyScreen(onBack: () -> Unit) {
    val ink = LocalInk.current
    val host = BriefEnv.reminder
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    var lead by remember { mutableStateOf(CLASS_REMIND_LEADS.first()) }
    var status by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }
    val usable = host != null && host.available

    LaunchedEffect(Unit) {
        if (host != null && host.available) {
            host.refreshAuthorization { auth = host.authorizationSummary() }
            val (s, onNow, leadNow) = rerun(host)
            status = s; on = onNow; lead = leadNow
        }
    }

    /** 拨开关 / 换提前量：先落盘再重排（fromUser 语义，对齐 app 侧 write）。 */
    fun write(nv: Boolean, nLead: Int) {
        on = nv; lead = nLead
        scope.launch {
            BriefEnv.graph.store.setMany(
                CLASS_REMIND_KEY_ON to if (nv) "1" else "0",
                CLASS_REMIND_KEY_LEAD to nLead.toString(),
            )
            if (host != null) status = rerun(host).first
        }
        if (nv && host != null) host.requestAuthorization { auth = host.authorizationSummary() }
    }

    ScaffoldPage(title = "通知设置", onBack = onBack) {
        Sheet {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Gap.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("上课提醒", fontSize = Type.title, fontWeight = FontWeight.Medium, color = ink.strong)
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = when {
                            !usable -> "这台上的提醒落点还没接上，不会自动弹上课提醒。"
                            on -> "开 · 课前 $lead 分钟起每分钟刷一次倒计时（不响）"
                            else -> "没开"
                        },
                        fontSize = Type.meta,
                        color = ink.muted,
                        modifier = Modifier.testTag("notify-reminder-subtitle"),
                    )
                }
                if (usable) {
                    Switch(
                        checked = on,
                        onCheckedChange = { write(it, lead) },
                        modifier = Modifier.testTag("notify-reminder-switch"),
                    )
                }
            }
            if (usable && on) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Gap.sm),
                ) {
                    CLASS_REMIND_LEADS.forEach { n ->
                        Chip("$n 分", n == lead, modifier = Modifier.testTag("notify-reminder-lead-$n")) { write(on, n) }
                    }
                }
            }
            if (usable) {
                // 落点自己那句排程结果原样上屏（"已排：…"/"这两周的课都排完了…"/"没开。"）。
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(Gap.xs))
                    Text(status, fontSize = Type.micro, color = ink.faint, modifier = Modifier.testTag("notify-reminder-status"))
                }
                Spacer(Modifier.height(Gap.xs))
                Text(
                    text = "节次时间照的是校方那张作息表（10 节，一行行抄进共享层的 LessonPeriods）；" +
                        "校方没有一条接口给过「第几节几点」，这张表得手抄着跟。",
                    fontSize = Type.meta,
                    lineHeight = Type.metaLine,
                    color = ink.muted,
                )
                if (auth.isNotBlank()) {
                    Spacer(Modifier.height(Gap.xxs))
                    Text(auth, fontSize = Type.micro, color = ink.faint, modifier = Modifier.testTag("notify-reminder-auth"))
                }
            }
        }
        Spacer(Modifier.height(Gap.lg))
        Sheet {
            Chip(
                text = if (BriefEnv.openSystemSettings == null) "不可用" else "开",
                picked = BriefEnv.openSystemSettings != null,
                onClick = { BriefEnv.openSystemSettings?.invoke() },
            )
            Spacer(Modifier.height(Gap.xs))
            Hairline()
            Spacer(Modifier.height(Gap.xs))
            RowLine(
                title = "打开系统通知设置",
                sub = if (BriefEnv.openSystemSettings == null) "当前不可用" else "跳转到系统设置",
                right = "›",
                onClick = { BriefEnv.openSystemSettings?.invoke() },
            )
        }
    }
}

/**
 * 读两条首选项 + 拉两周课表 → planClassReminder → 交给落点。
 * 回（排程那句人话, 开关, 提前量）：屏在进场时用后两格把自己的状态对齐存储，
 * write() 那一路调用方已经自己置好了，只取第一格。
 */
private suspend fun rerun(host: ReminderHost): Triple<String, Boolean, Int> {
    val graph = BriefEnv.graph
    val prefs = graph.store.snapshot()
    val onNow = graph.store.peekString(prefs, CLASS_REMIND_KEY_ON) == "1"
    val leadNow = classReminderLeadOf(graph.store.peekString(prefs, CLASS_REMIND_KEY_LEAD))
    if (!onNow) return Triple(host.apply(planClassReminder(on = false, leadRaw = null, slots = emptyList(), nowMs = nowMillis(), weekMonday = { null })), false, leadNow)
    val slots = ArrayList<Pair<Int, TimetableSlot>>()
    val weeks = LinkedHashMap<Int, kotlinx.datetime.LocalDate>()
    for (off in 0..1) {
        val week = graph.timetable.week(off).getOrNull() ?: continue
        weeks[off] = LessonPeriods.weekMonday(off)
        week.forEach { slots += off to it }
    }
    val status = host.apply(
        planClassReminder(
            on = true,
            leadRaw = leadNow.toString(),
            slots = slots,
            nowMs = nowMillis(),
            weekMonday = { weeks[it] },
        ),
    )
    return Triple(status, onNow, leadNow)
}
