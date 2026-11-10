package com.sendspin.protocol

/** Output-clock state reported by a host's audio synchronization controller. */
data class OutputSyncSnapshot(
    val state: String = "idle",
    val positionSource: String = "unavailable",
    val errorMicros: Long? = null,
    val resyncCount: Long = 0,
    val underrunCount: Long = 0,
    val insertedFrames: Long = 0,
    val droppedFrames: Long = 0,
)

interface AudioPlayer {
    val isPlaying: Boolean
    val droppedDecodeFrames: Long
    /** States: waiting_clock, buffering, playing, estimated, rebuffering, paused, idle, error. */
    val outputSync: OutputSyncSnapshot get() = OutputSyncSnapshot()
    /**
     * Synchronously suspend consumption before a stream's network queue is cleared.
     * The host keeps consumption suspended until configure/transition installs the new format.
     */
    fun prepareTransition() {}
    fun configure(format: StreamFormat)
    fun start()
    fun flush()
    fun stop()
    /** Reconfigure for a new stream and leave the playback pump running (configure + start). */
    fun transition(format: StreamFormat)
    /** Apply a linear gain in [0.0, 1.0] derived from the perceptual volume curve. */
    fun setVolume(gain: Float)
}

/** Discards every call. Used when a host does not advertise [OptionalRole.PLAYER]. */
object NoOpAudioPlayer : AudioPlayer {
    override val isPlaying = false
    override val droppedDecodeFrames = 0L
    override fun configure(format: StreamFormat) {}
    override fun start() {}
    override fun flush() {}
    override fun stop() {}
    override fun transition(format: StreamFormat) {}
    override fun setVolume(gain: Float) {}
}
