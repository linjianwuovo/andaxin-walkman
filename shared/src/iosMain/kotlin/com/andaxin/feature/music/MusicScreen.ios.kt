package com.andaxin.feature.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andaxin.shared.IosImages
import kotlinx.coroutines.launch

private enum class MusicTab(val label: String) {
    Home("首页"), Discover("发现"), Podcast("播客"), Library("音乐库"), Search("搜索"), Setting("设置"),
}

private enum class Transport { Play, Pause, Next, Prev, Mode, Queue, Heart }
private enum class LibrarySection(val label: String) { Recent("最近播放"), Liked("我喜欢的"), Queue("播放队列") }

@Composable
fun MusicScreen() {
    val controller = remember { MusicController() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var showLogin by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

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
        PlayerPanel(
            controller = controller,
            onBack = { expanded = false },
            onQueue = { showQueue = true },
            onLogin = { showLogin = true },
            onLogout = { controller.setMusicU(null) },
        )
        if (showQueue) QueueSheet(controller = controller, onClose = { showQueue = false })
        return
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CapsuleTabs(
            tabs = MusicTab.entries.map { it.label },
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )
        Box(Modifier.weight(1f)) {
            when (MusicTab.entries[selectedTab.coerceIn(0, MusicTab.entries.lastIndex)]) {
                MusicTab.Home -> HomeTab(controller, onSearch = { keyword ->
                    scope.launch { controller.searchAsPlaylist(keyword) }
                })
                MusicTab.Discover -> DiscoverTab(controller, onSearch = { keyword ->
                    keyboard?.hide()
                    scope.launch { controller.searchAsPlaylist(keyword) }
                })
                MusicTab.Podcast -> PodcastTab()
                MusicTab.Library -> LibraryTab(controller)
                MusicTab.Search -> SearchTab(controller, onSearch = {
                    keyboard?.hide()
                    scope.launch { controller.searchRemembered() }
                })
                MusicTab.Setting -> SettingTab(controller, onLogin = { showLogin = true })
            }
        }
        controller.current?.let { song ->
            MiniPlayer(song, controller, expanded = { expanded = true })
        }
    }
}

