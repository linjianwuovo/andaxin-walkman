package com.andaxin.feature.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun PodcastCatalogTab(controller: MusicController, onDetail: (MusicDetail) -> Unit) {
    var categories by remember { mutableStateOf<List<MusicCatalogItem>>(emptyList()) }
    var category by remember { mutableStateOf(0L) }
    var radios by remember { mutableStateOf<List<MusicCatalogItem>>(emptyList()) }
    var selected by remember { mutableStateOf<MusicCatalogItem?>(null) }
    var programs by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(controller.musicU) {
        try { categories = parseCatalogItems(controller.fetchText(neteasePodcastCategoryCall(controller.musicU)), "categories") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { categories = emptyList() }
    }
    LaunchedEffect(controller.musicU, category, selected?.id, offset, retry) {
        loading = true
        error = null
        try {
            val radio = selected
            if (radio == null) radios = parsePodcastRadios(controller.fetchText(neteasePodcastRadiosCall(category, controller.musicU)))
            else {
                val page = parsePodcastPrograms(controller.fetchText(neteasePodcastProgramsCall(radio.id, offset = offset, musicU = controller.musicU)))
                programs = (if (offset == 0) page.songs else programs + page.songs).distinctBy { it.id }
                more = page.more
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { error = failure.message ?: "播客暂时无法加载" }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (selected == null) SectionTitle("播客")
            else {
                TextButton(onClick = { selected = null; offset = 0; programs = emptyList() }) { Text("‹ 返回播客列表") }
                SectionTitle(selected?.name.orEmpty())
            }
        }
        if (selected == null) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = category == 0L, onClick = { category = 0L }, label = { Text("推荐") }) }
                    items(categories, key = { it.id }) { entry ->
                        FilterChip(selected = category == entry.id, onClick = { category = entry.id }, label = { Text(entry.name) })
                    }
                }
            }
            items(radios, key = { it.id }) { radio -> CatalogRow(radio) { selected = radio; offset = 0; programs = emptyList() } }
        } else items(programs, key = { it.id }) { song -> SongRow(song, controller, showLike = true, onDetail = onDetail) }
        if (loading) item { ProgressRow() }
        error?.let { reason -> item { MusicLoadError(reason) { retry++ } } }
        if (!loading && error == null && (if (selected == null) radios.isEmpty() else programs.isEmpty())) item { EmptyHint("这里暂时没有内容") }
        if (!loading && error == null && selected != null && more) item {
            TextButton(onClick = { offset += 30 }) { Text("加载更多节目") }
        }
    }
}

@Composable
internal fun CloudMusicTab(controller: MusicController, onLogin: () -> Unit, onDetail: (MusicDetail) -> Unit) {
    val token = controller.musicU
    if (token.isNullOrBlank()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SectionTitle("网易云云盘")
            Text("登录后查看和播放你上传到网易云的歌曲。")
            Button(onClick = onLogin) { Text("登录网易云") }
        }
        return
    }
    var songs by remember(token) { mutableStateOf<List<MusicSong>>(emptyList()) }
    var offset by remember(token) { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(token, offset, retry) {
        loading = true
        error = null
        try {
            val page = parseCloudSongs(controller.fetchText(neteaseCloudSongsCall(token, offset = offset)))
            songs = (if (offset == 0) page.songs else songs + page.songs).distinctBy { it.id }
            more = page.more
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { error = failure.message ?: "云盘暂时无法加载" }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("网易云云盘") }
        items(songs, key = { it.id }) { song -> SongRow(song, controller, showLike = true, onDetail = onDetail) }
        if (loading) item { ProgressRow() }
        error?.let { reason -> item {
            MusicLoadError(reason) { retry++ }
            TextButton(onClick = onLogin) { Text("重新登录网易云") }
        } }
        if (!loading && error == null && songs.isEmpty()) item { EmptyHint("云盘中还没有歌曲") }
        if (!loading && error == null && more) item { TextButton(onClick = { offset += 50 }) { Text("加载更多歌曲") } }
    }
}

@Composable
internal fun DownloadedMusicTab(controller: MusicController, onDetail: (MusicDetail) -> Unit) {
    val downloaded = IosMusicDownloads.songs
    val active = IosMusicDownloads.progress.toMap()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("下载")
            Text("已下载的歌曲可离线播放。可从歌曲的“···”菜单开始下载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IosMusicDownloads.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
        items(active.keys.toList(), key = { "active-$it" }) { id ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("正在下载 · ${((active[id] ?: 0f) * 100).toInt()}%", modifier = Modifier.weight(1f))
                TextButton(onClick = { IosMusicDownloads.cancel(id) }) { Text("取消") }
            }
        }
        items(downloaded, key = { "saved-${it.id}" }) { song ->
            SongRow(song, controller, showLike = true, onDetail = onDetail)
            TextButton(onClick = { IosMusicDownloads.remove(song.id) }) { Text("删除本地文件") }
        }
        if (active.isEmpty() && downloaded.isEmpty()) item { EmptyHint("还没有下载的歌曲") }
    }
}

@Composable
internal fun LocalMusicLibraryTab(controller: MusicController, onDetail: (MusicDetail) -> Unit) {
    var section by remember { mutableStateOf("最近播放") }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf<List<MusicSong>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val ids = if (section == "我喜欢的") controller.likedIds.toList() else selectedPlaylist?.let { controller.customPlaylists[it] }.orEmpty()
    LaunchedEffect(ids, section, selectedPlaylist, retry) {
        if (section != "我喜欢的" && selectedPlaylist == null) return@LaunchedEffect
        loading = true
        error = null
        try { resolved = controller.loadLibrarySongs(ids) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { error = failure.message ?: "歌曲暂时无法加载" }
        loading = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("最近播放", "我喜欢的", "播放队列", "本地歌单")) { label ->
                    FilterChip(selected = section == label, onClick = { section = label; selectedPlaylist = null }, label = { Text(label) })
                }
            }
        }
        if (section == "本地歌单" && selectedPlaylist == null) {
            item {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("新歌单名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(enabled = draft.isNotBlank(), onClick = { controller.createPlaylist(draft); draft = "" }) { Text("创建歌单") }
            }
            items(controller.customPlaylists.keys.toList(), key = { it }) { name ->
                Surface(onClick = { selectedPlaylist = name }, modifier = Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
                    Column(Modifier.padding(14.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text("${controller.customPlaylists[name].orEmpty().size} 首", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            if (selectedPlaylist != null) item {
                TextButton(onClick = { selectedPlaylist = null }) { Text("‹ 本地歌单") }
                SectionTitle(selectedPlaylist.orEmpty())
            }
            val list = when (section) {
                "最近播放" -> controller.history
                "播放队列" -> controller.queue
                else -> resolved
            }
            items(list, key = { it.id }) { song ->
                SongRow(song, controller, showLike = true, onDetail = onDetail)
                selectedPlaylist?.let { name -> TextButton(onClick = { controller.removeSongFromPlaylist(name, song.id) }) { Text("移出歌单") } }
            }
            if (loading) item { ProgressRow() }
            error?.let { reason -> item { MusicLoadError(reason) { retry++ } } }
            if (!loading && error == null && list.isEmpty()) item { EmptyHint("这里还没有歌曲") }
        }
    }
}

@Composable
private fun MusicLoadError(reason: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(reason, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text("重试") }
    }
}
