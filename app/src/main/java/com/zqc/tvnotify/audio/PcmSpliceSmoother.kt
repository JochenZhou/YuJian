package com.zqc.tvnotify.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Blends LE16 mono/stereo splice boundaries over 4 ms without adding or removing frames. */
class PcmSpliceSmoother(sampleRate: Int, private val channels: Int) {
    init {
        require(sampleRate > 0)
        require(channels == 1 || channels == 2)
    }

    private val frameBytes = channels * 2
    private val rampFrames = maxOf(2, (sampleRate * 0.004).roundToInt())
    private val weights = DoubleArray(rampFrames) { frame ->
        (1.0 - cos(PI * frame / (rampFrames - 1))) * 0.5
    }
    private val lastOutput = IntArray(channels)
    private val spliceStart = IntArray(channels)
    private var rampOffset = 0

    /** Discards prior output and arms the next block to ramp from silence. */
    fun reset() {
        lastOutput.fill(0)
        spliceStart.fill(0)
        rampOffset = 0
    }

    /** Arms a splice from the previous output frame, continuing over short input blocks. */
    fun beginSplice() {
        lastOutput.copyInto(spliceStart)
        rampOffset = 0
    }

    /**
     * Prepares a complete pending block once; partial writes must reuse the returned bytes.
     * History follows prepared output, so reset if that pending block is discarded.
     * Input is never modified, and steady blocks are returned without allocating a copy.
     */
    fun process(data: ByteArray): ByteArray {
        require(data.size % frameBytes == 0)
        if (data.isEmpty()) return data
        val frames = data.size / frameBytes
        val blendFrames = minOf(frames, rampFrames - rampOffset)
        val result = if (blendFrames > 0) data.copyOf() else data
        repeat(blendFrames) { frame ->
            val weight = weights[rampOffset + frame]
            for (channel in 0 until channels) {
                val start = spliceStart[channel]
                val incoming = sample(data, frame, channel)
                val value = (start + (incoming - start) * weight).roundToInt().coerceIn(-32768, 32767)
                val index = frame * frameBytes + channel * 2
                result[index] = value.toByte()
                result[index + 1] = (value shr 8).toByte()
            }
        }
        rampOffset += blendFrames
        for (channel in 0 until channels) lastOutput[channel] = sample(result, frames - 1, channel)
        return result
    }

    private fun sample(data: ByteArray, frame: Int, channel: Int): Int {
        val index = frame * frameBytes + channel * 2
        return ((data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)).toShort().toInt()
    }
}
