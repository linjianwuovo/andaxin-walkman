package com.andaxin.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.andaxin.core.net.HttpEngine
import com.andaxin.core.net.HttpEngineConfig
import com.andaxin.core.net.createHttpEngine
import kotlinx.coroutines.CancellationException

/*
 * 音乐这一屏的全部状态和网络调用都收在这里，composable 只负责画。
 *
 * 第一版只做匿名链路（搜索 + 取直链 + 放），因为实测这两个接口不要签名：
 *   GET /api/search/get/            -> 歌单一样的 songs 数组
 *   GET /api/song/enhance/player/url -> data[0].url 就是 mp3 直链
 * 登录（WKWebView 抓 MUSIC_U）先把凭据拿到手，签名那套等要无损/个人歌单再上。
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
    var musicU by mutableStateOf<String?>(null)

    private val player = MusicPlayer()

    suspend fun search() {
        val q = query.trim()
        if (q.isEmpty() || searching) return
        searching = true
        message = null
        try {
            val reply = engine.execute(neteaseSearchCall(q))
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

    suspend fun play(song: MusicSong) {
        message = null
        val url = try {
            parseSongUrl(engine.execute(neteaseSongUrlCall(song.id)).bodyText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        /*
         * 直链拿不到（会员曲/无版权/接口抽风）就走外链兜底：/song/media/outer/url 会 302 到能放的那一版，
         * 免费歌基本都能过；还是不行就如实告诉用户，别静默假装在放。
         */
        val target = url ?: neteaseOuterUrl(song.id)
        try {
            player.play(target)
            current = song
            playing = true
        } catch (e: Throwable) {
            message = "这首放不了：" + (e.message ?: "直链无效")
        }
    }

    fun toggle() {
        val before = playing
        if (before) player.pause() else player.resume()
        playing = !before
    }
}
