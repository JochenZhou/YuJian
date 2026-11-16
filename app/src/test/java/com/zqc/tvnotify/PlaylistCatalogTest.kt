package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistCatalogTest {

    private val sample = """
        {
          "version": 1,
          "target_entity_id": "media_player.music_assistant_x",
          "callback": {"url": "http://192.168.1.10:8123/api/yujian/play", "token": "abc"},
          "playlists": [
            {"id": "library://playlist/13", "name": "我的歌单", "track_count": 42, "cover_b64": "data:image/jpeg;base64,AA"},
            {"id": "library://playlist/14", "name": "另一张"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesValidPayload() {
        val catalog = PlaylistCatalog.parse(sample)!!
        assertEquals("media_player.music_assistant_x", catalog.targetEntityId)
        assertEquals("http://192.168.1.10:8123/api/yujian/play", catalog.callbackUrl)
        assertEquals("abc", catalog.callbackToken)
        assertEquals(2, catalog.playlists.size)
        val first = catalog.playlists[0]
        assertEquals("library://playlist/13", first.id)
        assertEquals("我的歌单", first.name)
        assertEquals(42, first.trackCount)
        assertEquals("data:image/jpeg;base64,AA", first.coverBase64)
    }

    @Test
    fun missingOptionalFieldsDefaultGracefully() {
        val catalog = PlaylistCatalog.parse(sample)!!
        val second = catalog.playlists[1]
        assertEquals("另一张", second.name)
        assertNull(second.trackCount)
        assertNull(second.coverBase64)
    }

    @Test
    fun invalidJsonIsNull() {
        assertNull(PlaylistCatalog.parse("not json"))
    }

    @Test
    fun missingPlaylistsIsNull() {
        assertNull(PlaylistCatalog.parse("""{"version":1}"""))
    }

    @Test
    fun missingCallbackIsEmpty() {
        val catalog = PlaylistCatalog.parse(
            """{"playlists":[{"id":"library://playlist/1","name":"x"}]}"""
        )!!
        assertEquals("", catalog.callbackUrl)
        assertEquals("", catalog.callbackToken)
    }

    @Test
    fun skipsEntriesWithoutId() {
        val catalog = PlaylistCatalog.parse(
            """{"playlists":[{"name":"no id"},{"id":"library://playlist/2","name":"ok"}]}"""
        )!!
        assertEquals(1, catalog.playlists.size)
        assertEquals("library://playlist/2", catalog.playlists[0].id)
    }
}
