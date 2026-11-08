package com.zqc.tvnotify

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.wifi.WifiManager
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
        /** AudioTrack 硬件缓冲时长：只留少量，缓冲深度由输出延迟补偿决定 */
        private const val TRACK_BUFFER_US = 400_000L
        /** 本机输出延迟默认值（写进 AudioTrack 到真正出声的补偿量） */
        private const val DEFAULT_OUTPUT_DELAY_US = 100_000L
        /** 超过该值的滞后视为过期数据，直接丢弃以免延迟累积 */
        private const val LATE_DROP_US = 1_000_000L
        /** 时钟未同步时最多等这么久再退化顺序播放 */
        private const val UNSYNCED_WAIT_US = 2_000_000L
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
    private var wifiLock: WifiManager.WifiLock? = null
    private var queueBytesCount = 0

    /**
     * 输出延迟补偿（微秒）：写进 AudioTrack 到真正出声之间的滞后。
     * 调度时提前这么多写入，使声音在服务端时间戳那一刻播出。
     */
    @Volatile
    private var outputDelayUs: Long = DEFAULT_OUTPUT_DELAY_US

    @Volatile
    private var chunksReceived = 0L

    /** 自 startStream 起累计写入 AudioTrack 的帧数（立体声 16bit = 4 字节/帧） */
    @Volatile
    private var framesWritten = 0L

    @Volatile
    private var droppedLate = 0L

    /** 时钟未同步累计等待起点（0 表示未在等待） */
    @Volatile
    private var unsyncedWaitSinceUs = 0L

    fun outputDelayMs(): Int = (outputDelayUs / 1000L).toInt()

    /** 服务端可通过 set_output_delay 调整补偿量 */
    fun setOutputDelayMs(ms: Int) {
        outputDelayUs = (ms.coerceIn(0, 5000).toLong() * 1000L)
    }

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
        put("dropped_late", droppedLate)
        put("output_delay_ms", outputDelayUs / 1000L)
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
        unsyncedWaitSinceUs = 0L
        releaseWakeLock()
    }

    private fun playbackLoop(sampleRate: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        while (running) {
            val track = audioTrack ?: break
            val head = queue.peek()

            if (head == null) {
                // 队列空：等新数据
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            if (!clockFilter.isSynced) {
                // 时钟尚未同步：短暂等待（对齐后播放更准），超过 2s 仍未同步则退化为顺序播放
                if (unsyncedWaitSinceUs == 0L) unsyncedWaitSinceUs = System.nanoTime() / 1000L
                val waitedUs = System.nanoTime() / 1000L - unsyncedWaitSinceUs
                if (waitedUs < UNSYNCED_WAIT_US) {
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                val chunk = queue.poll() ?: continue
                synchronized(lock) { queueBytesCount -= chunk.pcmData.size }
                writeToTrack(track, chunk.pcmData)
                continue
            }
            unsyncedWaitSinceUs = 0L

            // 时间戳调度 + 实测积压反馈：
            // 声音出扬声器时刻 = 写入时刻 + AudioTrack 当前积压 Q。
            // 目标：写入时刻 + Q == 时间戳时刻 → 写入时机 = 时间戳时刻 - Q。
            // Q 用 playbackHead 实时测量（历史积压、HAL 延迟都被动态吸收），
            // outputDelay 作为下限：积压不足时宁可等久一点也不让缓冲见底。
            val queuedUs = queuedPlaybackUs(track, sampleRate)
            val writeDelayUs = maxOf(queuedUs, outputDelayUs)
            val scheduledUs = clockFilter.serverToLocalUs(head.timestampUs) - writeDelayUs
            val nowUs = System.nanoTime() / 1000L
            val diffUs = scheduledUs - nowUs

            if (diffUs > 0) {
                // 还没到播出时刻：小步等待，保持对 stop/flush 的响应
                val sleepUs = minOf(diffUs, 10_000L)
                try {
                    Thread.sleep(sleepUs / 1000L, ((sleepUs % 1000L) * 1000L).toInt())
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            val chunk = queue.poll() ?: continue
            synchronized(lock) { queueBytesCount -= chunk.pcmData.size }

            if (-diffUs > LATE_DROP_US) {
                // 落后太多（seek/断流后的陈旧数据）：丢弃追赶
                droppedLate++
                continue
            }

            writeToTrack(track, chunk.pcmData)
        }
    }
    /** AudioTrack 内已写入但尚未播出的音频时长（微秒） */
    private fun queuedPlaybackUs(track: AudioTrack, sampleRate: Int): Long {
        val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queuedFrames = framesWritten - playedFrames
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
        try {
            // WiFi 高性能模式：防止省电策略把下行音频挤成突发簇（时间戳调度的大敌）
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YuJian:SendspinAudio")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
        wifiLock = null
    }
}
