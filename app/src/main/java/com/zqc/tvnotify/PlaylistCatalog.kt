package com.zqc.tvnotify

import org.json.JSONObject

/**
 * 歌单目录：Home Assistant 的玉简集成把 Music Assistant 的歌单通过
 * `POST /{token}/library` 推送到电视，这里是那份负载的内存模型与解析器。
 *
 * 封面 `cover_b64` 是完整的 data URI（`data:image/...;base64,...`），解析阶段
 * 只做字符串保存，解码成 Bitmap 交给 UI 层按需进行。
 */
data class PlaylistEntry(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val coverBase64: String?
)

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
                entries.add(PlaylistEntry(id, name, trackCount, cover))
            }
            val callback = root.optJSONObject("callback")
            return PlaylistCatalog(
                targetEntityId = root.optString("target_entity_id", "").trim(),
                callbackUrl = callback?.optString("url", "")?.trim() ?: "",
                callbackToken = callback?.optString("token", "")?.trim() ?: "",
                playlists = entries
            )
        }
    }
}

/**
 * 进程内唯一的歌单目录缓存。HTTP 服务线程写、播放页 UI 线程读，
 * 用 [Volatile] 保证跨线程可见性。
 */
object PlaylistStore {
    @Volatile
    private var catalog: PlaylistCatalog? = null

    fun update(catalog: PlaylistCatalog?) {
        this.catalog = catalog
    }

    fun current(): PlaylistCatalog? = catalog
}
