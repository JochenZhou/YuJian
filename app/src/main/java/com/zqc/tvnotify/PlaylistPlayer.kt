package com.zqc.tvnotify

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 向 Home Assistant 的玉简集成 webhook 发起「播放这张歌单」请求。
 *
 * 回调地址与 token 由 HA 随歌单目录一起推送过来（见 [PlaylistCatalog]），
 * 电视端零配置。okhttp 是既有依赖，这里不引入任何新库。
 */
object PlaylistPlayer {

    private val JSON_MIME = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    internal fun buildRequestPayload(
        id: String,
        name: String,
        trackId: String? = null,
        trackName: String? = null,
        model: String? = android.os.Build.MODEL
    ): JSONObject {
        val body = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("model", (model ?: "").trim())
        val cleanTrackId = trackId?.trim().orEmpty()
        if (cleanTrackId.isNotEmpty()) {
            body.put("track_id", cleanTrackId)
            val cleanTrackName = trackName?.trim().orEmpty()
            if (cleanTrackName.isNotEmpty()) {
                body.put("track_name", cleanTrackName)
            }
        }
        return body
    }

    /**
     * 异步发起点播。回调可能来自 okhttp 的 IO 线程，调用方需自行切回主线程。
     *
     * 请求体带上本机型号，HA 侧据此把点播落到这台电视自己的 Music Assistant
     * 播放器上；传入 [trackId] 时播放歌单内指定曲目并续播后续歌单，不传则整单从头播。
     */
    fun request(
        url: String,
        token: String,
        id: String,
        name: String,
        trackId: String? = null,
        trackName: String? = null,
        onResult: (ok: Boolean, error: String?) -> Unit
    ) {
        val body = buildRequestPayload(id, name, trackId, trackName).toString()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_MIME))
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false, e.message ?: "网络错误")
            }

            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                // 失败时把 HA 给的说明（例如“未指定目标播放器”）透出来，
                // 否则用户只看到 HTTP 409 无从下手。
                val error = if (ok) {
                    null
                } else {
                    val text = try {
                        response.body?.string().orEmpty()
                    } catch (_: IOException) {
                        ""
                    }
                    jsonError(text) ?: "HTTP ${response.code}"
                }
                response.close()
                onResult(ok, error)
            }
        })
    }

    /** 从 HA 的错误响应里取出可读的 error 字段。 */
    private fun jsonError(text: String): String? {
        if (text.isBlank()) return null
        return try {
            JSONObject(text).optString("error").trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
