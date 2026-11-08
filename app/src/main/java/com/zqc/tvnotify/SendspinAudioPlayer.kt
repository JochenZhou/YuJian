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
        /** AudioTrack 硬件缓冲时长：留足余量吸收服务端发送抖动，防欠载爆音 */
        private const val TRACK_BUFFER_US = 800_000L
        /** 播放线程维持的目标缓冲水位：高于它就节流等待，低于就立刻补齐 */
        private const val TARGET_BUFFER_US = 300_000L
        /** 单次节流最多等待多久，防止异常时间戳把播放线程挂死 */
        private const val MAX_PACING_WAIT_US = 500_000L
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

    /** 自 startStream 起累计写入 AudioTrack 的帧数（立体声 16bit = 4 字节/帧） */
    @Volatile
    private var framesWritten = 0L

    @Volatile
    private var chunksReceived = 0L

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
        chunksReceived++
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
                // 清空硬件缓冲，否则 seek 之后还会先播完旧的 800ms 音频
                flushTrack()
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

    /**
     * 丢弃 AudioTrack 里尚未播出的音频。
     * MODE_STREAM 下必须先 pause 再 flush，否则部分设备会抛 IllegalStateException。
     */
    private fun flushTrack() {
        val track = audioTrack ?: return
        try {
            track.pause()
            track.flush()
            framesWritten = 0L
            track.play()
        } catch (e: Exception) {
            Log.w(TAG, "flush AudioTrack 失败", e)
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

    /** 播放健康度快照，用于 /health 远程诊断（无需 adb） */
    fun debugStats(): org.json.JSONObject = org.json.JSONObject().apply {
        val track = audioTrack
        val sr = activeFormat?.sampleRate ?: 48000
        put("queued_ms", if (track != null) queuedPlaybackUs(track, sr) / 1000L else 0L)
        put("chunks", chunksReceived)
        put("target_ms", TARGET_BUFFER_US / 1000L)
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
        // 缓冲给足 ~800ms：太小的缓冲会被服务端发送节奏牵着走，造成周期性欠载卡顿
        val targetBytes = (TRACK_BUFFER_US * sampleRate * 4L / 1_000_000L).toInt() and -4
        val bufSize = maxOf(minBufSize * 4, targetBytes, 32768)

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
        framesWritten = 0L
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

            // 以"AudioTrack 里常驻多少毫秒音频"作为唯一节流依据：
            // 缓冲高于目标就等它排空到目标再写，低于目标则立即补齐。
            // 绝不往音乐中间插静音（旧实现每块都插，造成规律性脉冲卡顿）。
            val queuedUs = queuedPlaybackUs(track, sampleRate)
            if (queuedUs > TARGET_BUFFER_US) {
                val waitUs = minOf(queuedUs - TARGET_BUFFER_US, MAX_PACING_WAIT_US)
                try {
                    Thread.sleep(waitUs / 1000L, ((waitUs % 1000L) * 1000L).toInt())
                } catch (e: InterruptedException) {
                    break
                }
            }

            writeToTrack(track, chunk.pcmData)
        }
    }

    /** AudioTrack 内已写入但尚未播出的音频时长（微秒） */
    private fun queuedPlaybackUs(track: AudioTrack, sampleRate: Int): Long {
        val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queuedFrames = framesWritten - playedFrames
        // 正常值应在缓冲容量以内；越界说明 32 位计数回绕，按 0 处理
        if (queuedFrames <= 0L || queuedFrames > sampleRate.toLong() * 60L) return 0L
        return queuedFrames * 1_000_000L / sampleRate
    }

    /** 完整写入一块 PCM（AudioTrack.write 可能短写） */
    private fun writeToTrack(track: AudioTrack, data: ByteArray) {
        var offset = 0
        while (offset < data.size && running) {
            val written = track.write(data, offset, data.size - offset)
            if (written <= 0) break
            offset += written
            framesWritten += written / 4L // 立体声 16bit → 4 字节/帧
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
