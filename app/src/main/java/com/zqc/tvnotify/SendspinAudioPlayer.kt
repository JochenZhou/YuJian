package com.zqc.tvnotify

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
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
        /** AudioTrack 硬件缓冲时长：留足余量吸收 MA 的预推突发（~1s 周期） */
        private const val TRACK_BUFFER_US = 600_000L
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

    /** 最近一块相对时间戳的写入延迟（微秒，>0 = 该块已过期） */
    @Volatile
    private var lastErrUs = 0L

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
                // 队列超限：丢"将要最后播"的那块（时间戳最大），
                // 千万不能 poll()——那是最先要播的队首，丢它会让播放线程
                // 的"首块等待"永远等不到目标时刻（死锁：无声+队列永不消化）。
                val newest = java.util.Collections.max(queue, compareBy { it.timestampUs })
                val it2 = queue.iterator()
                while (it2.hasNext()) {
                    if (it2.next() === newest) { it2.remove(); break }
                }
                queueBytesCount -= newest.pcmData.size
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
        put("app_queue_ms", queueBytesCount * 250_000L / sr)  // 4字节/帧: bytes*1e6/4/sr/1000
        put("chunks", chunksReceived)
        put("dropped_late", droppedLate)
        put("output_delay_ms", outputDelayUs / 1000L)
        put("err_ms", lastErrUs / 1000L)
        put("playing", isTrackPlaying())
        put("head_frames", track?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L)
        put("written_frames", framesWritten)
        put("write_failures", writeFailures)
        put("rebuilds", rebuildCount)
    }

    private fun isTrackPlaying(): Boolean = try {
        audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    } catch (_: Exception) { false }

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
        startWatchdog(sampleRate)
    }

    private fun stopStreamInternal() {
        running = false
        stopWatchdog()
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

        // 极简可靠的播放策略：**到货即写**。
        // 物理事实：AudioTrack 是 FIFO；一份音频写入时，它排在已有在飞音频之后开始播。
        // 服务端以 1x 推送、设备以 1x 消耗，所以只要不断供、不溢出，
        // "写入时刻 + 在飞量" 就自动等于该块的时间戳时刻——水位与相位自稳。
        // 因此这里只做三件事：等首块到点（不抢跑）、缓冲满时等待（不溢出）、
        // 严重过期时丢弃（不累积越拖越多）。
        var started = false

        while (running) {
            // watchdog 会并发 release 旧 AudioTrack；任何单轮异常都吞掉，
            // 绝不能让未捕获异常杀死这个线程——线程一死就没人消费队列，
            // 表现为无声 + 队列涨满 + 所有计数冻结（v1.5.15 真机根因）。
            try {
                playbackLoopOnce(sampleRate, started)?.let { started = it }
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                Log.e(TAG, "playbackLoop 单轮异常，继续", e)
                try { Thread.sleep(20) } catch (e2: InterruptedException) { break }
            }
        }
    }

    private fun playbackLoopOnce(sampleRate: Int, prevStarted: Boolean): Boolean? {
        var started = prevStarted
        run step@{
            val track = audioTrack ?: return null
            val head = queue.peek()

            if (head == null) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { return null }
                return started
            }

            if (!clockFilter.isSynced) {
                // 时钟未同步：最多等 2s，之后退化顺序播放（有声优先）
                if (unsyncedWaitSinceUs == 0L) unsyncedWaitSinceUs = System.nanoTime() / 1000L
                val waitedUs = System.nanoTime() / 1000L - unsyncedWaitSinceUs
                if (waitedUs < UNSYNCED_WAIT_US) {
                    try { Thread.sleep(10) } catch (e: InterruptedException) { return null }
                    return started
                }
                started = true
                val chunk = queue.poll() ?: return started
                synchronized(lock) { queueBytesCount -= chunk.pcmData.size }
                writeToTrack(track, chunk.pcmData)
                return started
            }
            unsyncedWaitSinceUs = 0L

            val chunk = head
            val targetUs = clockFilter.serverToLocalUs(chunk.timestampUs) - outputDelayUs
            val nowUs = System.nanoTime() / 1000L

            // 首块：等到它的时间戳时刻才写，避免整条流系统性提前。
            // 但目标若在缓冲期之外（时钟异常/服务端预推过远）就放弃等待直接开播——
            // 无限等待会让溢出逻辑和等待互相配合锁死整条流（v1.5.14 真机无声的根因）。
            if (!started) {
                if (nowUs < targetUs) {
                    if (targetUs - nowUs > TRACK_BUFFER_US) {
                        Log.w(TAG, "首块目标时刻过远(${(targetUs - nowUs) / 1000}ms)，放弃等待直接开播")
                        started = true
                        return started
                    }
                    val sleepUs = minOf(targetUs - nowUs, 10_000L)
                    try { Thread.sleep(sleepUs / 1000L, ((sleepUs % 1000L) * 1000L).toInt()) }
                    catch (e: InterruptedException) { return null }
                    return started
                }
                started = true
            }

            // 严重过期（断流恢复/seek 残留）：丢弃，避免越拖越多
            if (nowUs - targetUs > LATE_DROP_US) {
                val stale = queue.poll() ?: return started
                synchronized(lock) { queueBytesCount -= stale.pcmData.size }
                droppedLate++
                return started
            }

            // 缓冲余量：在飞量接近缓冲上限就等一会（write 否则会阻塞）
            if (queuedPlaybackUs(track, sampleRate) + 50_000L > TRACK_BUFFER_US) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { return null }
                return started
            }

            queue.poll()
            synchronized(lock) { queueBytesCount -= chunk.pcmData.size }
            lastErrUs = nowUs - targetUs          // >0 = 该块已过期（写入即稍晚出声）
            writeToTrack(track, chunk.pcmData)
        }
        return started
    }

    /** AudioTrack 内已写入但尚未播出的音频时长（微秒）—— AudioTimestamp 不可用时的退化估计 */
    private fun queuedPlaybackUs(track: AudioTrack, sampleRate: Int): Long {
        val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queuedFrames = framesWritten - playedFrames
        if (queuedFrames <= 0L || queuedFrames > sampleRate.toLong() * 60L) return 0L
        return queuedFrames * 1_000_000L / sampleRate
    }

    /** 完整写入一块 PCM（AudioTrack.write 可能短写） */
    /** write() 失败/零写入计数（>0 说明 AudioTrack 拒收数据） */
    @Volatile
    var writeFailures = 0L
        private set

    /** AudioTrack 销毁重建次数（watchdog 自愈） */
    @Volatile
    var rebuildCount = 0L
        private set

    private fun writeToTrack(track: AudioTrack, data: ByteArray) {
        var offset = 0
        // 非阻塞写：AudioTrack 卡死/路由失效时 write 可能永久阻塞，
        // 会把播放线程一起冻死。这里最多试一次，写不下就留给下轮。
        while (offset < data.size && running) {
            val written = try {
                if (track.state == AudioTrack.STATE_UNINITIALIZED) { writeFailures++; break }
                track.write(data, offset, data.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: IllegalStateException) {
                // watchdog 正在重建：本块作废，绝不能让异常杀线程
                writeFailures++
                break
            } catch (e: Exception) {
                writeFailures++
                break
            }
            if (written <= 0) {
                writeFailures++
                break
            }
            offset += written
            framesWritten += written / 4L // 立体声 16bit → 4 字节/帧
        }
    }

    /**
     * 播出 watchdog：AudioTrack 声称 PLAYING 但硬件帧计数长时间不动 = 卡死
     * （已写满的缓冲不再被消费，head 恒 0）。唯一可靠的恢复是销毁重建。
     * 独立线程 + AudioTimestamp 硬件帧计数（playbackHead 在该设备上冻结时
     * AudioTimestamp 仍可能推进，二者都看）。
     */
    private var watchdogThread: Thread? = null

    private fun startWatchdog(sampleRate: Int) {
        stopWatchdog()
        val t = Thread({
            var lastProgress = -1L
            var stuckRounds = 0
            var lastWriteFailures = 0L
            while (running) {
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                val track = audioTrack ?: continue
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) { stuckRounds = 0; continue }
                if (framesWritten == 0L) { stuckRounds = 0; continue }   // 还没数据
                // 消费进度 = 已播出帧数（优先 AudioTimestamp，退化为 playbackHead）
                val ts = AudioTimestamp()
                val consumed = (try { track.getTimestamp(ts) && ts.nanoTime > 0 } catch (_: Exception) { false })
                    .let { ok -> if (ok) ts.framePosition.toLong() and 0xFFFFFFFFL
                                 else track.playbackHeadPosition.toLong() and 0xFFFFFFFFL }
                // playbackLoop 线程死亡检测：没人消费队列 = 永久无声，必须重启线程
                val worker = workerThread
                if (worker != null && !worker.isAlive && running) {
                    Log.w(TAG, "watchdog: 播放线程已死亡，重启")
                    try { worker.join(1000) } catch (_: Exception) {}
                    val t = Thread({ playbackLoop(sampleRate) }, "sendspin-audio-worker")
                    t.priority = Thread.MAX_PRIORITY
                    workerThread = t
                    t.start()
                    stuckRounds = 0
                    continue
                }
                val writeFailing = writeFailures > lastWriteFailures
                lastWriteFailures = writeFailures
                if (consumed > lastProgress && !writeFailing) {
                    lastProgress = consumed
                    stuckRounds = 0
                    continue
                }
                stuckRounds++
                Log.w(TAG, "watchdog: 停滞 ${stuckRounds}x2s（consumed=$consumed written=$framesWritten writeFail=$writeFailures）")
                if (stuckRounds >= 3) {
                    stuckRounds = 0
                    lastProgress = -1L
                    Log.w(TAG, "watchdog: AudioTrack 异常，销毁重建")
                    rebuildAudioTrack(sampleRate)
                }
            }
        }, "sendspin-audio-watchdog")
        t.isDaemon = true
        watchdogThread = t
        t.start()
    }

    private fun stopWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    /** 卡死自愈：丢弃当前 AudioTrack，立即重建并恢复播放（缓冲内数据作废，重新写入） */
    private fun rebuildAudioTrack(sampleRate: Int) {
        synchronized(lock) {
            val old = audioTrack
            audioTrack = null
            try { old?.pause() } catch (_: Exception) {}
            try { old?.flush() } catch (_: Exception) {}
            try { old?.release() } catch (_: Exception) {}
            framesWritten = 0L
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val targetBytes = (TRACK_BUFFER_US * sampleRate * 4L / 1_000_000L).toInt() and -4
            val bufSize = maxOf(minBufSize * 4, targetBytes, 32768)
            val track = try {
                AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM)
            } catch (e: Exception) {
                Log.e(TAG, "watchdog: 重建 AudioTrack 失败", e)
                return
            }
            audioTrack = track
            writeFailures = 0L
            rebuildCount++
            applyVolumeToTrack()
            try { track.play() } catch (e: Exception) { Log.e(TAG, "watchdog: play 失败", e) }
            Log.w(TAG, "watchdog: AudioTrack 已重建（第 $rebuildCount 次）")
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
