package com.zqc.tvnotify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPositionTrackerTest {
    @Test fun resetHasNoPositionUntilPlaybackStarts() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.reset(1_000_000)
        val position = tracker.sample(1_010_000, 48_000, 0)
        assertEquals(0L, position.frames)
        assertEquals("unavailable", position.source)
        assertFalse(position.reliable)
    }

    @Test fun estimateStartsAtZeroInsteadOfQueuedFrames() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.reset(0)
        tracker.start(0)
        assertEquals(0L, tracker.sample(0, 48_000, 0).frames)
        val position = tracker.sample(10_000, 48_000, 0)
        assertEquals(480L, position.frames)
        assertEquals("estimate", position.source)
        assertFalse(position.reliable)
    }

    @Test fun initialZeroHeadsAreRetriedUntilTheyAdvance() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.reset(0)
        tracker.start(0)
        tracker.sample(0, 48_000, 0)
        assertEquals("estimate", tracker.sample(100_000, 48_000, 0).source)
        val position = tracker.sample(110_000, 48_000, 480)
        assertEquals(480L, position.frames)
        assertEquals("playback_head", position.source)
        assertTrue(position.reliable)
    }

    @Test fun estimateFreezesAtSubmittedLimitAndNewWritesDoNotConsumeOldGap() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 480, 0)
        assertEquals(480L, tracker.sample(10_000, 480, 0).frames)
        assertEquals(480L, tracker.sample(5_000_000, 960, 0).frames)
        assertEquals(720L, tracker.sample(5_005_000, 960, 0).frames)
        assertEquals(960L, tracker.sample(5_020_000, 960, 0).frames)
    }

    @Test fun firstSubmissionAfterEmptyGapStartsAtSubmissionObservation() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 0, 0)
        assertEquals(0L, tracker.sample(4_000_000, 480, 0).frames)
        assertEquals(240L, tracker.sample(4_005_000, 480, 0).frames)
    }

    @Test fun pauseFreezesAndResumeDoesNotCountPausedTime() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0)
        tracker.pause(10_000, 48_000)
        assertEquals(480L, tracker.sample(2_000_000, 48_000, 0).frames)
        tracker.start(2_000_000)
        assertEquals(960L, tracker.sample(2_010_000, 48_000, 0).frames)
    }

    @Test fun repeatedStartDoesNotResetElapsedPlayback() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0)
        tracker.start(5_000)
        assertEquals(480L, tracker.sample(10_000, 48_000, 0).frames)
    }

    @Test fun hardwareTakesOverEvenBehindFourSecondEstimate() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 480_000, 0)
        assertEquals(192_000L, tracker.sample(4_000_000, 480_000, 0).frames)
        val position = tracker.sample(4_010_000, 480_000, 480)
        assertEquals(480L, position.frames)
        assertTrue(position.reliable)
        assertEquals("playback_head", position.source)
    }

    @Test fun temporaryZeroIsNotTreatedAsWrapOrCounterReset() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0)
        tracker.sample(10_000, 48_000, 480)
        val zero = tracker.sample(20_000, 48_000, 0)
        assertTrue(zero.frames in 480L..960L)
        val recovered = tracker.sample(30_000, 48_000, 1_440)
        assertEquals(1_440L, recovered.frames)
        assertEquals("playback_head", recovered.source)
        assertTrue(recovered.reliable)
    }

    @Test fun bogusRegressionDoesNotCreateUnsignedWrap() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0)
        tracker.sample(10_000, 48_000, 480)
        val regression = tracker.sample(20_000, 48_000, 400)
        assertFalse(regression.reliable)
        assertTrue(regression.frames <= 960)
        assertEquals(1_440L, tracker.sample(30_000, 48_000, 1_440).frames)
    }

    @Test fun realUnsigned32WrapUnwrapsAtPlausibleRate() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        val nearWrap = 0xfffffff0L
        val then = 89_478_485_000L
        tracker.sample(then, 0x1_0000_1000L, nearWrap)
        val wrapped = tracker.sample(then + 1_000, 0x1_0000_1000L, 32)
        assertEquals(0x1_0000_0020L, wrapped.frames)
        assertEquals("playback_head", wrapped.source)
        assertTrue(wrapped.reliable)
    }

    @Test fun headBeyondWritesIsRejectedAndNeverReturned() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 480, 0)
        val invalid = tracker.sample(10_000, 480, 960)
        assertEquals(480L, invalid.frames)
        assertFalse(invalid.reliable)
        val valid = tracker.sample(20_000, 480, 480)
        assertEquals(480L, valid.frames)
        assertTrue(valid.reliable)
    }

    @Test fun impossibleHeadJumpIsRejectedEvenWhenWritten() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 480_000, 0)
        val invalid = tracker.sample(10_000, 480_000, 48_000)
        assertEquals(480L, invalid.frames)
        assertFalse(invalid.reliable)
    }

    @Test fun advancingTimestampIsPreferredAndExtrapolated() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0, 0, 0)
        val position = tracker.sample(20_000, 48_000, 480, 480, 10_000)
        assertEquals(960L, position.frames)
        assertEquals("timestamp", position.source)
        assertTrue(position.reliable)
    }

    @Test fun futureCommittedTimestampUsesSignedExtrapolation() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0, 0, 0)
        val future = tracker.sample(10_000, 48_000, 480, 960, 20_000)
        assertEquals(480L, future.frames)
        assertEquals("timestamp", future.source)
        assertTrue(future.reliable)
    }

    @Test fun timestampExtrapolationCannotRunBeyondWrites() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 480, 0, 0, 0)
        val position = tracker.sample(20_000, 480, 0, 480, 10_000)
        assertEquals(480L, position.frames)
        assertTrue(position.reliable)
    }

    @Test fun staleTimestampFallsBackWithoutPhantomFramesAfterStarvation() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 480, 0, 0, 0)
        tracker.sample(10_000, 480, 0, 480, 10_000)
        val position = tracker.sample(4_000_000, 960, 0, 480, 10_000)
        assertEquals(480L, position.frames)
        assertEquals("estimate", position.source)
        assertFalse(position.reliable)
    }

    @Test fun timestampRegressionFallsBackToValidatedHead() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0, 0, 0)
        tracker.sample(10_000, 48_000, 480, 480, 10_000)
        val position = tracker.sample(20_000, 48_000, 960, 400, 9_000)
        assertEquals(960L, position.frames)
        assertEquals("playback_head", position.source)
    }

    @Test fun timestampBeyondCommittedFramesIsRejected() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 480, 0, 0, 0)
        val position = tracker.sample(10_000, 480, 480, 960, 20_000)
        assertEquals(480L, position.frames)
        assertEquals("playback_head", position.source)
    }

    @Test fun pauseIgnoresHardwareAdvanceUntilResumed() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0)
        tracker.sample(10_000, 48_000, 480)
        tracker.pause(10_000, 48_000)
        assertEquals(480L, tracker.sample(50_000, 48_000, 2_400).frames)
    }

    @Test fun resetStartsNewZeroCounterWithoutOldWrapOrEstimate() {
        val tracker = PlaybackPositionTracker(48_000)
        tracker.start(0)
        tracker.sample(0, 48_000, 0)
        tracker.sample(10_000, 48_000, 480)
        tracker.reset(1_000_000)
        tracker.start(1_000_000)
        assertEquals(0L, tracker.sample(1_000_000, 480, 0).frames)
        assertEquals(480L, tracker.sample(1_010_000, 480, 480).frames)
    }
}
