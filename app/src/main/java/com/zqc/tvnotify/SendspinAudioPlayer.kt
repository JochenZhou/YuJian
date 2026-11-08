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
        /** AudioTrack 硬件缓冲上限：水位由调度判据控制，这里只防溢出阻塞 */
        private const val TRACK_BUFFER_US = 400_000L
        /** 手动提前量默认 0：纯时间戳对齐。设备后级 DSP 延迟用 MA 静态延迟校准 */
        private const val DEFAULT_OUTPUT_DELAY_US = 0L
        /** 超过该值的滞后视为过期数据，直接丢弃以免延迟累积 */
        private const val LATE_DROP_US = 1_000_000L
        /** 写入容差（诊断用） */
        private const val LATE_TOLERANCE_US = 60_000L
        /** ROLLING 重锚阈值：只有偏差超过这么多（≈10 块）才 flush 重锚，防抖动反复重锚 */
        private const val RESYNC_THRESHOLD_US = 250_000L
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

    /** 连续丢弃计数（时间轴失配保护） */
    private var consecutiveDrops = 0L

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
            // 对齐基准：flush 后 playbackHead 可能不归零，直接把 framesWritten 贴上去，
            // 保证 在飞量 = framesWritten − playbackHead 立刻为 0。
            framesWritten = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            // 交给状态机重新锚定（不要在这里 play()，否则下一块写入会立刻出声）
            playState = PlayState.ANCHORING
            anchorPlayAtUs = 0L
            playStartNanos = 0L
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
        val ts = AudioTimestamp()
        val haveTs = try { track?.getTimestamp(ts) == true && ts.nanoTime > 0 } catch (_: Exception) { false }
        val inFlightUs = if (haveTs && track != null) {
            val played = ts.framePosition.toLong() and 0xFFFFFFFFL
            val writtenF = framesWritten
            val q = (writtenF - played).coerceAtLeast(0L)
            if (q > sr.toLong() * 60L) 0L else q * 1_000_000L / sr
        } else if (track != null) queuedPlaybackUs(track, sr) else 0L
        put("queued_ms", inFlightUs / 1000L)
        put("in_flight_ms", inFlightUs / 1000L)
        put("chunks", chunksReceived)
        put("dropped_late", droppedLate)
        put("output_delay_ms", outputDelayUs / 1000L)
        put("err_ms", lastErrUs / 1000L)
        put("end_lag_ms", lastEndLagUs / 1000L)
        put("hard_syncs", hardSyncs)
        put("state", playState.name)
        put("pipeline_latency_ms", pipelineLatencyUs / 1000L)
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

        // 不立刻 play()：先以静默状态积攒 cushion，由状态机在首块时间戳时刻 play()，
        // 让"第一帧出声时刻"精确锚定到服务端时间轴。
        playState = PlayState.ANCHORING
        anchorPlayAtUs = 0L
        calibrationsLeft = 2
        hardSyncs = 0L
        panicked = false
        lastHardSyncNanos = 0L
        rollingSinceNanos = 0L

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
        playState = PlayState.ANCHORING
        anchorPlayAtUs = 0L
        releaseWakeLock()
    }

    /**
     * 播放状态机。
     * ANCHORING：把音频写进（尚未 play() 的）AudioTrack 积累 cushion，
     *            到首块时间戳时刻调用 play() 锚定出声起点；
     * ROLLING  ：FIFO 已在跑，之后"到货即写"，节奏由服务端 1x 推送天然维持。
     */
    private enum class PlayState { ANCHORING, ROLLING }

    @Volatile
    private var playState = PlayState.ANCHORING

    /** 锚定时刻（本地微秒）：到点调用 play()，块 0 从此刻出声 */
    @Volatile
    private var anchorPlayAtUs = 0L

    /**
     * 自校准的设备管线延迟（微秒）：play() 到首帧真正出声的间隔。
     * 锚定时提前这么多 play()，让出声时刻落在时间戳上。
     * 每次开流最多校准 2 轮（首次猜 0，测出误差后修正）。
     */
    @Volatile
    private var pipelineLatencyUs = 0L

    /** 本流剩余可校准次数 */
    @Volatile
    private var calibrationsLeft = 2

    /** play() 时刻（纳秒）：稳定期内不做超差重锚，避免自激 */
    @Volatile
    private var rollingSinceNanos = 0L

    /** 最近一次 play() 调用时刻（纳秒）：用于测量设备管线延迟 */
    @Volatile
    private var playStartNanos = 0L

    private fun playbackLoop(sampleRate: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val ts = AudioTimestamp()

        while (running) {
            val track = audioTrack ?: break
            val head = queue.peek()

            // ---- 在飞量（已写入但尚未播出）----
            // 暂停态（ANCHORING 攒缓冲时）：精确等于已写帧数——一个字节都还没播。
            // 播放态：优先 AudioTimestamp（帧计数 + 纳秒时戳，含 HAL/DSP 管线），
            //   但要求时戳新鲜（<200ms）且帧号不大于已写帧数，否则用 playbackHead 退化。
            val nowNanos = System.nanoTime()
            val playing = isTrackPlaying()
            val framesWrittenUs = framesWritten * 1_000_000L / sampleRate
            // 在飞量统一用 playbackHeadPosition：它与 flush 的基准天然自洽
            // （AudioTimestamp 的 framePosition 在 flush 后不一定归零，拿它算在飞量
            //  会得出 0，触发"err 巨大 → 立刻重锚 → 再丢音频"的死循环）。
            // AudioTimestamp 只用于测设备管线延迟（需要绝对时间）。
            val inFlightUs = queuedPlaybackUs(track, sampleRate)
            // 管线延迟校准用的绝对时戳（新鲜且帧号合理才采信）
            var playedFrames = -1L
            var tsNanos = 0L
            if (playing) {
                val haveTs = try { track.getTimestamp(ts) } catch (_: Exception) { false }
                if (haveTs && ts.nanoTime > 0 && (nowNanos - ts.nanoTime) in 0..200_000_000L) {
                    val f = ts.framePosition.toLong() and 0xFFFFFFFFL
                    if (f <= framesWritten) { playedFrames = f; tsNanos = ts.nanoTime }
                }
            }

            if (head == null) {
                try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                continue
            }

            if (!clockFilter.isSynced) {
                if (unsyncedWaitSinceUs == 0L) unsyncedWaitSinceUs = System.nanoTime() / 1000L
                val waitedUs = System.nanoTime() / 1000L - unsyncedWaitSinceUs
                if (waitedUs < UNSYNCED_WAIT_US) {
                    try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                    continue
                }
                val chunk = queue.poll() ?: continue
                synchronized(lock) { queueBytesCount -= chunk.pcmData.size }
                writeToTrack(track, chunk.pcmData)
                if (!isTrackPlaying()) try { track.play() } catch (_: Exception) {}
                continue
            }
            unsyncedWaitSinceUs = 0L

            val chunk = head
            // 目标出声时刻 = 时间戳 - 手动提前量（MA 静态延迟）
            val chunkTargetUs = clockFilter.serverToLocalUs(chunk.timestampUs) - outputDelayUs
            // 锚定时还要再提前设备管线延迟，补偿 play()→出声 的间隔
            val chunkLocalUs = chunkTargetUs - pipelineLatencyUs
            val durationUs = chunk.pcmData.size * 1_000_000L / (sampleRate * 4L)
            val nowUs = nowNanos / 1000L

            when (playState) {
                PlayState.ANCHORING -> {
                    // 严格丢弃已过期的块：锚点必须精确等于头块目标时刻，
                    // 否则 FIFO 时间轴起点早于锚点 → err 永久偏移 → 每秒重锚 + 大量丢块。
                    if (chunkLocalUs < nowUs) {
                        val stale = queue.poll() ?: continue
                        synchronized(lock) { queueBytesCount -= stale.pcmData.size }
                        droppedLate++
                        // 保护：连续丢弃过多说明时间轴对不上（offset 异常），
                        // 强行以当前时刻为锚开播——宁可相位不准，也必须出声。
                        consecutiveDrops++
                        if (consecutiveDrops > 40) {
                            consecutiveDrops = 0
                            droppedLate = 0
                            if (isTrackPlaying()) {
                                try { track.pause(); track.flush() } catch (_: Exception) {}
                                framesWritten = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                            }
                            anchorPlayAtUs = maxOf(nowUs, 1L)
                            Log.w(TAG, "时间轴失配，强制以当前时刻开播")
                        }
                        continue
                    }
                    consecutiveDrops = 0
                    if (anchorPlayAtUs == 0L) {
                        // 从 ROLLING 掉回锚定：先停掉播放，避免 FIFO 旧音频立刻出声
                        if (isTrackPlaying()) {
                            try { track.pause(); track.flush() } catch (_: Exception) {}
                            // flush 后把基准贴到 playbackHead，保证在飞量立刻为 0
                            framesWritten = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                        }
                        anchorPlayAtUs = chunkLocalUs
                    }
                    if (nowUs < anchorPlayAtUs) {
                        // 未到锚定时刻：把音频写进（暂停中的）FIFO 攒 cushion
                        if (inFlightUs + durationUs <= TRACK_BUFFER_US) {
                            queue.poll()
                            synchronized(lock) { queueBytesCount -= chunk.pcmData.size }
                            writeToTrack(track, chunk.pcmData)
                            lastEndLagUs = 0L
                            continue
                        }
                        val sleepUs = minOf(anchorPlayAtUs - nowUs, 10_000L)
                        try { Thread.sleep(sleepUs / 1000L, ((sleepUs % 1000L) * 1000L).toInt()) }
                        catch (e: InterruptedException) { break }
                        continue
                    }
                    // 到锚定时刻：启动播放，第一块从此刻（+设备管线延迟）开始出声
                    if (!isTrackPlaying()) {
                        playStartNanos = System.nanoTime()
                        rollingSinceNanos = playStartNanos
                        try { track.play() } catch (e: Exception) { Log.w(TAG, "play 失败", e) }
                    }
                    lastErrUs = 0L
                    playState = PlayState.ROLLING
                    continue
                }

                PlayState.ROLLING -> {
                    val leadUs = chunkLocalUs - nowUs
                    // ---- 设备管线延迟实测 ----
                    // 首帧呈现时刻 P0 = nanoTime − framePosition/sr；
                    // D = P0 − play() 调用时刻。测量到就校正锚点并重锚一次。
                    if (playStartNanos > 0L && calibrationsLeft > 0 && playedFrames > 0) {
                        val p0Nanos = tsNanos - playedFrames * 1_000_000_000L / sampleRate
                        val dUs = (p0Nanos - playStartNanos) / 1000L
                        if (dUs in -20_000L..600_000L) {
                            calibrationsLeft--
                            pipelineLatencyUs = if (pipelineLatencyUs == 0L) dUs
                                                else (pipelineLatencyUs * 3 + dUs) / 4
                            playStartNanos = 0L
                            Log.i(TAG, "实测管线延迟: ${dUs / 1000}ms（累计 ${pipelineLatencyUs / 1000}ms）")
                            // 不立刻重锚（那会 flush 掉缓冲、产生一次跳音），
                            // 校正量留给下一次自然锚定（换流/欠载/超差）生效。
                            continue
                        }
                    }

                    // err 在 FIFO 流动中是常量（写一块 +dur，下一块的 lead 也 +dur），
                    // 只有锚定/flush 能改变它。所以这里只做"超差 → 重锚"。
                    val errUs = inFlightUs - leadUs
                    if (panicIfTooManyResyncs()) {
                        if (inFlightUs + durationUs > TRACK_BUFFER_US) {
                            try { Thread.sleep(durationUs.coerceIn(5_000L, 30_000L) / 1000L) }
                            catch (e: InterruptedException) { break }
                            continue
                        }
                        queue.poll()
                        synchronized(lock) { queueBytesCount -= chunk.pcmData.size }
                        lastErrUs = errUs
                        writeToTrack(track, chunk.pcmData)
                        continue
                    }
                    val t = System.nanoTime()
                    val settled = t - rollingSinceNanos > 1_500_000_000L
                    if (settled && kotlin.math.abs(errUs) > RESYNC_THRESHOLD_US) {
                        if (t - lastHardSyncNanos > 2_000_000_000L) {
                            lastHardSyncNanos = t
                            hardSyncs++
                            lastErrUs = errUs
                            playState = PlayState.ANCHORING
                            anchorPlayAtUs = 0L
                            continue
                        }
                    }
                    // 缓冲余量（正常 1x 流动下不会触发）
                    if (inFlightUs + durationUs > TRACK_BUFFER_US) {
                        try { Thread.sleep(durationUs.coerceIn(5_000L, 30_000L) / 1000L) }
                        catch (e: InterruptedException) { break }
                        continue
                    }
                    // 到货即写：水位由服务端 1x 推送维持，出声时刻由锚点延续
                    queue.poll()
                    synchronized(lock) { queueBytesCount -= chunk.pcmData.size }
                    lastErrUs = errUs
                    lastEndLagUs = errUs
                    writeToTrack(track, chunk.pcmData)
                }
            }
        }
    }

    private fun isTrackPlaying(): Boolean = try {
        audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
    } catch (_: Exception) { false }

    /** 上次硬同步时刻（纳秒，System.nanoTime 基），限流用 */
    @Volatile
    private var lastHardSyncNanos = 0L

    /** 硬同步次数（清空硬件缓冲重对齐），诊断用 */
    @Volatile
    private var hardSyncs = 0L

    /** 最近一块的播出误差（微秒，>0 = 比时间戳晚出声），诊断与 MA 上报用 */
    @Volatile
    private var lastErrUs = 0L

    /** 末端相对时间戳的滞后（≈一整块时长 = 正常值），诊断用 */
    @Volatile
    private var lastEndLagUs = 0L

    fun measuredErrMs(): Int = (lastErrUs / 1000L).toInt()

    fun measuredEndLagMs(): Int = (lastEndLagUs / 1000L).toInt()

    /** AudioTrack 内已写入但尚未播出的音频时长（微秒）—— AudioTimestamp 不可用时的退化估计 */
    private fun queuedPlaybackUs(track: AudioTrack, sampleRate: Int): Long {
        val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val queuedFrames = framesWritten - playedFrames
        if (queuedFrames <= 0L || queuedFrames > sampleRate.toLong() * 60L) return 0L
        return queuedFrames * 1_000_000L / sampleRate
    }

    /** 保险：单条流内硬同步次数上限，超过就退化为"顺序播放"（宁可不同步也要有声） */
    private fun panicIfTooManyResyncs(): Boolean {
        if (hardSyncs <= 20L) return false
        if (!panicked) {
            panicked = true
            Log.w(TAG, "硬同步次数过多（$hardSyncs），退化为顺序播放")
        }
        return true
    }

    @Volatile
    private var panicked = false

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
