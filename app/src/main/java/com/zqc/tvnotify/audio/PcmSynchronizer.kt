package com.zqc.tvnotify.audio

import kotlin.math.abs
import kotlin.math.ceil

/** Feedback for the software playback timeline, with positive error meaning late output. */
class PcmSynchronizer(private val sampleRate: Int, channels: Int) {
    init {
        require(sampleRate > 0)
        require(channels == 1 || channels == 2)
    }

    data class Decision(
        val silenceFrames: Int = 0,
        val trimFrames: Int = 0,
        val rate: Double = 1.0,
        val errorUs: Long = 0
    )

    private var smoothedErrorUs = 0.0

    fun reset() {
        smoothedErrorUs = 0.0
    }

    fun decide(
        targetUs: Long,
        outputUs: Long,
        availableFrames: Int,
        allowFineCorrection: Boolean = true
    ): Decision {
        require(availableFrames >= 0)
        val difference = outputUs.toDouble() - targetUs.toDouble()
        val errorUs = difference.toLong()
        if (abs(difference) >= 20_000.0) {
            reset()
            // At most 100 ms per call; use ceil so fractional late frames cannot remain.
            val maximum = ceil(sampleRate * 0.1).toInt()
            val frames = ceil(abs(difference) * sampleRate / 1_000_000.0)
                .coerceAtMost(maximum.toDouble()).toInt()
            return if (difference < 0) {
                Decision(silenceFrames = frames, errorUs = errorUs)
            } else {
                Decision(trimFrames = minOf(frames, availableFrames), errorUs = errorUs)
            }
        }
        if (!allowFineCorrection || availableFrames == 0 || abs(difference) <= 1_000.0) {
            reset()
            return Decision(errorUs = errorUs)
        }
        smoothedErrorUs += 0.1 * (difference - smoothedErrorUs)
        val rate = (1.0 + smoothedErrorUs / 2_000_000.0).coerceIn(0.995, 1.005)
        return Decision(rate = rate, errorUs = errorUs)
    }
}
