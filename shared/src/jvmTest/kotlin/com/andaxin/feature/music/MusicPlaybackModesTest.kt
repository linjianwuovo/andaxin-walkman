package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals

class MusicPlaybackModesTest {
    @Test fun legacyModeMappingsRetainMeaningAndNewDefaultMatchesAndroid() {
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_OFF, false), migrateLegacyMusicPlaybackMode(0))
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_ONE, false), migrateLegacyMusicPlaybackMode(1))
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_ALL, true), migrateLegacyMusicPlaybackMode(2))
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_ALL, false), migrateLegacyMusicPlaybackMode(3))
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_ALL, false), migrateLegacyMusicPlaybackMode(99))
    }

    @Test fun modeCycleMatchesAndroidWithLoopPreferenceEnabled() {
        val all = MusicPlaybackMode(MUSIC_REPEAT_ALL, false)
        val shuffle = nextMusicPlaybackMode(all, true)
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_ALL, true), shuffle)
        val one = nextMusicPlaybackMode(shuffle, true)
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_ONE, false), one)
        assertEquals(all, nextMusicPlaybackMode(one, true))
    }

    @Test fun modeCycleMatchesAndroidWithLoopPreferenceDisabled() {
        val off = MusicPlaybackMode(MUSIC_REPEAT_OFF, false)
        val shuffle = nextMusicPlaybackMode(off, false)
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_OFF, true), shuffle)
        val one = nextMusicPlaybackMode(shuffle, false)
        assertEquals(MusicPlaybackMode(MUSIC_REPEAT_ONE, false), one)
        assertEquals(off, nextMusicPlaybackMode(one, false))
    }

    @Test fun queueRepeatControlCyclesOffAllOneWithoutChangingShuffle() {
        var mode = MusicPlaybackMode(MUSIC_REPEAT_OFF, true)
        for (expected in listOf(MUSIC_REPEAT_ALL, MUSIC_REPEAT_ONE, MUSIC_REPEAT_OFF)) {
            mode = mode.copy(repeatMode = nextMusicRepeatMode(mode.repeatMode))
            assertEquals(expected, mode.repeatMode)
            assertEquals(true, mode.shuffleEnabled)
        }
        assertEquals(MUSIC_REPEAT_OFF, nextMusicRepeatMode(99))
    }

    @Test fun previousUsesPreferenceAndThreeSecondBoundary() {
        assertEquals(0, previousMusicQueueIndex(1, 3, 20.0, true))
        assertEquals(1, previousMusicQueueIndex(1, 3, 3.01, false))
        assertEquals(0, previousMusicQueueIndex(1, 3, 3.0, false))
        assertEquals(0, previousMusicQueueIndex(1, 3, 0.5, false))
        assertEquals(0, previousMusicQueueIndex(0, 3, 40.0, true))
    }

    @Test fun invalidPlaybackPositionRestartsCurrentInsteadOfSelectingInvalidIndex() {
        for (position in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(1, previousMusicQueueIndex(1, 3, position, false))
            assertEquals(0, previousMusicQueueIndex(1, 3, position, true))
        }
    }

    @Test fun manualNavigationIgnoresRepeatOneButRepeatAllWraps() {
        assertEquals(2, musicQueueNavigationIndex(1, 3, MUSIC_REPEAT_ONE, true))
        assertEquals(null, musicQueueNavigationIndex(2, 3, MUSIC_REPEAT_ONE, true))
        assertEquals(0, musicQueueNavigationIndex(2, 3, MUSIC_REPEAT_ALL, true))
        assertEquals(2, previousMusicQueueIndex(0, 3, 20.0, true, repeatMode = MUSIC_REPEAT_ALL))
        assertEquals(0, previousMusicQueueIndex(0, 3, 20.0, true, repeatMode = MUSIC_REPEAT_ONE))
    }

    @Test fun shuffleUsesStableOrderAndPreviousReversesNavigation() {
        val order = listOf(1, 2, 0)
        assertEquals(2, musicQueueNavigationIndex(1, 3, MUSIC_REPEAT_ALL, true, order))
        assertEquals(1, previousMusicQueueIndex(2, 3, 20.0, true, repeatMode = MUSIC_REPEAT_ALL, shuffleOrder = order))
        assertEquals(1, musicQueueNavigationIndex(0, 3, MUSIC_REPEAT_ALL, true, order))
        assertEquals(null, musicQueueNavigationIndex(0, 3, MUSIC_REPEAT_OFF, true, order))
        assertEquals(0, previousMusicQueueIndex(1, 3, 20.0, true, repeatMode = MUSIC_REPEAT_ALL, shuffleOrder = order))
    }

    @Test fun invalidOrdersAndIndicesNeverEscapeQueueBounds() {
        for (order in listOf(emptyList(), listOf(0, 0, 2), listOf(0, 1, 5), listOf(0, 1))) {
            assertEquals(2, musicQueueNavigationIndex(1, 3, MUSIC_REPEAT_OFF, true, order))
        }
        assertEquals(null, musicQueueNavigationIndex(-1, 3, MUSIC_REPEAT_ALL, true))
        assertEquals(null, musicQueueNavigationIndex(0, 0, MUSIC_REPEAT_ALL, false))
        assertEquals(5, previousMusicQueueIndex(5, 3, 0.0, true))
        assertEquals(true, isMusicShuffleOrder(listOf(1, 0, 2), 3))
        assertEquals(false, isMusicShuffleOrder(listOf(1, 0, 2), 2))
    }
}
