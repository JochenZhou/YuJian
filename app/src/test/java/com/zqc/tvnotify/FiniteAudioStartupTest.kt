package com.zqc.tvnotify

import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioChunk
import com.sendspin.protocol.ClockSync
import org.junit.Assert.*
import org.junit.Test

class FiniteAudioStartupTest {
    private data class Play(val timeUs: Long, val queuedFrames: Long)

    /** A streaming native queue: writes cannot consume space until play starts its clock. */
    private class FiniteOutput(
        private val nowUs: () -> Long,
        var actualCapacityFrames: Int = 4_800,
        private var advertisedCapacityFrames: Int = 9_600,
        private val writeLimitFrames: Int = Int.MAX_VALUE,
    ) : SendspinPcmPump.Output {
        private var submittedFrames = 0L
        private var consumedFrames = 0L
        private var observedUs = nowUs()
        private var writeTickUs = Long.MIN_VALUE
        private var writesThisTick = 0
        var playing = false
            private set
        var acceptWrites = true
        var replacementCapacityFrames: Int? = null
        var flushes = 0
            private set
        var fullZeroWrites = 0
            private set
        var emptyZeroWrites = 0
            private set
        var positiveWritesAfterPlay = 0
            private set
        var maxWritesPerTick = 0
            private set
        var maxQueuedFrames = 0L
            private set
        val plays = mutableListOf<Play>()
        val samples = mutableListOf<Short>()

        val queuedFrames: Long
            get() {
                advance()
                return submittedFrames - consumedFrames
            }

        private fun advance() {
            val currentUs = nowUs()
            check(currentUs >= observedUs) { "Fake time moved backwards" }
            if (playing) {
                val elapsedFrames = (currentUs - observedUs) * SAMPLE_RATE / 1_000_000L
                consumedFrames = minOf(submittedFrames, consumedFrames + elapsedFrames)
            }
            observedUs = currentUs
        }

        override fun playbackHead(): Long {
            advance()
            return consumedFrames
        }

        override fun timestamp(): SendspinPcmPump.Timestamp? = null
        override fun minimumBufferFrames() = 2_400
        override fun bufferCapacityFrames() = advertisedCapacityFrames

        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            assertTrue("write must contain complete mono PCM16 frames", length > 0 && length % 2 == 0)
            assertTrue("write must stay within its PCM block", offset >= 0 && offset + length <= data.size)
            if (writeTickUs != nowUs()) {
                writeTickUs = nowUs()
                writesThisTick = 0
            }
            writesThisTick++
            maxWritesPerTick = maxOf(maxWritesPerTick, writesThisTick)
            assertTrue("a single pump tick must stop retrying a finite native queue", writesThisTick <= 256)

            val room = actualCapacityFrames - queuedFrames.toInt()
            assertTrue("submitted PCM exceeded actual native capacity", room >= 0)
            val accepted = if (acceptWrites) minOf(length / 2, room, writeLimitFrames) else 0
            if (accepted == 0) {
                if (room == 0) fullZeroWrites++
                if (queuedFrames == 0L) emptyZeroWrites++
                return 0
            }
            repeat(accepted) { frame ->
                val index = offset + frame * 2
                samples += ((data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)).toShort()
            }
            submittedFrames += accepted
            if (playing) positiveWritesAfterPlay++
            maxQueuedFrames = maxOf(maxQueuedFrames, queuedFrames)
            assertTrue("outstanding submitted PCM must fit the actual queue", queuedFrames <= actualCapacityFrames)
            return accepted * 2
        }

        override fun play() {
            advance()
            assertFalse("play must only be called once per priming cycle", playing)
            plays += Play(nowUs(), queuedFrames)
            playing = true
        }

