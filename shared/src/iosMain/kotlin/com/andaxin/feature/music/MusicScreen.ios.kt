package com.andaxin.feature.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import kotlinx.coroutines.CoroutineScope

private enum class MusicTab(val label: String) {
    Home("首页"), Discover("发现"), Podcast("播客"), Library("音乐库"), Search("搜索"), Setting("设置"),
}

private enum class Transport { Play, Pause, Next, Prev, Mode, Queue, Heart }


private enum class LibrarySection(val label: String) { Recent("最近播放"), Liked("我喜欢的"), Queue("播放队列") }

private sealed interface MusicDetail {
    data class Playlist(val id: Long) : MusicDetail
    data class TopList(val id: Long) : MusicDetail
    data class Album(val id: Long) : MusicDetail
    data class Artist(val id: Long) : MusicDetail
}

private data class DetailUiState(
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val cover: String = "",
    val songs: List<MusicSong> = emptyList(),
    val topList: Boolean = false,
    val related: List<MusicCatalogItem> = emptyList(),
    val relatedKind: String = "",
)

@Composable
private fun parseLyricColor(raw: String?): Color {
    val fallback = MaterialTheme.colorScheme.primary
    val value = raw?.trim().orEmpty().removePrefix("#")
    return runCatching {
        when (value.length) {
            6 -> Color((0xFF000000u + value.toULong(16)).toLong())
            8 -> Color(value.toULong(16).toLong())
            else -> fallback
        }
    }.getOrDefault(fallback)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen() {
    val controller = remember { MusicController() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var showLogin by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var selectedLabel by remember { mutableStateOf(controller.loadSelectedTabLabel()) }
    var detail by remember { mutableStateOf<MusicDetail?>(null) }

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

    val visibleTabs = remember(controller.tabOrder, controller.tabVisibility) {
        controller.tabOrder.filter { controller.tabVisibility[it] != false }
    }
    val selectedIndex = visibleTabs.indexOf(selectedLabel).coerceAtLeast(0)
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val topTabs = maxWidth > 768.dp
        Column(Modifier.fillMaxSize()) {
            if (topTabs) {
                CapsuleTabs(tabs = visibleTabs, selectedIndex = selectedIndex, onSelect = { selectedLabel = visibleTabs[it]; controller.setSelectedTab(selectedLabel) })
            }
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = selectedLabel,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                ) { label ->
                    MusicPage(
                        label = label,
                        controller = controller,
                        scope = scope,
                        keyboardVisible = keyboard != null,
                        onSearch = { keyword -> scope.launch { controller.searchAsPlaylist(keyword) } },
                        onSearchRemembered = { keyboard?.hide(); scope.launch { controller.searchRemembered() } },
                        onLogin = { showLogin = true },
                        onDetail = { detail = it },
                    )
                }
            }
            if (!topTabs) {
                CapsuleTabs(tabs = visibleTabs, selectedIndex = selectedIndex, onSelect = { selectedLabel = visibleTabs[it]; controller.setSelectedTab(selectedLabel) })
            }
            controller.current?.let { song -> MiniPlayer(song, controller, expanded = { expanded = true }) }
        }
        detail?.let { target -> MusicDetailSheet(target, controller, onClose = { detail = null }) }
    }
}

