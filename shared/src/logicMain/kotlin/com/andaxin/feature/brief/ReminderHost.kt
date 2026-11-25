package com.andaxin.feature.brief

import com.andaxin.core.schedule.ReminderPlan

/**
 * 宿主插进来的「上课提醒落点」。
 *
 * 排程口径在 logicMain 的 planClassReminder（两端共用，纯算术），但**把一条链交给系统**
 * 这件事两端完全是两套机制：安卓是 AlarmManager + 每分钟一颗接一颗的滴答链（app 模块里），
 * iOS 是把整条离散链提前交给 UNUserNotificationCenter 的日历触发器（iosMain 里）。
 * 共享屏不碰机制，只认这三问：可用吗 / 问授权 / 交链。
 *
 * 没插（默认 null）或 available=false，共享屏就不摆开关 —— 摆一颗按不动的哑按钮比少一格糟，
 * 跟 [BriefEnv.canOpen] 同一个规矩。安卓的 app 模块今天仍用自己的 NotifyScreen（直读
 * ClassReminder 那台）；哪天安卓换共享屏，把 app 侧那台包一层插上即可。
 */
interface ReminderHost {
    /** 这一端做得到排程（做不到的话那一节整块不画开关）。 */
    val available: Boolean

    /** 问一次系统授权（iOS 弹框；安卓那边是 POST_NOTIFICATIONS）。 */
    fun requestAuthorization(onDone: (Boolean) -> Unit = {})

    /** 现读一遍授权状态（系统那一档用户随时能拨，不缓存成"我明明开了它还说不行"）。 */
    fun refreshAuthorization(onReady: () -> Unit = {})

    /** 授权那一闸的现状态，直接画在副标题下面（"通知权限：被拒 —— …"）。 */
    fun authorizationSummary(): String

    /** 把排好的链交给系统；回一句人话（"已排：…"/"没开。"），屏上原样显示。 */
    fun apply(plan: ReminderPlan): String
}
