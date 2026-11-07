package com.zqc.tvnotify

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue

/**
 * Sendspin 原生 PCM 零延迟时间同步音频播放引擎：
 * 1. 采用 Android 原生 AudioTrack（MODE_STREAM），零引入任何第三方编解码库；
 * 2. 结合 SendspinClockFilter 完成服务端时钟微秒对齐；
 * 3. 毫秒级抗抖动与自动追赶（超前写静音帧填补，滞后丢弃过期帧追赶）；
 * 4. 自动单声道转立体声，平方增益曲线硬件音量调控；
 * 5. 播放期间持有轻量级 PARTIAL_WAKE_LOCK 防止电视休眠切断音乐。
 */
class SendspinAudioPlayer(
    private val context: Context,
    private val clockFilter: SendspinClockFilter
) {

    companion object {
        private const val TAG = "SendspinAudio"
        private const val MAX_QUEUE_BYTES = 1048576 // 1MB 规范上限
        private const val JITTER_EARLY_THRESHOLD_US = 20_000L // 20ms
        private const val JITTER_LATE_THRESHOLD_US = 45_000L  // 45ms
        private const val MAX_SILENCE_INSERT_US = 250_000L    // 250ms
    }

    private data class AudioChunk(
        val timestampUs: Long,
        val pcmData: ByteArray
    ) : Comparable<AudioChunk> {
        override fun compareTo(other: AudioChunk): Int =
            timestampUs.compareTo(other.timestampUs)

        override fun equals(other: Any?): Boolean =
            other is AudioChunk && timestampUs == other.timestampUs

        override fun hashCode(): Int = timestampUs.hashCode()
    }

    private val queue = PriorityBlockingQueue<AudioChunk>(64)
    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var activeFormat: SendspinPlayerFormat? = null

    @Volatile
    private var volumePercent: Int = 100

    @Volatile
    private var isMuted: Boolean = false

    private var audioTrack: AudioTrack? = null
    private var workerThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var queueBytesCount = 0

    init {
        try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvnotify:sendspin-audio")?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "初始化 WakeLock 异常", e)
        }
    }

    fun configureStream(format: SendspinPlayerFormat) {
        synchronized(lock) {
            if (activeFormat == format && audioTrack != null) {
                return
            }
            stopStreamInternal()
            activeFormat = format
            startStreamInternal(format)
        }
    }

    fun enqueueAudioChunk(serverTimestampUs: Long, pcmBytes: ByteArray) {
        val format = activeFormat ?: return
        val stereoPcm = if (format.channels == 1) {
            convertMonoToStereo(pcmBytes)
        } else {
            pcmBytes
        }

        synchronized(lock) {
            if (queueBytesCount + stereoPcm.size > MAX_QUEUE_BYTES) {
                // 队列超限时丢弃最旧包
                val old = queue.poll()
                if (old != null) {
                    queueBytesCount -= old.pcmData.size
                }
            }
            queue.offer(AudioChunk(serverTimestampUs, stereoPcm))
            queueBytesCount += stereoPcm.size
        }
    }

    fun clearBuffer(fromServerTimestampUs: Long? = null) {
        synchronized(lock) {
            if (fromServerTimestampUs == null) {
                queue.clear()
                queueBytesCount = 0
            } else {
                val toKeep = ArrayList<AudioChunk>()
                var newBytes = 0
                while (!queue.isEmpty()) {
                    val c = queue.poll() ?: break
                    if (c.timestampUs < fromServerTimestampUs) {
                        toKeep.add(c)
                        newBytes += c.pcmData.size
                    }
                }
                queue.clear()
                queue.addAll(toKeep)
                queueBytesCount = newBytes
            }
        }
    }

    fun stopStream() {
        synchronized(lock) {
            stopStreamInternal()
        }
    }

    fun setVolumeAndMute(volumePercent: Int, muted: Boolean) {
        this.volumePercent = volumePercent.coerceIn(0, 100)
        this.isMuted = muted
        applyVolumeToTrack()
    }

    fun release() {
        stopStream()
        releaseWakeLock()
    }

    // ------------------------------------------------------------------
    // 内部实现与播放线程
    // ------------------------------------------------------------------

    private fun startStreamInternal(format: SendspinPlayerFormat) {
        val sampleRate = format.sampleRate
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        val bufSize = maxOf(minBufSize * 4, 32768)

        val track = try {
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufSize,
                AudioTrack.MODE_STREAM
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建 AudioTrack 失败", e)
            return
        }

        audioTrack = track
        applyVolumeToTrack()

        try {
            track.play()
        } catch (e: Exception) {
            Log.e(TAG, "启动 AudioTrack 失败", e)
        }

        acquireWakeLock()
        running = true

        val worker = Thread({ playbackLoop(sampleRate) }, "sendspin-audio-worker")
        worker.priority = Thread.MAX_PRIORITY
        workerThread = worker
        worker.start()
    }

    private fun stopStreamInternal() {
        running = false
        val worker = workerThread
        workerThread = null
        worker?.interrupt()

        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                track.stop()
            } catch (_: Exception) {}
            try {
                track.release()
            } catch (_: Exception) {}
        }
        queue.clear()
        queueBytesCount = 0
        releaseWakeLock()
    }

    private fun playbackLoop(sampleRate: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        while (running) {
            val chunk = try {
                queue.take()
            } catch (e: InterruptedException) {
                break
            }

            synchronized(lock) {
                queueBytesCount -= chunk.pcmData.size
            }

            val track = audioTrack ?: break
            val targetLocalUs = clockFilter.serverToLocalUs(chunk.timestampUs)
            val nowLocalUs = System.nanoTime() / 1000L
            val diffUs = targetLocalUs - nowLocalUs

            if (clockFilter.isSynced) {
                if (diffUs > JITTER_EARLY_THRESHOLD_US) {
                    // 超前到达：填补静音帧以保持硬件音频头绝对对齐
                    val silenceUs = minOf(diffUs, MAX_SILENCE_INSERT_US)
                    val silenceBytesCount = ((silenceUs * sampleRate * 4L) / 1_000_000L).toInt() and -4
                    if (silenceBytesCount > 0) {
                        val silence = ByteArray(silenceBytesCount)
                        track.write(silence, 0, silence.size)
                    }
                } else if (diffUs < -JITTER_LATE_THRESHOLD_US) {
                    // 滞后超过阈值：丢帧追赶
                    continue
                }
            }

            track.write(chunk.pcmData, 0, chunk.pcmData.size)
        }
    }

    private fun applyVolumeToTrack() {
        val track = audioTrack ?: return
        val gain = if (isMuted) 0f else {
            val fraction = volumePercent / 100f
            fraction * fraction // 平方对数响度曲线
        }
        try {
            track.setVolume(gain)
        } catch (_: Exception) {}
    }

    private fun convertMonoToStereo(monoPcm: ByteArray): ByteArray {
        val stereo = ByteArray(monoPcm.size * 2)
        var i = 0
        var o = 0
        while (i + 1 < monoPcm.size) {
            val b0 = monoPcm[i]
            val b1 = monoPcm[i + 1]
            stereo[o] = b0
            stereo[o + 1] = b1
            stereo[o + 2] = b0
            stereo[o + 3] = b1
            i += 2
            o += 4
        }
        return stereo
    }

    private fun acquireWakeLock() {
        try {
            wakeLock?.acquire(3 * 3600 * 1000L) // 最多持有 3 小时兜底
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }
}