        override fun pauseAndFlush() {
            advance()
            playing = false
            submittedFrames = 0L
            consumedFrames = 0L
            samples.clear()
            replacementCapacityFrames?.let { capacity ->
                actualCapacityFrames = capacity
                advertisedCapacityFrames = capacity
                replacementCapacityFrames = null
            }
            flushes++
        }
    }

    private class Fixture(advertisedCapacityFrames: Int = 9_600, writeLimitFrames: Int = Int.MAX_VALUE) {
        // offer() checks the real monotonic clock. Keep every simulated packet safely in its
        // admitted future window, even when the fake consumer advances several seconds instantly.
        val baseUs = ClockSync.localMicros() + 5_000_000L
        var nowUs = baseUs
            private set
        val clock = ClockSync().apply { processMeasurement(baseUs, baseUs, baseUs, baseUs) }
        val buffer = AudioBuffer(clock)
        val output = FiniteOutput(
            nowUs = { nowUs },
            advertisedCapacityFrames = advertisedCapacityFrames,
            writeLimitFrames = writeLimitFrames,
        )
        val pump = SendspinPcmPump(buffer, clock, SAMPLE_RATE, 1, output)

        fun offerRun(firstUs: Long, frames: Int = 24_000, packetFrames: Int = 960, value: Short = 12_000) {
            val previousSize = buffer.size
            var offsetFrames = 0
            var packets = 0
            while (offsetFrames < frames) {
                val count = minOf(packetFrames, frames - offsetFrames)
                val data = ByteArray(count * 2)
                repeat(count) {
                    data[it * 2] = value.toByte()
                    data[it * 2 + 1] = (value.toInt() shr 8).toByte()
                }
                buffer.offer(AudioChunk(firstUs + offsetFrames * 1_000_000L / SAMPLE_RATE, data))
                offsetFrames += count
                packets++
            }
            assertEquals("future PCM must reach the real AudioBuffer", previousSize + packets, buffer.size)
        }

        fun tick() {
            pump.step(nowUs)
            nowUs += TICK_US
        }

        fun runBefore(deadlineUs: Long) {
            while (nowUs < deadlineUs) tick()
        }

        fun runThrough(deadlineUs: Long) {
            while (nowUs <= deadlineUs) tick()
        }

        fun expectWriteFailureWithin(durationUs: Long) {
            val stopUs = nowUs + durationUs
            while (nowUs <= stopUs) {
                try {
                    tick()
                } catch (error: IllegalStateException) {
                    assertEquals("AudioTrack write stalled", error.message)
                    return
                }
            }
            fail("a genuinely unwritable output must fail within the recovery budget")
        }
    }

    private fun assertStart(play: Play, deadlineUs: Long) {
        assertTrue("PCM played before its first timestamp", play.timeUs >= deadlineUs)
        assertTrue("PCM missed its first timestamp by more than 20 ms", play.timeUs <= deadlineUs + 20_000L)
        assertEquals("play requires the full actual native preroll", 4_800L, play.queuedFrames)
    }

    private fun assertHealthy(f: Fixture, deadlineUs: Long) {
        assertEquals("healthy output must start exactly once", 1, f.output.plays.size)
        assertStart(f.output.plays.single(), deadlineUs)
        assertTrue(f.pump.isPlaying)
        assertEquals("finite backpressure must not flush healthy PCM", 0, f.output.flushes)
        assertEquals("finite backpressure must not cause a bogus resync", 0L, f.pump.snapshot.resyncCount)
        assertEquals(0L, f.pump.snapshot.underrunCount)
        assertTrue(f.output.maxQueuedFrames <= f.output.actualCapacityFrames)
    }

    @Test(timeout = 5_000) fun `full native queue starts on time when reported capacity is too large`() {
        val f = Fixture()
        val deadlineUs = f.baseUs + 100_000L
        f.offerRun(deadlineUs)
        f.runBefore(deadlineUs)
        assertEquals(4_800L, f.output.queuedFrames)
        assertTrue("exercise a zero write after positive prefill", f.output.fullZeroWrites > 0)
        assertTrue("prefill must remain silent", f.output.plays.isEmpty())
        f.runThrough(deadlineUs + 300_000L)
        assertHealthy(f, deadlineUs)
    }

    @Test(timeout = 5_000) fun `accurately reported finite capacity also starts on time`() {
        val f = Fixture(advertisedCapacityFrames = 4_800)
        val deadlineUs = f.baseUs + 100_000L
        f.offerRun(deadlineUs)
        f.runBefore(deadlineUs)
        assertEquals(4_800L, f.output.queuedFrames)
        assertTrue("prefill must remain silent", f.output.plays.isEmpty())
        f.runThrough(deadlineUs + 300_000L)
        assertHealthy(f, deadlineUs)
    }

    @Test(timeout = 5_000) fun `full prestart queue waits two seconds without tripping write watchdog`() {
        val f = Fixture()
        val deadlineUs = f.baseUs + 2_000_000L
        f.offerRun(deadlineUs)
        f.runBefore(deadlineUs)
        assertTrue("the smaller real queue must have rejected further prefill", f.output.fullZeroWrites > 0)
        assertEquals(4_800L, f.output.queuedFrames)
        assertTrue("a full native queue must still respect a distant timestamp", f.output.plays.isEmpty())
        f.runThrough(deadlineUs + 100_000L)
        assertHealthy(f, deadlineUs)
    }

    @Test(timeout = 5_000) fun `bounded batching primes short partial writes before a near deadline`() {
        val f = Fixture(advertisedCapacityFrames = 4_800, writeLimitFrames = 480)
        val deadlineUs = f.baseUs + 8_000L
        // Twenty contiguous 5 ms packets take 40 ms with one write per 2 ms tick.
        // Their total native preroll is available immediately; every tick still advances time.
        f.offerRun(deadlineUs, frames = 9_600, packetFrames = 240)
        f.runBefore(deadlineUs)
        assertTrue("prefill must remain silent", f.output.plays.isEmpty())
        f.runThrough(deadlineUs + 20_000L)
        assertTrue("prefill must make multiple bounded writes per tick", f.output.maxWritesPerTick > 1)
        assertHealthy(f, deadlineUs)
    }

    @Test(timeout = 5_000) fun `zero acceptance from an empty native queue still fails within recovery budget`() {
        val f = Fixture()
        val deadlineUs = f.baseUs + 2_000_000L
        f.output.acceptWrites = false
        f.offerRun(deadlineUs)
        f.expectWriteFailureWithin(2_000_000L)
        assertTrue("exercise an unwritable empty output", f.output.emptyZeroWrites > 0)
        assertEquals(0L, f.output.maxQueuedFrames)
        assertTrue("empty zero writes cannot qualify as a primed queue", f.output.plays.isEmpty())
    }

    @Test(timeout = 5_000) fun `genuine write stall after playing retains normal watchdog recovery`() {
        val f = Fixture(advertisedCapacityFrames = 4_800)
        val deadlineUs = f.baseUs + 100_000L
        f.offerRun(deadlineUs, frames = 144_000)
        f.runThrough(deadlineUs + 50_000L)
        assertHealthy(f, deadlineUs)
        assertTrue("exercise successful writes after play before stalling", f.output.positiveWritesAfterPlay > 0)
        f.output.acceptWrites = false
        f.expectWriteFailureWithin(2_000_000L)
        assertTrue("stalled output eventually becomes empty", f.output.emptyZeroWrites > 0)
        assertEquals("an unwritable output must not restart with no preroll", 1, f.output.plays.size)
    }

    @Test(timeout = 5_000) fun `pause reclaims finite queue and reprime keeps the new first timestamp`() {
        val f = Fixture()
        val firstUs = f.baseUs + 100_000L
        f.offerRun(firstUs, value = (-12_000).toShort())
        f.runThrough(firstUs + 50_000L)
        assertHealthy(f, firstUs)
        assertTrue(f.output.queuedFrames > 0L)

        f.pump.pause(f.nowUs)
        f.buffer.flush()
        assertEquals("pause must release the finite native space", 0L, f.output.queuedFrames)
        assertFalse(f.output.playing)
        assertFalse(f.pump.isPlaying)
        assertEquals("paused", f.pump.snapshot.state)

        val resumedUs = f.nowUs + 100_000L
        f.offerRun(resumedUs, value = 12_000)
        f.runBefore(resumedUs)
        assertEquals("repreroll must fit the reclaimed queue", 4_800L, f.output.queuedFrames)
        assertEquals("new PCM must wait for its own timestamp", 1, f.output.plays.size)
        assertTrue("old PCM must be discarded on pause", f.output.samples.all { it >= 0 })
        assertTrue("fresh PCM must reach the reclaimed queue", f.output.samples.contains(12_000.toShort()))
        f.runThrough(resumedUs + 50_000L)
        assertEquals(2, f.output.plays.size)
        assertStart(f.output.plays.last(), resumedUs)
        assertTrue(f.pump.isPlaying)
        assertEquals("only the explicit pause may flush", 1, f.output.flushes)
        assertEquals("only the explicit pause may re-prime", 1L, f.pump.snapshot.resyncCount)
        assertEquals(0L, f.pump.snapshot.underrunCount)
    }

    @Test(timeout = 5_000) fun `replacement with larger capacity is fully primed before resuming`() {
        val f = Fixture(advertisedCapacityFrames = 4_800)
        val firstUs = f.baseUs + 100_000L
        f.offerRun(firstUs)
        f.runThrough(firstUs + 50_000L)
        assertHealthy(f, firstUs)

        f.output.replacementCapacityFrames = 8_160
        f.pump.pause(f.nowUs)
        f.buffer.flush()
        val resumedUs = f.nowUs + 100_000L
        f.offerRun(resumedUs)
        f.runBefore(resumedUs)
        assertEquals("replacement must use its own native capacity", 8_160L, f.output.queuedFrames)
        assertEquals("new output must not start before its timestamp", 1, f.output.plays.size)
        f.runThrough(resumedUs + 50_000L)
        assertEquals(2, f.output.plays.size)
        assertEquals(8_160L, f.output.plays.last().queuedFrames)
        assertTrue(f.pump.isPlaying)
        assertEquals(1, f.output.flushes)
    }

    @Test(timeout = 5_000) fun `mode reset refreshes larger replacement capacity within the same tick`() {
        val f = Fixture(advertisedCapacityFrames = 4_800)
        val firstUs = f.baseUs + 100_000L
        f.offerRun(firstUs)
        f.runThrough(firstUs + 50_000L)
        assertHealthy(f, firstUs)

        f.buffer.flush()
        f.output.replacementCapacityFrames = 8_160
        f.buffer.setAutomaticSync(false)
        val resumedUs = f.nowUs + 100_000L
        f.offerRun(resumedUs)
        f.runBefore(resumedUs)
        assertEquals("mode reset must refresh startup and flow-control limits", 8_160L, f.output.queuedFrames)
        f.runThrough(resumedUs + 50_000L)
        assertEquals(2, f.output.plays.size)
        assertEquals(8_160L, f.output.plays.last().queuedFrames)
        assertEquals(1, f.output.flushes)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val TICK_US = 2_000L
    }
}
