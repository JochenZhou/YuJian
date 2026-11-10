package com.zqc.tvnotify.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmResamplerTest {
    @Test fun unityRatePreservesEveryByteIncludingFinalStereoFrame() {
        val data = pcm(99, 98, -32768, 32767, -1, 1, 97, 96)
        assertArrayEquals(pcm(-32768, 32767, -1, 1), PcmResampler(2).render(data, 4, 2, 1.0))
    }

    @Test fun linearInterpolationKeepsStereoChannelsIndependentAndWithinPcmRange() {
        val result = PcmResampler(2).render(pcm(-32768, 32767, 32767, -32768, -32768, 32767), 0, 3, 0.995)
        assertEquals(0, result.size % 4)
        val samples = decode(result)
        assertEquals(-32768, samples[0])
        assertEquals(32767, samples[1])
        assertTrue(samples[2] > 32_000)
        assertTrue(samples[3] < -32_000)
        for (index in samples.indices step 2) {
            assertTrue(abs(samples[index] + samples[index + 1]) <= 1)
        }
    }

    @Test fun fractionalPhaseAccumulatesAcrossShortPacketsForBothRateSigns() {
        val fast = PcmResampler(1)
        val slow = PcmResampler(1)
        var fastFrames = 0
        var slowFrames = 0
        repeat(10_000) { packet ->
            val input = pcm(packet and 0x7fff, (packet + 1) and 0x7fff)
            fastFrames += fast.render(input, 0, 2, 1.005).size / 2
            slowFrames += slow.render(input, 0, 2, 0.995).size / 2
        }
        assertTrue("fast output $fastFrames", abs(fastFrames - 19_900.4975) <= 1.1)
        assertTrue("slow output $slowFrames", abs(slowFrames - 20_100.5025) <= 1.1)
    }

    @Test fun packetBoundariesMatchContinuousLinearRendering() {
        val input = pcm(*IntArray(1_000) { it * 20 - 10_000 })
        for (rate in listOf(0.995, 1.005)) {
            val expected = PcmResampler(1).render(input, 0, 1_000, rate)
            val chunked = PcmResampler(1)
            val pieces = ArrayList<Byte>()
            repeat(100) { packet ->
                for (byte in chunked.render(input, packet * 20, 10, rate)) pieces.add(byte)
            }
            assertArrayEquals(expected, pieces.toByteArray())
        }
    }

    @Test fun resetAndUnityRateRemovePriorFractionalPhase() {
        val resampler = PcmResampler(1)
        resampler.render(pcm(1, 2, 3), 0, 3, 0.995)
        resampler.reset()
        val input = pcm(1, 2, 3)
        assertArrayEquals(PcmResampler(1).render(input, 0, 3, 0.995), resampler.render(input, 0, 3, 0.995))
        assertArrayEquals(input, resampler.render(input, 0, 3, 1.0))
        assertArrayEquals(PcmResampler(1).render(input, 0, 3, 0.995), resampler.render(input, 0, 3, 0.995))
    }

    @Test fun emptyInputProducesNoOutput() {
        assertEquals(0, PcmResampler(2).render(byteArrayOf(), 0, 0, 0.995).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteFrameOffsetIsRejected() {
        PcmResampler(2).render(ByteArray(12), 2, 1, 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sourceRangeBeyondDataIsRejected() {
        PcmResampler(2).render(ByteArray(4), 0, 2, 1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRateIsRejected() {
        PcmResampler(1).render(ByteArray(4), 0, 2, Double.NaN)
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { result ->
        for (index in samples.indices) {
            result[index * 2] = samples[index].toByte()
            result[index * 2 + 1] = (samples[index] shr 8).toByte()
        }
    }

    private fun decode(data: ByteArray): IntArray = IntArray(data.size / 2) { index ->
        (((data[index * 2].toInt() and 255) or (data[index * 2 + 1].toInt() shl 8))).toShort().toInt()
    }
}
