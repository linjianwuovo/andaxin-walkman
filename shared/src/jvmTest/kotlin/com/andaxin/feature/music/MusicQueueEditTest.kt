package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicQueueEditTest {
    private val queue = listOf(
        MusicSong(1, "A", "Artist", "Album"),
        MusicSong(2, "B", "Artist", "Album"),
        MusicSong(3, "C", "Artist", "Album"),
    )

    @Test fun lastItemAndInvalidRemovalsAreRejected() {
        assertNull(removeMusicQueueItem(queue.take(1), 0, 0))
        assertNull(removeMusicQueueItem(emptyList(), -1, 0))
        assertNull(removeMusicQueueItem(queue, 1, -1))
        assertNull(removeMusicQueueItem(queue, 1, queue.size))
        assertNull(removeMusicQueueItem(queue, -1, 0))
    }

    @Test fun removalBeforeOrAfterCurrentPreservesCurrentSong() {
        for (target in listOf(0, 2)) {
            val edit = requireNotNull(removeMusicQueueItem(queue, 1, target))
            assertEquals(queue[1], edit.queue[edit.index])
            assertEquals(2, edit.queue.size)
        }
    }

    @Test fun removingCurrentSelectsNextOrPreviousAtEnd() {
        val middle = requireNotNull(removeMusicQueueItem(queue, 1, 1))
        assertEquals(queue[2], middle.queue[middle.index])
        val last = requireNotNull(removeMusicQueueItem(queue, 2, 2))
        assertEquals(queue[1], last.queue[last.index])
        val first = requireNotNull(removeMusicQueueItem(queue, 0, 0))
        assertEquals(queue[1], first.queue[first.index])
    }

    @Test fun removalTargetsPositionRatherThanFirstMatchingId() {
        val duplicate = queue[0].copy(name = "Same ID, different occurrence")
        val edit = requireNotNull(removeMusicQueueItem(listOf(queue[0], duplicate, queue[2]), 1, 1))
        assertEquals(listOf(queue[0], queue[2]), edit.queue)
        assertEquals(queue[2], edit.queue[edit.index])
    }

    @Test fun reorderingPreservesCurrentForEveryValidMove() {
        for (current in queue.indices) for (from in queue.indices) for (to in queue.indices) {
            if (from == to) continue
            val edit = requireNotNull(moveMusicQueueItem(queue, current, from, to))
            assertEquals(queue[current], edit.queue[edit.index], "current=$current from=$from to=$to")
            assertEquals(queue[from], edit.queue[to])
            assertEquals(queue.toSet(), edit.queue.toSet())
        }
    }

    @Test fun invalidMovesAreRejectedWithoutMutatingInput() {
        assertNull(moveMusicQueueItem(queue, 1, -1, 0))
        assertNull(moveMusicQueueItem(queue, 1, 0, queue.size))
        assertNull(moveMusicQueueItem(queue, -1, 0, 1))
        assertNull(moveMusicQueueItem(queue, 1, 1, 1))
        assertEquals(listOf("A", "B", "C"), queue.map { it.name })
    }

    @Test fun editedQueueCanBePersistedAndRestoredPaused() {
        val moved = requireNotNull(moveMusicQueueItem(queue, 1, 1, 0))
        val removed = requireNotNull(removeMusicQueueItem(moved.queue, moved.index, 2))
        val restored = restoreMusicPlayback(decodeQueue(encodeQueue(removed.queue)), removed.index, 42.5)
        assertEquals(queue[1], restored.current)
        assertEquals(42.5, restored.positionSeconds)
        assertEquals(false, restored.playing)
    }
}
