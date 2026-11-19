package com.zqc.tvnotify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Extracted album artwork colors for WLED segment 0 primary color synchronization. */
data class WledArtworkColors(
    val primaryRgb: Int?,
    val secondaryRgb: Int?,
    val selectedRgb: Int?
)

/**
 * Extracts dominant primary and distinct secondary colors from album artwork bitmaps.
 * Any color that displays poorly on WLED RGB strips (black, near-black, dark dim shades,
 * or gray / low-saturation dull tones) is skipped in favor of a vivid secondary color;
 * if no sufficiently visible color exists, yields null.
 */
object WledArtworkColorExtractor {
    const val DARK_LUMINANCE_THRESHOLD = 0.035
    const val MIN_VISIBLE_CHANNEL = 88
    const val MIN_VISIBLE_CHROMA = 34
    const val MIN_VISIBLE_SATURATION = 0.24
    private const val SAMPLE_MAX_DIM = 32
    private const val HIST_BITS = 4
    private const val HIST_SIDE = 1 shl HIST_BITS
    private const val HIST_SIZE = HIST_SIDE * HIST_SIDE * HIST_SIDE
    private const val MERGE_DISTANCE = 42.0
    private const val DISTINCT_DISTANCE = 48.0

    private data class ColorCluster(
        var count: Int,
        var sumR: Long,
        var sumG: Long,
        var sumB: Long
    ) {
        val avgR: Int get() = (sumR.toDouble() / count).roundToInt().coerceIn(0, 255)
        val avgG: Int get() = (sumG.toDouble() / count).roundToInt().coerceIn(0, 255)
        val avgB: Int get() = (sumB.toDouble() / count).roundToInt().coerceIn(0, 255)
        fun toRgbInt(): Int = Color.rgb(avgR, avgG, avgB)
    }

