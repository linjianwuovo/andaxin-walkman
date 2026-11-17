package com.andaxin.feature.music

import com.andaxin.core.net.HttpBody
import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.formUrlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * 网易云那一层的「协议」：只有拼 URL 和拆 JSON，不碰网络也不碰 UI ——
 * 这样它在 Windows 上就能被 jvmTest 直接跑（本机没有 iOS 编译器，
 * 凡是能在 JVM 上钉死的东西一律先钉死，别留给 CI 猜）。
 *
 * 用的是**不需要签名**的两条老接口：
 *   GET /api/search/get            —— 搜索
 *   GET /api/song/enhance/player/url?ids=[id]&br=128000 —— 播放地址
 * 2026-11-15 实测（Windows/python，匿名 cookie）两条都回 200，
 * 拿到的 mp3 直链可以直接下；weapi/eapi 那层签名留着以后取无损/登录态再用。
 */

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

/** 匿名身份。os=pc 是最宽松的一档，没有它有些歌只回 30 秒试听。 */
private const val ANON_COOKIE = "os=pc; appver=8.9.70; __csrf="

/** 登录过就把 MUSIC_U 接在匿名身份后面：接口认的是这条 cookie，不是别的什么 token。 */
private fun baseHeaders(musicU: String?): List<Pair<String, String>> {
    val cookie = if (musicU.isNullOrBlank()) ANON_COOKIE else "$ANON_COOKIE; MUSIC_U=$musicU"
    return listOf(
        "User-Agent" to UA,
        "Referer" to "https://music.163.com/",
        "Cookie" to cookie,
    )
}

data class MusicSong(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    /** 封面地址（可能还是 / 结尾的模板地址）。锁屏那张图用它，列表里这版不画。 */
    val cover: String = "",
)

fun neteaseSearchCall(query: String, limit: Int = 20, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/search/get/?s=" + formUrlEncode(query) +
        "&type=1&limit=" + limit.coerceIn(1, 50) + "&offset=0",
    headers = baseHeaders(musicU),
)

fun neteaseSongUrlCall(id: Long, br: Int = 128000, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    // ids 是 JSON 数组的字符串，方括号必须转义，直接写 [ 会被服务端当成参数结束
    url = "https://music.163.com/api/song/enhance/player/url?ids=%5B" + id + "%5D&br=" + br,
    headers = baseHeaders(musicU),
)

/** 老接口里的兜底直链：302 到同一首的 mp3。拿不到播放地址时用它，客户端自己跟重定向。 */
fun neteaseOuterUrl(id: Long): String = "https://music.163.com/song/media/outer/url?id=" + id + ".mp3"

fun neteaseLyricCall(id: Long, musicU: String? = null): HttpCall = HttpCall(
    method = "POST",
    url = "https://interface.music.163.com/api/song/lyric/v1",
    headers = baseHeaders(musicU).plus("Content-Type" to "application/x-www-form-urlencoded"),
    body = HttpBody.Form(
        listOf(
            "id" to id.toString(),
            "cp" to "false",
            "tv" to "0",
            "lv" to "0",
            "rv" to "0",
            "kv" to "0",
            "yv" to "0",
            "ytv" to "0",
            "yrv" to "0",
        ),
    ),
)

data class LyricLine(val timeMs: Long, val text: String)

fun parseLyrics(json: String): List<LyricLine> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (runCatching { root["code"]?.jsonPrimitive?.longOrNull }.getOrNull() != 200L) return@runCatching emptyList()
    val lrc = runCatching { root["lrc"]?.jsonObject }.getOrNull() ?: return@runCatching emptyList()
    val lyric = runCatching { lrc["lyric"]?.jsonPrimitive?.content }.getOrNull() ?: return@runCatching emptyList()
    (parseEnhancedLyrics(lyric) + parseClassicLyrics(lyric)).distinctBy { it.timeMs }.sortedBy { it.timeMs }
}.getOrNull() ?: emptyList()

private fun parseEnhancedLyrics(lyric: String): List<LyricLine> = buildList {
    lyric.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return@forEach
        val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return@forEach
        val timeMs = runCatching { obj["t"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return@forEach
        val text = runCatching {
            (obj["c"] as? JsonArray)?.mapNotNull {
                runCatching { it.jsonObject["tx"]?.jsonPrimitive?.content }.getOrNull()
            }?.joinToString("").orEmpty()
        }.getOrDefault("")
        if (text.isNotBlank()) add(LyricLine(timeMs, text))
    }
}.sortedBy { it.timeMs }

private val classicLyricLine = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)")

private fun parseClassicLyrics(lyric: String): List<LyricLine> = buildList {
    lyric.lineSequence().forEach { line ->
        var matched = false
        classicLyricLine.findAll(line).forEach { match ->
            matched = true
            val minutes = match.groupValues[1].toLongOrNull() ?: return@forEach
            val seconds = match.groupValues[2].toDoubleOrNull() ?: return@forEach
            if (seconds < 0.0 || seconds >= 60.0) return@forEach
            val text = match.groupValues[3].trim()
            if (text.isNotEmpty()) add(LyricLine(minutes * 60_000L + (seconds * 1000.0).toLong(), text))
        }
        if (!matched && line.isNotBlank() && !line.trim().startsWith("{")) {
            val timeMs = metadataTimeMs(line.trim())
            if (timeMs != null) add(LyricLine(timeMs, line.trim()))
        }
    }
}

private fun metadataTimeMs(line: String): Long? {
    if (!line.startsWith("[") || !line.contains("]:")) return null
    val value = line.substringAfter('[').substringBefore(']').trim()
    val parts = value.split(':')
    if (parts.size < 2) return null
    val minutes = parts[0].toLongOrNull() ?: return null
    val seconds = parts[1].toDoubleOrNull() ?: return null
    if (seconds < 0.0 || seconds >= 60.0) return null
    return minutes * 60_000L + (seconds * 1000.0).toLong()
}

fun parseSearchSongs(json: String): List<MusicSong> {
    val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
    val songs = root["result"]?.jsonObjectOrNull()?.get("songs") as? JsonArray ?: return emptyList()
    return songs.mapNotNull { element ->
        val song = element.jsonObjectOrNull() ?: return@mapNotNull null
        val id = song["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val name = song["name"]?.jsonPrimitive?.contentOrNull() ?: return@mapNotNull null
        val artists = (song["artists"] as? JsonArray)
            ?.mapNotNull { it.jsonObjectOrNull()?.get("name")?.jsonPrimitive?.contentOrNull() }
            ?.joinToString("/")
            .orEmpty()
        val album = song["album"]?.jsonObjectOrNull()
        MusicSong(
            id = id,
            name = name,
            artist = artists,
            album = album?.get("name")?.jsonPrimitive?.contentOrNull().orEmpty(),
            cover = album?.get("picUrl")?.jsonPrimitive?.contentOrNull().orEmpty(),
        )
    }
}

/** 成功返回直链；版权受限/下架时 url 是 null，返回 null 让上层去试 outer 兜底。 */
fun parseSongUrl(json: String): String? {
    val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val first = (root["data"] as? JsonArray)?.firstOrNull()?.jsonObjectOrNull() ?: return null
    val url = first["url"]?.jsonPrimitive?.contentOrNull()
    return url?.takeIf { it.startsWith("http") }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()
