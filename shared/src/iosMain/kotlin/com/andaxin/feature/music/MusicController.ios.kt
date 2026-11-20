package com.andaxin.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andaxin.core.net.HttpCall
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
import kotlin.time.TimeSource

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
    companion object {
        // Playback belongs to the app, so navigating away must not stop it.
        val shared: MusicController by lazy { MusicController() }
    }

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
    var sleepRemaining by mutableStateOf(MusicStore.loadSleepMinutes() * 60)
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
    private var lastTickPosition = 0.0
    private var lastSavedPosition = -1.0
    private var resumeTarget = -1.0
    private var resumeTicks = 0
    private var searchRequest = 0L
    private var playbackRequest = 0L
    private var lyricRequest = 0L
    private var coverRequest = 0L
    private var sleepTick = TimeSource.Monotonic.markNow()
    private var sleepElapsed = 0.0

    private val player = MusicPlayer()
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main)
    private val nowPlaying = NowPlaying(
        onPlay = { resumeFromRemote() },
        onPause = { pauseFromRemote() },
        onToggle = { toggle() },
        onNext = { next() },
        onPrevious = { previous() },
        onSeek = { seconds -> seekTo(seconds) },
    )

    init {
        // 用户 11-16 的决定：上次在放，重开接着放。restore() 把队列/位置摆好，
        // 这里只在"上次确实在放"时才真的出声，位置由 startAt 接上。
        val wasPlaying = restore()
        history = MusicStore.loadRecent()
        likedIds = MusicStore.loadLikedIds()
        scope.launch { ticker() }
        if (wasPlaying && index >= 0) {
            val resumeAt = position
            scope.launch { playAt(index, startAt = resumeAt) }
        }
    }

    fun queueIndexOf(song: MusicSong): Int = queue.indexOfFirst { it.id == song.id }

    fun setMusicU(value: String?) {
        if (musicU != value) {
            searchRequest++
            playbackRequest++
            lyricRequest++
            searching = false
        }
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

    fun loadSelectedTabLabel(): String = MusicStore.loadSelectedTab().ifEmpty { "首页" }

    fun setSelectedTab(label: String) {
        MusicStore.saveSelectedTab(label)
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
        val removingCurrent = target == index
        val wasPlaying = playing
        queue = queue.toMutableList().also { it.removeAt(target) }
        index = when {
            queue.isEmpty() -> -1
            target < index -> index - 1
            removingCurrent -> index.coerceAtMost(queue.lastIndex)
            else -> index
        }
        if (removingCurrent) {
            playbackRequest++
            lyricRequest++
            player.stop()
            loaded = false
            playing = false
            current = queue.getOrNull(index)
            position = 0.0
            duration = 0.0
            lyrics = emptyList()
            lyricIndex = -1
            resumeTarget = -1.0
            resumeTicks = 0
            lastTickPosition = 0.0
            lastSavedPosition = 0.0
            MusicStore.savePosition(0.0)
            MusicStore.savePlaying(false)
            nowPlaying.clear()
            refreshNowPlaying()
            if (wasPlaying && index >= 0) {
                val nextSong = current ?: return
                scope.launch { play(nextSong) }
            }
        }
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
        sleepElapsed = 0.0
        sleepTick = TimeSource.Monotonic.markNow()
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
        val list = when {
            queue.any { it.id == song.id } -> queue
            songs.any { it.id == song.id } -> songs
            else -> queue + song
        }
        val active = current
        queue = if (active != null && list.none { it.id == active.id }) listOf(active) + list else list
        index = active?.let { item -> queue.indexOfFirst { it.id == item.id } } ?: -1
        MusicStore.saveQueue(queue, index.coerceAtLeast(0))
        if (!playAt(queue.indexOfFirst { it.id == song.id })) return
        val recent = listOf(song) + history.filterNot { it.id == song.id }
        history = recent.take(30)
        MusicStore.saveRecent(history)
    }

    /** 首页/发现点推荐卡片：搜到什么就把结果装进队列，供播放器“下一首”走。 */
    suspend fun searchAsPlaylist(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty() || searching) return
        query = trimmed
        val results = searchFor(trimmed) ?: return
        if (results.isNotEmpty()) {
            val active = current
            queue = if (active != null && results.none { it.id == active.id }) listOf(active) + results else results
            index = active?.let { song -> queue.indexOfFirst { it.id == song.id } } ?: -1
            MusicStore.saveQueue(queue, index.coerceAtLeast(0))
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

    private suspend fun searchFor(keyword: String): List<MusicSong>? {
        val q = keyword.trim()
        if (q.isEmpty() || searching) return null
        val request = ++searchRequest
        searching = true
        message = null
        try {
            val reply = engine.execute(neteaseSearchCall(q, musicU = musicU))
            if (request != searchRequest) return null
            val found = parseSearchSongs(reply.bodyText)
            if (!reply.isSuccessful) {
                message = "搜索返回 " + reply.status
                return null
            }
            songs = found
            if (found.isEmpty()) message = "没搜到。"
            return found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (request == searchRequest) message = "搜索失败：" + (e.message ?: "网络不通")
            return null
        } finally {
            if (request == searchRequest) searching = false
        }
    }

    fun toggle() {
        val song = current ?: return
        if (!loaded) {
            scope.launch { playAt(index, startAt = position) }
            return
        }
        if (playing) {
            pauseFromRemote()
            return
        } else {
            player.resume()
            sleepTick = TimeSource.Monotonic.markNow()
            playing = true
        }
        MusicStore.savePlaying(playing)
        refreshNowPlaying()
    }

    fun next() {
        if (queue.isEmpty()) return
        if (playMode != MODE_REPEAT_ALL && playMode != MODE_SHUFFLE && index >= queue.lastIndex) {
            message = "已播放到队列末尾"
            pauseFromRemote()
            return
        }
        val target = when (playMode) {
            MODE_ONE -> index
            MODE_SHUFFLE -> randomOther()
            MODE_REPEAT_ALL -> (index + 1) % queue.size
            else -> index + 1
        }
        val request = ++playbackRequest
        scope.launch {
            if (request == playbackRequest) playAt(target)
        }
    }

    fun previous() {
        if (queue.isEmpty()) return
        val target = if (playMode == MODE_SHUFFLE) randomOther() else index - 1
        val request = ++playbackRequest
        scope.launch {
            if (request == playbackRequest) playAt(target)
        }
    }

    /** 随机挑一首不是当前这首的曲目；单曲队列停在原处。 */
    private fun randomOther(): Int {
        if (queue.size <= 1) return index
        val pick = (0 until queue.size).random()
        return if (pick == index) (pick + 1) % queue.size else pick
    }

    fun seekTo(seconds: Double) {
        val ceiling = if (duration > 0.0) duration else seconds
        val target = seconds.coerceIn(0.0, ceiling)
        player.seek(target)
        position = target
        resumeTarget = -1.0
        resumeTicks = 0
        lastTickPosition = target
        endHandled = false
        lastSavedPosition = target
        MusicStore.savePosition(target)
        refreshNowPlaying()
    }

    private suspend fun playAt(target: Int, startAt: Double = 0.0): Boolean {
        val list = queue
        if (list.isEmpty()) return false
        val i = ((target % list.size) + list.size) % list.size
        val song = list[i]
        val request = ++playbackRequest
        message = null
        val url = resolve(song, bitrate)
        if (request != playbackRequest || !scope.isActive) return false
        val liveIndex = queue.indexOfFirst { it.id == song.id }
        if (liveIndex < 0) return false
        player.play(url, startAt = startAt)
        lyrics = emptyList()
        lyricIndex = -1
        current = song
        scope.launch { loadLyrics(song) }
        index = liveIndex
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
        lastTickPosition = position
        sleepTick = TimeSource.Monotonic.markNow()
        lastSavedPosition = position
        MusicStore.saveQueue(queue, index)
        MusicStore.savePosition(position)
        MusicStore.savePlaying(true)
        refreshNowPlaying()
        loadCover(song)
        return true
    }

    private suspend fun loadLyrics(song: MusicSong) {
        if (current?.id != song.id) return
        val request = ++lyricRequest
        val reply = try {
            engine.execute(neteaseLyricCall(song.id, musicU = musicU))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return
        }
        if (!reply.isSuccessful || request != lyricRequest || current?.id != song.id || !scope.isActive) return
        lyrics = parseLyrics(reply.bodyText)
        lyricIndex = -1
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

    /** 恢复队列和位置，并返回上次是否正在播放。 */
    private fun restore(): Boolean {
        val saved = MusicStore.loadQueue()
        if (saved.isEmpty()) return false
        val savedIndex = MusicStore.loadIndex().coerceIn(0, saved.lastIndex)
        queue = saved
        index = savedIndex
        current = saved[savedIndex]
        position = MusicStore.loadPosition()
        playing = false
        refreshNowPlaying()
        return MusicStore.loadPlaying()
    }

    private suspend fun ticker() {
        while (scope.isActive) {
            delay(500)
            val elapsed = sleepTick.elapsedNow().inWholeNanoseconds / 1_000_000_000.0
            sleepTick = TimeSource.Monotonic.markNow()
            if (!scrubbing && !player.seeking) {
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
                sleepElapsed += elapsed
                val seconds = sleepElapsed.toInt()
                sleepElapsed -= seconds
                sleepRemaining = (sleepRemaining - seconds).coerceAtLeast(0)
                if (sleepRemaining == 0) {
                    pauseFromRemote()
                    sleepMinutes = 0
                    sleepRemaining = 0
                    MusicStore.saveSleepMinutes(0)
                    message = "定时关闭已生效"
                }
            }
            // 结尾判定两路：正常播到尾巴，或 AVPlayer 已经在末尾把时间归零（短歌/拖到尾部时
            // 500ms 采样可能整段跳过 duration-0.4 那个窗口，只靠它就会一首歌卡死不放下一首）。
            val atTail = duration > 0.0 && position >= duration - 0.4
            val rewound = playing && duration > 2.0 && lastTickPosition >= duration - 1.5 && position < 0.5
            lastTickPosition = position
            if (playing && !player.seeking && (atTail || rewound) && !endHandled) {
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
        val request = ++coverRequest
        nowPlaying.setCover(null)
        val url = song.cover
        if (url.isEmpty()) return
        scope.launch {
            val data = fetchData(url)
            if (request != coverRequest || current?.id != song.id || current?.cover != url || !scope.isActive) return@launch
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
        sleepTick = TimeSource.Monotonic.markNow()
        playing = true
        MusicStore.savePlaying(true)
        refreshNowPlaying()
    }

    /** Only the playback owner may dispose; leaving the screen keeps audio running. */
    fun dispose() {
        playbackRequest++
        MusicStore.savePosition(position)
        MusicStore.savePlaying(playing)
        scopeJob.cancel()
        player.stop()
        playing = false
        loaded = false
        nowPlaying.dispose()
    }

    private fun pauseFromRemote() {
        playbackRequest++
        player.pause()
        playing = false
        lastSavedPosition = position
        MusicStore.savePosition(position)
        MusicStore.savePlaying(false)
        refreshNowPlaying()
    }
}
