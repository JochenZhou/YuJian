package com.zqc.tvnotify.audio

/** Decides whether a native track has playback or submitted data to reset. */
class AudioTrackResetPolicy {
    private var hasBufferedData = false

    fun onWrite(writtenBytes: Int) {
        if (writtenBytes > 0) hasBufferedData = true
    }

    fun shouldPauseAndFlush(isPlaying: Boolean): Boolean = hasBufferedData || isPlaying

    fun onFlushed() {
        hasBufferedData = false
    }
}
