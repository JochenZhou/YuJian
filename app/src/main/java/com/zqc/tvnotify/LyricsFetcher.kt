package com.zqc.tvnotify

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private val METADATA_TAG_REGEX = Regex(
        """^\[(?:ti|ar|al|by|offset|id|hash|sign|qq|total|kana|language):.*\]$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(rawText: String, source: String = "lrc"): ParsedLyrics {
        val clean = rawText.trimStart('\uFEFF').trim()
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
            if (line.isEmpty() || METADATA_TAG_REGEX.matches(line)) {
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
            val plain = rawLines.map { it.trimStart('\uFEFF').trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
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

internal data class LyricFetchOutcome(val rawText: String, val source: String)

internal data class LyricSourceSpec(
    val name: String,
    val plainOnly: Boolean = false,
    val fetcher: (title: String, artist: String, album: String, durationMs: Long, isCancelled: () -> Boolean) -> String?
)

/**
 * 多源同步歌词获取与本地磁盘缓存管理：
 * 1. 优先使用曲目自带内嵌歌词；
 * 2. 检查本地 cacheDir 磁盘缓存；
 * 3. 通过 SafeNetworkHelper 按顺序请求多歌词源（LRCLIB -> QQ 音乐 -> 酷狗音乐 -> 网易云音乐 -> lyrics.ovh），
 *    优先采纳带时间轴的同步 LRC（若前置源仅返回纯文本歌词则继续尝试后续同步源），并持久化缓存有效结果。
 */
object LyricsFetcher {

    private const val TAG = "LyricsFetcher"
    private const val MAX_DISK_CACHE_FILES = 100
    private const val USER_AGENT = "YuJian-TV/${BuildConfig.VERSION_NAME} (https://github.com/JochenZhou/YuJian)"

    private val QQ_HEADERS = mapOf("Referer" to "https://y.qq.com/")
    private val NETEASE_HEADERS = mapOf("Referer" to "https://music.163.com/")

    private val ARTIST_SPLIT_REGEX = Regex("""[,/&、;×]|(?i)\b(?:feat\.?|ft\.?|vs\.?|with)\b""")
    private val BRACKET_SUFFIX_REGEX = Regex("""\s*[\(\[（【][^\)\]）】]*[\)\]）】]\s*""")
    private val DASH_VERSION_REGEX = Regex(
        """\s+-\s+(?:live|remaster(?:ed)?|acoustic|demo|radio\s+edit|instrumental|ver\.?|version|正式版|现场|伴奏).*$""",
        RegexOption.IGNORE_CASE
    )
    private val JSONP_CALLBACK_REGEX = Regex("""^[^(]+\((\{.*\})\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val BASE64_ONLY_REGEX = Regex("""^[A-Za-z0-9+/=_\-\r\n]+$""")
    private val CREDIT_LINE_REGEX = Regex(
        """^(?:作词|作曲|编曲|词|曲|制作人|混音|混音师|混音工程师|录音|录音师|录音室|和声|和声编写|合声|吉他|鼓|贝斯|弦乐|OP|SP|Lyrics by|Composed by|Produced by|Arranged by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )
    private val PLACEHOLDER_EXACT_SET = setOf(
        "纯音乐请欣赏",
        "此歌曲为没有填词的纯音乐请您欣赏",
        "此歌曲为没有填词的纯音乐",
        "没有填词的纯音乐",
        "纯音乐无歌词",
        "暂无歌词",
        "nolyrics",
        "nolyricsavailable",
        "instrumental"
    )

    private data class RankedCandidate<T>(
        val payload: T,
        val title: String,
        val artists: List<String>,
        val durationSec: Long
    )

    private val DEFAULT_SOURCES: List<LyricSourceSpec> = listOf(
        LyricSourceSpec(name = "lrclib", fetcher = ::queryLrclib),
        LyricSourceSpec(name = "qq", fetcher = ::queryQqMusic),
        LyricSourceSpec(name = "kugou", fetcher = ::queryKugou),
        LyricSourceSpec(name = "netease", fetcher = ::queryNetease),
        LyricSourceSpec(name = "lyrics_ovh", plainOnly = true, fetcher = ::queryLyricsOvh)
    )

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
        networkFetcher: ((String, String, String, Long) -> String?)? = null,
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
        val usableEmbedded = extractUsableLyrics(embeddedLyrics)
        if (usableEmbedded != null) {
            val parsed = LrcParser.parse(usableEmbedded, "embedded")
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

        // 2 & 3. 磁盘缓存读取与多源联网检索均在单线程守护线程池内串行执行
        executor.execute {
            if (requestId != latestRequestId.get()) return@execute

            var cacheDir: File? = null
            var cacheFile: File? = null
            try {
                cacheDir = cacheRootProvider().apply { mkdirs() }
                cacheFile = File(cacheDir, "$cacheKey.lrc")
                if (cacheFile.exists()) {
                    val cached = cacheFile.readText(Charsets.UTF_8)
                    val usableCached = extractUsableLyrics(cached)
                    if (usableCached != null) {
                        val parsed = LrcParser.parse(usableCached, "disk_cache")
                        if (parsed.lines.isNotEmpty()) {
                            if (requestId == latestRequestId.get()) onResult(parsed)
                            return@execute
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取本地歌词缓存失败", e)
            }

            if (requestId != latestRequestId.get()) return@execute

            try {
                val outcome = if (networkFetcher != null) {
                    val customText = networkFetcher(cleanTitle, cleanArtist, cleanAlbum, durationMs)
                    val usableCustom = customText?.let(::extractUsableLyrics)
                    if (usableCustom != null) LyricFetchOutcome(usableCustom, "lrclib") else null
                } else {
                    resolveFromProviders(
                        title = cleanTitle,
                        artist = cleanArtist,
                        album = cleanAlbum,
                        durationMs = durationMs,
                        isCancelled = { requestId != latestRequestId.get() }
                    )
                }

                if (outcome != null) {
                    if (cacheFile != null && cacheDir != null) {
                        try {
                            cacheFile.writeText(outcome.rawText, Charsets.UTF_8)
                            pruneCache(cacheDir)
                        } catch (e: Exception) {
                            Log.w(TAG, "写入歌词缓存失败", e)
                        }
                    }
                    if (requestId == latestRequestId.get()) {
                        onResult(LrcParser.parse(outcome.rawText, outcome.source))
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

    internal fun resolveFromProviders(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        sources: List<LyricSourceSpec> = DEFAULT_SOURCES,
        isCancelled: () -> Boolean = { false }
    ): LyricFetchOutcome? {
        var plainFallback: LyricFetchOutcome? = null
        for (source in sources) {
            if (isCancelled()) return null
            if (source.plainOnly && plainFallback != null) continue
            val raw = source.fetcher(title, artist, album, durationMs, isCancelled) ?: continue
            val usable = extractUsableLyrics(raw) ?: continue
            val parsed = LrcParser.parse(usable, source.name)
            if (parsed.synced && parsed.lines.isNotEmpty()) {
                return LyricFetchOutcome(usable, source.name)
            }
            if (parsed.lines.isNotEmpty() && plainFallback == null) {
                plainFallback = LyricFetchOutcome(usable, "plain")
            }
        }
        return plainFallback
    }

    private fun queryLrclib(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isCancelled: () -> Boolean
    ): String? {
        val encTitle = encodeQuery(title)
        val encArtist = encodeQuery(artist)
        val encAlbum = encodeQuery(album)
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)

        // 尝试 1: 精确获取 /api/get（仅当参数较为完整时）
        if (artist.isNotEmpty() && durSec > 0L) {
            val getUrl = buildString {
                append("https://lrclib.net/api/get?track_name=").append(encTitle)
                append("&artist_name=").append(encArtist)
                if (album.isNotEmpty()) append("&album_name=").append(encAlbum)
                append("&duration=").append(durSec)
            }
            fetchResponseBody(getUrl, connectTimeoutMs = 3800, readTimeoutMs = 4000)?.let { body ->
                parseLrclibGetResponse(body)?.let { return it }
            }
        }

        if (isCancelled()) return null

        // 尝试 2: 模糊搜索 /api/search
        val searchUrl = if (artist.isNotEmpty()) {
            "https://lrclib.net/api/search?track_name=$encTitle&artist_name=$encArtist"
        } else {
            "https://lrclib.net/api/search?track_name=$encTitle"
        }
        val body = fetchResponseBody(searchUrl, connectTimeoutMs = 4200, readTimeoutMs = 4200) ?: return null
        return parseLrclibSearchResponse(body, durSec)
    }

    internal fun parseLrclibGetResponse(body: String): String? {
        val obj = parseJsonObject(body) ?: return null
        if (obj.optBoolean("instrumental", false)) return null
        return extractUsableLyrics(obj.optCleanString("syncedLyrics"))
            ?: extractUsableLyrics(obj.optCleanString("plainLyrics"))
    }

    internal fun parseLrclibSearchResponse(body: String, durSec: Long): String? {
        return try {
            val array = JSONArray(body)
            var bestSynced: String? = null
            var fallbackPlain: String? = null

            for (i in 0 until minOf(array.length(), 10)) {
                val item = array.getJSONObject(i)
                if (item.optBoolean("instrumental", false)) continue
                val itemDuration = item.optDouble("duration", 0.0)
                val itemSynced = extractUsableLyrics(item.optCleanString("syncedLyrics"))
                val itemPlain = extractUsableLyrics(item.optCleanString("plainLyrics"))

                if (itemSynced != null) {
                    if (durSec <= 0L || abs(itemDuration - durSec) <= 4.0) {
                        return itemSynced
                    }
                    if (bestSynced == null) {
                        bestSynced = itemSynced
                    }
                } else if (itemPlain != null && fallbackPlain == null) {
                    fallbackPlain = itemPlain
                }
            }
            bestSynced ?: fallbackPlain
        } catch (_: Exception) {
            null
        }
    }

    private fun queryQqMusic(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isCancelled: () -> Boolean
    ): String? {
        val searchQuery = buildSearchKeyword(title, artist)
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${encodeQuery(searchQuery)}&p=1&n=8&format=json"
        val searchBody = fetchResponseBody(searchUrl, extraHeaders = QQ_HEADERS) ?: return null
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)
        val mids = selectQqCandidateMids(searchBody, title, artist, durSec)
        if (mids.isEmpty()) return null

        var plainCandidate: String? = null
        for (mid in mids) {
            if (isCancelled()) return null
            val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${encodeQuery(mid)}&format=json&nobase64=1"
            val lyricBody = fetchResponseBody(lyricUrl, extraHeaders = QQ_HEADERS) ?: continue
            val usable = parseQqLyricResponse(lyricBody) ?: continue
            if (LrcParser.parse(usable, "qq").synced) return usable
            if (plainCandidate == null) plainCandidate = usable
        }
        return plainCandidate
    }

    internal fun selectQqCandidateMids(searchBody: String, title: String, artist: String, durSec: Long): List<String> {
        val root = parseJsonObject(searchBody) ?: return emptyList()
        val songs = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
        return rankJsonCandidates(songs, title, artist, durSec) { item ->
            val mid = item.optCleanString("songmid")
            if (mid.isEmpty()) return@rankJsonCandidates null
            val songTitle = item.optCleanString("songname")
            val singersArr = item.optJSONArray("singer")
            val singers = extractNameList(singersArr)
            val interval = item.optLong("interval", 0L).coerceAtLeast(0L)
            RankedCandidate(mid, songTitle, singers, interval)
        }
    }

    internal fun parseQqLyricResponse(lyricBody: String): String? {
        val root = parseJsonObject(lyricBody) ?: return null
        if (root.optInt("retcode", -1) != 0 && root.optInt("code", -1) != 0) return null
        var raw = root.optCleanString("lyric")
        if (raw.isEmpty()) return null
        if (!raw.contains('[') && !raw.contains('\n') && BASE64_ONLY_REGEX.matches(raw) &&
            (raw.startsWith("Wz") || raw.startsWith("W3") || raw.startsWith("77u/"))
        ) {
            val decoded = decodeBase64Utf8(raw)
            if (decoded.isNotBlank()) raw = decoded
        }
        return extractUsableLyrics(decodeHtmlEntities(raw))
    }

    private fun queryKugou(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isCancelled: () -> Boolean
    ): String? {
        val primaryArtist = extractPrimaryArtist(artist)
        val keyword = if (primaryArtist.isNotEmpty()) "$primaryArtist - $title" else title
        val searchUrl = buildString {
            append("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=").append(encodeQuery(keyword))
            if (durationMs > 0L) append("&duration=").append(durationMs)
        }
        val searchBody = fetchResponseBody(searchUrl) ?: return null
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)
        val candidates = selectKugouCandidates(searchBody, title, artist, durSec)
        if (candidates.isEmpty()) return null

        var plainCandidate: String? = null
        for ((id, accesskey) in candidates) {
            if (isCancelled()) return null
            val downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${encodeQuery(id)}&accesskey=${encodeQuery(accesskey)}&fmt=lrc&charset=utf8"
            val downloadBody = fetchResponseBody(downloadUrl) ?: continue
            val usable = parseKugouDownloadResponse(downloadBody) ?: continue
            if (LrcParser.parse(usable, "kugou").synced) return usable
            if (plainCandidate == null) plainCandidate = usable
        }
        return plainCandidate
    }

    internal fun selectKugouCandidates(searchBody: String, title: String, artist: String, durSec: Long): List<Pair<String, String>> {
        val root = parseJsonObject(searchBody) ?: return emptyList()
        if (root.optInt("status", 0) != 200) return emptyList()
        val array = root.optJSONArray("candidates")
        return rankJsonCandidates(array, title, artist, durSec) { item ->
            val id = item.optCleanString("id")
            val key = item.optCleanString("accesskey")
            if (id.isEmpty() || key.isEmpty()) return@rankJsonCandidates null
            val candSong = item.optCleanString("song")
            val candSinger = item.optCleanString("singer")
            val candDurSec = (item.optLong("duration", 0L) / 1000L).coerceAtLeast(0L)
            RankedCandidate(id to key, candSong, if (candSinger.isEmpty()) emptyList() else listOf(candSinger), candDurSec)
        }
    }

    internal fun parseKugouDownloadResponse(downloadBody: String): String? {
        val root = parseJsonObject(downloadBody) ?: return null
        if (root.optInt("status", 0) != 200) return null
        val encoded = root.optCleanString("content")
        if (encoded.isEmpty()) return null
        val decoded = decodeBase64Utf8(encoded)
        return extractUsableLyrics(decoded)
    }

    private fun queryNetease(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isCancelled: () -> Boolean
    ): String? {
        val searchQuery = buildSearchKeyword(title, artist)
        val searchUrl = "https://music.163.com/api/search/get?s=${encodeQuery(searchQuery)}&type=1&limit=8"
        val searchBody = fetchResponseBody(searchUrl, extraHeaders = NETEASE_HEADERS) ?: return null
        val durSec = (durationMs / 1000L).coerceAtLeast(0L)
        val ids = selectNeteaseCandidateIds(searchBody, title, artist, durSec)
        if (ids.isEmpty()) return null

        var plainCandidate: String? = null
        for (songId in ids) {
            if (isCancelled()) return null
            val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1"
            val lyricBody = fetchResponseBody(lyricUrl, extraHeaders = NETEASE_HEADERS) ?: continue
            val usable = parseNeteaseLyricResponse(lyricBody) ?: continue
            if (LrcParser.parse(usable, "netease").synced) return usable
            if (plainCandidate == null) plainCandidate = usable
        }
        return plainCandidate
    }

    internal fun selectNeteaseCandidateIds(searchBody: String, title: String, artist: String, durSec: Long): List<Long> {
        val root = parseJsonObject(searchBody) ?: return emptyList()
        val songs = root.optJSONObject("result")?.optJSONArray("songs")
        return rankJsonCandidates(songs, title, artist, durSec) { item ->
            val id = item.optLong("id", 0L)
            if (id <= 0L) return@rankJsonCandidates null
            val songTitle = item.optCleanString("name")
            val artists = extractNameList(item.optJSONArray("artists"))
            val songDurSec = (item.optLong("duration", 0L) / 1000L).coerceAtLeast(0L)
            RankedCandidate(id, songTitle, artists, songDurSec)
        }
    }

    internal fun parseNeteaseLyricResponse(lyricBody: String): String? {
        val root = parseJsonObject(lyricBody) ?: return null
        if (root.optBoolean("nolyric", false) ||
            root.optBoolean("pureMusic", false) ||
            root.optBoolean("uncollected", false)
        ) {
            return null
        }
        val raw = root.optJSONObject("lrc")?.optCleanString("lyric") ?: return null
        return extractUsableLyrics(raw)
    }

    private fun queryLyricsOvh(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        isCancelled: () -> Boolean
    ): String? {
        val primaryArtist = extractPrimaryArtist(artist)
        if (primaryArtist.isEmpty() || isCancelled()) return null
        val url = "https://api.lyrics.ovh/v1/${encodePathSegment(primaryArtist)}/${encodePathSegment(title)}"
        val body = fetchResponseBody(url, connectTimeoutMs = 3500, readTimeoutMs = 3500) ?: return null
        return parseLyricsOvhResponse(body)
    }

    internal fun parseLyricsOvhResponse(body: String): String? {
        val root = parseJsonObject(body) ?: return null
        return extractUsableLyrics(root.optCleanString("lyrics"))
    }

    private fun fetchResponseBody(
        urlStr: String,
        connectTimeoutMs: Int = 3500,
        readTimeoutMs: Int = 4000,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = SafeNetworkHelper.openConnection(
                urlStr = urlStr,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                userAgent = USER_AGENT,
                acceptHeader = "application/json",
                extraHeaders = extraHeaders
            )
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求歌词接口异常: $urlStr", e)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun <T> rankJsonCandidates(
        array: JSONArray?,
        title: String,
        artist: String,
        durSec: Long,
        limit: Int = 2,
        extractor: (JSONObject) -> RankedCandidate<T>?
    ): List<T> {
        if (array == null || array.length() == 0) return emptyList()
        val scored = ArrayList<Pair<T, Int>>()
        for (i in 0 until minOf(array.length(), 10)) {
            val item = array.optJSONObject(i) ?: continue
            val cand = extractor(item) ?: continue
            val score = scoreTrackCandidate(
                queryTitle = title,
                queryArtist = artist,
                targetDurSec = durSec,
                candTitle = cand.title,
                candArtists = cand.artists,
                candDurSec = cand.durationSec
            )
            if (score >= 0) {
                // Keep slight tie-breaker bonus for earlier search relevance order.
                scored.add(cand.payload to (score * 16 + (10 - i)))
            }
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    internal fun scoreTrackCandidate(
        queryTitle: String,
        queryArtist: String,
        targetDurSec: Long,
        candTitle: String,
        candArtists: List<String>,
        candDurSec: Long
    ): Int {
        val qExact = normalizeMatchKey(queryTitle)
        val cExact = normalizeMatchKey(candTitle)
        val qBase = normalizeMatchKey(stripTitleDecorations(queryTitle))
        val cBase = normalizeMatchKey(stripTitleDecorations(candTitle))

        val titleScore = when {
            qExact.isNotEmpty() && cExact == qExact -> 30
            qBase.isNotEmpty() && cBase == qBase -> 20
            qBase.length >= 2 && cBase.contains(qBase) -> 10
            cBase.length >= 2 && qBase.contains(cBase) -> 10
            else -> return -1
        }

        if (queryArtist.isNotBlank() && !artistsOverlap(queryArtist, candArtists)) {
            return -1
        }

        val durationBonus = when {
            targetDurSec > 0L && candDurSec > 0L -> {
                val diff = abs(candDurSec - targetDurSec)
                when {
                    diff <= 6L -> (30 - diff.toInt() * 3)
                    diff <= 15L && titleScore >= 20 -> 5
                    else -> return -1
                }
            }
            targetDurSec <= 0L && candDurSec in 1L..59L -> -8
            else -> 2
        }

        return (titleScore + durationBonus).coerceAtLeast(1)
    }

    internal fun extractUsableLyrics(rawText: String): String? {
        val cleaned = rawText.trimStart('\uFEFF').trim()
        if (cleaned.isEmpty()) return null
        val parsed = LrcParser.parse(cleaned, "check")
        if (parsed.lines.isEmpty()) return null

        var hasPlaceholder = false
        var contentLines = 0
        for (line in parsed.lines) {
            val text = line.text.trim()
            if (text.isEmpty() || CREDIT_LINE_REGEX.containsMatchIn(text)) continue
            val normalized = normalizeMatchKey(text)
            if (normalized in PLACEHOLDER_EXACT_SET || normalized.contains("没有填词的纯音乐")) {
                hasPlaceholder = true
            } else {
                contentLines++
            }
        }
        if (contentLines == 0 || (hasPlaceholder && contentLines <= 1)) {
            return null
        }
        return cleaned
    }

    internal fun decodeBase64Utf8(encoded: String): String {
        val out = ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (ch in encoded) {
            val value = when (ch) {
                in 'A'..'Z' -> ch.code - 'A'.code
                in 'a'..'z' -> ch.code - 'a'.code + 26
                in '0'..'9' -> ch.code - '0'.code + 52
                '+', '-' -> 62
                '/', '_' -> 63
                '=' -> break
                else -> continue
            }
            acc = (acc shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8).trimStart('\uFEFF')
    }

    internal fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return text
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&#10;", "\n")
            .replace("&#13;", "")
            .replace("&amp;", "&")
    }

    private fun parseJsonObject(body: String): JSONObject? {
        val trimmed = body.trimStart('\uFEFF').trim()
        if (trimmed.isEmpty()) return null
        val jsonText = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            JSONP_CALLBACK_REGEX.matchEntire(trimmed)?.groupValues?.get(1) ?: return null
        }
        return try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optCleanString(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return optString(key, "").trim()
    }

    private fun extractNameList(array: JSONArray?): List<String> {
        if (array == null || array.length() == 0) return emptyList()
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val name = array.optJSONObject(i)?.optCleanString("name").orEmpty()
            if (name.isNotEmpty()) list.add(name)
        }
        return list
    }

    private fun extractPrimaryArtist(artist: String): String {
        if (artist.isBlank()) return ""
        return ARTIST_SPLIT_REGEX.split(artist)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun buildSearchKeyword(title: String, artist: String): String {
        val primaryArtist = extractPrimaryArtist(artist)
        return if (primaryArtist.isNotEmpty()) "$title $primaryArtist" else title
    }

    private fun artistsOverlap(queryArtist: String, candArtists: List<String>): Boolean {
        val qTokens = ARTIST_SPLIT_REGEX.split(queryArtist)
            .map(::normalizeMatchKey)
            .filter { it.isNotEmpty() }
        if (qTokens.isEmpty()) return true

        val cTokens = candArtists
            .flatMap { ARTIST_SPLIT_REGEX.split(it) }
            .map(::normalizeMatchKey)
            .filter { it.isNotEmpty() }
        if (cTokens.isEmpty()) return false

        for (q in qTokens) {
            for (c in cTokens) {
                if (q == c) return true
                if (q.length >= 2 && c.length >= 2 && (q.contains(c) || c.contains(q))) {
                    return true
                }
            }
        }
        return false
    }

    private fun stripTitleDecorations(title: String): String {
        val noBrackets = title.replace(BRACKET_SUFFIX_REGEX, " ").trim()
        return noBrackets.replace(DASH_VERSION_REGEX, "").trim()
    }

    private fun normalizeMatchKey(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.lowercase(Locale.ROOT)) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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
