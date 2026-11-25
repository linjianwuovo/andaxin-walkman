package com.andaxin.feature.music

import platform.Foundation.NSNumber
import platform.Foundation.NSUserDefaults

/*
 * 队列、位置和偏好存入 NSUserDefaults。旧版 music.playing 不再读取，启动始终暂停。
 */
internal object MusicStore {

    private val defaults: NSUserDefaults get() = NSUserDefaults.standardUserDefaults

    private const val KEY_MUSIC_U = "music.musicU"
    private const val KEY_QUEUE = "music.queue"
    private const val KEY_INDEX = "music.index"
    private const val KEY_POSITION = "music.position"
    private const val KEY_RESTORE_POSITION = "music.restorePosition"
    private const val KEY_RECENT = "music.recent"
    private const val KEY_RECENT_TIMES = "music.recent.playedAt"
    private const val KEY_LIKED = "music.liked"
    private const val KEY_PLAY_MODE = "music.playMode"
    private const val KEY_REPEAT_MODE = "music.repeatMode"
    private const val KEY_SHUFFLE_ENABLED = "music.shuffleEnabled"
    private const val KEY_SHUFFLE_ORDER = "music.shuffleOrder"
    private const val KEY_LOOP_PLAYBACK = "music.loopPlayback"
    private const val KEY_ALWAYS_PREVIOUS = "music.alwaysPrevious"
    private const val KEY_SEARCH_HISTORY = "music.searchHistory"
    private const val KEY_BITRATE = "music.bitrate"
    private const val KEY_LYRICS_VISIBLE = "music.lyricsVisible"
    private const val KEY_TAB_ORDER = "music.tabOrder"
    private const val KEY_TAB_VISIBILITY = "music.tabVisibility"
    private const val KEY_TAB_SELECTED = "music.tabSelected"
    private const val KEY_SLEEP_MINUTES = "music.sleepMinutes"
    private const val KEY_LYRIC_FONT = "music.lyricFont"
    private const val KEY_LYRIC_COLOR = "music.lyricColor"
    private const val KEY_TRANSLATE_LYRICS = "music.translateLyrics"
    private const val KEY_CUSTOM_PLAYLISTS = "music.customPlaylists"

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

    fun loadPosition(): Double = validMusicPosition(defaults.doubleForKey(KEY_POSITION))

    fun savePosition(seconds: Double) {
        defaults.setDouble(validMusicPosition(seconds), forKey = KEY_POSITION)
    }

    fun loadRestorePlaybackPosition(): Boolean =
        (defaults.objectForKey(KEY_RESTORE_POSITION) as? NSNumber)?.boolValue ?: true

    fun saveRestorePlaybackPosition(value: Boolean) {
        defaults.setBool(value, forKey = KEY_RESTORE_POSITION)
    }

    fun loadRecent(): List<MusicSong> = decodeQueue(defaults.stringForKey(KEY_RECENT).orEmpty())

    fun saveRecent(songs: List<MusicSong>, playedAt: Map<Long, Long> = emptyMap()) {
        val recent = songs.take(100)
        defaults.setObject(encodeQueue(recent), forKey = KEY_RECENT)
        val times = recent.mapNotNull { song -> playedAt[song.id]?.let { song.id.toString() to it } }.toMap()
        defaults.setObject(times, forKey = KEY_RECENT_TIMES)
    }

    fun loadRecentTimes(): Map<Long, Long> =
        (defaults.dictionaryForKey(KEY_RECENT_TIMES) as? Map<*, *>)
            ?.mapNotNull { (id, time) ->
                val key = (id as? String)?.toLongOrNull()
                val value = (time as? Number)?.toLong()
                if (key != null && value != null) key to value else null
            }?.toMap().orEmpty()

    fun loadLikedIds(): Set<Long> = (defaults.arrayForKey(KEY_LIKED) as? List<*>)
        ?.mapNotNull { (it as? Number)?.toLong() }
        ?.toSet()
        ?: emptySet()

    fun saveLikedIds(ids: Set<Long>) {
        defaults.setObject(ids.map { it as Any }, forKey = KEY_LIKED)
    }

