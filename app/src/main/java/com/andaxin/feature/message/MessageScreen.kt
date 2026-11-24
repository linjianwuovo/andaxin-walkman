package com.andaxin.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andaxin.shell.Refreshable
import com.andaxin.shell.pressClick
import com.andaxin.app.LocalAppGraph
import com.andaxin.core.design.Gap
import com.andaxin.core.design.LocalInk
import com.andaxin.core.design.Rhythm
import com.andaxin.core.design.Type
import com.andaxin.data.MessageItem
import com.andaxin.shell.Hairline
import com.andaxin.shell.Sheet
import com.andaxin.shell.adxViewModel
import com.andaxin.shell.ErrorLine
import com.andaxin.shell.ShelfTail
import com.andaxin.shell.registerShelfNavigationViewport
import com.andaxin.state.MessageModel

@Composable
fun MessageScreen(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val model = adxViewModel { MessageModel(it.messages) }
    val state by model.state.collectAsStateWithLifecycle()
    // 第一下按下去只是把这一行 arm 起来，第二次才真发 `readPushMessage.do`
    var armedRead by remember { mutableStateOf<String?>(null) }

    // 下拉一次重拉这一屏：新消息不用杀进程重开才看得见
    Refreshable({ model.refresh() }, modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        // 把这一屏的滚动范围报给底栏，收起/展开才有判据（见 ShelfPageViewport.kt）。
        registerShelfNavigationViewport(scroll)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = Gap.screen),
        ) {
            Spacer(Modifier.height(Gap.md))
            val unreadCount = state.items.count { it.unread }
            val ink = LocalInk.current
            Column(verticalArrangement = Arrangement.spacedBy(Gap.xs)) {
                Text("消息", fontSize = Type.display, fontWeight = FontWeight.SemiBold, color = ink.strong)
                if (!state.firstLoad || state.items.isNotEmpty()) {
                    Text(
                        text = "${state.items.size} 条消息 · $unreadCount 条未读",
                        fontSize = Type.meta,
                        lineHeight = Type.metaLine,
                        color = if (unreadCount > 0) MaterialTheme.colorScheme.primary else ink.muted,
                    )
                }
            }
            Spacer(Modifier.height(Gap.lg))

            if (state.items.isEmpty() && state.error == null) {
                Sheet {
                    com.andaxin.shell.EmptyState(if (state.firstLoad) "正在加载消息…" else "暂无消息")
                }
            } else if (state.items.isNotEmpty()) {
                Sheet {
                    state.items.forEachIndexed { i, item ->
                        // 「标已读」也是一发写操作（`msgCenter/readPushMessage.do`），以前点一下行就替学校发了，
                        // 而这条口的成功响应我们一次都没验过 —— 现在要连按两下，第一下只把这行变成"再按一次"
                        MessageRow(item, armed = armedRead == item.id) {
                            if (!item.unread) return@MessageRow
                            if (armedRead == item.id) {
                                armedRead = null
                                model.markRead(item.id)
                            } else {
                                armedRead = item.id
                            }
                        }
                        if (i != state.items.lastIndex) {
                            Spacer(Modifier.height(Gap.xs))
                            Hairline()
                            Spacer(Modifier.height(Gap.xs))
                        }
                    }
                }
            }

            // 标已读没成，红点就还在那儿 —— 这时候必须说一句为什么，不然看着像点了没反应
            state.readError?.let {
                Spacer(Modifier.height(Gap.md))
                Text(it, fontSize = Type.meta, lineHeight = Type.metaLine, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(Gap.lg))
            val lastRun by graph.poller.lastRunAt.collectAsStateWithLifecycle(0L)
            val busy by graph.poller.busy.collectAsStateWithLifecycle(false)
            Text(
                text = when {
                    busy -> "正在拉新消息…"
                    lastRun == 0L -> "还没轮询过"
                    else -> "上次刷新：" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                        .format(java.util.Date(lastRun)) + "，每 5 分钟一次"
                },
                fontSize = Type.micro,
                color = LocalInk.current.faint,
            )
            Spacer(Modifier.height(Gap.sm))
            state.error?.let { ErrorLine(it) }
            ShelfTail()
        }
    }
}

@Composable
private fun MessageRow(item: MessageItem, armed: Boolean = false, onClick: () -> Unit) {
    val ink = LocalInk.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = fontScale > 1.3f || maxWidth < 280.dp
        Row(
            Modifier.fillMaxWidth().pressClick(shrink = false, onClick = onClick)
                .heightIn(min = 48.dp).padding(vertical = Rhythm.rowV),
        ) {
            if (item.unread) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.width(Gap.sm))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontSize = Type.title,
                    lineHeight = Type.bodyLine,
                    fontWeight = if (item.unread) FontWeight.Medium else FontWeight.Normal,
                    color = ink.strong,
                    maxLines = if (stacked) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.brief.isNotBlank()) {
                    Spacer(Modifier.height(Gap.xxs))
                    Text(item.brief, fontSize = Type.meta, lineHeight = Type.metaLine, color = ink.muted,
                        maxLines = if (stacked) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                }
                if (stacked) {
                    Spacer(Modifier.height(Gap.sm))
                    MessageTime(item, armed)
                }
            }
            if (!stacked) {
                Spacer(Modifier.width(Gap.md))
                Column(Modifier.widthIn(max = 96.dp), horizontalAlignment = Alignment.End) {
                    MessageTime(item, armed)
                }
            }
        }
    }
}

@Composable
private fun MessageTime(item: MessageItem, armed: Boolean) {
    val ink = LocalInk.current
    if (armed) {
        Text("再按一次标已读", fontSize = Type.micro, lineHeight = Type.metaLine,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = Gap.xxs))
    }
    Text(item.sentAt.substringAfter(' ').ifBlank { item.sentAt },
        fontSize = Type.micro, lineHeight = Type.microLine, color = ink.muted)
}
