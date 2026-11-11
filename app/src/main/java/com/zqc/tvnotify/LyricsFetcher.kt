package com.zqc.tvnotify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class LrcLine(val timeMs: Long, val text: String)

data class ParsedLyrics(
    val synced: Boolean,
    val lines: List<LrcLine>,
    val source: String
)

/**
 * 标准 LRC 歌词解析器：
 * 支持 [offset:+/-ms] 偏移、毫秒多精度变长（.x / .xx / .xxx）、一行多时间戳标签扩展、
 * 负偏移截断保护及纯文本无轴歌词优雅回退。
 */
object LrcParser {

    private val OFFSET_REGEX = Regex("""\[offset:([+-]?\d+)\]""", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")

    fun parse(rawText: String, source: String = "lrc"): ParsedLyrics {
        val clean = rawText.trim()
        if (clean.isEmpty()) {
            return ParsedLyrics(synced = false, lines = emptyList(), source = source)
        }

        var offsetMs = 0L
        val offsetMatch = OFFSET_REGEX.find(clean)
        if (offsetMatch != null) {
            offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
        }

        val resultLines = ArrayList<LrcLine>()
        val rawLines = clean.lines()

        for (rawLine in rawLines) {
            val line = rawLine.trim()
            if (line.isEmpty() ||
                line.startsWith("[ti:", true) ||
                line.startsWith("[ar:", true) ||
                line.startsWith("[al:", true) ||
                line.startsWith("[by:", true) ||
                line.startsWith("[offset:", true)
            ) {
                continue
            }

            val matches = TAG_REGEX.findAll(line).toList()
            if (matches.isEmpty()) {
                continue
            }

            val text = line.replace(TAG_REGEX, "").trim()
            if (text.isEmpty()) continue

            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = m.groupValues[3]
                var ms = 0L
                if (fracStr.isNotEmpty()) {
                    val rawFrac = fracStr.toLongOrNull() ?: 0L
                    ms = when (fracStr.length) {
                        1 -> rawFrac * 100L
                        2 -> rawFrac * 10L
                        else -> rawFrac
                    }
                }
                val totalMs = (min * 60_000L + sec * 1_000L + ms + offsetMs).coerceAtLeast(0L)
                resultLines.add(LrcLine(totalMs, text))
            }
        }

        if (resultLines.isEmpty()) {
            val plain = rawLines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
            return ParsedLyrics(
                synced = false,
                lines = plain.map { LrcLine(0L, it) },
                source = "plain"
            )
        }

        resultLines.sortBy { it.timeMs }
        return ParsedLyrics(synced = true, lines = resultLines, source = source)
    }

    /**
     * 查找当前毫秒时刻对应的已唱高亮行索引，
     * 未开始返回 -1，若时间已超过最后一行则停留在最后一行。
     */
    fun findActiveLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines[0].timeMs) {
            return -1
        }
        var low = 0
        var high = lines.size - 1
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }
}

/**
 * 多源同步歌词获取与本地磁盘缓存管理：
 * 1. 优先使用曲目自带内嵌歌词；
 * 2. 检查本地 cacheDir 磁盘缓存；
 * 3. 通过 SafeNetworkHelper 请求 lrclib.net 开放 API（穿透 Android 6.0 证书兼容限制），
 *    具备精确搜索与时长误差容差匹配能力。
 */
object LyricsFetcher {

    private const val TAG = "LyricsFetcher"
    private const val MAX_DISK_CACHE_FILES = 100
    private const val USER_AGENT = "YuJian-TV/${BuildConfig.VERSION_NAME} (https://github.com/JochenZhou/YuJian)"

