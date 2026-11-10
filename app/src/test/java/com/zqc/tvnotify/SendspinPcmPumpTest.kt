package com.zqc.tvnotify

import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioChunk
import com.sendspin.protocol.ClockSync
import org.junit.Assert.*
import org.junit.Test

class SendspinPcmPumpTest {
    private class Output : SendspinPcmPump.Output {
        var head = 0L
        var written = 0L
        var playing = false
        var plays = 0
        var limitFrames = Int.MAX_VALUE
        var clockUs = 0L
        var startUs = 0L
        var speed = 1.0
        var useHardwareHead = false
        var emitTimestamp = false
        var latency = 0L
        var timestampDelayUs = 0L
        var minimumFrames = 0
        val samples = mutableListOf<Short>()
        override fun playbackHead(): Long = if (useHardwareHead && playing) {
            minOf(written, ((clockUs - startUs).coerceAtLeast(0) * 48_000 * speed / 1_000_000).toLong())
        } else head
        override fun timestamp(): SendspinPcmPump.Timestamp? = if (emitTimestamp && playing && clockUs - startUs >= timestampDelayUs) {
            SendspinPcmPump.Timestamp(minOf(written, (clockUs - startUs - timestampDelayUs).coerceAtLeast(0) * 48_000 / 1_000_000), clockUs)
        } else null
        override fun latencyUs() = latency
        override fun minimumBufferFrames() = minimumFrames
        override fun write(data: ByteArray, offset: Int, length: Int): Int {
            val frames = minOf(length / 2, limitFrames)
            repeat(frames) {
                val index = offset + it * 2
                samples += ((data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)).toShort()
            }
            written += frames
            return frames * 2
        }
        override fun play() { playing = true; startUs = clockUs; plays++ }
        override fun pauseAndFlush() { playing = false; head = 0; written = 0 }
    }

    private class Fixture(ready: Boolean = true, minimumFrames: Int = 0) {
        val base = ClockSync.localMicros()
        val clock = ClockSync()
        val buffer = AudioBuffer(clock)
        val output = Output().apply { this.minimumFrames = minimumFrames }
        val pump = SendspinPcmPump(buffer, clock, 48_000, 1, output)
        init { if (ready) clock.processMeasurement(base, base, base, base) }
        fun add(startUs: Long, frames: Int, value: Short = 1234) {
            val data = ByteArray(frames * 2)
            repeat(frames) { data[it * 2] = value.toByte(); data[it * 2 + 1] = (value.toInt() shr 8).toByte() }
            buffer.offer(AudioChunk(startUs, data))
        }
        fun prefill(nowUs: Long) { repeat(12) { pump.step(nowUs) } }
    }

    @Test fun `does not write or play before clock becomes ready`() {
        val f = Fixture(false)
        f.add(f.base + 100_000, 1920)
        f.prefill(f.base)
        assertEquals(0L, f.output.written)
        assertFalse(f.output.playing)
        assertEquals("waiting_clock", f.pump.snapshot.state)
    }

    @Test fun `prefills without playing until first frame deadline`() {
        val f = Fixture()
        f.add(f.base + 100_000, 3840)
        f.prefill(f.base)
        assertTrue(f.output.written >= 1920)
        assertFalse(f.output.playing)
        f.pump.step(f.base + 99_000)
        assertFalse(f.output.playing)
        f.pump.step(f.base + 100_000)
        assertTrue(f.output.playing)
        assertEquals(1, f.output.plays)
    }

    @Test fun `TV output is primed above its HAL minimum before play`() {
        val f = Fixture(minimumFrames = 4_800) // 100 ms at 48 kHz, above the former 80 ms limit.
        f.add(f.base + 100_000L, 9_600)
        repeat(24) { f.pump.step(f.base) }
        assertTrue("HAL minimum must fit within queue budget", f.output.written >= 4_800L)
        f.pump.step(f.base + 100_000L)
        assertTrue(f.output.playing)
        assertEquals(1, f.output.plays)
    }

