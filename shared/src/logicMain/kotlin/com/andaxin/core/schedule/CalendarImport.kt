package com.andaxin.core.schedule

/*
 * 「导入到日程」的去重口径（平台无关）。
 *
 * 为什么要去重：他一周可能点两次「导入到日程」——第二次不该在日历里留两份一模一样的课。
 * 判据用 uid（TimetableIcs 已经按「第几周 + 星期几 + 第几节」生成，见 CalendarEvent.uid），
 * 它天然是这一节课在这一周里的唯一身份；同一节课换个名字（校方改了课名）也认得出是同一条。
 *
 * 已有的怎么认：平台各自都能读出「这条日历事件带没带我们的 uid」——
 *  · iOS：EKEvent.eventIdentifier 是系统给的新 id，不是我们的；所以把 uid 写进
 *    notes 的第一行（`安大信课表 · …` 原本就在 notes 里，再加一行 `#andaxin:<uid>`），
 *    读回来时按这个前缀捞。不用 URL 字段是因为那个字段用户看得见、还能被别的 App 改。
 *  · 安卓（哪天接上 WRITE_CALENDAR）：CalendarContract 有现成的 SYNC_DATA 列可用。
 * 这里只给"应该写什么标记 / 怎么从文本里认回来"，两端共用一份，免得标记格式各写一遍。
 */

/** 写进日历事件 notes 的标记前缀。 */
const val CALENDAR_UID_MARK = "#andaxin:"

/** 一条事件在日历里该带的 notes：原说明 + 一行我们的身份标记。 */
fun calendarEventNotes(event: CalendarEvent): String =
    event.notes + "\n" + CALENDAR_UID_MARK + event.uid

/** 从一段 notes 里认出我们的 uid；不是我们写的那条就 null。 */
fun calendarUidOf(notes: String?): String? =
    notes?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith(CALENDAR_UID_MARK) }
        ?.removePrefix(CALENDAR_UID_MARK)
        ?.takeIf { it.isNotBlank() }

/**
 * 这一批里**真正要新写**的那些：already 是日历里已经有的 uid 集合。
 *
 * 注意这里不删旧的：多出来的那条（比如上周导的）留着是用户自己的东西，
 * 我们不替他清日历 —— 只保证"同一节课不会因为我点了两次就变成两条"。
 */
fun calendarEventsToAdd(
    events: List<CalendarEvent>,
    alreadyPresent: Set<String>,
): List<CalendarEvent> = events
    .filter { it.uid !in alreadyPresent }
    .distinctBy { it.uid }

/** 给用户看的那句话（成功/重复/失败三种都说清楚，不糊成一句"已完成"）。 */
fun calendarImportMessage(total: Int, toAdd: Int, failed: Int): String {
    if (total == 0) return "这一周没有要导的课"
    if (failed > 0) return "有 $failed 条没写进日历（其余 $toAdd 条已导入）"
    val skipped = total - toAdd
    return when {
        toAdd == 0 -> "这 ${total} 节课都已经在日历里了"
        skipped == 0 -> "已导入 $toAdd 节课到你的日历"
        else -> "已导入 $toAdd 节（另有 $skipped 节本来就在日历里）"
    }
}