    fun loadPlaybackMode(): MusicPlaybackMode {
        val repeat = defaults.objectForKey(KEY_REPEAT_MODE) as? NSNumber
        val shuffle = defaults.objectForKey(KEY_SHUFFLE_ENABLED) as? NSNumber
        if (repeat != null || shuffle != null) {
            val repeatMode = repeat?.intValue?.coerceIn(MUSIC_REPEAT_OFF, MUSIC_REPEAT_ALL) ?: MUSIC_REPEAT_ALL
            return MusicPlaybackMode(repeatMode, shuffle?.boolValue ?: false)
        }
        val legacy = defaults.objectForKey(KEY_PLAY_MODE) as? NSNumber
            ?: return MusicPlaybackMode(MUSIC_REPEAT_ALL, false)
        return migrateLegacyMusicPlaybackMode(legacy.intValue)
    }

    fun savePlaybackMode(value: MusicPlaybackMode) {
        defaults.setInteger(value.repeatMode.toLong(), forKey = KEY_REPEAT_MODE)
        defaults.setBool(value.shuffleEnabled, forKey = KEY_SHUFFLE_ENABLED)
    }

    fun loadShuffleOrder(): List<Int> = defaults.arrayForKey(KEY_SHUFFLE_ORDER).orEmpty()
        .mapNotNull { (it as? NSNumber)?.intValue }

    fun saveShuffleOrder(order: List<Int>) {
        defaults.setObject(order.map { it.toLong() }, forKey = KEY_SHUFFLE_ORDER)
    }

    fun loadLoopPlayback(): Boolean =
        (defaults.objectForKey(KEY_LOOP_PLAYBACK) as? NSNumber)?.boolValue ?: true

    fun saveLoopPlayback(value: Boolean) {
        defaults.setBool(value, forKey = KEY_LOOP_PLAYBACK)
    }

    fun loadAlwaysPrevious(): Boolean =
        (defaults.objectForKey(KEY_ALWAYS_PREVIOUS) as? NSNumber)?.boolValue ?: true

    fun saveAlwaysPrevious(value: Boolean) {
        defaults.setBool(value, forKey = KEY_ALWAYS_PREVIOUS)
    }

    fun loadSearchHistory(): List<String> = (defaults.arrayForKey(KEY_SEARCH_HISTORY) as? List<*>)
        ?.mapNotNull { it as? String }
        ?.take(20)
        ?: emptyList()

    fun saveSearchHistory(items: List<String>) {
        defaults.setObject(items.take(20).map { it as Any }, forKey = KEY_SEARCH_HISTORY)
    }

    fun loadBitrate(): Int {
        val value = defaults.integerForKey(KEY_BITRATE).toInt()
        return if (value == 320000) 320000 else 128000
    }

    fun saveBitrate(value: Int) {
        defaults.setInteger(value.toLong(), forKey = KEY_BITRATE)
    }

    fun loadLyricsVisible(): Boolean {
        val value = defaults.objectForKey(KEY_LYRICS_VISIBLE) ?: return true
        return (value as? NSNumber)?.boolValue ?: true
    }

    fun saveLyricsVisible(value: Boolean) {
        defaults.setBool(value, forKey = KEY_LYRICS_VISIBLE)
    }

    fun loadTabOrder(): List<String> {
        val saved = defaults.stringForKey(KEY_TAB_ORDER).orEmpty().split(",").filter { it.isNotBlank() }
        val canonical = listOf("首页", "发现", "播客", "音乐库", "搜索", "设置", "下载", "云盘", "历史")
        return (saved + canonical.filterNot { it in saved }).distinct()
    }

    fun saveTabOrder(order: List<String>) {
        defaults.setObject(order.joinToString(","), forKey = KEY_TAB_ORDER)
    }

    fun loadTabVisibility(): Map<String, Boolean> {
        val raw = defaults.stringForKey(KEY_TAB_VISIBILITY).orEmpty()
        if (raw.isBlank()) return mapOf("下载" to false, "云盘" to false, "历史" to false)
        return raw.split(",").mapNotNull { part ->
            val kv = part.split(":", limit = 2)
            if (kv.size == 2) kv[0] to (kv[1] == "1") else null
        }.toMap()
    }

