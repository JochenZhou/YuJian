package com.zqc.tvnotify.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackResetPolicyTest {
    @Test fun freshTrackSkipsNativeResetOnRepeatedClockFlushes() {
        val policy = AudioTrackResetPolicy()

        repeat(3) {
            assertFalse(policy.shouldPauseAndFlush(isPlaying = false))
        }
    }

    @Test fun zeroAndFailedWritesDoNotMakeAnEmptyTrackNeedReset() {
        val policy = AudioTrackResetPolicy()

        policy.onWrite(0)
        policy.onWrite(-3)
        policy.onWrite(-6)

        assertFalse(policy.shouldPauseAndFlush(isPlaying = false))
    }

    @Test fun positivePartialWriteNeedsResetUntilFlushCompletes() {
        val policy = AudioTrackResetPolicy()

        policy.onWrite(4)
        assertTrue(policy.shouldPauseAndFlush(isPlaying = false))
        policy.onWrite(0)
        policy.onWrite(-6)
        assertTrue(policy.shouldPauseAndFlush(isPlaying = false))
    }

    @Test fun nativePlayingTrackNeedsResetWithoutTrackedWrites() {
        val policy = AudioTrackResetPolicy()

        assertTrue(policy.shouldPauseAndFlush(isPlaying = true))
        assertFalse(policy.shouldPauseAndFlush(isPlaying = false))
    }

    @Test fun completedFlushClearsSubmittedDataButStillRespectsNativePlaying() {
        val policy = AudioTrackResetPolicy()
        policy.onWrite(1_920)
        assertTrue(policy.shouldPauseAndFlush(isPlaying = true))

        policy.onFlushed()

        assertTrue(policy.shouldPauseAndFlush(isPlaying = true))
        assertFalse(policy.shouldPauseAndFlush(isPlaying = false))
    }

    @Test fun repeatedIdlePausesAfterARealFlushSkipNativeReset() {
        val policy = AudioTrackResetPolicy()
        policy.onWrite(1_920)
        assertTrue(policy.shouldPauseAndFlush(isPlaying = false))
        policy.onFlushed()

        repeat(3) {
            assertFalse(policy.shouldPauseAndFlush(isPlaying = false))
            policy.onFlushed()
        }
    }

    @Test fun acceptedWriteAfterFlushMakesTheNextResetNecessary() {
        val policy = AudioTrackResetPolicy()
        policy.onWrite(1_920)
        policy.onFlushed()
        policy.onWrite(0)
        assertFalse(policy.shouldPauseAndFlush(isPlaying = false))

        policy.onWrite(4)

        assertTrue(policy.shouldPauseAndFlush(isPlaying = false))
    }
}
