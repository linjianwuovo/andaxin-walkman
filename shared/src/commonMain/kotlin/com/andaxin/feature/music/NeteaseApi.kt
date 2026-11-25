package com.andaxin.feature.music

import com.andaxin.core.net.HttpBody
import com.andaxin.core.net.HttpCall
import com.andaxin.core.net.formUrlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
    val duration: Long = 0L,
    val artistId: Long = 0L,
    val albumId: Long = 0L,
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

fun neteaseTopListCall(musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/toplist/detail",
    headers = baseHeaders(musicU),
)

fun neteasePlaylistDetailCall(id: Long, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/playlist/detail?id=" + id,
    headers = baseHeaders(musicU),
)

fun neteaseAlbumCall(id: Long, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/album/" + id,
    headers = baseHeaders(musicU),
)

fun neteaseArtistCall(id: Long, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/artist/" + id,
    headers = baseHeaders(musicU),
)

fun neteaseArtistAlbumsCall(id: Long, limit: Int = 30, offset: Int = 0, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/artist/albums/" + id + "?limit=" + limit + "&offset=" + offset,
    headers = baseHeaders(musicU),
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val wordTimes: List<Long> = emptyList(),
    val translation: String = "",
)

data class MusicCatalogItem(val id: Long, val name: String, val cover: String, val count: Int = 0)
data class MusicArtistDetail(val id: Long, val name: String, val cover: String, val description: String)
data class MusicAlbumDetail(val id: Long, val name: String, val cover: String, val artist: String, val description: String, val songs: List<MusicSong>)
data class MusicPlaylistDetail(val id: Long, val name: String, val cover: String, val description: String, val playCount: Long, val songs: List<MusicSong>)

fun neteaseLyricCall(id: Long, musicU: String? = null): HttpCall = HttpCall(
    method = "POST",
    url = "https://music.163.com/api/song/lyric/v1",
    headers = baseHeaders(musicU) + listOf("Content-Type" to "application/x-www-form-urlencoded"),
    body = HttpBody.Form(listOf("id" to id.toString(), "lv" to "-1", "kv" to "-1", "tv" to "-1", "rv" to "-1")),
)

fun neteaseTopListByIdCall(id: Long, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/toplist?id=" + id,
    headers = baseHeaders(musicU),
)

fun neteaseSearchSuggestCall(keyword: String, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/search/suggest/web?s=" + formUrlEncode(keyword),
    headers = baseHeaders(musicU),
)

fun neteasePlaylistCatalogueCall(musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/playlist/catalogue",
    headers = baseHeaders(musicU),
)

fun neteasePlaylistByCategoryCall(category: String, limit: Int = 30, offset: Int = 0, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/playlist/list?cat=" + formUrlEncode(category) + "&limit=" + limit + "&offset=" + offset,
    headers = baseHeaders(musicU),
)

fun neteasePodcastCategoryCall(musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/djradio/category/get",
    headers = baseHeaders(musicU),
)

fun neteaseSongDetailCall(ids: List<Long>, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/song/detail?ids=[" + ids.joinToString(",") + "]",
    headers = baseHeaders(musicU),
)

