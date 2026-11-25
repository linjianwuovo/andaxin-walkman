package com.andaxin.core.schedule

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 「导入到日程」的去重与提示语口径（两端共用，见 CalendarImport.kt）。
 * 这些判据决定"点两次会不会变两条课"和"提示语会不会含糊"，都能纯算术钉住。
 */
class CalendarImportTest {

    private fun event(uid: String, title: String = "高等数学", start: Long = 1_000L) = CalendarEvent(
        uid = uid,
        title = title,
        location = "教一 101",
        notes = "安大信课表 · 第8周 · 第 1 节",
        startMillis = start,
        endMillis = start + 45 * 60_000L,
    )

    @Test
    fun notes_里带得回身份标记() {
        val e = event("andaxin-8-1-1")
        val notes = calendarEventNotes(e)
        assertTrue(notes.contains("安大信课表"), "原来的说明不能丢：$notes")
        assertEquals("andaxin-8-1-1", calendarUidOf(notes))
    }

    @Test
    fun 不是我们写的日历条目认不出uid() {
        assertNull(calendarUidOf(null))
        assertNull(calendarUidOf(""))
        assertNull(calendarUidOf("随便写的备注"))
        assertNull(calendarUidOf("#andaxin:"))
        // 前缀对但那一行是空的（被别的 App 清过）也不认
        assertNull(calendarUidOf("安大信课表\n#andaxin:   "))
    }

    @Test
    fun 标记行在中间或末尾都认得出() {
        val notes = "第一行随便\n#andaxin:andaxin-8-2-3\n第三行"
        assertEquals("andaxin-8-2-3", calendarUidOf(notes))
    }

    @Test
    fun 已经有的不再写第二遍() {
        val events = listOf(event("a"), event("b"), event("c"))
        val toAdd = calendarEventsToAdd(events, setOf("b"))
        assertEquals(listOf("a", "c"), toAdd.map { it.uid })
    }

    @Test
    fun 同一批里重复的uid只写一条() {
        val events = listOf(event("a"), event("a"), event("b"))
        assertEquals(listOf("a", "b"), calendarEventsToAdd(events, emptySet()).map { it.uid })
    }

    @Test
    fun 全都已有时不写() {
        val events = listOf(event("a"), event("b"))
        assertTrue(calendarEventsToAdd(events, setOf("a", "b")).isEmpty())
    }

    @Test
    fun 提示语三种情况都说清楚() {
        assertEquals("这一周没有要导的课", calendarImportMessage(total = 0, toAdd = 0, failed = 0))
        // 全都已经有了 —— 不能报"已导入 0 节"这种让人以为失败的话
        assertTrue(calendarImportMessage(total = 5, toAdd = 0, failed = 0).contains("都已经在日历里"))
        // 全新导入
        assertEquals("已导入 5 节课到你的日历", calendarImportMessage(total = 5, toAdd = 5, failed = 0))
        // 一部分本来就有
        val partial = calendarImportMessage(total = 5, toAdd = 3, failed = 0)
        assertTrue(partial.contains("3") && partial.contains("2"), "应当同时说出导入数和跳过数：$partial")
        // 有失败要把失败数说出来，不能糊成"已完成"
        val withFailures = calendarImportMessage(total = 5, toAdd = 3, failed = 2)
        assertTrue(withFailures.contains("2") && withFailures.contains("3"), "应说清失败与成功：$withFailures")
    }
}