package com.zqc.tvnotify

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Post-resample Sendspin PCM tap invoked right after AudioTrack writes non-silence audio. */
fun interface WledAudioTap {
    fun onPcmWritten(
        data: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        channels: Int,
        presentationUs: Long
    )
}

/** Analyzed audio metrics encoded into WLED AudioReactive V2 UDP packets. */
data class WledAudioMetrics(
    val sampleRaw: Float,
    val sampleSmth: Float,
    val samplePeak: Int,
    val frameCounter: Int,
    val fftResult: ByteArray,
    val zeroCrossingCount: Int,
    val fftMagnitude: Float,
    val majorPeakHz: Float,
    val pressure0: Int = 0,
    val pressure1: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WledAudioMetrics) return false
        return sampleRaw == other.sampleRaw &&
            sampleSmth == other.sampleSmth &&
            samplePeak == other.samplePeak &&
            frameCounter == other.frameCounter &&
            fftResult.contentEquals(other.fftResult) &&
            zeroCrossingCount == other.zeroCrossingCount &&
            fftMagnitude == other.fftMagnitude &&
            majorPeakHz == other.majorPeakHz &&
            pressure0 == other.pressure0 &&
            pressure1 == other.pressure1
    }

    override fun hashCode(): Int {
        var result = sampleRaw.hashCode()
        result = 31 * result + sampleSmth.hashCode()
        result = 31 * result + samplePeak
        result = 31 * result + frameCounter
        result = 31 * result + fftResult.contentHashCode()
        result = 31 * result + zeroCrossingCount
        result = 31 * result + fftMagnitude.hashCode()
        result = 31 * result + majorPeakHz.hashCode()
        result = 31 * result + pressure0
        result = 31 * result + pressure1
        return result
    }
}

/** Encoder for WLED AudioReactive V2 (44-byte little-endian) UDP sync packets. */
object WledAudioSyncPacket {
    const val PACKET_SIZE = 44
    const val MULTICAST_GROUP = "239.0.0.1"
    const val DEFAULT_PORT = Prefs.DEFAULT_WLED_SYNC_PORT
    private val HEADER = byteArrayOf(
        '0'.code.toByte(),
        '0'.code.toByte(),
        '0'.code.toByte(),
        '0'.code.toByte(),
        '2'.code.toByte(),
        0
    )

    fun encodeV2(metrics: WledAudioMetrics, out: ByteArray = ByteArray(PACKET_SIZE)): ByteArray {
        require(out.size >= PACKET_SIZE) { "Buffer too small for WLED V2 packet: ${out.size}" }
        val buf = ByteBuffer.wrap(out, 0, PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(HEADER)
        buf.put(metrics.pressure0.coerceIn(0, 255).toByte())
        buf.put(metrics.pressure1.coerceIn(0, 255).toByte())
        buf.putFloat(sanitizeFinite(metrics.sampleRaw).coerceIn(0f, 255f))
        buf.putFloat(sanitizeFinite(metrics.sampleSmth).coerceIn(0f, 255f))
        buf.put(metrics.samplePeak.coerceIn(0, 255).toByte())
        buf.put((metrics.frameCounter and 0xFF).toByte())
        for (i in 0 until 16) {
            val value: Byte = if (i < metrics.fftResult.size) metrics.fftResult[i] else 0.toByte()
            buf.put(value)
        }
        buf.putShort(metrics.zeroCrossingCount.coerceIn(0, 65535).toShort())
        buf.putFloat(sanitizeFinite(metrics.fftMagnitude).coerceAtLeast(0f))
        buf.putFloat(sanitizeFinite(metrics.majorPeakHz).coerceAtLeast(0f))
        return out
    }

    private fun sanitizeFinite(value: Float): Float = if (value.isFinite()) value else 0f
}

/**
 * Pure-Kotlin 16-band logarithmic FFT and envelope analyzer for 16-bit LE PCM audio.
 * Safe for non-UI audio worker thread use with reusable scratch arrays.
 */
class WledAudioAnalyzer {
    companion object {
        const val FFT_SIZE = 1024
        const val NUM_BANDS = 16
        private const val NOISE_FLOOR_RMS = 1e-4
        private const val INITIAL_AGC_PEAK = 0.04
        private const val MIN_AGC_PEAK = 0.02
        private val BAND_EDGES_HZ = doubleArrayOf(
            40.0, 86.0, 129.0, 216.0, 301.0, 430.0, 560.0, 818.0,
            1120.0, 1421.0, 1895.0, 2412.0, 3015.0, 3704.0, 4479.0, 7106.0, 11025.0
        )
    }

