package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicLyricPhoneticTest {
    @Test fun neteaseRomanizationPreservesOriginalTimingAndTranslation() {
        val lines = parseLyrics("""{"lrc":{"lyric":"[00:01.00]你好\n[00:03.00]世界"},"tlyric":{"lyric":"[00:01.00]Hello\n[00:03.00]World"},"romalrc":{"lyric":"[00:01.10]ni hao\n[00:02.90]shi jie"}}""")
        assertEquals(listOf(1000L, 3000L), lines.map { it.timeMs })
        assertEquals(listOf("你好", "世界"), lines.map { it.text })
        assertEquals(listOf("Hello", "World"), lines.map { it.translation })
        assertEquals(listOf("ni hao", "shi jie"), lines.map { it.phonetic })
    }

    @Test fun yrcWordTimingIsUnchangedByRomanization() {
        val line = parseLyrics("""{"yrc":{"lyric":"[1000,2000](1000,500,0)你(1500,1500,0)好"},"romalrc":{"lyric":"[00:01.05]ni hao"}}""").single()
        assertEquals(1000L, line.timeMs)
        assertEquals("你好", line.text)
        assertEquals(listOf(0L, 500L), line.wordTimes)
        assertEquals("ni hao", line.phonetic)
    }

    @Test fun matchingUsesGlobalNearestPairsWithoutReusingAnOccurrence() {
        val original = listOf(LyricLine(1000, "甲"), LyricLine(1800, "乙"))
        val phonetics = listOf(LyricLine(1700, "yi"), LyricLine(200, "jia"))
        val lines = attachMusicPhonetics(original, phonetics)
        assertEquals(listOf("jia", "yi"), lines.map { it.phonetic })
        assertEquals(listOf("", "yi"), attachMusicPhonetics(original, phonetics.take(1)).map { it.phonetic })
    }

    @Test fun matchingIncludesOneSecondBoundaryButRejectsDistantText() {
        val original = listOf(LyricLine(2000, "甲"), LyricLine(5000, "乙"))
        val lines = attachMusicPhonetics(original, listOf(LyricLine(1000, "jia"), LyricLine(6001, "yi")))
        assertEquals(listOf("jia", ""), lines.map { it.phonetic })
    }

    @Test fun preexistingPhoneticsAndAllOtherFieldsArePreserved() {
        val original = LyricLine(1000, "甲", listOf(0L), translation = "first", phonetic = "existing")
        assertEquals(original, attachMusicPhonetics(listOf(original), listOf(LyricLine(1000, "new"))).single())
    }

    @Test fun phoneticAndTranslationVisibilityAreIndependent() {
        val line = LyricLine(1000, "你好", translation = "Hello", phonetic = "ni hao")
        assertEquals("ni hao", displayedLyricPhonetic(line, true))
        assertEquals("Hello", displayedLyricTranslation(line, true))
        assertNull(displayedLyricPhonetic(line, false))
        assertEquals("Hello", displayedLyricTranslation(line, true))
        assertEquals("ni hao", displayedLyricPhonetic(line, true))
        assertNull(displayedLyricTranslation(line, false))
        assertEquals("你好", line.text)
    }

    @Test fun absentMalformedAndCommentOnlyRomanizationDoNotDiscardOriginal() {
        val original = """"lrc":{"lyric":"[00:01.00]你好"}"""
        for (payload in listOf("", ",\"romalrc\":null", ",\"romalrc\":[]", ",\"romalrc\":{\"lyric\":\"bad\"}", ",\"romalrc\":{\"lyric\":\"[00:01.00]// comment\"}")) {
            val line = parseLyrics("{$original$payload}").single()
            assertEquals("你好", line.text)
            assertEquals("", line.phonetic)
        }
        assertNull(displayedLyricPhonetic(LyricLine(0, "same", phonetic = "same"), true))
        assertNull(displayedLyricPhonetic(LyricLine(0, "text", phonetic = " "), true))
    }

    @Test fun phoneticOnlyPayloadDoesNotPretendToBeOriginalLyrics() {
        assertEquals(emptyList(), parseLyrics("""{"romalrc":{"lyric":"[00:01.00]ni hao"}}"""))
    }

    @Test fun nestedRomanizationAndRepeatedTimestampTagsAreSupported() {
        val lines = parseLyrics("""{"lrc":{"lyric":"[00:01.00][00:03.00]你好"},"romalrc":{"lyric":"[00:01.00][00:03.00]ni hao"}}""")
        assertEquals(listOf(1000L, 3000L), lines.map { it.timeMs })
        assertEquals(listOf("ni hao", "ni hao"), lines.map { it.phonetic })
    }
}
