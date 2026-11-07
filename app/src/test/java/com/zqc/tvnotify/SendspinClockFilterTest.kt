package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SendspinClockFilterTest {
    @Test
    fun handlesLargeEpochDisparityWithoutOverflow() {
        val filter = SendspinClockFilter()
        val localBase = 120_000_000L // 120s uptime in us
        val serverBase = 1_762_531_200_000_000L // Unix epoch 2025+ in us
        // True offset = serverBase - localBase, symmetric 2ms one-way delay (4000us RTT)
        filter.onTimeExchange(
            clientTxUs = localBase,
            serverRxUs = serverBase + 2_000L,
            serverTxUs = serverBase + 2_100L,
            clientRxUs = localBase + 4_100L
        )
        assertTrue(filter.isSynced)
        assertEquals(4_000L, filter.minRttUs)
        assertTrue(abs(filter.localToServerUs(localBase + 1_000_000L) - (serverBase + 1_000_000L)) <= 50L)

        // Extrapolate 10.5 seconds at 1.0x (playbackSpeed = 1000) and clamp to duration
        val meta = SendspinTrackMetadata(
            timestampUs = serverBase,
            title = "Test",
            hasProgress = true,
            trackProgressMs = 30_000L,
            trackDurationMs = 180_000L,
            playbackSpeed = 1000
        )
        val pos = filter.currentTrackPositionMs(meta, localBase + 10_500_000L)
        assertEquals(40_500L, pos)

        // Clamps to trackDurationMs even after 10 hours
        val clamped = filter.currentTrackPositionMs(meta, localBase + 36_000_000_000L)
        assertEquals(180_000L, clamped)
    }

    @Test
    fun rejectsHighJitterSpikeUsingMinRttWindow() {
        val filter = SendspinClockFilter()
        // Clean measurement: offset = +500_000us, RTT = 2_000us
        repeat(4) { i ->
            val t = i * 1_000_000L
            filter.onTimeExchange(t, t + 501_000L, t + 501_000L, t + 2_000L)
        }
        // Asymmetric Wi-Fi spike: 200ms delayed downlink -> RTT = 201_000us, skewed offset
        val spikeT = 5_000_000L
        filter.onTimeExchange(spikeT, spikeT + 501_000L, spikeT + 501_000L, spikeT + 201_000L)
        // Mapped time must remain within 2ms of true +500_000us offset
        val mappedLocal = filter.serverToLocalUs(10_500_000L)
        assertTrue(abs(mappedLocal - 10_000_000L) < 2_000L)
    }
}
