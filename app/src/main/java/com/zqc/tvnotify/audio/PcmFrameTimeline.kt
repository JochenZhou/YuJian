package com.zqc.tvnotify.audio

import java.util.ArrayDeque

/** Maps committed output frames to source time. Call [discardBefore] as playback advances. */
class PcmFrameTimeline(private val sampleRate: Int) {
    init {
        require(sampleRate > 0)
    }

    private data class Segment(
        val start: Long,
        val count: Int,
        val serverUs: Long,
        val sourceFrames: Int
    ) {
        val end: Long get() = start + count
    }

    private val segments = ArrayDeque<Segment>()
    private var discardedBefore = 0L
    private var lastEnd = 0L
    private var endTimeUs: Long? = null

    fun reset() {
        segments.clear()
        discardedBefore = 0
        lastEnd = 0
        endTimeUs = null
    }

    fun append(startFrame: Long, frameCount: Int, serverStartUs: Long, sourceFrames: Int = frameCount) {
        require(startFrame >= 0 && frameCount >= 0 && sourceFrames >= 0)
        require(startFrame <= Long.MAX_VALUE - frameCount)
        require(startFrame >= lastEnd) { "Output segments must not overlap" }
        if (frameCount == 0) return
        val segment = Segment(startFrame, frameCount, serverStartUs, sourceFrames)
        segments.addLast(segment)
        lastEnd = segment.end
        endTimeUs = timeIn(segment, segment.end)
    }

    fun serverTimeAt(frame: Long): Long? {
        if (frame < discardedBefore) return null
        // Half-open intervals make a shared boundary select the new segment's timestamp.
        for (segment in segments) {
            if (frame < segment.start) return null
            if (frame < segment.end) return timeIn(segment, frame)
        }
        return if (frame == lastEnd) endTimeUs else null
    }

    fun discardBefore(frame: Long) {
        require(frame >= 0)
        discardedBefore = maxOf(discardedBefore, frame)
        while (segments.isNotEmpty() && segments.peekFirst().end <= discardedBefore) {
            segments.removeFirst()
        }
    }

    private fun timeIn(segment: Segment, frame: Long): Long {
        val durationUs = (frame - segment.start).toDouble() * segment.sourceFrames * 1_000_000.0 /
            (segment.count.toDouble() * sampleRate)
        return segment.serverUs + durationUs.toLong()
    }
}
