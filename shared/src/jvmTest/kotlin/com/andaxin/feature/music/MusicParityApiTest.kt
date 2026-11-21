package com.andaxin.feature.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class MusicParityApiTest {
    @Test fun podcastUsesPlayableMainSongIdAndPaging() {
        val page = parsePodcastPrograms("""{"code":200,"count":77,"more":true,"programs":[{"id":99,"name":"一集播客","duration":120000,"coverUrl":"https://image","mainSong":{"id":42,"name":"音轨","artists":[{"name":"主播"}]}}]}""")
        assertEquals(42L, page.songs.single().id)
        assertEquals("一集播客", page.songs.single().name)
        assertEquals("主播", page.songs.single().artist)
        assertEquals(120000L, page.songs.single().duration)
        assertEquals(77, page.total)
        assertTrue(page.more)
    }
    @Test fun radioCatalogPreservesActualProgramCounts() {
        val radios = parsePodcastRadios("""{"code":200,"djRadios":[{"id":7,"name":"电台","picUrl":"https://cover","programCount":30}]}""")
        assertEquals(7L, radios.single().id)
        assertEquals(30, radios.single().count)
        assertEquals("https://cover", radios.single().cover)
    }
    @Test fun cloudSupportsFileAndSimpleSongMetadata() {
        val page = parseCloudSongs("""{"code":200,"count":2,"hasMore":false,"data":[{"songId":41,"songName":"云盘歌曲","simpleSong":{"id":41,"name":"歌曲","ar":[{"id":3,"name":"歌手"}],"al":{"id":6,"name":"专辑","picUrl":"https://cover"}}},{"songId":"42","songName":"个人录音","artist":"自己","album":"本地"}]}""")
        assertEquals(listOf(41L,42L), page.songs.map { it.id })
        assertEquals("歌手",page.songs[0].artist)
        assertEquals("自己",page.songs[1].artist)
        assertFalse(page.more)
    }
    @Test fun expiredLoginMustNotBecomeAnEmptyCloud() {
        assertFailsWith<IllegalStateException> { parseCloudSongs("""{"code":301,"message":"需要登录"}""") }
        assertFailsWith<IllegalStateException> { parsePodcastRadios("""{"code":500,"message":"服务异常"}""") }
    }
    @Test fun authenticatedCloudAndBoundedPageQueries() {
        val call = neteaseCloudSongsCall("test-session", limit=500, offset=-10)
        assertTrue(call.headers.any { it.first == "Cookie" && it.second.contains("MUSIC_U=test-session") })
        assertTrue(call.url.endsWith("limit=100&offset=0"))
        assertFailsWith<IllegalArgumentException> { neteaseCloudSongsCall("") }
        assertTrue(neteasePodcastProgramsCall(7,offset=30).url.contains("radioId=7&limit=30&offset=30"))
        assertTrue(neteasePodcastRadiosCall(3).url.endsWith("cateId=3"))
    }
}
