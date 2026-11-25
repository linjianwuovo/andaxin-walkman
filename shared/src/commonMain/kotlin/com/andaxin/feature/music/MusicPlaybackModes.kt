package com.andaxin.feature.music

internal const val MUSIC_REPEAT_OFF = 0
internal const val MUSIC_REPEAT_ONE = 1
internal const val MUSIC_REPEAT_ALL = 2

internal data class MusicPlaybackMode(val repeatMode: Int, val shuffleEnabled: Boolean)

internal fun migrateLegacyMusicPlaybackMode(value: Int): MusicPlaybackMode = when (value) {
    0 -> MusicPlaybackMode(MUSIC_REPEAT_OFF, false)
    1 -> MusicPlaybackMode(MUSIC_REPEAT_ONE, false)
    2 -> MusicPlaybackMode(MUSIC_REPEAT_ALL, true)
    3 -> MusicPlaybackMode(MUSIC_REPEAT_ALL, false)
    else -> MusicPlaybackMode(MUSIC_REPEAT_ALL, false)
}

internal fun nextMusicPlaybackMode(mode: MusicPlaybackMode, loopPlaybackEnabled: Boolean): MusicPlaybackMode {
    val loopingMode = if (loopPlaybackEnabled) MUSIC_REPEAT_ALL else MUSIC_REPEAT_OFF
    return when {
        mode.shuffleEnabled -> MusicPlaybackMode(MUSIC_REPEAT_ONE, false)
        mode.repeatMode == MUSIC_REPEAT_ONE -> MusicPlaybackMode(loopingMode, false)
        else -> MusicPlaybackMode(loopingMode, true)
    }
}

internal fun nextMusicRepeatMode(repeatMode: Int): Int = when (repeatMode) {
    MUSIC_REPEAT_OFF -> MUSIC_REPEAT_ALL
    MUSIC_REPEAT_ALL -> MUSIC_REPEAT_ONE
    else -> MUSIC_REPEAT_OFF
}

internal fun previousMusicQueueIndex(
    index: Int,
    queueSize: Int,
    positionSeconds: Double,
    alwaysPrevious: Boolean,
    thresholdSeconds: Double = 3.0,
    repeatMode: Int = MUSIC_REPEAT_OFF,
    shuffleOrder: List<Int>? = null,
): Int {
    if (index !in 0 until queueSize) return index
    if (!alwaysPrevious && (!positionSeconds.isFinite() || positionSeconds < 0.0)) return index
    if (!alwaysPrevious && positionSeconds > thresholdSeconds) return index
    return musicQueueNavigationIndex(index, queueSize, repeatMode, forward = false, shuffleOrder) ?: index
}

internal fun isMusicShuffleOrder(order: List<Int>, queueSize: Int): Boolean =
    order.size == queueSize && order.toSet() == (0 until queueSize).toSet()

internal fun musicQueueNavigationIndex(
    index: Int,
    queueSize: Int,
    repeatMode: Int,
    forward: Boolean,
    shuffleOrder: List<Int>? = null,
): Int? {
    if (index !in 0 until queueSize) return null
    val order = shuffleOrder?.takeIf { isMusicShuffleOrder(it, queueSize) } ?: (0 until queueSize).toList()
    val position = order.indexOf(index)
    val next = position + if (forward) 1 else -1
    if (next in order.indices) return order[next]
    if (repeatMode != MUSIC_REPEAT_ALL) return null
    return if (forward) order.first() else order.last()
}