private fun contentOrNull(obj: JsonElement?, key: String): String? = runCatching { obj?.jsonObject?.get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
private fun longOrNull(obj: JsonElement?, key: String): Long? = runCatching { obj?.jsonObject?.get(key)?.jsonPrimitive?.longOrNull }.getOrNull()
private fun intOrNull(obj: JsonElement?, key: String): Int? = runCatching { obj?.jsonObject?.get(key)?.jsonPrimitive?.intOrNull }.getOrNull()
private fun arrayOrEmpty(obj: JsonElement?, key: String): List<JsonElement> = runCatching { obj?.jsonObject?.get(key)?.jsonArray?.toList() }.getOrNull().orEmpty()
private fun firstCover(obj: JsonElement?): String = listOf("coverImgUrl", "picUrl", "pic56x56Url", "cover", "img1v1Url").mapNotNull { contentOrNull(obj, it) }.firstOrNull().orEmpty()
private fun parseSongElement(obj: JsonElement): MusicSong {
    val artists = arrayOrEmpty(obj, "artists").ifEmpty { arrayOrEmpty(obj, "ar") }
    val album = obj.jsonObject["album"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
        ?: obj.jsonObject["al"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }
    return MusicSong(
        id = longOrNull(obj, "id") ?: 0L,
        name = contentOrNull(obj, "name").orEmpty(),
        artist = artists.mapNotNull { contentOrNull(it, "name") }.joinToString(" / "),
        album = contentOrNull(album, "name").orEmpty(),
        cover = firstCover(album),
        duration = longOrNull(obj, "duration") ?: longOrNull(obj, "dt") ?: 0L,
        artistId = artists.firstOrNull()?.let { longOrNull(it, "id") } ?: 0L,
        albumId = longOrNull(album, "id") ?: 0L,
    )
}

fun parseSearchSongs(json: String): List<MusicSong> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val result = root["result"]?.jsonObject ?: root
    val array = result["songs"]?.jsonArray.orEmpty()
    array.map(::parseSongElement)
}.getOrElse { emptyList() }

fun parseSongUrl(json: String): String = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val data = root["data"]?.jsonArray ?: return@runCatching ""
    data.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
}.getOrElse { "" }
fun parseSongArray(json: String): List<MusicSong> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val array = root["songs"]?.jsonArray ?: root["tracks"]?.jsonArray ?: root["data"]?.jsonArray ?: return@runCatching emptyList()
    array.map(::parseSongElement)
}.getOrElse { emptyList() }

fun parseRankings(json: String): List<MusicCatalogItem> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val list = root["list"]?.jsonArray.orEmpty()
    list.mapNotNull { item ->
        val id = longOrNull(item, "id") ?: return@mapNotNull null
        MusicCatalogItem(id, contentOrNull(item, "name").orEmpty(), firstCover(item), intOrNull(item, "updateFrequency") ?: 0)
    }
}.getOrElse { emptyList() }

fun parseCatalogItems(json: String, key: String): List<MusicCatalogItem> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val data = root["result"]?.jsonObject ?: root
    val list = data[key]?.jsonArray.orEmpty()
    list.mapNotNull { item ->
        val name = contentOrNull(item, "name").orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        MusicCatalogItem(longOrNull(item, "id") ?: 0L, name, firstCover(item), intOrNull(item, "resourceCount") ?: 0)
    }
}.getOrElse { emptyList() }

fun parseSearchSuggestions(json: String): List<MusicSong> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val result = root["result"]?.jsonObject ?: root
    arrayOrEmpty(result, "songs").map(::parseSongElement)
}.getOrElse { emptyList() }

fun parseAlbumDetail(json: String): MusicAlbumDetail? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val album = root["album"]?.jsonObject ?: root
    val id = longOrNull(album, "id") ?: return@runCatching null
    val artist = arrayOrEmpty(album, "artists").firstOrNull()?.let { contentOrNull(it, "name") } ?: contentOrNull(album, "artist").orEmpty()
    val songs = arrayOrEmpty(album, "songs").map(::parseSongElement)
    MusicAlbumDetail(id, contentOrNull(album, "name").orEmpty(), firstCover(album), artist, contentOrNull(album, "description").orEmpty(), songs)
}.getOrNull()

fun parseArtistDetail(json: String): MusicArtistDetail? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val artist = root["artist"]?.jsonObject ?: root
    val id = longOrNull(artist, "id") ?: return@runCatching null
    MusicArtistDetail(id, contentOrNull(artist, "name").orEmpty(), firstCover(artist), contentOrNull(artist, "briefDesc").orEmpty())
}.getOrNull()

fun parseArtistAlbums(json: String): List<MusicCatalogItem> = parseCatalogItems(json, "hotAlbums")

fun parsePlaylistDetail(json: String): MusicPlaylistDetail? = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val playlist = root["playlist"]?.jsonObject ?: return@runCatching null
    val id = longOrNull(playlist, "id") ?: return@runCatching null
    val songs = arrayOrEmpty(playlist, "tracks").map(::parseSongElement)
    MusicPlaylistDetail(id, contentOrNull(playlist, "name").orEmpty(), firstCover(playlist), contentOrNull(playlist, "description").orEmpty(), longOrNull(playlist, "playCount") ?: 0L, songs)
}.getOrNull()

