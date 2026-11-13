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
) : LocalAudioPlayer {
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
    @Volatile private var outputEnabled = false

    override fun setOutputEnabled(enabled: Boolean) { outputEnabled = enabled }
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
                    if (outputEnabled) pump?.pause(ClockSync.localMicros())
                    sync = pump?.snapshot ?: OutputSyncSnapshot(state = "paused")
                }
            }
        }
        Log.i(TAG, "Audio focus change=$change granted=$focusGranted running=${running.get()}")
    }

    override val isPlaying: Boolean get() = outputEnabled && running.get() && focusGranted && pump?.isPlaying == true
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
                        if (running.get() && outputEnabled && focusGranted && !transitionPending) {
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
                        if (!outputEnabled) return@synchronized
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
        fun newNativeTrack(): AudioTrack {
            // Xiaomi Android 6 does not consume Builder(USAGE_MEDIA) tracks.
            @Suppress("DEPRECATION")
            val output = AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes, AudioTrack.MODE_STREAM)
            if (output.state != AudioTrack.STATE_INITIALIZED) {
                output.release()
                error("AudioTrack initialization failed")
            }
            output.setVolume(volume)
            track = output
            return output
        }
        val output = newNativeTrack()
        pump = SendspinPcmPump(buffer, clock, format.sampleRate, format.channels,
            TrackOutput(output, format.sampleRate, format.channels * 2, bufferBytes, minBytes, ::newNativeTrack))
        sync = OutputSyncSnapshot(state = if (clock.isReady) "buffering" else "waiting_clock")
        Log.i(TAG, "AudioTrack configured rate=${format.sampleRate} channels=${format.channels} bufferBytes=$bufferBytes")
    }

    private inner class TrackOutput(
        private var track: AudioTrack,
        private val sampleRate: Int,
        private val frameBytes: Int,
        bufferBytes: Int,
        private val minimumBytes: Int,
        private val recreateNativeTrack: () -> AudioTrack,
    ) : SendspinPcmPump.Output {
        private val requestedFrames = bufferBytes / frameBytes
        // getBufferSizeInFrames is public since API 23. Requested bytes are not proof of the
        // writable native size on vendor AudioTracks; never require more preroll than it reports.
        private var capacityFrames = requestedFrames
        private var minimumFrames = minimumBytes / frameBytes
        private var bufferDurationUs = requestedFrames.toLong() * 1_000_000L / sampleRate
        init { refreshNativeSizing() }
        private fun refreshNativeSizing() {
            capacityFrames = try { track.bufferSizeInFrames.takeIf { it > 0 } ?: requestedFrames }
                catch (_: Exception) { requestedFrames }
            minimumFrames = minOf(minimumBytes / frameBytes, capacityFrames)
            bufferDurationUs = capacityFrames.toLong() * 1_000_000L / sampleRate
            Log.i(TAG, "AudioTrack native buffer session=${track.audioSessionId} requestedFrames=$requestedFrames reportedFrames=$capacityFrames minimumFrames=$minimumFrames")
        }
        override fun isEnabled() = outputEnabled
        override fun minimumBufferFrames() = minimumFrames
        override fun bufferCapacityFrames() = capacityFrames
        private val audioTimestamp = AudioTimestamp()
        private val resetPolicy = AudioTrackResetPolicy()
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
            if (!outputEnabled) return 0
            val count = track.write(data, offset, length, AudioTrack.WRITE_NON_BLOCKING)
            resetPolicy.onWrite(count)
            return count
        }
        override fun play() { if (outputEnabled) track.play() }
        override fun pauseAndFlush() {
            if (!outputEnabled) return
            if (!resetPolicy.shouldPauseAndFlush(track.playState == AudioTrack.PLAYSTATE_PLAYING)) return
            // Some Android 6 HALs defer flushing the native ring until its server runs. Reusing
            // that stopped ring can report zero writable bytes while our new timeline is empty.
            // Replace only the native track; preserve focus, protocol clock and pending network PCM.
            val old = track
            // Construct first so allocation failure leaves the old object valid for recovery.
            val replacement = recreateNativeTrack()
            try { old.pause() } catch (_: Exception) { }
            old.release()
            track = replacement
            refreshNativeSizing()
            measuredLatencyUs = 0L
            lastLatencyUs = Long.MIN_VALUE
            resetPolicy.onFlushed()
            Log.i(TAG, "AudioTrack native queue replaced for timestamp re-prime")
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
