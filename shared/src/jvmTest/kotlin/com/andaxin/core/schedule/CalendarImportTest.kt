package com.andaxin.core.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarImportTest {
    private fun event(uid: String, title: String = "高等数学", start: Long = 1_000L) = CalendarEvent(
        uid = uid,
        title = title,
        location = "教一 101",
        notes = "安大信课表 · 第8周 · 第 1 节",
        startMillis = start,
        endMillis = start + 45 * 60_000L,
    )

    @Test fun notesPreserveDescriptionAndLegacyMarker() {
        val notes = calendarEventNotes(event("andaxin-8-1-1"))
        assertTrue(notes.contains("安大信课表"))
        assertEquals("andaxin-8-1-1", calendarUidOf(notes))
        assertEquals("andaxin-8-2-3", calendarUidOf("备注\n#andaxin:andaxin-8-2-3\n其他备注"))
    }

    @Test fun foreignAndEmptyNotesHaveNoIdentity() {
        listOf(null, "", "随便写的备注", "#andaxin:", "#andaxin:   ").forEach { assertNull(calendarUidOf(it)) }
    }

    @Test fun importingSameCourseTwiceAddsNothing() {
        val events = listOf(event("a"), event("b"))
        assertTrue(calendarEventsToAdd(events, events.map { it.importIdentity() }.toSet()).isEmpty())
    }

    @Test fun repeatedWeekNumberInAnotherSemesterIsNotSkipped() {
        val earlier = event("andaxin-8-1-1", start = 1_000L)
        val later = event("andaxin-8-1-1", start = 1_000L + 180L * 86_400_000L)
        assertEquals(listOf(later), calendarEventsToAdd(listOf(later), setOf(earlier.importIdentity())))
    }

    @Test fun duplicateRowsInOneImportAreWrittenOnce() {
        assertEquals(listOf(event("a"), event("b")), calendarEventsToAdd(listOf(event("a"), event("a"), event("b")), emptySet()))
    }

    @Test fun renamedCourseKeepsItsIdentity() {
        val before = event("a")
        val after = event("a", title = "新课程名")
        assertTrue(calendarEventsToAdd(listOf(after), setOf(before.importIdentity())).isEmpty())
    }

    @Test fun failedItemsRemainEligibleOnRetry() {
        val events = listOf(event("a"), event("b"), event("c"))
        val toRetry = calendarEventsToAdd(events, setOf(events[0].importIdentity(), events[2].importIdentity()))
        assertEquals(listOf("b"), toRetry.map { it.uid })
    }

    @Test fun successfulImportReportsActualAddedAndSkippedCounts() {
        assertEquals("这一周没有要导的课", calendarImportMessage(0, 0, 0))
        assertEquals("这 5 节课都已经在日历里了", calendarImportMessage(5, 0, 0))
        assertEquals("已导入 5 节课到你的日历", calendarImportMessage(5, 5, 0))
        assertEquals("已导入 3 节课到你的日历（另有 2 节本来就在日历里）", calendarImportMessage(5, 3, 0))
    }

    @Test fun partialFailureDoesNotCountAttemptedWritesAsSuccess() {
        val message = calendarImportMessage(total = 8, added = 3, failed = 2)
        assertEquals("已导入 3 节课，2 节未写入日历，可重试（另有 3 节本来就在日历里）", message)
        assertFalse(message.contains("已导入 5"))
    }

    @Test fun completeFailureReportsZeroSuccessfulWrites() {
        assertEquals("已导入 0 节课，5 节未写入日历，可重试", calendarImportMessage(5, 0, 5))
    }

    @Test fun invalidCountsCannotProduceMisleadingMessages() {
        assertFailsWith<IllegalArgumentException> { calendarImportMessage(5, 4, 2) }
        assertFailsWith<IllegalArgumentException> { calendarImportMessage(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { calendarImportMessage(1, -1, 0) }
        assertFailsWith<IllegalArgumentException> { calendarImportMessage(1, 0, -1) }
    }
}
