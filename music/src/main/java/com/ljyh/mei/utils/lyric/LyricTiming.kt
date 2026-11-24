package com.ljyh.mei.utils.lyric

import com.ljyh.mei.ui.model.LyricData
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.model.synced.UncheckedSyncedLine

/** Renderer time may stop changing only after every visible animation has saturated. */
internal class KaraokeRenderTimeWindow(
    val first: Int,
    val last: Int,
    private val safeRawFirst: Int,
    private val safeRawLast: Int,
) {
    fun sample(timeMs: Int): Int {
        // lyrics-ui's simple float uses Int subtraction. Preserve its original
        // arithmetic for unusual clock values which would overflow that subtraction.
        if (timeMs < safeRawFirst || timeMs > safeRawLast) return timeMs
        return timeMs.coerceIn(first, last)
    }
}

/**
 * Static bounds audited against lyrics-ui 1.0.19: karaoke fill, 700ms simple
 * float, word/glyph/phonetic animations, and nested vocals' inclusive +/-600ms
 * visibility. Focus, scrolling and breathing dots must keep the original clock.
 */
internal fun karaokeRenderTimeWindow(line: KaraokeLine): KaraokeRenderTimeWindow? {
    val nested = (line as? KaraokeLine.MainKaraokeLine)?.accompanimentLines.orEmpty()
    val renderLines = listOf(line) + nested
    var first = line.start.toLong()
    var last = line.end.toLong()
    var earliestSyllable = Long.MAX_VALUE
    var latestSyllable = Long.MIN_VALUE
    for (renderLine in renderLines) {
        if (renderLine.end <= renderLine.start || renderLine.syllables.isEmpty()) return null
        first = minOf(first, renderLine.start.toLong())
        last = maxOf(last, renderLine.end.toLong())
        if (renderLine !== line) {
            first = minOf(first, renderLine.start.toLong() - 600L)
            last = maxOf(last, renderLine.end.toLong() + 600L)
        }
        for (syllable in renderLine.syllables) {
            if (syllable.end <= syllable.start || syllable.content.isEmpty()) return null
            val start = syllable.start.toLong()
            earliestSyllable = minOf(earliestSyllable, start)
            latestSyllable = maxOf(latestSyllable, start)
            first = minOf(first, start)
            last = maxOf(last, syllable.end.toLong(), start + 700L)
        }
    }

    // Word animation interpolates timestamps with Float. Restrict this optional
    // cache to exact-integer Float timings and spans (2^24), where its multiply,
    // subtract, ratio and add chain has less than 8ms accumulated rounding error.
    // A 16ms margin also lies beyond the inclusive vocal-visibility boundaries.
    val exactFloatInteger = 1L shl 24
    first -= 16L
    last += 16L
    if (first < -exactFloatInteger || last > exactFloatInteger ||
        last - first > exactFloatInteger
    ) return null

    val safeRawFirst = maxOf(Int.MIN_VALUE.toLong(), Int.MIN_VALUE.toLong() + latestSyllable)
    val safeRawLast = minOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + earliestSyllable)
    if (first < safeRawFirst || last > safeRawLast) return null
    return KaraokeRenderTimeWindow(first.toInt(), last.toInt(), safeRawFirst.toInt(), safeRawLast.toInt())
}

/** Derive display timings from the original snapshot without changing its metadata. */
internal fun LyricData.withTimingOffset(offsetMs: Int): LyricData {
    if (offsetMs == 0) return this
    return copy(lyricLine = lyricLine.copy(lines = lyricLine.lines.map { it.shiftTiming(offsetMs) }))
}

private fun ISyncedLine.shiftTiming(offsetMs: Int): ISyncedLine {
    return when (this) {
        is SyncedLine -> copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
        is UncheckedSyncedLine -> copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
        is KaraokeLine.MainKaraokeLine -> copy(
            start = start.shiftTiming(offsetMs),
            end = end.shiftTiming(offsetMs),
            syllables = syllables.map { it.shiftTiming(offsetMs) },
            accompanimentLines = accompanimentLines?.map { it.shiftTiming(offsetMs) },
        )
        is KaraokeLine.AccompanimentKaraokeLine -> shiftTiming(offsetMs)
        else -> throw IllegalArgumentException("Unsupported lyric line type: ${javaClass.name}")
    }
}

private fun KaraokeLine.AccompanimentKaraokeLine.shiftTiming(
    offsetMs: Int,
): KaraokeLine.AccompanimentKaraokeLine {
    return copy(
        start = start.shiftTiming(offsetMs),
        end = end.shiftTiming(offsetMs),
        syllables = syllables.map { it.shiftTiming(offsetMs) },
    )
}

