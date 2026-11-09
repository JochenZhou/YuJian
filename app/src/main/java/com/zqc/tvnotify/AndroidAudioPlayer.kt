package com.zqc.tvnotify

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.StreamFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** API-23 PCM output adapter; protocol decoding and timestamp ordering stay in sendspin-jvm. */
class AndroidAudioPlayer(
    private val context: Context,
    private val buffer: AudioBuffer,
    private val clock: ClockSync,
    private val audioManager: AudioManager,
    private val onOutputDelayMeasured: (Int) -> Unit,
) : AudioPlayer {
    companion object {
        private const val TAG = "YuJianAudio"
        private const val POLL_SLEEP_MS = 2L
        private const val MAX_QUEUE_MS = 80L
        private const val STARTUP_BUFFER_MS = 40L
        private const val MAX_WRITE_BYTES = 8 * 1024
        private const val MAX_OUTPUT_DELAY_MS = 250
        private const val INITIAL_OUTPUT_DELAY_MS = 40
        private const val DELAY_REPORT_INTERVAL_NS = 500_000_000L
        private const val PUMP_DIAGNOSTIC_INTERVAL_NS = 1_000_000_000L
        private const val STALL_REBUILD_NS = 1_500_000_000L
        private const val MAX_STALL_REBUILDS = 5
        private const val STALE_CHUNK_LIMIT_US = 500_000L
    }

    private val lock = Any()
    private val ioLock = Any()
    private val running = AtomicBoolean(false)
    private val flushGeneration = AtomicLong(0L)
    private val framesWritten = AtomicLong(0L)
    private var playbackHeadFrames = 0L
    private var lastPlaybackHeadPosition = 0L
    @Volatile private var track: AudioTrack? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var format: StreamFormat? = null
    @Volatile private var volume = 1f
    @Volatile private var playbackStarted = false
    @Volatile private var focusGranted = false
    @Volatile private var pausedForFocus = false
    private var thread: Thread? = null

    private var lastReportedOutputDelayMs = -1
    private var lastDelaySampleNs = 0L
    private var lastPumpDiagnosticNs = 0L
    private var firstWriteLogged = false
    private val timestamp = AudioTimestamp()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusGranted = true
                Log.i(TAG, "Audio focus GAIN running=${running.get()} paused=$pausedForFocus playState=${track?.playState}")
                if (pausedForFocus) {
                    pausedForFocus = false
                    if (running.get() && playbackStarted) {
                        try { track?.play() } catch (error: Exception) { Log.w(TAG, "Audio resume failed", error) }
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusGranted = false
                pausedForFocus = playbackStarted
                Log.i(TAG, "Audio focus transient loss running=${running.get()} playState=${track?.playState}")
                if (playbackStarted) try { track?.pause() } catch (_: Exception) { }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusGranted = false
                pausedForFocus = false
                running.set(false)
                playbackStarted = false
                Log.w(TAG, "Audio focus permanent loss; pump stopped playState=${track?.playState}")
                try { track?.pause() } catch (_: Exception) { }
                releaseWakeLock()
            }
        }
    }

    override val isPlaying: Boolean
        get() = running.get() && playbackStarted && focusGranted && track?.playState == AudioTrack.PLAYSTATE_PLAYING

    override val droppedDecodeFrames: Long = 0L

    override fun configure(format: StreamFormat) {
        require(format.codec.equals("pcm", ignoreCase = true)) { "Unsupported Sendspin codec: ${format.codec}" }
        require(format.bitDepth == 16) { "Only 16-bit PCM is supported" }
        require(format.channels in 1..2) { "Only mono/stereo PCM is supported" }
        require(format.sampleRate in 8_000..192_000) { "Unsupported PCM sample rate" }

        synchronized(lock) {
            stopLocked()
            val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBytes = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            check(minBytes > 0) { "AudioTrack rejected stream format: $minBytes" }
            val bufferBytes = max(minBytes * 2, format.sampleRate * format.channels * 2 / 2)
            // Android 6 小米电视上 AudioTrack.Builder(USAGE_MEDIA) 路径创建的轨道会出现在
            // AudioFlinger 里但 Server 永远不推进（写入成功、播放头恒 0）；走旧构造器
            // STREAM_MUSIC 路径与本机 TTS/MediaPlayer 一致，实测可以出声。
            val output = @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
            check(output.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack initialization failed" }
            track = output
            this.format = format
            framesWritten.set(0L)
            playbackHeadFrames = 0L
            lastPlaybackHeadPosition = 0L
            playbackStarted = false
            output.setStereoVolume(volume, volume)
            Log.i(TAG, "AudioTrack configured codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} bits=${format.bitDepth} minBytes=$minBytes bufferBytes=$bufferBytes state=${output.state}")
            val focus = audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
            if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.e(TAG, "Audio focus request denied result=$focus")
                releaseTrackLocked()
                throw IllegalStateException("Music audio focus denied")
            }
            focusGranted = true
            Log.i(TAG, "Audio focus granted playState=${output.playState}")
            lastReportedOutputDelayMs = -1
            reportOutputDelay(INITIAL_OUTPUT_DELAY_MS)
            acquireWakeLock()
        }
    }

    override fun start() {
        synchronized(lock) {
            val output = track ?: return
            if (!running.compareAndSet(false, true)) return
            try {
                Log.i(TAG, "Audio pump start requested format=$format state=${output.state} playState=${output.playState} focus=$focusGranted bufferChunks=${buffer.size}")
                thread = Thread({ pump(output) }, "sendspin-audio").apply { isDaemon = true; start() }
            } catch (error: Exception) {
                running.set(false)
                playbackStarted = false
                releaseTrackLocked()
                releaseWakeLock()
                throw error
            }
        }
    }

    override fun flush() {
        val generation = flushGeneration.incrementAndGet()
        synchronized(lock) {
            buffer.flush()
            synchronized(ioLock) {
                playbackStarted = false
                try {
                    track?.pause()
                    track?.flush()
                    framesWritten.set(0L)
                    playbackHeadFrames = 0L
                    lastPlaybackHeadPosition = track?.playbackHeadPosition?.toLong()?.and(0xFFFF_FFFFL) ?: 0L
                    if (running.get() && focusGranted && playbackStarted) track?.play() else Unit
                } catch (error: Exception) {
                    Log.w(TAG, "AudioTrack flush failed", error)
                }
            }
            Log.i(TAG, "AudioTrack flush generation=$generation running=${running.get()} focus=$focusGranted playState=${track?.playState}")
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    override fun transition(format: StreamFormat) = configure(format)

    override fun setVolume(gain: Float) {
        volume = gain.coerceIn(0f, 1f)
        synchronized(lock) { track?.setStereoVolume(volume, volume) }
    }

    private fun pump(output: AudioTrack) {
        var pendingData: ByteArray? = null
        var pendingOffset = 0
        var pendingGeneration = -1L
        var lastWriteResult: Int? = null
        var startupFramesWritten = 0L
        var lastProgressHead = 0L
        var lastProgressNs = System.nanoTime()
        var stallRebuilds = 0
        lastPumpDiagnosticNs = 0L
        firstWriteLogged = false
        Log.i(TAG, "Audio pump entered running=${running.get()} focus=$focusGranted format=$format")
        try {
            while (running.get() && track === output) {
                val generation = flushGeneration.get()
                if (pendingGeneration != generation) {
                    pendingData = null
                    pendingOffset = 0
                    pendingGeneration = generation
                }
                val currentFormat = format ?: break
                val frameBytes = currentFormat.channels * (currentFormat.bitDepth / 8)
                val sampleRate = currentFormat.sampleRate
                val playedFrames = outputPlaybackHead(output)
                val queuedFrames = (framesWritten.get() - playedFrames).coerceAtLeast(0L)
                val localNow = System.nanoTime() / 1_000L
                val projectedOutputTime = if (sampleRate > 0) {
                    localNow + queuedFrames * 1_000_000L / sampleRate
                } else {
                    localNow
                }
                val nextChunkDelayMicros = if (frameBytes > 0) {
                    buffer.nextChunkDelayMicros(projectedOutputTime)
                } else {
                    null
                }
                logPumpState(output, currentFormat, queuedFrames, nextChunkDelayMicros, pendingData, pendingOffset, lastWriteResult, startupFramesWritten)
                if (frameBytes <= 0 || !focusGranted) {
                    Thread.sleep(POLL_SLEEP_MS)
                    continue
                }

                val maxQueuedFrames = sampleRate.toLong() * MAX_QUEUE_MS / 1_000L
                val startupBufferFrames = sampleRate.toLong() * STARTUP_BUFFER_MS / 1_000L
                if (!playbackStarted && queuedFrames >= startupBufferFrames) {
                    synchronized(ioLock) {
                        if (running.get() && track === output && focusGranted && !playbackStarted) {
                            output.play()
                            playbackStarted = true
                            Log.i(TAG, "AudioTrack started with startup buffer queuedFrames=$queuedFrames threshold=$startupBufferFrames format=$currentFormat playState=${output.playState}")
                        }
                    }
                }
                if (playbackStarted && playbackHeadFrames == lastProgressHead) {
                    val stalledNs = System.nanoTime() - lastProgressNs
                    if (stalledNs >= STALL_REBUILD_NS && stallRebuilds < MAX_STALL_REBUILDS) {
                        Log.e(TAG, "AudioTrack stalled at head=$playbackHeadFrames after ${stalledNs / 1_000_000} ms; rebuilding ($stallRebuilds/$MAX_STALL_REBUILDS)")
                        stallRebuilds++
                        if (rebuildTrackAfterStall()) {
                            Log.i(TAG, "AudioTrack rebuilt after stall; continuing pump")
                        }
                        lastProgressNs = System.nanoTime()
                        continue
                    }
                }
                if (playbackHeadFrames != lastProgressHead) {
                    lastProgressHead = playbackHeadFrames
                    lastProgressNs = System.nanoTime()
                    stallRebuilds = 0
                }
                updateOutputDelay(output, sampleRate, queuedFrames)
                if (queuedFrames >= maxQueuedFrames) {
                    Thread.sleep(POLL_SLEEP_MS)
                    continue
                }

                if (pendingData == null) {
                    val delay = buffer.nextChunkDelayMicros(projectedOutputTime)
                    if (delay != null && delay > 0L) {
                        Thread.sleep(minOf(POLL_SLEEP_MS, maxOf(1L, delay / 1_000L)))
                        continue
                    }
                    val chunk = buffer.poll(projectedOutputTime)
                    if (chunk == null) {
                        if (queuedFrames == 0L) buffer.signalUnderrun()
                        Thread.sleep(POLL_SLEEP_MS)
                        continue
                    }
                    if (chunk.data.isEmpty() || chunk.data.size % frameBytes != 0) {
                        Log.w(TAG, "Dropping malformed PCM chunk (${chunk.data.size} bytes)")
                        continue
                    }
                    pendingData = chunk.data
                    pendingOffset = 0
                }

                val data = pendingData ?: continue
                val availableFrames = (maxQueuedFrames - (framesWritten.get() - outputPlaybackHead(output)))
                    .coerceAtLeast(0L)
                if (availableFrames == 0L) {
                    Thread.sleep(POLL_SLEEP_MS)
                    continue
                }
                val availableBytes = minOf(Int.MAX_VALUE.toLong(), availableFrames * frameBytes).toInt()
                val requestBytes = minOf(MAX_WRITE_BYTES, data.size - pendingOffset, availableBytes)
                if (requestBytes <= 0) {
                    Thread.sleep(POLL_SLEEP_MS)
                    continue
                }
                val written = synchronized(ioLock) {
                    if (!running.get() || track !== output || !focusGranted || flushGeneration.get() != generation) {
                        0
                    } else {
                        val count = output.write(data, pendingOffset, requestBytes, AudioTrack.WRITE_NON_BLOCKING)
                        lastWriteResult = count
                        if (count > 0) {
                            check(count % frameBytes == 0) { "AudioTrack returned unaligned PCM write: $count" }
                            val totalFrames = framesWritten.addAndGet((count / frameBytes).toLong())
                            startupFramesWritten += (count / frameBytes).toLong()
                            if (!firstWriteLogged) {
                                firstWriteLogged = true
                                Log.i(TAG, "First PCM write bytes=$count frames=$totalFrames format=$currentFormat playState=${output.playState} focus=$focusGranted")
                            }
                        }
                        count
                    }
                }
                if (written < 0) throw IllegalStateException("AudioTrack write failed: $written")
                if (written == 0) {
                    Thread.sleep(POLL_SLEEP_MS)
                } else {
                    pendingOffset += written
                    if (pendingOffset >= data.size) {
                        pendingData = null
                        pendingOffset = 0
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.e(TAG, "AudioTrack playback failed", error)
            running.set(false)
            playbackStarted = false
            synchronized(lock) {
                if (track === output) releaseTrackLocked()
                releaseWakeLock()
            }
        } finally {
            Log.i(TAG, "Audio pump exited running=${running.get()} trackCurrent=${track === output} focus=$focusGranted framesWritten=${framesWritten.get()} playbackHead=$playbackHeadFrames playState=${output.playState} bufferChunks=${buffer.size}")
        }
    }

    private fun logPumpState(
        output: AudioTrack,
        currentFormat: StreamFormat,
        queuedFrames: Long,
        nextChunkDelayMicros: Long?,
        pendingData: ByteArray?,
        pendingOffset: Int,
        lastWriteResult: Int?,
        startupFramesWritten: Long,
    ) {
        val nowNs = System.nanoTime()
        if (nowNs - lastPumpDiagnosticNs < PUMP_DIAGNOSTIC_INTERVAL_NS) return
        lastPumpDiagnosticNs = nowNs
        val dataRemaining = pendingData?.let { (it.size - pendingOffset).coerceAtLeast(0) } ?: 0
        Log.i(
            TAG,
            "Audio pump state running=${running.get()} focus=$focusGranted pausedForFocus=$pausedForFocus " +
                "format=${currentFormat.codec}/${currentFormat.sampleRate}/${currentFormat.channels}/${currentFormat.bitDepth} " +
                "trackState=${output.state} playState=${output.playState} bufferChunks=${buffer.size} " +
                "nextChunkDelayUs=$nextChunkDelayMicros outputDelayUs=${buffer.outputDelayMicros} " +
                "clockOffsetUs=${clock.offsetMicros} clockDriftPpm=${clock.lastDriftPpm} " +
                "framesWritten=${framesWritten.get()} playbackHead=$playbackHeadFrames queuedFrames=$queuedFrames " +
                "startupFramesWritten=$startupFramesWritten pendingBytes=$dataRemaining lastWriteResult=$lastWriteResult",
        )
    }

    private fun rebuildTrackAfterStall(): Boolean = synchronized(lock) {
        val currentFormat = format ?: return false
        val deadTrack = track ?: return false
        Log.i(TAG, "Rebuilding stalled AudioTrack format=$currentFormat framesWritten=${framesWritten.get()} playbackHead=$playbackHeadFrames")
        synchronized(ioLock) {
            try { deadTrack.pause() } catch (_: Exception) { }
            try { deadTrack.flush() } catch (_: Exception) { }
            try { deadTrack.release() } catch (_: Exception) { }
        }
        track = null
        val channelMask = if (currentFormat.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(currentFormat.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) {
            Log.e(TAG, "AudioTrack rebuild rejected stream format: $minBytes")
            return false
        }
        val bufferBytes = max(minBytes * 2, currentFormat.sampleRate * currentFormat.channels * 2 / 2)
        val fresh = @Suppress("DEPRECATION")
        AudioTrack(AudioManager.STREAM_MUSIC, currentFormat.sampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
        if (fresh.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack rebuild failed state=${fresh.state}")
            try { fresh.release() } catch (_: Exception) { }
            return false
        }
        framesWritten.set(0L)
        playbackHeadFrames = 0L
        lastPlaybackHeadPosition = 0L
        playbackStarted = false
        // 重建后只保留最近 0.5 s 内的块，扔掉卡死期间积压的过期音频
        buffer.dropChunksOlderThan(System.nanoTime() / 1_000L - STALE_CHUNK_LIMIT_US)
        fresh.setStereoVolume(volume, volume)
        track = fresh
        Log.i(TAG, "AudioTrack rebuilt state=${fresh.state} format=$currentFormat bufferBytes=$bufferBytes")
        return true
    }

    private fun updateOutputDelay(output: AudioTrack, sampleRate: Int, queuedFrames: Long) {
        val sampleNowNs = System.nanoTime()
        if (sampleNowNs - lastDelaySampleNs < DELAY_REPORT_INTERVAL_NS) return
        lastDelaySampleNs = sampleNowNs
        val measuredQueuedFrames = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && output.getTimestamp(timestamp)
        ) {
            (framesWritten.get() - timestamp.framePosition).coerceAtLeast(0L)
        } else {
            queuedFrames
        }
        reportOutputDelay((measuredQueuedFrames * 1_000L / sampleRate).toInt())
    }

    private fun reportOutputDelay(delayMs: Int) {
        val bounded = delayMs.coerceIn(0, 250)
        if (bounded == lastReportedOutputDelayMs) return
        lastReportedOutputDelayMs = bounded
        buffer.outputDelayMicros = bounded * 1_000L
        try {
            onOutputDelayMeasured(bounded)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to report output latency", error)
        }
    }

    private fun outputPlaybackHead(output: AudioTrack): Long = synchronized(ioLock) {
        val raw = output.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        if (raw >= lastPlaybackHeadPosition) {
            playbackHeadFrames += raw - lastPlaybackHeadPosition
        } else {
            playbackHeadFrames += 0x1_0000_0000L - lastPlaybackHeadPosition + raw
        }
        lastPlaybackHeadPosition = raw
        playbackHeadFrames
    }

    private fun stopLocked() {
        Log.i(TAG, "AudioTrack stop running=${running.get()} focus=$focusGranted playState=${track?.playState} framesWritten=${framesWritten.get()} playbackHead=$playbackHeadFrames")
        running.set(false)
        val oldThread = thread
        thread = null
        oldThread?.interrupt()
        if (oldThread != null && oldThread !== Thread.currentThread()) {
            try { oldThread.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        playbackStarted = false
        releaseTrackLocked()
        releaseWakeLock()
        focusGranted = false
        pausedForFocus = false
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(focusListener)
    }

    private fun releaseTrackLocked() {
        val output = track
        track = null
        format = null
        if (output != null) {
            synchronized(ioLock) {
                try { output.pause() } catch (_: Exception) { }
                try { output.flush() } catch (_: Exception) { }
                try { output.release() } catch (_: Exception) { }
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YuJian:Sendspin")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to hold playback CPU wake lock", error)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release playback wake lock", error)
        } finally {
            wakeLock = null
        }
    }
}
