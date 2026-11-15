package com.andaxin.feature.music

import platform.Foundation.NSUserDefaults

/*
 * 落盘：就 NSUserDefaults 一个盒子，不引数据库。
 *
 * 存四样：登录 cookie（MUSIC_U）、队列（encodeQueue 那个字符串）、放到第几首、放到第几秒。
 * **不存"正在放"**：0.9.29 那条决定是冷启动必须停着（用户原话：重开就响会吓死人），
 * 所以恢复的是"上次那首 + 那个位置"，按下去才出声。
 */
internal object MusicStore {

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    private const val KEY_MUSIC_U = "music.musicU"
    private const val KEY_QUEUE = "music.queue"
    private const val KEY_INDEX = "music.index"
    private const val KEY_POSITION = "music.position"

    fun loadMusicU(): String? = defaults.stringForKey(KEY_MUSIC_U)?.takeIf { it.isNotBlank() }

    fun saveMusicU(value: String?) {
        if (value.isNullOrBlank()) {
            defaults.removeObjectForKey(KEY_MUSIC_U)
        } else {
            defaults.setObject(value, forKey = KEY_MUSIC_U)
        }
    }

    fun loadQueue(): List<MusicSong> = decodeQueue(defaults.stringForKey(KEY_QUEUE).orEmpty())

    fun saveQueue(queue: List<MusicSong>, index: Int) {
        defaults.setObject(encodeQueue(queue), forKey = KEY_QUEUE)
        defaults.setInteger(index.toLong(), forKey = KEY_INDEX)
    }

    fun loadIndex(): Int = defaults.integerForKey(KEY_INDEX).toInt()

    fun loadPosition(): Double {
        val seconds = defaults.doubleForKey(KEY_POSITION)
        return if (seconds.isNaN() || seconds < 0.0) 0.0 else seconds
    }

    fun savePosition(seconds: Double) {
        defaults.setDouble(seconds, forKey = KEY_POSITION)
    }
}
