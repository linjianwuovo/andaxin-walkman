package com.andaxin.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
 * 音乐 tab。第一版就三件事：搜、点一条放、底部一条能暂停的播放条。
 *
 * 不做的事（这一版故意不做，别当成忘了）：
 *  - 进度条/拖拽：AVPlayer 的 periodicTimeObserver 要 C 函数指针，先不做假进度条。
 *  - 后台播放/锁屏控制：要开 UIBackgroundModes=audio + MPNowPlayingInfoCenter，下一版。
 *  - 歌单/每日推荐/播放历史：都要签名接口。
 */
@Composable
fun MusicScreen() {
    val controller = remember { MusicController() }
    val scope = rememberCoroutineScope()
    var showLogin by remember { mutableStateOf(false) }

    if (showLogin) {
        NeteaseLoginScreen(
            onDone = { token ->
                controller.musicU = token
                controller.message = "已登录网易云（MUSIC_U 已保存）"
                showLogin = false
            },
            onCancel = { showLogin = false },
        )
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
                text = if (controller.musicU == null) "网易云：未登录（匿名只放试听）" else "网易云：已登录",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { if (controller.musicU == null) showLogin = true else controller.musicU = null },
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
                        text = "搜一下想听的歌。第一版只有匿名链路，会员歌会退到外链那份。",
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
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(song.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = song.artist,
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
