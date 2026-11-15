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
    fun ids参数_方括号要转义否则服务端当场参数错误() {
        assertTrue(neteaseSongUrlCall(347230).url.contains("ids=%5B347230%5D"))
    }
}
