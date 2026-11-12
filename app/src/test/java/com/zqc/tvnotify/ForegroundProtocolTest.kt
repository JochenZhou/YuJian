package com.zqc.tvnotify

import com.sendspin.protocol.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ForegroundProtocolTest {
    private class Output : AudioPlayer {
        override var isPlaying = false
        override val droppedDecodeFrames = 0L
        override fun configure(format: StreamFormat) {}
        override fun start() { isPlaying = true }
        override fun prepareTransition() { isPlaying = false }
        override fun flush() {}
        override fun stop() { isPlaying = false }
        override fun transition(format: StreamFormat) { configure(format); start() }
        override fun setVolume(gain: Float) {}
    }

    @Test fun `host visibility preserves server session group intent clock volume and mute`() {
        val http = OkHttpClient()
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        lateinit var gate: ForegroundAudioPlayer
        val client = SendSpinClient(
            okHttpClient = http,
            moshi = moshi,
            preferences = ClientPreferences(supportedOptionalRoles = setOf(OptionalRole.PLAYER)),
            clientId = "visibility-test", clientName = "test", manufacturer = "test", productName = "test", softwareVersion = "test",
            audioPlayerFactory = { buffer, _ -> ForegroundAudioPlayer(buffer, Output()).also { gate = it } },
        )
        try {
            client.setLocalVolume(37)
            client.setLocalMuted(true)
            val now = ClockSync.localMicros()
            client.clockSync.processMeasurement(now, now, now, now)
            gate.configure(StreamFormat("pcm", 48_000, 2, 16))
            gate.start()
            val state = client.state.value
            val group = client.groupPlaybackState.value
            gate.setForeground(true)
            gate.setForeground(false)
            assertFalse(gate.isPlaying)
            assertEquals(state, client.state.value)
            assertEquals(group, client.groupPlaybackState.value)
            assertEquals(37, client.currentPlayerVolume)
            assertTrue(client.currentPlayerMuted)
            assertTrue(client.clockSync.isReady)
        } finally {
            client.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