    fun saveTabVisibility(visibility: Map<String, Boolean>) {
        defaults.setObject(visibility.entries.joinToString(",") { it.key + ":" + if (it.value) "1" else "0" }, forKey = KEY_TAB_VISIBILITY)
    }

    fun loadSelectedTab(): String = defaults.stringForKey(KEY_TAB_SELECTED)?.takeIf { it.isNotBlank() }.orEmpty()

    fun saveSelectedTab(label: String) {
        defaults.setObject(label, forKey = KEY_TAB_SELECTED)
    }

    fun loadSleepMinutes(): Int = defaults.integerForKey(KEY_SLEEP_MINUTES).toInt().coerceIn(0, 240)

    fun saveSleepMinutes(value: Int) {
        defaults.setInteger(value.coerceIn(0, 240).toLong(), forKey = KEY_SLEEP_MINUTES)
    }

    fun loadLyricFontSize(): Int {
        val value = defaults.integerForKey(KEY_LYRIC_FONT).toInt()
        return if (value in 14..32) value else 20
    }

    fun saveLyricFontSize(value: Int) {
        defaults.setInteger(value.coerceIn(14, 32).toLong(), forKey = KEY_LYRIC_FONT)
    }

    fun loadLyricColor(): String = defaults.stringForKey(KEY_LYRIC_COLOR)?.takeIf { it.isNotBlank() }.orEmpty()

    fun saveLyricColor(value: String) {
        defaults.setObject(value, forKey = KEY_LYRIC_COLOR)
    }

    fun loadTranslateLyrics(): Boolean =
        (defaults.objectForKey(KEY_TRANSLATE_LYRICS) as? NSNumber)?.boolValue ?: true

    fun saveTranslateLyrics(value: Boolean) {
        defaults.setBool(value, forKey = KEY_TRANSLATE_LYRICS)
    }

    fun loadCustomPlaylists(): Map<String, List<Long>> {
        defaults.dictionaryForKey("music.customPlaylists.v2")?.let { saved ->
            return saved.entries.mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                val ids = (value as? List<*>)?.mapNotNull { (it as? Number)?.toLong() } ?: return@mapNotNull null
                name to ids.filter { it > 0L }.distinct()
            }.toMap()
        }
        val raw = defaults.stringForKey(KEY_CUSTOM_PLAYLISTS).orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val kv = entry.split("=", limit = 2)
            if (kv.size != 2) return@mapNotNull null
            val ids = kv[1].split(",").mapNotNull { it.toLongOrNull() }
            kv[0] to ids
        }.toMap()
    }

    fun saveCustomPlaylists(playlists: Map<String, List<Long>>) {
        defaults.setObject(playlists.mapValues { (_, ids) -> ids.distinct().map { it as Any } }, forKey = "music.customPlaylists.v2")
    }
    fun loadDownloadedSongs(): List<MusicSong> = decodeQueue(defaults.stringForKey("music.download.songs").orEmpty())
    fun saveDownloadedSongs(songs: List<MusicSong>) { defaults.setObject(encodeQueue(songs), forKey = "music.download.songs") }
    fun loadDownloadPaths(): Map<Long, String> = (defaults.dictionaryForKey("music.download.paths") ?: emptyMap<Any?, Any?>()).entries.mapNotNull { (key, value) ->
        val id = (key as? String)?.toLongOrNull() ?: return@mapNotNull null
        val name = value as? String ?: return@mapNotNull null
        id to name
    }.toMap()
    fun saveDownloadPaths(paths: Map<Long, String>) { defaults.setObject(paths.mapKeys { it.key.toString() }, forKey = "music.download.paths") }
    fun loadLibrarySongs(): List<MusicSong> = decodeQueue(defaults.stringForKey("music.library.songs").orEmpty())
    fun saveLibrarySongs(songs: List<MusicSong>) { defaults.setObject(encodeQueue(songs), forKey = "music.library.songs") }

}