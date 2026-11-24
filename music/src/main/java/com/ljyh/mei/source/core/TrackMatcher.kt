package com.ljyh.mei.source.core

import com.ljyh.mei.source.api.Track
import java.text.Normalizer
import java.util.Locale

data class MatchCandidate(val track: Track, val confidence: Double, val reasons: List<String>)

/** Conservative recording matching. Missing evidence never counts as agreement. */
class TrackMatcher {
    fun rank(origin: Track, candidates: List<Track>): List<MatchCandidate> = candidates
        .distinctBy { it.ref }
        .mapNotNull { candidate -> score(origin, candidate) }
        .sortedByDescending { it.confidence }

    fun automatic(candidates: List<MatchCandidate>): MatchCandidate? {
        val best = candidates.firstOrNull() ?: return null
        return best.takeIf { it.confidence >= ACCEPT_THRESHOLD &&
            (candidates.size == 1 || best.confidence - candidates[1].confidence >= AMBIGUITY_MARGIN) }
    }

    internal fun alternate(origin: Track, candidates: List<Track>): Track? {
        val title = alternateTitle(origin.title)
        val artists = artistNames(origin)
        val version = versions(origin)
        if (title.isBlank() || artists.isEmpty() || origin.durationMs <= 0 ||
            version.any { it !in SAFE_ALTERNATE_VERSIONS }) return null
        val matches = candidates.distinctBy(Track::ref).filter { candidate ->
            val candidateVersion = versions(candidate)
            candidate.ref != origin.ref && origin.explicit == candidate.explicit &&
                candidateVersion != version && candidateVersion.all { it in SAFE_ALTERNATE_VERSIONS } &&
                alternateTitle(candidate.title) == title && artistNames(candidate) == artists &&
                candidate.durationMs > 0 &&
                kotlin.math.abs(origin.durationMs - candidate.durationMs) <= minOf(45_000.0, origin.durationMs * 0.2)
        }
        return matches.singleOrNull()
    }

    internal fun alternateTitle(title: String): String = normalize(
        title.replace(VERSION_SUFFIX) { suffix ->
            val markers = versionMarkers(suffix.value)
            if (markers.isNotEmpty() && markers.all { it in SAFE_ALTERNATE_VERSIONS }) "" else suffix.value
        },
    )

    private fun score(origin: Track, candidate: Track): MatchCandidate? {
        if (origin.explicit != candidate.explicit || versions(origin) != versions(candidate)) return null
        val originIsrc = validIsrc(origin.isrc)
        val candidateIsrc = validIsrc(candidate.isrc)
        if (!originIsrc.isNullOrBlank() && !candidateIsrc.isNullOrBlank()) {
            if (origin.durationMs > 0 && candidate.durationMs > 0 && !durationsAgree(origin.durationMs, candidate.durationMs)) return null
            return if (originIsrc == candidateIsrc) MatchCandidate(candidate, 1.0, listOf("isrc", "version")) else null
        }
        if (normalize(origin.title) != normalize(candidate.title)) return null
        val artists = artistNames(origin)
        val others = artistNames(candidate)
        if (artists.isEmpty() || others.isEmpty() || artists != others) return null
        if (origin.durationMs <= 0 || candidate.durationMs <= 0) return null
        val difference = kotlin.math.abs(origin.durationMs - candidate.durationMs)
        if (!durationsAgree(origin.durationMs, candidate.durationMs)) return null
        val albumMatches = normalize(origin.album.title).isNotBlank() && normalize(origin.album.title) == normalize(candidate.album.title)
        val confidence = 0.93 + (if (albumMatches) 0.05 else 0.0) - difference.toDouble() / 100_000
        return MatchCandidate(candidate, confidence, listOf("title", "artists", "duration", "version") + if (albumMatches) listOf("album") else emptyList())
    }

    private fun versions(track: Track): Set<String> =
        versionMarkers(track.title + " " + track.version.orEmpty() + " " + track.album.title)

    private fun versionMarkers(value: String): Set<String> {
        val text = normalize(value)
        return VERSION_MARKERS.filter { (_, marker) -> marker.containsMatchIn(text) }.keys
    }
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[\\p{P}\\p{Z}\\s]+"), " ").trim()

    private fun artistNames(track: Track): Set<String> =
        track.artists.map { normalize(it.name) }.filter(String::isNotBlank).toSet()

    private fun validIsrc(value: String?): String? = value?.filter(Char::isLetterOrDigit)?.uppercase(Locale.ROOT)
        ?.takeIf { Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}").matches(it) }

    private fun durationsAgree(origin: Long, candidate: Long): Boolean {
        val difference = kotlin.math.abs(origin - candidate)
        return difference <= 3_000 && difference <= origin * 0.02
    }

    companion object {
        const val ACCEPT_THRESHOLD = 0.91
        const val AMBIGUITY_MARGIN = 0.04
        private val VERSION_MARKERS = listOf("live", "remix", "cover", "instrumental", "acoustic", "karaoke", "伴奏", "翻唱", "现场", "現場", "纯音乐", "純音樂", "重制", "重製", "remaster", "remastered", "sped", "slowed")
            .associateWith { Regex("(^|[^a-z])${Regex.escape(it)}([^a-z]|$)") }
        private val SAFE_ALTERNATE_VERSIONS = setOf("live", "acoustic", "现场", "現場", "重制", "重製", "remaster", "remastered")
        private val VERSION_SUFFIX = Regex("""(?:\([^()]++\)|（[^（）]++）|\[[^\[\]]++\]|\s+[-–—]\s+.+)\s*$""")
    }
}
