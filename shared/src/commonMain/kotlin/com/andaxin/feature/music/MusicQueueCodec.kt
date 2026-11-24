package com.andaxin.feature.music

/*
 * 队列的落盘格式。放在 commonMain 而不是 iOS 那一侧，是为了能在 Windows 上被 jvmTest 钉住：
 * 换行分条、制表符分字段，字段里那四个字符（\\ \t \n \r）转义后再写。
 * 用 NSUserDefaults 存的就是这个字符串，读回来解不开就当没有 —— 别让一个坏字符串炸掉启动。
 */
private const val FIELD = '\t'
private const val RECORD = '\n'

fun encodeQueue(songs: List<MusicSong>): String = songs.joinToString(RECORD.toString()) { song ->
    listOf(
        song.id.toString(),
        escapeField(song.name),
        escapeField(song.artist),
        escapeField(song.album),
        escapeField(song.cover),
    ).joinToString(FIELD.toString())
}

fun decodeQueue(text: String): List<MusicSong> {
    if (text.isEmpty()) return emptyList()
    return text.split(RECORD).mapNotNull { line ->
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(FIELD)
        if (parts.size < 4) return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        MusicSong(
            id = id,
            name = unescapeField(parts[1]),
            artist = unescapeField(parts[2]),
            album = unescapeField(parts[3]),
            cover = if (parts.size >= 5) unescapeField(parts[4]) else "",
        )
    }
}

private fun escapeField(value: String): String {
    if (value.none { it == '\\' || it == FIELD || it == RECORD }) return value
    val out = StringBuilder(value.length + 8)
    value.forEach { c ->
        when (c) {
            '\\' -> out.append("\\\\")
            FIELD -> out.append("\\t")
            RECORD -> out.append("\\n")
            '\r' -> out.append("\\r")
            else -> out.append(c)
        }
    }
    return out.toString()
}

private fun unescapeField(value: String): String {
    if (!value.contains('\\')) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            when (value[i + 1]) {
                't' -> out.append(FIELD)
                'n' -> out.append(RECORD)
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append(value[i + 1])
            }
            i += 2
        } else {
            out.append(c)
            i += 1
        }
    }
    return out.toString()
}
