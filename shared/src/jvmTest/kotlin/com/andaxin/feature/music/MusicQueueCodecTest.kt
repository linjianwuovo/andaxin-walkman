package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 队列落盘的钉子：这套编码是"存进 NSUserDefaults 再读回来"的唯一一道关，
 * 名字里带制表符/换行/反斜杠的歌在真机上确实存在（有些翻唱标题就是多行），漏一个就串行。
 */
class MusicQueueCodecTest {

    @Test
    fun 队列_原样转一圈() {
        val songs = listOf(
            MusicSong(1, "晴天", "周杰伦", "叶惠美", "http://p1.music.126.net/a.jpg"),
            MusicSong(2, "Hello", "Adele", "25", ""),
        )
        val back = decodeQueue(encodeQueue(songs))
        assertEquals(songs, back)
    }

    @Test
    fun 队列_字段里带制表符换行和反斜杠也不串行() {
        val tricky = MusicSong(
            id = 3,
            name = "第一行\n第二行\t带制表符\\反斜杠",
            artist = "A\tB",
            album = "C\nD",
            cover = "http://x/y.jpg",
        )
        val back = decodeQueue(encodeQueue(listOf(tricky)))
        assertEquals(1, back.size)
        assertEquals(tricky, back[0])
    }

    @Test
    fun 队列_空串和垃圾串都当没有() {
        assertTrue(decodeQueue("").isEmpty())
        assertTrue(decodeQueue("这不是队列").isEmpty())
        assertTrue(decodeQueue("123\t只有四段").isEmpty())
    }
}
