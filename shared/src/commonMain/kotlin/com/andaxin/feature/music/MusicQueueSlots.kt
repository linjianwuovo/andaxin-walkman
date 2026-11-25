package com.andaxin.feature.music

internal class MusicQueueSlots {
    private var keys = mutableListOf<Any>()

    fun reset(size: Int) {
        keys = MutableList(size) { Any() }
    }

    fun keyAt(index: Int): Any? = keys.getOrNull(index)

    fun indexOf(key: Any): Int = keys.indexOf(key)

    fun insert(index: Int) {
        keys.add(index, Any())
    }

    fun remove(index: Int) {
        keys.removeAt(index)
    }

    fun move(from: Int, to: Int) {
        keys.add(to, keys.removeAt(from))
    }
}
