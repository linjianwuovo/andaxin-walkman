package com.andaxin.feature.notify

import com.andaxin.core.concurrency.nowMillis
import com.andaxin.core.schedule.ArmedLesson
import com.andaxin.core.schedule.ReminderPlan
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.NSURL
import platform.Foundation.localTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/*
 * iOS 的上课提醒落点（安卓那份是 AlarmManager + 每分钟链，见 app 侧 ClassReminder.kt）。
 * 排程口径全在 logicMain 的 planClassReminder，两端共用，这里只管"把链交给系统"。
 *
 * 为什么排的是「每分钟一颗日历触发器」而不是「应用每分钟醒一次」：iOS 没有后者
 * （后台执行只给几分钟，BGAppRefreshTask 根本不保证分钟级），唯一能让"课前 N 分钟起
 * 每分钟刷一次"成立的机制就是把整条离散链**提前**交给系统（UNCalendarNotificationTrigger）。
 * 同一节课的档共用一条 threadIdentifier，通知中心按一组收着逐档换文案，
 * 对齐安卓那条固定 NOTE_ID 反复 notify 的效果。
 *
 * 全程不出声（对齐他 10-25 那句「不要响」）：sound 设 nil、badge 不动 ——
 * 红点被一条倒计时通知推着走是另一种吵。
 *
 * 系统对单应用待排通知的硬上限是 64 条：排之前先按自己的前缀清掉旧链
 * （改提前量/换课表重排时旧的半截链不许还活着），清的时候只认前缀，别的手上的通知不碰。
 */
@OptIn(ExperimentalForeignApi::class)
object IosClassReminder {

    /** 待排/已排链的公共前缀；清旧链时只认它。 */
    private const val ID_PREFIX = "andaxin.class."

    /** 系统硬上限 64，留几颗余量。 */
    private const val PENDING_CAP = 60

    /** 最近一次排程的人话结果，界面上"退回来那一帧"直接读它。 */
    var lastStatus: String = "正在看本机存了什么…"
        private set

    /** 上一次授权读数（-1 = 还没拿到过）。 */
    private var lastAuthStatus: Long = -1L

    fun authorizationSummary(): String = when (lastAuthStatus) {
        UNAuthorizationStatusAuthorized -> "通知权限：已允许"
        UNAuthorizationStatusProvisional -> "通知权限：静默允许（来了不进横幅）"
        UNAuthorizationStatusEphemeral -> "通知权限：临时允许"
        UNAuthorizationStatusDenied -> "通知权限：被拒 —— 去系统设置里给一下，否则倒计时一条都发不出来"
        UNAuthorizationStatusNotDetermined -> "通知权限：还没问过"
        else -> "通知权限：状态还没拿到"
    }

