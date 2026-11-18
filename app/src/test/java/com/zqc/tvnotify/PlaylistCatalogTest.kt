package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistCatalogTest {

    @Before
    fun resetStore() {
        PlaylistStore.update(null)
        PlaylistStore.markActivePlaylist(null)
    }

    private val sample = """
        {
          "version": 1,
          "target_entity_id": "media_player.music_assistant_x",
          "callback": {"url": "http://192.168.1.10:8123/api/yujian/play", "token": "abc"},
          "playlists": [
            {
              "id": "library://playlist/13",
              "name": "我的歌单",
              "track_count": 42,
              "cover_b64": "data:image/jpeg;base64,AA",
              "tracks": [
                {"id": "library://track/101", "name": "晴天", "artist": "周杰伦"},
                {"id": "library://track/102", "name": "七里香"}
              ]
            },
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
        assertEquals(2, first.tracks.size)
        assertEquals("library://track/101", first.tracks[0].id)
        assertEquals("晴天", first.tracks[0].name)
        assertEquals("周杰伦", first.tracks[0].artist)
        assertEquals("library://track/102", first.tracks[1].id)
        assertNull(first.tracks[1].artist)
    }

    @Test
    fun missingOptionalFieldsDefaultGracefully() {
        val catalog = PlaylistCatalog.parse(sample)!!
        val second = catalog.playlists[1]
        assertEquals("另一张", second.name)
        assertNull(second.trackCount)
        assertNull(second.coverBase64)
        assertTrue(second.tracks.isEmpty())
        assertNull(second.effectiveTrackCount)
    }

    @Test
    fun parsesTracksWithoutCountAndSkipsInvalidTracks() {
        val catalog = PlaylistCatalog.parse(
            """
            {
              "playlists": [
                {
                  "id": "library://playlist/9",
                  "name": "夜听",
                  "tracks": [
                    {"name": "missing id"},
                    {"id": "spotify://track/77", "name": "Fade - Instrumental", "artist": ""}
                  ]
                }
              ]
            }
            """.trimIndent()
        )!!
        val entry = catalog.playlists[0]
        assertNull(entry.trackCount)
        assertEquals(1, entry.effectiveTrackCount)
        assertEquals(1, entry.tracks.size)
        assertEquals("spotify://track/77", entry.tracks[0].id)
        assertNull(entry.tracks[0].artist)
    }

    @Test
    fun resolvesActivePlaylistByMarkedIdOrCurrentPlayingTrack() {
        val catalog = PlaylistCatalog.parse(
            """
            {
              "playlists": [
                {
                  "id": "library://playlist/1",
                  "name": "华语",
                  "tracks": [{"id": "library://track/1", "name": "晴天", "artist": "周杰伦"}]
                },
                {
                  "id": "library://playlist/2",
                  "name": "英文",
                  "tracks": [
                    {"id": "library://track/2", "name": "Yellow", "artist": "Coldplay"},
                    {"id": "library://track/3", "name": "晴天", "artist": "刘宪华"}
                  ]
                }
              ]
            }
            """.trimIndent()
        )!!
        PlaylistStore.update(catalog)

        // Matches by track title + artist even before manually picking on TV
        assertEquals("library://playlist/2", PlaylistStore.resolveActivePlaylist("晴天", "刘宪华")?.id)
        assertEquals("library://playlist/1", PlaylistStore.resolveActivePlaylist("晴天", "周杰伦")?.id)

        // Explicit mark overrides when track belongs to that playlist or playback is idle
        PlaylistStore.markActivePlaylist("library://playlist/1")
        assertEquals("library://playlist/1", PlaylistStore.resolveActivePlaylist(null, null)?.id)

        // External playback from another playlist updates auto-detected playlist seamlessly
        assertEquals("library://playlist/2", PlaylistStore.resolveActivePlaylist("Yellow", "Coldplay")?.id)

        // Partitioned "Artist - Track" still matches unsplit track metadata
        val splitTrack = PlaylistTrack("library://track/9", "Instrumental", "Fade")
        assertTrue(splitTrack.matchesPlaying("Fade - Instrumental", ""))
        assertFalse(splitTrack.matchesPlaying("Other", ""))
    }

    @Test
    fun playlistPlayerPayloadIncludesTrackOnlyWhenProvided() {
        val whole = PlaylistPlayer.buildRequestPayload("library://playlist/1", "我的歌单", model = "MiTV4")
        assertEquals("library://playlist/1", whole.getString("id"))
        assertEquals("我的歌单", whole.getString("name"))
        assertEquals("MiTV4", whole.getString("model"))
        assertFalse(whole.has("track_id"))

        val single = PlaylistPlayer.buildRequestPayload(
            "library://playlist/1",
            "我的歌单",
            trackId = "library://track/101",
            trackName = "晴天",
            model = "MiTV4"
        )
        assertEquals("library://track/101", single.getString("track_id"))
        assertEquals("晴天", single.getString("track_name"))
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
