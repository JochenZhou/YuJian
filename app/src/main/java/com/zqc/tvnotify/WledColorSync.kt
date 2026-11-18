package com.zqc.tvnotify

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * If the primary color is black / near-black (relative luminance < 0.025), falls back
 * to the secondary color; if neither is sufficiently lit, yields null.
 */
object WledArtworkColorExtractor {
    const val DARK_LUMINANCE_THRESHOLD = 0.025
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

    fun isDarkColor(rgb: Int): Boolean = relativeLuminance(rgb) < DARK_LUMINANCE_THRESHOLD

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

        var secondaryRgb: Int? = null
        if (isDarkColor(primaryRgb)) {
            for (i in 1 until merged.size) {
                val candidate = merged[i]
                val candidateRgb = candidate.toRgbInt()
                if (!isDarkColor(candidateRgb) && rgbDistance(primaryCluster, candidate) >= DISTINCT_DISTANCE) {
                    secondaryRgb = candidateRgb
                    break
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
            !isDarkColor(primaryRgb) -> primaryRgb
            secondaryRgb != null && !isDarkColor(secondaryRgb) -> secondaryRgb
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

/** Injectable HTTP JSON poster for WLED `/json/state` updates. */
fun interface WledHttpPoster {
    fun postJson(url: String, jsonBody: String)
}

class OkHttpWledPoster(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .writeTimeout(2000, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS)
        .build()
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

/**
 * Coordinates opt-in WLED segment 0 primary color synchronization from track album artwork.
 * Never sets power, brightness, effect, or palette fields, preserving active WLED AudioReactive effects.
 */
class WledColorSync(
    initialEnabled: Boolean = false,
    initialHost: String = Prefs.DEFAULT_WLED_COLOR_HOST,
    private val poster: WledHttpPoster = OkHttpWledPoster(),
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

        fun buildStateJson(rgb: Int): String {
            val r = Color.red(rgb)
            val g = Color.green(rgb)
            val b = Color.blue(rgb)
            return """{"seg":[{"id":0,"col":[[$r,$g,$b]]}]}"""
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
