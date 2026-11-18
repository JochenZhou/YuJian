package com.zqc.tvnotify

import org.json.JSONObject

/**
 * 歌单目录：Home Assistant 的玉简集成把 Music Assistant 的歌单通过
 * `POST /{token}/library` 推送到电视，这里是那份负载的内存模型与解析器。
 *
 * 封面 `cover_b64` 是完整的 data URI（`data:image/...;base64,...`），解析阶段
 * 只做字符串保存，解码成 Bitmap 交给 UI 层按需进行；`tracks` 为歌单内的曲目
 * 清单，老版本集成不推送该字段时默认为空列表。
 */
data class PlaylistTrack(
    val id: String,
    val name: String,
    val artist: String? = null
) {
    /** 判断曲目是否与播放器当前显示的歌名/歌手匹配。 */
    fun matchesPlaying(currentTitle: String?, currentArtist: String? = null, requireArtist: Boolean = false): Boolean {
        val playingTitle = currentTitle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val cleanName = name.trim()
        val cleanArtist = artist?.trim().orEmpty()
        val combined = if (cleanArtist.isNotEmpty()) "$cleanArtist - $cleanName" else cleanName
        val titleMatches = cleanName.equals(playingTitle, ignoreCase = true) ||
            combined.equals(playingTitle, ignoreCase = true)
        if (!titleMatches) return false
        val playingArtist = currentArtist?.trim().orEmpty()
        if (cleanArtist.isNotEmpty() && playingArtist.isNotEmpty()) {
            return playingArtist.contains(cleanArtist, ignoreCase = true) ||
                cleanArtist.contains(playingArtist, ignoreCase = true)
        }
        return !requireArtist
    }
}

data class PlaylistEntry(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val coverBase64: String?,
    val tracks: List<PlaylistTrack> = emptyList()
) {
    val effectiveTrackCount: Int?
        get() = trackCount ?: tracks.takeIf { it.isNotEmpty() }?.size
}

data class PlaylistCatalog(
    val targetEntityId: String,
    val callbackUrl: String,
    val callbackToken: String,
    val playlists: List<PlaylistEntry>
) {
    companion object {
        /**
         * 解析 /library 负载。只有缺少 `playlists` 数组（或整体不是 JSON）才返回
         * null；其余字段缺失时宽松降级，避免一次字段变更清空整份目录。
         */
        fun parse(text: String): PlaylistCatalog? {
            val root = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            val array = root.optJSONArray("playlists") ?: return null
            val entries = ArrayList<PlaylistEntry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                if (id.isEmpty()) continue
                val name = item.optString("name", "").trim().ifEmpty { id }
                val trackCount = if (item.isNull("track_count")) {
                    null
                } else {
                    item.optInt("track_count", -1).takeIf { it >= 0 }
                }
                val cover = if (item.isNull("cover_b64")) {
                    null
                } else {
                    item.optString("cover_b64", "").trim().ifEmpty { null }
                }
                val tracks = parseTracks(item)
                entries.add(PlaylistEntry(id, name, trackCount, cover, tracks))
            }
            val callback = root.optJSONObject("callback")
            return PlaylistCatalog(
                targetEntityId = root.optString("target_entity_id", "").trim(),
                callbackUrl = callback?.optString("url", "")?.trim() ?: "",
                callbackToken = callback?.optString("token", "")?.trim() ?: "",
                playlists = entries
            )
        }

        private fun parseTracks(item: JSONObject): List<PlaylistTrack> {
            val array = item.optJSONArray("tracks") ?: return emptyList()
            val tracks = ArrayList<PlaylistTrack>(array.length())
            for (i in 0 until array.length()) {
                val track = array.optJSONObject(i) ?: continue
                val id = track.optString("id", "").trim()
                if (id.isEmpty()) continue
                val name = track.optString("name", "").trim().ifEmpty { id }
                val artist = if (track.isNull("artist")) {
                    null
                } else {
                    track.optString("artist", "").trim().ifEmpty { null }
                }
                tracks.add(PlaylistTrack(id, name, artist))
            }
            return tracks
        }
    }
}

/**
 * 进程内唯一的歌单目录与当前点播歌单缓存。HTTP 服务线程写、播放页 UI 线程读，
 * 用 [Volatile] 保证跨线程可见性。
 */
object PlaylistStore {
    @Volatile
    private var catalog: PlaylistCatalog? = null

    @Volatile
    private var activePlaylistId: String? = null

    fun update(catalog: PlaylistCatalog?) {
        this.catalog = catalog
    }

    fun current(): PlaylistCatalog? = catalog

    fun markActivePlaylist(playlistId: String?) {
        activePlaylistId = playlistId?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun currentActivePlaylistId(): String? = activePlaylistId

    /**
     * 解析当前正在播放的歌单：
     * 1. 若上次在电视端选过歌单且当前曲目仍在其中（或当前无曲目/该歌单无曲目清单），优先返回该歌单；
     * 2. 若当前曲目属于另一张同步歌单，自动切换匹配（优先同时命中歌名与歌手）；
     * 3. 否则回退到上次记录的歌单（若仍在目录中）。
     */
    fun resolveActivePlaylist(currentTitle: String?, currentArtist: String? = null): PlaylistEntry? {
        val currentCatalog = catalog ?: return null
        if (currentCatalog.playlists.isEmpty()) return null
        val activeEntry = activePlaylistId?.let { targetId ->
            currentCatalog.playlists.firstOrNull { it.id == targetId }
        }
        val cleanTitle = currentTitle?.trim().orEmpty()
        if (activeEntry != null) {
            if (cleanTitle.isEmpty() || activeEntry.tracks.isEmpty()) return activeEntry
            if (activeEntry.tracks.any { it.matchesPlaying(cleanTitle, currentArtist) }) return activeEntry
        }
        if (cleanTitle.isNotEmpty()) {
            val strictMatch = currentCatalog.playlists.firstOrNull { entry ->
                entry.tracks.any { it.matchesPlaying(cleanTitle, currentArtist, requireArtist = true) }
            }
            if (strictMatch != null) return strictMatch
            val titleMatch = currentCatalog.playlists.firstOrNull { entry ->
                entry.tracks.any { it.matchesPlaying(cleanTitle, currentArtist, requireArtist = false) }
            }
            if (titleMatch != null) return titleMatch
        }
        return activeEntry
    }
}
