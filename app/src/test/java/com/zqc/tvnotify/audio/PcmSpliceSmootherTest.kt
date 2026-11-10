package com.zqc.tvnotify.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSpliceSmootherTest {
    @Test fun firstStereoAudioRampsFromZeroWithoutChangingLengthOrInput() {
        val smoother = PcmSpliceSmoother(1000, 2)
        val input = pcm(12000, -12000, 12000, -12000, 12000, -12000, 12000, -12000, 12000, -12000)
        val original = input.copyOf()
        val result = smoother.process(input)
        assertEquals(input.size, result.size)
        assertArrayEquals(pcm(0, 0, 3000, -3000, 9000, -9000, 12000, -12000, 12000, -12000), result)
        assertArrayEquals(original, input)
    }

    @Test fun realSampleRatesCompleteTheirOnsetBetweenThreeAndFiveMilliseconds() {
        for (sampleRate in intArrayOf(44100, 48000)) {
            val smoother = PcmSpliceSmoother(sampleRate, 1)
            val input = pcm(*IntArray(sampleRate * 6 / 1000) { 12000 })
            val result = decode(smoother.process(input))
            assertEquals(input.size / 2, result.size)
            assertEquals(0, result.first())
            assertTrue(result[sampleRate * 3 / 1000] in 1..11999)
            assertEquals(12000, result[sampleRate * 5 / 1000])
            for (index in 1 until result.size) assertTrue(result[index] >= result[index - 1])
        }
    }

    @Test fun hardSpliceStartsAtLastOutputFrameAndReachesIncomingSignal() {
        val smoother = PcmSpliceSmoother(1000, 1)
        smoother.process(pcm(1000, 2000, 3000, 4000))
        smoother.beginSplice()
        assertArrayEquals(pcm(4000, 2000, -2000, -4000, -4000),
            smoother.process(pcm(-4000, -4000, -4000, -4000, -4000)))
    }

    @Test fun shortPacketsShareOneRampAndSteadyPassesReuseInput() {
        val smoother = PcmSpliceSmoother(1000, 1)
        assertArrayEquals(pcm(0, 3000), smoother.process(pcm(12000, 12000)))
        assertArrayEquals(pcm(9000, 12000), smoother.process(pcm(12000, 12000)))
        val steady = pcm(-1200, 2400, -3600)
        assertSame(steady, smoother.process(steady))
        smoother.beginSplice()
        assertArrayEquals(pcm(-3600, -2700, -900, 0), smoother.process(pcm(0, 0, 0, 0)))
    }

    @Test fun emptyInputDoesNotConsumeAnArmedSpliceOrForgetItsLastFrame() {
        val smoother = PcmSpliceSmoother(1000, 1)
        smoother.process(pcm(4000, 4000, 4000, 4000))
        smoother.beginSplice()
        val empty = byteArrayOf()
        assertSame(empty, smoother.process(empty))
        assertArrayEquals(pcm(4000, 3000, 1000, 0), smoother.process(pcm(0, 0, 0, 0)))
    }

    @Test fun resetForgetsDiscardedOutputAndRampsFromSilenceAgain() {
        val smoother = PcmSpliceSmoother(1000, 1)
        smoother.process(pcm(16000, 16000, 16000, 16000))
        smoother.beginSplice()
        smoother.process(pcm(-16000, -16000))
        smoother.reset()
        assertArrayEquals(pcm(0, 3000, 9000, 12000), smoother.process(pcm(12000, 12000, 12000, 12000)))
    }

    @Test fun rearmingDuringRampUsesLastActuallyEmittedSample() {
        val smoother = PcmSpliceSmoother(1000, 1)
        assertArrayEquals(pcm(0, 3000), smoother.process(pcm(12000, 12000)))
        smoother.beginSplice()
        assertArrayEquals(pcm(3000, 0, -6000, -9000), smoother.process(pcm(-9000, -9000, -9000, -9000)))
    }

    @Test fun oppositeStereoExtremesBlendIndependentlyWithoutOverflow() {
        val smoother = PcmSpliceSmoother(1000, 2)
        smoother.process(pcm(32767, -32768, 32767, -32768, 32767, -32768, 32767, -32768))
        smoother.beginSplice()
        assertArrayEquals(pcm(32767, -32768, 16383, -16384, -16384, 16383, -32768, 32767),
            smoother.process(pcm(-32768, 32767, -32768, 32767, -32768, 32767, -32768, 32767)))
    }

    @Test fun matchingSignalHasNoGainDipOrDcChangeAtSplice() {
        val smoother = PcmSpliceSmoother(1000, 2)
        val input = pcm(12000, -7000, 12000, -7000, 12000, -7000, 12000, -7000)
        smoother.process(input)
        smoother.beginSplice()
        assertArrayEquals(input, smoother.process(input))
        assertSame(input, smoother.process(input))
    }

    @Test fun invalidFrameDoesNotAdvanceRamp() {
        val smoother = PcmSpliceSmoother(1000, 2)
        try {
            smoother.process(ByteArray(3))
            throw AssertionError("Expected incomplete stereo frame to be rejected")
        } catch (_: IllegalArgumentException) {
            assertArrayEquals(pcm(0, 0, 3000, -3000), smoother.process(pcm(12000, -12000, 12000, -12000)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSampleRateIsRejected() {
        PcmSpliceSmoother(0, 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedChannelCountIsRejected() {
        PcmSpliceSmoother(48000, 3)
    }

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { result ->
        for (index in samples.indices) {
            result[index * 2] = samples[index].toByte()
            result[index * 2 + 1] = (samples[index] shr 8).toByte()
        }
    }

    private fun decode(data: ByteArray): IntArray = IntArray(data.size / 2) { index ->
        ((data[index * 2].toInt() and 255) or (data[index * 2 + 1].toInt() shl 8)).toShort().toInt()
    }
}
