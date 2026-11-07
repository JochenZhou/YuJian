package com.zqc.tvnotify

/**
 * Sendspin 协议数据模型与时钟同步滤波器。
 */

data class SendspinPlayerFormat(
    val codec: String = "pcm",
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val bitDepth: Int = 16
)

data class SendspinTrackMetadata(
    val timestampUs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val artworkUrl: String = "",
    val year: Int = 0,
    val track: Int = 0,
    val hasProgress: Boolean = false,
    val trackProgressMs: Long = 0L,
    val trackDurationMs: Long = 0L,
    val playbackSpeed: Int = 0,
    val embeddedLyrics: String = ""
)

data class SendspinControllerState(
    val supportedCommands: Set<String> = emptySet(),
    val volume: Int = 100,
    val muted: Boolean = false,
    val repeat: String = "off",
    val shuffle: Boolean = false,
    val seekMaxMs: Long = 0L
)

data class SendspinColorPalette(
    val timestampUs: Long = 0L,
    val backgroundDarkRgb: Int? = null,
    val primaryRgb: Int? = null,
    val accentRgb: Int? = null
)

data class SendspinSnapshot(
    val enabled: Boolean,
    val autoPopup: Boolean,
    val connected: Boolean,
    val playing: Boolean,
    val boundPort: Int,
    val serverName: String,
    val serverUrl: String,
    val clientName: String,
    val clientId: String,
    val pairingToken: String,
    val format: SendspinPlayerFormat?,
    val metadata: SendspinTrackMetadata,
    val controller: SendspinControllerState,
    val playerVolume: Int,
    val playerMuted: Boolean,
    val rttMs: Long,
    val synced: Boolean,
    val colorPalette: SendspinColorPalette?,
    val discoveredServers: List<String> = emptyList()
)

/**
 * 双向时钟同步滤波器：
 * 实现 Sendspin 规范中的高精度时钟对齐，将服务端微秒时钟 serverTimeUs
 * 映射至本地 System.nanoTime() / 1000L 微秒时钟，同时具备 Wi-Fi 抖动尖峰抑制与
 * 64 位防溢出曲目进度推算。
 */
class SendspinClockFilter {

    @Volatile
    var isSynced: Boolean = false
        private set

    @Volatile
    var minRttUs: Long = 0L
        private set

    @Volatile
    private var offsetUs: Long = 0L

    private data class TimeSample(val rttUs: Long, val offsetUs: Long)
    private val samples = ArrayList<TimeSample>(16)
    private val lock = Any()

    fun reset() {
        synchronized(lock) {
            isSynced = false
            minRttUs = 0L
            offsetUs = 0L
            samples.clear()
        }
    }

    /**
     * 处理一次 client/time <-> server/time 往返测量：
     * clientTxUs: 客户端发送时刻（本地微秒）
     * serverRxUs: 服务端接收时刻（服务端微秒）
     * serverTxUs: 服务端发送时刻（服务端微秒）
     * clientRxUs: 客户端收到回复时刻（本地微秒）
     */
    fun onTimeExchange(clientTxUs: Long, serverRxUs: Long, serverTxUs: Long, clientRxUs: Long) {
        val d1 = serverRxUs - clientTxUs
        val d2 = serverTxUs - clientRxUs
        val sampleOffset = (d1 / 2L) + (d2 / 2L) + ((d1 % 2L + d2 % 2L) / 2L)
        val rtt = ((clientRxUs - clientTxUs) - (serverTxUs - serverRxUs)).coerceAtLeast(0L)

        synchronized(lock) {
            if (isSynced && rtt > (minRttUs * 3L + 10_000L)) {
                // 忽略非对称 Wi-Fi 延迟抖动尖峰
                return
            }
            if (samples.size >= 16) {
                samples.removeAt(0)
            }
            samples.add(TimeSample(rtt, sampleOffset))

            if (!isSynced) {
                offsetUs = sampleOffset
                minRttUs = rtt
                isSynced = true
            } else {
                if (rtt < minRttUs) {
                    minRttUs = rtt
                }
                // 指数移动加权平滑 (alpha = 0.25)
                offsetUs = (offsetUs * 3L + sampleOffset) / 4L
            }
        }
    }

    /** 将服务端微秒时钟映射为本地微秒单调时钟 */
    fun serverToLocalUs(serverTimeUs: Long): Long {
        return serverTimeUs - offsetUs
    }

    /** 将本地微秒单调时钟反解为服务端微秒时钟 */
    fun localToServerUs(localTimeUs: Long): Long {
        return localTimeUs + offsetUs
    }

    /**
     * 根据当前元数据与本地时刻推算曲目当前播放毫秒进度，
     * 自动处理跨数小时大数值防溢出与时长边界截断。
     */
    fun currentTrackPositionMs(meta: SendspinTrackMetadata, nowLocalUs: Long): Long {
        if (!meta.hasProgress) {
            return 0L
        }
        if (meta.playbackSpeed == 0 || !isSynced) {
            return meta.trackProgressMs
        }
        val nowServerUs = localToServerUs(nowLocalUs)
        val deltaUs = nowServerUs - meta.timestampUs
        val speed = meta.playbackSpeed.toLong()
        val progressDeltaMs = (deltaUs / 1_000_000L) * speed + ((deltaUs % 1_000_000L) * speed) / 1_000_000L
        val calculated = meta.trackProgressMs + progressDeltaMs
        return if (meta.trackDurationMs > 0L) {
            calculated.coerceIn(0L, meta.trackDurationMs)
        } else {
            calculated.coerceAtLeast(0L)
        }
    }
}
