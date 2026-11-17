package com.andaxin.feature.music

import com.andaxin.core.net.HttpBody
import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.formUrlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * 网易云协议层：只拼 URL 和拆 JSON，不碰网络也不碰 UI。
 * 只用匿名老接口（搜索、取直链、歌词），不引签名也不碰 Apple Music 官方 API。
 */

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
private const val ANON_COOKIE = "os=pc; appver=8.9.70; __csrf="

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
    url = "https://music.163.com/api/song/enhance/player/url?ids=%5B" + id + "%5D&br=" + br,
    headers = baseHeaders(musicU),
)

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

/** 一行歌词：timeMs 是行开始时间；wordTimes 是逐字时间戳（增强歌词才有）。 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val wordTimes: List<Long> = emptyList(),
)

fun parseLyrics(json: String): List<LyricLine> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (runCatching { root["code"]?.jsonPrimitive?.longOrNull }.getOrNull() != 200L) return@runCatching emptyList()
    val lrc = runCatching { root["lrc"]?.jsonObject }.getOrNull() ?: return@runCatching emptyList()
    val lyric = runCatching { lrc["lyric"]?.jsonPrimitive?.content }.getOrNull() ?: return@runCatching emptyList()
    val enhanced = parseEnhancedLyrics(lyric)
    // 增强歌词自带完整的时间轴，别把同一行的经典 LRC 版本再叠上去。
    if (enhanced.isNotEmpty()) enhanced.sortedBy { it.timeMs }
    else parseClassicLyrics(lyric).distinctBy { it.timeMs }.sortedBy { it.timeMs }
}.getOrNull() ?: emptyList()

/** 增强歌词：{"t":1250,"c":[{"tx":"故事","t":900},{"tx":"的小黄花"}]}，tx 拼正文，c[].t 是逐字时间。 */
private fun parseEnhancedLyrics(lyric: String): List<LyricLine> = buildList {
    lyric.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return@forEach
        val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return@forEach
        val timeMs = runCatching { obj["t"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: return@forEach
        val chunks = runCatching { obj["c"] as? JsonArray }.getOrNull().orEmpty()
        val text = chunks.mapNotNull { chunk ->
            runCatching { chunk.jsonObject["tx"]?.jsonPrimitive?.content }.getOrNull()
        }.joinToString("")
        if (text.isNotBlank()) {
            val wordTimes = chunks.map { chunk ->
                runCatching { chunk.jsonObject["t"]?.jsonPrimitive?.longOrNull }.getOrNull()
            }
            add(LyricLine(timeMs, text, wordTimes.filterNotNull().takeIf { it.size == chunks.size }.orEmpty()))
        }
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

fun parseSongUrl(json: String): String? {
    val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
    val first = (root["data"] as? JsonArray)?.firstOrNull()?.jsonObjectOrNull() ?: return null
    val url = first["url"]?.jsonPrimitive?.contentOrNull()
    return url?.takeIf { it.startsWith("http") }
}

private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