private val classicTimeRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
private val richChunkRegex = Regex("<cy([^>]*)>(.*?)</cy>", RegexOption.DOT_MATCHES_ALL)
private val richTimeRegex = Regex("(?:timestamp|t|d)=\\s*['\"]?([^'\"\\s>]+)['\"]?")
private val ttmlLineRegex = Regex("<p([^>]*)>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
private val ttmlSpanRegex = Regex("<span([^>]*)>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
private fun stripTags(value: String): String = value.replace(Regex("<[^>]*>"), "")
private fun timeMs(raw: String): Long {
    val value = raw.trim()
    if (value.isEmpty()) return 0L
    if (value.all { it.isDigit() }) return value.toLongOrNull() ?: 0L
    if (value.endsWith("ms")) return (value.removeSuffix("ms").toLongOrNull() ?: 0L)
    if (value.endsWith('m')) return (value.removeSuffix("m").toLongOrNull() ?: 0L) * 60000L
    if (value.endsWith('h')) return (value.removeSuffix("h").toLongOrNull() ?: 0L) * 3600000L
    if (value.endsWith('s')) return parseClockTime(value.dropLast(1))
    return parseClockTime(value)
}
private fun parseClockTime(value: String): Long {
    val parts = value.split(':')
    val secondsPart = parts.last()
    val decimal = secondsPart.split('.', limit = 2)
    val seconds = (decimal[0].toLongOrNull() ?: 0L)
    val millis = decimal.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
    var minutes = 0L
    for (index in 0 until parts.size - 1) minutes = minutes * 60L + (parts[index].toLongOrNull() ?: 0L)
    return minutes * 60000L + seconds * 1000L + millis
}
private fun classicLrcTimeMs(line: String): Long? {
    val match = classicTimeRegex.find(line) ?: return null
    val minute = match.groupValues[1].toLongOrNull() ?: 0L
    val second = match.groupValues[2].toLongOrNull() ?: 0L
    val fraction = match.groupValues[3]
    val millis = if (fraction.isEmpty()) 0L else fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    return minute * 60000L + second * 1000L + millis
}
private fun parseClassicLrc(raw: String): List<LyricLine> {
    val result = mutableListOf<LyricLine>()
    raw.lineSequence().forEach { line ->
        val text = line.replace(classicTimeRegex, "").trim()
        classicTimeRegex.findAll(line).forEach { match ->
            classicLrcTimeMs(match.value)?.let { time ->
                result.add(LyricLine(time, text.ifEmpty { "♪" }))
            }
        }
    }
    return result.sortedBy { it.timeMs }
}
private fun parseRichLyrics(raw: String): List<LyricLine> {
    val result = mutableListOf<LyricLine>()
    richChunkRegex.findAll(raw).forEach { chunk ->
        val attrs = chunk.groupValues[1]
        val times = richTimeRegex.findAll(attrs).map { timeMs(it.groupValues[1].trim('"', '\'')) }.toList()
        val start = times.firstOrNull() ?: return@forEach
        val text = stripTags(chunk.groupValues[2]).trim()
        if (text.isNotEmpty()) result.add(LyricLine(start, text))
    }
    if (result.isNotEmpty()) return result.sortedBy { it.timeMs }
    return parseClassicLrc(stripTags(raw))
}
private fun parseTtmlLyrics(raw: String): List<LyricLine> {
    val result = mutableListOf<LyricLine>()
    ttmlLineRegex.findAll(raw).forEach { line ->
        val beginAttr = line.groupValues[1]
        val begin = Regex("begin\\s*=\\s*['\"]?([^'\"\\s>]+)['\"]?").find(beginAttr)?.groupValues?.get(1) ?: "0"
        val start = timeMs(begin)
        val inner = line.groupValues[2]
        val spans = ttmlSpanRegex.findAll(inner).toList()
        if (spans.isEmpty()) {
            val text = stripTags(inner).trim()
            if (text.isNotEmpty()) result.add(LyricLine(start, text))
        } else spans.forEach { span ->
            val spanAttr = span.groupValues[1]
            val spanBegin = Regex("begin\\s*=\\s*['\"]?([^'\"\\s>]+)['\"]?").find(spanAttr)?.groupValues?.get(1) ?: begin
            val text = stripTags(span.groupValues[2]).trim()
            if (text.isNotEmpty()) result.add(LyricLine(timeMs(spanBegin), text))
        }
    }
    if (result.isNotEmpty()) return result.sortedBy { it.timeMs }
    return parseClassicLrc(stripTags(raw))
}
private val yrcLineRegex = Regex("""^\[(\d+),(\d+)\](.*)$""")
private val yrcWordRegex = Regex("""\((\d+),(\d+),\d+\)""")

private fun parseYrcLyrics(raw: String): List<LyricLine> = raw.lineSequence().mapNotNull { row ->
    val line = yrcLineRegex.matchEntire(row.trim()) ?: return@mapNotNull null
    val start = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
    val content = line.groupValues[3]
    val words = yrcWordRegex.findAll(content).toList()
    if (words.isEmpty()) return@mapNotNull null
    val text = StringBuilder()
    val times = mutableListOf<Long>()
    words.forEachIndexed { index, word ->
        val wordStart = word.groupValues[1].toLongOrNull() ?: return@forEachIndexed
        val end = words.getOrNull(index + 1)?.range?.first ?: content.length
        val chunk = content.substring(word.range.last + 1, end)
        text.append(chunk)
        repeat(chunk.length) { times.add((wordStart - start).coerceAtLeast(0L)) }
    }
    text.toString().takeIf { it.isNotBlank() }?.let { LyricLine(start, it, times) }
}.sortedBy { it.timeMs }.toList()

private fun parseLyricPayload(raw: String): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    if (raw.trimStart().startsWith("{")) return runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        val translated = arrayOrEmpty(obj, "translatedLyric").flatMap { e ->
            parseClassicLrc(contentOrNull(e, "lineLyric").orEmpty())
        }
        if (translated.isNotEmpty()) translated else parseClassicLrc(contentOrNull(obj, "lyric").orEmpty())
    }.getOrElse { parseClassicLrc(raw) }
    if (raw.contains("<p") && raw.contains("</p>")) return parseTtmlLyrics(raw)
    if (raw.contains("<cy")) return parseRichLyrics(raw)
    val yrc = parseYrcLyrics(raw)
    return yrc.ifEmpty { parseClassicLrc(raw) }
}

