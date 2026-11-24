package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal fun normalizedAppleArtistName(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(whitespace, " ")
        .trim()

internal fun hasAppleArtistEvidence(sourceSongs: List<Track>): Boolean {
    val songs = sourceSongs.recordingEvidence()
    return songs.any { it.isrc != null } || songs.map { it.workTitle }.distinct().size >= 2
}

/** Candidate songs must already be restricted to recordings credited to the candidate's catalog ID. */
internal fun confirmsAppleArtist(sourceSongs: List<Track>, candidateSongs: List<AppleArtistSong>): Boolean {
    val source = sourceSongs.recordingEvidence()
    val candidates = candidateSongs.mapNotNull { recordingEvidence(it.title, it.album, it.durationMs, it.isrc) }
    val matchedWorks = mutableSetOf<String>()
    val matchedRecordingIds = mutableSetOf<String>()
    for (song in source) {
        for (other in candidates) {
            if (song.editions != other.editions || durationsConflict(song, other)) continue
            if (song.isrc != null && other.isrc != null) {
                if (song.isrc == other.isrc) return true
                continue
            }
            if (song.title != other.title) continue
            val sameAlbum = song.album != null && song.album == other.album
            val sameDuration = song.durationMs != null && other.durationMs != null
            if ((!sameAlbum && !sameDuration) || song.workTitle in matchedWorks) continue
            val recordingId = song.isrc ?: other.isrc
            if (recordingId != null && !matchedRecordingIds.add(recordingId)) continue
            matchedWorks += song.workTitle
            if (matchedWorks.size >= 2) return true
        }
    }
    return false
}

private data class RecordingEvidence(
    val title: String,
    val workTitle: String,
    val album: String?,
    val durationMs: Long?,
    val isrc: String?,
    val editions: Set<String>,
)

private fun List<Track>.recordingEvidence(): List<RecordingEvidence> =
    distinctBy(Track::ref).mapNotNull { recordingEvidence(it.title, it.album.title, it.durationMs, it.isrc, it.version) }

private fun recordingEvidence(
    title: String,
    album: String?,
    durationMs: Long?,
    isrc: String?,
    version: String? = null,
): RecordingEvidence? {
    val normalizedTitle = normalizedAppleArtistName(title)
    val normalizedAlbum = normalizedAppleArtistName(album.orEmpty()).replace(releaseSuffix, "")
    val (workTitle, titleEditions) = splitEditions(normalizedTitle)
    if (workTitle.isBlank()) return null
    val albumEditions = splitEditions(normalizedAlbum).second
    val explicitEdition = normalizedAppleArtistName(version.orEmpty())
    val editions = (titleEditions + albumEditions + explicitEdition) - originalEditions
    if (editions.any { nonOriginalRecording.containsMatchIn(it) }) return null

    // A self-titled single supplies no independent title evidence.
    val albumEvidence = normalizedAlbum.takeUnless {
        it in unknownAlbums || it == normalizedTitle || it == workTitle
    }
    val duration = durationMs?.takeIf { it > 0 }
    val recordingId = isrc?.filter(Char::isLetterOrDigit)?.uppercase(Locale.ROOT)
        ?.takeIf(isrcFormat::matches)
    if (albumEvidence == null && duration == null && recordingId == null) return null
    return RecordingEvidence(normalizedTitle, workTitle, albumEvidence, duration, recordingId, editions)
}

private fun splitEditions(value: String): Pair<String, Set<String>> {
    var base = value
    val editions = mutableSetOf<String>()
    while (true) {
        val suffix = editionSuffix.find(base) ?: break
        val label = suffix.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: break
        if (!editionMarker.containsMatchIn(label)) break
        editions += label
        base = base.substring(0, suffix.range.first).trimEnd()
    }
    return base to editions
}

private fun durationsConflict(song: RecordingEvidence, other: RecordingEvidence): Boolean {
    val first = song.durationMs ?: return false
    val second = other.durationMs ?: return false
    return abs(first - second) > minOf(3_000.0, minOf(first, second) * 0.02)
}

private val whitespace = Regex("[\\p{Z}\\s]+")
private val isrcFormat = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
private val releaseSuffix = Regex("""\s+[-–—]\s+(?:single|ep)$""")
private val editionSuffix = Regex("""\s*(?:\(([^()]+)\)|\[([^\[\]]+)\]|【([^【】]+)】|\s+[-–—]\s+(.+))$""")
private val nonOriginalRecording = Regex(
    """(?<![a-z])(?:cover|inst|instrumental|karaoke|off[ -]vocal|tribute)(?![a-z])""" +
        """|翻唱|伴奏|伴唱|純音樂|纯音乐|カラオケ|インスト""",
)
private val editionMarker = Regex(
    """${nonOriginalRecording.pattern}""" +
        """|(?<![a-z])(?:live|dj|remix|mix|remaster(?:ed)?|acoustic|version|edit|sped|slowed|demo|take)(?![a-z])""" +
        """|現場|现场|重製|重制|版$|ライブ|バージョン""",
)
private val originalEditions = setOf("", "original", "original version", "原版", "原曲")
private val unknownAlbums = setOf("", "unknown", "unknown album", "未知", "未知专辑", "未知專輯")