    @Test fun `short preroll waits for contiguous samples instead of starting an underrun`() {
        val f = Fixture(minimumFrames = 4_800)
        f.add(f.base + 100_000L, 960)
        f.prefill(f.base)
        f.pump.step(f.base + 100_000L)
        assertFalse("one 20ms packet cannot prime a 100ms HAL", f.output.playing)
        f.add(f.base + 120_000L, 8_640)
        repeat(24) { f.pump.step(f.base + 102_000L) }
        f.pump.step(f.base + 104_000L)
        assertTrue(f.output.playing)
        assertTrue(f.output.written >= 4_800L)
    }

    @Test fun `late chunk is trimmed instead of replaying old content`() {
        val f = Fixture()
        f.add(f.base - 100_000, 960, -20_000)
        f.add(f.base + 100_000, 3840, 20_000)
        f.prefill(f.base)
        assertTrue("stale negative PCM must not reach the output", f.output.samples.all { it >= 0 })
        assertTrue(f.output.samples.contains(20_000.toShort()))
        assertTrue(f.pump.snapshot.droppedFrames >= 960)
    }

    @Test fun `fresh audio fades from zero without adding output frames or changing its deadline`() {
        val f = Fixture()
        f.output.limitFrames = 48
        f.add(f.base + 100_000L, 960, 20_000)
        repeat(24) { f.pump.step(f.base) }
        assertEquals(960L, f.output.written)
        assertEquals(0.toShort(), f.output.samples.first())
        assertEquals(20_000.toShort(), f.output.samples[191])
        assertEquals(20_000.toShort(), f.output.samples.last())
        f.pump.step(f.base + 99_000L)
        assertFalse(f.output.playing)
        f.pump.step(f.base + 100_000L)
        assertTrue(f.output.playing)
    }

    @Test fun `partial write retains original timestamp and frame position`() {
        val f = Fixture()
        f.output.limitFrames = 120
        f.add(f.base + 100_000, 1920, 11)
        repeat(30) { f.pump.step(f.base) }
        assertEquals(1920L, f.output.written)
        assertEquals(1920, f.output.samples.size)
        f.pump.step(f.base + 100_000)
        assertTrue(f.output.playing)
        assertEquals(0L, f.pump.snapshot.errorMicros ?: 0L)
    }

    @Test fun `focus pause discards queued output and resumes at current timestamp`() {
        val f = Fixture()
        f.add(f.base + 100_000, 3840, 3)
        f.prefill(f.base)
        f.pump.step(f.base + 100_000)
        f.pump.pause(f.base + 110_000)
        assertFalse(f.output.playing)
        assertEquals("paused", f.pump.snapshot.state)
        f.add(f.base + 1_100_000, 3840, 5)
        f.prefill(f.base + 1_000_000)
        f.pump.step(f.base + 1_100_000)
        assertTrue(f.output.playing)
        assertEquals(2, f.output.plays)
        assertEquals(5.toShort(), f.output.samples.last())
    }

    @Test fun `continuous output corrects both directions of hardware clock drift`() {
        for (speed in listOf(0.998, 1.002)) {
            val f = Fixture()
            f.output.speed = speed
            f.output.useHardwareHead = true
            var packet = 0
            var maxError = 0L
            var sawReliable = false
            for (tick in 0..10_000) {
                val now = f.base + tick * 2_000L
                f.output.clockUs = now
                while (packet < 1010 && f.base + 100_000L + packet * 20_000L <= now + 180_000L) {
                    f.add(f.base + 100_000L + packet * 20_000L, 960)
                    packet++
                }
                f.pump.step(now)
                if (tick > 1000 && f.pump.snapshot.positionSource == "playback_head") {
                    sawReliable = true
                    maxError = maxOf(maxError, kotlin.math.abs(f.pump.snapshot.errorMicros ?: 0L))
                }
            }
            assertTrue("hardware feedback was not used at speed $speed", sawReliable)
            assertTrue("drift grew beyond 20ms at speed $speed: $maxError", maxError < 20_000L)
            assertEquals("unexpected re-prime at speed $speed", 1, f.output.plays)
            assertEquals(0L, f.pump.snapshot.underrunCount)
        }
    }

