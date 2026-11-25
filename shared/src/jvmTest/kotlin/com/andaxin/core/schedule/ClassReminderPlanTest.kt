package com.andaxin.core.schedule

import com.andaxin.data.TimetableSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/*
 * 排程口径的钉子（两端共用的那一份，见 ClassReminderPlan.kt 顶上那段）。
 * 课表槽、周一起点全给死值，按 UTC 折毫秒，Windows 本机跑出来的和 iOS/安卓排出来的一致。
 */
class ClassReminderPlanTest {
    private val monday = LocalDate(2026, 11, 23)
    private val zone = TimeZone.UTC
    private fun at(month: Int, day: Int, h: Int, m: Int): Long =
        LocalDateTime(2026, month, day, h, m).toInstant(zone).toEpochMilliseconds()

    private fun slot(course: String, day: Int, period: Int, count: Int = 2, room: String = "教一 101") =
        TimetableSlot("c-$course-$day-$period", course, "老师", room, day, period, count)

    private val weekMonday: (Int) -> LocalDate? = { off ->
        when (off) {
            0 -> monday
            1 -> monday.plus(kotlinx.datetime.DatePeriod(days = 7))
            else -> null
        }
    }

    /** 第 1 节 08:30、周一：起点必须正好是那一周的周一上午。 */
    @Test
    fun 节次起点_照抄的十节表() {
        assertEquals(at(11, 23, 8, 30), slotStartMs(monday, day = 1, period = 1, zone = zone))
        // 第 7 节官方给的是 15:55（不是 45 分钟推出来的）—— 这条钉住"结束时刻是抄的"那件事的另一半。
        assertEquals(at(11, 23, 15, 55), slotStartMs(monday, day = 1, period = 7, zone = zone))
        assertNull(slotStartMs(monday, day = 1, period = 11, zone = zone))
        assertNull(slotStartMs(monday, day = 8, period = 1, zone = zone))
    }

    @Test
    fun 剩余分钟_向上取整且过点报零() {
        assertEquals(2, minutesLeftCeil(1_000_000L + 61_000L, 1_000_000L))
        assertEquals(1, minutesLeftCeil(1_000_000L + 60_000L, 1_000_000L))
        assertEquals(1, minutesLeftCeil(1_000_000L + 1_000L, 1_000_000L))
        assertEquals(0, minutesLeftCeil(1_000_000L, 1_000_000L))
        assertEquals(0, minutesLeftCeil(999_000L, 1_000_000L))
    }

    @Test
    fun 开关没开_整条链都不排() {
        val plan = planClassReminder(on = false, leadRaw = "10", slots = listOf(0 to slot("高数", 1, 1)), nowMs = at(11, 23, 6, 0), weekMonday = weekMonday, zone = zone)
        assertTrue(plan is ReminderPlan.Off)
    }

    @Test
    fun 课表没拉到_说的是那句人话() {
        val plan = planClassReminder(on = true, leadRaw = "10", slots = emptyList(), nowMs = at(11, 23, 6, 0), weekMonday = weekMonday, zone = zone)
        assertTrue(plan is ReminderPlan.NoTimetable)
    }

    @Test
    fun 提前量认表里的档位_坏值回默认() {
        assertEquals(15, classReminderLeadOf("15"))
        assertEquals(CLASS_REMIND_DEFAULT_LEAD, classReminderLeadOf("7"))
        assertEquals(CLASS_REMIND_DEFAULT_LEAD, classReminderLeadOf(null))
        assertEquals(CLASS_REMIND_DEFAULT_LEAD, classReminderLeadOf("abc"))
    }

    @Test
    fun 链从提前量第一档到上课那一刻() {
        val now = at(11, 23, 6, 0)
        val start = at(11, 23, 8, 30)
        val plan = planClassReminder(
            on = true, leadRaw = "5",
            slots = listOf(0 to slot("高数", 1, 1)),
            nowMs = now, weekMonday = weekMonday, zone = zone,
        ) as ReminderPlan.Armed
        assertEquals(listOf(5, 4, 3, 2, 1, 0), plan.chain.map { it.second })
        assertEquals(start - 5 * 60_000L, plan.chain.first().first)
        assertEquals(start, plan.chain.last().first)
        assertEquals(0, plan.chain.last().second)
        assertEquals("高数", plan.lesson.course)
        assertTrue(plan.status.startsWith("已排：高数"))
    }

    @Test
    fun 晨跑占位和越界节次都不排() {
        val now = at(11, 23, 6, 0)
        val plan = planClassReminder(
            on = true, leadRaw = "10",
            slots = listOf(0 to slot("阳光晨跑", 1, 0), 0 to slot("不存在", 1, 11)),
            nowMs = now, weekMonday = weekMonday, zone = zone,
        )
        assertTrue(plan is ReminderPlan.NothingLeft)
    }

    @Test
    fun 正在上的课跳过_排下一节() {
        // 8:35 时第 1 节已经在上课（left=0，补发窗口早过了），9:20 第 2 节才是下一个。
        val now = at(11, 23, 8, 35)
        val plan = planClassReminder(
            on = true, leadRaw = "5",
            slots = listOf(0 to slot("正在上", 1, 1), 0 to slot("下一节", 1, 2)),
            nowMs = now, weekMonday = weekMonday, zone = zone,
        ) as ReminderPlan.Armed
        assertEquals("下一节", plan.lesson.course)
        assertEquals(at(11, 23, 9, 20), plan.lesson.startMs)
    }

    @Test
    fun 窗口里的那一节当场补一发() {
        val start = at(11, 23, 8, 30)
        val now = start - 100_000L // 剩 1 分 40 秒 → 向上取整 2 分钟
        val plan = planClassReminder(
            on = true, leadRaw = "5",
            slots = listOf(0 to slot("快上课", 1, 1)),
            nowMs = now, weekMonday = weekMonday, zone = zone,
        ) as ReminderPlan.Armed
        assertTrue(plan.catchUp)
        // 第一档 = 当场（fireAt=now，剩 2 分钟），之后每分钟一档到"现在上课"。
        assertEquals(listOf(2, 1, 0), plan.chain.map { it.second })
        assertEquals(now, plan.chain.first().first)
        assertEquals(start - 1 * 60_000L, plan.chain[1].first)
        assertEquals(start, plan.chain.last().first)
        assertTrue(plan.status.startsWith("补发：快上课"))
        assertNull(plan.followUp)
    }

    @Test
    fun 下一节跟着排_对齐响完接排下一颗() {
        val now = at(11, 23, 6, 0)
        val plan = planClassReminder(
            on = true, leadRaw = "5",
            slots = listOf(0 to slot("第一节", 1, 1), 0 to slot("第二节", 1, 2), 1 to slot("下周一", 1, 1)),
            nowMs = now, weekMonday = weekMonday, zone = zone,
        ) as ReminderPlan.Armed
        assertEquals("第一节", plan.lesson.course)
        val (follow, followChain) = plan.followUp!!
        assertEquals("第二节", follow.course)
        assertEquals(at(11, 23, 9, 20) - 5 * 60_000L, followChain.first().first)
        assertEquals(listOf(5, 4, 3, 2, 1, 0), followChain.map { it.second })
    }

    @Test
    fun 两周外的周次拿不到_那周整周跳过() {
        val now = at(11, 23, 6, 0)
        val plan = planClassReminder(
            on = true, leadRaw = "10",
            slots = listOf(5 to slot("第五周", 1, 1)),
            nowMs = now, weekMonday = weekMonday, zone = zone,
        )
        assertTrue(plan is ReminderPlan.NothingLeft)
    }
}
