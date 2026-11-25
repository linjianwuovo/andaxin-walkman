package com.andaxin.core.schedule

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusNotDetermined
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKAuthorizationStatusWriteOnly
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKSpan
import platform.Foundation.NSError
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

/*
 * 「导入到日程」的 iOS 落点：EventKit。
 *
 * 为什么是「写进用户自己的日历」而不是一条订阅源：订阅日历（webcal）要一个能被系统反复拉的
 * URL，我们没有（也不该为了导课表开一个服务器）；EventKit 一次授权之后点一下就把这周的课
 * 落进他默认日历，跟安卓那条 `BriefEnv.addToCalendar` 缝的意思一样。
 *
 * 授权分两档：iOS 17 起 write-only 就够（我们只写不读），先要 write-only；
 * 系统只给 full access 的老版本也接住。全被拒时说清楚去系统设置里开，不静默失败。
 *
 * 去重照 logicMain 的口径（见 CalendarImport.kt）：先按时间窗口把已有事件读回来，
 * 用 notes 里的 `#andaxin:<uid>` 认出哪些是我们写过的，只补新出现的那些。
 * **不删任何已有的日历条目** —— 那是用户的东西。
 */
@OptIn(ExperimentalForeignApi::class)
object IosCalendarExport {

    /** 一次导入的结果，界面拿到之后原样显示那句人话。 */
    private var lastMessage: String = ""

    fun lastStatus(): String = lastMessage

    /**
     * 把这批课写进日历。[onResult] 的第一个参数是"有没有真的写进去至少一条或本来就都在"，
     * 第二个是要显示的那句话。
     */
    fun import(events: List<CalendarEvent>, onResult: (Boolean, String) -> Unit) {
        if (events.isEmpty()) {
            val msg = calendarImportMessage(0, 0, 0)
            lastMessage = msg
            onResult(false, msg)
            return
        }
        val store = EKEventStore()
        val status = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
        when (status) {
            EKAuthorizationStatusFullAccess, EKAuthorizationStatusWriteOnly -> write(store, events, onResult)
            EKAuthorizationStatusDenied, EKAuthorizationStatusRestricted -> {
                val msg = "没拿到日历权限：去「设置 → 安大信 → 日历」里给一下，再点一次"
                lastMessage = msg
                onResult(false, msg)
            }
            else -> requestThenWrite(store, events, onResult)
        }
    }

    private fun requestThenWrite(store: EKEventStore, events: List<CalendarEvent>, onResult: (Boolean, String) -> Unit) {
        // write-only 就够用（我们只写不读）；老系统上再退到 full access。
        store.requestWriteOnlyAccessToEventsWithCompletion { granted, error ->
            if (granted && error == null) {
                write(store, events, onResult)
            } else {
                val msg = if (error != null) {
                    "日历授权没成功：${error.localizedDescription}"
                } else {
                    "没给日历权限，这次没导入。想要的话去系统设置里开一下"
                }
                lastMessage = msg
                onResult(false, msg)
            }
        }
    }

    /** 真写：先捞已有的 uid，只补新的，逐条 saveEvent。 */
    private fun write(store: EKEventStore, events: List<CalendarEvent>, onResult: (Boolean, String) -> Unit) {
        val calendar = store.defaultCalendarForNewEvents()
        if (calendar == null) {
            val msg = "这台机器上没有可写的日历（系统日历被删了或还没建）"
            lastMessage = msg
            onResult(false, msg)
            return
        }
        val already = existingUids(store, events)
        val toAdd = calendarEventsToAdd(events, already)
        var failed = 0
        for (event in toAdd) {
            val ek = EKEvent.eventWithEventStore(store)
            ek.setCalendar(calendar)
            ek.setTitle(event.title)
            ek.setLocation(event.location.ifBlank { null })
            ek.setNotes(calendarEventNotes(event))
            ek.setStartDate(NSDate.dateWithTimeIntervalSince1970(event.startMillis / 1000.0))
            ek.setEndDate(NSDate.dateWithTimeIntervalSince1970(event.endMillis / 1000.0))
            ek.setAllDay(false)
            val ok = memScoped {
                val err = alloc<ObjCObjectVar<NSError?>>()
                val saved = store.saveEvent(ek, span = EKSpan.EKSpanThisEvent, error = err.ptr)
                if (!saved) failed += 1
                saved
            }
            if (!ok) continue
        }
        val msg = calendarImportMessage(events.size, toAdd.size, failed)
        lastMessage = msg
        onResult(failed == 0, msg)
    }

    /**
     * 日历里已经有哪些 uid：只查这批课所在的时间跨度（最早一条的前一天 → 最晚一条的后一天），
     * 不全库扫 —— 用户日历可能有十年的东西。
     */
    private fun existingUids(store: EKEventStore, events: List<CalendarEvent>): Set<String> {
        val from = events.minOf { it.startMillis } - 86_400_000L
        val to = events.maxOf { it.endMillis } + 86_400_000L
        val predicate = store.predicateForEventsWithStartDate(
            startDate = NSDate.dateWithTimeIntervalSince1970(from / 1000.0),
            endDate = NSDate.dateWithTimeIntervalSince1970(to / 1000.0),
            calendars = null,
        )
        val found = store.eventsMatchingPredicate(predicate).orEmpty()
        return found.mapNotNull { (it as? EKEvent)?.notes?.let { notes -> calendarUidOf(notes) } }.toSet()
    }
}