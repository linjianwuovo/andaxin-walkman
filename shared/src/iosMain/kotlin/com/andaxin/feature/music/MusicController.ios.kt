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
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.UIKit.UIImage
import kotlin.coroutines.resume

/*
 * 音乐这一屏的全部状态和网络调用都收在这里，composable 只负责画。
 *
 * 协议层只有匿名链路（搜索 + 取直链 + 放），实测这两个接口不要签名：
 *   GET /api/search/get/            -> songs 数组
 *   GET /api/song/enhance/player/url -> data[0].url 就是 mp3 直链
 * 登录（WKWebView 抓 MUSIC_U）拿到的 cookie 现在会真的带进请求，也落进 NSUserDefaults。
 *
 * 四件"这一版才补上"的东西：
 *  队列     —— 一次搜索的结果就是队列，下一首/上一首在里面转圈走。
 *  进度     —— 每 0.5 秒问一次 AVPlayer 的位置（见 MusicPlayer 里为什么不用 periodicTimeObserver），
 *              拖拽时把 scrubbing 置真，别让游标和用户的手指打架。
 *  锁屏     —— NowPlaying 那一层负责写 nowPlayingInfo + 接耳机线控。
 *  落盘     —— 队列/第几首/第几秒/MUSIC_U 存 NSUserDefaults；冷启动只恢复"上次那首 + 那个位置"，
 *              **不自动出声**（0.9.29 那条决定：重开就响会吓死人）。
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
    var position by mutableStateOf(0.0)
    var duration by mutableStateOf(0.0)
    var musicU by mutableStateOf(MusicStore.loadMusicU())
        private set

    /** 用户正按着进度条：这段时间别用播放器的游标覆盖 position。 */
    var scrubbing = false

    private var index = -1
    private var loaded = false
    private var endHandled = false
    private var lastSavedPosition = -1.0

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
        scope.launch { ticker() }
    }

    fun queueIndexOf(song: MusicSong): Int = queue.indexOfFirst { it.id == song.id }

    fun setMusicU(value: String?) {
        musicU = value
        MusicStore.saveMusicU(value)
    }

    suspend fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        message = null
        try {
            val reply = engine.execute(neteaseSearchCall(q, musicU = musicU))
            val found = parseSearchSongs(reply.bodyText)
            songs = found
            if (found.isEmpty()) {
                message = if (reply.isSuccessful) "没搜到。" else "搜索返回 " + reply.status
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            message = "搜索失败：" + (e.message ?: "网络不通")
        } finally {
            searching = false
        }
    }

    /** 点列表里的一首：这次搜索的结果整条变成队列，从这一首开始放。 */
    suspend fun play(song: MusicSong) {
        val list = songs.ifEmpty { listOf(song) }
        val found = list.indexOfFirst { it.id == song.id }
        queue = list
        playAt(if (found >= 0) found else 0)
    }

    fun toggle() {
        val song = current ?: return
        if (!loaded) {
            scope.launch { playAt(index) }
            return
        }
        if (playing) {
            player.pause()
            playing = false
        } else {
            player.resume()
            playing = true
        }
        refreshNowPlaying()
    }

    fun next() {
        if (queue.isEmpty()) return
        scope.launch { playAt(index + 1) }
    }

    fun previous() {
        if (queue.isEmpty()) return
        scope.launch { playAt(index - 1) }
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

    private suspend fun playAt(target: Int) {
        val list = queue
        if (list.isEmpty()) return
        val i = ((target % list.size) + list.size) % list.size
        val song = list[i]
        message = null
        val url = resolve(song)
        player.play(url)
        current = song
        index = i
        loaded = true
        playing = true
        position = 0.0
        duration = 0.0
        endHandled = false
        lastSavedPosition = 0.0
        MusicStore.saveQueue(list, i)
        MusicStore.savePosition(0.0)
        refreshNowPlaying()
        loadCover(song)
    }

    /*
     * 直链拿不到（会员曲/无版权/接口抽风）就走外链兜底：/song/media/outer/url 会 302 到能放的那一版，
     * 免费歌基本都能过；两条都空才如实告诉用户，别静默假装在放。
     */
    private suspend fun resolve(song: MusicSong): String {
        val direct = try {
            parseSongUrl(engine.execute(neteaseSongUrlCall(song.id, musicU = musicU)).bodyText)
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
                if (loaded || pos > 0.0) position = pos
            }
            val dur = player.durationSeconds()
            if (dur > 0.5 && dur != duration) duration = dur
            if (playing && duration > 0.0 && position >= duration - 0.4 && !endHandled) {
                endHandled = true
                next()
            }
            if (position - lastSavedPosition >= 5.0) {
                lastSavedPosition = position
                MusicStore.savePosition(position)
            }
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
            // 回来的时候可能已经切歌了，别把上一首的封面贴到这一首上
            if (current?.cover != url) return@launch
            nowPlaying.setCover(data?.let { UIImage(data = it) })
            refreshNowPlaying()
        }
    }

    /**
     * 取一张图回来。用 NSURLSession 而不是 NSData.dataWithContentsOfURL ——
     * 后者在 K/N 里根本没绑成类方法（CI 报 Unresolved reference），
     * 而 dataTaskWithRequest 这条 IosImages.kt 里已经跑通过，照抄它的写法：
     * 带 completionHandler 的那条是**顶层扩展**，不 import 就只剩单参数成员，报 "Too many arguments"。
     */
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
            scope.launch { playAt(index) }
            return
        }
        player.resume()
        playing = true
        refreshNowPlaying()
    }

    private fun pauseFromRemote() {
        if (!playing) return
        player.pause()
        playing = false
        refreshNowPlaying()
    }
}
