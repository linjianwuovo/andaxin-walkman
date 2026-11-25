package com.andaxin.feature.music

internal data class RestoredMusicPlayback(
    val queue: List<MusicSong>,
    val index: Int,
    val positionSeconds: Double,
) {
    val current: MusicSong? get() = queue.getOrNull(index)
    val playing: Boolean get() = false
}

internal fun validMusicPosition(seconds: Double): Double =
    if (seconds.isFinite() && seconds >= 0.0) seconds else 0.0

internal fun restoreMusicPlayback(
    queue: List<MusicSong>,
    index: Int,
    positionSeconds: Double,
    restorePosition: Boolean = true,
): RestoredMusicPlayback {
    if (queue.isEmpty()) return RestoredMusicPlayback(emptyList(), -1, 0.0)
    return RestoredMusicPlayback(
        queue = queue,
        index = index.coerceIn(0, queue.lastIndex),
        positionSeconds = if (restorePosition) validMusicPosition(positionSeconds) else 0.0,
    )
}
