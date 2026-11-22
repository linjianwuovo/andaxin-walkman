package com.andaxin.feature.about

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.andaxin.BuildConfig
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.AndaXinTheme
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Radius
import com.andaxin.core.design.Type
import com.andaxin.core.settings.DayNight
import com.andaxin.data.capture.LastUpdate
import com.andaxin.data.capture.Release
import com.andaxin.feature.web.encodeUrl
import com.andaxin.shell.Hairline
import com.andaxin.shell.Sheet
import io.github.neoruaa.glasmio.icons.GlasmioIcon
import kotlinx.coroutines.launch

@Composable
internal fun UpdateCard(open: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur = BuildConfig.VERSION_NAME
    val beta = cur.endsWith("-beta")
    var latest by remember { mutableStateOf<Release?>(null) }
    var at by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf<String?>(null) }
    var cached by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf<String?>(null) }
    var queueFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        LastUpdate.fromJson(graph.store.peekString(graph.store.snapshot(), LastUpdate.KEY).orEmpty())
            ?.let { (r, _, t) ->
                latest = r
                at = t
                cached = true
            }
    }

    val state = updateCardState(cur, latest?.version, busy, why, cached)
    val check: () -> Unit = {
        if (!busy) {
            busy = true
            why = null
            queued = null
            queueFailed = false
            scope.launch {
                val result = graph.updates.check(cur, beta)
                busy = false
                val ok = result.getOrNull()
                if (ok == null) {
                    why = result.exceptionOrNull()?.message ?: "未能获取更新信息"
                } else {
                    latest = ok.latest
                    at = ok.checkedAt
                    cached = false
                    graph.store.setText(LastUpdate.KEY, LastUpdate.toJson(ok.latest, ok.newer, ok.checkedAt))
                }
            }
        }
    }
    val download: () -> Unit = download@{
        val r = latest ?: return@download
        if (busy) return@download
        queued = null
        queueFailed = false
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val channel = if (beta) "beta" else "stable"
            val version = r.version.take(48)
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('.', '_', '-')
                .ifEmpty { "unknown" }
            val file = "app-$channel-$version-${java.util.UUID.randomUUID()}.apk"
            dm.enqueue(
                DownloadManager.Request(Uri.parse(r.apkUrl))
                    .setTitle(file)
                    .setDescription("安大信 ${r.version}")
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalPublicDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS, file
                    ),
            )
            file
        }.onSuccess { file -> queued = "已交给系统下载：$file。请在通知栏打开并确认安装。" }
            .onFailure {
                queueFailed = true
                queued = "系统下载器未能接收：${it.message ?: it.javaClass.simpleName}"
            }
    }

    UpdateCardContent(
        currentVersion = cur,
        latest = latest,
        checkedAt = at,
        state = state,
        error = why,
        queued = queued,
        queueFailed = queueFailed,
        onPrimary = if (state.primaryAction == UpdateAction.Download) download else check,
        onCheck = check,
        onRelease = { latest?.let { open("web/" + encodeUrl(it.pageUrl)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateCardContent(
    currentVersion: String,
    latest: Release?,
    checkedAt: String,
    state: UpdateCardState,
    error: String?,
    queued: String?,
    queueFailed: Boolean,
    onPrimary: () -> Unit,
    onCheck: () -> Unit,
    onRelease: () -> Unit,
) {
    val ink = LocalInk.current
    val colors = MaterialTheme.colorScheme
    Sheet(Modifier.padding(top = Gap.xl)) {
        UpdateCardHeader(currentVersion.endsWith("-beta"), state)
        Spacer(Modifier.height(Gap.lg))
        Text("当前版本", fontSize = Type.meta, color = ink.muted)
        Text(
            currentVersion,
            fontSize = Type.body,
            lineHeight = Type.bodyLine,
            fontWeight = FontWeight.Medium,
            color = ink.strong,
        )
        Spacer(Modifier.height(Gap.md))
        Hairline()
        Spacer(Modifier.height(Gap.md))
        Text(state.latestLabel, fontSize = Type.meta, color = ink.muted)
        Text(
            latest?.version ?: when (state.phase) {
                UpdatePhase.Checking -> "等待检测结果"
                UpdatePhase.Error -> "暂未获取"
                else -> "尚未检测"
            },
            fontSize = if (latest == null) Type.body else Type.display,
            fontWeight = FontWeight.SemiBold,
            color = if (state.phase == UpdatePhase.Available) colors.primary else ink.strong,
            modifier = Modifier.fillMaxWidth(),
        )
        latest?.date?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(Gap.xs))
            Text("发布于 $it", fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
        }
        Spacer(Modifier.height(Gap.md))
        Text(
            state.description,
            fontSize = Type.meta,
            lineHeight = Type.metaLine,
            color = ink.body,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (checkedAt.isNotBlank()) {
            Spacer(Modifier.height(Gap.xs))
            Text("上次检测 $checkedAt", fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
        }
        error?.let {
            Spacer(Modifier.height(Gap.sm))
            UpdateFeedback(it, failed = true)
        }
        queued?.let {
            Spacer(Modifier.height(Gap.sm))
            UpdateFeedback(it, failed = queueFailed)
        }
        Spacer(Modifier.height(Gap.lg))
        Button(
            onClick = onPrimary,
            enabled = state.actionsEnabled,
            shape = Radius.pill,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = if (colors.primary.luminance() > 0.179f) Color.Black else Color.White,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                state.primaryLabel,
                fontSize = Type.body,
                lineHeight = Type.bodyLine,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        if (state.primaryAction == UpdateAction.Download || latest?.pageUrl?.isNotBlank() == true) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(Gap.xs),
            ) {
                if (state.primaryAction == UpdateAction.Download) {
                    TextButton(onClick = onCheck, enabled = state.actionsEnabled, colors = ButtonDefaults.textButtonColors(contentColor = ink.body), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("重新检测", fontSize = Type.meta, lineHeight = Type.metaLine, textAlign = TextAlign.Center)
                    }
                }
                if (latest?.pageUrl?.isNotBlank() == true) {
                    TextButton(onClick = onRelease, colors = ButtonDefaults.textButtonColors(contentColor = ink.body), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("查看发布页", fontSize = Type.meta, lineHeight = Type.metaLine, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        if (state.primaryAction == UpdateAction.Download || queued != null) {
            Spacer(Modifier.height(Gap.xs))
            Text("下载由系统管理，安装需要你确认。", fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
        }
    }
}

@Composable
private fun UpdateCardHeader(beta: Boolean, state: UpdateCardState) {
    val ink = LocalInk.current
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inlineStatus = maxWidth >= 300.dp && fontScale <= 1.3f
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gap.sm)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(Radius.field).background(colors.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.phase == UpdatePhase.Checking) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.primary)
                    } else {
                        GlasmioIcon("arrow.clockwise", contentDescription = null, tint = colors.primary, size = 22.dp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("检测更新", fontSize = Type.title, fontWeight = FontWeight.SemiBold, color = ink.strong)
                    Text(if (beta) "Beta 更新通道" else "正式版更新通道", fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted)
                }
                if (inlineStatus) UpdateStatusPill(state)
            }
            if (!inlineStatus) {
                Spacer(Modifier.height(Gap.sm))
                UpdateStatusPill(state, Modifier.padding(start = 48.dp))
            }
        }
    }
}

@Composable
private fun UpdateStatusPill(state: UpdateCardState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val available = state.phase == UpdatePhase.Available
    val failed = state.phase == UpdatePhase.Error
    Surface(
        modifier = modifier,
        shape = Radius.pill,
        color = when { failed -> colors.errorContainer; available -> colors.primary; else -> colors.surfaceVariant },
        contentColor = when { failed -> colors.onErrorContainer; available -> if (colors.primary.luminance() > 0.179f) Color.Black else Color.White; else -> colors.onSurfaceVariant },
    ) {
        Text(state.status, fontSize = Type.meta, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = Gap.md, vertical = Gap.xs))
    }
}

@Composable
private fun UpdateFeedback(text: String, failed: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = Radius.field,
        color = if (failed) colors.errorContainer else colors.surfaceVariant,
        contentColor = if (failed) colors.onErrorContainer else colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, fontSize = Type.meta, lineHeight = Type.metaLine, modifier = Modifier.padding(Gap.md))
    }
}

internal data class UpdateCardPreviewCase(val version: String?, val busy: Boolean = false, val error: String? = null, val cached: Boolean = false)

internal class UpdateCardPreviewCases : PreviewParameterProvider<UpdateCardPreviewCase> {
    override val values = sequenceOf(
        UpdateCardPreviewCase(null),
        UpdateCardPreviewCase(null, busy = true),
        UpdateCardPreviewCase("0.9.34"),
        UpdateCardPreviewCase("0.9.35"),
        UpdateCardPreviewCase("0.9.33"),
        UpdateCardPreviewCase("0.9.35", cached = true),
        UpdateCardPreviewCase("2026.11.21-preview-with-a-long-version-name-beta", error = "连接超时：暂时无法访问更新源。这里保留上次结果，请稍后重新检测。", cached = true),
    )
}

@Preview(name = "Light states", widthDp = 360, locale = "zh", showBackground = true)
@Preview(name = "Dark states", widthDp = 360, locale = "zh", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Large font narrow", widthDp = 320, locale = "zh", showBackground = true, fontScale = 1.8f)
@Composable
private fun UpdateCardPreview(@PreviewParameter(UpdateCardPreviewCases::class) case: UpdateCardPreviewCase) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    AndaXinTheme(dayNight = if (dark) DayNight.Dark else DayNight.Light) {
        val release = case.version?.let {
            Release(it, "v$it", "2026-11-21 17:00（本地时间，完整发布日期）", "", "https://github.com/neoruaa/andaxin/releases/tag/v$it")
        }
        UpdateCardContent(
            currentVersion = "0.9.34",
            latest = release,
            checkedAt = if (release == null) "" else "2026-11-21 16:30",
            state = updateCardState("0.9.34", case.version, case.busy, case.error, case.cached),
            error = case.error,
            queued = null,
            queueFailed = false,
            onPrimary = {},
            onCheck = {},
            onRelease = {},
        )
    }
}
