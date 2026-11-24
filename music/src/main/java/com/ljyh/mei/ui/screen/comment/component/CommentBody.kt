package com.ljyh.mei.ui.screen.comment.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.SubcomposeAsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.source.api.ProviderIds
import com.ljyh.mei.source.shared.OnlineComment
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon

// Image identifiers used by the NetEase web comment renderer.
private val NeteaseCommentEmoji = mapOf(
    "大笑" to 86, "可爱" to 85, "憨笑" to 359, "色" to 95, "亲亲" to 363,
    "惊恐" to 96, "流泪" to 356, "亲" to 362, "呆" to 352, "哀伤" to 342,
    "呲牙" to 343, "吐舌" to 348, "撇嘴" to 353, "怒" to 361, "奸笑" to 341,
    "汗" to 97, "痛苦" to 346, "惶恐" to 354, "生病" to 350, "口罩" to 351,
    "大哭" to 357, "晕" to 355, "发怒" to 115, "开心" to 360, "鬼脸" to 94,
    "皱眉" to 87, "流感" to 358, "爱心" to 33, "心碎" to 34, "钟情" to 303,
    "星星" to 309, "生气" to 314, "便便" to 89, "强" to 13, "弱" to 372,
    "拜" to 14, "牵手" to 379, "跳舞" to 380, "禁止" to 374, "这边" to 262,
    "爱意" to 106, "示爱" to 376, "嘴唇" to 367, "狗" to 81, "猫" to 78,
    "猪" to 100, "兔子" to 459, "小鸡" to 450, "公鸡" to 461, "幽灵" to 116,
    "圣诞" to 411, "外星" to 101, "钻石" to 52, "礼物" to 107, "男孩" to 0,
    "女孩" to 1, "蛋糕" to 337, "18" to 186, "圈" to 312, "叉" to 313,
)
private val CommentEmojiToken = Regex("[\\[［]([^\\]］\\[［\\n]{1,12})[\\]］]")

@Composable
internal fun CommentBody(comment: OnlineComment, style: TextStyle) {
    val colors = LocalGlassColors.current
    if (comment.content.isNotBlank()) {
        val (body, names) = remember(comment.content, comment.providerId) {
            val names = mutableSetOf<String>()
            val body = buildAnnotatedString {
                var offset = 0
                if (comment.providerId == ProviderIds.NETEASE) {
                    for (match in CommentEmojiToken.findAll(comment.content)) {
                        val name = match.groupValues[1]
                        if (name !in NeteaseCommentEmoji) continue
                        append(comment.content.substring(offset, match.range.first))
                        appendInlineContent(name, match.value)
                        names += name
                        offset = match.range.last + 1
                    }
                }
                append(comment.content.substring(offset))
            }
            body to names
        }
        val inlineContent = remember(names) {
            names.associateWith { name ->
                InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                    SubcomposeAsyncImage(
                        model = "https://s1.music.126.net/style/web2/emt/emoji_${NeteaseCommentEmoji.getValue(name)}.png",
                        contentDescription = name,
                        modifier = Modifier.fillMaxSize(),
                        error = { SfIcon("face.smiling", null, tint = LocalGlassColors.current.secondaryContent, size = 16.dp) },
                    )
                }
            }
        }
        Text(body, inlineContent = inlineContent, style = style, color = colors.content)
    }
    comment.voiceDurationMs?.let { duration ->
        Row(
            Modifier.padding(top = if (comment.content.isBlank()) 0.dp else 6.dp)
                .background(colors.secondaryContent.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SfIcon("waveform", null, tint = colors.secondaryContent, size = 15.dp)
            Text(
                if (duration > 0) stringResource(R.string.comment_voice_seconds, (duration - 1) / 1000 + 1)
                else stringResource(R.string.comment_voice),
                color = colors.secondaryContent,
                style = IosTypography.footnote,
            )
        }
    }
}
