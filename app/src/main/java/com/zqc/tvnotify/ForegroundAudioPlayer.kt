package com.zqc.tvnotify

import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.OutputSyncSnapshot
import com.sendspin.protocol.StreamFormat
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The fast gate must not wait for native allocation, release, or playback I/O. */
interface LocalAudioPlayer : AudioPlayer {
    fun setOutputEnabled(enabled: Boolean)
}

/** Serial native work with a short logical-state lock; no group/controller dependencies. */
class ForegroundAudioPlayer(
    private val buffer: AudioBuffer,
    private val delegate: LocalAudioPlayer,
    private val worker: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "sendspin-output-lifecycle").apply { isDaemon = true }
    },
) : AudioPlayer, java.io.Closeable {
    private val lock = Any()
    private var foreground = false
    private var format: StreamFormat? = null
    private var startRequested = false
    private var gain = 1f
    private var epoch = 0L
    private var revision = 0L
    private var closed = false
    private var resumeError: String? = null
    // Native installation belongs exclusively to worker, not the protocol's logical format cache.
    private var installedRevision = -1L
    private var installedFormat: StreamFormat? = null

    init { buffer.setAcceptingAudio(false); delegate.setOutputEnabled(false) }

    override val isPlaying: Boolean
        get() = synchronized(lock) { foreground && startRequested && !closed && delegate.isPlaying }
    override val droppedDecodeFrames: Long get() = delegate.droppedDecodeFrames
    override val outputSync: OutputSyncSnapshot
        get() = synchronized(lock) {
            when {
                !foreground -> OutputSyncSnapshot(state = "background_suspended")
                resumeError != null -> OutputSyncSnapshot(state = "error", errorReason = resumeError)
                else -> delegate.outputSync
            }
        }

    fun setForeground(visible: Boolean) {
        synchronized(lock) {
            if (closed || foreground == visible) return
            foreground = visible
            revision++
            epoch++
            resumeError = null
            delegate.setOutputEnabled(false)
            buffer.setAcceptingAudio(visible)
        }
        schedule()
    }

    override fun configure(format: StreamFormat) {
        synchronized(lock) {
            if (closed) return
            this.format = format
            revision++
            epoch++
            resumeError = null
            delegate.setOutputEnabled(false)
        }
        schedule()
    }

    override fun start() {
        synchronized(lock) {
            if (closed) return
            startRequested = true
            epoch++
        }
        schedule()
    }

    override fun prepareTransition() {
        synchronized(lock) {
            if (closed) return
            startRequested = false
            revision++
            epoch++
            delegate.setOutputEnabled(false)
        }
        schedule()
    }

    override fun flush() {
        synchronized(lock) {
            if (closed) return
            revision++
            epoch++
            delegate.setOutputEnabled(false)
            // Must run at the protocol boundary, never later on worker (which would drop new PCM).
            buffer.flush()
        }
        schedule()
    }

    override fun stop() {
        synchronized(lock) {
            startRequested = false
            format = null
            revision++
            epoch++
            resumeError = null
            delegate.setOutputEnabled(false)
            buffer.flush()
        }
        schedule()
    }

    override fun transition(format: StreamFormat) { prepareTransition(); configure(format); start() }

    override fun setVolume(gain: Float) {
        synchronized(lock) { this.gain = gain.coerceIn(0f, 1f); epoch++ }
        schedule()
    }

    private data class Desired(val epoch: Long, val revision: Long, val format: StreamFormat?, val start: Boolean, val gain: Float)

    private fun schedule() {
        val desired = synchronized(lock) {
            if (closed) return
            Desired(++epoch, revision, format.takeIf { foreground }, startRequested && foreground, gain)
        }
        try { worker.execute { apply(desired) } }
        catch (error: java.util.concurrent.RejectedExecutionException) {
            if (synchronized(lock) { !closed }) throw error
        }
    }

    private fun current(desired: Desired) = synchronized(lock) { !closed && desired.epoch == epoch }

    private fun apply(desired: Desired) {
        if (!current(desired)) return
        try {
            val target = desired.format
            if (target == null) {
                delegate.stop()
                installedFormat = null
                installedRevision = -1L
                return
            }
            if (installedFormat != target || installedRevision != desired.revision) {
                delegate.stop()
                installedFormat = null
                installedRevision = -1L
                if (!current(desired)) return
                delegate.configure(target)
                installedFormat = target
                installedRevision = desired.revision
            }
            if (!current(desired)) return
            delegate.setVolume(desired.gain)
            synchronized(lock) {
                if (closed || desired.epoch != epoch) return
                resumeError = null
                delegate.setOutputEnabled(desired.start)
            }
            // A hide/transition racing this call closes the fast gate first; start cannot reopen it.
            if (desired.start) delegate.start()
        } catch (error: Exception) {
            delegate.setOutputEnabled(false)
            delegate.stop()
            installedFormat = null
            installedRevision = -1L
            synchronized(lock) {
                if (desired.epoch == epoch) resumeError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            epoch++
            delegate.setOutputEnabled(false)
            buffer.setAcceptingAudio(false)
        }
        worker.execute { delegate.stop() }
        (worker as? ExecutorService)?.shutdown()
    }
}