private fun lyricPayload(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> value.contentOrNull
    is JsonObject -> contentOrNull(value, "lyric") ?: contentOrNull(value, "content")
    else -> null
}.orEmpty()

private fun mergeLyricTranslations(original: List<LyricLine>, translations: List<LyricLine>): List<LyricLine> {
    if (translations.isEmpty()) return original
    val attached = mutableMapOf<Int, String>()
    if (original.any { it.wordTimes.isNotEmpty() }) {
        for (translation in translations) {
            val nearest = original.indices.filter { it !in attached }
                .minWithOrNull(compareBy<Int> { kotlin.math.abs(original[it].timeMs - translation.timeMs) }
                    .thenByDescending { original[it].text.length }) ?: continue
            if (kotlin.math.abs(original[nearest].timeMs - translation.timeMs) <= 3000L) {
                attached[nearest] = translation.text
            }
        }
    } else {
        var nearest = 0
        for ((index, line) in original.withIndex()) {
            while (nearest < translations.lastIndex &&
                kotlin.math.abs(translations[nearest].timeMs - line.timeMs) >
                kotlin.math.abs(translations[nearest + 1].timeMs - line.timeMs)) nearest++
            attached[index] = translations[nearest].text
        }
    }
    return original.mapIndexed { index, line ->
        line.copy(translation = attached[index].orEmpty().takeUnless { it == line.text }.orEmpty())
    }
}

