package com.andaxin.feature.music

import platform.Foundation.NSUserDefaults

/*
 * 落盘：就 NSUserDefaults 一个盒子，不引数据库。
 *
 * 存五样：登录 cookie（MUSIC_U）、队列（encodeQueue 那个字符串）、放到第几首、放到第几秒、
 * 上次是不是在放。
 * 播放状态是要存的（用户 11-16 的决定：上次在放，重开接着放）。
 */
internal object MusicStore {

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    private const val KEY_MUSIC_U = "music.musicU"
    private const val KEY_QUEUE = "music.queue"
    private const val KEY_INDEX = "music.index"
    private const val KEY_POSITION = "music.position"
    private const val KEY_PLAYING = "music.playing"

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

    /** 上次退出时是不是在放；没存过就是 false（首装重开不出声）。 */
    fun loadPlaying(): Boolean = defaults.boolForKey(KEY_PLAYING)

    fun savePlaying(value: Boolean) {
        defaults.setBool(value, forKey = KEY_PLAYING)
    }
}
