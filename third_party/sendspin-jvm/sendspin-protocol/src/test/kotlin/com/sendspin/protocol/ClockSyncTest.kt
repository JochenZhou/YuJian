package com.sendspin.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {
    @Test
    fun `first valid measurement seeds clock offset and uncertainty`() {
        val clock = ClockSync()
        assertFalse(clock.isReady)
        assertEquals(1_000_000.0, clock.uncertaintyMicros, 0.0)

        // 200 us total client time, 40 us server work, 160 us RTT, +1_000_000 us offset.
        clock.processMeasurement(1_000L, 1_001_080L, 1_001_120L, 1_200L)

        assertTrue(clock.isReady)
        assertEquals(1_000_000L, clock.offsetMicros)
        assertEquals(160L, clock.lastRttMicros)
        assertEquals(40.0, clock.uncertaintyMicros, 0.0)
        assertEquals(1_500L, clock.toLocalMicros(1_001_500L, nowMicros = 1_200L))
    }

    @Test
    fun `invalid timestamp order negative RTT and overflow cannot seed the clock`() {
        val measurements = listOf(
            longArrayOf(1_000L, 2_000L, 2_100L, 900L), // local receive before send
            longArrayOf(1_000L, 2_100L, 2_000L, 1_200L), // server send before receive
            longArrayOf(1_000L, 2_000L, 2_300L, 1_200L), // negative RTT
            longArrayOf(Long.MIN_VALUE, 0L, 1L, Long.MAX_VALUE), // elapsed overflows Long
        )
        for (measurement in measurements) {
            val clock = ClockSync()
            clock.processMeasurement(measurement[0], measurement[1], measurement[2], measurement[3])
            assertFalse("Invalid measurement seeded clock: ${measurement.toList()}", clock.isReady)
            assertEquals(0L, clock.offsetMicros)
            assertEquals(0L, clock.lastRttMicros)
        }
    }

    @Test
    fun `out of order sample cannot move an established filter backwards`() {
        val clock = ClockSync()
        clock.processMeasurement(1_000L, 11_080L, 11_120L, 1_200L)
        val uncertainty = clock.uncertaintyMicros
        clock.processMeasurement(900L, 999_980L, 1_000_000L, 1_100L)
        clock.processMeasurement(1_000L, 999_980L, 1_000_000L, 1_200L)

        assertTrue(clock.isReady)
        assertEquals(10_000L, clock.offsetMicros)
        assertEquals(uncertainty, clock.uncertaintyMicros, 0.0)
        assertEquals(0.0, clock.lastDriftPpm, 0.0)
    }

    @Test
    fun `zero RTT measurements stay finite and keep updating`() {
        val clock = ClockSync()
        clock.processMeasurement(1_000L, 11_000L, 11_000L, 1_000L)
        clock.processMeasurement(2_000L, 12_000L, 12_000L, 2_000L)
        clock.processMeasurement(3_000L, 13_000L, 13_000L, 3_000L)

        assertTrue(clock.isReady)
        assertTrue(clock.uncertaintyMicros.isFinite())
        assertTrue(clock.lastDriftPpm.isFinite())
        assertEquals(10_000L, clock.offsetMicros)
    }

    @Test
    fun `reset clears readiness and permits reseeding against a different server`() {
        val clock = ClockSync()
        clock.processMeasurement(1_000L, 1_001_080L, 1_001_120L, 1_200L)
        clock.reset()

        assertFalse(clock.isReady)
        assertEquals(1_000_000.0, clock.uncertaintyMicros, 0.0)
        assertEquals(0.0, clock.lastOffsetMicros, 0.0)
        assertEquals(0.0, clock.lastDriftPpm, 0.0)
        assertEquals(0L, clock.lastRttMicros)
        clock.processMeasurement(100L, 50_180L, 50_220L, 300L)
        assertTrue(clock.isReady)
        assertEquals(50_000L, clock.offsetMicros)
        assertEquals(0.0, clock.lastDriftPpm, 0.0)
    }
}