@Composable
private fun CapsuleTabs(tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val indicator by animateFloatAsState(
        targetValue = selectedIndex.coerceAtLeast(0).toFloat(),
        animationSpec = spring(),
        label = "music-tab-indicator",
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(tabs.size) { index ->
            val selected = indicator.toInt() == index
            val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(onClick = { onSelect(index) }, shape = CircleShape, color = container, modifier = Modifier.height(38.dp)) {
                Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    Text(
                        tabs[index],
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = content,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeTab(controller: MusicController, onSearch: (String) -> Unit) {
    val recents = controller.history.take(8)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionTitle("最近播放") }
        if (recents.isEmpty()) item { EmptyHint("还没有播放记录，先去搜索或发现页找首歌。") }
        else items(recents, key = { it.id }) { SongRow(it, controller, showLike = true) }
        item { SectionTitle("推荐歌单") }
        items(listOf("热门", "新歌", "经典")) { keyword ->
            Surface(
                onClick = { onSearch(keyword) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(36.dp)) { drawCircle(Color(0xFFE8666A), size.minDimension / 2f) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(keyword + "推荐", style = MaterialTheme.typography.titleMedium)
                        Text("匿名搜索并自动排队", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        item { Text(controller.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DiscoverTab(controller: MusicController, onSearch: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchField(controller.query, { controller.query = it }, "发现：搜歌名或歌手", { onSearch(controller.query) })
        LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("热门", "新歌", "华语", "轻音乐", "电子", "经典")) { chip ->
                FilterChip(selected = false, onClick = { onSearch(chip) }, label = { Text(chip) })
            }
        }
        if (controller.searching) ProgressRow()
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(controller.songs, key = { it.id }) { SongRow(it, controller, showLike = true) }
            if (controller.songs.isEmpty() && !controller.searching) item { EmptyHint("点上面的分类，或直接搜索。") }
        }
    }
}

@Composable
private fun PodcastTab() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("播客", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("播客列表依赖登录态歌单同步，本轮只用匿名接口，先放本地占位。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LibraryTab(controller: MusicController) {
    var section by remember { mutableIntStateOf(0) }
    val sections = LibrarySection.entries
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sections.size) { index ->
                FilterChip(
                    selected = section == index,
                    onClick = { section = index },
                    label = { Text(sections[index].label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val songs = when (sections[section.coerceIn(0, sections.lastIndex)]) {
            LibrarySection.Recent -> controller.history
            LibrarySection.Liked -> controller.history.filter { controller.likedIds.contains(it.id) }
            LibrarySection.Queue -> controller.queue
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(songs, key = { it.id }) { SongRow(it, controller, showLike = true) }
            if (songs.isEmpty()) item { EmptyHint("这一格还是空的。最近听的歌、收藏、队列会出现在这里。") }
        }
    }
}

@Composable
private fun SearchTab(controller: MusicController, onSearch: () -> Unit) {
    var historyVersion by remember { mutableIntStateOf(0) }
    val history = remember(historyVersion) { controller.searchHistory() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchField(controller.query, { controller.query = it }, "搜歌名或歌手", onSearch, withButton = true)
        if (history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("搜索历史", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { controller.clearSearchHistory(); historyVersion++ }) { Text("清空") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history) { word ->
                    FilterChip(selected = false, onClick = { controller.query = word; onSearch(); historyVersion++ }, label = { Text(word) })
                }
            }
        }
        controller.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
        if (controller.searching) ProgressRow()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(controller.songs, key = { it.id }) { SongRow(it, controller, showLike = true) }
            if (controller.songs.isEmpty() && !controller.searching) item { EmptyHint("搜一下想听的歌。匿名也能搜能放。") }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    withButton: Boolean = false,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); onSearch() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            placeholder = { Text(placeholder) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.weight(1f),
        )
        if (withButton) Button(onClick = submit, modifier = Modifier.padding(start = 8.dp)) { Text("搜索") }
    }
}

@Composable
private fun SettingTab(controller: MusicController, onLogin: () -> Unit) {
    var bitrateVersion by remember { mutableIntStateOf(0) }
    var lyricVersion by remember { mutableIntStateOf(0) }
    val bitrate = remember(bitrateVersion) { controller.bitrate }
    val lyricsVisible = remember(lyricVersion) { controller.lyricsVisible }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("播放与歌词")
        SettingRow("播放音质") {
            FilterChip(selected = bitrate == 128000, onClick = { controller.setBitrate(128000); bitrateVersion++ }, label = { Text("128k") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = bitrate == 320000, onClick = { controller.setBitrate(320000); bitrateVersion++ }, label = { Text("320k") })
        }
        SettingRow("歌词显示") {
            FilterChip(selected = lyricsVisible, onClick = { controller.setLyricsVisible(true); lyricVersion++ }, label = { Text("开") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = !lyricsVisible, onClick = { controller.setLyricsVisible(false); lyricVersion++ }, label = { Text("关") })
        }
        SettingRow("网易云账号") {
            if (controller.musicU == null) TextButton(onClick = onLogin) { Text("网页登录") }
            else {
                Text("已登录", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { controller.setMusicU(null) }) { Text("退出") }
            }
        }
        SectionTitle("本地数据")
        SettingRow("清空搜索历史") { Button(onClick = { controller.clearSearchHistory() }) { Text("清空") } }
        SettingRow("清空我的喜欢") { Button(onClick = { controller.clearLikes() }) { Text("清空") } }
        SectionTitle("关于")
        Text("多 tab、队列、逐字歌词、本地收藏。只用匿名 netease web 接口，不接官方 API。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingRow(title: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

@Composable
private fun ProgressRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun SongRow(song: MusicSong, controller: MusicController, showLike: Boolean) {
    val selected = controller.current?.id == song.id
    Row(
        Modifier.fillMaxWidth().clickable { controller.playFromUi(song) }
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song.cover, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.artist + if (song.album.isEmpty()) "" else " · " + song.album,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showLike) TransportIcon(Transport.Heart, MaterialTheme.colorScheme.primary, Modifier.size(22.dp), filled = controller.likedIds.contains(song.id), onClick = { controller.toggleLike(song) })
    }
}

@Composable
private fun MiniPlayer(song: MusicSong, controller: MusicController, expanded: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable { expanded() },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(song.cover, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist + "  ·  " + clock(controller.position) + " / " + clock(controller.duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledIconButton(
                onClick = { controller.toggle() },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            ) { TransportIcon(if (controller.playing) Transport.Pause else Transport.Play, MaterialTheme.colorScheme.onPrimary, Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun PlayerPanel(
    controller: MusicController,
    onBack: () -> Unit,
    onQueue: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val song = controller.current ?: return
    var showLyrics by remember { mutableStateOf(controller.lyricsVisible) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("← 收起") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showLyrics = !showLyrics; controller.setLyricsVisible(showLyrics) }) { Text(if (showLyrics) "歌词" else "封面") }
            TextButton(onClick = onQueue) { Text("队列") }
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (showLyrics) KaraokeLyricsView(controller, Modifier.fillMaxSize())
            else AlbumArt(song.cover, Modifier.size(minOf(maxWidth, maxHeight)), RoundedCornerShape(20.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(song.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artist + if (song.album.isEmpty()) "" else " · " + song.album, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("点歌词跳转 · " + clock(controller.position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(clock(controller.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TransportIcon(Transport.Mode, MaterialTheme.colorScheme.onSurface, Modifier.size(30.dp), onClick = { controller.cyclePlayMode() })
            IconButton(onClick = { controller.previous() }, modifier = Modifier.size(52.dp)) { TransportIcon(Transport.Prev, MaterialTheme.colorScheme.onSurface, Modifier.size(26.dp)) }
            FilledIconButton(
                onClick = { controller.toggle() },
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            ) { TransportIcon(if (controller.playing) Transport.Pause else Transport.Play, MaterialTheme.colorScheme.onPrimary, Modifier.size(30.dp)) }
            IconButton(onClick = { controller.next() }, modifier = Modifier.size(52.dp)) { TransportIcon(Transport.Next, MaterialTheme.colorScheme.onSurface, Modifier.size(26.dp)) }
            TransportIcon(Transport.Queue, MaterialTheme.colorScheme.onSurface, Modifier.size(30.dp), onClick = onQueue)
        }
        Text(when (controller.playMode) { 1 -> "单曲循环"; 2 -> "随机播放"; else -> "列表循环" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (controller.musicU == null) "未登录" else "已登录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = if (controller.musicU == null) onLogin else onLogout) { Text(if (controller.musicU == null) "登录" else "退出") }
        }
    }
}

@Composable
private fun KaraokeLyricsView(controller: MusicController, modifier: Modifier) {
    val state = rememberLazyListState()
    val activeIndex = remember(controller.lyrics, controller.position) {
        val nowMs = (controller.position * 1000.0).toLong()
        var active = -1
        for (i in controller.lyrics.indices) {
            if (controller.lyrics[i].timeMs <= nowMs) active = i else break
        }
        active
    }
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) state.animateScrollToItem(activeIndex, scrollOffset = -120)
    }
    if (controller.lyrics.isEmpty()) {
        val hint = if (controller.current == null) "播放歌曲后这里会显示逐字歌词" else "正在获取歌词…如果一直为空，请检查网络或重试"
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            TextButton(onClick = { controller.reloadLyrics() }) { Text("重试歌词") }
        }
        return
    }
    LazyColumn(
        state = state,
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 120.dp),
    ) {
        items(controller.lyrics.size) { index ->
            val line = controller.lyrics[index]
            val active = index == activeIndex
            Text(
                text = if (active) karaokeLine(line, controller.position) else AnnotatedString(line.text),
                style = if (active) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { controller.seekTo(line.timeMs / 1000.0) },
            )
        }
    }
}

@Composable
private fun karaokeLine(line: LyricLine, positionSeconds: Double): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val nowMs = (positionSeconds * 1000.0).toLong() - line.timeMs
    return buildAnnotatedString {
        if (line.wordTimes.size == line.text.length && line.text.isNotEmpty()) {
            line.text.forEachIndexed { i, ch ->
                val style = if (nowMs >= line.wordTimes[i]) SpanStyle(color = primary, fontWeight = FontWeight.Bold) else SpanStyle(color = dim)
                withStyle(style) { append(ch) }
            }
        } else withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append(line.text) }
    }
}

@Composable
private fun QueueSheet(controller: MusicController, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("播放队列", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(controller.queue) { index, song ->
                    val currentRow = song.id == controller.current?.id
                    Row(
                        Modifier.fillMaxWidth().clickable { controller.playQueueAt(index) }
                            .background(if (currentRow) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (currentRow) Text("♪", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumArt(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bmp = if (url.isNullOrBlank()) null else IosImages.load(url) }
    val b = bmp
    if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    else BoxWithConstraints(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text("♪", fontSize = (maxWidth.value * 0.4f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

@Composable
private fun TransportIcon(
    kind: Transport,
    tint: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val label = when (kind) {
        Transport.Play -> "播放"
        Transport.Pause -> "暂停"
        Transport.Next -> "下一首"
        Transport.Prev -> "上一首"
        Transport.Mode -> "播放模式"
        Transport.Queue -> "播放队列"
        Transport.Heart -> "收藏"
    }
    val clickable = if (onClick != null) modifier.clickable { onClick() } else modifier
    Canvas(clickable.semantics { contentDescription = label }) {
        val s = minOf(size.width, size.height)
        val ox = (size.width - s) / 2f
        val oy = (size.height - s) / 2f
        fun px(f: Float) = ox + s * f
        fun py(f: Float) = oy + s * f
        when (kind) {
            Transport.Play -> drawPath(Path().apply { moveTo(px(0.30f), py(0.18f)); lineTo(px(0.82f), py(0.50f)); lineTo(px(0.30f), py(0.82f)); close() }, tint)
            Transport.Pause -> {
                val barW = s * 0.20f; val gap = s * 0.18f
                val left = px(0.5f) - (barW * 2 + gap) / 2f; val top = py(0.18f); val bh = s * 0.64f
                val cr = CornerRadius(barW * 0.35f, barW * 0.35f)
                drawRoundRect(tint, Offset(left, top), Size(barW, bh), cr)
                drawRoundRect(tint, Offset(left + barW + gap, top), Size(barW, bh), cr)
            }
            Transport.Next -> {
                drawPath(Path().apply { moveTo(px(0.20f), py(0.22f)); lineTo(px(0.60f), py(0.50f)); lineTo(px(0.20f), py(0.78f)); close() }, tint)
                drawRoundRect(tint, Offset(px(0.66f), py(0.22f)), Size(s * 0.12f, s * 0.56f), CornerRadius(s * 0.04f, s * 0.04f))
            }
            Transport.Prev -> {
                drawRoundRect(tint, Offset(px(0.22f), py(0.22f)), Size(s * 0.12f, s * 0.56f), CornerRadius(s * 0.04f, s * 0.04f))
                drawPath(Path().apply { moveTo(px(0.80f), py(0.22f)); lineTo(px(0.40f), py(0.50f)); lineTo(px(0.80f), py(0.78f)); close() }, tint)
            }
            Transport.Mode -> {
                drawRoundRect(tint, Offset(px(0.16f), py(0.36f)), Size(s * 0.68f, s * 0.10f), CornerRadius(s * 0.05f, s * 0.05f))
                drawPath(Path().apply { moveTo(px(0.72f), py(0.20f)); lineTo(px(0.88f), py(0.41f)); lineTo(px(0.60f), py(0.41f)); close() }, tint)
                drawPath(Path().apply { moveTo(px(0.28f), py(0.80f)); lineTo(px(0.12f), py(0.59f)); lineTo(px(0.40f), py(0.59f)); close() }, tint)
            }
            Transport.Queue -> repeat(3) { i -> drawRoundRect(tint, Offset(px(0.18f), py(0.24f + i * 0.20f)), Size(s * 0.64f, s * 0.08f), CornerRadius(s * 0.04f, s * 0.04f)) }
            Transport.Heart -> {
                val p = Path().apply {
                    moveTo(px(0.50f), py(0.86f))
                    cubicTo(px(0.10f), py(0.55f), px(0.22f), py(0.18f), px(0.50f), py(0.38f))
                    cubicTo(px(0.78f), py(0.18f), px(0.90f), py(0.55f), px(0.50f), py(0.86f))
                    close()
                }
                drawPath(p, if (filled) tint else tint.copy(alpha = 0.55f))
            }
        }
    }
}

private fun clock(seconds: Double): String {
    if (seconds.isNaN() || seconds <= 0.0) return "0:00"
    val total = seconds.toInt()
    val minutes = total / 60
    val rest = total % 60
    return minutes.toString() + ":" + (if (rest < 10) "0" + rest else rest.toString())
}