package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicLyricTranslationTest {
    @Test fun neteaseTranslationIsAttachedWithoutChangingOriginalTiming() {
        val lines = parseLyrics("""{"lrc":{"lyric":"[00:01.00]Hello\n[00:02.50]World"},"tlyric":{"lyric":"[00:01.00]你好\n[00:02.50]世界"}}""")
        assertEquals(listOf(1000L, 2500L), lines.map { it.timeMs })
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals(listOf("你好", "世界"), lines.map { it.translation })
    }

    @Test fun togglingTranslationHidesAndRestoresTheSameParsedText() {
        val line = parseLyrics("""{"lrc":{"lyric":"[00:01.00]Hello"},"tlyric":{"lyric":"[00:01.00]你好"}}""").single()
        assertEquals("你好", displayedLyricTranslation(line, true))
        assertNull(displayedLyricTranslation(line, false))
        assertEquals("你好", displayedLyricTranslation(line, true))
        assertEquals("Hello", line.text)
    }

    @Test fun wordTimedTranslationsPreferYtlrcWithTlyricFallback() {
        val rich = """"yrc":{"lyric":"<cy timestamp='800'>Hello</cy>"}"""
        val primary = parseLyrics("""{$rich,"ytlrc":{"lyric":"[00:00.80]逐字译文"},"tlyric":{"lyric":"[00:00.80]普通译文"}}""").single()
        assertEquals("逐字译文", primary.translation)
        val fallback = parseLyrics("""{$rich,"ytlrc":{"lyric":""},"tlyric":{"lyric":"[00:00.80]普通译文"}}""").single()
        assertEquals("普通译文", fallback.translation)
    }

    @Test fun repeatedTimestampTagsCreateDistinctLinesAndTranslations() {
        val lines = parseLyrics("""{"lrc":{"lyric":"[00:01.00][00:03.00]Refrain"},"tlyric":{"lyric":"[00:01.00][00:03.00]副歌"}}""")
        assertEquals(listOf(1000L, 3000L), lines.map { it.timeMs })
        assertEquals(listOf("副歌", "副歌"), lines.map { it.translation })
    }

    @Test fun classicTranslationsFollowAndroidNearestTimestampMatching() {
        val lines = parseLyrics("""{"lrc":{"lyric":"[00:01.00]Hello\n[00:03.00]World"},"tlyric":{"lyric":"[00:01.10]Hello\n[00:02.90]世界"}}""")
        assertEquals(listOf("", "世界"), lines.map { it.translation })
        assertNull(displayedLyricTranslation(lines.first(), true))
    }

    @Test fun neteaseYrcKeepsWordTimingAndMatchesNearbyTranslationOnce() {
        val lines = parseLyrics("""{"yrc":{"lyric":"[1000,2000](1000,500,0)Hel(1500,1500,0)lo\n[5000,1500](5000,1500,0)World"},"ytlrc":{"lyric":"[00:01.10]你好\n[00:05.15]世界\n[00:20.00]不可错配"}}""")
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals(listOf(0L, 0L, 0L, 500L, 500L), lines.first().wordTimes)
        assertEquals(listOf("你好", "世界"), lines.map { it.translation })
        assertEquals(listOf(1000L, 5000L), lines.map { it.timeMs })
    }

    @Test fun invalidRichPayloadFallsBackToUsableLrc() {
        val line = parseLyrics("""{"yrc":{"lyric":"invalid"},"lrc":{"lyric":"[00:01.00]Hello"},"tlyric":{"lyric":"[00:01.10]你好"}}""").single()
        assertEquals("Hello", line.text)
        assertEquals("你好", line.translation)
    }

    @Test fun absentTranslationAndMalformedRepliesRemainSafe() {
        assertEquals("", parseLyrics("""{"lrc":{"lyric":"[00:01.00]Hello"}}""").single().translation)
        assertEquals(emptyList(), parseLyrics("not JSON"))
        assertNull(displayedLyricTranslation(LyricLine(0, "Hello", translation = " "), true))
    }
}
