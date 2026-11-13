package com.andaxin.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Type
import com.andaxin.feature.brief.ScaffoldPage
import com.andaxin.shell.Sheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 开源许可。
 *
 * 这一屏是 GPLv3 逼出来的，不是顺手加的"关于"页：
 *  - §5(d) 要求交互界面里能方便、显眼地看到版权声明、无担保声明、许可全文和怎么拿副本；
 *  - 光在仓库根放一份 LICENSE 不够 —— 装到手机上的人打不开仓库。
 * 所以版权与无担保写在最上面（打开就能看见，不用先滚 674 行），全文跟在后面，可以长按复制。
 *
 * 两份文本都不编译进代码，放 assets/licenses/：正文 674 行 + 第三方清单，
 * 塞进 Kotlin 字符串既没法读，也没法跟仓库根的 LICENSE 对 diff。
 * 读失败必须在屏上显示一行明确的错，不许留白 —— 一屏空白会被当成"这版没有许可"。
 */
private const val LICENSE_ASSET = "licenses/GPL-3.0.txt"
private const val NOTICES_ASSET = "licenses/THIRD_PARTY.md"

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    // 两个 null = 还在读；失败单独落在 loadError 上。
    // 不共用同一份状态："读完了但内容是空串"和"压根没读成"是两回事，混在一起就没法如实报错。
    var license by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // assets 里是几十到上百 KB，必须在 IO 线程读；主线程读会在大文件上卡出一帧
        val result = withContext(Dispatchers.IO) {
            runCatching { readAsset(context, LICENSE_ASSET) to readAsset(context, NOTICES_ASSET) }
        }
        result.onSuccess { (l, n) ->
            license = l
            notices = n
        }
        result.onFailure { loadError = it.message ?: it::class.java.simpleName }
    }

    ScaffoldPage(title = "开源许可", onBack = onBack) {
        // 第一节：GPLv3 §5(d) 点名的四样东西，不折叠、不放在全文后面
        Sheet {
            Text(
                "安大信·随身听 (andaxin-walkman)",
                fontSize = Type.body,
                fontWeight = FontWeight.SemiBold,
                color = ink.strong,
            )
            Spacer(Modifier.height(Gap.xxs))
            Text("Copyright 2026 linjianwuovo", fontSize = Type.meta, color = ink.body)
            Spacer(Modifier.height(Gap.sm))
            Text(
                "本程序是自由软件：你可以依 GNU 通用公共许可协议第 3 版（GNU GPL v3）的条款" +
                    "再分发和/或修改它。",
                fontSize = Type.meta,
                lineHeight = Type.metaLine,
                color = ink.body,
            )
            Spacer(Modifier.height(Gap.xs))
            Text(
                "本程序不带任何担保，连「可商用」和「适合某个特定用途」这两条默示担保也没有。" +
                    "许可全文在本屏下方，也可以从仓库根的 LICENSE 取（github.com/linjianwuovo/andaxin-walkman）。",
                fontSize = Type.meta,
                lineHeight = Type.metaLine,
                color = ink.muted,
            )
            Spacer(Modifier.height(Gap.xs))
            Text(
                "本程序是 NEORUAA/MeiloX 1.55.1 的修改版本，改动自提交 c1fd1cd（2026-11-10）起。" +
                    "发布哪个版本的二进制，对应源码就是同名的 git tag。",
                fontSize = Type.micro,
                lineHeight = Type.microLine,
                color = ink.muted,
            )
        }

        Spacer(Modifier.height(Gap.lg))

        // 第二节：第三方清单。同样放 assets，跟仓库根 NOTICE 的内容对齐
        Sheet {
            SectionHeading("第三方组件")
            Spacer(Modifier.height(Gap.xs))
            Text(
                "下面这份是仓库根 NOTICE 的摘录，法律效力以 NOTICE 和各自许可原文为准。" +
                    "仓里还有几样不是本项目的东西（网易云音乐的专有运行时、SF Pro 与 MiSans 字体、" +
                    "PV Tool 的模板），它们不在 GPLv3 的授权范围内，NOTICE 第七节列了清单。",
                fontSize = Type.micro,
                lineHeight = Type.microLine,
                color = ink.muted,
            )
            Spacer(Modifier.height(Gap.sm))
            AssetText(notices, loadError, "第三方组件清单")
        }

        Spacer(Modifier.height(Gap.lg))

        // 第三节：许可全文。放在最后 —— 前面两节是"一眼要看到的"，这一节是"要的时候能查到"
        Sheet {
            SectionHeading("GNU GENERAL PUBLIC LICENSE 第 3 版")
            Spacer(Modifier.height(Gap.xs))
            Text(
                "以下是许可原文，未改动一个字。长按可以选中复制。",
                fontSize = Type.micro,
                lineHeight = Type.microLine,
                color = ink.muted,
            )
            Spacer(Modifier.height(Gap.sm))
            AssetText(license, loadError, "许可全文")
        }
    }
}

/** 小节标题：跟关于页其它卡里的小标题同一档字重，不另造样式。 */
@Composable
private fun SectionHeading(text: String) {
    Text(text, fontSize = Type.title, fontWeight = FontWeight.SemiBold, color = LocalInk.current.strong)
}

/**
 * 一段从 assets 读来的长文本。
 *
 * 三态必须分清楚：还在读、读失败、读到了。前两态都要在屏上留下字，
 * 否则用户看到的就是一张空白卡 —— 那比报错更糟，它看起来像"这版没有许可"。
 * 等宽字体是给许可原文用的：原文的缩进和条款编号靠空格对齐，换了比例字体就散了。
 */
@Composable
private fun AssetText(body: String?, error: String?, what: String) {
    val ink = LocalInk.current
    when {
        error != null -> Text(
            "读不到$what（$error）。这不是「没有」，是这一版打包漏了 assets/licenses/ 下的文件。",
            fontSize = Type.micro,
            lineHeight = Type.microLine,
            color = ink.muted,
        )
        body == null -> Text("正在读取$what…", fontSize = Type.micro, color = ink.faint)
        else -> SelectionContainer {
            Text(
                text = body,
                fontSize = Type.tiny,
                lineHeight = Type.tinyLine,
                fontFamily = FontFamily.Monospace,
                color = ink.body,
                modifier = Modifier.fillMaxWidth().padding(top = Gap.xxs),
            )
        }
    }
}

/** 读 assets 里的一份 UTF-8 文本。路径错了、文件没打进包，都会在这里抛出来，由调用方如实显示。 */
private fun readAsset(context: android.content.Context, path: String): String =
    context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