fun parseLyrics(json: String): List<LyricLine> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val lrcObject = root["lrc"] as? JsonObject ?: root
    val original = sequenceOf("ttml", "yrc", "qrc", "lrc", "translatedLrc", "romalrc")
        .map { key -> parseLyricPayload(lyricPayload(lrcObject[key] ?: root[key])) }
        .firstOrNull { it.isNotEmpty() }.orEmpty()
    val translations = sequenceOf("ytlrc", "tlyric", "translatedLrc").map { key ->
        parseLyricPayload(lyricPayload(root[key] ?: lrcObject[key]))
            .filter { it.text != "♪" && it.text.isNotBlank() && !it.text.startsWith("//") }
    }.firstOrNull { it.isNotEmpty() }.orEmpty()
    mergeLyricTranslations(original, translations)
}.getOrElse { emptyList() }

internal fun displayedLyricTranslation(line: LyricLine, enabled: Boolean): String? =
    line.translation.takeIf { enabled && it.isNotBlank() && it != line.text }

data class MusicSongPage(val songs: List<MusicSong>, val more: Boolean, val total: Int)

data class MusicHistoryEntry(val song: MusicSong, val playedAt: Long?)

data class MusicHistoryPage(val entries: List<MusicHistoryEntry>, val remoteEnabled: Boolean)

/** Device-local history transition. A resumed/restarted current item must not be recorded again. */
fun recordLocalPlayback(
    existing: List<MusicHistoryEntry>,
    song: MusicSong,
    playedAt: Long,
    isNewTrack: Boolean,
    maximum: Int = 100,
): List<MusicHistoryEntry> {
    if (!isNewTrack || song.id <= 0L || maximum <= 0) return existing
    return (listOf(MusicHistoryEntry(song, playedAt)) + existing.filterNot { it.song.id == song.id })
        .take(maximum)
}

fun neteaseRecentSongsCall(musicU: String, limit: Int = 100): HttpCall {
    require(musicU.isNotBlank()) { "请先登录网易云" }
    return HttpCall(
        method = "POST",
        url = "https://music.163.com/api/play-record/song/list",
        headers = baseHeaders(musicU),
        body = HttpBody.Form(listOf("limit" to limit.coerceIn(1, 100).toString())),
    )
}

fun parseRecentSongs(json: String): MusicHistoryPage {
    val root = checkedMusicRoot(json)
    val data = root["data"]?.jsonObject ?: return MusicHistoryPage(emptyList(), remoteEnabled = false)
    val entries = data["list"]?.jsonArray.orEmpty().mapNotNull { item ->
        val entry = item.jsonObject
        val songObject = entry["song"] ?: entry["simpleSong"] ?: return@mapNotNull null
        val song = parseSongElement(songObject)
        if (song.id <= 0L) return@mapNotNull null
        val time = longOrNull(item, "playTime") ?: longOrNull(item, "playedAt")
        MusicHistoryEntry(song, time)
    }
    return MusicHistoryPage(entries, remoteEnabled = true)
}

fun mergeRecentSongs(
    local: List<MusicHistoryEntry>,
    remote: List<MusicHistoryEntry>?,
    maximum: Int = 100,
): List<MusicHistoryEntry> {
    data class Candidate(val entry: MusicHistoryEntry, val order: Int)
    val chosen = linkedMapOf<Long, Candidate>()
    (local + remote.orEmpty()).forEachIndexed { index, entry ->
        val prior = chosen[entry.song.id]
        val newer = prior == null || when {
            entry.playedAt == null -> false
            prior.entry.playedAt == null -> true
            else -> entry.playedAt > prior.entry.playedAt
        }
        if (newer) chosen[entry.song.id] = Candidate(entry, prior?.order ?: index)
    }
    return chosen.values.sortedWith { left, right ->
        when {
            left.entry.playedAt == null && right.entry.playedAt == null -> left.order.compareTo(right.order)
            left.entry.playedAt == null -> 1
            right.entry.playedAt == null -> -1
            left.entry.playedAt != right.entry.playedAt -> right.entry.playedAt.compareTo(left.entry.playedAt)
            else -> left.order.compareTo(right.order)
        }
    }.take(maximum.coerceAtLeast(0)).map { it.entry }
}

