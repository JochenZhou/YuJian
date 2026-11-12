package com.zqc.tvnotify

import com.sendspin.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ForegroundAudioPlayerTest {
    private class Output : AudioPlayer {
        var nativeOpen = false
        var starts = 0
        var stops = 0
        var configured: StreamFormat? = null
        var gain = 1f
        override var isPlaying = false
        override val droppedDecodeFrames = 0L
        override fun configure(format: StreamFormat) { configured = format; nativeOpen = true }
        override fun start() { starts++; isPlaying = nativeOpen }
        override fun prepareTransition() { isPlaying = false }
        override fun flush() { isPlaying = false }
        override fun stop() { stops++; nativeOpen = false; isPlaying = false }
        override fun transition(format: StreamFormat) { configure(format); start() }
        override fun setVolume(gain: Float) { this.gain = gain }
    }
    private val pcm = StreamFormat("pcm", 48_000, 2, 16)
    private class Fixture {
        val buffer = AudioBuffer(ClockSync())
        val output = Output()
        val player = ForegroundAudioPlayer(buffer, output)
        fun offer(time: Long = 1L) = buffer.offer(AudioChunk(time, byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun `service-only startup never allocates native output or retains audio`() {
        val f = Fixture()
        f.player.configure(pcm)
        f.player.start()
        f.offer()
        assertFalse(f.output.nativeOpen)
        assertFalse(f.player.isPlaying)
        assertEquals(0, f.buffer.size)
        assertEquals("background_suspended", f.player.outputSync.state)
    }

    @Test fun `leaving app releases output without altering volume and drops arriving audio`() {
        val f = Fixture()
        f.player.setForeground(true)
        f.player.configure(pcm)
        f.player.setVolume(0.37f)
        f.player.start()
        assertTrue(f.player.isPlaying)
        f.offer()
        f.player.setForeground(false)
        f.offer(2L)
        assertFalse(f.output.nativeOpen)
        assertFalse(f.player.isPlaying)
        assertEquals(0, f.buffer.size)
        assertEquals(0.37f, f.output.gain)
    }

    @Test fun `foreground return recreates current format and starts with fresh queue`() {
        val f = Fixture()
        f.player.configure(pcm)
        f.player.start()
        f.offer()
        f.player.setForeground(true)
        assertTrue(f.output.nativeOpen)
        assertTrue(f.player.isPlaying)
        assertEquals(pcm, f.output.configured)
        assertEquals(0, f.buffer.size)
        f.offer(3L)
        assertEquals(1, f.buffer.size)
    }

    @Test fun `background track changes and commands cannot reopen native output`() {
        val f = Fixture()
        val next = StreamFormat("pcm", 44_100, 1, 16)
        f.player.setForeground(true)
        f.player.configure(pcm)
        f.player.start()
        f.player.setForeground(false)
        f.player.prepareTransition()
        f.player.flush()
        f.player.configure(next)
        f.player.setVolume(0f)
        f.player.start()
        assertFalse(f.output.nativeOpen)
        assertEquals(1, f.output.starts)
        f.player.setForeground(true)
        assertEquals(next, f.output.configured)
        assertEquals(0f, f.output.gain)
        assertTrue(f.player.isPlaying)
    }

    @Test fun `stream end while hidden cannot resurrect playback on return`() {
        val f = Fixture()
        f.player.configure(pcm)
        f.player.start()
        f.player.stop()
        f.player.setForeground(true)
        assertFalse(f.output.nativeOpen)
        assertEquals(0, f.output.starts)
    }

    @Test fun `a stalled configure cannot reopen output after background transition completes`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val output = Output()
        val slow = object : AudioPlayer by output {
            override fun configure(format: StreamFormat) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                output.configure(format)
            }
        }
        val buffer = AudioBuffer(ClockSync())
        val player = ForegroundAudioPlayer(buffer, slow)
        player.setForeground(true)
        val configure = Thread { player.configure(pcm); player.start() }.apply { start() }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val background = Thread { player.setForeground(false) }.apply { start() }
        release.countDown()
        configure.join(5_000)
        background.join(5_000)
        assertFalse(configure.isAlive)
        assertFalse(background.isAlive)
        assertFalse(output.nativeOpen)
        assertFalse(player.isPlaying)
    }

    @Test fun `in-flight format transition stays gated across background and foreground`() {
        val f = Fixture()
        f.player.setForeground(true)
        f.player.configure(pcm)
        f.player.start()
        f.player.prepareTransition()
        f.player.setForeground(false)
        f.player.configure(pcm)
        f.player.setForeground(true)
        assertFalse(f.player.isPlaying)
        f.player.start()
        assertTrue(f.player.isPlaying)
        val starts = f.output.starts
        f.player.setForeground(true)
        assertEquals(starts, f.output.starts)
    }
}