    @Test fun `normal downstream presentation latency is not mistaken for a full track queue`() {
        val f = Fixture()
        f.output.useHardwareHead = true
        f.output.emitTimestamp = true
        f.output.timestampDelayUs = 50_000L
        f.output.latency = 50_000L
        var packet = 0
        var sawTimestamp = false
        for (tick in 0..1000) {
            val now = f.base + tick * 2_000L
            f.output.clockUs = now
            while (f.base + 100_000L + packet * 20_000L <= now + 180_000L) {
                f.add(f.base + 100_000L + packet * 20_000L, 960)
                packet++
            }
            f.pump.step(now)
            if (f.pump.snapshot.positionSource == "timestamp") sawTimestamp = true
        }
        assertTrue("valid timestamp should take over", sawTimestamp)
        assertEquals("healthy pipeline must not repeatedly flush", 1, f.output.plays)
        assertEquals(0L, f.pump.snapshot.resyncCount)
        assertTrue(kotlin.math.abs(f.pump.snapshot.errorMicros ?: Long.MAX_VALUE) < 5_000L)
    }

    @Test fun `hardware takeover behind estimate discards hidden output backlog`() {
        val f = Fixture()
        var packet = 0
        for (tick in 0..2000) {
            val now = f.base + tick * 2_000L
            while (f.base + 100_000L + packet * 20_000L <= now + 180_000L) {
                f.add(f.base + 100_000L + packet * 20_000L, 960)
                packet++
            }
            f.pump.step(now)
        }
        assertTrue(f.output.written > 180_000L)
        f.output.head = 480L
        f.pump.step(f.base + 4_002_000L)
        assertFalse("old output must be flushed when hardware exposes backlog", f.output.playing)
        assertTrue(f.pump.snapshot.resyncCount > 0)
        assertEquals("rebuffering", f.pump.snapshot.state)
    }

    @Test fun `missing timestamp preserves its position domain without adding head latency`() {
        val f = Fixture()
        f.output.emitTimestamp = true
        var packet = 0
        var sawTimestamp = false
        for (tick in 0..1000) {
            val now = f.base + tick * 2_000L
            f.output.clockUs = now
            while (f.base + 100_000L + packet * 20_000L <= now + 180_000L) {
                f.add(f.base + 100_000L + packet * 20_000L, 960)
                packet++
            }
            if (tick == 500) {
                f.output.emitTimestamp = false
                f.output.latency = 50_000L
            }
            f.pump.step(now)
            if (tick < 500 && f.pump.snapshot.positionSource == "timestamp") sawTimestamp = true
        }
        assertTrue(sawTimestamp)
        assertEquals(1, f.output.plays)
        assertEquals(0L, f.pump.snapshot.droppedFrames)
        assertTrue(kotlin.math.abs(f.pump.snapshot.errorMicros ?: Long.MAX_VALUE) < 5_000L)
    }

    @Test fun `zero writes trigger recovery even across underrun reprime`() {
        val f = Fixture()
        f.add(f.base + 100_000L, 3840)
        f.prefill(f.base)
        f.pump.step(f.base + 100_000L)
        f.output.limitFrames = 0
        var failed = false
        var packet = 0
        for (tick in 0..1000) {
            val now = f.base + 102_000L + tick * 2_000L
            while (f.base + 180_000L + packet * 20_000L <= now + 180_000L) {
                f.add(f.base + 180_000L + packet * 20_000L, 960)
                packet++
            }
            try { f.pump.step(now) } catch (error: IllegalStateException) {
                assertEquals("AudioTrack write stalled", error.message)
                failed = true
                break
            }
        }
        assertTrue("zero writes must eventually ask the adapter to rebuild", failed)
    }

    @Test fun `long starvation re-primes and never replays a late burst`() {
        val f = Fixture()
        f.add(f.base + 100_000, 1920, 1)
        f.prefill(f.base)
        f.pump.step(f.base + 100_000)
        f.pump.step(f.base + 160_000)
        f.pump.step(f.base + 200_000)
        assertTrue(f.pump.snapshot.underrunCount > 0)
        f.add(f.base + 300_000, 960, -20_000)
        f.add(f.base + 1_100_000, 1920, 20_000)
        f.prefill(f.base + 1_000_000)
        assertTrue("stale burst must not be played through the fade", f.output.samples.all { it >= 0 })
        f.pump.step(f.base + 1_100_000)
        assertTrue(f.output.playing)
        assertEquals(20_000.toShort(), f.output.samples.last())
    }
}
