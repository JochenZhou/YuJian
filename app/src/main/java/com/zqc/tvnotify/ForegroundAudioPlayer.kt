package com.zqc.tvnotify

import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer

/** Host-local visibility gate; no controller or network command dependency. */
class ForegroundAudioPlayer(
    private val buffer: AudioBuffer,
    private val delegate: AudioPlayer,
) : AudioPlayer by delegate {
    fun setForeground(foreground: Boolean) {}
}
