@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andaxin.shared.IosImages
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import platform.Foundation.NSUserDefaults

private enum class MusicTab(val label: String) {
    Home("首页"), Discover("发现"), Podcast("播客"), Library("音乐库"), Search("搜索"), Setting("设置"),
}

private enum class Transport { Play, Pause, Next, Prev, Mode, Queue, Heart }


private enum class LibrarySection(val label: String) { Recent("最近播放"), Liked("我喜欢的"), Queue("播放队列") }

internal sealed interface MusicDetail {
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
private fun effectiveLyricFontSize(controller: MusicController): Int {
    val storedSize = controller.lyricFontSize
    val hasStoredSize = remember(controller, storedSize) {
        NSUserDefaults.standardUserDefaults.objectForKey("music.lyricFont") != null
    }
    return if (hasStoredSize) storedSize else 28
}

private fun parseLyricColor(raw: String): Color {
    val value = raw.trim().removePrefix("#")
    return runCatching {
        when (value.length) {
            6 -> Color((0xFF000000u + value.toULong(16)).toLong())
            8 -> Color(value.toULong(16).toLong())
            else -> Color.White
        }
    }.getOrDefault(Color.White)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen() {
    val controller = remember { MusicController.shared }
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
                if (showLogin) {
                    controller.setMusicU(token)
                    controller.message = "已登录网易云（MUSIC_U 已保存）"
                    showLogin = false
                }
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
    // 当前 tab 被隐藏时不能静默留在第 0 个：把选中项一起挪到第一个可见页并写回。
    LaunchedEffect(visibleTabs) {
        if (visibleTabs.isNotEmpty() && selectedLabel !in visibleTabs) {
            selectedLabel = visibleTabs.first()
            controller.setSelectedTab(selectedLabel)
        }
    }
    val selectedIndex = visibleTabs.indexOf(selectedLabel).takeIf { it >= 0 } ?: 0
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
        "播客" -> PodcastCatalogTab(controller, onDetail)
        "音乐库" -> LocalMusicLibraryTab(controller, onDetail)
        "搜索" -> SearchTab(controller, onSearch = onSearchRemembered, onDetail = onDetail)
        "设置" -> SettingTab(controller, onLogin = onLogin)
        "下载" -> DownloadedMusicTab(controller, onDetail)
        "云盘" -> CloudMusicTab(controller, onLogin, onDetail)
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
        // 安卓会把云端 recentSongs 并进来；那条接口走 weapi 签名，iOS 侧没有等价实现，
        // 所以这里只有本机记录，并在界面上写明，不装作已经对齐。
        item {
            Text(
                "仅显示本机播放记录；云端的“最近播放”尚未接入这个版本。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(controller.history, key = { it.id }) { SongRow(it, controller, showLike = true, onDetail = onDetail) }
        if (controller.history.isEmpty()) item { EmptyHint("这台设备还没有播放记录。") }
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
    // controller.bitrate / lyricsVisible 本身就是 mutableStateOf，直接读就会重组；
    // 之前用 remember(version) + 手动 ++ 是多余的影子状态，外部一改就与界面脱同步。
    val bitrate = controller.bitrate
    val lyricsVisible = controller.lyricsVisible
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("播放与歌词")
        SettingRow("恢复播放进度") {
            Switch(checked = controller.restorePlaybackPosition, onCheckedChange = controller::setRestorePlaybackPosition, modifier = Modifier.testTag("music-restore-position"))
        }
        Text("下次启动时恢复上次位置；始终暂停，点击播放后继续。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingRow("播放音质") {
            FilterChip(selected = bitrate == 128000, onClick = { controller.setBitrate(128000) }, label = { Text("128k") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = bitrate == 320000, onClick = { controller.setBitrate(320000) }, label = { Text("320k") })
        }
        SettingRow("歌词显示") {
            FilterChip(selected = lyricsVisible, onClick = { controller.setLyricsVisible(true) }, label = { Text("开") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = !lyricsVisible, onClick = { controller.setLyricsVisible(false) }, label = { Text("关") })
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0, 15, 30, 60, 120).forEach { minutes ->
                FilterChip(selected = controller.sleepMinutes == minutes, onClick = { controller.setSleepMinutes(minutes) }, label = { Text(if (minutes == 0) "关闭" else minutes.toString() + "分") })
            }
        }
        SectionTitle("歌词外观")
        val lyricFontSize = effectiveLyricFontSize(controller)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号 " + lyricFontSize, modifier = Modifier.weight(1f))
            TextButton(onClick = { controller.setLyricFontSize(lyricFontSize - 2) }) { Text("减小") }
            TextButton(onClick = { controller.setLyricFontSize(lyricFontSize + 2) }) { Text("增大") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主色", modifier = Modifier.weight(1f))
            listOf(
                0xFFE8666A to "红",
                0xFF64B5F6 to "蓝",
                0xFFFFD54F to "黄",
            ).forEach { (color, label) ->
                val hex = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
                FilterChip(selected = controller.lyricColor.equals(hex, ignoreCase = true), onClick = { controller.setLyricColor(hex) }, label = { Text(label) })
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
        Text("支持网易云歌曲、播客、云盘、本地歌单与离线下载；受账号权限限制的内容需要登录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
}

@Composable
internal fun CatalogRow(item: MusicCatalogItem, onClick: () -> Unit) {
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
internal fun ProgressRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MusicDetailSheet(initialTarget: MusicDetail, controller: MusicController, onClose: () -> Unit) {
    var target by remember(initialTarget) { mutableStateOf(initialTarget) }
    var state by remember(target) { mutableStateOf(DetailUiState()) }
    var loading by remember(target) { mutableStateOf(true) }
    var failed by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) {
        val requested = target
        loading = true
        failed = false
        try {
            val text = controller.fetchText(
                when (requested) {
                    is MusicDetail.Playlist -> neteasePlaylistDetailCall(requested.id, controller.musicU)
                    is MusicDetail.TopList -> neteaseTopListByIdCall(requested.id, controller.musicU)
                    is MusicDetail.Album -> neteaseAlbumCall(requested.id, controller.musicU)
                    is MusicDetail.Artist -> neteaseArtistCall(requested.id, controller.musicU)
                },
            )
            state = when (requested) {
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
                        parseArtistAlbums(controller.fetchText(neteaseArtistAlbumsCall(requested.id, musicU = controller.musicU)))
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
            failed = when (requested) {
                is MusicDetail.Playlist -> parsePlaylistDetail(text) == null
                is MusicDetail.TopList -> parseSongArray(text).isEmpty()
                is MusicDetail.Album -> parseAlbumDetail(text) == null
                is MusicDetail.Artist -> parseArtistDetail(text) == null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
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
                                Surface(onClick = { target = MusicDetail.Album(related.id) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
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
                            SongRow(song, controller, showLike = true, onDetail = { target = it })
                        }
                        if (state.songs.isEmpty()) item { EmptyHint("还没解析到歌曲。") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SongRow(song: MusicSong, controller: MusicController, showLike: Boolean, onDetail: (MusicDetail) -> Unit) {
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
                modifier = if (song.albumId > 0L) Modifier.clickable { onDetail(MusicDetail.Album(song.albumId)) } else Modifier,
            )
            Column {
                Text(
                    song.artist.ifEmpty { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (song.artistId > 0L) Modifier.clickable { onDetail(MusicDetail.Artist(song.artistId)) } else Modifier,
                )
                if (song.album.isNotEmpty()) {
                    Text(
                        song.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (song.albumId > 0L) Modifier.clickable { onDetail(MusicDetail.Album(song.albumId)) } else Modifier,
                    )
                }
            }
        }
        FilledIconButton(onClick = { controller.playFromUi(song) }, modifier = Modifier.size(48.dp)) {
            TransportIcon(Transport.Play, MaterialTheme.colorScheme.onPrimary, Modifier.size(16.dp))
        }
        TextButton(onClick = { actionsOpen = !actionsOpen }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("···") }
    }
    if (actionsOpen) {
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { controller.addToQueue(song, playNext = true); actionsOpen = false }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("下一首", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { controller.addToQueue(song); actionsOpen = false }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("入队", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { addTarget = controller.customPlaylists.keys.firstOrNull().orEmpty() }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("存歌单", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { controller.toggleLike(song); actionsOpen = false }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (controller.likedIds.contains(song.id)) "取消喜欢" else "喜欢", style = MaterialTheme.typography.labelSmall) }
            TextButton(onClick = { controller.download(song); actionsOpen = false }, enabled = song.id !in controller.resolvingDownloads && song.id !in IosMusicDownloads.progress, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (IosMusicDownloads.localUrl(song.id) != null) "已下载" else "下载", style = MaterialTheme.typography.labelSmall) }
        }
    }
    addTarget?.let { name ->
        var draft by remember { mutableStateOf(name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { addTarget = null },
            title = { Text("加入本地歌单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true, placeholder = { Text("歌单名称") })
                if (controller.customPlaylists.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(controller.customPlaylists.keys.toList()) { existing ->
                            FilterChip(selected = draft == existing, onClick = { draft = existing }, label = { Text(existing) })
                        }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("music-mini-player").clickable { expanded() },
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
                modifier = Modifier.size(40.dp).testTag("music-mini-toggle"),
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
    val artwork = rememberAlbumBitmap(song.cover)
    val foreground = if (showLyrics) Color.White else MaterialTheme.colorScheme.onSurface
    val secondary = if (showLyrics) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    val actionColors = if (showLyrics) ButtonDefaults.textButtonColors(contentColor = foreground) else ButtonDefaults.textButtonColors()
    val playColor = if (showLyrics) Color(0xFF1A181C) else MaterialTheme.colorScheme.onPrimary
    val playBackground = if (showLyrics) Color.White else MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize()) {
        if (showLyrics) LyricsBackdrop(artwork, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack, colors = actionColors) { Text("← 收起") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showLyrics = !showLyrics; controller.setLyricsVisible(showLyrics) }, colors = actionColors) { Text(if (showLyrics) "歌词" else "封面") }
                TextButton(onClick = onQueue, colors = actionColors) { Text("队列") }
            }
            Spacer(Modifier.height(10.dp))
            if (showLyrics) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    AlbumArtImage(artwork, Modifier.size(48.dp), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodyMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val lines = controller.lyrics
                val lyricModifier = Modifier.weight(1f).fillMaxWidth()
                key(song.id, lines) { KaraokeLyricsView(controller, lines, lyricModifier) }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AlbumArtImage(artwork, Modifier.size(minOf(maxWidth, maxHeight)), RoundedCornerShape(20.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text(song.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist + if (song.album.isEmpty()) "" else " · " + song.album, style = MaterialTheme.typography.bodyMedium, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("点歌词跳转 · " + clock(controller.position), style = MaterialTheme.typography.labelSmall, color = secondary)
                Text(clock(controller.duration), style = MaterialTheme.typography.labelSmall, color = secondary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TransportIcon(Transport.Mode, foreground, Modifier.size(30.dp), onClick = { controller.cyclePlayMode() })
                IconButton(onClick = { controller.previous() }, modifier = Modifier.size(52.dp)) { TransportIcon(Transport.Prev, foreground, Modifier.size(26.dp)) }
                FilledIconButton(
                    onClick = { controller.toggle() },
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = playBackground, contentColor = playColor),
                ) { TransportIcon(if (controller.playing) Transport.Pause else Transport.Play, playColor, Modifier.size(30.dp)) }
                IconButton(onClick = { controller.next() }, modifier = Modifier.size(52.dp)) { TransportIcon(Transport.Next, foreground, Modifier.size(26.dp)) }
                TransportIcon(Transport.Queue, foreground, Modifier.size(30.dp), onClick = onQueue)
            }
            Text(when (controller.playMode) { 1 -> "单曲循环"; 2 -> "随机播放"; else -> "列表循环" }, style = MaterialTheme.typography.labelSmall, color = secondary, modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (controller.musicU == null) "未登录" else "已登录", style = MaterialTheme.typography.labelSmall, color = secondary, modifier = Modifier.weight(1f))
                TextButton(onClick = if (controller.musicU == null) onLogin else onLogout, colors = actionColors) { Text(if (controller.musicU == null) "登录" else "退出") }
            }
        }
    }
}

@Composable
private fun LyricsBackdrop(artwork: ImageBitmap?, modifier: Modifier) {
    Box(modifier.background(Color(0xFF242226))) {
        if (artwork != null) {
            Image(artwork, contentDescription = null, modifier = Modifier.matchParentSize().blur(36.dp), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
                Color.Black.copy(alpha = 0.72f),
                Color.Black.copy(alpha = 0.76f),
                Color.Black.copy(alpha = 0.87f),
            ))))
        }
    }
}

@Composable
private fun KaraokeLyricsView(controller: MusicController, lines: List<LyricLine>, modifier: Modifier) {
    if (lines.isEmpty()) {
        val hint = if (controller.current == null) "播放歌曲后这里会显示逐字歌词" else "暂未获取到歌词，可重试"
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center)
            TextButton(onClick = { controller.reloadLyrics() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("重试歌词") }
        }
        return
    }

    val activeIndex by remember(controller, lines) {
        derivedStateOf { activeLyricIndex(lines, (controller.position * 1000.0).toLong()) }
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex.coerceAtLeast(0))
    val dragging by state.interactionSource.collectIsDraggedAsState()
    var browsing by remember { mutableStateOf(false) }
    LaunchedEffect(dragging, browsing) {
        if (dragging) {
            browsing = true
        } else if (browsing) {
            snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
                if (!scrolling) {
                    delay(2_500L)
                    browsing = false
                }
            }
        }
    }
    LaunchedEffect(activeIndex, dragging, browsing) {
        if (!dragging && !browsing) state.animateScrollToItem(activeIndex.coerceAtLeast(0))
    }

    val color = remember(controller.lyricColor) { parseLyricColor(controller.lyricColor) }
    val fontSize = effectiveLyricFontSize(controller)
    val lyricStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.2f).sp,
        textDirection = TextDirection.Content,
    )
    BoxWithConstraints(modifier) {
        val anchor = minOf(72.dp, maxHeight * 0.26f)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().lyricEdgeFade(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight - anchor),
        ) {
            items(lines.size, key = { index -> index }) { index ->
                val line = lines[index]
                val active = index == activeIndex
                val distance = abs(index - activeIndex.coerceAtLeast(0))
                val inactiveAlpha = when (distance) { 0, 1 -> 0.72f; 2 -> 0.60f; else -> 0.52f }
                Text(
                    text = if (active) karaokeLine(line, controller.position, color) else AnnotatedString(line.text),
                    style = lyricStyle,
                    color = if (active) color else Color.White.copy(alpha = inactiveAlpha),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().clickable {
                        controller.seekTo(line.timeMs / 1000.0)
                        browsing = false
                    }.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun Modifier.lyricEdgeFade(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    val mask = Brush.verticalGradient(
        0f to Color.Transparent,
        0.14f to Color.Black,
        0.80f to Color.Black,
        1f to Color.Transparent,
    )
    onDrawWithContent {
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
    }
}

private fun activeLyricIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var left = 0
    var right = lines.size
    while (left < right) {
        val middle = left + (right - left) / 2
        if (lines[middle].timeMs <= positionMs) left = middle + 1 else right = middle
    }
    return left - 1
}

private fun karaokeLine(line: LyricLine, positionSeconds: Double, activeColor: Color): AnnotatedString {
    val dim = Color.White.copy(alpha = 0.62f)
    val nowMs = (positionSeconds * 1000.0).toLong() - line.timeMs
    return buildAnnotatedString {
        if (line.wordTimes.size == line.text.length && line.text.isNotEmpty()) {
            line.text.forEachIndexed { i, ch ->
                val color = if (nowMs >= line.wordTimes[i]) activeColor else dim
                withStyle(SpanStyle(color = color)) { append(ch) }
            }
        } else withStyle(SpanStyle(color = activeColor)) { append(line.text) }
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
private fun rememberAlbumBitmap(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bitmap = if (url.isNullOrBlank()) null else IosImages.load(url) }
    return bitmap
}

@Composable
private fun AlbumArt(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    AlbumArtImage(rememberAlbumBitmap(url), modifier, shape)
}

@Composable
private fun AlbumArtImage(bitmap: ImageBitmap?, modifier: Modifier, shape: Shape) {
    if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
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
