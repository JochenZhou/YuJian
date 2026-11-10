package com.zqc.tvnotify

import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioChunk
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.OutputSyncSnapshot
import com.zqc.tvnotify.audio.PcmFrameTimeline
import com.zqc.tvnotify.audio.PcmResampler
import com.zqc.tvnotify.audio.PcmSynchronizer
import com.zqc.tvnotify.audio.PlaybackPositionTracker
import kotlin.math.abs
import kotlin.math.ceil

/** Nonblocking PCM scheduler. All methods are called under the output adapter's I/O lock. */
internal class SendspinPcmPump(
    private val buffer: AudioBuffer,
    private val clock: ClockSync,
    private val sampleRate: Int,
    private val channels: Int,
    private val output: Output,
) {
    data class Timestamp(val frame: Long, val timeUs: Long)
    interface Output {
        fun playbackHead(): Long
        fun timestamp(): Timestamp?
        fun write(data: ByteArray, offset: Int, length: Int): Int
        fun play()
        fun pauseAndFlush()
        /** Estimated latency downstream of playbackHead(), excluding this track's queued PCM. */
        fun latencyUs(): Long = 0L
    }

    private val frameBytes = channels * 2
    private val queueLimit = sampleRate * 80L / 1000
    private val positions = PlaybackPositionTracker(sampleRate)
    private val consumption = PlaybackPositionTracker(sampleRate)
    private var occupiedFrames = 0L
    private val timeline = PcmFrameTimeline(sampleRate)
    private val synchronizer = PcmSynchronizer(sampleRate, channels)
    private val resampler = PcmResampler(channels)
    private var writtenFrames = 0L
    private var started = false
    private var firstServerUs: Long? = null
    private var chunk: AudioChunk? = null
    private var chunkOffsetFrames = 0
    private var block: Block? = null
    private var drySinceUs: Long? = null
    private var writeBlockedSinceUs: Long? = null
    private var positionUsesTimestamp = false
    private var mode = buffer.automaticSync
    private var compensationUs = buffer.outputDelayMicros
    private var resyncs = 0L
    private var underruns = 0L
    private var inserted = 0L
    private var dropped = 0L
    private var lastPosition = PlaybackPositionTracker.Position(0L, "unavailable", false)
    var snapshot = OutputSyncSnapshot(state = "buffering")
        private set
    val isPlaying: Boolean get() = started
    val isWriteStalled: Boolean get() = writeBlockedSinceUs != null

    private data class Block(
        val bytes: ByteArray,
        val serverUs: Long,
        val sourceFrames: Int,
        val silence: Boolean,
        var written: Int = 0,
    )

    fun step(nowUs: Long) {
        if (mode != buffer.automaticSync || compensationUs != buffer.outputDelayMicros) {
            mode = buffer.automaticSync
            compensationUs = buffer.outputDelayMicros
            resetOutput(nowUs, "buffering", countResync = true)
        }
        if (!clock.isReady) {
            if (started || writtenFrames > 0) resetOutput(nowUs, "waiting_clock", countResync = true)
            publish("waiting_clock", null)
            return
        }
        check(writeBlockedSinceUs?.let { nowUs - it < 1_500_000L } != false) { "AudioTrack write stalled" }
        val stamp = output.timestamp()
        val rawHead = output.playbackHead()
        lastPosition = positions.sample(nowUs, writtenFrames, rawHead, stamp?.frame, stamp?.timeUs)
        val consumed = consumption.sample(nowUs, writtenFrames, rawHead)
        // Keep history while a real hardware cursor catches up with the startup estimate.
        timeline.discardBefore((lastPosition.frames - sampleRate * 5L).coerceAtLeast(0L))
        occupiedFrames = (writtenFrames - consumed.frames).coerceAtLeast(0L)
        val queued = (writtenFrames - lastPosition.frames).coerceAtLeast(0L)
        // The DAC cursor includes downstream latency; only consumption controls track occupancy.
        if (consumed.reliable && occupiedFrames > queueLimit + sampleRate / 50L) {
            resetOutput(nowUs, "rebuffering", countResync = true)
            return
        }
        if (lastPosition.source == "timestamp") positionUsesTimestamp = true
        else if (lastPosition.source == "playback_head") positionUsesTimestamp = false
        val latencyUs = if (positionUsesTimestamp) 0L else output.latencyUs().coerceIn(0L, 2_000_000L)

        if (started && queued == 0L) {
            if (drySinceUs == null) {
                drySinceUs = nowUs
                underruns++
                buffer.signalUnderrun()
            }
            // After an underrun, old frame/time anchors no longer describe the hardware timeline.
            resetOutput(nowUs, "rebuffering", countResync = true)
            return
        }
        if (queued > 0L) drySinceUs = null

        if (!started && writtenFrames > 0L) {
            val first = firstServerUs ?: error("Missing first PCM timestamp")
            val startUs = clock.toLocalMicros(first, nowUs) - compensationUs - latencyUs
            if (nowUs - startUs > 20_000L) {
                resetOutput(nowUs, "rebuffering", countResync = true)
                return
            }
            if (nowUs >= startUs) {
                // Buffered PCM is ready; do not wait for more packets after its deadline.
                output.play()
                positions.start(nowUs)
                consumption.start(nowUs)
                lastPosition = positions.sample(nowUs, writtenFrames, output.playbackHead())
                consumption.sample(nowUs, writtenFrames, output.playbackHead())
                started = true
            }
        }
        if (occupiedFrames >= queueLimit) {
            updatePlayingSnapshot(nowUs, latencyUs)
            return
        }
        if (block != null) {
            // A partly accepted write is still subject to the live clock, including long stalls.
            val current = block!!
            val frameOffset = current.written / frameBytes
            val totalOutput = current.bytes.size / frameBytes
            val remainingServerUs = current.serverUs + frameOffset.toLong() * current.sourceFrames * 1_000_000L / totalOutput / sampleRate
            val outputUs = predictedOutputUs(nowUs, latencyUs, queued)
            if (started && abs(outputUs - (clock.toLocalMicros(remainingServerUs, nowUs) - compensationUs)) > 50_000L) {
                dropped += (current.bytes.size - current.written) / frameBytes
                block = null
                resetOutput(nowUs, "rebuffering", countResync = true)
                return
            }
        }
        if (block == null) prepareBlock(nowUs, latencyUs, queued)
        val current = block
        if (current != null) {
            val room = (queueLimit - occupiedFrames).coerceAtLeast(0L)
            val bytes = minOf(current.bytes.size - current.written, (room * frameBytes).toInt(), 8192)
                .let { it - it % frameBytes }
            if (bytes > 0) {
                val count = output.write(current.bytes, current.written, bytes)
                check(count >= 0) { "AudioTrack write failed: $count" }
                check(count <= bytes && count % frameBytes == 0) { "Invalid PCM write result: $count" }
                if (count == 0) {
                    if (writeBlockedSinceUs == null) writeBlockedSinceUs = nowUs
                    check(nowUs - writeBlockedSinceUs!! < 1_500_000L) { "AudioTrack write stalled" }
                } else {
                    writeBlockedSinceUs = null
                    val outputFrames = count / frameBytes
                    val allOutputFrames = current.bytes.size / frameBytes
                    val offsetFrames = current.written / frameBytes
                    val sourceBegin = offsetFrames.toLong() * current.sourceFrames / allOutputFrames
                    val sourceEnd = (offsetFrames + outputFrames).toLong() * current.sourceFrames / allOutputFrames
                    timeline.append(writtenFrames, outputFrames,
                        current.serverUs + sourceBegin * 1_000_000L / sampleRate,
                        (sourceEnd - sourceBegin).toInt())
                    writtenFrames += outputFrames
                    if (current.silence) inserted += outputFrames
                    current.written += count
                    if (current.written == current.bytes.size) block = null
                    // Seed the submitted-frame bound immediately; future packets cannot consume
                    // elapsed time accumulated while nothing was submitted.
                    positions.sample(nowUs, writtenFrames, output.playbackHead())
                    consumption.sample(nowUs, writtenFrames, output.playbackHead())
                }
            }
        }
        updatePlayingSnapshot(nowUs, latencyUs)
    }

    private fun prepareBlock(nowUs: Long, latencyUs: Long, queued: Long) {
        repeat(64) {
            if (chunk == null) {
                chunk = buffer.take() ?: return
                chunkOffsetFrames = 0
            }
            val pending = chunk!!
            if (pending.data.isEmpty() || pending.data.size % frameBytes != 0) {
                chunk = null
                return@repeat
            }
            val remaining = pending.data.size / frameBytes - chunkOffsetFrames
            val serverUs = pending.serverTimestampMicros + chunkOffsetFrames.toLong() * 1_000_000L / sampleRate
            val targetUs = clock.toLocalMicros(serverUs, nowUs) - compensationUs
            val predictedUs = predictedOutputUs(nowUs, latencyUs, queued)
            if (!started && writtenFrames == 0L) {
                val late = predictedUs - targetUs
                if (late > 1_000L) {
                    val trim = minOf(remaining, ceil(late.toDouble() * sampleRate / 1_000_000).toInt())
                    chunkOffsetFrames += trim
                    dropped += trim
                    if (trim == remaining) chunk = null
                    return@repeat
                }
                firstServerUs = serverUs
            }
            // A large discontinuity in either direction should establish a fresh first-frame
            // deadline, rather than putting seconds of silence or stale audio into AudioTrack.
            if (started && abs(predictedUs - targetUs) > 250_000L) {
                resetOutput(nowUs, "rebuffering", countResync = true, keepChunk = true)
                return
            }
            val decision = if (!started && writtenFrames == 0L) {
                PcmSynchronizer.Decision(errorUs = predictedUs - targetUs)
            } else {
                synchronizer.decide(targetUs, predictedUs, remaining,
                    allowFineCorrection = mode && lastPosition.reliable)
            }
            if (decision.trimFrames > 0) {
                val trim = minOf(remaining, decision.trimFrames)
                chunkOffsetFrames += trim
                dropped += trim
                if (trim == remaining) chunk = null
                return@repeat
            }
            if (decision.silenceFrames > 0) {
                val frames = minOf(decision.silenceFrames, (queueLimit - occupiedFrames).coerceAtLeast(0L).toInt())
                if (frames <= 0) return
                val serverStart = serverUs - ((targetUs - predictedUs).coerceAtLeast(0L))
                block = Block(ByteArray(frames * frameBytes), serverStart, frames, true)
                return
            }
            val frames = minOf(remaining, sampleRate / 50) // Bound a correction block to 20 ms.
            val bytes = resampler.render(pending.data, chunkOffsetFrames * frameBytes, frames, decision.rate)
            chunkOffsetFrames += frames
            if (chunkOffsetFrames == pending.data.size / frameBytes) chunk = null
            if (bytes.isEmpty()) return@repeat
            dropped += (frames - bytes.size / frameBytes).coerceAtLeast(0)
            inserted += (bytes.size / frameBytes - frames).coerceAtLeast(0)
            block = Block(bytes, serverUs, frames, false)
            return
        }
    }

    private fun predictedOutputUs(nowUs: Long, latencyUs: Long, queued: Long): Long {
        if (!started) {
            val first = firstServerUs
            if (first != null && writtenFrames > 0L) {
                return clock.toLocalMicros(first, nowUs) - compensationUs + writtenFrames * 1_000_000L / sampleRate
            }
        }
        return nowUs + latencyUs + queued * 1_000_000L / sampleRate
    }

    private fun updatePlayingSnapshot(nowUs: Long, latencyUs: Long) {
        if (!started) {
            publish("buffering", null)
            return
        }
        val serverUs = timeline.serverTimeAt(lastPosition.frames)
        val errorUs = serverUs?.let { nowUs + latencyUs - (clock.toLocalMicros(it, nowUs) - compensationUs) }
        publish(if (lastPosition.reliable) "playing" else "estimated", errorUs)
    }

    fun pause(nowUs: Long) {
        resetOutput(nowUs, "paused", countResync = started || writtenFrames > 0L)
        writeBlockedSinceUs = null
    }
    fun flush(nowUs: Long) {
        resetOutput(nowUs, "buffering", countResync = started || writtenFrames > 0L)
        writeBlockedSinceUs = null
    }

    private fun resetOutput(nowUs: Long, state: String, countResync: Boolean, keepChunk: Boolean = false) {
        output.pauseAndFlush()
        positions.reset(nowUs)
        consumption.reset(nowUs)
        occupiedFrames = 0L
        timeline.reset()
        synchronizer.reset()
        resampler.reset()
        writtenFrames = 0L
        started = false
        firstServerUs = null
        if (!keepChunk) chunk = null
        block = null
        drySinceUs = null
        positionUsesTimestamp = false
        lastPosition = PlaybackPositionTracker.Position(0L, "unavailable", false)
        if (countResync) resyncs++
        publish(state, null)
    }

    private fun publish(state: String, errorUs: Long?) {
        snapshot = OutputSyncSnapshot(state, lastPosition.source, errorUs, resyncs, underruns, inserted, dropped)
    }
}
