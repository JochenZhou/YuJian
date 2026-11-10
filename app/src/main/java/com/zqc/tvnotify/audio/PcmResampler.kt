package com.zqc.tvnotify.audio

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Stateful LE16 mono/stereo interpolation. Rate is source frames per output frame. */
class PcmResampler(private val channels: Int) {
    init {
        require(channels == 1 || channels == 2)
    }

    private val frameBytes = channels * 2
    private val previous = IntArray(channels)
    private var hasPrevious = false
    // Fixed-point phase avoids chunk-boundary drift from repeated floating-point additions.
    private var phaseQ = 0L
    private val scale = 1L shl 30
    private var stepQ = scale

    fun reset() {
        phaseQ = 0L
        stepQ = scale
        hasPrevious = false
        previous.fill(0)
    }

    fun render(data: ByteArray, offsetBytes: Int, sourceFrames: Int, rate: Double): ByteArray {
        require(offsetBytes >= 0 && sourceFrames >= 0 && offsetBytes % frameBytes == 0)
        require(rate.isFinite() && rate in 0.995..1.005)
        val byteCount = sourceFrames.toLong() * frameBytes
        require(offsetBytes.toLong() + byteCount <= data.size)
        if (sourceFrames == 0) return ByteArray(0)
        if (rate == 1.0 && phaseQ == 0L) {
            // An integer phase needs no lookahead, including the final frame. Keep the last
            // sample available when a later rate change needs interpolation across this boundary.
            for (channel in 0 until channels) previous[channel] = sample(data, offsetBytes, sourceFrames - 1, channel)
            hasPrevious = true
            return data.copyOfRange(offsetBytes, offsetBytes + byteCount.toInt())
        }

        val last = sourceFrames - 1
        stepQ = (rate * scale).roundToLong()
        val remainingQ = last.toLong() * scale - phaseQ
        val outputFrames = if (remainingQ <= 0) 0 else (remainingQ + stepQ - 1) / stepQ
        require(outputFrames <= Int.MAX_VALUE / frameBytes)
        val result = ByteArray(outputFrames.toInt() * frameBytes)
        var positionQ = phaseQ
        repeat(outputFrames.toInt()) { output ->
            val left = (positionQ shr 30).toInt()
            val fraction = (positionQ - left.toLong() * scale).toDouble() / scale
            for (channel in 0 until channels) {
                val a = if (left < 0 && hasPrevious) previous[channel]
                    else sample(data, offsetBytes, left.coerceAtLeast(0), channel)
                val b = sample(data, offsetBytes, (left + 1).coerceAtLeast(0).coerceAtMost(last), channel)
                val value = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767)
                val outputOffset = output * frameBytes + channel * 2
                result[outputOffset] = value.toByte()
                result[outputOffset + 1] = (value shr 8).toByte()
            }
            positionQ += stepQ
        }
        phaseQ = positionQ - sourceFrames.toLong() * scale
        for (channel in 0 until channels) previous[channel] = sample(data, offsetBytes, last, channel)
        hasPrevious = true
        return result
    }

    private fun sample(data: ByteArray, offsetBytes: Int, frame: Int, channel: Int): Int {
        val index = offsetBytes + frame * frameBytes + channel * 2
        return ((data[index].toInt() and 255) or (data[index + 1].toInt() shl 8)).toShort().toInt()
    }
}
