package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MusicQueueSlotsTest {
    @Test fun identicalSongsHaveDistinctQueueOccurrences() {
        val slots = MusicQueueSlots().apply { reset(3) }
        val first = requireNotNull(slots.keyAt(0))
        val second = requireNotNull(slots.keyAt(1))
        assertNotEquals(first, second)
        assertEquals(1, slots.indexOf(second))
        assertNull(slots.keyAt(-1))
        assertNull(slots.keyAt(3))
    }

    @Test fun pendingSelectionFollowsMovesAndInsertions() {
        val slots = MusicQueueSlots().apply { reset(3) }
        val pending = requireNotNull(slots.keyAt(1))
        slots.move(1, 0)
        assertEquals(0, slots.indexOf(pending))
        slots.insert(0)
        assertEquals(1, slots.indexOf(pending))
        slots.remove(0)
        assertEquals(0, slots.indexOf(pending))
        slots.move(0, 2)
        assertEquals(2, slots.indexOf(pending))
    }

    @Test fun removingTargetDoesNotFallBackToAnIdenticalSong() {
        val slots = MusicQueueSlots().apply { reset(3) }
        val retained = requireNotNull(slots.keyAt(0))
        val removed = requireNotNull(slots.keyAt(1))
        slots.remove(1)
        assertEquals(-1, slots.indexOf(removed))
        assertEquals(0, slots.indexOf(retained))
    }

    @Test fun replacingQueueInvalidatesAllPendingSelections() {
        val slots = MusicQueueSlots().apply { reset(2) }
        val pending = requireNotNull(slots.keyAt(1))
        slots.reset(2)
        assertEquals(-1, slots.indexOf(pending))
        assertNotEquals(pending, slots.keyAt(1))
    }

    @Test fun everyMoveAndRemovalMatchesTheSongPositionMapping() {
        val songs = List(4) { MusicSong(1, "Identical", "Artist", "Album") }
        for (current in songs.indices) for (from in songs.indices) for (to in songs.indices) {
            if (from == to) continue
            val slots = MusicQueueSlots().apply { reset(songs.size) }
            val pending = requireNotNull(slots.keyAt(current))
            val edit = requireNotNull(moveMusicQueueItem(songs, current, from, to))
            slots.move(from, to)
            assertEquals(edit.index, slots.indexOf(pending))
        }
        for (current in songs.indices) for (target in songs.indices) {
            val slots = MusicQueueSlots().apply { reset(songs.size) }
            val pending = requireNotNull(slots.keyAt(current))
            val edit = requireNotNull(removeMusicQueueItem(songs, current, target))
            slots.remove(target)
            assertEquals(if (current == target) -1 else edit.index, slots.indexOf(pending))
        }
    }
}