fun neteasePodcastRadiosCall(categoryId: Long = 0L, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = if (categoryId > 0L) "https://music.163.com/api/djradio/recommend?cateId=$categoryId"
          else "https://music.163.com/api/djradio/recommend/v1",
    headers = baseHeaders(musicU),
)

fun neteasePodcastProgramsCall(radioId: Long, limit: Int = 30, offset: Int = 0, musicU: String? = null): HttpCall = HttpCall(
    method = "GET",
    url = "https://music.163.com/api/dj/program/byradio?radioId=$radioId&limit=${limit.coerceIn(1, 100)}&offset=${offset.coerceAtLeast(0)}&asc=false",
    headers = baseHeaders(musicU),
)

fun neteaseCloudSongsCall(musicU: String, limit: Int = 50, offset: Int = 0): HttpCall {
    require(musicU.isNotBlank()) { "请先登录网易云" }
    return HttpCall(
        method = "GET",
        url = "https://music.163.com/api/v1/cloud/get?limit=${limit.coerceIn(1, 100)}&offset=${offset.coerceAtLeast(0)}",
        headers = baseHeaders(musicU),
    )
}

private fun checkedMusicRoot(json: String): JsonObject {
    val root = Json.parseToJsonElement(json).jsonObject
    val code = root["code"]?.jsonPrimitive?.intOrNull
    check(code == null || code == 200) {
        if (code == 301 || code == 302) "登录已失效，请重新登录网易云"
        else contentOrNull(root, "message") ?: contentOrNull(root, "msg") ?: "音乐服务暂不可用（$code）"
    }
    return root
}

fun parsePodcastRadios(json: String): List<MusicCatalogItem> =
    checkedMusicRoot(json)["djRadios"]?.jsonArray.orEmpty().mapNotNull { item ->
        val id = longOrNull(item, "id") ?: return@mapNotNull null
        val name = contentOrNull(item, "name") ?: return@mapNotNull null
        MusicCatalogItem(id, name, firstCover(item), intOrNull(item, "programCount") ?: 0)
    }

fun parsePodcastPrograms(json: String): MusicSongPage {
    val root = checkedMusicRoot(json)
    val songs = root["programs"]?.jsonArray.orEmpty().mapNotNull { program ->
        val main = program.jsonObject["mainSong"] ?: return@mapNotNull null
        val parsed = parseSongElement(main)
        if (parsed.id <= 0L) return@mapNotNull null
        parsed.copy(
            name = contentOrNull(program, "name") ?: parsed.name,
            cover = contentOrNull(program, "coverUrl") ?: parsed.cover,
            duration = longOrNull(program, "duration") ?: parsed.duration,
        )
    }
    return MusicSongPage(songs, root["more"]?.jsonPrimitive?.booleanOrNull ?: false, intOrNull(root, "count") ?: songs.size)
}

fun parseCloudSongs(json: String): MusicSongPage {
    val root = checkedMusicRoot(json)
    val songs = root["data"]?.jsonArray.orEmpty().mapNotNull { entry ->
        val simple = entry.jsonObject["simpleSong"]
        val parsed = simple?.let(::parseSongElement)
        val id = longOrNull(entry, "songId") ?: parsed?.id ?: return@mapNotNull null
        if (id <= 0L) return@mapNotNull null
        MusicSong(
            id = id,
            name = contentOrNull(entry, "songName") ?: parsed?.name.orEmpty(),
            artist = parsed?.artist?.takeIf { it.isNotBlank() } ?: contentOrNull(entry, "artist").orEmpty(),
            album = parsed?.album?.takeIf { it.isNotBlank() } ?: contentOrNull(entry, "album").orEmpty(),
            cover = parsed?.cover.orEmpty(), duration = parsed?.duration ?: 0L,
            artistId = parsed?.artistId ?: 0L, albumId = parsed?.albumId ?: 0L,
        )
    }
    return MusicSongPage(songs, root["hasMore"]?.jsonPrimitive?.booleanOrNull ?: false, intOrNull(root, "count") ?: songs.size)
}