    private val latestRequestId = AtomicLong(0L)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "sendspin-lyrics").apply { isDaemon = true }
    }

    fun fetchAsync(
        context: Context,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        embeddedLyrics: String = "",
        onResult: (ParsedLyrics?) -> Unit
    ) {
        val appContext = context.applicationContext
        fetchWithCacheDirAsync(
            cacheRootProvider = { File(appContext.cacheDir, "sendspin_lyrics") },
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            embeddedLyrics = embeddedLyrics,
            onResult = onResult
        )
    }

    internal fun fetchWithCacheDirAsync(
        cacheRootProvider: () -> File,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        embeddedLyrics: String = "",
        networkFetcher: (String, String, String, Long) -> String? = ::queryLrclib,
        onResult: (ParsedLyrics?) -> Unit
    ) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty()) {
            latestRequestId.incrementAndGet()
            onResult(null)
            return
        }

        // 1. 内嵌歌词优先（纯内存解析，不触碰磁盘）
        if (embeddedLyrics.isNotBlank()) {
            val parsed = LrcParser.parse(embeddedLyrics, "embedded")
            if (parsed.lines.isNotEmpty()) {
                latestRequestId.incrementAndGet()
                onResult(parsed)
                return
            }
        }

        val requestId = latestRequestId.incrementAndGet()
        val cleanAlbum = album.trim()
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)
        val cacheKey = sha256Hex(
            "${cleanTitle.lowercase(Locale.ROOT)}|${cleanArtist.lowercase(Locale.ROOT)}|$durSec"
        )

        // 2 & 3. 磁盘缓存读取与 lrclib.net 检索均在单线程守护线程池内串行执行
        executor.execute {
            if (requestId != latestRequestId.get()) return@execute

            var cacheDir: File? = null
            var cacheFile: File? = null
            try {
                cacheDir = cacheRootProvider().apply { mkdirs() }
                cacheFile = File(cacheDir, "$cacheKey.lrc")
                if (cacheFile.exists()) {
                    val cached = cacheFile.readText(Charsets.UTF_8)
                    val parsed = LrcParser.parse(cached, "disk_cache")
                    if (parsed.lines.isNotEmpty()) {
                        if (requestId == latestRequestId.get()) onResult(parsed)
                        return@execute
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取本地歌词缓存失败", e)
            }

            if (requestId != latestRequestId.get()) return@execute

            try {
                val lyricsText = networkFetcher(cleanTitle, cleanArtist, cleanAlbum, durationMs)
                if (!lyricsText.isNullOrBlank()) {
                    if (cacheFile != null && cacheDir != null) {
                        try {
                            cacheFile.writeText(lyricsText, Charsets.UTF_8)
                            pruneCache(cacheDir)
                        } catch (e: Exception) {
                            Log.w(TAG, "写入歌词缓存失败", e)
                        }
                    }
                    if (requestId == latestRequestId.get()) {
                        onResult(LrcParser.parse(lyricsText, "lrclib"))
                    }
                } else if (requestId == latestRequestId.get()) {
                    onResult(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "检索歌词异常", e)
                if (requestId == latestRequestId.get()) {
                    onResult(null)
                }
            }
        }
    }

    private fun queryLrclib(title: String, artist: String, album: String, durationMs: Long): String? {
        val encTitle = URLEncoder.encode(title, "UTF-8")
        val encArtist = URLEncoder.encode(artist, "UTF-8")
        val encAlbum = URLEncoder.encode(album, "UTF-8")
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)

        // 尝试 1: 精确获取 /api/get（仅当参数较为完整时）
        if (artist.isNotEmpty() && durSec > 0L) {
            val getUrl = buildString {
                append("https://lrclib.net/api/get?track_name=").append(encTitle)
                append("&artist_name=").append(encArtist)
                if (album.isNotEmpty()) append("&album_name=").append(encAlbum)
                append("&duration=").append(durSec)
            }
            val firstResult = requestJson(getUrl)
            if (firstResult != null) {
                val synced = firstResult.optString("syncedLyrics", "").trim()
                if (synced.isNotEmpty()) return synced
                val plain = firstResult.optString("plainLyrics", "").trim()
                if (plain.isNotEmpty()) return plain
            }
        }

        // 尝试 2: 模糊搜索 /api/search
        val searchUrl = if (artist.isNotEmpty()) {
            "https://lrclib.net/api/search?track_name=$encTitle&artist_name=$encArtist"
        } else {
            "https://lrclib.net/api/search?track_name=$encTitle"
        }
        var conn: HttpURLConnection? = null
        try {
            conn = SafeNetworkHelper.openConnection(searchUrl, 4500, 4500, USER_AGENT, "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val array = JSONArray(body)
                var bestSynced: String? = null
                var fallbackPlain: String? = null

                for (i in 0 until minOf(array.length(), 10)) {
                    val item = array.getJSONObject(i)
                    val itemDuration = item.optDouble("duration", 0.0)
                    val itemSynced = item.optString("syncedLyrics", "").trim()
                    val itemPlain = item.optString("plainLyrics", "").trim()

                    if (itemSynced.isNotEmpty()) {
                        if (durSec <= 0L || abs(itemDuration - durSec) <= 4.0) {
                            return itemSynced
                        }
                        if (bestSynced == null) {
                            bestSynced = itemSynced
                        }
                    } else if (itemPlain.isNotEmpty() && fallbackPlain == null) {
                        fallbackPlain = itemPlain
                    }
                }
                return bestSynced ?: fallbackPlain
            }
        } catch (e: Exception) {
            Log.w(TAG, "调用 lrclib 搜索接口异常", e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
        return null
    }

    private fun requestJson(urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = SafeNetworkHelper.openConnection(urlStr, 4000, 4000, USER_AGENT, "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(text)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun pruneCache(cacheDir: File) {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= MAX_DISK_CACHE_FILES) return
        val sorted = files.sortedBy { it.lastModified() }
        val toRemove = sorted.take(files.size - MAX_DISK_CACHE_FILES)
        for (f in toRemove) {
            try { f.delete() } catch (_: Exception) {}
        }
    }
}
