package com.andaxin.core.schedule

import com.andaxin.data.TimetableSlot
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/*
 * 上课提醒的排程口径，平台无关的一份。
 *
 * 安卓那边落点是 AlarmManager + 每分钟一颗接一颗的滴答链（app 侧 core/schedule/ClassReminder.kt），
 * iOS 这边落点是 UNCalendarNotificationTrigger 一次把整条离散链交给系统 —— 两边必须按同一张
 * 十节课作息表、同一个提前量口径来排（口径写两遍就会漂，所以只写这一遍）。
 *
 * 语义逐条对齐 app 侧 ClassReminder.reschedule()：
 * · 先看「已经有人在窗口里」的那一节（catchUp，当场补一发再每分钟一档）；
 * · 否则连排两周取最近的一节（只排本周的话，周五最后一节之后整条链断到下周一）；
 * · `startPeriod < 1` 是"阳光晨跑"活动占位，不是课，不排；
 * · 提前 lead 分钟起算；触发点已过又不在窗口内（含正在上的）跳过；
 * · 窗口内每分钟一档"还有 N 分钟"，N 从墙钟现减、向上取整（61 秒报 2 不报 1）；
 * · 最后一档 0 = "现在上课"。
 *
 * 时区口径：LocalDateTime → 本机时区的瞬时，跟安卓 Calendar.set(HOUR_OF_DAY) 同一意思。
 */

const val CLASS_REMIND_KEY_ON = "remind.on"
const val CLASS_REMIND_KEY_LEAD = "remind.leadMinutes"
val CLASS_REMIND_LEADS = listOf(5, 10, 15, 20, 30)
const val CLASS_REMIND_DEFAULT_LEAD = 10

/** 一节被武装起来的课（纯数据：平台侧拿它去挂闹钟 / 挂系统通知）。 */
data class ArmedLesson(
    val course: String,
    val room: String,
    val period: Int,
    val startMs: Long,
    val weekOffset: Int,
    val day: Int,
)

sealed class ReminderPlan {
    /** 开关没开：平台上要把待发链全部撤干净。 */
    data object Off : ReminderPlan()

    /** 课表没拉到（未登录 / 网络断）：界面那句人话原样显示。 */
    data class NoTimetable(val why: String) : ReminderPlan()

    /** 往后两周没有该提醒的课了。 */
    data class NothingLeft(val why: String) : ReminderPlan()

    /**
     * 排成了：chain 是"从提前量第一档（窗口里则是当场）到上课那一刻"的离散时刻表，
     * 每一项 (fireAtMs, 剩余分钟数)，最后一项 0 = "现在上课"。
     * followUp 是链尾之后下一节的整条链（对齐 app 侧"响完接排下一颗"：安卓响完由接收者
     * 重排，iOS 没有运行中的接收者，就把下一节也一并交给系统）。
     */
    data class Armed(
        val lesson: ArmedLesson,
        val lead: Int,
        val chain: List<Pair<Long, Int>>,
        val catchUp: Boolean,
        val status: String,
        val followUp: Pair<ArmedLesson, List<Pair<Long, Int>>>?,
    ) : ReminderPlan()
}

fun classReminderLeadOf(raw: String?): Int =
    raw?.toIntOrNull()?.takeIf { it in CLASS_REMIND_LEADS } ?: CLASS_REMIND_DEFAULT_LEAD

/** 某一周（monday 给的那周周一）第 day 天第 period 节的**开始毫秒**。 */
fun slotStartMs(monday: LocalDate, day: Int, period: Int, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val startAt = LessonPeriods.startAt(period) ?: return null
    if (day !in 1..7) return null
    val date = monday.plus(DatePeriod(days = day - 1))
    val h = startAt.substringBefore(":").toInt()
    val m = startAt.substringAfter(":").toInt()
    return LocalDateTime(date.year, date.monthNumber, date.dayOfMonth, h, m).toInstant(zone).toEpochMilliseconds()
}