private fun KaraokeSyllable.shiftTiming(offsetMs: Int): KaraokeSyllable {
    return copy(start = start.shiftTiming(offsetMs), end = end.shiftTiming(offsetMs))
}

private fun Int.shiftTiming(offsetMs: Int): Int {
    val shifted = toLong() + offsetMs
    // Reject the entire derivation rather than clipping individual timestamps and durations.
    require(shifted in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "Lyric timing offset exceeds the supported range"
    }
    return shifted.toInt()
}

private val LYRIC_OFFSET_TAG_REGEX = Regex(
    """^\s*\[\s*offset\s*:\s*([+-]?\d+)\s*]\s*$""",
    RegexOption.IGNORE_CASE,
)
private const val MAX_HEADER_OFFSET_MS = 300_000

/**
 * Parses `[offset:+/-ms]` from LRC/QRC/YRC headers.
 * Per the LRC/QRC specification, a positive `[offset:500]` advances lyrics earlier (`time - 500`).
 */
internal fun extractLyricHeaderOffsetMs(raw: String): Int {
    for (line in raw.lineSequence()) {
        val match = LYRIC_OFFSET_TAG_REGEX.matchEntire(line) ?: continue
        val parsed = match.groupValues[1].toLongOrNull() ?: continue
        if (parsed in -MAX_HEADER_OFFSET_MS.toLong()..MAX_HEADER_OFFSET_MS.toLong()) {
            return parsed.toInt()
        }
    }
    return 0
}

internal fun extractLyricHeaderOffsetMs(lines: Iterable<String>): Int {
    for (line in lines) {
        val match = LYRIC_OFFSET_TAG_REGEX.matchEntire(line) ?: continue
        val parsed = match.groupValues[1].toLongOrNull() ?: continue
        if (parsed in -MAX_HEADER_OFFSET_MS.toLong()..MAX_HEADER_OFFSET_MS.toLong()) {
            return parsed.toInt()
        }
    }
    return 0
}

internal fun shiftHeaderTimestamp(timestampMs: Int, headerOffsetMs: Int): Int {
    if (headerOffsetMs == 0) return timestampMs
    return (timestampMs.toLong() - headerOffsetMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

internal fun List<KaraokeLine>.applyHeaderOffset(headerOffsetMs: Int): List<KaraokeLine> {
    if (headerOffsetMs == 0) return this
    return map { line -> line.shiftByHeaderOffset(headerOffsetMs) }
}

private fun KaraokeLine.shiftByHeaderOffset(headerOffsetMs: Int): KaraokeLine = when (this) {
    is KaraokeLine.MainKaraokeLine -> {
        val shiftedSyllables = syllables.map { it.shiftByHeaderOffset(headerOffsetMs) }
        val shiftedAccompaniments = accompanimentLines?.map { it.shiftAccompanimentByHeaderOffset(headerOffsetMs) }
        val newStart = shiftedSyllables.minOfOrNull { it.start } ?: shiftHeaderTimestamp(start, headerOffsetMs)
        val newEnd = maxOf(
            newStart + (end - start).coerceIn(0, 1),
            shiftedSyllables.maxOfOrNull { it.end } ?: shiftHeaderTimestamp(end, headerOffsetMs),
        )
        copy(
            syllables = shiftedSyllables,
            accompanimentLines = shiftedAccompaniments,
            start = newStart,
            end = newEnd,
        )
    }
    is KaraokeLine.AccompanimentKaraokeLine -> shiftAccompanimentByHeaderOffset(headerOffsetMs)
}

private fun KaraokeLine.AccompanimentKaraokeLine.shiftAccompanimentByHeaderOffset(
    headerOffsetMs: Int,
): KaraokeLine.AccompanimentKaraokeLine {
    val shiftedSyllables = syllables.map { it.shiftByHeaderOffset(headerOffsetMs) }
    val newStart = shiftedSyllables.minOfOrNull { it.start } ?: shiftHeaderTimestamp(start, headerOffsetMs)
    val newEnd = maxOf(
        newStart + (end - start).coerceIn(0, 1),
        shiftedSyllables.maxOfOrNull { it.end } ?: shiftHeaderTimestamp(end, headerOffsetMs),
    )
    return copy(syllables = shiftedSyllables, start = newStart, end = newEnd)
}

private fun KaraokeSyllable.shiftByHeaderOffset(headerOffsetMs: Int): KaraokeSyllable {
    val newStart = shiftHeaderTimestamp(start, headerOffsetMs)
    val minDuration = (end - start).coerceIn(0, 1)
    val newEnd = maxOf(newStart + minDuration, shiftHeaderTimestamp(end, headerOffsetMs))
    return copy(start = newStart, end = newEnd)
}
