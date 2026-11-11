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
    val errorReason: String? = null,
)

interface AudioPlayer {
    val isPlaying: Boolean
    val droppedDecodeFrames: Long
    /** States: waiting_clock, buffering, playing, estimated, rebuffering, paused, idle, error. */
    val outputSync: OutputSyncSnapshot get() = OutputSyncSnapshot()
    /**
     * Synchronously suspend old output and all queue consumption before a stream boundary.
     * Keep the gate held through [configure] and [flush]; only [start] may resume consumption.
     * Must also work while a slow [configure] is in progress. The default is for hosts without
     * an asynchronous consumer; playback hosts must implement this hook.
     *
     * The client calls this and [start] under its stream lock. Host locks must never call back
     * into the client's stream handlers, and configure must not wait for a later start call.
     */
    fun prepareTransition() {}
    /**
     * Install [format] without consuming or clearing the network queue, starting output, or
     * releasing the transition gate. May run slowly; a newer stream can supersede it in flight.
     * Retaining a compatible output track is allowed. The client checks generation afterward.
     */
    fun configure(format: StreamFormat)
    /** Synchronously release the gate and start/resume, including an already-running pump. */
    fun start()
    /** Clear queued audio/output synchronously without releasing an existing transition gate. */
    fun flush()
    fun stop()
    /**
     * Compatibility helper (configure + start). The protocol uses separate calls so an obsolete
     * configure cannot start output; it never invokes this automatically-starting helper.
     */
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
