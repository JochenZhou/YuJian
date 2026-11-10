package com.sendspin.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.sendspin.protocol.ProtocolLog as Timber
import java.util.PriorityQueue

/**
 * Thread-safe priority queue for [AudioChunk]s ordered by their scheduled local playback time.
 *
 * Chunks whose scheduled time is more than [DROP_THRESHOLD_MICROS] in the past are silently
 * dropped (late arrival). Callers should observe [underrunState] to react when the queue
 * empties unexpectedly during active playback.
 */
class AudioBuffer(
    private val clockSync: ClockSync,
    private val capacity: Int = 2048,
) {
    private val lock = Any()

    // Ordered by server timestamp (ascending) — local playback time is computed live at poll
    // time from the current ClockSync estimate, never baked in (see scheduledMicros).
    private val queue = PriorityQueue<QueuedChunk>(
        capacity,
        compareBy { it.chunk.serverTimestampMicros },
    )

    // ── Output delay ──────────────────────────────────────────────────────────

    /** Microseconds to subtract from every chunk's scheduled play time (receiver compensation). */
    @Volatile var outputDelayMicros: Long = 0L
        private set

    fun setOutputDelayMicros(delayMicros: Long) {
        outputDelayMicros = delayMicros
    }

    /** The host watches this together with [outputDelayMicros] to re-prime its output clock. */
    @Volatile var automaticSync: Boolean = true
        private set

    fun setAutomaticSync(automatic: Boolean) {
        automaticSync = automatic
    }

    // ── Stats (for diagnostics) ───────────────────────────────────────────────

    @Volatile var droppedChunks: Long = 0L; private set
    @Volatile var lateChunks: Long = 0L; private set

    private val _underrunState = MutableStateFlow(false)
    val underrunState: StateFlow<Boolean> = _underrunState

    // ── Write path ────────────────────────────────────────────────────────────

    /**
     * Add a chunk arriving from the network.
     * Once the clock is ready, converts server time to local time and rejects late chunks.
     * Before readiness, only capacity bounds apply.
     */
    fun offer(chunk: AudioChunk) {
        synchronized(lock) {
            // Before seeding, the server and local monotonic clocks can have unrelated epochs.
            // Keep the queue bounded by capacity without rejecting against an unknown offset.
            if (clockSync.isReady) {
                val now = ClockSync.localMicros()
                // Admission uses raw local time; output compensation belongs to the consumer.
                val rawLocalTime = clockSync.toLocalMicros(chunk.serverTimestampMicros, now)
                if (rawLocalTime < now - DROP_THRESHOLD_MICROS) {
                    lateChunks++
                    droppedChunks++
                    Timber.w("AudioBuffer: dropped late chunk (%.1f ms late)", (now - rawLocalTime) / 1000.0)
                    return
                }
                if (rawLocalTime > now + MAX_FUTURE_MICROS) {
                    droppedChunks++
                    Timber.w("AudioBuffer: dropped far-future chunk (%.1f s ahead)", (rawLocalTime - now) / 1_000_000.0)
                    return
                }
            }
            if (queue.size >= capacity) {
                // Evict the chunk scheduled furthest in the future — it is the least urgent.
                // Evicting the soonest-to-play chunk (the default PriorityQueue.poll() order)
                // would create an immediate gap; evicting the newest preserves playback continuity.
                // Server timestamp order is equivalent to local-time order for this purpose since
                // toLocalMicros is monotonic in server time for a fixed offset/drift snapshot.
                val toEvict = queue.maxByOrNull { it.chunk.serverTimestampMicros }
                queue.remove(toEvict)
                droppedChunks++
                Timber.w("AudioBuffer: evicted newest chunk (buffer full)")
            }
            queue.offer(QueuedChunk(chunk))
            _underrunState.value = false
        }
    }

    /** Computes the chunk's current scheduled local playback time from the live [clockSync] estimate. */
    private fun scheduledMicros(chunk: AudioChunk, nowMicros: Long): Long =
        clockSync.toLocalMicros(chunk.serverTimestampMicros, nowMicros) - outputDelayMicros

    // ── Read path ─────────────────────────────────────────────────────────────

    /** Inspect the earliest server-timestamped chunk without applying any timing policy. */
    fun peek(): AudioChunk? = synchronized(lock) { queue.peek()?.chunk }

    /** Remove the earliest chunk unconditionally; the host owns playback timing and trimming. */
    fun take(): AudioChunk? = synchronized(lock) { queue.poll()?.chunk }

    /**
     * Returns the next chunk whose scheduled time is at or before [nowMicros],
     * or `null` if none is ready yet. Chunks that fell more than
     * [DROP_THRESHOLD_MICROS] behind the play head are discarded here too, so a
     * paused consumer does not replay minutes of stale audio once it resumes —
     * it jumps straight to the live position instead.
     */
    fun poll(nowMicros: Long = ClockSync.localMicros()): AudioChunk? {
        synchronized(lock) {
            if (!clockSync.isReady) return null
            while (true) {
                val head = queue.peek() ?: return null
                val scheduled = scheduledMicros(head.chunk, nowMicros)
                if (scheduled > nowMicros) return null
                if (scheduled < nowMicros - DROP_THRESHOLD_MICROS) {
                    queue.poll()
                    lateChunks++
                    droppedChunks++
                    Timber.w("AudioBuffer: dropped stale chunk (%.1f ms behind play head)", (nowMicros - scheduled) / 1000.0)
                    continue
                }
                return queue.poll()?.chunk
            }
        }
    }

    /**
     * Returns how far in the future (µs) the next chunk is scheduled,
     * or `null` if the queue is empty.
     */
    fun nextChunkDelayMicros(nowMicros: Long = ClockSync.localMicros()): Long? {
        synchronized(lock) {
            if (!clockSync.isReady) return null
            val head = queue.peek() ?: return null
            return (scheduledMicros(head.chunk, nowMicros) - nowMicros).coerceAtLeast(0L)
        }
    }

    /** Discard all buffered chunks (e.g. on [StreamClear] / seek). */
    fun flush() {
        synchronized(lock) { queue.clear() }
        Timber.d("AudioBuffer: flushed")
    }

    /**
     * Drop chunks scheduled before [cutoffLocalMicros] (local-clock µs).
     * Used after a stalled AudioTrack rebuild to discard audio that would
     * otherwise replay out-of-order once the fresh track starts.
     */
    fun dropChunksOlderThan(cutoffLocalMicros: Long) {
        synchronized(lock) {
            if (!clockSync.isReady) return
            val iterator = queue.iterator()
            var removed = 0
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (clockSync.toLocalMicros(queued.chunk.serverTimestampMicros) < cutoffLocalMicros) {
                    iterator.remove()
                    removed++
                }
            }
            if (removed > 0) {
                droppedChunks += removed
                Timber.w("AudioBuffer: dropped %d stale chunks after track rebuild", removed)
            }
        }
    }

    val size: Int get() = synchronized(lock) { queue.size }

    /** Signal a buffer underrun (called by the audio player when it runs out of data). */
    fun signalUnderrun() {
        if (!_underrunState.value) {
            _underrunState.value = true
            Timber.w("AudioBuffer: underrun")
        }
    }

    private data class QueuedChunk(
        val chunk: AudioChunk,
    )

    companion object {
        // Genuine network delay peaks at ~200 ms; 1 s gives a 5× margin.
        // Seeks cause a brief underrun while Music Assistant's pre-buffered burst (timestamps
        // up to ~3 s in the past) is dropped; the audio player recovers when valid chunks
        // arrive. Can drop to ~500 ms once Music Assistant adopts aiosendspin PR #237's
        // keep_stream=True + stream/clear, eliminating the stale burst (see CLAUDE.md).
        const val DROP_THRESHOLD_MICROS = 1_000_000L  // 1 s

        /** Chunks scheduled further ahead than this are dropped — prevents the buffer
         *  from filling with a server pre-send burst that the player can't yet consume. */
        const val MAX_FUTURE_MICROS = 30_000_000L    // 30 s
    }
}
