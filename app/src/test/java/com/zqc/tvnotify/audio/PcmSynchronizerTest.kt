package com.zqc.tvnotify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSynchronizerTest {
    @Test fun earlyOutputAddsSilenceAndLateOutputTrims() {
        val early = PcmSynchronizer(48_000, 2).decide(1_000_000, 970_000, 4_800)
        assertEquals(-30_000L, early.errorUs)
        assertEquals(1_440, early.silenceFrames)
        assertEquals(0, early.trimFrames)
        val late = PcmSynchronizer(48_000, 2).decide(1_000_000, 1_030_000, 4_800)
        assertEquals(30_000L, late.errorUs)
        assertEquals(1_440, late.trimFrames)
        assertEquals(0, late.silenceFrames)
    }

    @Test fun hardThresholdIncludesTwentyMillisecondsAndCeilsFractionalFrames() {
        val synchronizer = PcmSynchronizer(44_100, 1)
        assertEquals(882, synchronizer.decide(0, 20_000, 10_000).trimFrames)
        assertEquals(883, synchronizer.decide(0, 20_001, 10_000).trimFrames)
        assertEquals(883, synchronizer.decide(20_001, 0, 10_000).silenceFrames)
    }

    @Test fun hardCorrectionIsBoundedAndTrimCannotExceedAvailableFrames() {
        val synchronizer = PcmSynchronizer(48_000, 2)
        val early = synchronizer.decide(5_000_000, 0, 48_000)
        assertEquals(4_800, early.silenceFrames)
        val late = synchronizer.decide(0, 5_000_000, 400)
        assertEquals(400, late.trimFrames)
        assertEquals(1.0, late.rate, 0.0)
    }

    @Test fun deadbandLeavesRateAtOne() {
        val synchronizer = PcmSynchronizer(48_000, 2)
        for (error in listOf(-1_000L, -500L, 0L, 500L, 1_000L)) {
            val decision = synchronizer.decide(10_000, 10_000 + error, 480)
            assertEquals(1.0, decision.rate, 0.0)
            assertEquals(0, decision.silenceFrames)
            assertEquals(0, decision.trimFrames)
        }
    }

    @Test fun persistentLateErrorSpeedsUpAndEarlyErrorSlowsDown() {
        val late = PcmSynchronizer(48_000, 2)
        val first = late.decide(0, 10_000, 480).rate
        var settled = first
        repeat(100) { settled = late.decide(0, 10_000, 480).rate }
        assertTrue(first > 1.0 && first < settled)
        assertTrue(settled <= 1.005 && settled > 1.004)
        val early = PcmSynchronizer(48_000, 2)
        var slow = 1.0
        repeat(100) { slow = early.decide(10_000, 0, 480).rate }
        assertTrue(slow >= 0.995 && slow < 0.996)
    }

    @Test fun phaseSignReversalConvergesWithoutEscapingRateBounds() {
        val synchronizer = PcmSynchronizer(48_000, 2)
        repeat(100) { synchronizer.decide(0, 10_000, 480) }
        var reversed = 1.0
        repeat(100) {
            reversed = synchronizer.decide(10_000, 0, 480).rate
            assertTrue(reversed in 0.995..1.005)
        }
        assertTrue(reversed < 1.0)
    }

    @Test fun disabledFineCorrectionResetsRateWhileHardCorrectionStillWorks() {
        val synchronizer = PcmSynchronizer(48_000, 2)
        repeat(30) { synchronizer.decide(0, 10_000, 480) }
        assertEquals(1.0, synchronizer.decide(0, 10_000, 480, false).rate, 0.0)
        assertEquals(960, synchronizer.decide(0, 20_000, 2_000, false).trimFrames)
    }

    @Test fun generationResetRemovesPreviousFeedback() {
        val synchronizer = PcmSynchronizer(48_000, 2)
        repeat(50) { synchronizer.decide(0, 10_000, 480) }
        synchronizer.reset()
        assertEquals(1.0, synchronizer.decide(0, 0, 480).rate, 0.0)
        val fresh = PcmSynchronizer(48_000, 2).decide(10_000, 0, 480)
        assertEquals(fresh, synchronizer.decide(10_000, 0, 480))
    }

    @Test fun feedbackClosesAConstantSoftwareTimelineOffset() {
        val synchronizer = PcmSynchronizer(48_000, 2)
        var errorUs = 10_000.0
        repeat(1_000) {
            val rate = synchronizer.decide(0, errorUs.toLong(), 480).rate
            errorUs += 10_000.0 / rate - 10_000.0
        }
        assertTrue("remaining error $errorUs", errorUs in -1_100.0..1_100.0)
    }

    @Test fun noAvailableFramesDoesNotAccumulateRateCorrection() {
        val synchronizer = PcmSynchronizer(48_000, 2)
        repeat(30) { assertEquals(1.0, synchronizer.decide(0, 10_000, 0).rate, 0.0) }
    }
}
