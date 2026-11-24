package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MusicHistoryTest {
    private fun song(id: Long, name: String = "歌曲$id") =
        MusicSong(id = id, name = name, artist = "歌手", album = "专辑")

    @Test fun newTrackIsPrependedAndDeduplicated() {
        val existing = listOf(MusicHistoryEntry(song(1), 100L), MusicHistoryEntry(song(2), 90L))
        val updated = recordLocalPlayback(existing, song(2), 200L, isNewTrack = true)
        assertEquals(listOf(2L, 1L), updated.map { it.song.id })
        assertEquals(200L, updated.first().playedAt)
    }

    @Test fun resumeOrRestoreIsNotANewListen() {
        val existing = listOf(MusicHistoryEntry(song(1), 100L))
        assertEquals(existing, recordLocalPlayback(existing, song(1), 300L, isNewTrack = false))
        assertEquals(existing, recordLocalPlayback(existing, song(0), 300L, isNewTrack = true))
    }

    @Test fun localHistoryIsBounded() {
        val existing = (1L..9L).map { MusicHistoryEntry(song(it), it) }
        val updated = recordLocalPlayback(existing, song(10), 10L, isNewTrack = true, maximum = 5)
        assertEquals(listOf(10L, 1L, 2L, 3L, 4L), updated.map { it.song.id })
    }

    @Test fun remoteRecentSongsParseNestedSongAndTimestamps() {
        val page = parseRecentSongs(
            """{"code":200,"data":{"total":2,"list":[
               {"playTime":1700,"song":{"id":11,"name":"甲","ar":[{"name":"A"}],"al":{"name":"X"}}},
               {"playedAt":1600,"simpleSong":{"id":12,"name":"乙","artists":[{"name":"B"}],"album":{"name":"Y"}}}]}}"""
        )
        assertTrue(page.remoteEnabled)
        assertEquals(listOf(11L, 12L), page.entries.map { it.song.id })
        assertEquals(listOf(1700L, 1600L), page.entries.map { it.playedAt })
        assertEquals("A", page.entries.first().song.artist)
    }

    @Test fun expiredLoginMustNotBecomeAnEmptyHistory() {
        // 换票失败要和"这个号确实没听过歌"分开：301 抛错，交给界面提示重登。
        assertFailsWith<IllegalStateException> { parseRecentSongs("""{"code":301,"message":"需要登录"}""") }
        val empty = parseRecentSongs("""{"code":200,"data":{"total":0,"list":[]}}""")
        assertTrue(empty.entries.isEmpty())
        assertTrue(empty.remoteEnabled)
        assertFailsWith<IllegalArgumentException> { neteaseRecentSongsCall("") }
    }

    @Test fun mergePrefersNewerTimestampAndKeepsLocalOrderForUnknown() {
        val local = listOf(
            MusicHistoryEntry(song(1), 100L),
            MusicHistoryEntry(song(2), null),
            MusicHistoryEntry(song(3), 50L),
        )
        val remote = listOf(
            MusicHistoryEntry(song(1), 400L),
            MusicHistoryEntry(song(4), 200L),
        )
        val merged = mergeRecentSongs(local, remote)
        assertEquals(listOf(1L, 4L, 3L, 2L), merged.map { it.song.id })
        assertEquals(400L, merged.first().playedAt)
    }

    @Test fun mergeWithoutRemoteReturnsLocalOrder() {
        val local = listOf(MusicHistoryEntry(song(5), 10L), MusicHistoryEntry(song(6), 20L))
        assertEquals(listOf(6L, 5L), mergeRecentSongs(local, null).map { it.song.id })
    }
}