    fun relativeLuminance(rgb: Int): Double {
        val r = linearize(Color.red(rgb))
        val g = linearize(Color.green(rgb))
        val b = linearize(Color.blue(rgb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** True when the color is too dark / dim to show cleanly on an RGB LED strip. */
    fun isDarkColor(rgb: Int): Boolean {
        val maxC = maxOf(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        if (maxC < MIN_VISIBLE_CHANNEL) return true
        return relativeLuminance(rgb) < DARK_LUMINANCE_THRESHOLD && maxC < 120
    }

    /**
     * True when the color is either too dark OR too grayish / washed-out to produce
     * an obvious, vivid color on WLED LEDs (black, charcoal, dark/mid/light gray, dull beige).
     */
    fun isWeakOrDarkColor(rgb: Int): Boolean {
        if (isDarkColor(rgb)) return true
        val r = Color.red(rgb)
        val g = Color.green(rgb)
        val b = Color.blue(rgb)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val chroma = maxC - minC
        val saturation = if (maxC == 0) 0.0 else chroma.toDouble() / maxC.toDouble()
        return chroma < MIN_VISIBLE_CHROMA || saturation < MIN_VISIBLE_SATURATION
    }

    fun extract(bitmap: Bitmap): WledArtworkColors {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return WledArtworkColors(null, null, null)
        }
        val targetW = min(bitmap.width, SAMPLE_MAX_DIM)
        val targetH = min(bitmap.height, SAMPLE_MAX_DIM)
        val sample = if (bitmap.width == targetW && bitmap.height == targetH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, false)
        }
        val pixels = IntArray(targetW * targetH)
        try {
            sample.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        } finally {
            if (sample !== bitmap && !sample.isRecycled) {
                sample.recycle()
            }
        }

        val counts = IntArray(HIST_SIZE)
        val sumR = LongArray(HIST_SIZE)
        val sumG = LongArray(HIST_SIZE)
        val sumB = LongArray(HIST_SIZE)
        var opaqueCount = 0

        for (px in pixels) {
            if (Color.alpha(px) < 128) continue
            opaqueCount++
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)
            val bin = ((r ushr (8 - HIST_BITS)) shl (HIST_BITS * 2)) or
                ((g ushr (8 - HIST_BITS)) shl HIST_BITS) or
                (b ushr (8 - HIST_BITS))
            counts[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
        }

        if (opaqueCount == 0) {
            return WledArtworkColors(null, null, null)
        }

        val rawClusters = ArrayList<ColorCluster>()
        for (i in 0 until HIST_SIZE) {
            val c = counts[i]
            if (c > 0) {
                rawClusters.add(ColorCluster(c, sumR[i], sumG[i], sumB[i]))
            }
        }
        rawClusters.sortByDescending { it.count }

        val merged = ArrayList<ColorCluster>()
        for (bin in rawClusters) {
            var matched: ColorCluster? = null
            for (existing in merged) {
                if (rgbDistance(existing, bin) < MERGE_DISTANCE) {
                    matched = existing
                    break
                }
            }
            if (matched != null) {
                matched.count += bin.count
                matched.sumR += bin.sumR
                matched.sumG += bin.sumG
                matched.sumB += bin.sumB
            } else {
                merged.add(bin)
            }
        }
        merged.sortByDescending { it.count }

        val primaryCluster = merged[0]
        val primaryRgb = primaryCluster.toRgbInt()
        val minSecondaryCount = maxOf(2, opaqueCount / 100)

        var secondaryRgb: Int? = null
        if (isWeakOrDarkColor(primaryRgb)) {
            // Prefer a distinct, clearly visible chromatic secondary cluster first.
            for (i in 1 until merged.size) {
                val candidate = merged[i]
                if (candidate.count < minSecondaryCount) continue
                val candidateRgb = candidate.toRgbInt()
                if (!isWeakOrDarkColor(candidateRgb) && rgbDistance(primaryCluster, candidate) >= DISTINCT_DISTANCE) {
                    secondaryRgb = candidateRgb
                    break
                }
            }
            // Fallback: allow a nearby vibrant cluster when primary is mid-gray / muted.
            if (secondaryRgb == null) {
                for (i in 1 until merged.size) {
                    val candidate = merged[i]
                    if (candidate.count < minSecondaryCount) continue
                    val candidateRgb = candidate.toRgbInt()
                    if (!isWeakOrDarkColor(candidateRgb)) {
                        secondaryRgb = candidateRgb
                        break
                    }
                }
            }
        }
        if (secondaryRgb == null) {
            for (i in 1 until merged.size) {
                val candidate = merged[i]
                if (rgbDistance(primaryCluster, candidate) >= DISTINCT_DISTANCE) {
                    secondaryRgb = candidate.toRgbInt()
                    break
                }
            }
        }

        val selectedRgb = when {
            !isWeakOrDarkColor(primaryRgb) -> primaryRgb
            secondaryRgb != null && !isWeakOrDarkColor(secondaryRgb) -> secondaryRgb
            else -> null
        }
        return WledArtworkColors(
            primaryRgb = primaryRgb,
            secondaryRgb = secondaryRgb,
            selectedRgb = selectedRgb
        )
    }

    private fun linearize(channel: Int): Double {
        val c = (channel and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun rgbDistance(a: ColorCluster, b: ColorCluster): Double {
        val dr = (a.avgR - b.avgR).toDouble()
        val dg = (a.avgG - b.avgG).toDouble()
        val db = (a.avgB - b.avgB).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }
}

/**
 * Curated 1D AudioReactive WLED effects verified against WLED v16 `/json/fxdata`.
 * Only applied when explicitly chosen by the user; passive artwork/audio sync never overwrites WLED effects.
 */
enum class WledEffectPreset(
    val storageId: String,
    val displayName: String,
    val subtitle: String,
    val wledName: String,
    val fallbackFxId: Int,
    val defaultSpeed: Int,
    val defaultIntensity: Int
) {
    CUSTOM("custom", "保持当前效果", "不改动灯带自身效果，仅同步音频律动与封面色彩", "", -1, 128, 128),
    GRAVCENTRIC("gravcentric", "中轴舒展 (Gravcentric)", "从中点向两端实时对称扩散，一维灯带最耐看（推荐）", "Gravcentric", 157, 128, 165),
    GRAVIMETER("gravimeter", "呼吸音柱 (Gravimeter)", "由底向上的实时声压音柱，低频与人声起伏清晰", "Gravimeter", 132, 128, 165),
    GRAVFREQ("gravfreq", "频谱音柱 (Gravfreq)", "按实时主频变色的动态音柱，高低音层次鲜明", "Gravfreq", 158, 128, 165),
    PIXELWAVE("pixelwave", "音浪流光 (Pixelwave)", "从中心向两侧涌动的实时音乐光浪", "Pixelwave", 129, 136, 148),
    PIXELS("pixels", "星点跃动 (Pixels)", "随节拍闪烁跃动的彩色星点，轻盈灵动", "Pixels", 128, 128, 160),
    PUDDLES("puddles", "音律涟漪 (Puddles)", "由音乐节拍触发的扩散水波光晕", "Puddles", 134, 132, 160);

    companion object {
        val RECOMMENDED: List<WledEffectPreset>
            get() = entries.filter { it != CUSTOM }

        fun tryParse(raw: String?): WledEffectPreset? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            if (normalized.isEmpty()) return CUSTOM
            return entries.firstOrNull {
                it.storageId == normalized ||
                    (it.wledName.isNotEmpty() && it.wledName.equals(normalized, ignoreCase = true))
            }
        }

        fun fromRaw(raw: String?): WledEffectPreset = tryParse(raw) ?: CUSTOM
    }
}

/** Injectable HTTP JSON poster for WLED `/json/state` updates. */
fun interface WledHttpPoster {
    fun postJson(url: String, jsonBody: String)
}

/** Injectable HTTP GET reader for WLED `/json/effects` dynamic index resolution. */
fun interface WledHttpGetter {
    fun getBody(url: String): String
}

private fun defaultWledOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(1500, TimeUnit.MILLISECONDS)
    .readTimeout(2000, TimeUnit.MILLISECONDS)
    .writeTimeout(2000, TimeUnit.MILLISECONDS)
    .callTimeout(2500, TimeUnit.MILLISECONDS)
    .build()

class OkHttpWledPoster(
    private val client: OkHttpClient = defaultWledOkHttpClient()
) : WledHttpPoster {
    override fun postJson(url: String, jsonBody: String) {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WLED HTTP ${response.code}")
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class OkHttpWledGetter(
    private val client: OkHttpClient = defaultWledOkHttpClient()
) : WledHttpGetter {
    override fun getBody(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("WLED HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }
}

/**
 * Coordinates opt-in WLED segment 0 primary color synchronization from track album artwork,
 * and explicit user-triggered application of curated WLED effects.
 * Passive artwork/audio sync never alters power, brightness, effect, or palette fields.
 */
class WledColorSync(
    initialEnabled: Boolean = false,
    initialHost: String = Prefs.DEFAULT_WLED_COLOR_HOST,
    private val poster: WledHttpPoster = OkHttpWledPoster(),
    private val getter: WledHttpGetter = OkHttpWledGetter(),
    private val bitmapDecoder: (ByteArray) -> Bitmap? = { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    },
    private val customExecutor: Executor? = null,
    private val debounceMs: Long = 120L,
    private val nowNs: () -> Long = System::nanoTime
) : AutoCloseable {

    companion object {
        private const val TAG = "WledColorSync"
        private const val ERROR_LOG_THROTTLE_NS = 5_000_000_000L

        fun buildEndpointUrl(rawHost: String): String? {
            val normalized = Prefs.normalizeWledHost(rawHost) ?: return null
            return "http://$normalized/json/state"
        }

        fun buildEffectsUrl(rawHost: String): String? {
            val normalized = Prefs.normalizeWledHost(rawHost) ?: return null
            return "http://$normalized/json/effects"
        }

        fun buildStateJson(rgb: Int): String {
            val r = Color.red(rgb)
            val g = Color.green(rgb)
            val b = Color.blue(rgb)
            return """{"seg":[{"id":0,"col":[[$r,$g,$b]]}]}"""
        }

        fun buildEffectStateJson(fxId: Int, speed: Int, intensity: Int): String {
            val clampedSx = speed.coerceIn(0, 255)
            val clampedIx = intensity.coerceIn(0, 255)
            return """{"seg":[{"id":0,"fx":$fxId,"sx":$clampedSx,"ix":$clampedIx}]}"""
        }

        fun resolveEffectId(preset: WledEffectPreset, effectsJson: String?): Int {
            if (preset.wledName.isNotEmpty() && !effectsJson.isNullOrBlank()) {
                val index = runCatching {
                    val array = JSONArray(effectsJson)
                    var matched = -1
                    for (i in 0 until array.length()) {
                        val candidate = array.optString(i, "").trim()
                        if (candidate.equals(preset.wledName, ignoreCase = true)) {
                            matched = i
                            break
                        }
                    }
                    matched
                }.getOrDefault(-1)
                if (index >= 0) return index
            }
            return preset.fallbackFxId
        }

        private fun signatureOf(bytes: ByteArray?): Long {
            if (bytes == null || bytes.isEmpty()) return 0L
            var hash = bytes.size.toLong() * 0x517CC1B727220A95L
            val step = maxOf(1, bytes.size / 64)
            var i = 0
            while (i < bytes.size) {
                hash = hash * 31L + (bytes[i].toInt() and 0xFF)
                i += step
            }
            return if (hash == 0L) 1L else hash
        }
    }

    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private var ownedExecutor: ExecutorService? = null

    @Volatile
    private var enabled: Boolean = initialEnabled

    @Volatile
    private var host: String = Prefs.normalizeWledHost(initialHost) ?: Prefs.DEFAULT_WLED_COLOR_HOST

    private var cachedArtwork: ByteArray? = null
    private var cachedSignature: Long = 0L
    private var lastSentUrl: String? = null
    private var lastSentRgb: Int? = null
    private var lastErrorLogNs = 0L

    fun isEnabled(): Boolean = enabled && !closed.get()

    fun getHost(): String = host

    /**
     * Explicitly applies a curated WLED effect preset to segment 0 by resolving the live effect name
     * against `/json/effects` (falling back to the verified v16 index). Never invoked passively.
     */
    fun applyEffectPreset(preset: WledEffectPreset, onResult: ((Boolean, String) -> Unit)? = null) {
        if (closed.get()) {
            onResult?.invoke(false, "WLED 服务已关闭")
            return
        }
        if (preset == WledEffectPreset.CUSTOM || preset.fallbackFxId < 0) {
            onResult?.invoke(true, "已设为保持灯带当前效果")
            return
        }
        val targetHost = host
        val stateUrl = buildEndpointUrl(targetHost)
        val effectsUrl = buildEffectsUrl(targetHost)
        if (stateUrl == null) {
            onResult?.invoke(false, "无效的 WLED 地址：$targetHost")
            return
        }
        executorOrCreate().execute {
            if (closed.get()) return@execute
            try {
                val effectsJson = effectsUrl?.let { runCatching { getter.getBody(it) }.getOrNull() }
                val fxId = resolveEffectId(preset, effectsJson)
                val body = buildEffectStateJson(fxId, preset.defaultSpeed, preset.defaultIntensity)
                poster.postJson(stateUrl, body)
                onResult?.invoke(true, "已应用 WLED 效果：${preset.displayName}")
            } catch (t: Throwable) {
                logRateLimited("WLED applyEffectPreset failed for $stateUrl", t)
                onResult?.invoke(false, "应用 WLED 效果失败：无法连接 $targetHost")
            }
        }
    }

    fun setEnabled(newEnabled: Boolean) {
        val artworkToPush: ByteArray?
        synchronized(stateLock) {
            if (closed.get() || enabled == newEnabled) return
            enabled = newEnabled
            generation.incrementAndGet()
            lastSentUrl = null
            lastSentRgb = null
            artworkToPush = if (newEnabled) cachedArtwork else null
        }
        if (artworkToPush != null) {
            scheduleSync(artworkToPush)
        }
    }

    fun setHost(newHost: String): Boolean {
        val normalized = Prefs.normalizeWledHost(newHost) ?: return false
        val artworkToPush: ByteArray?
        synchronized(stateLock) {
            if (closed.get()) return false
            if (host == normalized) return true
            host = normalized
            generation.incrementAndGet()
            lastSentUrl = null
            lastSentRgb = null
            artworkToPush = if (enabled) cachedArtwork else null
        }
        if (artworkToPush != null) {
            scheduleSync(artworkToPush)
        }
        return true
    }

    fun onArtworkBytes(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            synchronized(stateLock) {
                if (closed.get()) return
                cachedArtwork = null
                cachedSignature = 0L
                lastSentUrl = null
                lastSentRgb = null
                generation.incrementAndGet()
            }
            return
        }
        val shouldPush: Boolean
        synchronized(stateLock) {
            if (closed.get()) return
            val sig = signatureOf(bytes)
            val prev = cachedArtwork
            if (sig == cachedSignature && prev != null && bytes.contentEquals(prev)) {
                return
            }
            cachedArtwork = bytes
            cachedSignature = sig
            shouldPush = enabled
        }
        if (shouldPush) {
            scheduleSync(bytes)
        }
    }

    private fun executorOrCreate(): Executor {
        customExecutor?.let { return it }
        synchronized(stateLock) {
            return ownedExecutor ?: Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "wled-color-sync").apply { isDaemon = true }
            }.also { ownedExecutor = it }
        }
    }

    private fun scheduleSync(bytes: ByteArray) {
        val taskGen = generation.incrementAndGet()
        executorOrCreate().execute {
            if (debounceMs > 0L) {
                LockSupport.parkNanos(debounceMs * 1_000_000L)
            }
            if (closed.get() || !enabled || generation.get() != taskGen) return@execute
            val targetUrl = buildEndpointUrl(host) ?: return@execute
            val bitmap = runCatching { bitmapDecoder(bytes) }.getOrNull() ?: return@execute
            val selectedRgb = WledArtworkColorExtractor.extract(bitmap).selectedRgb ?: return@execute
            if (closed.get() || !enabled || generation.get() != taskGen) return@execute

            synchronized(stateLock) {
                if (closed.get() || !enabled || generation.get() != taskGen) return@execute
                if (lastSentUrl == targetUrl && lastSentRgb == selectedRgb) return@execute
            }

            val body = buildStateJson(selectedRgb)
            try {
                poster.postJson(targetUrl, body)
                synchronized(stateLock) {
                    if (generation.get() == taskGen) {
                        lastSentUrl = targetUrl
                        lastSentRgb = selectedRgb
                    }
                }
            } catch (t: Throwable) {
                logRateLimited("WLED color sync failed for $targetUrl", t)
            }
        }
    }

    override fun close() {
        val execToShutdown: ExecutorService?
        synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) return
            enabled = false
            cachedArtwork = null
            cachedSignature = 0L
            lastSentUrl = null
            lastSentRgb = null
            generation.incrementAndGet()
            execToShutdown = ownedExecutor
            ownedExecutor = null
        }
        execToShutdown?.let { runCatching { it.shutdownNow() } }
    }

    private fun logRateLimited(msg: String, error: Throwable) {
        val now = nowNs()
        if (lastErrorLogNs == 0L || now - lastErrorLogNs >= ERROR_LOG_THROTTLE_NS) {
            lastErrorLogNs = now
            runCatching { Log.w(TAG, "$msg: ${error.message}") }
        }
    }
}
