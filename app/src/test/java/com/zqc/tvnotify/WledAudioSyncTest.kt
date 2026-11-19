package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class WledAudioSyncTest {

    private class FakeMulticastLease : MulticastLease {
        val acquires = AtomicInteger(0)
        val releases = AtomicInteger(0)
        val held = AtomicBoolean(false)

        override fun acquire() {
            acquires.incrementAndGet()
            held.set(true)
        }

        override fun release() {
            if (held.compareAndSet(true, false)) {
                releases.incrementAndGet()
            }
        }
    }

    @Test
    fun `audio sync prefs default off with port 11988 and reject out of range ports`() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(Prefs.isWledSyncEnabled(context))
        assertEquals(11988, Prefs.getWledSyncPort(context))

        assertTrue(Prefs.setWledSyncPort(context, 12345))
        assertEquals(12345, Prefs.getWledSyncPort(context))

        assertFalse(Prefs.setWledSyncPort(context, 0))
        assertFalse(Prefs.setWledSyncPort(context, -1))
        assertFalse(Prefs.setWledSyncPort(context, 65536))
        assertEquals(12345, Prefs.getWledSyncPort(context))

        assertNull(Prefs.parseWledSyncPort("abc"))
        assertNull(Prefs.parseWledSyncPort("65536"))
        assertEquals(11988, Prefs.parseWledSyncPort(" 11988 "))

        Prefs.setWledSyncEnabled(context, true)
        assertTrue(Prefs.isWledSyncEnabled(context))
        Prefs.setWledSyncEnabled(context, false)
        assertFalse(Prefs.isWledSyncEnabled(context))
    }

    @Test
    fun `packet encoder writes exact 44 byte little endian WLED v2 payload`() {
        val bands = ByteArray(16) { i -> (i * 15).toByte() }
        val metrics = WledAudioMetrics(
            sampleRaw = 123.5f,
            sampleSmth = 98.25f,
            samplePeak = 1,
            frameCounter = 250,
            fftResult = bands,
            zeroCrossingCount = 412,
            fftMagnitude = 512.75f,
            majorPeakHz = 440.5f,
            pressure0 = 7,
            pressure1 = 19
        )
        val encoded = WledAudioSyncPacket.encodeV2(metrics)
        assertEquals(44, encoded.size)
        assertEquals("00002", String(encoded, 0, 5, Charsets.US_ASCII))
        assertEquals(0.toByte(), encoded[5])
        assertEquals(7, encoded[6].toInt() and 0xFF)
        assertEquals(19, encoded[7].toInt() and 0xFF)

        val buf = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(123.5f, buf.getFloat(8), 0.001f)
        assertEquals(98.25f, buf.getFloat(12), 0.001f)
        assertEquals(1, encoded[16].toInt() and 0xFF)
        assertEquals(250, encoded[17].toInt() and 0xFF)
        for (i in 0 until 16) {
            assertEquals(i * 15, encoded[18 + i].toInt() and 0xFF)
        }
        assertEquals(412, buf.getShort(34).toInt() and 0xFFFF)
        assertEquals(512.75f, buf.getFloat(36), 0.001f)
        assertEquals(440.5f, buf.getFloat(40), 0.001f)
    }

    @Test
    fun `analyzer outputs zero energy on silence and recovers cleanly on reset`() {
        val analyzer = WledAudioAnalyzer()
        val silence = ByteArray(1024 * 4)
        val m1 = analyzer.analyze(silence, 0, silence.size, 48_000, 2)
        assertEquals(0f, m1.sampleRaw, 0.001f)
        assertEquals(0f, m1.sampleSmth, 0.001f)
        assertEquals(0, m1.samplePeak)
        assertEquals(0f, m1.fftMagnitude, 0.001f)
        assertEquals(0f, m1.majorPeakHz, 0.001f)
        assertTrue(m1.fftResult.all { it.toInt() == 0 })

        val sine = generateSinePcm16(freqHz = 440.0, sampleRate = 48_000, channels = 2, frames = 1024, amplitude = 0.6)
        val loud = analyzer.analyze(sine, 0, sine.size, 48_000, 2)
        assertTrue(loud.sampleRaw > 50f)
        assertTrue(loud.sampleSmth > 10f)

        analyzer.reset()
        val afterReset = analyzer.analyze(silence, 0, silence.size, 48_000, 2)
        assertEquals(0f, afterReset.sampleRaw, 0.001f)
        assertEquals(0f, afterReset.sampleSmth, 0.001f)
        assertEquals(0, afterReset.frameCounter)
    }

    @Test
    fun `analyzer distinguishes low vs high frequency sine waves across GEQ bands`() {
        val lowAnalyzer = WledAudioAnalyzer()
        val lowSine = generateSinePcm16(freqHz = 100.0, sampleRate = 48_000, channels = 2, frames = 1024, amplitude = 0.5)
        val lowMetrics = lowAnalyzer.analyze(lowSine, 0, lowSine.size, 48_000, 2)

        val highAnalyzer = WledAudioAnalyzer()
        val highSine = generateSinePcm16(freqHz = 4000.0, sampleRate = 48_000, channels = 2, frames = 1024, amplitude = 0.5)
        val highMetrics = highAnalyzer.analyze(highSine, 0, highSine.size, 48_000, 2)

        val lowBandsPeak = (0..3).maxOf { lowMetrics.fftResult[it].toInt() and 0xFF }
        val lowBandsUpper = (10..15).maxOf { lowMetrics.fftResult[it].toInt() and 0xFF }
        assertTrue("100 Hz should excite low bands ($lowBandsPeak vs $lowBandsUpper)", lowBandsPeak > lowBandsUpper + 40)
        assertTrue("100 Hz peak freq should stay low (${lowMetrics.majorPeakHz})", lowMetrics.majorPeakHz in 70f..140f)

        val highBandsPeak = (11..15).maxOf { highMetrics.fftResult[it].toInt() and 0xFF }
        val highBandsLower = (0..4).maxOf { highMetrics.fftResult[it].toInt() and 0xFF }
        assertTrue("4000 Hz should excite high bands ($highBandsPeak vs $highBandsLower)", highBandsPeak > highBandsLower + 40)
        assertTrue("4000 Hz peak freq should be near 4 kHz (${highMetrics.majorPeakHz})", highMetrics.majorPeakHz in 3800f..4200f)
    }

    @Test
    fun `analyzer handles mono stereo Int16 min clipping empty and trailing partial bytes`() {
        val monoAnalyzer = WledAudioAnalyzer()
        val stereoAnalyzer = WledAudioAnalyzer()
        val mono = generateSinePcm16(freqHz = 250.0, sampleRate = 48_000, channels = 1, frames = 1024, amplitude = 0.5)
        val stereo = generateSinePcm16(freqHz = 250.0, sampleRate = 48_000, channels = 2, frames = 1024, amplitude = 0.5)

        val mMono = monoAnalyzer.analyze(mono, 0, mono.size, 48_000, 1)
        val mStereo = stereoAnalyzer.analyze(stereo, 0, stereo.size, 48_000, 2)
        assertEquals(mMono.sampleRaw, mStereo.sampleRaw, 1.5f)
        assertEquals(mMono.majorPeakHz, mStereo.majorPeakHz, 5f)

        // Short.MIN_VALUE clipping & trailing partial frame bytes (length = 5 bytes on stereo 4-byte frames)
        val extreme = byteArrayOf(0x00, 0x80.toByte(), 0x00, 0x80.toByte(), 0x7F)
        val extremeMetrics = stereoAnalyzer.analyze(extreme, 0, extreme.size, 48_000, 2)
        assertTrue(extremeMetrics.sampleRaw in 0f..255f)
        assertTrue(extremeMetrics.sampleRaw > 200f)

        val emptyMetrics = stereoAnalyzer.analyze(ByteArray(1), 0, 1, 48_000, 2)
        assertEquals(0f, emptyMetrics.sampleRaw, 0.001f)
    }

    @Test
    fun `disabled sync sends zero packets and never acquires multicast lock`() {
        val lease = FakeMulticastLease()
        val sent = AtomicInteger(0)
        val sinkCreated = AtomicInteger(0)
        val sync = WledAudioSync(
            initialEnabled = false,
            initialPort = 11988,
            sinkFactory = {
                sinkCreated.incrementAndGet()
                object : DatagramSink {
                    override fun send(packet: ByteArray, length: Int, host: String, port: Int) {
                        sent.incrementAndGet()
                    }
                    override fun close() = Unit
                }
            },
            multicastLease = lease
        )
        sync.setPlaybackActive(true)
        val pcm = generateSinePcm16(freqHz = 440.0, sampleRate = 48_000, channels = 2, frames = 512, amplitude = 0.5)
        sync.onPcmWritten(pcm, 0, pcm.size, 48_000, 2, 1000L)
        Thread.sleep(40)
        assertEquals(0, sinkCreated.get())
        assertEquals(0, sent.get())
        assertEquals(0, lease.acquires.get())
        assertFalse(lease.held.get())
        sync.close()
    }

    @Test
    fun `multicast lock and socket close cleanly across enable disable and pause transitions`() {
        val lease = FakeMulticastLease()
        val sentLatch = CountDownLatch(1)
        val closedCount = AtomicInteger(0)
        val packets = CopyOnWriteArrayList<Pair<String, Int>>()

        val sync = WledAudioSync(
            initialEnabled = true,
            initialPort = 11990,
            sinkFactory = {
                object : DatagramSink {
                    override fun send(packet: ByteArray, length: Int, host: String, port: Int) {
                        packets.add(host to port)
                        sentLatch.countDown()
                    }
                    override fun close() {
                        closedCount.incrementAndGet()
                    }
                }
            },
            multicastLease = lease,
            minIntervalNs = 0L
        )
        assertEquals(0, lease.acquires.get())

        sync.setPlaybackActive(true)
        assertTrue(lease.held.get())
        val pcm = generateSinePcm16(freqHz = 300.0, sampleRate = 48_000, channels = 2, frames = 512, amplitude = 0.5)
        sync.onPcmWritten(pcm, 0, pcm.size, 48_000, 2, 10_000L)
        assertTrue("Expected UDP packet delivery", sentLatch.await(2, TimeUnit.SECONDS))
        assertEquals("239.0.0.1" to 11990, packets.first())

        // Pause playback -> socket closed and lock released immediately
        sync.setPlaybackActive(false)
        assertFalse(lease.held.get())
        assertEquals(1, closedCount.get())
        assertEquals(lease.acquires.get(), lease.releases.get())

        // Re-enable -> new lock acquired -> close tears it down
        sync.setPlaybackActive(true)
        assertTrue(lease.held.get())
        sync.close()
        assertFalse(lease.held.get())
        assertEquals(lease.acquires.get(), lease.releases.get())
        assertEquals(2, closedCount.get())
    }

    @Test
    fun `drops stale frames under backpressure and survives transient socket failures`() {
        val lease = FakeMulticastLease()
        val clock = AtomicLong(1_000_000L)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        val sendCount = AtomicInteger(0)
        val deliveredTags = CopyOnWriteArrayList<Float>()

        val sync = WledAudioSync(
            initialEnabled = true,
            initialPort = 11988,
            sinkFactory = {
                object : DatagramSink {
                    override fun send(packet: ByteArray, length: Int, host: String, port: Int) {
                        val n = sendCount.incrementAndGet()
                        val raw = ByteBuffer.wrap(packet, 8, 4).order(ByteOrder.LITTLE_ENDIAN).float
                        deliveredTags.add(raw)
                        if (n == 1) {
                            firstStarted.countDown()
                            releaseFirst.await(2, TimeUnit.SECONDS)
                            throw IOException("Simulated temporary network failure")
                        } else {
                            secondSent.countDown()
                        }
                    }
                    override fun close() = Unit
                }
            },
            multicastLease = lease,
            nowNs = { clock.get() },
            minIntervalNs = 0L,
            maxFrameAgeNs = 50_000_000L
        )
        sync.setPlaybackActive(true)

        val pcmLoud = generateSinePcm16(freqHz = 220.0, sampleRate = 48_000, channels = 2, frames = 512, amplitude = 0.7)
        val pcmQuiet = generateSinePcm16(freqHz = 220.0, sampleRate = 48_000, channels = 2, frames = 512, amplitude = 0.05)

        // Frame 1 enters sink.send and throws after unblock; worker remains healthy.
        sync.onPcmWritten(pcmLoud, 0, pcmLoud.size, 48_000, 2, 1000L)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))

        // While worker is blocked on Frame 1, enqueue stale frame at clock=1ms, then advance clock +200ms
        sync.onPcmWritten(pcmQuiet, 0, pcmQuiet.size, 48_000, 2, 2000L)
        clock.addAndGet(200_000_000L)
        releaseFirst.countDown()

        // Give worker a moment to observe & drop the stale quiet frame, then send fresh frame at current clock.
        Thread.sleep(30)
        val freshPcm = generateSinePcm16(freqHz = 880.0, sampleRate = 48_000, channels = 2, frames = 512, amplitude = 0.4)
        sync.onPcmWritten(freshPcm, 0, freshPcm.size, 48_000, 2, 3000L)
        assertTrue("Worker should recover after IOException and send fresh frame", secondSent.await(2, TimeUnit.SECONDS))

        // Only Frame 1 and fresh Frame 3 were sent (stale Frame 2 dropped)
        assertEquals(2, sendCount.get())
        assertTrue("Expected fresh 0.4-amplitude packet (>140), got ${deliveredTags[1]}", deliveredTags[1] > 140f)
        sync.close()
    }

    @Test
    fun `AGC lifts typical quiet digital music across full loudness range and all GEQ octaves`() {
        val analyzer = WledAudioAnalyzer()
        val sampleRate = 48_000
        val channels = 2
        val frames = 1024
        val quietMix = ByteArray(frames * channels * 2)
        var idx = 0
        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            // Quiet music (~0.045 total amplitude across bass 110Hz, mid 660Hz, treble 3500Hz).
            val v = (sin(2.0 * PI * 110.0 * t) * 0.025 +
                sin(2.0 * PI * 660.0 * t) * 0.018 +
                sin(2.0 * PI * 3500.0 * t) * 0.015)
            val s = (v * 32767.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            for (c in 0 until channels) {
                quietMix[idx++] = (s and 0xFF).toByte()
                quietMix[idx++] = ((s ushr 8) and 0xFF).toByte()
            }
        }
        lateinit var last: WledAudioMetrics
        repeat(6) {
            last = analyzer.analyze(quietMix, 0, quietMix.size, sampleRate, channels)
        }
        assertTrue("Quiet music should drive full-strip sampleRaw (>= 140), got ${last.sampleRaw}", last.sampleRaw >= 140f)
        assertTrue("Quiet music should drive sampleSmth (>= 120), got ${last.sampleSmth}", last.sampleSmth >= 120f)
        val bassBand = last.fftResult[1].toInt() and 0xFF
        val midBand = last.fftResult[6].toInt() and 0xFF
        val trebleBand = last.fftResult[12].toInt() and 0xFF
        val gapBand = last.fftResult[9].toInt() and 0xFF
        assertTrue("Bass band should be strong ($bassBand)", bassBand >= 120)
        assertTrue("Mid band should be strong ($midBand)", midBand >= 120)
        assertTrue("Treble band must not collapse ($trebleBand)", trebleBand >= 120)
        assertTrue("Unexcited gap band should stay low ($gapBand)", gapBand < 60)
    }

    @Test
    fun `analyzer applies smooth decay release and throttles rapid beat peaks`() {
        val analyzer = WledAudioAnalyzer()
        val sampleRate = 48_000
        val channels = 2
        val loud = generateSinePcm16(freqHz = 220.0, sampleRate = sampleRate, channels = channels, frames = 1024, amplitude = 0.65)
        val silence = ByteArray(1024 * channels * 2)

        lateinit var peakMetrics: WledAudioMetrics
        var peaksTriggered = 0
        repeat(10) {
            peakMetrics = analyzer.analyze(loud, 0, loud.size, sampleRate, channels)
            if (peakMetrics.samplePeak != 0) peaksTriggered++
        }
        assertTrue("Steady tone should trigger at most 2 beat peaks in 10 frames ($peaksTriggered)", peaksTriggered <= 2)
        val peakSmth = peakMetrics.sampleSmth
        val peakBand = (0..5).maxOf { peakMetrics.fftResult[it].toInt() and 0xFF }
        assertTrue(peakSmth > 150f)
        assertTrue(peakBand > 150)

        // First silent frame after tone decays gently instead of snapping to zero
        val decay1 = analyzer.analyze(silence, 0, silence.size, sampleRate, channels)
        assertTrue("Smoothed level should fade gradually (${decay1.sampleSmth} vs $peakSmth)", decay1.sampleSmth in (peakSmth * 0.7f)..peakSmth)
        val decayBand1 = (0..5).maxOf { decay1.fftResult[it].toInt() and 0xFF }
        assertTrue("FFT bands should fade gradually ($decayBand1 vs $peakBand)", decayBand1 > peakBand / 2)
    }

    @Test
    fun `gain percent scales overall level and deepens valleys cleanly`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(Prefs.DEFAULT_WLED_SYNC_GAIN, Prefs.getWledSyncGain(context))
        assertTrue(Prefs.setWledSyncGain(context, 45))
        assertEquals(45, Prefs.getWledSyncGain(context))
        assertFalse(Prefs.setWledSyncGain(context, 5))
        assertFalse(Prefs.setWledSyncGain(context, 250))
        assertEquals(45, Prefs.getWledSyncGain(context))

        val pcm = generateSinePcm16(freqHz = 220.0, sampleRate = 48_000, channels = 2, frames = 1024, amplitude = 0.4)
        val fullAnalyzer = WledAudioAnalyzer().apply { setGainPercent(100) }
        val lowAnalyzer = WledAudioAnalyzer().apply { setGainPercent(40) }
        lateinit var fullMetrics: WledAudioMetrics
        lateinit var lowMetrics: WledAudioMetrics
        repeat(4) {
            fullMetrics = fullAnalyzer.analyze(pcm, 0, pcm.size, 48_000, 2)
            lowMetrics = lowAnalyzer.analyze(pcm, 0, pcm.size, 48_000, 2)
        }
        assertTrue(
            "40% gain should substantially lower sampleRaw (${lowMetrics.sampleRaw} vs ${fullMetrics.sampleRaw})",
            lowMetrics.sampleRaw < fullMetrics.sampleRaw * 0.55f
        )
        val fullPeakBand = fullMetrics.fftResult.maxOf { it.toInt() and 0xFF }
        val lowPeakBand = lowMetrics.fftResult.maxOf { it.toInt() and 0xFF }
        assertTrue(
            "40% gain should substantially lower FFT bands ($lowPeakBand vs $fullPeakBand)",
            lowPeakBand < fullPeakBand * 0.55
        )
    }

    @Test
    fun `extractUdpHost strips scheme trailing slash and port cleanly`() {
        assertEquals("192.168.101.206", WledAudioSync.extractUdpHost("192.168.101.206"))
        assertEquals("192.168.101.206", WledAudioSync.extractUdpHost("http://192.168.101.206:80/"))
        assertEquals("wled-living.local", WledAudioSync.extractUdpHost("https://wled-living.local:443/"))
        assertEquals("::1", WledAudioSync.extractUdpHost("http://[::1]:8080"))
        assertNull(WledAudioSync.extractUdpHost("http://192.168.101.206/json/state"))
        assertNull(WledAudioSync.extractUdpHost("   "))
        assertNull(WledAudioSync.extractUdpHost(null))
    }

    @Test
    fun `audio sync sends V2 packets to both multicast and direct unicast host and survives multicast failure`() {
        val destinations = CopyOnWriteArrayList<String>()
        val bothReceived = CountDownLatch(2)
        val unicastAfterFail = CountDownLatch(1)
        val failMulticast = AtomicBoolean(false)
        val clockNs = AtomicLong(1_000_000_000L)

        val sink = object : DatagramSink {
            override fun send(packet: ByteArray, length: Int, host: String, port: Int) {
                if (failMulticast.get() && host == WledAudioSyncPacket.MULTICAST_GROUP) {
                    throw IOException("Simulated router multicast drop")
                }
                assertEquals(WledAudioSyncPacket.PACKET_SIZE, length)
                assertEquals(11988, port)
                destinations.add("$host:$port")
                bothReceived.countDown()
                if (failMulticast.get() && host == "192.168.101.206") {
                    unicastAfterFail.countDown()
                }
            }

            override fun close() = Unit
        }

        val sync = WledAudioSync(
            initialEnabled = true,
            initialPort = 11988,
            initialUnicastHost = "http://192.168.101.206:80",
            sinkFactory = { sink },
            multicastLease = FakeMulticastLease(),
            nowNs = { clockNs.get() },
            minIntervalNs = 1_000_000L,
            maxFrameAgeNs = 500_000_000L
        )
        try {
            sync.setPlaybackActive(true)
            val pcm = generateSinePcm16(freqHz = 220.0, sampleRate = 48_000, channels = 2, frames = 1024, amplitude = 0.5)
            sync.onPcmWritten(pcm, 0, pcm.size, 48_000, 2, 0L)
            assertTrue("Should deliver to both multicast and unicast", bothReceived.await(3, TimeUnit.SECONDS))
            assertTrue(destinations.contains("239.0.0.1:11988"))
            assertTrue(destinations.contains("192.168.101.206:11988"))

            failMulticast.set(true)
            clockNs.addAndGet(50_000_000L)
            sync.onPcmWritten(pcm, 0, pcm.size, 48_000, 2, 20_000L)
            assertTrue("Unicast must still deliver when multicast throws", unicastAfterFail.await(3, TimeUnit.SECONDS))
        } finally {
            sync.close()
        }
    }

    private fun generateSinePcm16(
        freqHz: Double,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        amplitude: Double
    ): ByteArray {
        val out = ByteArray(frames * channels * 2)
        var idx = 0
        for (i in 0 until frames) {
            val sample = (sin(2.0 * PI * freqHz * i / sampleRate) * amplitude * 32767.0)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            for (c in 0 until channels) {
                out[idx++] = (sample and 0xFF).toByte()
                out[idx++] = ((sample ushr 8) and 0xFF).toByte()
            }
        }
        return out
    }
}