    /** 问一次系统授权；结果记下来，界面上那句副标题跟着变。 */
    fun requestAuthorization(onDone: (Boolean) -> Unit = {}) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        center.requestAuthorizationWithOptions(options) { granted, error ->
            if (error == null) lastAuthStatus = if (granted) UNAuthorizationStatusAuthorized else UNAuthorizationStatusDenied
            readAuthStatus(center)
            onDone(error == null && granted)
        }
    }

    /** 现问一遍授权状态（用户随时能在系统里拨，不缓存成"我明明开了它还说不行"）。 */
    fun refreshAuthorization(onReady: () -> Unit = {}) {
        readAuthStatus(UNUserNotificationCenter.currentNotificationCenter())
        onReady()
    }

    private fun readAuthStatus(center: UNUserNotificationCenter) {
        runCatching {
            center.getNotificationSettingsWithCompletionHandler { settings ->
                if (settings != null) lastAuthStatus = settings.authorizationStatus()
            }
        }
    }

    /** 打开系统里本应用那一页设置（权限被拒时界面上那颗键走这条）。 */
    fun openSystemSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url)
    }

    /**
     * 把排好的链交给系统。Off / 没课表 / 排完 时撤干净（对齐安卓「关了还留着倒计时是说不过去的」）。
     * 回一句人话，通知设置那一屏原样显示。
     */
    fun apply(plan: ReminderPlan): String {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        if (plan !is ReminderPlan.Armed) {
            clearScheduled(center)
            lastStatus = when (plan) {
                is ReminderPlan.NoTimetable -> plan.why
                is ReminderPlan.NothingLeft -> plan.why
                else -> "没开。"
            }
            return lastStatus
        }
        // 先清自己排过的旧链，再排新的。
        clearScheduled(center)
        val now = nowMillis()
        // 主链 + 跟排链（对齐 app 侧"响完接排下一颗"：iOS 没有运行中的接收者，
        // 预算内把下一节一并交出去，链断也不至于全天哑）。系统上限 64，这里按 PENDING_CAP 截。
        var placed = 0
        placed += schedule(center, plan.lesson, plan.chain, 0, now, PENDING_CAP - placed)
        plan.followUp?.let { (lesson, chain) ->
            if (placed < PENDING_CAP) placed += schedule(center, lesson, chain, 1, now, PENDING_CAP - placed)
        }
        lastStatus = plan.status + "，走系统通知那条路（权限被拒则一条都发不出来）"
        return lastStatus
    }

    /** 一节课的链逐档落给系统；回实际排下去的颗数，超出 budget 的尾巴不排。 */
    private fun schedule(
        center: UNUserNotificationCenter,
        lesson: ArmedLesson,
        chain: List<Pair<Long, Int>>,
        chainIdx: Int,
        now: Long,
        budget: Int,
    ): Int {
        var placed = 0
        for ((fireAtMs, left) in chain) {
            if (placed >= budget) break
            if (fireAtMs <= now - 1_000L) continue
            val content = UNMutableNotificationContent()
            content.setTitle(if (left > 0) "快上课了" else "现在上课")
            content.setBody(
                listOf(
                    if (left > 0) "还有 $left 分钟" else "现在上课",
                    "第 ${lesson.period} 节",
                    clockText(fireAtMs),
                    lesson.room.takeIf { it.isNotBlank() },
                ).filterNotNull().joinToString(" · "),
            )
            content.setSubtitle("上课提醒 · 节次时间照校方作息表")
            // 同一节课的链共用一条 thread：通知中心按一组收，逐档换文案。
            content.setThreadIdentifier("$ID_PREFIX${lesson.startMs}")
            // 不出声：sound 保持 nil（安卓那边是渠道清铃声 + setSilent 两道，这儿一道就够）。
            content.setSound(null)
            val units = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
                NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond
            val comps = NSCalendar.currentCalendar()
                .components(units, NSDate.dateWithTimeIntervalSince1970(fireAtMs / 1000.0))
            comps.timeZone = NSTimeZone.localTimeZone
            val trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(comps, repeats = false)
            val request = UNNotificationRequest.requestWithIdentifier(
                "$ID_PREFIX${lesson.startMs}.$chainIdx.$left", content, trigger,
            )
            center.addNotificationRequest(request, withCompletionHandler = null)
            placed += 1
        }
        return placed
    }

    /** 只撤自己排过的链：按前缀找 identifier，别的通知一条不碰。 */
    fun clearScheduled() = clearScheduled(UNUserNotificationCenter.currentNotificationCenter())

    private fun clearScheduled(center: UNUserNotificationCenter) {
        center.getPendingNotificationRequestsWithCompletionHandler { list ->
            val mine = list.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier() }
                .filter { it.startsWith(ID_PREFIX) }
            if (mine.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(mine)
        }
        center.getDeliveredNotificationsWithCompletionHandler { list ->
            val mine = list.orEmpty().mapNotNull { (it as? UNNotification)?.request()?.identifier() }
                .filter { it.startsWith(ID_PREFIX) }
            if (mine.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(mine)
        }
    }

    /** 上课时刻 "MM-dd HH:mm"，口径对齐安卓 timeFmt（两端的倒计时文案要能对上）。 */
    private fun clockText(ms: Long): String {
        val units = NSCalendarUnitMonth or NSCalendarUnitDay or NSCalendarUnitHour or NSCalendarUnitMinute
        val comps = NSCalendar.currentCalendar()
            .components(units, NSDate.dateWithTimeIntervalSince1970(ms / 1000.0))
        return two(comps.month) + "-" + two(comps.day) + " " + two(comps.hour) + ":" + two(comps.minute)
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')
}
