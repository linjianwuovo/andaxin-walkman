package com.andaxin.core.schedule

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusRestricted
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKSpan
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
object IosCalendarExport {
    private var importing = false

    fun import(events: List<CalendarEvent>, onResult: (Boolean, String) -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            if (importing) {
                onResult(false, "正在导入日历，请稍候")
                return@dispatch_async
            }
            importing = true
            val uniqueEvents = events.distinctBy { it.importIdentity() }
            fun report(ok: Boolean, message: String) {
                importing = false
                onResult(ok, message)
            }
            if (uniqueEvents.isEmpty()) {
                report(false, calendarImportMessage(0, 0, 0))
                return@dispatch_async
            }
            val store = EKEventStore()
            when (EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)) {
                EKAuthorizationStatusFullAccess -> write(store, uniqueEvents, ::report)
                EKAuthorizationStatusDenied, EKAuthorizationStatusRestricted -> report(
                    false, "需要日历完整访问权限才能检查重复课程：请在「设置 → 隐私与安全性 → 日历」里为安大信开启",
                )
                else -> {
                    val completion: (Boolean, NSError?) -> Unit = { granted, error ->
                        dispatch_async(dispatch_get_main_queue()) {
                            if (granted && EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent) == EKAuthorizationStatusFullAccess) {
                                write(store, uniqueEvents, ::report)
                            } else {
                                report(false, "未获得日历完整访问权限，本次没有导入" + (error?.localizedDescription?.let { "：$it" } ?: ""))
                            }
                        }
                    }
                    // 去重需要读取事件，write-only 不足；iOS 16 使用旧授权接口。
                    val major = UIDevice.currentDevice.systemVersion.substringBefore('.').toIntOrNull() ?: 16
                    if (major >= 17) store.requestFullAccessToEventsWithCompletion(completion)
                    else store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent, completion)
                }
            }
        }
    }

    private fun write(store: EKEventStore, events: List<CalendarEvent>, onResult: (Boolean, String) -> Unit) {
        val calendar = store.defaultCalendarForNewEvents()
        if (calendar == null) {
            onResult(false, "没有可写的默认日历，请先在系统「日历」中创建日历")
            return
        }
        val toAdd = calendarEventsToAdd(events, existingIdentities(store, events))
        var added = 0
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
            val saved = memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                store.saveEvent(ek, span = EKSpan.EKSpanThisEvent, error = error.ptr)
            }
            if (saved) added++ else failed++
        }
        onResult(failed == 0, calendarImportMessage(events.size, added, failed))
    }

    private fun existingIdentities(store: EKEventStore, events: List<CalendarEvent>): Set<CalendarImportIdentity> {
        val predicate = store.predicateForEventsWithStartDate(
            startDate = NSDate.dateWithTimeIntervalSince1970((events.minOf { it.startMillis } - 86_400_000L) / 1000.0),
            endDate = NSDate.dateWithTimeIntervalSince1970((events.maxOf { it.endMillis } + 86_400_000L) / 1000.0),
            calendars = null,
        )
        return store.eventsMatchingPredicate(predicate).orEmpty().mapNotNull { item ->
            val event = item as? EKEvent ?: return@mapNotNull null
            val uid = calendarUidOf(event.notes) ?: return@mapNotNull null
            val start = event.startDate ?: return@mapNotNull null
            CalendarImportIdentity(uid, (start.timeIntervalSince1970 * 1000.0).toLong())
        }.toSet()
    }
}