@Composable
private fun MusicPage(
    label: String,
    controller: MusicController,
    scope: CoroutineScope,
    keyboardVisible: Boolean,
    onSearch: (String) -> Unit,
    onSearchRemembered: () -> Unit,
    onLogin: () -> Unit,
    onDetail: (MusicDetail) -> Unit,
) {
    when (label) {
        "首页" -> HomeTab(controller, onSearch = onSearch, onDetail = onDetail)
        "发现" -> DiscoverTab(controller, onSearch = onSearch, onDetail = onDetail)
        "播客" -> PodcastTab()
        "音乐库" -> LibraryTab(controller, onDetail = onDetail)
        "搜索" -> SearchTab(controller, onSearch = onSearchRemembered, onDetail = onDetail)
        "设置" -> SettingTab(controller, onLogin = onLogin)
        "下载" -> ShellPage("下载", "匿名网页接口不提供稳定下载签名；这里保留入口，真正下载需要登录态与签名接口。")
        "云盘" -> ShellPage("云盘", "云盘需要登录账号和签名接口；iOS 当前只提供匿名能力，不显示虚构数据。")
        "历史" -> HistoryTab(controller, onDetail = onDetail)
        else -> ShellPage("页面", "这个页面暂未开放。")
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
private fun HomeTab(controller: MusicController, onSearch: (String) -> Unit, onDetail: (MusicDetail) -> Unit) {
    val recents = controller.history.take(8)
    var rankings by remember { mutableStateOf<List<MusicCatalogItem>>(emptyList()) }
    var rankingsLoaded by remember { mutableStateOf(false) }
    var rankingsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            rankings = parseRankings(controller.fetchText(neteaseTopListCall(controller.musicU)))
        } catch (_: Throwable) {
            rankingsFailed = true
        }
        rankingsLoaded = true
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SectionTitle("最近播放") }
        if (recents.isEmpty()) item { EmptyHint("还没有播放记录，先去搜索或发现页找首歌。") }
        else items(recents, key = { it.id }) { SongRow(it, controller, showLike = true, onDetail = onDetail) }
        item { SectionTitle("网易云榜单") }
        if (!rankingsLoaded) item { ProgressRow() }
        else if (rankings.isEmpty()) item { EmptyHint(if (rankingsFailed) "榜单加载失败，请稍后再试。" else "暂时没有榜单数据。") }
        else items(rankings, key = { it.id }) { item ->
            CatalogRow(item, onClick = { onDetail(MusicDetail.TopList(item.id)) })
        }
        item { SectionTitle("搜索快捷入口") }
        items(listOf("热门", "新歌", "经典")) { keyword ->
            Surface(
                onClick = { onSearch(keyword) },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(30.dp)) { drawCircle(Color(0xFFE8666A), size.minDimension / 2f) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(keyword + "搜索", style = MaterialTheme.typography.bodyMedium)
                        Text("匿名搜索并自动排队", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        item { Text(controller.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DiscoverTab(controller: MusicController, onSearch: (String) -> Unit, onDetail: (MusicDetail) -> Unit) {
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf<List<MusicCatalogItem>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(true) }
    var loadingPlaylists by remember { mutableStateOf(false) }
    var catalogFailed by remember { mutableStateOf(false) }
    var playlistsFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            categories = parseCatalogItems(controller.fetchText(neteasePlaylistCatalogueCall(controller.musicU)), "sub")
                .map { it.name }
                .filter { it.isNotBlank() }
                .distinct()
                .take(24)
        } catch (_: Throwable) {
            catalogFailed = true
        }
        loadingCatalog = false
    }
    LaunchedEffect(selectedCategory) {
        if (selectedCategory.isBlank()) return@LaunchedEffect
        loadingPlaylists = true
        playlistsFailed = false
        try {
            playlists = parseCatalogItems(
                controller.fetchText(neteasePlaylistByCategoryCall(selectedCategory, musicU = controller.musicU)),
                "playlists",
            ).filter { it.id > 0L }
        } catch (_: Throwable) {
            playlistsFailed = true
        }
        loadingPlaylists = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SearchField(controller.query, { controller.query = it }, "发现：搜歌名或歌手", { onSearch(controller.query) })
        if (loadingCatalog) ProgressRow()
        else if (catalogFailed || categories.isEmpty()) Text("分类加载失败；仍可用搜索。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        else LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { chip ->
                FilterChip(selected = selectedCategory == chip, onClick = { selectedCategory = chip }, label = { Text(chip) })
            }
        }
        if (loadingPlaylists) ProgressRow()
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedCategory.isNotBlank()) {
                items(playlists, key = { it.id }) { item ->
                    CatalogRow(item, onClick = { onDetail(MusicDetail.Playlist(item.id)) })
                }
                if (!loadingPlaylists && playlists.isEmpty()) item { EmptyHint(if (playlistsFailed) "该分类歌单加载失败。" else "该分类暂时没有歌单。") }
            } else {
                items(controller.songs, key = { it.id }) { SongRow(it, controller, showLike = true, onDetail = onDetail) }
                if (controller.songs.isEmpty() && !controller.searching) item { EmptyHint("选择分类看真实歌单，或搜索歌曲。") }
            }
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
private fun LibraryTab(controller: MusicController, onDetail: (MusicDetail) -> Unit) {
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
            items(songs, key = { it.id }) { SongRow(it, controller, showLike = true, onDetail = onDetail) }
            if (songs.isEmpty()) item { EmptyHint("这一格还是空的。最近听的歌、收藏、队列会出现在这里。") }
        }
    }
}

@Composable
private fun SearchTab(controller: MusicController, onSearch: () -> Unit, onDetail: (MusicDetail) -> Unit) {
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
            items(controller.songs, key = { it.id }) { SongRow(it, controller, showLike = true, onDetail = onDetail) }
            if (controller.songs.isEmpty() && !controller.searching) item { EmptyHint("搜一下想听的歌。匿名也能搜能放。") }
        }
    }
}

@Composable
private fun ShellPage(title: String, message: String) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
        }
    }
}

