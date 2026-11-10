package com.zqc.tvnotify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmFrameTimelineTest {
    @Test fun mapsFramesAndExactFinalBoundary() {
        val timeline = PcmFrameTimeline(48_000)
        assertNull(timeline.serverTimeAt(0))
        timeline.append(0, 480, 1_000_000)
        assertEquals(1_000_000L, timeline.serverTimeAt(0))
        assertEquals(1_005_000L, timeline.serverTimeAt(240))
        assertEquals(1_010_000L, timeline.serverTimeAt(480))
        assertNull(timeline.serverTimeAt(481))
    }

    @Test fun mapsResampledSourceDurationInsteadOfOutputFrameDuration() {
        val timeline = PcmFrameTimeline(48_000)
        timeline.append(0, 475, 2_000_000, 480)
        assertEquals(2_002_000L, timeline.serverTimeAt(95))
        assertEquals(2_010_000L, timeline.serverTimeAt(475))
    }

    @Test fun partialWritesRetainTimestampAndSourceRatio() {
        val timeline = PcmFrameTimeline(48_000)
        timeline.append(0, 190, 2_000_000, 192)
        timeline.append(190, 285, 2_004_000, 288)
        assertEquals(2_002_000L, timeline.serverTimeAt(95))
        assertEquals(2_004_000L, timeline.serverTimeAt(190))
        assertEquals(2_006_000L, timeline.serverTimeAt(285))
        assertEquals(2_010_000L, timeline.serverTimeAt(475))
    }

    @Test fun contiguousBoundaryUsesTheNextTimestampIncludingDiscontinuity() {
        val timeline = PcmFrameTimeline(48_000)
        timeline.append(0, 480, 1_000_000)
        timeline.append(480, 480, 3_000_000)
        assertEquals(3_000_000L, timeline.serverTimeAt(480))
    }

    @Test fun unrecordedGapHasNoTimeAndArtificialSilenceCanFillIt() {
        val timeline = PcmFrameTimeline(48_000)
        timeline.append(0, 480, 1_000_000)
        timeline.append(960, 480, 1_020_000)
        assertNull(timeline.serverTimeAt(720))
        val withSilence = PcmFrameTimeline(48_000)
        withSilence.append(0, 480, 1_000_000)
        withSilence.append(480, 480, 1_010_000)
        withSilence.append(960, 480, 1_020_000)
        assertEquals(1_015_000L, withSilence.serverTimeAt(720))
    }

    @Test fun discardRemovesConsumedHistoryWithoutChangingRemainingInterpolation() {
        val timeline = PcmFrameTimeline(48_000)
        timeline.append(0, 475, 2_000_000, 480)
        timeline.discardBefore(95)
        assertNull(timeline.serverTimeAt(94))
        assertEquals(2_002_000L, timeline.serverTimeAt(95))
        assertEquals(2_010_000L, timeline.serverTimeAt(475))
        timeline.discardBefore(475)
        assertNull(timeline.serverTimeAt(474))
        assertEquals(2_010_000L, timeline.serverTimeAt(475))
    }

    @Test fun longRunningConsumedSegmentsCanBeDiscardedAsPlaybackAdvances() {
        val timeline = PcmFrameTimeline(48_000)
        repeat(10_000) { index ->
            val start = index * 480L
            timeline.append(start, 480, index * 10_000L)
            timeline.discardBefore(start)
        }
        assertNull(timeline.serverTimeAt(0))
        assertEquals(99_995_000L, timeline.serverTimeAt(4_799_760))
    }

    @Test fun resetAllowsEarlierServerTimestampAndFrameZero() {
        val timeline = PcmFrameTimeline(48_000)
        timeline.append(0, 480, 10_000_000)
        timeline.discardBefore(480)
        timeline.reset()
        timeline.append(0, 480, 100_000)
        assertEquals(100_000L, timeline.serverTimeAt(0))
        assertEquals(105_000L, timeline.serverTimeAt(240))
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingSegmentsAreRejected() {
        val timeline = PcmFrameTimeline(48_000)
        timeline.append(0, 480, 0)
        timeline.append(479, 480, 10_000)
    }
}