/** 还剩几分钟，向上取整（对齐 app 侧 minutesLeft）；已过点报 0。 */
fun minutesLeftCeil(startMs: Long, nowMs: Long): Int {
    val left = startMs - nowMs
    if (left <= 0L) return 0
    return ((left + 59_999L) / 60_000L).toInt().coerceAtMost(9_999)
}

private fun armedOf(off: Int, slot: TimetableSlot, startMs: Long) = ArmedLesson(
    course = slot.course.ifBlank { "一节课" },
    room = slot.room,
    period = slot.startPeriod,
    startMs = startMs,
    weekOffset = off,
    day = slot.day,
)

/** lead 档整条链：提前量第一档到"现在上课"。 */
private fun chainOf(startMs: Long, lead: Int): List<Pair<Long, Int>> =
    (lead downTo 1).map { left -> (startMs - left * 60_000L) to left } + listOf(startMs to 0)

/**
 * 把两周课表排成一条提醒链（catchUp 优先，对齐 app 侧 reschedule 的顺序）。
 * slots 收 (weekOffset, slot) 两片；weekMonday 给那一周的周一（拿不到 = 那周跳过）。
 */
fun planClassReminder(
    on: Boolean,
    leadRaw: String?,
    slots: List<Pair<Int, TimetableSlot>>,
    nowMs: Long,
    weekMonday: (Int) -> LocalDate?,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): ReminderPlan {
    if (!on) return ReminderPlan.Off
    if (slots.isEmpty()) return ReminderPlan.NoTimetable("课表没拉到，暂时排不上提醒（登录掉了先去登一次）。")
    val lead = classReminderLeadOf(leadRaw)
    val starts = slots.mapNotNull { (off, slot) ->
        if (slot.startPeriod < 1) return@mapNotNull null
        val monday = weekMonday(off) ?: return@mapNotNull null
        val startMs = slotStartMs(monday, slot.day, slot.startPeriod, zone) ?: return@mapNotNull null
        Triple(off, slot, startMs)
    }.sortedBy { it.third }
    // ① 窗口里的那一节（对齐 app 的 catchUp）：0 < 剩余 <= lead，当场就该发。
    val hit = starts.firstNotNullOfOrNull { (off, slot, startMs) ->
        val left = minutesLeftCeil(startMs, nowMs)
        if (left in 1..lead) Triple(armedOf(off, slot, startMs), left, startMs) else null
    }
    if (hit != null) {
        val (lesson, leftNow, startMs) = hit
        val chain = (leftNow downTo 1).map { left ->
            // 显示"还有 N 分钟"的那档在 startMs - N 分钟整点火（剩余恰好 N 分钟）；
            // 第一档的边界已经过去（现在正处在它里面），所以当场发。
            val fireAt = if (left == leftNow) nowMs else startMs - left * 60_000L
            fireAt to left
        } + listOf(startMs to 0)
        return ReminderPlan.Armed(
            lesson = lesson,
            lead = lead,
            chain = chain,
            catchUp = true,
            status = "补发：" + lesson.course + " · 第 " + lesson.period + " 节 · 已进入提醒窗口，当场补一发再每分钟一档",
            followUp = null,
        )
    }
    // ② 最近的一节（触发点在未来）。
    val next = starts.firstOrNull { it.third - lead * 60_000L > nowMs }
        ?: return ReminderPlan.NothingLeft("这两周的课都排完了，不提醒了。")
    val lesson = armedOf(next.first, next.second, next.third)
    val follow = starts.firstOrNull { it.third - lead * 60_000L > next.third }
    return ReminderPlan.Armed(
        lesson = lesson,
        lead = lead,
        chain = chainOf(next.third, lead),
        catchUp = false,
        status = "已排：" + lesson.course + " · 第 " + lesson.period + " 节 · 提前 " + lead + " 分钟起，每分钟一档",
        followUp = follow?.let { armedOf(it.first, it.second, it.third) to chainOf(it.third, lead) },
    )
}
