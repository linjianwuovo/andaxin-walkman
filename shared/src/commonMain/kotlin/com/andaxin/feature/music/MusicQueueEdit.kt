package com.andaxin.feature.music

internal data class MusicQueueEdit(val queue: List<MusicSong>, val index: Int)

internal fun removeMusicQueueItem(
    queue: List<MusicSong>,
    currentIndex: Int,
    target: Int,
): MusicQueueEdit? {
    if (queue.size <= 1 || currentIndex !in queue.indices || target !in queue.indices) return null
    val next = queue.toMutableList().also { it.removeAt(target) }
    val index = when {
        target < currentIndex -> currentIndex - 1
        target == currentIndex -> currentIndex.coerceAtMost(next.lastIndex)
        else -> currentIndex
    }
    return MusicQueueEdit(next, index)
}

internal fun moveMusicQueueItem(
    queue: List<MusicSong>,
    currentIndex: Int,
    from: Int,
    to: Int,
): MusicQueueEdit? {
    if (currentIndex !in queue.indices || from !in queue.indices || to !in queue.indices || from == to) return null
    val next = queue.toMutableList()
    next.add(to, next.removeAt(from))
    val index = when {
        currentIndex == from -> to
        from < currentIndex && to >= currentIndex -> currentIndex - 1
        from > currentIndex && to <= currentIndex -> currentIndex + 1
        else -> currentIndex
    }
    return MusicQueueEdit(next, index)
}
