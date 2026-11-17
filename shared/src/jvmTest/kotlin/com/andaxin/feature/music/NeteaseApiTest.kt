package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * 网易云协议层的钉子。JSON 全部是 2026-11-15 真接口回包里剪下来的字段，
 * 不是照着想象写的：少字段、null 的 url、多一个 artists 都要能扛。
 */

class NeteaseApiTest {

    @Test
    fun 搜索回包_取到名字歌手专辑() {
        val json = """{"result":{"songs":[{"id":3377030,"name":"Hello","artists":[{"id":1,"name":"Adele"}],"album":{"id":2,"name":"25"}}]},"code":200}"""
        val songs = parseSearchSongs(json)
        assertEquals(1, songs.size)
        assertEquals(3377030L, songs[0].id)
        assertEquals("Hello", songs[0].name)
        assertEquals("Adele", songs[0].artist)
        assertEquals("25", songs[0].album)
    }

    @Test
    fun 搜索回包_带出封面地址() {
        val json = """{"result":{"songs":[{"id":7,"name":"歌","artists":[{"name":"人"}],"album":{"name":"辑","picUrl":"http://p1.music.126.net/x.jpg"}}]}}"""
        val songs = parseSearchSongs(json)
        assertEquals("http://p1.music.126.net/x.jpg", songs[0].cover)
    }

    @Test
    fun 登录之后_MUSIC_U要被带进请求() {
        val call = neteaseSearchCall("晴天", musicU = "abc123")
        val cookie = call.headers.first { it.first == "Cookie" }.second
        assertTrue(cookie.contains("MUSIC_U=abc123"))
        assertTrue(cookie.contains("os=pc"))
    }

    @Test
    fun 搜索回包_缺字段的那首直接跳过而不是整包失败() {
        val json = """{"result":{"songs":[{"name":"没有 id"},{"id":9,"name":"留着"}]}}"""
        val songs = parseSearchSongs(json)
        assertEquals(1, songs.size)
        assertEquals("留着", songs[0].name)
        assertEquals("", songs[0].artist)
    }

    @Test
    fun 搜索地址_查询词被编码且带上匿名身份() {
        val call = neteaseSearchCall("周杰伦 晴天")
        assertTrue(call.url.startsWith("https://music.163.com/api/search/get/?s="))
        assertTrue(call.url.contains("%E5%91%A8%E6%9D%B0%E4%BC%A6"))     // 周杰伦
        assertTrue(call.url.contains("+"))                                 // 空格编成 +（formUrlEncode 口径）
        assertTrue(call.headers.any { it.first == "Cookie" && it.second.contains("os=pc") })
    }

    @Test
    fun 播放地址_拿得到直链() {
        val json = """{"data":[{"id":347230,"url":"http://m702.music.126.net/2026/x.mp3?vuutv=abc","br":128000,"code":200}],"code":200}"""
        assertEquals("http://m702.music.126.net/2026/x.mp3?vuutv=abc", parseSongUrl(json))
    }

    @Test
    fun 播放地址_版权受限时是null() {
        val json = """{"data":[{"id":1,"url":null,"code":404}],"code":200}"""
        assertNull(parseSongUrl(json))
        assertNull(parseSongUrl("不是 JSON"))
    }

    @Test
    fun 歌词请求_九个表单字段和音乐U都要带齐() {
        val call = neteaseLyricCall(347230, musicU = "abc123")
        val body = call.body as com.andaxin.core.net.HttpBody.Form
        assertEquals("POST", call.method)
        assertEquals("https://interface.music.163.com/api/song/lyric/v1", call.url)
        assertEquals(
            listOf(
                "id" to "347230",
                "cp" to "false",
                "tv" to "0",
                "lv" to "0",
                "rv" to "0",
                "kv" to "0",
                "yv" to "0",
                "ytv" to "0",
                "yrv" to "0",
            ),
            body.fields,
        )
        assertTrue(call.headers.any { it.first == "Content-Type" && it.second == "application/x-www-form-urlencoded" })
        assertTrue(call.headers.any { it.first == "Cookie" && it.second.contains("MUSIC_U=abc123") })
    }

    @Test
    fun 歌词回包_增强格式按片段拼成完整一行() {
        val lrc = """{"t":0,"c":[{"tx":"作词: "},{"tx":"周杰伦","li":"http://x"}]}
{"t":1250,"c":[{"tx":"故事的小黄花"},{"tx":" 从出生那年就飘着"}]}
{"t":9800,"c":[{"tx":"  "}]}""".trimIndent()
        val json = """{"code":200,"lrc":{"lyric":${kotlinx.serialization.json.Json.encodeToString(lrc)}}}"""
        assertEquals(
            listOf(
                LyricLine(0L, "作词: 周杰伦"),
                LyricLine(1250L, "故事的小黄花 从出生那年就飘着"),
            ),
            parseLyrics(json),
        )
    }

    @Test
    fun 歌词回包_经典LRC按时间戳排序() {
        val lrc = """[00:12.34]第二句
[00:01.50]第一句
[00:10]整秒
[ar:歌手]
空白""".trimIndent()
        val json = """{"code":200,"lrc":{"lyric":${kotlinx.serialization.json.Json.encodeToString(lrc)}}}"""
        assertEquals(
            listOf(
                LyricLine(1500L, "第一句"),
                LyricLine(10_000L, "整秒"),
                LyricLine(12_340L, "第二句"),
            ),
            parseLyrics(json),
        )
    }

    @Test
    fun 歌词回包_坏输入回空列表() {
        assertTrue(parseLyrics("不是 JSON").isEmpty())
        assertTrue(parseLyrics("""{"code":404,"lrc":{"lyric":"[00:01.00]别读"}}""").isEmpty())
        assertTrue(parseLyrics("""{"code":200,"lrc":"字段不对"}""").isEmpty())
    }
}
