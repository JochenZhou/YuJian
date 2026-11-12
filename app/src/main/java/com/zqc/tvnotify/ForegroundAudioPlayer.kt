package com.zqc.tvnotify

import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.OutputSyncSnapshot
import com.sendspin.protocol.StreamFormat

/** Host-local visibility gate. Never changes group transport, player mute or protocol clock. */
class ForegroundAudioPlayer(
    private val buffer: AudioBuffer,
    private val delegate: AudioPlayer,
) : AudioPlayer {
    private var foreground = false
    private var format: StreamFormat? = null
    private var startRequested = false
    private var gain = 1f
    private var resumeError: String? = null

    init { buffer.setAcceptingAudio(false) }

    override val isPlaying: Boolean
        @Synchronized get() = foreground && delegate.isPlaying
    override val droppedDecodeFrames: Long
        @Synchronized get() = delegate.droppedDecodeFrames
    override val outputSync: OutputSyncSnapshot
        @Synchronized get() = when {
            !foreground -> delegate.outputSync.copy(state = "background_suspended", positionSource = "unavailable", errorMicros = null, errorReason = null)
            resumeError != null -> OutputSyncSnapshot(state = "error", errorReason = resumeError)
            else -> delegate.outputSync
        }

    @Synchronized fun setForeground(visible: Boolean) {
        if (foreground == visible) return
        foreground = visible
        resumeError = null
        // Closing admission and clearing it is atomic with network offers. Reopening always starts
        // a fresh queue; no PCM received while hidden can be replayed after returning.
        buffer.setAcceptingAudio(visible)
        if (!visible) {
            delegate.stop()
            return
        }
        try {
            format?.let { delegate.configure(it) }
            delegate.setVolume(gain)
            if (startRequested && format != null) delegate.start()
        } catch (error: Exception) {
            delegate.stop()
            resumeError = error.message ?: error.javaClass.simpleName
        }
    }

    @Synchronized override fun configure(format: StreamFormat) {
        this.format = format
        resumeError = null
        if (foreground) delegate.configure(format)
    }

    @Synchronized override fun start() {
        startRequested = true
        if (foreground && format != null) delegate.start()
    }

    @Synchronized override fun prepareTransition() {
        startRequested = false
        if (foreground) delegate.prepareTransition()
    }

    @Synchronized override fun flush() {
        buffer.flush()
        if (foreground) delegate.flush()
    }

    @Synchronized override fun stop() {
        startRequested = false
        format = null
        resumeError = null
        buffer.flush()
        delegate.stop()
    }

    @Synchronized override fun transition(format: StreamFormat) {
        prepareTransition()
        configure(format)
        start()
    }

    @Synchronized override fun setVolume(gain: Float) {
        this.gain = gain.coerceIn(0f, 1f)
        delegate.setVolume(this.gain)
    }
}
