package com.andaxin.feature.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andaxin.shared.IosImages
import kotlinx.coroutines.launch

/*
 * 音乐 tab。这一版在"能听歌"的最小闭环上做了 UI 打磨：
 *   搜 → 点一条开始放 → 底下那条播放条点开就是全屏播放器。
 *
 * 0.9.32 这一发把"纯文字"换成了真正的播放器观感：
 *   ① 列表行 / 播放条 / 播放器都画专辑封面（走 IosImages.load，内存 LRU 缓存，
 *      取不到就一块中性色 + 音符占位，不空一块灰）；
 *   ② 播放器改成大封面居中 + 图标 transport（上一首 / 播放暂停 / 下一首），
 *      封面用 BoxWithConstraints 按剩余空间取正方形，矮屏自动缩小、控件永远露得出；
 *   ③ 播放/暂停做成圆形实心钮，上一首/下一首是图标钮。图标用 Canvas 现画，
 *      不引 material-icons 依赖（共享层目前只有 material3，没那套图标）。
 *
 * 放歌仍是 AVPlayer，进度每 0.5 秒问一次；锁屏封面由 NowPlaying 原生 UIImage 那条线画，
 * 与这屏的封面是同一张 URL、各取各的缓存。
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(controller.songs, key = { it.id }) { song ->
                val selected = controller.current?.id == song.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { controller.play(song) } }
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(song.cover, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
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
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AlbumArt(song.cover, Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
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
                    FilledIconButton(
                        onClick = { controller.toggle() },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        TransportIcon(
                            kind = if (controller.playing) Transport.Pause else Transport.Play,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerPanel(controller: MusicController, onBack: () -> Unit) {
    val song = controller.current ?: return
    val total = if (controller.duration > 0.0) controller.duration else 1.0
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← 收起") }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (controller.queue.isEmpty()) "" else "第 " + (controller.queueIndexOf(song) + 1) + " / " + controller.queue.size + " 首",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        // 大封面：吃掉剩余高度，取宽高中较小的那个做正方形 —— 矮屏自动缩小，下面的控件永远露得出。
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            AlbumArt(song.cover, Modifier.size(side), RoundedCornerShape(20.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(song.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = song.artist + if (song.album.isEmpty()) "" else " · " + song.album,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
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
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.previous() }, modifier = Modifier.size(52.dp)) {
                TransportIcon(Transport.Prev, MaterialTheme.colorScheme.onSurface, Modifier.size(26.dp))
            }
            FilledIconButton(
                onClick = { controller.toggle() },
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                TransportIcon(
                    kind = if (controller.playing) Transport.Pause else Transport.Play,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = { controller.next() }, modifier = Modifier.size(52.dp)) {
                TransportIcon(Transport.Next, MaterialTheme.colorScheme.onSurface, Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "队列就是这次搜索的结果；放到末尾会回到第一首。锁屏和耳机线上是同一套按钮。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 专辑封面。走 IosImages.load（NSURLSession + Skia 解码 + 内存 LRU），取不到就一块中性色 + 音符占位。
 * 形状由调用给的 modifier 定尺寸、shape 定圆角；列表 48dp、播放条 44dp、播放器按剩余空间取大正方形。
 */
@Composable
private fun AlbumArt(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bmp = if (url.isNullOrBlank()) null else IosImages.load(url)
    }
    val b = bmp
    if (b != null) {
        Image(
            bitmap = b,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        BoxWithConstraints(
            modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "♪",
                fontSize = (maxWidth.value * 0.4f).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

private enum class Transport { Play, Pause, Next, Prev }

/** Canvas 现画的 transport 图标（三角 = 播放、双竖 = 暂停、三角+竖 = 下一首、竖+三角 = 上一首），不引图标依赖。 */
@Composable
private fun TransportIcon(kind: Transport, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val s = minOf(size.width, size.height)
        val ox = (size.width - s) / 2f
        val oy = (size.height - s) / 2f
        fun px(f: Float) = ox + s * f
        fun py(f: Float) = oy + s * f
        when (kind) {
            Transport.Play -> {
                val p = Path().apply {
                    moveTo(px(0.30f), py(0.18f))
                    lineTo(px(0.82f), py(0.50f))
                    lineTo(px(0.30f), py(0.82f))
                    close()
                }
                drawPath(p, tint)
            }
            Transport.Pause -> {
                val barW = s * 0.20f
                val gap = s * 0.18f
                val left = px(0.5f) - (barW * 2 + gap) / 2f
                val top = py(0.18f)
                val bh = s * 0.64f
                val cr = CornerRadius(barW * 0.35f, barW * 0.35f)
                drawRoundRect(tint, Offset(left, top), Size(barW, bh), cr)
                drawRoundRect(tint, Offset(left + barW + gap, top), Size(barW, bh), cr)
            }
            Transport.Next -> {
                val p = Path().apply {
                    moveTo(px(0.20f), py(0.22f))
                    lineTo(px(0.60f), py(0.50f))
                    lineTo(px(0.20f), py(0.78f))
                    close()
                }
                drawPath(p, tint)
                drawRoundRect(tint, Offset(px(0.66f), py(0.22f)), Size(s * 0.12f, s * 0.56f), CornerRadius(s * 0.04f, s * 0.04f))
            }
            Transport.Prev -> {
                drawRoundRect(tint, Offset(px(0.22f), py(0.22f)), Size(s * 0.12f, s * 0.56f), CornerRadius(s * 0.04f, s * 0.04f))
                val p = Path().apply {
                    moveTo(px(0.80f), py(0.22f))
                    lineTo(px(0.40f), py(0.50f))
                    lineTo(px(0.80f), py(0.78f))
                    close()
                }
                drawPath(p, tint)
            }
        }
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
