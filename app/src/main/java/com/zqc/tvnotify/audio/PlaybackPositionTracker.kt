package com.zqc.tvnotify.audio

/**
 * Tracks frames presented by an AudioTrack without treating its queued frames as already played.
 * Times are monotonic microseconds in the same clock domain as AudioTimestamp.nanoTime.
 */
class PlaybackPositionTracker(private val sampleRate: Int) {
    init {
        require(sampleRate > 0)
    }

    data class Position(val frames: Long, val source: String, val reliable: Boolean)

    private var playing = false
    private var started = false
    private var startUs = 0L
    private var updateUs = 0L
    private var activeUs = 0L
    private var submitted = 0L
    private var estimate = 0.0
    private var position = Position(0, "unavailable", false)
    private var headRaw = 0L
    private var headFrames = 0L
    private var headActiveUs = 0L
    private var headAdvanced = false
    private var headAdvanceUs = 0L
    private var stampFrame = 0L
    private var stampUs = 0L
    private var stampSeen = false
    private var stampAdvanced = false
    private var stampAdvanceUs = 0L

    fun reset(nowUs: Long) {
        playing = false
        started = false
        startUs = nowUs
        updateUs = nowUs
        activeUs = 0
        submitted = 0
        estimate = 0.0
        position = Position(0, "unavailable", false)
        headRaw = 0
        headFrames = 0
        headActiveUs = 0
        headAdvanced = false
        headAdvanceUs = 0
        stampFrame = 0
        stampUs = nowUs
        stampSeen = false
        stampAdvanced = false
        stampAdvanceUs = 0
    }

    fun start(nowUs: Long) {
        if (playing) return
        if (!started) {
            reset(nowUs)
            started = true
            position = Position(0, "estimate", false)
        }
        updateUs = nowUs
        playing = true
    }

    fun pause(nowUs: Long, writtenFrames: Long) {
        require(writtenFrames >= 0)
        advanceEstimate(nowUs, writtenFrames)
        if (playing) position = Position(estimate.toLong(), "estimate", false)
        playing = false
    }

    fun sample(
        nowUs: Long,
        writtenFrames: Long,
        rawHead: Long,
        timestampFrame: Long? = null,
        timestampUs: Long? = null
    ): Position {
        require(writtenFrames >= 0)
        val previousSubmitted = submitted
        advanceEstimate(nowUs, writtenFrames)
        if (!playing) {
            position = position.copy(frames = position.frames.coerceIn(0, writtenFrames))
            return position
        }
        val head = readHead(rawHead, writtenFrames)
        val timestamp = if (timestampFrame != null && timestampUs != null) {
            readTimestamp(nowUs, writtenFrames, previousSubmitted, timestampFrame, timestampUs)
        } else null
        position = when {
            timestamp != null -> Position(timestamp, "timestamp", true)
            head != null -> Position(head, "playback_head", true)
            else -> Position(estimate.toLong().coerceIn(0, writtenFrames), "estimate", false)
        }
        // Confirmed hardware is authoritative, even when the fallback estimate got ahead.
        if (position.reliable) estimate = position.frames.toDouble()
        return position
    }

    private fun advanceEstimate(nowUs: Long, writtenFrames: Long) {
        val elapsed = if (nowUs > updateUs) nowUs - updateUs else 0
        if (playing) {
            activeUs += elapsed
            // Only spend time on frames known submitted at the previous observation. In particular,
            // a new write after starvation cannot be consumed by the elapsed empty interval.
            val previousLimit = minOf(submitted, writtenFrames).toDouble()
            estimate = minOf(previousLimit, estimate + elapsed.toDouble() * sampleRate / 1_000_000.0)
        }
        estimate = estimate.coerceIn(0.0, writtenFrames.toDouble())
        submitted = writtenFrames
        updateUs = maxOf(updateUs, nowUs)
    }

    private fun readHead(raw: Long, written: Long): Long? {
        if (raw !in 0..0xffff_ffffL) return null
        if (raw == headRaw) {
            return if (headAdvanced && activeUs - headAdvanceUs <= FRESH_US && headFrames <= written) {
                headFrames
            } else null
        }
        val delta = if (raw >= headRaw) {
            raw - headRaw
        } else if (headRaw >= 0xf000_0000L && raw <= 0x0fff_ffffL) {
            0x1_0000_0000L - headRaw + raw
        } else return null
        if (headFrames > Long.MAX_VALUE - delta) return null
        val frames = headFrames + delta
        if (frames > written || !plausible(delta, activeUs - headActiveUs)) return null
        headRaw = raw
        headFrames = frames
        headActiveUs = activeUs
        headAdvanced = true
        headAdvanceUs = activeUs
        return frames
    }

    private fun readTimestamp(now: Long, written: Long, previousWritten: Long, frame: Long, time: Long): Long? {
        if (frame < 0 || frame > written) return null
        val age = now.toDouble() - time.toDouble()
        // Some devices timestamp a committed future presentation point. Keep its signed offset.
        if (age > FRESH_US || age < -FUTURE_US) return null
        val baseFrame = if (stampSeen) stampFrame else 0
        val baseUs = if (stampSeen) stampUs else startUs
        if (frame < baseFrame || time < baseUs) return null
        val delta = frame - baseFrame
        val elapsed = time - baseUs
        if (delta > 0 && (elapsed <= 0 || !plausible(delta, elapsed))) return null
        if (delta > 0) {
            stampAdvanced = true
            stampAdvanceUs = activeUs
        }
        stampFrame = frame
        stampUs = time
        stampSeen = true
        if (!stampAdvanced || activeUs - stampAdvanceUs > FRESH_US) return null
        val extrapolated = frame.toDouble() + age * sampleRate / 1_000_000.0
        // A cached timestamp may not extrapolate through newly observed submissions after a gap.
        val limit = minOf(written, maxOf(frame, previousWritten))
        return extrapolated.coerceIn(0.0, limit.toDouble()).toLong()
    }

    private fun plausible(deltaFrames: Long, elapsedUs: Long): Boolean {
        if (elapsedUs < 0) return false
        // Ten milliseconds of observation jitter plus 10% rate tolerance; a small regression
        // cannot become a 32-bit wrap and a sudden multi-second jump is rejected.
        val maximum = elapsedUs.toDouble() * sampleRate * 1.1 / 1_000_000.0 + sampleRate / 100.0 + 1
        return deltaFrames <= maximum
    }

    private companion object {
        const val FRESH_US = 250_000L
        const val FUTURE_US = 100_000L
    }
}
