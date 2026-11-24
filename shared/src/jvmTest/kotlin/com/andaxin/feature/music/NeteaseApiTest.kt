package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeteaseApiTest {
    @Test fun searchUrlIsEncoded() {
        val call = neteaseSearchCall("周杰伦")
        assertTrue(call.url.contains("s=%E5%91%A8%E6%9D%B0%E4%BC%A6"))
        assertTrue(call.url.contains("type=1"))
    }

    @Test fun songUrlUsesProvidedBitrate() {
        assertTrue(neteaseSongUrlCall(42L, 320000).url.contains("br=320000"))
    }

    @Test fun lyricsAreParsedFromLrc() {
        val json = "{\"lrc\":{\"lyric\":\"[00:01.00]第一行\n[00:02.50]第二行\"}}"
        val lines = parseLyrics(json)
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals("第一行", lines[0].text)
    }

    @Test fun searchResultsAreParsed() {
        val json = "{\"result\":{\"songs\":[{\"id\":1,\"name\":\"晴天\",\"artists\":[{\"id\":7,\"name\":\"周杰伦\"}],\"album\":{\"id\":9,\"name\":\"叶惠美\",\"picUrl\":\"http://img\"}}]}}"
        val songs = parseSearchSongs(json)
        assertEquals(1, songs.size)
        assertEquals(7L, songs[0].artistId)
        assertEquals(9L, songs[0].albumId)
    }
}

class RichLyricsTest {
    @Test fun parsesTtml() {
        val json = "{\"lrc\":{\"ttml\":\"<p begin='00:00.50'><span begin='00:00.50'>第一行</span><span begin='00:01.20'>第二行</span></p>\"}}";
        val lines = parseLyrics(json)
        assertEquals(2, lines.size)
        assertEquals(500L, lines[0].timeMs)
        assertEquals("第二行", lines[1].text)
    }

    @Test fun parsesYrc() {
        val json = "{\"lrc\":{\"yrc\":\"<cy timestamp='800'>第一行</cy><cy timestamp='1500'>第二行</cy>\"}}";
        val lines = parseLyrics(json)
        assertEquals(2, lines.size)
        assertEquals(800L, lines[0].timeMs)
    }

    @Test fun parsesQrc() {
        val json = "{\"lrc\":{\"qrc\":\"<cy t=\\\"1200\\\">第一行</cy>\"}}"
        val lines = parseLyrics(json)
        assertEquals(1, lines.size)
        assertEquals(1200L, lines[0].timeMs)
    }
}

class ExpandedApiParserTest {
    @Test fun parsesRankings() {
        val json = "{\"list\":[{\"id\":1,\"name\":\"飙升榜\",\"coverImgUrl\":\"http://c\",\"updateFrequency\":\"每日更新\"}]}"
        val items = parseRankings(json)
        assertEquals(1, items.size)
        assertEquals("飙升榜", items[0].name)
    }

    @Test fun parsesCatalogueCategories() {
        val json = "{\"sub\":[{\"name\":\"华语\",\"resourceCount\":100,\"pic56x56Url\":\"http://c\"}]}"
        val items = parseCatalogItems(json, "sub")
        assertEquals(1, items.size)
        assertEquals(100, items[0].count)
    }

    @Test fun parsesAlbumArtistPlaylistDetails() {
        val albumJson = "{\"album\":{\"id\":10,\"name\":\"叶惠美\",\"picUrl\":\"http://a\",\"artists\":[{\"name\":\"周杰伦\"}],\"songs\":[{\"id\":11,\"name\":\"晴天\",\"artists\":[{\"name\":\"周杰伦\"}],\"album\":{\"id\":10,\"name\":\"叶惠美\"}}]}}"
        val artistJson = "{\"artist\":{\"id\":7,\"name\":\"周杰伦\",\"briefDesc\":\"歌手\"}}"
        val playlistJson = "{\"playlist\":{\"id\":20,\"name\":\"歌单\",\"tracks\":[{\"id\":11,\"name\":\"晴天\",\"artists\":[{\"name\":\"周杰伦\"}],\"album\":{\"id\":10,\"name\":\"叶惠美\"}}]}}"
        assertEquals("周杰伦", parseAlbumDetail(albumJson)!!.artist)
        assertEquals(7L, parseArtistDetail(artistJson)!!.id)
        assertEquals(1, parsePlaylistDetail(playlistJson)!!.songs.size)
    }
}