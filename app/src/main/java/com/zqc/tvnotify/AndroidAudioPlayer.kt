package com.zqc.tvnotify

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.PowerManager
import android.util.Log
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.OutputSyncSnapshot
import com.sendspin.protocol.StreamFormat
import com.zqc.tvnotify.audio.AudioTrackResetPolicy
import java.util.concurrent.atomic.AtomicBoolean

/** API-23 PCM adapter. Protocol time and the shared network queue remain owned by sendspin-jvm. */
class AndroidAudioPlayer(
    private val context: Context,
    private val buffer: AudioBuffer,
    private val clock: ClockSync,
    private val audioManager: AudioManager,
) : AudioPlayer {
    companion object {
        private const val TAG = "YuJianAudio"
        private const val POLL_MS = 2L
        private const val MAX_REBUILDS = 3
    }

    private val lock = Any()
    private val ioLock = Any()
    private val running = AtomicBoolean(false)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var format: StreamFormat? = null
    @Volatile private var pump: SendspinPcmPump? = null
    @Volatile private var focusGranted = false
    @Volatile private var transitionPending = false
    @Volatile private var volume = 1f
    @Volatile private var sync = OutputSyncSnapshot()
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(ioLock) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> focusGranted = true
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    focusGranted = false
                    // Discard paused track/pending PCM; a later gain re-primes against live time.
                    pump?.pause(ClockSync.localMicros())
                    sync = pump?.snapshot ?: OutputSyncSnapshot(state = "paused")
                }
            }
        }
        Log.i(TAG, "Audio focus change=$change granted=$focusGranted running=${running.get()}")
    }

    override val isPlaying: Boolean get() = running.get() && focusGranted && pump?.isPlaying == true
    override val droppedDecodeFrames: Long get() = sync.droppedFrames
    override val outputSync: OutputSyncSnapshot get() = sync

    override fun configure(format: StreamFormat) {
        require(format.codec.equals("pcm", true)) { "Unsupported codec: ${format.codec}" }
        require(format.bitDepth == 16 && format.channels in 1..2) { "Only mono/stereo PCM16 is supported" }
        require(format.sampleRate in 8_000..192_000) { "Unsupported PCM sample rate" }
        synchronized(lock) {
            synchronized(ioLock) {
                if (this.format == format && track != null && focusGranted) {
                    transitionPending = true
                    pump?.flush(ClockSync.localMicros())
                    sync = pump?.snapshot ?: OutputSyncSnapshot(state = "buffering")
                    return
                }
            }
            stopLocked()
            transitionPending = true
            try {
                synchronized(ioLock) {
                    this.format = format
                    createTrackLocked(format)
                }
                @Suppress("DEPRECATION")
                val focus = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                check(focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Music audio focus denied" }
                focusGranted = true
                acquireWakeLock()
            } catch (error: Exception) {
                stopLocked()
                throw error
            }
        }
    }

    override fun start() {
        synchronized(lock) {
            synchronized(ioLock) {
                if (track == null) return
                transitionPending = false
            }
            if (!running.compareAndSet(false, true)) return
            thread = Thread({ runPump() }, "sendspin-audio").apply { isDaemon = true; start() }
        }
    }

    override fun prepareTransition() {
        synchronized(ioLock) {
            transitionPending = true
            pump?.flush(ClockSync.localMicros())
            sync = OutputSyncSnapshot(state = "buffering")
        }
    }

    override fun transition(format: StreamFormat) {
        configure(format)
        start()
    }

    override fun flush() {
        synchronized(ioLock) {
            buffer.flush()
            pump?.flush(ClockSync.localMicros())
            sync = pump?.snapshot ?: OutputSyncSnapshot()
        }
    }

    override fun stop() = synchronized(lock) { stopLocked() }

    override fun setVolume(gain: Float) {
        volume = gain.coerceIn(0f, 1f)
        synchronized(ioLock) { track?.setVolume(volume) }
    }

    private fun runPump() {
        var rebuilds = 0
        var healthySinceUs = ClockSync.localMicros()
        var lastLogUs = 0L
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    synchronized(ioLock) {
                        if (running.get() && focusGranted && !transitionPending) {
                            val scheduler = pump ?: return
                            val nowUs = ClockSync.localMicros()
                            scheduler.step(nowUs)
                            sync = scheduler.snapshot
                            if (nowUs - lastLogUs >= 5_000_000L) {
                                lastLogUs = nowUs
                                Log.d(TAG, "sync=${sync.state} source=${sync.positionSource} errorUs=${sync.errorMicros} " +
                                    "resync=${sync.resyncCount} underrun=${sync.underrunCount} chunks=${buffer.size}")
                            }
                        }
                    }
                    if (ClockSync.localMicros() - healthySinceUs > 30_000_000L) rebuilds = 0
                } catch (error: Exception) {
                    synchronized(ioLock) {
                        if (!running.get()) return
                        val outputState = try { pump?.diagnosticState(ClockSync.localMicros()) } catch (_: Exception) { "unavailable" }
                        Log.w(TAG, "Audio output failure playState=${track?.playState} $outputState", error)
                        if (++rebuilds > MAX_REBUILDS) throw error
                        val currentFormat = format ?: throw error
                        Log.w(TAG, "Rebuilding audio output after write failure ($rebuilds/$MAX_REBUILDS)", error)
                        releaseTrackLocked()
                        createTrackLocked(currentFormat)
                        healthySinceUs = ClockSync.localMicros()
                    }
                }
                Thread.sleep(POLL_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            Log.e(TAG, "Audio playback failed", error)
            running.set(false)
            synchronized(ioLock) {
                sync = sync.copy(state = "error", errorMicros = null, positionSource = "unavailable", errorReason = error.message ?: error.javaClass.simpleName)
                releaseTrackLocked()
            }
            releaseWakeLock()
        }
    }

    private fun createTrackLocked(format: StreamFormat) {
        val channelMask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        check(minBytes > 0) { "AudioTrack rejected format: $minBytes" }
        val bufferBytes = minBytes * 2
        // Xiaomi Android 6 does not consume the Builder(USAGE_MEDIA) track. Keep the proven legacy route.
        @Suppress("DEPRECATION")
        val output = AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            error("AudioTrack initialization failed")
        }
        track = output
        output.setVolume(volume)
        pump = SendspinPcmPump(buffer, clock, format.sampleRate, format.channels,
            TrackOutput(output, format.sampleRate, format.channels * 2, bufferBytes, minBytes))
        sync = OutputSyncSnapshot(state = if (clock.isReady) "buffering" else "waiting_clock")
        Log.i(TAG, "AudioTrack configured rate=${format.sampleRate} channels=${format.channels} bufferBytes=$bufferBytes")
    }

    private class TrackOutput(
        private val track: AudioTrack,
        sampleRate: Int,
        frameBytes: Int,
        bufferBytes: Int,
        minimumBytes: Int,
    ) : SendspinPcmPump.Output {
        private val requestedFrames = bufferBytes / frameBytes
        // getBufferSizeInFrames is public since API 23. Requested bytes are not proof of the
        // writable native size on vendor AudioTracks; never require more preroll than it reports.
        private val capacityFrames = try { track.bufferSizeInFrames.takeIf { it > 0 } ?: requestedFrames }
            catch (_: Exception) { requestedFrames }
        private val minimumFrames = minOf(minimumBytes / frameBytes, capacityFrames)
        init { Log.i(TAG, "AudioTrack native buffer requestedFrames=$requestedFrames reportedFrames=$capacityFrames minimumFrames=$minimumFrames") }
        override fun minimumBufferFrames() = minimumFrames
        override fun bufferCapacityFrames() = capacityFrames
        private val audioTimestamp = AudioTimestamp()
        private val resetPolicy = AudioTrackResetPolicy()
        private val bufferDurationUs = capacityFrames.toLong() * 1_000_000L / sampleRate
        private var latencyMethod = try { AudioTrack::class.java.getMethod("getLatency") } catch (_: Exception) { null }
        private var measuredLatencyUs = 0L
        private var lastLatencyUs = Long.MIN_VALUE

        override fun playbackHead(): Long = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        override fun timestamp(): SendspinPcmPump.Timestamp? = try {
            if (track.getTimestamp(audioTimestamp)) {
                SendspinPcmPump.Timestamp(audioTimestamp.framePosition, audioTimestamp.nanoTime / 1000L)
            } else null
        } catch (_: Exception) { null }

        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            val count = track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            resetPolicy.onWrite(count)
            return count
        }
        override fun play() = track.play()
        override fun pauseAndFlush() {
            if (!resetPolicy.shouldPauseAndFlush(track.playState == AudioTrack.PLAYSTATE_PLAYING)) return
            // A clock-ready flush can reach a never-started, empty track. Pausing that track
            // makes some Android 6 vendor implementations reject every subsequent write.
            // For real queued output discard first, then return to STOPPED for fresh preroll.
            track.pause()
            track.flush()
            track.stop()
            track.flush()
            resetPolicy.onFlushed()
        }
        override fun latencyUs(): Long {
            val nowUs = ClockSync.localMicros()
            if (lastLatencyUs != Long.MIN_VALUE && nowUs - lastLatencyUs < 500_000L) return measuredLatencyUs
            lastLatencyUs = nowUs
            val method = latencyMethod ?: return 0L
            try {
                val totalUs = (method.invoke(track) as Int).toLong() * 1000L
                // As in AudioTrack position trackers, remove the allocated track buffer duration:
                // pending PCM is already accounted for separately by the scheduler.
                val downstreamUs = (totalUs - bufferDurationUs).coerceAtLeast(0L)
                if (downstreamUs <= 2_000_000L) measuredLatencyUs = downstreamUs
            } catch (_: Exception) {
                latencyMethod = null
                measuredLatencyUs = 0L
            }
            return measuredLatencyUs
        }
    }

    private fun stopLocked() {
        running.set(false)
        val oldThread = thread
        thread = null
        oldThread?.interrupt()
        if (oldThread != null && oldThread !== Thread.currentThread()) {
            try { oldThread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        synchronized(ioLock) {
            releaseTrackLocked()
            format = null
            focusGranted = false
            sync = OutputSyncSnapshot()
        }
        releaseWakeLock()
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(focusListener)
    }

    private fun releaseTrackLocked() {
        val output = track
        track = null
        pump = null
        if (output != null) {
            try { output.pause() } catch (_: Exception) { }
            try { output.flush() } catch (_: Exception) { }
            try { output.release() } catch (_: Exception) { }
        }
    }

    private fun acquireWakeLock() {
        try {
            val manager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YuJian:Sendspin")
                .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
        } catch (error: Exception) { Log.w(TAG, "Unable to hold audio wake lock", error) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } }
        catch (error: Exception) { Log.w(TAG, "Unable to release audio wake lock", error) }
        finally { wakeLock = null }
    }
}
