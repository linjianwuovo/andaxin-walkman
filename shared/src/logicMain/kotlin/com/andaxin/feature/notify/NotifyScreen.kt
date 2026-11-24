package com.andaxin.feature.notify

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.feature.brief.BriefEnv
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.Chip
import com.andaxin.shell.Hairline
import com.andaxin.shell.RowLine
import com.andaxin.shell.Sheet

/**
 * 通知设置（共享层，两端同一屏）。
 *
 * 只写这一端真做得到的事：上课提醒的排程是平台各自的机制，iOS 当前没有排程，
 * 所以界面明写这一句，不摆一颗假开关。系统通知权限不归应用管，交给宿主打开系统设置页。
 */
@Composable
fun NotifyScreen(onBack: () -> Unit) {
    val ink = LocalInk.current
    ScaffoldPage(title = "通知设置", onBack = onBack) {
        Sheet {
            Text(
                text = "课程提醒的排程按平台各自实现：iOS 当前没有排程，不会自动弹上课提醒。",
                fontSize = Type.meta,
                lineHeight = Type.metaLine,
                color = ink.muted,
            )
        }
        Spacer(Modifier.height(Gap.lg))
        Sheet {
            Chip(
                text = if (BriefEnv.openSystemSettings == null) "不可用" else "开",
                picked = BriefEnv.openSystemSettings != null,
                onClick = { BriefEnv.openSystemSettings?.invoke() },
            )
            Spacer(Modifier.height(Gap.xs))
            Hairline()
            Spacer(Modifier.height(Gap.xs))
            RowLine(
                title = "打开系统通知设置",
                sub = if (BriefEnv.openSystemSettings == null) "当前不可用" else "跳转到系统设置",
                right = "›",
                onClick = { BriefEnv.openSystemSettings?.invoke() },
            )
        }
    }
}