    private val hannWindow = DoubleArray(FFT_SIZE) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))
    }
    private val ring = DoubleArray(FFT_SIZE)
    private var ringCursor = 0
    private var ringFilled = 0
    private var fftReal = DoubleArray(FFT_SIZE)
    private var fftImag = DoubleArray(FFT_SIZE)
    private var binMagnitudes = DoubleArray(FFT_SIZE / 2)
    private val smoothedBands = DoubleArray(NUM_BANDS)
    private val fftOut = ByteArray(NUM_BANDS)

    private var smoothedLevel = 0f
    private var agcPeak = INITIAL_AGC_PEAK
    private var beatBaseline = 0.0
    private var lastSampleSign = 0
    private var peakCooldownFrames = 0
    private var frameCounter = 0

    @Volatile
    private var gainPercent: Int = 100

    fun setGainPercent(percent: Int) {
        gainPercent = percent.coerceIn(Prefs.MIN_WLED_SYNC_GAIN, Prefs.MAX_WLED_SYNC_GAIN)
    }

    fun getGainPercent(): Int = gainPercent

    fun reset() {
        ring.fill(0.0)
        ringCursor = 0
        ringFilled = 0
        fftReal.fill(0.0)
        fftImag.fill(0.0)
        binMagnitudes.fill(0.0)
        smoothedBands.fill(0.0)
        fftOut.fill(0)
        smoothedLevel = 0f
        agcPeak = INITIAL_AGC_PEAK
        beatBaseline = 0.0
        lastSampleSign = 0
        peakCooldownFrames = 0
        frameCounter = 0
    }

    fun analyze(
        data: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        channels: Int
    ): WledAudioMetrics {
        if (sampleRate <= 0 || channels <= 0 || length <= 0 || offset < 0 || offset + length > data.size) {
            return silentMetrics()
        }
        val frameBytes = channels * 2
        val frameCount = length / frameBytes
        if (frameCount <= 0) {
            return silentMetrics()
        }

        var sumSquares = 0.0
        var zeroCrossings = 0
        var pos = offset
        for (f in 0 until frameCount) {
            var chSum = 0.0
            for (c in 0 until channels) {
                val lo = data[pos].toInt() and 0xFF
                val hi = data[pos + 1].toInt()
                val pcm16 = (hi shl 8) or lo
                chSum += (pcm16 / 32768.0).coerceIn(-1.0, 1.0)
                pos += 2
            }
            val mono = chSum / channels
            sumSquares += mono * mono
            val sign = when {
                mono > 0.0005 -> 1
                mono < -0.0005 -> -1
                else -> lastSampleSign
            }
            if (lastSampleSign != 0 && sign != lastSampleSign) {
                zeroCrossings++
            }
            lastSampleSign = sign

            ring[ringCursor] = mono
            ringCursor = (ringCursor + 1) % FFT_SIZE
            if (ringFilled < FFT_SIZE) ringFilled++
        }

        val gainScale = gainPercent / 100.0
        val extraContrast = (1.0 - gainScale).coerceAtLeast(0.0)
        val rawExp = 0.85 + extraContrast * 0.75
        val bandExp = 0.80 + extraContrast * 0.70

        val rms = sqrt(sumSquares / frameCount)
        val rawLevel = if (rms < NOISE_FLOOR_RMS) {
            0f
        } else {
            if (rms > agcPeak) {
                agcPeak = max(agcPeak + 0.58 * (rms - agcPeak), rms * 0.90)
            } else {
                agcPeak = max(MIN_AGC_PEAK, agcPeak * 0.984)
            }
            val normRms = (rms / max(agcPeak, MIN_AGC_PEAK)).coerceIn(0.0, 1.35)
            if (normRms < 0.08) {
                0f
            } else {
                val base = ((normRms.coerceAtMost(1.0) - 0.05) / 0.95).coerceIn(0.0, 1.0).pow(rawExp) * 230.0
                val burst = ((normRms - 1.0).coerceAtLeast(0.0) / 0.35) * 25.0
                ((base + burst) * gainScale).toFloat().coerceIn(0f, 255f)
            }
        }

        smoothedLevel = if (rawLevel == 0f && smoothedLevel < 0.5f) {
            0f
        } else {
            // Responsive musical smoothing: crisp real-time beat rise with smooth, non-strobing decay.
            val alpha = if (rawLevel >= smoothedLevel) 0.56f else 0.18f
            val next = smoothedLevel + alpha * (rawLevel - smoothedLevel)
            if (next < 0.25f) 0f else next.coerceIn(0f, 255f)
        }

        val beatEnergy = rms
        if (beatBaseline == 0.0) {
            beatBaseline = beatEnergy
        } else {
            val baseAlpha = if (beatEnergy > beatBaseline) 0.09 else 0.04
            beatBaseline += baseAlpha * (beatEnergy - beatBaseline)
        }
        if (peakCooldownFrames > 0) peakCooldownFrames--
        val isPeak = rms >= NOISE_FLOOR_RMS &&
            rawLevel >= 48f &&
            peakCooldownFrames == 0 &&
            beatEnergy > beatBaseline * 1.34
        if (isPeak) {
            peakCooldownFrames = 5 // ~100 ms at 50 fps: lively beats without wild strobe
        }

        var windowSumSq = 0.0
        for (i in 0 until FFT_SIZE) {
            val sample = if (ringFilled < FFT_SIZE) {
                val validStart = FFT_SIZE - ringFilled
                if (i < validStart) 0.0 else ring[i - validStart]
            } else {
                ring[(ringCursor + i) % FFT_SIZE]
            }
            windowSumSq += sample * sample
            fftReal[i] = sample * hannWindow[i]
            fftImag[i] = 0.0
        }

        val counter = frameCounter
        frameCounter = (frameCounter + 1) and 0xFF

        if (windowSumSq < NOISE_FLOOR_RMS * NOISE_FLOOR_RMS * FFT_SIZE) {
            for (band in 0 until NUM_BANDS) {
                val faded = if (smoothedBands[band] < 1.0) 0.0 else smoothedBands[band] * 0.78
                smoothedBands[band] = faded
                fftOut[band] = faded.roundToInt().coerceIn(0, 254).toByte()
            }
            return WledAudioMetrics(
                sampleRaw = rawLevel,
                sampleSmth = smoothedLevel,
                samplePeak = if (isPeak) 1 else 0,
                frameCounter = counter,
                fftResult = fftOut.copyOf(),
                zeroCrossingCount = zeroCrossings.coerceIn(0, 65535),
                fftMagnitude = 0f,
                majorPeakHz = 0f
            )
        }

        fftInPlace(fftReal, fftImag)

        val half = FFT_SIZE / 2
        val binHz = sampleRate.toDouble() / FFT_SIZE
        val scaleNorm = 2.0 / FFT_SIZE
        var peakBin = 1
        var peakMag = 0.0
        binMagnitudes[0] = 0.0
        for (b in 1 until half) {
            val mag = hypot(fftReal[b], fftImag[b]) * scaleNorm * 2.0
            binMagnitudes[b] = mag
            val freq = b * binHz
            if (freq >= 30.0 && mag > peakMag) {
                peakMag = mag
                peakBin = b
            }
        }

        // Parabolic peak interpolation across adjacent bins.
        var majorPeakHz = if (peakMag > 1e-4) (peakBin * binHz).toFloat() else 0f
        if (peakMag > 1e-4 && peakBin in 1 until (half - 1)) {
            val left = binMagnitudes[peakBin - 1]
            val center = binMagnitudes[peakBin]
            val right = binMagnitudes[peakBin + 1]
            val denom = left - 2.0 * center + right
            if (kotlin.math.abs(denom) > 1e-9) {
                val delta = (0.5 * (left - right) / denom).coerceIn(-0.5, 0.5)
                majorPeakHz = ((peakBin + delta) * binHz).toFloat().coerceAtLeast(0f)
            }
        }

        val agcRef = max(agcPeak, MIN_AGC_PEAK)
        for (band in 0 until NUM_BANDS) {
            val fLow = BAND_EDGES_HZ[band]
            val fHigh = min(BAND_EDGES_HZ[band + 1], sampleRate * 0.49)
            val targetBand = if (fHigh <= fLow) {
                0.0
            } else {
                val startBin = max(1, (fLow / binHz).toInt())
                val endBin = min(half - 1, max(startBin, (fHigh / binHz).roundToInt()))
                var bandEnergy = 0.0
                for (b in startBin..endBin) {
                    bandEnergy += binMagnitudes[b] * binMagnitudes[b]
                }
                // Parseval total band energy avoids crushing wide upper-octave bands, plus +0.09/band treble tilt.
                val bandRms = sqrt(bandEnergy)
                val tilt = 1.0 + band * 0.09
                val bandNorm = (bandRms * tilt) / (agcRef * 0.72)
                if (bandRms < 0.0003 || bandNorm < 0.08) {
                    0.0
                } else {
                    (((bandNorm.coerceIn(0.0, 1.0) - 0.06) / 0.94).coerceIn(0.0, 1.0).pow(bandExp) * 254.0 * gainScale)
                        .coerceIn(0.0, 254.0)
                }
            }
            val prevBand = smoothedBands[band]
            val bandAlpha = if (targetBand >= prevBand) 0.62 else 0.22
            val nextBand = if (targetBand == 0.0 && prevBand < 1.0) {
                0.0
            } else {
                prevBand + bandAlpha * (targetBand - prevBand)
            }
            smoothedBands[band] = nextBand.coerceIn(0.0, 254.0)
            fftOut[band] = smoothedBands[band].roundToInt().coerceIn(0, 254).toByte()
        }

        return WledAudioMetrics(
            sampleRaw = rawLevel,
            sampleSmth = smoothedLevel,
            samplePeak = if (isPeak) 1 else 0,
            frameCounter = counter,
            fftResult = fftOut.copyOf(),
            zeroCrossingCount = zeroCrossings.coerceIn(0, 65535),
            fftMagnitude = ((peakMag / agcRef) * 220.0 * gainScale).toFloat().coerceIn(0f, 512f),
            majorPeakHz = majorPeakHz
        )
    }

    private fun silentMetrics(): WledAudioMetrics {
        smoothedLevel = 0f
        beatBaseline = 0.0
        smoothedBands.fill(0.0)
        fftOut.fill(0)
        val counter = frameCounter
        frameCounter = (frameCounter + 1) and 0xFF
        return WledAudioMetrics(
            sampleRaw = 0f,
            sampleSmth = 0f,
            samplePeak = 0,
            frameCounter = counter,
            fftResult = ByteArray(NUM_BANDS),
            zeroCrossingCount = 0,
            fftMagnitude = 0f,
            majorPeakHz = 0f
        )
    }

    private fun fftInPlace(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            val halfLen = len shr 1
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until halfLen) {
                    val even = i + k
                    val odd = even + halfLen
                    val uR = real[even]
                    val uI = imag[even]
                    val vR = real[odd] * wR - imag[odd] * wI
                    val vI = real[odd] * wI + imag[odd] * wR
                    real[even] = uR + vR
                    imag[even] = uI + vI
                    real[odd] = uR - vR
                    imag[odd] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Injectable UDP datagram sender for testing and production reuse. */
interface DatagramSink {
    fun send(packet: ByteArray, length: Int, host: String, port: Int)
    fun close()
}

/** Standard non-throwing UDP datagram sink; caches resolved InetAddress per destination host. */
class AndroidDatagramSink : DatagramSink {
    private var socket: MulticastSocket? = null
    private val addrCache = HashMap<String, InetAddress>(4)

    @Synchronized
    override fun send(packet: ByteArray, length: Int, host: String, port: Int) {
        val target = addrCache[host] ?: InetAddress.getByName(host).also {
            if (addrCache.size >= 8) addrCache.clear()
            addrCache[host] = it
        }
        val activeSocket = socket ?: MulticastSocket().apply {
            timeToLive = 1
            reuseAddress = true
        }.also { socket = it }
        activeSocket.send(DatagramPacket(packet, length, target, port))
    }

    @Synchronized
    override fun close() {
        runCatching { socket?.close() }
        socket = null
        addrCache.clear()
    }
}

/** Injectable lease wrapper for WifiManager.MulticastLock lifecycle management. */
interface MulticastLease {
    fun acquire()
    fun release()
}

class AndroidMulticastLease(context: Context) : MulticastLease {
    private val appContext = context.applicationContext
    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    override fun acquire() {
        val current = lock ?: (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("yujian-wled-audio")
            ?.apply { setReferenceCounted(false) }
            ?.also { lock = it }
        if (current != null && !current.isHeld) {
            runCatching { current.acquire() }
        }
    }

    @Synchronized
    override fun release() {
        val current = lock ?: return
        if (current.isHeld) {
            runCatching { current.release() }
        }
    }
}

/**
 * Coordinates opt-in WLED AudioReactive V2 UDP multicast broadcasting from post-resample
 * Sendspin PCM without ever blocking the audio pump thread.
 */
class WledAudioSync(
    context: Context? = null,
    initialEnabled: Boolean = false,
    initialPort: Int = WledAudioSyncPacket.DEFAULT_PORT,
    initialUnicastHost: String? = null,
    initialGainPercent: Int = 100,
    private val sinkFactory: () -> DatagramSink = { AndroidDatagramSink() },
    private val multicastLease: MulticastLease =
        if (context != null) AndroidMulticastLease(context) else NoOpMulticastLease,
    private val nowNs: () -> Long = System::nanoTime,
    private val minIntervalNs: Long = 20_000_000L,
    private val maxFrameAgeNs: Long = 150_000_000L
) : WledAudioTap, AutoCloseable {

    companion object {
        private const val TAG = "WledAudioSync"
        private const val ERROR_LOG_THROTTLE_NS = 5_000_000_000L

        internal fun extractUdpHost(rawHost: String?): String? {
            val normalized = Prefs.normalizeWledHost(rawHost.orEmpty()) ?: return null
            if (normalized.isEmpty()) return null
            val hostOnly = when {
                normalized.startsWith("[") -> normalized.substringAfter("[").substringBefore("]")
                normalized.indexOf(':') == normalized.lastIndexOf(':') && normalized.contains(':') ->
                    normalized.substringBefore(':')
                else -> normalized
            }.trim()
            return hostOnly.ifEmpty { null }
        }
    }

    private data class PcmFrame(
        val bytes: ByteArray,
        val length: Int,
        val sampleRate: Int,
        val channels: Int,
        val presentationUs: Long,
        val enqueuedNs: Long
    )

    private object NoOpMulticastLease : MulticastLease {
        override fun acquire() = Unit
        override fun release() = Unit
    }

    private val stateLock = Any()
    private val closed = AtomicBoolean(false)
    private val latestFrame = AtomicReference<PcmFrame?>(null)
    private val analyzer = WledAudioAnalyzer().apply {
        setGainPercent(initialGainPercent)
    }
    private val packetBuf = ByteArray(WledAudioSyncPacket.PACKET_SIZE)

    @Volatile
    private var enabled: Boolean = initialEnabled

    @Volatile
    private var playbackActive: Boolean = false

    @Volatile
    private var port: Int = if (initialPort in 1..65535) initialPort else WledAudioSyncPacket.DEFAULT_PORT

    @Volatile
    private var unicastHost: String? = extractUdpHost(initialUnicastHost)

    private var workerThread: Thread? = null
    private var activeSink: DatagramSink? = null
    private var workerRunning = AtomicBoolean(false)
    private var lastSendNs = -1L
    private var lastErrorLogNs = 0L

    fun isActive(): Boolean = enabled && playbackActive && !closed.get()

    fun setEnabled(newEnabled: Boolean) {
        synchronized(stateLock) {
            if (closed.get() || enabled == newEnabled) return
            enabled = newEnabled
            updateWorkerStateLocked()
        }
    }

    fun setPort(newPort: Int): Boolean {
        if (newPort !in 1..65535) return false
        synchronized(stateLock) {
            if (closed.get()) return false
            port = newPort
            return true
        }
    }

    fun setGainPercent(newGainPercent: Int): Boolean {
        if (newGainPercent !in Prefs.MIN_WLED_SYNC_GAIN..Prefs.MAX_WLED_SYNC_GAIN) return false
        synchronized(stateLock) {
            if (closed.get()) return false
            analyzer.setGainPercent(newGainPercent)
            return true
        }
    }

    fun setUnicastHost(rawHost: String?) {
        synchronized(stateLock) {
            if (closed.get()) return
            unicastHost = extractUdpHost(rawHost)
        }
    }

    fun setPlaybackActive(active: Boolean) {
        synchronized(stateLock) {
            if (closed.get() || playbackActive == active) return
            playbackActive = active
            updateWorkerStateLocked()
        }
    }

    fun reset() {
        latestFrame.set(null)
        synchronized(analyzer) {
            analyzer.reset()
        }
        lastSendNs = -1L
    }

    override fun close() {
        synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) return
            enabled = false
            playbackActive = false
            stopWorkerLocked()
        }
    }

    override fun onPcmWritten(
        data: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        channels: Int,
        presentationUs: Long
    ) {
        if (!isActive() || length <= 0 || offset < 0 || offset + length > data.size) return
        val copy = ByteArray(length)
        System.arraycopy(data, offset, copy, 0, length)
        latestFrame.set(
            PcmFrame(
                bytes = copy,
                length = length,
                sampleRate = sampleRate,
                channels = channels,
                presentationUs = presentationUs,
                enqueuedNs = nowNs()
            )
        )
        workerThread?.let(LockSupport::unpark)
    }

    private fun updateWorkerStateLocked() {
        if (enabled && playbackActive && !closed.get()) {
            startWorkerLocked()
        } else {
            stopWorkerLocked()
        }
    }

    private fun startWorkerLocked() {
        if (workerThread != null) return
        latestFrame.set(null)
        synchronized(analyzer) { analyzer.reset() }
        lastSendNs = -1L
        multicastLease.acquire()
        val sink = runCatching { sinkFactory() }.getOrElse {
            multicastLease.release()
            logRateLimited("Failed to create UDP sink", it)
            return
        }
        activeSink = sink
        workerRunning.set(true)
        val thread = Thread({ runWorker(sink) }, "wled-audio-sync").apply {
            isDaemon = true
        }
        workerThread = thread
        thread.start()
    }

    private fun stopWorkerLocked() {
        workerRunning.set(false)
        latestFrame.set(null)
        val thread = workerThread
        workerThread = null
        if (thread != null) {
            LockSupport.unpark(thread)
        }
        val sink = activeSink
        activeSink = null
        runCatching { sink?.close() }
        multicastLease.release()
        synchronized(analyzer) { analyzer.reset() }
        lastSendNs = -1L
    }

    private fun runWorker(sink: DatagramSink) {
        while (workerRunning.get() && isActive()) {
            var frame = latestFrame.getAndSet(null)
            if (frame == null) {
                LockSupport.parkNanos(10_000_000L)
                continue
            }

            var currentNs = nowNs()
            if (maxFrameAgeNs > 0L && currentNs - frame.enqueuedNs > maxFrameAgeNs) {
                continue
            }

            if (lastSendNs >= 0L) {
                val sinceLast = currentNs - lastSendNs
                if (sinceLast < minIntervalNs) {
                    LockSupport.parkNanos(minIntervalNs - sinceLast)
                    if (!workerRunning.get() || !isActive()) break
                    val fresher = latestFrame.getAndSet(null)
                    if (fresher != null) {
                        frame = fresher
                    }
                    currentNs = nowNs()
                    if (maxFrameAgeNs > 0L && currentNs - frame.enqueuedNs > maxFrameAgeNs) {
                        continue
                    }
                }
            }

            val metrics = synchronized(analyzer) {
                analyzer.analyze(frame.bytes, 0, frame.length, frame.sampleRate, frame.channels)
            }
            WledAudioSyncPacket.encodeV2(metrics, packetBuf)
            val sendPort = port
            val directHost = unicastHost
            var sentAny = false
            try {
                sink.send(packetBuf, WledAudioSyncPacket.PACKET_SIZE, WledAudioSyncPacket.MULTICAST_GROUP, sendPort)
                sentAny = true
            } catch (t: Throwable) {
                if (workerRunning.get() && isActive()) {
                    logRateLimited("WLED UDP multicast send failed on port $sendPort", t)
                }
            }
            if (!directHost.isNullOrEmpty() && directHost != WledAudioSyncPacket.MULTICAST_GROUP) {
                try {
                    sink.send(packetBuf, WledAudioSyncPacket.PACKET_SIZE, directHost, sendPort)
                    sentAny = true
                } catch (t: Throwable) {
                    if (workerRunning.get() && isActive()) {
                        logRateLimited("WLED UDP unicast send failed to $directHost:$sendPort", t)
                    }
                }
            }
            if (sentAny) {
                lastSendNs = nowNs()
            }
        }
    }

    private fun logRateLimited(msg: String, error: Throwable) {
        val now = nowNs()
        if (lastErrorLogNs == 0L || now - lastErrorLogNs >= ERROR_LOG_THROTTLE_NS) {
            lastErrorLogNs = now
            runCatching { Log.w(TAG, "$msg: ${error.message}") }
        }
    }
}
