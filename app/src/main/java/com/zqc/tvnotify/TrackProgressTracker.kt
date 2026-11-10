package com.zqc.tvnotify

import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.GroupPlaybackState
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.TrackMetadataMsg
import com.sendspin.protocol.orFallback
import com.sendspin.protocol.orNull

/** Session-owned track position, independent of Android audio output. Call under the runtime lock. */
internal class TrackProgressTracker(
    /** Null means this session's server clock is not ready. Receive time is never a substitute. */
    private val toLocalMicros: (serverTimestampUs: Long, nowUs: Long) -> Long?,
) {
    data class Snapshot(
        val progressMs: Long = 0L,
        val durationMs: Long = 0L,
        val progressUpdatedAtUs: Long = 0L,
        val playbackSpeed: Int = 0,
        val playing: Boolean = false,
    )

    private data class Anchor(
        val serverTimestampUs: Long,
        val progressMs: Long,
        val durationMs: Long,
        val speed: Int,
        var localTimestampUs: Long? = null,
        var clockLost: Boolean = false,
    )

    private var anchor: Anchor? = null
    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var latestMetadataTimestampUs: Long? = null
    private var metadataSpeed: Int? = null
    private var groupState: GroupPlaybackState? = null
    private var waitingForResumeAnchor = false
    private var anchorReceivedAfterPause = false
    private var frozenProgressMs = 0L
    private var lastProgressMs = 0L

    /** Returns false for an out-of-order metadata patch, including its identity fields. */
    fun update(metadata: TrackMetadataMsg?, nowUs: Long): Boolean {
        if (metadata == null) {
            clearMetadata()
            return true
        }
        val timestamp = metadata.timestamp
        if (timestamp > 0L) {
            val latest = latestMetadataTimestampUs
            if (latest != null && timestamp < latest) return false
            latestMetadataTimestampUs = timestamp
        }

        // Only an explicitly supplied, known title can confirm a track change. An artist/album
        // correction, duration patch, or initially missing title must not reset a seek position.
        val incomingTitle = metadata.title.orNull()?.takeIf { it.isNotBlank() }
        fun changedKnown(old: String?, incoming: JsonOptional<String>): Boolean =
            !old.isNullOrBlank() && incoming.orNull()?.takeIf { it.isNotBlank() }?.let { it != old } == true
        val differentTrack = !title.isNullOrBlank() && incomingTitle != null &&
            (incomingTitle != title || changedKnown(artist, metadata.artist) || changedKnown(album, metadata.album))
        if (differentTrack) clearAnchor()
        title = metadata.title.orFallback(title)
        artist = metadata.artist.orFallback(artist)
        album = metadata.album.orFallback(album)

        when (val progress = metadata.progress) {
            JsonOptional.Absent -> Unit // Keep the position, timestamp, speed and duration together.
            is JsonOptional.Present -> {
                val value = progress.value
                if (value == null) {
                    clearAnchor()
                } else {
                    val speed = value.playbackSpeed.coerceAtLeast(0)
                    anchor = Anchor(timestamp, value.trackProgress, value.trackDuration.coerceAtLeast(0L), speed)
                    metadataSpeed = speed
                    frozenProgressMs = ClockSync.calculateProgress(value.trackProgress, nowUs, 0, value.trackDuration, nowUs)
                    lastProgressMs = frozenProgressMs
                    // Group pause remains authoritative even when a progress patch still says 1x.
                    waitingForResumeAnchor = groupState == GroupPlaybackState.PAUSED || groupState == GroupPlaybackState.STOPPED
                    anchorReceivedAfterPause = waitingForResumeAnchor && speed > 0
                }
            }
        }
        return true
    }

    fun setGroupPlaybackState(state: GroupPlaybackState?, nowUs: Long) {
        if (state == groupState) return
        if (state == GroupPlaybackState.PAUSED || state == GroupPlaybackState.STOPPED) {
            frozenProgressMs = snapshot(nowUs).progressMs
            waitingForResumeAnchor = true
            anchorReceivedAfterPause = false
        }
        // Accept both wire orders: a fresh resume anchor may arrive before or after PLAYING.
        if (state == GroupPlaybackState.PLAYING && anchorReceivedAfterPause) waitingForResumeAnchor = false
        groupState = state
    }

    fun snapshot(nowUs: Long): Snapshot {
        val current = anchor
        var speed = 0
        var progress = frozenProgressMs
        if (current != null && !current.clockLost && current.serverTimestampUs > 0L) {
            val mappedTime = toLocalMicros(current.serverTimestampUs, nowUs)
            if (mappedTime == null && current.localTimestampUs != null) {
                // Never map an old, previously valid anchor through a new clock epoch.
                current.clockLost = true
                frozenProgressMs = lastProgressMs
                progress = frozenProgressMs
            } else if (mappedTime != null) {
                if (current.localTimestampUs == null) current.localTimestampUs = mappedTime
                if (!waitingForResumeAnchor) {
                    speed = current.speed
                    progress = ClockSync.calculateProgress(current.progressMs, current.localTimestampUs!!, speed, current.durationMs, nowUs)
                }
            }
        }
        lastProgressMs = progress
        val playing = when (groupState) {
            GroupPlaybackState.PLAYING -> true
            GroupPlaybackState.PAUSED, GroupPlaybackState.STOPPED -> false
            null -> metadataSpeed?.let { it > 0 } ?: false
        }
        return Snapshot(progress, current?.durationMs ?: 0L, nowUs, speed, playing)
    }

    /** A new client/socket session also discards identity, stale-timestamp filtering and group intent. */
    fun reset() {
        clearMetadata()
        groupState = null
    }

    private fun clearMetadata() {
        clearAnchor()
        title = null
        artist = null
        album = null
        latestMetadataTimestampUs = null
    }

    private fun clearAnchor() {
        anchor = null
        metadataSpeed = null
        waitingForResumeAnchor = false
        anchorReceivedAfterPause = false
        frozenProgressMs = 0L
        lastProgressMs = 0L
    }
}
