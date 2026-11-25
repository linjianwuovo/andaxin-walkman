package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MusicPlaybackRestoreTest {
    private val queue = listOf(
        MusicSong(1, "甲", "歌手", "专辑"),
        MusicSong(2, "乙", "歌手", "专辑"),
    )

    @Test fun restoringQueueAndPositionNeverStartsPlayback() {
        val restored = restoreMusicPlayback(queue, 1, 42.5)
        assertEquals(queue, restored.queue)
        assertEquals(queue[1], restored.current)
        assertEquals(1, restored.index)
        assertEquals(42.5, restored.positionSeconds)
        assertFalse(restored.playing)
    }

    @Test fun positionRestoreCanBeDisabledWithoutDiscardingQueue() {
        val restored = restoreMusicPlayback(queue, 1, 42.5, restorePosition = false)
        assertEquals(queue, restored.queue)
        assertEquals(queue[1], restored.current)
        assertEquals(0.0, restored.positionSeconds)
        assertFalse(restored.playing)
    }

    @Test fun emptyQueueHasNoCurrentSongOrPosition() {
        val restored = restoreMusicPlayback(emptyList(), 20, 42.5)
        assertEquals(emptyList(), restored.queue)
        assertEquals(-1, restored.index)
        assertNull(restored.current)
        assertEquals(0.0, restored.positionSeconds)
        assertFalse(restored.playing)
    }

    @Test fun restoredIndexIsClampedToSavedQueue() {
        assertEquals(0, restoreMusicPlayback(queue, -1, 0.0).index)
        assertEquals(queue.lastIndex, restoreMusicPlayback(queue, Int.MAX_VALUE, 0.0).index)
        assertEquals(0, restoreMusicPlayback(queue.take(1), 10, 0.0).index)
    }

    @Test fun invalidPositionsAreResetBeforeNativeSeek() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { position ->
            assertEquals(0.0, validMusicPosition(position))
            assertEquals(0.0, restoreMusicPlayback(queue, 0, position).positionSeconds)
        }
    }

    @Test fun finiteNonNegativePositionsArePreserved() {
        listOf(0.0, 0.25, 42.5, 86_400.0).forEach { position ->
            assertEquals(position, validMusicPosition(position))
        }
    }

    @Test fun persistedQueueRoundTripStillRestoresPaused() {
        val restored = restoreMusicPlayback(decodeQueue(encodeQueue(queue)), 1, 15.0)
        assertEquals(queue[1], restored.current)
        assertEquals(15.0, restored.positionSeconds)
        assertFalse(restored.playing)
    }
}
