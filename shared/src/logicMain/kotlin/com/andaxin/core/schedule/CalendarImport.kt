package com.andaxin.core.schedule

const val CALENDAR_UID_MARK = "#andaxin:"

// 周次每学期重用，身份必须同时包含实际开课时间；保留原 notes 标记以识别旧版本写入的课。
data class CalendarImportIdentity(val uid: String, val startMillis: Long)

fun CalendarEvent.importIdentity(): CalendarImportIdentity = CalendarImportIdentity(uid, startMillis)

fun calendarEventNotes(event: CalendarEvent): String =
    event.notes + "\n" + CALENDAR_UID_MARK + event.uid

fun calendarUidOf(notes: String?): String? =
    notes?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith(CALENDAR_UID_MARK) }
        ?.removePrefix(CALENDAR_UID_MARK)
        ?.takeIf { it.isNotBlank() }

fun calendarEventsToAdd(
    events: List<CalendarEvent>,
    alreadyPresent: Set<CalendarImportIdentity>,
): List<CalendarEvent> = events
    .distinctBy { it.importIdentity() }
    .filter { it.importIdentity() !in alreadyPresent }

fun calendarImportMessage(total: Int, added: Int, failed: Int): String {
    require(total >= 0 && added >= 0 && failed >= 0 && added <= total && failed <= total - added)
    if (total == 0) return "这一周没有要导的课"
    val skipped = total - added - failed
    val summary = when {
        failed > 0 -> "已导入 $added 节课，$failed 节未写入日历，可重试"
        added == 0 -> "这 $total 节课都已经在日历里了"
        else -> "已导入 $added 节课到你的日历"
    }
    return if (skipped > 0 && (added > 0 || failed > 0)) {
        "$summary（另有 $skipped 节本来就在日历里）"
    } else summary
}
