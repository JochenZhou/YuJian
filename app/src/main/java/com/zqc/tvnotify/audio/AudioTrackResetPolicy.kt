package com.zqc.tvnotify.audio

/** Decides whether a native track has playback or submitted data to reset. */
class AudioTrackResetPolicy {
    fun onWrite(writtenBytes: Int) = Unit

    fun shouldPauseAndFlush(isPlaying: Boolean): Boolean = true

    fun onFlushed() = Unit
}
