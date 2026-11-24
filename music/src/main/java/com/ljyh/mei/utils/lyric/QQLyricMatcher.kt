package com.ljyh.mei.utils.lyric

import com.ljyh.mei.data.model.MediaMetadata
import com.ljyh.mei.data.model.qq.u.SearchResult
import com.ljyh.mei.data.model.room.QQSong
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal object QQLyricMatcher {
    private val PARENTHESES_REGEX = Regex("""[\(（][^)）]*[\)）]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val NORMALIZE_PUNCTUATION_REGEX = Regex("""[\p{P}\p{Z}\s]+""")
    private val ARTIST_SPLIT_REGEX = Regex("""[,，/／&＆、;；]+""")
    private val VERSION_MARKERS = listOf(
        "live", "remix", "cover", "instrumental", "acoustic", "karaoke",
        "伴奏", "翻唱", "现场", "纯音乐", "重制", "remaster", "sped", "slowed",
    )

    fun cleanTitle(title: String): String = title
        .replace(PARENTHESES_REGEX, "")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    fun searchKeywords(metadata: MediaMetadata): List<String> {
        val title = metadata.title.trim()
        val cleanedTitle = cleanTitle(title)
        val allArtists = metadata.artists.mapNotNull { it.name.trim().takeIf(String::isNotEmpty) }
            .joinToString(" ")
        val primaryArtist = metadata.artists.firstOrNull()?.name?.trim().orEmpty()
        return buildList {
            if (title.isNotEmpty() && allArtists.isNotEmpty()) add("$title $allArtists")
            if (cleanedTitle.isNotEmpty() && cleanedTitle != title && allArtists.isNotEmpty()) {
                add("$cleanedTitle $allArtists")
            }
            if (title.isNotEmpty() && primaryArtist.isNotEmpty() && primaryArtist != allArtists) {
                add("$title $primaryArtist")
            }
            if (cleanedTitle.isNotEmpty() && primaryArtist.isNotEmpty() && primaryArtist != allArtists) {
                add("$cleanedTitle $primaryArtist")
            }
            if (title.isNotEmpty()) add(title)
            if (cleanedTitle.isNotEmpty() && cleanedTitle != title) add(cleanedTitle)
        }.distinct()
    }

    fun selectBestMatch(
        metadata: MediaMetadata,
        candidates: List<SearchResult.Request.Data.Body.ItemSong>,
    ): SearchResult.Request.Data.Body.ItemSong? {
        if (candidates.isEmpty()) return null
        val targetDurationSec = metadata.duration / 1000L
        val originTitle = normalize(metadata.title)
        val originCleanTitle = normalize(cleanTitle(metadata.title))
        val originSubtitles = extractParentheticalParts(metadata.title)
        val originArtists = metadata.artists.flatMap { splitNormalizedArtists(it.name) }.toSet()
        val originAlbum = normalize(metadata.album.title)
        val originVersions = versionMarkers("${metadata.title} ${metadata.album.title}")

        data class Scored(val song: SearchResult.Request.Data.Body.ItemSong, val score: Double)

        return candidates.take(15).mapNotNull { candidate ->
            if (targetDurationSec > 0 && candidate.interval > 0 &&
                abs(targetDurationSec - candidate.interval) > 5L
            ) {
                return@mapNotNull null
            }
            val rawCandidateTitle = candidate.title.ifBlank { candidate.name }
            val candidateVersions = versionMarkers(
                "$rawCandidateTitle ${candidate.subtitle} ${candidate.album.title.ifBlank { candidate.album.name }}",
            )
            if (originVersions != candidateVersions) return@mapNotNull null

            val candidateArtists = candidate.singer.flatMap { splitNormalizedArtists(it.name) }.toSet()
            if (originArtists.isNotEmpty()) {
                if (candidateArtists.isEmpty() || !artistsOverlap(originArtists, candidateArtists)) {
                    return@mapNotNull null
                }
            }

            val candTitle = normalize(rawCandidateTitle)
            val candCleanTitle = normalize(cleanTitle(rawCandidateTitle))
            val candSubtitle = normalize(candidate.subtitle)
            val candParens = extractParentheticalParts(rawCandidateTitle)

            val exactTitle = originTitle.isNotEmpty() && (candTitle == originTitle || normalize(candidate.name) == originTitle)
            val cleanTitleMatch = originCleanTitle.isNotEmpty() &&
                (candCleanTitle == originCleanTitle || candTitle == originCleanTitle)
            val translatedTitleMatch = (candSubtitle.isNotEmpty() && (candSubtitle == originCleanTitle || candSubtitle in originSubtitles)) ||
                (originCleanTitle.isNotEmpty() && originCleanTitle in candParens) ||
                (candCleanTitle.isNotEmpty() && candCleanTitle in originSubtitles)

            if (!exactTitle && !cleanTitleMatch && !translatedTitleMatch) return@mapNotNull null

            val exactArtistSet = originArtists.isNotEmpty() && originArtists == candidateArtists
            val candidateAlbum = normalize(candidate.album.title.ifBlank { candidate.album.name })
            val albumMatch = originAlbum.isNotEmpty() && originAlbum == candidateAlbum
            val durationDiff = if (targetDurationSec > 0 && candidate.interval > 0) {
                abs(targetDurationSec - candidate.interval).toDouble()
            } else 2.5

            val score = (if (exactTitle) 40.0 else if (cleanTitleMatch) 30.0 else 20.0) +
                (if (exactArtistSet) 25.0 else 15.0) +
                (if (albumMatch) 10.0 else 0.0) -
                durationDiff
            Scored(candidate, score)
        }.maxByOrNull { it.score }?.song
    }

    fun isStoredSongCompatible(metadata: MediaMetadata, stored: QQSong): Boolean {
        val targetDurationSec = metadata.duration / 1000L
        if (targetDurationSec > 0 && stored.duration > 0 && abs(targetDurationSec - stored.duration) > 5L) {
            return false
        }
        val originVersions = versionMarkers("${metadata.title} ${metadata.album.title}")
        val storedVersions = versionMarkers("${stored.title} ${stored.album}")
        if (originVersions != storedVersions) return false

        val originArtists = metadata.artists.flatMap { splitNormalizedArtists(it.name) }.toSet()
        if (originArtists.isNotEmpty()) {
            val storedArtists = splitNormalizedArtists(stored.artist).toSet()
            if (storedArtists.isEmpty() || !artistsOverlap(originArtists, storedArtists)) {
                return false
            }
        }
        return true
    }

    private fun artistsOverlap(origin: Set<String>, candidate: Set<String>): Boolean =
        origin.any { o ->
            candidate.any { c ->
                o == c || (o.length >= 2 && c.length >= 2 && (c.contains(o) || o.contains(c)))
            }
        }

    private fun extractParentheticalParts(text: String): Set<String> =
        PARENTHESES_REGEX.findAll(text)
            .map { it.value.substring(1, it.value.length - 1) }
            .map(::normalize)
            .filter(String::isNotEmpty)
            .toSet()

    private fun splitNormalizedArtists(raw: String): List<String> =
        raw.split(ARTIST_SPLIT_REGEX)
            .flatMap { token ->
                val full = normalize(token)
                val stripped = normalize(cleanTitle(token))
                val parens = extractParentheticalParts(token)
                listOf(full, stripped) + parens
            }
            .filter(String::isNotEmpty)
            .distinct()

    private fun versionMarkers(text: String): Set<String> {
        val normalized = normalize(text)
        return VERSION_MARKERS.filter { marker ->
            if (marker.all { it in 'a'..'z' }) {
                Regex("(^|[^a-z])${Regex.escape(marker)}([^a-z]|$)").containsMatchIn(normalized)
            } else {
                normalized.contains(marker)
            }
        }.toSet()
    }

    internal fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(NORMALIZE_PUNCTUATION_REGEX, " ")
            .trim()
}
