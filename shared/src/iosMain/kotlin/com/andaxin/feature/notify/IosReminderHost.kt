package com.andaxin.feature.notify

import com.andaxin.core.schedule.ReminderPlan
import com.andaxin.feature.brief.ReminderHost

/*
 * 宿主那台落点的 iOS 实现（缝见 logicMain 的 ReminderHost）。
 * available 恒真：这一端从 iOS 10 起就有 UNUserNotificationCenter，排程链本身就是它吃的东西。
 * apply 把算好的链交出去；request/refresh 两问授权；lastStatus 原样递回那句人话。
 */
internal object IosReminderHost : ReminderHost {
    override val available: Boolean get() = true
    override fun requestAuthorization(onDone: (Boolean) -> Unit) = IosClassReminder.requestAuthorization(onDone)
    override fun refreshAuthorization(onReady: () -> Unit) = IosClassReminder.refreshAuthorization(onReady)
    override fun authorizationSummary(): String = IosClassReminder.authorizationSummary()
    override fun apply(plan: ReminderPlan): String = IosClassReminder.apply(plan)
}
