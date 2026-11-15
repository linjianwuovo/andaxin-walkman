package com.andaxin.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * 音乐 tab。这一版是"能听歌"的最小闭环 + 一屏播放器：
 *   搜 → 点一条开始放 → 底下那条播放条点开就是播放器（进度可拖、上一首/下一首）
 *
 * 放歌用 AVPlayer，进度每 0.5 秒从播放器问一次；锁屏那边由 NowPlaying 写 nowPlayingInfo，
 * 封面也在那儿（原生 UIImage），所以这屏不加载图片 —— 列表里只放字，省一次网络。
 */
@Composable
fun MusicScreen() {
    val controller = remember { MusicController() }
    val scope = rememberCoroutineScope()
    var showLogin by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    if (showLogin) {
        NeteaseLoginScreen(
            onDone = { token ->
                controller.setMusicU(token)
                controller.message = "已登录网易云（MUSIC_U 已保存）"
                showLogin = false
            },
            onCancel = { showLogin = false },
        )
        return
    }

    if (expanded && controller.current != null) {
        PlayerPanel(controller = controller, onBack = { expanded = false })
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = controller.query,
                onValueChange = { controller.query = it },
                singleLine = true,
                placeholder = { Text("搜歌名或歌手") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { scope.launch { controller.search() } },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("搜索") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (controller.musicU == null) "网易云：未登录（匿名也能搜能放）" else "网易云：已登录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { if (controller.musicU == null) showLogin = true else controller.setMusicU(null) },
            ) { Text(if (controller.musicU == null) "登录" else "退出登录") }
        }

        controller.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        if (controller.searching) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            items(controller.songs, key = { it.id }) { song ->
                val selected = controller.current?.id == song.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { controller.play(song) } }
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = song.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artist + if (song.album.isEmpty()) "" else " · " + song.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (controller.songs.isEmpty() && !controller.searching) {
                item {
                    Text(
                        text = "搜一下想听的歌。不登录也能搜能放，会员歌会退到外链那份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    )
                }
            }
        }

        controller.current?.let { song ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { expanded = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(song.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = song.artist + "  ·  " + clock(controller.position) + " / " + clock(controller.duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(onClick = { controller.toggle() }) { Text(if (controller.playing) "暂停" else "播放") }
                }
            }
        }
    }
}

@Composable
private fun PlayerPanel(controller: MusicController, onBack: () -> Unit) {
    val song = controller.current ?: return
    val total = if (controller.duration > 0.0) controller.duration else 1.0
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← 收起") }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (controller.queue.isEmpty()) "" else "第 " + (controller.queueIndexOf(song) + 1) + " / " + controller.queue.size + " 首",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(40.dp))
        Text(song.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = song.artist + if (song.album.isEmpty()) "" else " · " + song.album,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(28.dp))
        Slider(
            value = controller.position.coerceIn(0.0, total).toFloat(),
            valueRange = 0f..total.toFloat(),
            onValueChange = { value ->
                controller.scrubbing = true
                controller.position = value.toDouble()
            },
            onValueChangeFinished = {
                controller.scrubbing = false
                controller.seekTo(controller.position)
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(controller.position), style = MaterialTheme.typography.labelSmall)
            Text(clock(controller.duration), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { controller.previous() }) { Text("上一首") }
            Button(onClick = { controller.toggle() }) { Text(if (controller.playing) "暂停" else "播放") }
            Button(onClick = { controller.next() }) { Text("下一首") }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "队列就是这次搜索的结果；放到末尾会回到第一首。锁屏和耳机线上是同一套按钮。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 秒 → m:ss。负数、NaN 都按 0 显示，别让进度那一行出现 "-1:60"。 */
private fun clock(seconds: Double): String {
    if (seconds.isNaN() || seconds <= 0.0) return "0:00"
    val total = seconds.toInt()
    val minutes = total / 60
    val rest = total % 60
    return minutes.toString() + ":" + (if (rest < 10) "0" + rest else rest.toString())
}
