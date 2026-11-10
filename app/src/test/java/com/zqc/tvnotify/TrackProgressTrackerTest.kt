package com.zqc.tvnotify

import com.sendspin.protocol.GroupPlaybackState
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.ProgressInfo
import com.sendspin.protocol.TrackMetadataMsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackProgressTrackerTest {
    private val serverEpochUs = 8_000_000_000L
    private var clockReady = true
    private var serverOffsetUs = serverEpochUs
    private val tracker = TrackProgressTracker { timestampUs, _ ->
        if (clockReady) timestampUs - serverOffsetUs else null
    }

    private fun metadata(
        localTimeUs: Long = 1_000_000L,
        progressMs: Long = 10_000L,
        durationMs: Long = 245_000L,
        speed: Int = 1000,
        title: String = "First track",
    ) = TrackMetadataMsg(
        timestamp = serverEpochUs + localTimeUs,
        title = JsonOptional.Present(title),
        artist = JsonOptional.Present("Artist"),
        album = JsonOptional.Present("Album"),
        progress = JsonOptional.Present(ProgressInfo(progressMs, durationMs, speed)),
    )

    @Test fun firstAnchorAdvancesUsingMappedServerTimeAndReportsSampleTime() {
        tracker.update(metadata(), 1_250_000L)
        val sample = tracker.snapshot(2_000_000L)
        assertEquals(11_000L, sample.progressMs)
        assertEquals(245_000L, sample.durationMs)
        assertEquals(2_000_000L, sample.progressUpdatedAtUs)
        assertEquals(1000, sample.playbackSpeed)
        assertTrue(sample.playing)
    }

    @Test fun doubleSpeedUsesProtocolThousandths() {
        tracker.update(metadata(speed = 2000), 1_000_000L)
        assertEquals(13_000L, tracker.snapshot(2_500_000L).progressMs)
        assertEquals(2000, tracker.snapshot(2_500_000L).playbackSpeed)
    }

    @Test fun progressClampsToKnownDurationAndZero() {
        tracker.update(metadata(progressMs = 244_500L), 1_000_000L)
        assertEquals(245_000L, tracker.snapshot(5_000_000L).progressMs)
        tracker.update(metadata(localTimeUs = 6_000_000L, progressMs = -500L, speed = 0), 6_000_000L)
        assertEquals(0L, tracker.snapshot(7_000_000L).progressMs)
    }

    @Test fun unknownDurationDoesNotClampAdvancingProgress() {
        tracker.update(metadata(progressMs = 245_000L, durationMs = 0L), 1_000_000L)
        val sample = tracker.snapshot(11_000_000L)
        assertEquals(255_000L, sample.progressMs)
        assertEquals(0L, sample.durationMs)
    }

    @Test fun zeroSpeedFreezesAtAuthoritativePosition() {
        tracker.update(metadata(), 1_000_000L)
        tracker.update(metadata(localTimeUs = 2_000_000L, progressMs = 10_800L, speed = 0), 2_000_000L)
        val sample = tracker.snapshot(20_000_000L)
        assertEquals(10_800L, sample.progressMs)
        assertEquals(0, sample.playbackSpeed)
        assertFalse(sample.playing)
    }

    @Test fun groupPauseFreezesAndPlayingIntentWaitsForFreshResumeAnchor() {
        tracker.update(metadata(), 1_000_000L)
        tracker.setGroupPlaybackState(GroupPlaybackState.PAUSED, 2_000_000L)
        assertEquals(11_000L, tracker.snapshot(10_000_000L).progressMs)
        assertFalse(tracker.snapshot(10_000_000L).playing)
        tracker.setGroupPlaybackState(GroupPlaybackState.PLAYING, 10_000_000L)
        val waiting = tracker.snapshot(20_000_000L)
        assertEquals(11_000L, waiting.progressMs)
        assertEquals(0, waiting.playbackSpeed)
        assertTrue(waiting.playing)
        tracker.update(metadata(localTimeUs = 20_000_000L, progressMs = 11_200L), 20_000_000L)
        assertEquals(12_200L, tracker.snapshot(21_000_000L).progressMs)
    }

    @Test fun freshResumeAnchorMayArriveBeforeGroupPlaying() {
        tracker.update(metadata(), 1_000_000L)
        tracker.setGroupPlaybackState(GroupPlaybackState.PAUSED, 2_000_000L)
        tracker.update(metadata(localTimeUs = 10_000_000L, progressMs = 11_100L), 10_000_000L)
        assertEquals(11_100L, tracker.snapshot(10_000_000L).progressMs)
        assertEquals(0, tracker.snapshot(10_000_000L).playbackSpeed)
        tracker.setGroupPlaybackState(GroupPlaybackState.PLAYING, 10_000_000L)
        val sample = tracker.snapshot(11_000_000L)
        assertEquals(12_100L, sample.progressMs)
        assertEquals(1000, sample.playbackSpeed)
        assertTrue(sample.playing)
    }

    @Test fun groupStoppedFreezesWithoutInventingNewTrack() {
        tracker.update(metadata(), 1_000_000L)
        tracker.setGroupPlaybackState(GroupPlaybackState.STOPPED, 2_000_000L)
        val sample = tracker.snapshot(20_000_000L)
        assertEquals(11_000L, sample.progressMs)
        assertEquals(245_000L, sample.durationMs)
        assertFalse(sample.playing)
    }

    @Test fun seeksReplacePositionInEitherDirectionWithoutResettingIdentity() {
        tracker.update(metadata(), 1_000_000L)
        tracker.update(metadata(localTimeUs = 2_000_000L, progressMs = 100_000L), 2_000_000L)
        assertEquals(101_000L, tracker.snapshot(3_000_000L).progressMs)
        tracker.update(metadata(localTimeUs = 4_000_000L, progressMs = 5_000L), 4_000_000L)
        assertEquals(6_000L, tracker.snapshot(5_000_000L).progressMs)
    }

    @Test fun partialMetadataKeepsOriginalProgressTimestampDurationAndSpeed() {
        tracker.update(metadata(speed = 2000), 1_000_000L)
        tracker.update(
            TrackMetadataMsg(timestamp = serverEpochUs + 4_000_000L, artworkUrl = JsonOptional.Present("new-art")),
            4_000_000L,
        )
        val sample = tracker.snapshot(5_000_000L)
        assertEquals(18_000L, sample.progressMs)
        assertEquals(245_000L, sample.durationMs)
        assertEquals(2000, sample.playbackSpeed)
    }

    @Test fun newTitleWithoutProgressCannotInheritOldCompletedDuration() {
        tracker.update(metadata(progressMs = 245_000L), 1_000_000L)
        tracker.update(
            TrackMetadataMsg(timestamp = serverEpochUs + 2_000_000L, title = JsonOptional.Present("Next track")),
            2_000_000L,
        )
        val sample = tracker.snapshot(20_000_000L)
        assertEquals(0L, sample.progressMs)
        assertEquals(0L, sample.durationMs)
        assertEquals(0, sample.playbackSpeed)
    }

    @Test fun identityChangesWithFreshProgressUseTheNewAnchor() {
        tracker.update(metadata(progressMs = 245_000L), 1_000_000L)
        tracker.update(metadata(localTimeUs = 2_000_000L, title = "Next track", progressMs = 0L, durationMs = 180_000L), 2_000_000L)
        val sample = tracker.snapshot(3_000_000L)
        assertEquals(1_000L, sample.progressMs)
        assertEquals(180_000L, sample.durationMs)
    }

    @Test fun artistAndAlbumPatchesWithoutTitleDoNotResetProgress() {
        tracker.update(metadata(), 1_000_000L)
        tracker.update(
            TrackMetadataMsg(timestamp = serverEpochUs + 2_000_000L, artist = JsonOptional.Present("Corrected artist"), album = JsonOptional.Present("Corrected album")),
            2_000_000L,
        )
        assertEquals(12_000L, tracker.snapshot(3_000_000L).progressMs)
    }

    @Test fun repeatedTitleWithChangedKnownAlbumIdentifiesDifferentRecording() {
        tracker.update(metadata(), 1_000_000L)
        tracker.update(
            TrackMetadataMsg(timestamp = serverEpochUs + 2_000_000L, title = JsonOptional.Present("First track"), album = JsonOptional.Present("Live album")),
            2_000_000L,
        )
        assertEquals(0L, tracker.snapshot(3_000_000L).progressMs)
        assertEquals(0L, tracker.snapshot(3_000_000L).durationMs)
    }

    @Test fun durationPatchWithoutTitleRemainsOnCurrentTrack() {
        tracker.update(metadata(), 1_000_000L)
        tracker.update(
            TrackMetadataMsg(timestamp = serverEpochUs + 2_000_000L, progress = JsonOptional.Present(ProgressInfo(11_000L, 300_000L, 1000))),
            2_000_000L,
        )
        val sample = tracker.snapshot(3_000_000L)
        assertEquals(12_000L, sample.progressMs)
        assertEquals(300_000L, sample.durationMs)
    }

    @Test fun titleHydrationAndExplicitTitleClearDoNotInventTrackChange() {
        tracker.update(metadata().copy(title = JsonOptional.Absent), 1_000_000L)
        tracker.update(TrackMetadataMsg(timestamp = serverEpochUs + 2_000_000L, title = JsonOptional.Present("First track")), 2_000_000L)
        assertEquals(12_000L, tracker.snapshot(3_000_000L).progressMs)
        tracker.update(TrackMetadataMsg(timestamp = serverEpochUs + 4_000_000L, title = JsonOptional.Present(null)), 4_000_000L)
        assertEquals(14_000L, tracker.snapshot(5_000_000L).progressMs)
    }

    @Test fun explicitProgressClearDiscardsAnchorAndDuration() {
        tracker.update(metadata(), 1_000_000L)
        tracker.update(TrackMetadataMsg(timestamp = serverEpochUs + 2_000_000L, progress = JsonOptional.Present(null)), 2_000_000L)
        val sample = tracker.snapshot(10_000_000L)
        assertEquals(0L, sample.progressMs)
        assertEquals(0L, sample.durationMs)
        assertEquals(0, sample.playbackSpeed)
    }

    @Test fun metadataRoleClearPreservesIndependentGroupPauseIntent() {
        tracker.setGroupPlaybackState(GroupPlaybackState.PAUSED, 1_000_000L)
        tracker.update(metadata(), 1_000_000L)
        tracker.update(null, 2_000_000L)
        tracker.update(metadata(localTimeUs = 3_000_000L), 3_000_000L)
        val sample = tracker.snapshot(4_000_000L)
        assertFalse(sample.playing)
        assertEquals(10_000L, sample.progressMs)
        assertEquals(0, sample.playbackSpeed)
    }

    @Test fun olderTimestampCannotReplaceSeekOrTrackIdentity() {
        tracker.update(metadata(localTimeUs = 5_000_000L, progressMs = 50_000L), 5_000_000L)
        assertFalse(tracker.update(metadata(localTimeUs = 4_000_000L, title = "Stale title", progressMs = 245_000L), 6_000_000L))
        assertEquals(52_000L, tracker.snapshot(7_000_000L).progressMs)
    }

    @Test fun reconnectResetAcceptsLowerTimestampFromNewServerEpoch() {
        tracker.update(metadata(), 1_000_000L)
        tracker.setGroupPlaybackState(GroupPlaybackState.PAUSED, 2_000_000L)
        tracker.reset()
        serverOffsetUs = 100_000L
        tracker.update(TrackMetadataMsg(timestamp = 10_100_000L, progress = JsonOptional.Present(ProgressInfo(2_000L, 100_000L, 1000))), 10_000_000L)
        val sample = tracker.snapshot(11_000_000L)
        assertEquals(3_000L, sample.progressMs)
        assertTrue(sample.playing)
    }

    @Test fun pendingAnchorWaitsForClockAndThenUsesOriginalTimestamp() {
        clockReady = false
        tracker.update(metadata(), 1_000_000L)
        val waiting = tracker.snapshot(10_000_000L)
        assertEquals(10_000L, waiting.progressMs)
        assertEquals(0, waiting.playbackSpeed)
        assertTrue(waiting.playing)
        tracker.update(TrackMetadataMsg(timestamp = serverEpochUs + 10_000_000L, year = JsonOptional.Present(2026)), 10_000_000L)
        clockReady = true
        val ready = tracker.snapshot(11_000_000L)
        assertEquals(20_000L, ready.progressMs)
        assertEquals(1000, ready.playbackSpeed)
    }

    @Test fun clockLossDoesNotRemapOldAnchorIntoNewEpoch() {
        tracker.update(metadata(), 1_000_000L)
        assertEquals(11_000L, tracker.snapshot(2_000_000L).progressMs)
        clockReady = false
        assertEquals(11_000L, tracker.snapshot(3_000_000L).progressMs)
        serverOffsetUs = 100_000L
        clockReady = true
        val sample = tracker.snapshot(10_000_000L)
        assertEquals(11_000L, sample.progressMs)
        assertEquals(0, sample.playbackSpeed)
    }

    @Test fun missingTimestampNeverUsesReceiveTimeAsMovingAnchor() {
        tracker.update(TrackMetadataMsg(progress = JsonOptional.Present(ProgressInfo(5_000L, 0L, 1000))), 1_000_000L)
        val sample = tracker.snapshot(10_000_000L)
        assertEquals(5_000L, sample.progressMs)
        assertEquals(0, sample.playbackSpeed)
    }
}
