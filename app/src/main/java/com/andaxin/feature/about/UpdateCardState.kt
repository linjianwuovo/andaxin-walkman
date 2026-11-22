package com.andaxin.feature.about

import com.andaxin.data.capture.plainOf
import com.andaxin.data.capture.versionNewer

internal enum class UpdatePhase { NotChecked, Checking, Current, Available, Ahead, Cached, Error }
internal enum class UpdateAction { Check, Download }

internal data class UpdateCardState(
    val phase: UpdatePhase,
    val status: String,
    val description: String,
    val latestLabel: String,
    val primaryLabel: String,
    val primaryAction: UpdateAction,
    val actionsEnabled: Boolean,
    val newer: Boolean,
)

internal fun updateCardState(
    currentVersion: String,
    latestVersion: String?,
    busy: Boolean,
    error: String?,
    cached: Boolean,
): UpdateCardState {
    val newer = latestVersion?.let { versionNewer(it, currentVersion) } ?: false
    val phase = when {
        busy -> UpdatePhase.Checking
        error != null -> UpdatePhase.Error
        latestVersion == null -> UpdatePhase.NotChecked
        cached -> UpdatePhase.Cached
        newer -> UpdatePhase.Available
        plainOf(latestVersion) == plainOf(currentVersion) -> UpdatePhase.Current
        else -> UpdatePhase.Ahead
    }
    val description = when (phase) {
        UpdatePhase.NotChecked -> "点击检测，获取最新版本信息。不会自动检查更新。"
        UpdatePhase.Checking -> "正在连接更新源，请稍候。"
        UpdatePhase.Error -> "暂时无法确认最新版本，请重新检测。"
        UpdatePhase.Cached -> if (newer) "上次查到有新版，建议重新检测确认。"
            else "这里保留上次结果，尚未重新检测。"
        UpdatePhase.Available -> "发现新版本，可以下载更新。"
        UpdatePhase.Current -> "当前已是最新版本。"
        UpdatePhase.Ahead -> "本机版本比更新源中的版本更新。"
    }
    val action = if (!busy && error == null && newer) UpdateAction.Download else UpdateAction.Check
    return UpdateCardState(
        phase = phase,
        status = when (phase) {
            UpdatePhase.NotChecked -> "待检测"
            UpdatePhase.Checking -> "检测中"
            UpdatePhase.Current -> "已是最新"
            UpdatePhase.Available -> "有新版本"
            UpdatePhase.Ahead -> "本机更新"
            UpdatePhase.Cached -> "上次结果"
            UpdatePhase.Error -> "检测失败"
        },
        description = description,
        latestLabel = if (latestVersion != null && (cached || busy || error != null)) "上次查到的版本" else "最新版本",
        primaryLabel = when {
            busy -> "正在检测…"
            error != null -> "重新检测"
            action == UpdateAction.Download -> "下载新版"
            else -> "检测更新"
        },
        primaryAction = action,
        actionsEnabled = !busy,
        newer = newer,
    )
}