@Composable
private fun HistoryTab(controller: MusicController, onDetail: (MusicDetail) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SectionTitle("最近播放") }
        items(controller.history, key = { it.id }) { SongRow(it, controller, showLike = true, onDetail = onDetail) }
        if (controller.history.isEmpty()) item { EmptyHint("还没有播放历史。") }
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
        SectionTitle("页面与切换")
        SettingRow("页面顺序") {
            TextButton(onClick = { controller.setTabOrder(controller.tabOrder.reversed()) }) { Text("反转顺序") }
        }
        Text("下载 / 云盘 / 历史", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("下载", "云盘", "历史").forEach { label ->
            FilterChip(
                selected = controller.tabVisibility[label] == true,
                onClick = { controller.setTabVisible(label, controller.tabVisibility[label] != true) },
                label = { Text(label) },
            )
        }
        SectionTitle("定时关闭")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0, 15, 30, 60, 120).forEach { minutes ->
                FilterChip(selected = controller.sleepMinutes == minutes, onClick = { controller.setSleepMinutes(minutes) }, label = { Text(if (minutes == 0) "关闭" else minutes.toString() + "分") })
            }
        }
        SectionTitle("歌词外观")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号 " + controller.lyricFontSize, modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.setLyricFontSize(controller.lyricFontSize - 2) }) { Text("减小") }
            TextButton(onClick = { controller.setLyricFontSize(controller.lyricFontSize + 2) }) { Text("增大") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主色", modifier = Modifier.weight(1f))
            listOf(0xFFE8666A, 0xFF64B5F6, 0xFFFFD54F).forEach { color ->
                val hex = "#" + color.toString(16).padStart(6, '0').uppercase()
                FilterChip(selected = controller.lyricColor.equals(hex, ignoreCase = true), onClick = { controller.setLyricColor(hex) }, label = { Text("色") })
            }
        }
        SettingRow("翻译歌词") {
            FilterChip(selected = controller.translateLyrics, onClick = { controller.setTranslateLyrics(!controller.translateLyrics) }, label = { Text(if (controller.translateLyrics) "开" else "关") })
        }
        Text("翻译开关已开启时只尝试解析接口返回的译文；匿名接口可能不提供译文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle("均衡器")
        Text("均衡器：原生 DSP 不在当前 iOS 工程能力内；这里只保留设备音效说明，不假装有可调音效。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun CatalogRow(item: MusicCatalogItem, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(item.cover, Modifier.size(46.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.count > 0) item.count.toString() + " 首/项" else "点击查看内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun ProgressRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MusicDetailSheet(target: MusicDetail, controller: MusicController, onClose: () -> Unit) {
    var state by remember(target) { mutableStateOf(DetailUiState()) }
    var loading by remember(target) { mutableStateOf(true) }
    var failed by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) {
        loading = true
        failed = false
        try {
            val text = controller.fetchText(
                when (target) {
                    is MusicDetail.Playlist -> neteasePlaylistDetailCall(target.id, controller.musicU)
                    is MusicDetail.TopList -> neteaseTopListByIdCall(target.id, controller.musicU)
                    is MusicDetail.Album -> neteaseAlbumCall(target.id, controller.musicU)
                    is MusicDetail.Artist -> neteaseArtistCall(target.id, controller.musicU)
                },
            )
            state = when (target) {
                is MusicDetail.Playlist -> {
                    val detail = parsePlaylistDetail(text)
                    DetailUiState(
                        title = detail?.name ?: "歌单详情",
                        subtitle = if (detail == null) "" else "播放 " + detail.playCount,
                        description = detail?.description.orEmpty(),
                        cover = detail?.cover.orEmpty(),
                        songs = detail?.songs.orEmpty(),
                    )
                }
                is MusicDetail.TopList -> DetailUiState(
                    title = "榜单详情",
                    songs = parseSongArray(text),
                    topList = true,
                )
                is MusicDetail.Album -> {
                    val detail = parseAlbumDetail(text)
                    DetailUiState(
                        title = detail?.name ?: "专辑详情",
                        subtitle = detail?.artist.orEmpty(),
                        description = detail?.description.orEmpty(),
                        cover = detail?.cover.orEmpty(),
                        songs = detail?.songs.orEmpty(),
                    )
                }
                is MusicDetail.Artist -> {
                    val detail = parseArtistDetail(text)
                    val songs = parseSongArray(text)
                    val albums = runCatching {
                        parseArtistAlbums(controller.fetchText(neteaseArtistAlbumsCall(target.id, musicU = controller.musicU)))
                    }.getOrDefault(emptyList())
                    DetailUiState(
                        title = detail?.name ?: "歌手详情",
                        subtitle = "热门歌曲与专辑",
                        description = detail?.description.orEmpty(),
                        cover = detail?.cover.orEmpty(),
                        songs = songs,
                        related = albums,
                        relatedKind = "专辑",
                    )
                }
            }
            failed = when (target) {
                is MusicDetail.Playlist -> parsePlaylistDetail(text) == null
                is MusicDetail.TopList -> parseSongArray(text).isEmpty()
                is MusicDetail.Album -> parseAlbumDetail(text) == null
                is MusicDetail.Artist -> parseArtistDetail(text) == null
            }
        } catch (_: Throwable) {
            failed = true
        }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.title.isBlank()) "详情" else state.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }
            when {
                loading -> ProgressRow()
                failed -> EmptyHint("详情加载失败，请稍后再试。")
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.cover.isNotBlank()) {
                            AlbumArt(state.cover, Modifier.size(64.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            if (state.subtitle.isNotBlank()) Text(state.subtitle, style = MaterialTheme.typography.bodyMedium)
                            Text(if (state.songs.isEmpty() && state.related.isEmpty()) "暂无内容" else "歌曲 " + state.songs.size + " 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.topList) Text("榜单曲目来自排行榜接口", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    if (state.related.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SectionTitle(state.relatedKind.ifEmpty { "相关" })
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.related) { related ->
                                Surface(onClick = { /* 当前页已展示真实专辑条目 */ }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Column(Modifier.width(120.dp).padding(8.dp)) {
                                        AlbumArt(related.cover, Modifier.size(104.dp))
                                        Text(related.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.songs, key = { it.id }) { song ->
                            SongRow(song, controller, showLike = true, onDetail = { _ -> /* 详情页内不再叠加弹层 */ })
                        }
                        if (state.songs.isEmpty()) item { EmptyHint("还没解析到歌曲。") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: MusicSong, controller: MusicController, showLike: Boolean, onDetail: (MusicDetail) -> Unit) {
    val selected = controller.current?.id == song.id
    var actionsOpen by remember { mutableStateOf(false) }
    var addTarget by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().clickable { controller.playFromUi(song) }
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(song.cover, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onDetail(MusicDetail.Album(song.albumId)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.artist.ifEmpty { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { if (song.artistId > 0L) onDetail(MusicDetail.Artist(song.artistId)) },
                )
                if (song.album.isNotEmpty()) {
                    Text(" · ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        song.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { if (song.albumId > 0L) onDetail(MusicDetail.Album(song.albumId)) },
                    )
                }
            }
        }
        FilledIconButton(onClick = { controller.playFromUi(song) }, modifier = Modifier.size(32.dp)) {
            TransportIcon(Transport.Play, MaterialTheme.colorScheme.onPrimary, Modifier.size(16.dp))
        }
        TextButton(onClick = { actionsOpen = !actionsOpen }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("···") }
    }
    if (actionsOpen) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = { controller.addToQueue(song, playNext = true); actionsOpen = false }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("下一首", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { controller.addToQueue(song); actionsOpen = false }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("入队", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { addTarget = controller.customPlaylists.keys.firstOrNull().orEmpty() }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("存歌单", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { controller.toggleLike(song); actionsOpen = false }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (controller.likedIds.contains(song.id)) "取消喜欢" else "喜欢", style = MaterialTheme.typography.labelSmall) }
            Text("下载壳", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Text("下载需要登录态与签名接口，这里只保留入口，不提供假下载。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    addTarget?.let { name ->
        var draft by remember { mutableStateOf(name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { addTarget = null },
            title = { Text("加入本地歌单") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, placeholder = { Text("歌单名称") })
                if (controller.customPlaylists.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(controller.customPlaylists.keys.toList()) { existing ->
                            FilterChip(selected = draft == existing, onClick = { draft = existing }, label = { Text(existing) })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isBlank()) controller.createPlaylist("我喜欢的歌")
                    val finalName = draft.ifBlank { "我喜欢的歌" }
                    if (!controller.customPlaylists.containsKey(finalName)) controller.createPlaylist(finalName)
                    controller.addSongToPlaylist(finalName, song)
                    addTarget = null
                    actionsOpen = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { addTarget = null }) { Text("取消") } },
        )
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
            val activeColor = parseLyricColor(controller.lyricColor)
            Text(
                text = if (active) karaokeLine(line, controller.position, activeColor) else AnnotatedString(line.text),
                style = if (active) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = controller.lyricFontSize.sp) else MaterialTheme.typography.bodyLarge.copy(fontSize = (controller.lyricFontSize - 2).sp),
                color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { controller.seekTo(line.timeMs / 1000.0) },
            )
        }
    }
}

@Composable
private fun karaokeLine(line: LyricLine, positionSeconds: Double, activeColor: Color): AnnotatedString {
    val primary = activeColor
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
