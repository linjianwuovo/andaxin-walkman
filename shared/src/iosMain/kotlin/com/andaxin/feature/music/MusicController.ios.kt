package com.andaxin.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andaxin.core.net.HttpEngine
import com.andaxin.core.net.HttpEngineConfig
import com.andaxin.core.net.createHttpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.UIKit.UIImage
import kotlin.coroutines.resume

private const val MODE_LIST = 0
private const val MODE_ONE = 1
private const val MODE_SHUFFLE = 2
private const val MODE_REPEAT_ALL = 3

/*
 * 音乐 tab 的全部状态和网络调用都收在这里，composable 只负责画。
 * 协议层只有匿名链路：搜索 / 取直链 / 歌词；登录态只影响请求头里的 MUSIC_U。
 * 本地收藏、最近播放、搜索历史、播放模式与音质全部落在 NSUserDefaults。
 */
class MusicController(
    private val engine: HttpEngine = createHttpEngine(HttpEngineConfig()),
) {
    var query by mutableStateOf("")
    var songs by mutableStateOf<List<MusicSong>>(emptyList())
    var searching by mutableStateOf(false)
    var current by mutableStateOf<MusicSong?>(null)
    var playing by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var queue by mutableStateOf<List<MusicSong>>(emptyList())
        private set
    var likedIds by mutableStateOf<Set<Long>>(emptySet())
        private set
    var playMode by mutableStateOf(MusicStore.loadPlayMode())
        private set
    var bitrate by mutableStateOf(MusicStore.loadBitrate())
        private set
    var lyricsVisible by mutableStateOf(MusicStore.loadLyricsVisible())
        private set
    var sleepMinutes by mutableStateOf(MusicStore.loadSleepMinutes())
        private set
    var sleepRemaining by mutableStateOf(0)
        private set
    var lyricFontSize by mutableStateOf(MusicStore.loadLyricFontSize())
        private set
    var lyricColor by mutableStateOf(MusicStore.loadLyricColor())
        private set
    var translateLyrics by mutableStateOf(MusicStore.loadTranslateLyrics())
        private set
    var tabOrder by mutableStateOf(MusicStore.loadTabOrder())
        private set
    var tabVisibility by mutableStateOf(MusicStore.loadTabVisibility())
        private set
    var customPlaylists by mutableStateOf(MusicStore.loadCustomPlaylists())
        private set
    var history by mutableStateOf<List<MusicSong>>(emptyList())
        private set
    var position by mutableStateOf(0.0)
    var duration by mutableStateOf(0.0)
    var lyrics by mutableStateOf<List<LyricLine>>(emptyList())
        private set
    var lyricIndex by mutableStateOf(-1)
        private set
    var musicU by mutableStateOf(MusicStore.loadMusicU())
        private set
    var scrubbing = false

    private var index = -1
    private var loaded = false
    private var endHandled = false
    private var lastSavedPosition = -1.0
    private var resumeTarget = -1.0
    private var resumeTicks = 0

    private val player = MusicPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val nowPlaying = NowPlaying(
        onPlay = { resumeFromRemote() },
        onPause = { pauseFromRemote() },
        onNext = { next() },
        onPrevious = { previous() },
        onSeek = { seconds -> seekTo(seconds) },
    )

    init {
        restore()
        history = MusicStore.loadRecent()
        likedIds = MusicStore.loadLikedIds()
        scope.launch { ticker() }
    }

    fun queueIndexOf(song: MusicSong): Int = queue.indexOfFirst { it.id == song.id }

    fun setMusicU(value: String?) {
        musicU = value
        MusicStore.saveMusicU(value)
    }

    fun toggleLike(song: MusicSong) {
        likedIds = if (likedIds.contains(song.id)) likedIds - song.id else likedIds + song.id
        MusicStore.saveLikedIds(likedIds)
    }

    fun setPlayMode(value: Int) {
        playMode = if (value in MODE_LIST..MODE_REPEAT_ALL) value else MODE_LIST
        MusicStore.savePlayMode(playMode)
    }

    fun loadSelectedTabLabel(): String = store.loadSelectedTab().ifEmpty { "首页" }

    fun setSelectedTab(label: String) {
        store.saveSelectedTab(label)
    }

    suspend fun fetchText(call: HttpCall): String = engine.execute(call).bodyText

    fun cyclePlayMode() = setPlayMode((playMode + 1) % 4)

    fun setBitrate(value: Int) {
        bitrate = if (value == 320000) 320000 else 128000
        MusicStore.saveBitrate(bitrate)
    }

    fun setLyricsVisible(value: Boolean) {
        lyricsVisible = value
        MusicStore.saveLyricsVisible(value)
    }

    fun reloadLyrics() {
        val song = current ?: return
        scope.launch { loadLyrics(song) }
    }

    fun playQueueAt(index: Int) {
        if (queue.indices.contains(index)) scope.launch { playAt(index) }
    }

    fun addToQueue(song: MusicSong, playNext: Boolean = false) {
        if (queue.any { it.id == song.id }) {
            message = "歌曲已在队列中"
            return
        }
        val nextIndex = if (index >= 0) index + 1 else queue.size
        queue = if (playNext && nextIndex <= queue.size) {
            queue.toMutableList().also { it.add(nextIndex, song) }
        } else queue + song
        MusicStore.saveQueue(queue, index.coerceAtLeast(0))
        message = if (playNext) "已插播到下一首" else "已加入播放队列"
    }

    fun removeFromQueue(songId: Long) {
        val target = queue.indexOfFirst { it.id == songId }
        if (target < 0) return
        val mutable = queue.toMutableList().also { it.removeAt(target) }
        val newIndex = when {
            mutable.isEmpty() -> -1
            target < index -> index - 1
            target == index -> index.coerceAtMost(mutable.lastIndex)
            else -> index
        }
        queue = mutable
        index = newIndex
        if (newIndex >= 0) current = mutable[newIndex]
        MusicStore.saveQueue(queue, index.coerceAtLeast(0))
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val mutable = queue.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        queue = mutable
        index = when (index) {
            from -> to
            in (from + 1)..to -> index - 1
            in to until from -> index + 1
            else -> index
        }
        MusicStore.saveQueue(queue, index.coerceAtLeast(0))
    }

    fun setSleepMinutes(value: Int) {
        sleepMinutes = value.coerceIn(0, 240)
        sleepRemaining = sleepMinutes * 60
        MusicStore.saveSleepMinutes(sleepMinutes)
        message = if (sleepMinutes == 0) "已关闭定时关闭" else "已设置 $sleepMinutes 分钟后停止"
    }

    fun setLyricFontSize(value: Int) {
        lyricFontSize = value.coerceIn(14, 32)
        MusicStore.saveLyricFontSize(lyricFontSize)
    }

    fun setLyricColor(value: String) {
        lyricColor = value
        MusicStore.saveLyricColor(value)
    }

    fun setTranslateLyrics(value: Boolean) {
        translateLyrics = value
        MusicStore.saveTranslateLyrics(value)
    }

    fun setTabOrder(order: List<String>) {
        tabOrder = (order + tabOrder.filterNot { it in order }).distinct()
        MusicStore.saveTabOrder(tabOrder)
    }

    fun setTabVisible(label: String, visible: Boolean) {
        tabVisibility = tabVisibility + (label to visible)
        MusicStore.saveTabVisibility(tabVisibility)
    }

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        customPlaylists = customPlaylists + (trimmed to emptyList())
        MusicStore.saveCustomPlaylists(customPlaylists)
        message = "已创建本地歌单：$trimmed"
    }

    fun addSongToPlaylist(name: String, song: MusicSong) {
        val ids = (customPlaylists[name].orEmpty() + song.id).distinct()
        customPlaylists = customPlaylists + (name to ids)
        MusicStore.saveCustomPlaylists(customPlaylists)
        message = "已加入本地歌单：$name"
    }

    fun playModeLabel(): String = when (playMode) {
        MODE_ONE -> "单曲循环"
        MODE_SHUFFLE -> "随机播放"
        MODE_REPEAT_ALL -> "全部循环"
        else -> "列表循环"
    }
    fun clearSearchHistory() = MusicStore.saveSearchHistory(emptyList())

    fun searchHistory(): List<String> = MusicStore.loadSearchHistory()

    fun playFromUi(song: MusicSong) {
        scope.launch { play(song) }
    }

    fun clearLikes() {
        likedIds = emptySet()
        MusicStore.saveLikedIds(emptySet())
    }

    /** 从当前列表里把某首歌挑出来放；缺歌时不往队列里塞占位数据。 */
    suspend fun play(song: MusicSong) {
        val list = queue.ifEmpty { songs.ifEmpty { listOf(song) } }
        val found = list.indexOfFirst { it.id == song.id }
        queue = list
        playAt(if (found >= 0) found else 0)
        val recent = listOf(song) + history.filterNot { it.id == song.id }
        history = recent.take(30)
        MusicStore.saveRecent(history)
    }

    /** 首页/发现点推荐卡片：搜到什么就把结果装进队列，供播放器“下一首”走。 */
    suspend fun searchAsPlaylist(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty() || searching) return
        query = trimmed
        searchFor(trimmed)
        if (songs.isNotEmpty()) {
            queue = songs
            message = "「$trimmed」已放入播放队列"
        }
    }

    suspend fun search() = searchFor(query)

    /** 搜索页专用：先把关键词写进历史，再发真正的匿名请求。 */
    suspend fun searchRemembered() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        val trimmedHistory = listOf(q) + MusicStore.loadSearchHistory().filterNot { it == q }
        MusicStore.saveSearchHistory(trimmedHistory)
        searchFor(q)
    }

    private suspend fun searchFor(keyword: String) {
        val q = keyword.trim()
        if (q.isEmpty() || searching) return
        searching = true
        message = null
        try {
            val reply = engine.execute(neteaseSearchCall(q, musicU = musicU))
            val found = parseSearchSongs(reply.bodyText)
            songs = found
            if (found.isEmpty()) message = if (reply.isSuccessful) "没搜到。" else "搜索返回 " + reply.status
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            message = "搜索失败：" + (e.message ?: "网络不通")
        } finally {
            searching = false
        }
    }

    fun toggle() {
        val song = current ?: return
        if (!loaded) {
            scope.launch { playAt(index, startAt = position) }
            return
        }
        if (playing) {
            player.pause()
            playing = false
            lastSavedPosition = position
            MusicStore.savePosition(position)
        } else {
            player.resume()
            playing = true
        }
        MusicStore.savePlaying(playing)
        refreshNowPlaying()
    }

    fun next() {
        if (queue.isEmpty()) return
        if (playMode != MODE_REPEAT_ALL && playMode != MODE_SHUFFLE && index >= queue.lastIndex) {
            message = "已播放到队列末尾"
            return
        }
        val target = when (playMode) {
            MODE_ONE -> index
            MODE_SHUFFLE -> if (queue.size <= 1) index else index + 1 + (1 until queue.size).random()
            MODE_REPEAT_ALL -> (index + 1) % queue.size
            else -> index + 1
        }
        scope.launch { playAt(target) }
    }

    fun previous() {
        if (queue.isEmpty()) return
        val target = if (playMode == MODE_SHUFFLE && queue.size > 1) {
            index - 1 - (1 until queue.size).random()
        } else index - 1
        scope.launch { playAt(target) }
    }

    fun seekTo(seconds: Double) {
        val ceiling = if (duration > 0.0) duration else seconds
        val target = seconds.coerceIn(0.0, ceiling)
        player.seek(target)
        position = target
        lastSavedPosition = target
        MusicStore.savePosition(target)
        refreshNowPlaying()
    }

    private suspend fun playAt(target: Int, startAt: Double = 0.0) {
        val list = queue
        if (list.isEmpty()) return
        val i = ((target % list.size) + list.size) % list.size
        val song = list[i]
        message = null
        lyrics = emptyList()
        lyricIndex = -1
        val url = resolve(song, bitrate)
        player.play(url, startAt = startAt)
        current = song
        scope.launch { loadLyrics(song) }
        index = i
        loaded = true
        playing = true
        if (startAt > 0.5) {
            position = startAt
            resumeTarget = startAt
            resumeTicks = 20
        } else {
            position = 0.0
            resumeTarget = -1.0
            resumeTicks = 0
        }
        duration = 0.0
        endHandled = false
        lastSavedPosition = position
        MusicStore.saveQueue(list, i)
        MusicStore.savePosition(position)
        MusicStore.savePlaying(true)
        refreshNowPlaying()
        loadCover(song)
    }

    private suspend fun loadLyrics(song: MusicSong) {
        val reply = try {
            engine.execute(neteaseLyricCall(song.id, musicU = musicU))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return
        }
        if (!reply.isSuccessful) return
        val parsed = parseLyrics(reply.bodyText)
        if (current?.id == song.id) {
            lyrics = parsed
            lyricIndex = -1
        }
    }

    private suspend fun resolve(song: MusicSong, br: Int): String {
        val direct = try {
            parseSongUrl(engine.execute(neteaseSongUrlCall(song.id, br = br, musicU = musicU)).bodyText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        return direct ?: neteaseOuterUrl(song.id)
    }

    private fun restore() {
        val saved = MusicStore.loadQueue()
        if (saved.isEmpty()) return
        val savedIndex = MusicStore.loadIndex().coerceIn(0, saved.lastIndex)
        queue = saved
        index = savedIndex
        current = saved[savedIndex]
        position = MusicStore.loadPosition()
        playing = false
        refreshNowPlaying()
    }

    private suspend fun ticker() {
        while (scope.isActive) {
            delay(500)
            if (!scrubbing) {
                val pos = player.positionSeconds()
                if (resumeTarget >= 0.0 && resumeTicks > 0) {
                    resumeTicks--
                    if (pos >= resumeTarget - 1.5) {
                        resumeTarget = -1.0
                        position = pos
                    }
                } else {
                    resumeTarget = -1.0
                    if (loaded || pos > 0.0) position = pos
                }
            }
            val dur = player.durationSeconds()
            if (dur > 0.5 && dur != duration) duration = dur
            if (sleepMinutes > 0 && playing) {
                sleepRemaining = (sleepRemaining - 1).coerceAtLeast(0)
                if (sleepRemaining == 0) {
                    pauseFromRemote()
                    sleepMinutes = 0
                    sleepRemaining = 0
                    MusicStore.saveSleepMinutes(0)
                    message = "定时关闭已生效"
                }
            }
            if (playing && duration > 0.0 && position >= duration - 0.4 && !endHandled) {
                endHandled = true
                // 单曲循环绕过 next()，确保自动续播也停在同一首。
                if (playMode == MODE_ONE) scope.launch { playAt(index) } else next()
            }
            if (position - lastSavedPosition >= 5.0) {
                lastSavedPosition = position
                MusicStore.savePosition(position)
            }
            val nowMs = (position * 1000.0).toLong()
            var active = -1
            for (i in lyrics.indices) {
                if (lyrics[i].timeMs <= nowMs) active = i else break
            }
            if (active != lyricIndex) lyricIndex = active
            if (playing) refreshNowPlaying()
        }
    }

    private fun refreshNowPlaying() {
        val song = current ?: return
        nowPlaying.update(
            title = song.name,
            artist = song.artist,
            duration = duration,
            elapsed = position,
            rate = if (playing) 1.0 else 0.0,
        )
    }

    private fun loadCover(song: MusicSong) {
        nowPlaying.setCover(null)
        val url = song.cover
        if (url.isEmpty()) return
        scope.launch {
            val data = fetchData(url)
            if (current?.cover != url) return@launch
            nowPlaying.setCover(data?.let { UIImage(data = it) })
            refreshNowPlaying()
        }
    }

    private suspend fun fetchData(url: String): NSData? = suspendCancellableCoroutine { c ->
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            c.resume(null)
            return@suspendCancellableCoroutine
        }
        val request = NSURLRequest.requestWithURL(nsUrl)
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, _: NSURLResponse?, err: NSError? ->
            c.resume(if (err == null) data else null)
        }
        task.resume()
    }

    private fun resumeFromRemote() {
        if (current == null) return
        if (!loaded) {
            scope.launch { playAt(index, startAt = position) }
            return
        }
        player.resume()
        playing = true
        MusicStore.savePlaying(true)
        refreshNowPlaying()
    }

    private fun pauseFromRemote() {
        if (!playing) return
        player.pause()
        playing = false
        lastSavedPosition = position
        MusicStore.savePosition(position)
        MusicStore.savePlaying(false)
        refreshNowPlaying()
    }
}