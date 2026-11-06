package com.zqc.tvnotify

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 横幅分级。未知值统一按 [INFO] 处理，避免推送方拼错字段就整条失败。 */
enum class BannerLevel {
    INFO,
    WARN,
    ERROR;

    companion object {
        fun fromRaw(raw: String?): BannerLevel = when (raw?.trim()?.lowercase()) {
            "warn" -> WARN
            "error" -> ERROR
            else -> INFO
        }
    }
}

/** 横幅展示方位。支持顶部居中、底部居中及四角画中画 (PiP) 模式。 */
enum class BannerPosition(val raw: String) {
    TOP("top"),
    BOTTOM("bottom"),
    TOP_RIGHT("top-right"),
    TOP_LEFT("top-left"),
    BOTTOM_RIGHT("bottom-right"),
    BOTTOM_LEFT("bottom-left");

    val isTop: Boolean
        get() = this == TOP || this == TOP_RIGHT || this == TOP_LEFT

    val isPip: Boolean
        get() = this == TOP_RIGHT || this == TOP_LEFT || this == BOTTOM_RIGHT || this == BOTTOM_LEFT

    companion object {
        fun fromRaw(raw: String?, fallback: BannerPosition = TOP): BannerPosition {
            val normalized = raw?.trim()?.lowercase()?.replace("_", "-") ?: return fallback
            return entries.firstOrNull { it.raw == normalized } ?: fallback
        }
    }
}

/**
 * 一条待展示的横幅契约。
 */
data class BannerRequest(
    val title: String = "",
    val text: String = "",
    val durationSeconds: Int = DEFAULT_DURATION_SEC,
    val level: BannerLevel = BannerLevel.INFO,
    val sticky: Boolean = false,
    val maxDurationSeconds: Int = DEFAULT_MAX_DURATION_SEC,
    val sound: Boolean = true,
    val imageUrl: String? = null,
    val imageBase64: String? = null,
    val videoUrl: String? = null,
    /** 内置抽帧动画：直接读 assets/testframes 循环播放，不依赖任何网络/服务 */
    val assetFrames: Boolean = false,
    /** 可选通知唯一标识：若当前展示的横幅具相同 id，则原地平滑更新文案/倒计时，无重新入场闪烁 */
    val id: String? = null,
    /** 指定展示方位，空则继承电视全局默认方位 */
    val position: BannerPosition? = null,
    /** 原生 TTS 语音播报文本；传 "true" 时自动朗读标题与正文 */
    val tts: String? = null,
    /** 静态快照定时刷新间隔（毫秒），大于 0 时自动启动低开销准视频流模式 */
    val imageIntervalMs: Int = 0,
    /** 是否开启排队展示：true 时若当前已有横幅在展示，则进入队列顺延播放，不抢占顶替 */
    val queue: Boolean = false,
    /** 可选单次通知主题覆盖：非空时本次横幅配色、图标与提示音均按该主题呈现 */
    val theme: Theme? = null
) {
    val hasMedia: Boolean
        get() = assetFrames || !videoUrl.isNullOrBlank() || !imageUrl.isNullOrBlank() ||
                !imageBase64.isNullOrBlank()

    companion object {
        const val DEFAULT_DURATION_SEC = 5

        /** sticky 的默认兜底时长（5 分钟）：没有它，推送方崩溃后横幅会永久占屏 */
        const val DEFAULT_MAX_DURATION_SEC = 300
        const val MIN_MAX_DURATION_SEC = 30
        const val MAX_MAX_DURATION_SEC = 3600
    }
}

/**
 * tvOS 风格与仙侠传音玉简横幅控制器：用 WindowManager 悬浮窗自绘。
 */
class BannerController(private val context: Context) {

    companion object {
        private const val TAG = "TVNotifyBanner"

        private const val ENTER_DURATION_MS = 280L
        private const val EXIT_DURATION_MS = 200L
        private const val MIN_DURATION_SEC = 1
        private const val MAX_DURATION_SEC = 30
        private const val MIN_MAX_DURATION_SEC = BannerRequest.MIN_MAX_DURATION_SEC
        private const val MAX_MAX_DURATION_SEC = BannerRequest.MAX_MAX_DURATION_SEC

        /** 屏宽占比：正文居中宽卡片 vs 四角画中画紧凑卡片 */
        private const val BANNER_WIDTH_RATIO = 0.65f
        private const val BANNER_WIDTH_RATIO_WITH_MEDIA = 0.78f
        private const val BANNER_PIP_WIDTH_RATIO = 0.42f
        private const val BANNER_PIP_WIDTH_RATIO_WITH_MEDIA = 0.48f

        private const val BANNER_MARGIN_DP = 28f
        private const val FALLBACK_HEIGHT_DP = 76f

        /** sticky 模式下 KEEP_SCREEN_ON 的保留窗口：超时后撤销，避免电视永不熄屏 */
        private const val KEEP_SCREEN_ON_WINDOW_MS = 60_000L

        /** 等待主线程完成「读状态 + 移除视图」的上限，超时按「无横幅」作答 */
        private const val DISMISS_WAIT_MS = 500L

        internal fun looksLikeJpeg(bytes: ByteArray): Boolean =
            bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable { dismissAnimated() }
    private val clearKeepScreenOnRunnable = Runnable { clearKeepScreenOn() }

    @Volatile
    private var bannerView: View? = null
    private var bannerParams: WindowManager.LayoutParams? = null
    private var currentAnimator: Animator? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerResId: Int = 0
    private var activeMediaSession: MediaSession? = null

    /** 媒体区看门狗 */
    private var mediaWatchdog: Runnable? = null

    // 状态与原地刷新缓存
    private var currentRequestId: String? = null
    private var currentPosition: BannerPosition = BannerPosition.TOP
    private var currentTitleView: TextView? = null
    private var currentBodyView: TextView? = null
    private var currentBadgeView: TextView? = null
    private var currentContainerView: LinearLayout? = null
    private var currentIconView: View? = null

    // 轻量级 FIFO 排队队列（上限 5 条）
    private val requestQueue = ArrayDeque<BannerRequest>()

    // 原生 TTS 与云端 MP3 兜底语音引擎（兼容小米电视 Android 6.0 等精简掉了中文 TTS 的固件）
    private var ttsEngine: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var ttsNativeChineseSupported: Boolean = false
    private var ttsInitDone: Boolean = false
    private var ttsInitInProgress: Boolean = false
    private var pendingTtsText: String? = null
    private var pendingTtsRunnable: Runnable? = null
    private var ttsInitTimeoutRunnable: Runnable? = null
    private var ttsMediaPlayer: MediaPlayer? = null
    private var ttsWorkerThread: Thread? = null

    fun isShowing(): Boolean = bannerView != null

    /**
     * 展示横幅。可在任意线程调用，内部统一切主线程。
     */
    fun show(request: BannerRequest) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(request) }
            return
        }
        if (!canDrawOverlay()) {
            Log.w(TAG, "缺少 SYSTEM_ALERT_WINDOW 权限，横幅被丢弃")
            return
        }

        // 1. 若开启排队模式且当前正在显示横幅，且不是同 ID 刷新，则排队顺延
        if (request.queue && isShowing() && (request.id.isNullOrBlank() || request.id != currentRequestId)) {
            if (requestQueue.size >= 5) {
                requestQueue.removeFirst()
            }
            requestQueue.addLast(request)
            Log.i(TAG, "横幅排队顺延，当前队列深度: ${requestQueue.size}")
            return
        }

        // 2. 同 ID 原地刷新（无闪烁更新文字、倒计时与级别颜色）
        if (!request.id.isNullOrBlank() && request.id == currentRequestId && bannerView != null) {
            updateBannerInPlace(request)
            return
        }

        // 3. 正常展示新横幅（抢占清理上一条）
        removeBannerNow()

        val targetPosition = request.position ?: BannerPosition.fromRaw(Prefs.getBannerPosition(context))
        val targetTheme = request.theme ?: Theme.current(context)
        currentPosition = targetPosition
        currentRequestId = request.id

        val view = buildBannerView(request, targetPosition, targetTheme)
        val params = buildLayoutParams(targetPosition, request.hasMedia)

        view.alpha = 0f
        view.translationY = if (targetPosition.isTop) -dp(FALLBACK_HEIGHT_DP).toFloat() else dp(FALLBACK_HEIGHT_DP).toFloat()

        if (!addViewSafely(view, params)) {
            activeMediaSession?.stop()
            activeMediaSession = null
            return
        }
        bannerView = view
        bannerParams = params

        activeMediaSession?.start()

        if (request.sound) {
            playSound(targetTheme)
        }
        speakTtsIfNeeded(request)

        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                v.removeOnLayoutChangeListener(this)
                if (bannerView === v) {
                    playEnterAnimation(v, targetPosition)
                }
            }
        })

        scheduleAutoHide(request)
    }

    /**
     * 同 ID 原地平滑更新（无重入动画，不重置悬浮窗）
     */
    private fun updateBannerInPlace(request: BannerRequest) {
        val theme = request.theme ?: Theme.current(context)
        val trimmedTitle = request.title.trim()
        val trimmedText = request.text.trim()

        currentTitleView?.text = if (trimmedTitle.isNotEmpty()) trimmedTitle else theme.defaultBannerTitle
        currentBodyView?.let { body ->
            if (trimmedText.isNotEmpty()) {
                body.text = trimmedText
                body.visibility = View.VISIBLE
            } else {
                body.visibility = View.GONE
            }
        }

        val badgeColor = theme.bannerIconColor(request.level)
        currentBadgeView?.apply {
            text = theme.levelBadgeText(request.level)
            setTextColor(badgeColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f).toFloat()
                setColor(Color.argb(0x2A, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
                setStroke(dp(1f), Color.argb(0x66, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
            }
        }

        currentContainerView?.background = buildBannerBackground(theme, request.level)

        if (request.sound) {
            playSound(theme)
        }
        speakTtsIfNeeded(request)
        scheduleAutoHide(request)
    }

    /** 立即移除（无动画），用于 Activity / Service 退出等场景。 */
    fun dismissImmediately() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissImmediately() }
            return
        }
        requestQueue.clear()
        stopActiveTts()
        removeBannerNow()
    }

    /** 供 `POST /{token}/dismiss` 调用：关闭横幅并回答「调用前是否真的有横幅」。 */
    fun dismissWithResult(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val wasShowing = isShowing()
            requestQueue.clear()
            stopActiveTts()
            dismissAnimated()
            return wasShowing
        }
        val latch = CountDownLatch(1)
        val wasShowing = AtomicBoolean(false)
        val posted = mainHandler.post {
            try {
                wasShowing.set(isShowing())
                requestQueue.clear()
                stopActiveTts()
                dismissAnimated()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) {
            return false
        }
        return try {
            if (latch.await(DISMISS_WAIT_MS, TimeUnit.MILLISECONDS)) wasShowing.get() else false
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** 释放资源，Service / Activity 销毁时调用。 */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { release() }
            return
        }
        requestQueue.clear()
        removeBannerNow()
        releaseMediaPlayer()
        releaseTts()
    }

    // ------------------------------------------------------------------
    // 语音播报（灵音传诵）：系统原生 TTS + 云端 MP3 兜底（完美兼容小米电视 Android 6.0）
    // ------------------------------------------------------------------

    private fun speakTtsIfNeeded(request: BannerRequest) {
        val ttsVal = request.tts?.trim() ?: return
        if (ttsVal.isEmpty() || Prefs.isMuted(context)) return

        pendingTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTtsRunnable = null

        // 若传入直接的 HTTP(S) 音频流 URL（例如 Home Assistant 合成好的 TTS 音频地址）
        if (ttsVal.startsWith("http://", ignoreCase = true) || ttsVal.startsWith("https://", ignoreCase = true)) {
            val delayMs = if (request.sound) 350L else 0L
            val runnable = Runnable {
                pendingTtsRunnable = null
                if (!Prefs.isMuted(context)) {
                    playCloudAudioFromUrls(listOf(ttsVal), "direct-url")
                }
            }
            pendingTtsRunnable = runnable
            if (delayMs > 0L) mainHandler.postDelayed(runnable, delayMs) else runnable.run()
            return
        }

        val textToSpeak = if (ttsVal.equals("true", ignoreCase = true) || ttsVal == "1") {
            val title = request.title.trim()
            val body = request.text.trim()
            when {
                title.isNotEmpty() && body.isNotEmpty() -> "$title。$body"
                title.isNotEmpty() -> title
                else -> body
            }
        } else {
            ttsVal
        }

        if (textToSpeak.isBlank()) return

        // 当同时开启提示音时，延迟 350ms 播报语音，避免老电视（Android 6.0）音频混音器抢占吞字
        val delayMs = if (request.sound) 350L else 0L
        val runnable = Runnable {
            pendingTtsRunnable = null
            dispatchTtsText(textToSpeak)
        }
        pendingTtsRunnable = runnable
        if (delayMs > 0L) mainHandler.postDelayed(runnable, delayMs) else runnable.run()
    }

    private fun dispatchTtsText(textToSpeak: String) {
        if (Prefs.isMuted(context)) return
        if (ttsInitDone) {
            if (ttsReady && ttsNativeChineseSupported && ttsEngine != null) {
                if (!speakWithSystemTts(textToSpeak)) {
                    speakWithCloudTts(textToSpeak)
                }
            } else {
                speakWithCloudTts(textToSpeak)
            }
            return
        }
        pendingTtsText = textToSpeak
        if (!ttsInitInProgress) {
            initTts()
        }
    }

    private fun initTts() {
        if (ttsInitDone || ttsInitInProgress) return
        ttsInitInProgress = true

        // 守护超时：部分精简版电视固件（如小米电视 Android 6.0）删除了 TTS 服务，bindService 可能不回调
        val timeout = Runnable {
            if (ttsInitInProgress) {
                Log.w(TAG, "系统 TextToSpeech 初始化超时(1200ms)，自动切换为云端灵音引擎")
                ttsInitInProgress = false
                ttsInitDone = true
                ttsReady = false
                ttsNativeChineseSupported = false
                pendingTtsText?.let {
                    pendingTtsText = null
                    speakWithCloudTts(it)
                }
            }
        }
        ttsInitTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, 1200L)

        try {
            ttsEngine = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post {
                    ttsInitTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                    ttsInitTimeoutRunnable = null
                    if (!ttsInitInProgress && ttsInitDone) return@post
                    ttsInitInProgress = false
                    ttsInitDone = true

                    var chineseOk = false
                    if (status == TextToSpeech.SUCCESS) {
                        ttsEngine?.let { engine ->
                            try {
                                val candidates = listOf(
                                    Locale.SIMPLIFIED_CHINESE,
                                    Locale.CHINESE,
                                    Locale("zh", "CN")
                                )
                                for (loc in candidates) {
                                    val res = engine.setLanguage(loc)
                                    if (res >= TextToSpeech.LANG_AVAILABLE) {
                                        chineseOk = true
                                        break
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "检测系统 TTS 中文语言包异常", e)
                            }
                        }
                    }

                    ttsReady = (status == TextToSpeech.SUCCESS)
                    ttsNativeChineseSupported = chineseOk
                    Log.i(TAG, "系统 TTS 初始化完成: status=$status, nativeChinese=$chineseOk")

                    pendingTtsText?.let { text ->
                        pendingTtsText = null
                        if (ttsReady && ttsNativeChineseSupported) {
                            if (!speakWithSystemTts(text)) {
                                speakWithCloudTts(text)
                            }
                        } else {
                            speakWithCloudTts(text)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            ttsInitTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            ttsInitTimeoutRunnable = null
            ttsInitInProgress = false
            ttsInitDone = true
            ttsReady = false
            ttsNativeChineseSupported = false
            Log.w(TAG, "初始化系统 TextToSpeech 异常，启用云端语音兜底", e)
            pendingTtsText?.let {
                pendingTtsText = null
                speakWithCloudTts(it)
            }
        }
    }

    private fun speakWithSystemTts(text: String): Boolean {
        if (Prefs.isMuted(context)) return true
        val engine = ttsEngine ?: return false
        return try {
            stopCloudTtsPlayer()
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tvnotify_tts")
            } else {
                @Suppress("DEPRECATION")
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
            if (result == TextToSpeech.ERROR) {
                Log.w(TAG, "系统 TTS speak 返回 ERROR，回退云端语音")
                ttsNativeChineseSupported = false
                false
            } else {
                true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "系统 TTS 播报异常，回退云端语音", e)
            ttsNativeChineseSupported = false
            false
        }
    }

    /**
     * 云端 MP3 语音合成兜底：
     * 针对小米电视 Android 6.0 等移除了中文 TTS 引擎的电视 ROM，自动拉取轻量 MP3 并通过 FileDescriptor 播放。
     * 若网络不通或接口失效，静默放弃，绝对不影响横幅与系统的正常展示与运行。
     */
    private fun speakWithCloudTts(text: String) {
        if (Prefs.isMuted(context)) return
        val clean = text.trim().replace(Regex("\\s+"), " ").take(220)
        if (clean.isEmpty()) return
        val encoded = try {
            URLEncoder.encode(clean, "UTF-8")
        } catch (_: Exception) {
            return
        }
        val urls = listOf(
            "https://fanyi.baidu.com/gettts?lan=zh&text=$encoded&spd=5&source=web"
        )
        playCloudAudioFromUrls(urls, clean.take(24))
    }

    private fun playCloudAudioFromUrls(urls: List<String>, tagHint: String) {
        ttsWorkerThread?.interrupt()
        ttsWorkerThread = Thread({
            var audioBytes: ByteArray? = null
            for (url in urls) {
                if (Thread.currentThread().isInterrupted) return@Thread
                try {
                    val bytes = downloadTtsAudioBytes(url)
                    if (bytes != null && isValidAudioPayload(bytes)) {
                        audioBytes = bytes
                        break
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "云端 TTS 节点拉取失败 ($url): ${e.message}")
                }
            }
            val validBytes = audioBytes
            if (validBytes == null || Thread.currentThread().isInterrupted) {
                Log.w(TAG, "所有云端 TTS 节点均未能获取有效音频: $tagHint")
                return@Thread
            }
            try {
                val cacheFile = File(context.cacheDir, "yujian_tts_voice.mp3")
                FileOutputStream(cacheFile).use { fos ->
                    fos.write(validBytes)
                    fos.flush()
                }
                mainHandler.post {
                    if (!Prefs.isMuted(context)) {
                        playLocalTtsFile(cacheFile)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "写入或播放云端 TTS 缓存文件失败", e)
            }
        }, "yujian-cloud-tts").apply {
            isDaemon = true
            start()
        }
    }

    private fun downloadTtsAudioBytes(urlStr: String): ByteArray? {
        val conn = SafeNetworkHelper.openConnection(
            urlStr = urlStr,
            connectTimeoutMs = 3500,
            readTimeoutMs = 5000,
            userAgent = "Mozilla/5.0 (Linux; Android 6.0; MiTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36",
            acceptHeader = "audio/mpeg, audio/*, */*"
        )
        return try {
            conn.connect()
            if (conn.responseCode != 200) {
                Log.w(TAG, "云端 TTS HTTP ${conn.responseCode}: $urlStr")
                return null
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                val maxBytes = 2 * 1024 * 1024
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) break
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    /** 过滤掉部分接口报错时返回的 HTML (`<...`) 或 JSON (`{...`) 文本 */
    private fun isValidAudioPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 256) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == '<'.code || b0 == '{'.code) return false
        // MP3 Sync Word (0xFF 0xE0..0xFF), ID3v2 ("ID3"), WAV ("RIFF"), OGG ("OggS")
        return (b0 == 0xFF && (b1 and 0xE0) == 0xE0) ||
                (b0 == 'I'.code && b1 == 'D'.code) ||
                (b0 == 'R'.code && b1 == 'I'.code) ||
                (b0 == 'O'.code && b1 == 'g'.code) ||
                bytes.size > 1024
    }

    @Suppress("DEPRECATION")
    private fun playLocalTtsFile(file: File) {
        stopCloudTtsPlayer()
        val player = MediaPlayer()
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            // 关键：Android 6.0 mediaserver 进程无权直接按路径打开应用私有 cacheDir，必须通过 FileDescriptor 传递句柄
            FileInputStream(file).use { fis ->
                player.setDataSource(fis.fd)
            }
            player.setVolume(1.0f, 1.0f)
            player.setOnCompletionListener { mp ->
                if (ttsMediaPlayer === mp) {
                    ttsMediaPlayer = null
                }
                try { mp.release() } catch (_: Exception) {}
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "云端 TTS MediaPlayer 播放错误: what=$what extra=$extra")
                if (ttsMediaPlayer === mp) {
                    ttsMediaPlayer = null
                }
                try { mp.release() } catch (_: Exception) {}
                true
            }
            player.prepare()
            player.start()
            ttsMediaPlayer = player
            Log.i(TAG, "云端 TTS 语音开始播放 (${file.length()} bytes)")
        } catch (e: Throwable) {
            Log.w(TAG, "播放本地 TTS 音频失败", e)
            try { player.release() } catch (_: Exception) {}
        }
    }

    private fun stopActiveTts() {
        pendingTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTtsRunnable = null
        ttsWorkerThread?.interrupt()
        ttsWorkerThread = null
        stopCloudTtsPlayer()
        try {
            ttsEngine?.stop()
        } catch (_: Exception) {}
    }

    private fun stopCloudTtsPlayer() {
        val player = ttsMediaPlayer ?: return
        ttsMediaPlayer = null
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (_: Exception) {}
        try {
            player.release()
        } catch (_: Exception) {}
    }

    private fun releaseTts() {
        pendingTtsRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingTtsRunnable = null
        ttsInitTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        ttsInitTimeoutRunnable = null
        ttsWorkerThread?.interrupt()
        ttsWorkerThread = null
        stopCloudTtsPlayer()
        try {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        } catch (_: Exception) {}
        ttsEngine = null
        ttsReady = false
        ttsNativeChineseSupported = false
        ttsInitDone = false
        ttsInitInProgress = false
        pendingTtsText = null
    }

    // ------------------------------------------------------------------
    // 计时：普通 / sticky
    // ------------------------------------------------------------------

    private fun scheduleAutoHide(request: BannerRequest) {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        if (request.sticky) {
            val maxSeconds = request.maxDurationSeconds
                .coerceIn(MIN_MAX_DURATION_SEC, MAX_MAX_DURATION_SEC)
            mainHandler.postDelayed(hideRunnable, maxSeconds * 1000L)
            mainHandler.postDelayed(clearKeepScreenOnRunnable, KEEP_SCREEN_ON_WINDOW_MS)
        } else {
            val minForTts = if (!request.tts.isNullOrBlank()) 7 else MIN_DURATION_SEC
            val seconds = request.durationSeconds.coerceAtLeast(minForTts).coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
            mainHandler.postDelayed(hideRunnable, seconds * 1000L)
        }
    }

    private fun clearKeepScreenOn() {
        val view = bannerView ?: return
        val params = bannerParams ?: return
        try {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            windowManager.updateViewLayout(view, params)
            Log.i(TAG, "sticky 横幅已撤销 KEEP_SCREEN_ON，电视可正常熄屏")
        } catch (e: Exception) {
            Log.w(TAG, "撤销 KEEP_SCREEN_ON 失败", e)
        }
    }

    // ------------------------------------------------------------------
    // 提示音（内置 R.raw 与 6 款主题 PCM/WAV 声学合成器）
    // ------------------------------------------------------------------

    private fun playSound(theme: Theme = Theme.current(context)) {
        if (Prefs.isMuted(context)) {
            Log.d(TAG, "闭关免扰模式生效，静默不播放提示音")
            return
        }

        // 每次重新创建提示音实例播放，播放完成或错误立即释放；
        // 彻底解决 Android 6.0 (小米电视等) 上长期持有一个 MediaPlayer 进行 pause/seekTo(0) 重放时
        // 底层 AudioTrack / mediaserver 偶发挂死或状态机报错导致后续无提示音的顽疾
        val player = createThemeMediaPlayer(theme) ?: return

        releaseMediaPlayer()
        mediaPlayer = player

        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
            player.setOnCompletionListener { mp ->
                if (mediaPlayer === mp) {
                    mediaPlayer = null
                    mediaPlayerResId = 0
                }
                try { mp.release() } catch (_: Exception) {}
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.w(TAG, "提示音播放错误 what=$what extra=$extra")
                if (mediaPlayer === mp) {
                    mediaPlayer = null
                    mediaPlayerResId = 0
                }
                try { mp.release() } catch (_: Exception) {}
                true
            }
            player.start()
        } catch (e: Exception) {
            Log.w(TAG, "提示音播放失败", e)
            if (mediaPlayer === player) {
                mediaPlayer = null
                mediaPlayerResId = 0
            }
            try { player.release() } catch (_: Exception) {}
        }
    }

    private fun createThemeMediaPlayer(theme: Theme): MediaPlayer? {
        if (theme != Theme.XIUXIAN && theme != Theme.DEFAULT) {
            val wavFile = ThemeSoundSynth.getOrGenerateWav(context, theme)
            if (wavFile != null && wavFile.exists()) {
                val synthPlayer = MediaPlayer()
                try {
                    synthPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
                    FileInputStream(wavFile).use { fis ->
                        synthPlayer.setDataSource(fis.fd)
                    }
                    synthPlayer.prepare()
                    return synthPlayer
                } catch (e: Exception) {
                    Log.w(TAG, "加载合成主题音效失败 (${theme.storageId})，回退至内置音效", e)
                    try { synthPlayer.release() } catch (_: Exception) {}
                }
            }
        }
        val resId = if (theme.isXianxia) R.raw.chord_xiuxian else R.raw.chord
        return try {
            MediaPlayer.create(context, resId)
        } catch (e: Exception) {
            Log.w(TAG, "创建提示音 MediaPlayer 失败", e)
            null
        }
    }

    private fun releaseMediaPlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        mediaPlayerResId = 0
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (_: Exception) {}
        try {
            player.release()
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // 动画：滑入 + 淡入 + 微缩放（支持上下不同方向）
    // ------------------------------------------------------------------

    private fun playEnterAnimation(view: View, position: BannerPosition) {
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val startY = if (position.isTop) {
            -(height + dp(BANNER_MARGIN_DP)).toFloat()
        } else {
            (height + dp(BANNER_MARGIN_DP)).toFloat()
        }
        view.translationY = startY
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        animate(view, startY, 0f, 0f, 1f, 0.94f, 1.0f, ENTER_DURATION_MS, null)
    }

    private fun dismissAnimated() {
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        activeMediaSession?.stop()
        activeMediaSession = null
        cancelMediaWatchdog()

        val view = bannerView ?: return
        val height = if (view.height > 0) view.height else dp(FALLBACK_HEIGHT_DP)
        val endY = if (currentPosition.isTop) {
            -(height + dp(BANNER_MARGIN_DP)).toFloat()
        } else {
            (height + dp(BANNER_MARGIN_DP)).toFloat()
        }
        animate(view, view.translationY, endY, view.alpha, 0f, view.scaleX, 0.95f, EXIT_DURATION_MS) {
            removeViewSafely(view)
            if (bannerView === view) {
                bannerView = null
                bannerParams = null
                currentRequestId = null
                currentTitleView = null
                currentBodyView = null
                currentBadgeView = null
                currentContainerView = null
                currentIconView = null
            }
            // 若队列有等待通知，衔接播放下一条
            if (requestQueue.isNotEmpty()) {
                val next = requestQueue.removeFirst()
                mainHandler.post { show(next) }
            }
        }
    }

    private fun animate(
        view: View,
        fromY: Float,
        toY: Float,
        fromAlpha: Float,
        toAlpha: Float,
        fromScale: Float,
        toScale: Float,
        durationMs: Long,
        onEnd: (() -> Unit)?
    ) {
        cancelAnimation()
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = durationMs
        animator.interpolator = DecelerateInterpolator(1.4f)
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            view.translationY = fromY + (toY - fromY) * fraction
            view.alpha = fromAlpha + (toAlpha - fromAlpha) * fraction
            val scale = fromScale + (toScale - fromScale) * fraction
            view.scaleX = scale
            view.scaleY = scale
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (currentAnimator === animation) {
                    currentAnimator = null
                }
                if (!cancelled) {
                    onEnd?.invoke()
                }
            }
        })
        currentAnimator = animator
        animator.start()
    }

    private fun cancelAnimation() {
        val animator = currentAnimator
        currentAnimator = null
        if (animator != null && animator.isRunning) {
            animator.cancel()
        }
    }

    // ------------------------------------------------------------------
    // 视图构建
    // ------------------------------------------------------------------

    private fun buildBannerView(request: BannerRequest, position: BannerPosition, theme: Theme): View {
        val trimmedTitle = request.title.trim()
        val trimmedText = request.text.trim()

        val container = LinearLayout(context)
        currentContainerView = container
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL
        if (theme != Theme.DEFAULT) {
            container.setPadding(dp(22f), dp(16f), dp(22f), dp(17f))
        } else {
            container.setPadding(dp(18f), dp(13f), dp(18f), dp(14f))
        }
        container.background = buildBannerBackground(theme, request.level)

        // 8 大主题专属法宝/徽章自绘图标
        val iconSize = dp(if (theme != Theme.DEFAULT) 36f else 30f)
        val iconParams = LinearLayout.LayoutParams(iconSize, iconSize)
        iconParams.gravity = Gravity.CENTER_VERTICAL
        iconParams.setMargins(0, 0, dp(14f), 0)
        val icon = ThemeIconView(context, theme, theme.bannerIconColor(request.level))
        currentIconView = icon
        container.addView(icon, iconParams)

        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        // 标题行
        val headerRow = LinearLayout(context)
        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.gravity = Gravity.CENTER_VERTICAL

        val badgeColor = theme.bannerIconColor(request.level)
        val badgeFillAlpha = if (theme.isLightBanner) 0x22 else 0x2A
        val badgeStrokeAlpha = if (theme.isLightBanner) 0x88 else 0x66
        val badgeView = TextView(context).apply {
            text = theme.levelBadgeText(request.level)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(badgeColor)
            typeface = theme.bannerTitleTypeface
            setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
            includeFontPadding = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(if (theme == Theme.CYBERPUNK || theme == Theme.MATRIX) 3f else 6f).toFloat()
                setColor(Color.argb(badgeFillAlpha, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
                setStroke(dp(1f), Color.argb(badgeStrokeAlpha, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
            }
        }
        currentBadgeView = badgeView
        val badgeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, dp(8f), 0)
        }
        headerRow.addView(badgeView, badgeParams)

        val finalTitle = if (trimmedTitle.isNotEmpty()) trimmedTitle else theme.defaultBannerTitle
        val titleView = buildTitleView(finalTitle, theme, request.level, position.isPip)
        currentTitleView = titleView
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        headerRow.addView(titleView, titleParams)
        column.addView(headerRow)

        if (trimmedText.isNotEmpty()) {
            val bodyView = buildBodyView(trimmedText, theme, position.isPip)
            currentBodyView = bodyView
            column.addView(bodyView)
        } else if (trimmedTitle.isEmpty()) {
            val bodyView = buildBodyView("（空通知）", theme, position.isPip)
            currentBodyView = bodyView
            column.addView(bodyView)
        }

        val columnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        container.addView(column, columnParams)

        // 媒体视窗（静态图片、快照轮询或动态 MJPEG 视频流）
        if (request.hasMedia) {
            val mediaCard = buildMediaCard(theme, request, position.isPip)
            container.addView(mediaCard)
        }

        return container
    }

    private fun buildBannerBackground(theme: Theme, level: BannerLevel): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(theme.bannerBackgroundStart, theme.bannerBackgroundEnd)
        )
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.cornerRadius = dp(theme.bannerCornerRadiusDp).toFloat()
        val strokeWidth = if (theme != Theme.DEFAULT) 1.8f else 1.3f
        drawable.setStroke(dp(strokeWidth), theme.bannerStrokeColor(level))
        return drawable
    }

    private fun buildTitleView(title: String, theme: Theme, level: BannerLevel, isPip: Boolean): TextView {
        val view = TextView(context)
        view.text = title
        view.setTextColor(theme.bannerTitle)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPip) 16f else 18f)
        view.typeface = theme.bannerTitleTypeface
        if (!theme.isLightBanner && theme != Theme.DEFAULT) {
            val glowColor = theme.bannerIconColor(level)
            view.setShadowLayer(
                8f, 0f, 1f,
                Color.argb(0x70, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
            )
        }
        view.isSingleLine = true
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        return view
    }

    private fun buildBodyView(text: String, theme: Theme, isPip: Boolean): TextView {
        val view = TextView(context)
        view.text = text
        view.setTextColor(theme.bannerBodyText)
        view.typeface = theme.bannerBodyTypeface
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isPip) 13.5f else 15f)
        view.maxLines = if (isPip) 3 else 4
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = false
        view.setLineSpacing(dp(2.5f).toFloat(), 1f)
        view.setPadding(0, dp(4f), 0, 0)
        return view
    }

    private fun buildLayoutParams(position: BannerPosition, hasMedia: Boolean): WindowManager.LayoutParams {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val ratio = if (position.isPip) {
            if (hasMedia) BANNER_PIP_WIDTH_RATIO_WITH_MEDIA else BANNER_PIP_WIDTH_RATIO
        } else {
            if (hasMedia) BANNER_WIDTH_RATIO_WITH_MEDIA else BANNER_WIDTH_RATIO
        }
        val minWidth = dp(if (position.isPip) 300f else 440f)
        val width = (screenWidth * ratio).toInt().coerceAtLeast(minWidth)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        )

        val margin = dp(BANNER_MARGIN_DP)
        when (position) {
            BannerPosition.TOP -> {
                params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                params.y = margin
            }
            BannerPosition.BOTTOM -> {
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.y = margin
            }
            BannerPosition.TOP_RIGHT -> {
                params.gravity = Gravity.TOP or Gravity.END
                params.x = margin
                params.y = margin
            }
            BannerPosition.TOP_LEFT -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.x = margin
                params.y = margin
            }
            BannerPosition.BOTTOM_RIGHT -> {
                params.gravity = Gravity.BOTTOM or Gravity.END
                params.x = margin
                params.y = margin
            }
            BannerPosition.BOTTOM_LEFT -> {
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.x = margin
                params.y = margin
            }
        }
        return params
    }

    // ------------------------------------------------------------------
    // 多媒体视窗构建与会话管理
    // ------------------------------------------------------------------

    private fun buildMediaCard(theme: Theme, request: BannerRequest, isPip: Boolean): View {
        val bannerWidthPx = (context.resources.displayMetrics.widthPixels *
                (if (isPip) BANNER_PIP_WIDTH_RATIO_WITH_MEDIA else BANNER_WIDTH_RATIO_WITH_MEDIA)).toInt()
        val mediaWidth = minOf(dp(if (isPip) 160f else 220f), (bannerWidthPx * 0.36f).toInt()).coerceAtLeast(dp(88f))
        val mediaHeight = (mediaWidth * 9 / 16).coerceAtLeast(dp(50f))

        val mediaCornerRadius = dp(if (theme.isXianxia) 13f else 10f).toFloat()

        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(mediaWidth, mediaHeight).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(14f), 0, 0, 0)
            }
            val strokeColor = if (theme != Theme.DEFAULT) {
                theme.bannerStrokeColor(request.level)
            } else {
                Color.argb(0x55, 0xFF, 0xFF, 0xFF)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = mediaCornerRadius
                setColor(if (theme.isXianxia) Color.argb(0xA0, 0x09, 0x16, 0x1A) else Color.argb(0xA0, 0x15, 0x16, 0x1A))
                setStroke(dp(1.2f), strokeColor)
            }
        }

        val imageView = RoundedCornerImageView(context, mediaCornerRadius).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val status = MediaStatus()
        val placeholder = TextView(context).apply {
            text = if (theme.isXianxia) "灵图凝聚中…" else "正在载入…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 3
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
        }
        frame.addView(placeholder)
        frame.addView(imageView)

        val hideStatus = Runnable { placeholder.visibility = View.GONE }
        val showStatus = { text: String ->
            placeholder.text = text
            placeholder.visibility = View.VISIBLE
        }
        val prefix = if (theme.isXianxia) "灵图未显：" else "未加载："
        val markFailed = Runnable { showStatus(prefix + status.step) }

        val watchdog = Runnable {
            if (imageView.drawable == null) markFailed.run()
        }

        // 动态流或快照轮询角标
        val isLiveStream = !request.videoUrl.isNullOrBlank() || (request.imageIntervalMs > 0 && !request.imageUrl.isNullOrBlank())
        if (isLiveStream) {
            val liveBadge = TextView(context).apply {
                text = if (theme.isXianxia) "灵镜" else "LIVE"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(dp(5f), dp(1.5f), dp(5f), dp(1.5f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4f).toFloat()
                    setColor(if (theme.isXianxia) Color.argb(0xC0, 0x13, 0x56, 0x47) else Color.argb(0xD0, 0xD9, 0x38, 0x3A))
                }
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins(dp(6f), dp(6f), 0, 0)
                }
            }
            frame.addView(liveBadge)
        }

        activeMediaSession = if (request.assetFrames) {
            AssetFramesSession(
                assets = context.assets,
                targetView = imageView,
                mainHandler = mainHandler,
                status = status,
                onLoaded = { mainHandler.post(hideStatus) },
                onFailed = { mainHandler.post(markFailed) }
            )
        } else if (!request.videoUrl.isNullOrBlank()) {
            MjpegStreamSession(
                videoUrl = request.videoUrl,
                targetView = imageView,
                mainHandler = mainHandler,
                status = status,
                onLoaded = { mainHandler.post(hideStatus) },
                onFailure = {
                    status.step = "视频流失败：" + status.step
                    if (!request.imageUrl.isNullOrBlank() || !request.imageBase64.isNullOrBlank()) {
                        mainHandler.post {
                            if (bannerView != null) {
                                val fallback = ImageMediaSession(
                                    imageUrl = request.imageUrl,
                                    imageBase64 = request.imageBase64,
                                    imageIntervalMs = request.imageIntervalMs,
                                    targetView = imageView,
                                    mainHandler = mainHandler,
                                    status = status,
                                    onLoaded = { mainHandler.post(hideStatus) },
                                    onFailed = { mainHandler.post(markFailed) }
                                )
                                activeMediaSession = fallback
                                fallback.start()
                            }
                        }
                    } else {
                        mainHandler.post(markFailed)
                    }
                }
            )
        } else {
            ImageMediaSession(
                imageUrl = request.imageUrl,
                imageBase64 = request.imageBase64,
                imageIntervalMs = request.imageIntervalMs,
                targetView = imageView,
                mainHandler = mainHandler,
                status = status,
                onLoaded = { mainHandler.post(hideStatus) },
                onFailed = { mainHandler.post(markFailed) }
            )
        }

        mainHandler.postDelayed(watchdog, 4000L)
        mediaWatchdog = watchdog
        return frame
    }

    private interface MediaSession {
        fun start()
        fun stop()
    }

    private class MediaStatus {
        @Volatile var step: String = "未开始"
    }

    private class AssetFramesSession(
        private val assets: android.content.res.AssetManager,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val status: MediaStatus,
        private val onLoaded: (() -> Unit)? = null,
        private val onFailed: (() -> Unit)? = null
    ) : MediaSession {
        @Volatile private var isRunning = false
        private var frames: List<ByteArray> = emptyList()
        private var frameIndex = 0

        private val tick = object : Runnable {
            override fun run() {
                if (!isRunning || frames.isEmpty()) return
                val bytes = frames[frameIndex % frames.size]
                frameIndex++
                val bitmap = if (!looksLikeJpeg(bytes)) {
                    if (frameIndex == 1) {
                        status.step = "抽帧非JPEG(${bytes.size}B ${bytes.take(3).joinToString("") { "%02x".format(it) }})"
                        onFailed?.invoke()
                    }
                    return
                } else {
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Throwable) {
                        null
                    }
                }
                if (bitmap != null) {
                    targetView.setImageBitmap(bitmap)
                    if (frameIndex == 1) onLoaded?.invoke()
                } else if (frameIndex == 1) {
                    status.step = "抽帧解码失败"
                    onFailed?.invoke()
                    return
                }
                mainHandler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }

        override fun start() {
            isRunning = true
            Thread({
                try {
                    status.step = "读取内置抽帧"
                    val names = assets.list(ASSET_DIR)?.sorted().orEmpty()
                    val loaded = ArrayList<ByteArray>(names.size)
                    for (name in names) {
                        if (!name.lowercase().endsWith(".jpg") && !name.lowercase().endsWith(".jpeg")) continue
                        try {
                            assets.open("$ASSET_DIR/$name").use { loaded.add(it.readBytes()) }
                        } catch (_: Exception) {}
                    }
                    frames = loaded
                    status.step = "抽帧 ${loaded.size} 张"
                    if (loaded.isEmpty()) {
                        onFailed?.invoke()
                    } else {
                        mainHandler.post(tick)
                    }
                } catch (e: Throwable) {
                    status.step = "抽帧异常 ${e.javaClass.simpleName}"
                    mainHandler.post { onFailed?.invoke() }
                }
            }, "tvnotify-assetframes").apply {
                isDaemon = true
                start()
            }
        }

        override fun stop() {
            isRunning = false
            mainHandler.removeCallbacks(tick)
        }

        companion object {
            private const val ASSET_DIR = "testframes"
            private const val FRAME_INTERVAL_MS = 250L
        }
    }

    private class RoundedCornerImageView(context: Context, private val radiusPx: Float) :
        ImageView(context) {
        private val clipPath = Path()
        private val clipRect = RectF()

        override fun onDraw(canvas: Canvas) {
            clipPath.reset()
            clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.addRoundRect(clipRect, radiusPx, radiusPx, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clipPath)
            super.onDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    private class MjpegStreamSession(
        private val videoUrl: String,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val status: MediaStatus,
        private val onLoaded: (() -> Unit)? = null,
        private val onFailure: (() -> Unit)?
    ) : MediaSession {
        @Volatile private var isRunning = false
        private var connection: HttpURLConnection? = null
        private var inStreamRef: InputStream? = null
        private var workerThread: Thread? = null
        private var firstFrameShown = false
        private var sampleSize: Int = 1
        private var sampleDetected: Boolean = false
        private var frameCounter: Int = 0

        override fun start() {
            isRunning = true
            workerThread = Thread({
                runStream()
            }, "yujian-mjpeg").apply {
                isDaemon = true
                start()
            }
        }

        private fun runStream() {
            try {
                val conn = SafeNetworkHelper.openConnection(
                    urlStr = videoUrl,
                    connectTimeoutMs = 3500,
                    readTimeoutMs = 5000,
                    userAgent = "YuJian/${NotifyService.VERSION_NAME}",
                    acceptHeader = "multipart/x-mixed-replace, image/jpeg, */*"
                )
                connection = conn
                status.step = "连接视频流"
                conn.connect()
                if (conn.responseCode != 200) {
                    Log.w(TAG, "MJPEG 连接返回非 200: ${conn.responseCode}")
                    status.step = "HTTP ${conn.responseCode}"
                    onFailure?.invoke()
                    return
                }
                status.step = "已连流，等待首帧"
                val inStream = BufferedInputStream(conn.inputStream, 16384)
                inStreamRef = inStream
                val parser = MjpegFrameParser(inStream)
                var lastRenderTime = 0L
                val minFrameIntervalMs = 66L

                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val frameData = parser.readNextFrame()
                    if (frameData == null) {
                        status.step = if (firstFrameShown) "视频流中断" else "视频流无帧"
                        if (isRunning) onFailure?.invoke()
                        break
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastRenderTime < minFrameIntervalMs) {
                        continue
                    }
                    lastRenderTime = now
                    frameCounter++

                    if (!sampleDetected || frameCounter % 150 == 0) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(frameData, 0, frameData.size, bounds)
                        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                            var s = 1
                            val reqW = 480
                            val reqH = 270
                            while ((bounds.outWidth / (s * 2)) >= reqW && (bounds.outHeight / (s * 2)) >= reqH) {
                                s *= 2
                            }
                            sampleSize = s
                            sampleDetected = true
                        }
                    }

                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frameData, 0, frameData.size, opts) ?: continue

                    mainHandler.post {
                        if (isRunning) {
                            targetView.setImageBitmap(bitmap)
                            if (!firstFrameShown) {
                                firstFrameShown = true
                                onLoaded?.invoke()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (isRunning) {
                    Log.w(TAG, "MJPEG 播放异常: ${e.message}")
                    status.step = "${e.javaClass.simpleName}: ${e.message?.take(40) ?: ""}"
                    onFailure?.invoke()
                }
            } finally {
                try { inStreamRef?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }

        override fun stop() {
            isRunning = false
            try { inStreamRef?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
            workerThread?.interrupt()
            workerThread = null
        }
    }

    private class ImageMediaSession(
        private val imageUrl: String?,
        private val imageBase64: String?,
        private val imageIntervalMs: Int = 0,
        private val targetView: ImageView,
        private val mainHandler: Handler,
        private val status: MediaStatus,
        private val onLoaded: (() -> Unit)? = null,
        private val onFailed: (() -> Unit)? = null
    ) : MediaSession {
        companion object {
            private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        }

        @Volatile private var isRunning = false
        private var connection: HttpURLConnection? = null
        private var workerThread: Thread? = null

        override fun start() {
            isRunning = true
            workerThread = Thread({
                try {
                    status.step = if (!imageBase64.isNullOrBlank()) {
                        "解码 base64(${imageBase64.length})"
                    } else {
                        "下载图片"
                    }
                    val bytes = if (!imageBase64.isNullOrBlank()) {
                        val raw = imageBase64.trim()
                        val clean = if (raw.contains(",")) raw.substringAfter(",") else raw
                        Base64.decode(clean, Base64.DEFAULT)
                    } else if (!imageUrl.isNullOrBlank()) {
                        downloadImageBytes(imageUrl)
                    } else null

                    val bitmap = if (bytes != null && bytes.isNotEmpty() && !looksLikeJpeg(bytes)) {
                        status.step = "非JPEG(${bytes.size}B ${bytes.take(3).joinToString("") { "%02x".format(it) }})"
                        null
                    } else if (bytes != null && bytes.isNotEmpty() && isRunning) {
                        status.step = "解码位图 ${bytes.size}B"
                        decodeSampledBitmap(bytes, 480, 270)
                    } else null

                    status.step = "位图 ${bitmap?.width}x${bitmap?.height} bytes=${bytes?.size ?: 0}"
                    Log.i(TAG, "静态图片解码：bytes=${bytes?.size ?: 0} bitmap=${bitmap?.width}x${bitmap?.height}")

                    mainHandler.post {
                        if (isRunning) {
                            if (bitmap != null) {
                                targetView.alpha = 1f
                                targetView.setImageBitmap(bitmap)
                                onLoaded?.invoke()
                            } else {
                                onFailed?.invoke()
                            }
                        }
                    }

                    // 快照定时轮询（准视频流模式）
                    val interval = imageIntervalMs.coerceIn(500, 10000).toLong()
                    if (imageIntervalMs > 0 && !imageUrl.isNullOrBlank() && bitmap != null) {
                        while (isRunning && !Thread.currentThread().isInterrupted) {
                            try {
                                Thread.sleep(interval)
                            } catch (_: InterruptedException) {
                                break
                            }
                            if (!isRunning) break
                            val pollUrl = if (imageUrl.contains("?")) {
                                "$imageUrl&_yjt=${SystemClock.uptimeMillis()}"
                            } else {
                                "$imageUrl?_yjt=${SystemClock.uptimeMillis()}"
                            }
                            try {
                                val nextBytes = downloadImageBytes(pollUrl)
                                if (nextBytes != null && nextBytes.isNotEmpty() && looksLikeJpeg(nextBytes)) {
                                    val nextBitmap = decodeSampledBitmap(nextBytes, 480, 270)
                                    if (nextBitmap != null && isRunning) {
                                        mainHandler.post {
                                            if (isRunning) {
                                                targetView.setImageBitmap(nextBitmap)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "快照轮询偶发失败: ${e.message}")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "加载静态图片异常: ${e.message}")
                    status.step = "${e.javaClass.simpleName}: ${e.message?.take(40) ?: ""}"
                    mainHandler.post { if (isRunning) onFailed?.invoke() }
                }
            }, "yujian-img").apply {
                isDaemon = true
                start()
            }
        }

        private fun downloadImageBytes(urlStr: String): ByteArray? {
            val conn = SafeNetworkHelper.openConnection(
                urlStr = urlStr,
                connectTimeoutMs = 3000,
                readTimeoutMs = 4000,
                userAgent = "YuJian/${NotifyService.VERSION_NAME}",
                acceptHeader = "image/*, */*"
            )
            connection = conn
            conn.connect()
            if (conn.responseCode != 200) return null
            val declared = conn.headerFields["Content-Length"]?.firstOrNull()?.toLongOrNull() ?: -1L
            if (declared > MAX_IMAGE_BYTES) {
                Log.w(TAG, "图片过大，已放弃：${declared}B > ${MAX_IMAGE_BYTES}B")
                return null
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_IMAGE_BYTES) {
                        Log.w(TAG, "图片超过上限，已截断放弃：>${MAX_IMAGE_BYTES}B")
                        return null
                    }
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        }

        private fun decodeSampledBitmap(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            if (bounds.outWidth > reqW || bounds.outHeight > reqH) {
                val halfW = bounds.outWidth / 2
                val halfH = bounds.outHeight / 2
                while ((halfW / sample) >= reqW && (halfH / sample) >= reqH) {
                    sample *= 2
                }
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        override fun stop() {
            isRunning = false
            try { connection?.disconnect() } catch (_: Exception) {}
            workerThread?.interrupt()
            workerThread = null
        }
    }

    private class MjpegFrameParser(private val inStream: InputStream) {
        private val buffer = ByteArrayOutputStream(32768)
        private var prevByte: Int = -1

        fun readNextFrame(): ByteArray? {
            buffer.reset()
            var foundSoi = false

            while (true) {
                val b = inStream.read()
                if (b == -1) return null
                if (prevByte == 0xFF && b == 0xD8) {
                    foundSoi = true
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                    prevByte = -1
                    break
                }
                prevByte = b
            }

            if (!foundSoi) return null

            var remainingLimit = 2 * 1024 * 1024
            while (remainingLimit-- > 0) {
                val b = inStream.read()
                if (b == -1) return null
                buffer.write(b)
                if (prevByte == 0xFF && b == 0xD9) {
                    prevByte = -1
                    return buffer.toByteArray()
                }
                if (prevByte == 0xFF && b == 0xD8) {
                    buffer.reset()
                    buffer.write(0xFF)
                    buffer.write(0xD8)
                    prevByte = -1
                    continue
                }
                prevByte = b
            }
            return null
        }
    }

    // ------------------------------------------------------------------
    // WindowManager 读写
    // ------------------------------------------------------------------

    private fun addViewSafely(view: View, params: WindowManager.LayoutParams): Boolean {
        return try {
            windowManager.addView(view, params)
            true
        } catch (e: Exception) {
            Log.w(TAG, "addView 失败, type=${params.type}", e)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                params.type != WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
            ) {
                params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                try {
                    windowManager.addView(view, params)
                    Log.i(TAG, "已降级为 TYPE_SYSTEM_ERROR 重试成功")
                    true
                } catch (second: Exception) {
                    Log.e(TAG, "降级 TYPE_SYSTEM_ERROR 仍然失败", second)
                    false
                }
            } else {
                false
            }
        }
    }

    private fun removeViewSafely(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "removeView 失败（可能已移除）", e)
        }
    }

    private fun removeBannerNow() {
        activeMediaSession?.stop()
        activeMediaSession = null
        cancelMediaWatchdog()
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.removeCallbacks(clearKeepScreenOnRunnable)
        cancelAnimation()
        bannerParams = null
        currentRequestId = null
        currentTitleView = null
        currentBodyView = null
        currentBadgeView = null
        currentContainerView = null
        currentIconView = null
        val view = bannerView ?: return
        bannerView = null
        removeViewSafely(view)
    }

    private fun cancelMediaWatchdog() {
        val watchdog = mediaWatchdog ?: return
        mediaWatchdog = null
        mainHandler.removeCallbacks(watchdog)
    }

    private fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics
    ).toInt()

    // ------------------------------------------------------------------
    // 8 大主题专属自绘矢量图标组件
    // ------------------------------------------------------------------

    private class ThemeIconView(
        context: Context,
        private val theme: Theme,
        private val levelColor: Int
    ) : View(context) {

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            when (theme) {
                Theme.XIUXIAN, Theme.DEFAULT -> drawJadeSlip(canvas, w, h)
                Theme.PALACE -> drawPalaceScroll(canvas, w, h)
                Theme.CYBERPUNK -> drawCyberHex(canvas, w, h)
                Theme.WUKONG -> drawGoldenHoop(canvas, w, h)
                Theme.MATRIX -> drawMatrixTerminal(canvas, w, h)
                Theme.INK_LIGHT -> drawInkSeal(canvas, w, h)
                Theme.GAMER -> drawGamerTrophy(canvas, w, h)
            }
        }

        /** 1. 修仙玉简 / tvOS：灵玉简牍 + 双重灵波纹 */
        private fun drawJadeSlip(canvas: Canvas, w: Float, h: Float) {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val r1 = w * 0.36f
            val r2 = w * 0.46f
            val sweepAngle = 56f

            strokePaint.strokeWidth = w * 0.045f
            strokePaint.color = Color.argb(0xB3, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawArc(RectF(cx - r1, cy - r1, cx + r1, cy + r1), 180f - sweepAngle / 2, sweepAngle, false, strokePaint)
            canvas.drawArc(RectF(cx - r1, cy - r1, cx + r1, cy + r1), -sweepAngle / 2, sweepAngle, false, strokePaint)

            strokePaint.strokeWidth = w * 0.035f
            strokePaint.color = Color.argb(0x55, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), 180f - sweepAngle / 2, sweepAngle, false, strokePaint)
            canvas.drawArc(RectF(cx - r2, cy - r2, cx + r2, cy + r2), -sweepAngle / 2, sweepAngle, false, strokePaint)

            val jadeW = w * 0.30f
            val jadeH = h * 0.74f
            val left = cx - jadeW / 2
            val top = cy - jadeH / 2
            val right = cx + jadeW / 2
            val bottom = cy + jadeH / 2
            val radius = jadeW * 0.22f

            fillPaint.shader = LinearGradient(
                left, top, right, bottom,
                intArrayOf(
                    Color.argb(0xFF, 0xF2, 0xFF, 0xFA),
                    levelColor,
                    Color.argb(0xFF, 0x14, 0x3E, 0x42)
                ),
                floatArrayOf(0.0f, 0.45f, 1.0f),
                Shader.TileMode.CLAMP
            )
            val jadeRect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(jadeRect, radius, radius, fillPaint)
            fillPaint.shader = null

            strokePaint.strokeWidth = 2f
            strokePaint.color = Color.argb(0x99, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawRoundRect(jadeRect, radius, radius, strokePaint)

            fillPaint.color = Color.argb(0xDD, 0x0A, 0x18, 0x1B)
            canvas.drawCircle(cx, top + jadeW * 0.36f, jadeW * 0.12f, fillPaint)

            strokePaint.color = Color.argb(0x70, 0xFF, 0xFF, 0xFF)
            strokePaint.strokeWidth = w * 0.024f
            val lineY1 = cy + jadeH * 0.06f
            val lineY2 = cy + jadeH * 0.22f
            canvas.drawLine(cx - jadeW * 0.24f, lineY1, cx + jadeW * 0.24f, lineY1, strokePaint)
            canvas.drawLine(cx - jadeW * 0.18f, lineY2, cx + jadeW * 0.18f, lineY2, strokePaint)
        }

        /** 2. 大唐鎏金：宫廷鎏金卷轴圣旨令牌 */
        private fun drawPalaceScroll(canvas: Canvas, w: Float, h: Float) {
            val cx = w * 0.5f
            val cy = h * 0.5f

            // 上下鎏金卷轴轴杆
            fillPaint.shader = null
            fillPaint.color = levelColor
            val rodW = w * 0.78f
            val rodH = h * 0.10f
            canvas.drawRoundRect(RectF(cx - rodW / 2, h * 0.12f, cx + rodW / 2, h * 0.12f + rodH), rodH / 2, rodH / 2, fillPaint)
            canvas.drawRoundRect(RectF(cx - rodW / 2, h * 0.78f, cx + rodW / 2, h * 0.78f + rodH), rodH / 2, rodH / 2, fillPaint)

            // 卷轴朱红锦缎主体
            val bodyW = w * 0.58f
            val bodyRect = RectF(cx - bodyW / 2, h * 0.20f, cx + bodyW / 2, h * 0.80f)
            fillPaint.shader = LinearGradient(
                bodyRect.left, bodyRect.top, bodyRect.right, bodyRect.bottom,
                intArrayOf(Color.rgb(0x6E, 0x18, 0x18), Color.rgb(0x38, 0x0A, 0x0A)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(bodyRect, fillPaint)
            fillPaint.shader = null

            strokePaint.strokeWidth = w * 0.045f
            strokePaint.color = levelColor
            canvas.drawRect(bodyRect, strokePaint)

            // 中央菱形鎏金玉玺印记
            path.reset()
            val r = w * 0.15f
            path.moveTo(cx, cy - r)
            path.lineTo(cx + r, cy)
            path.lineTo(cx, cy + r)
            path.lineTo(cx - r, cy)
            path.close()
            fillPaint.color = levelColor
            canvas.drawPath(path, fillPaint)
        }

        /** 3. 赛博霓虹：双层六边形全息能量核 */
        private fun drawCyberHex(canvas: Canvas, w: Float, h: Float) {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val outerR = w * 0.42f
            val innerR = w * 0.24f

            // 外层霓虹六边形
            buildHexPath(cx, cy, outerR)
            fillPaint.shader = null
            fillPaint.color = Color.argb(0x28, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawPath(path, fillPaint)

            strokePaint.strokeWidth = w * 0.055f
            strokePaint.color = levelColor
            canvas.drawPath(path, strokePaint)

            // 内层荧光黄能量核
            buildHexPath(cx, cy, innerR)
            fillPaint.color = Color.rgb(0xFC, 0xEE, 0x09)
            canvas.drawPath(path, fillPaint)

            // 核心黑点与十字准星线
            fillPaint.color = Color.rgb(0x14, 0x0B, 0x29)
            canvas.drawCircle(cx, cy, w * 0.07f, fillPaint)
        }

        private fun buildHexPath(cx: Float, cy: Float, r: Float) {
            path.reset()
            for (i in 0 until 6) {
                val angle = Math.toRadians((60.0 * i - 30.0))
                val x = (cx + r * kotlin.math.cos(angle)).toFloat()
                val y = (cy + r * kotlin.math.sin(angle)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }

        /** 4. 黑神话·暗金：暗金紧箍咒环 */
        private fun drawGoldenHoop(canvas: Canvas, w: Float, h: Float) {
            val cx = w * 0.5f
            val cy = h * 0.54f
            val r = w * 0.34f

            strokePaint.strokeWidth = w * 0.085f
            strokePaint.color = levelColor
            // 金箍主圆弧（顶部留出卷云缺口）
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -70f, 320f, false, strokePaint)

            // 顶部向内微卷的双祥云金箍扣
            strokePaint.strokeWidth = w * 0.068f
            val curlR = w * 0.11f
            canvas.drawArc(
                RectF(cx - curlR * 2.05f, cy - r - curlR * 0.5f, cx - curlR * 0.05f, cy - r + curlR * 1.5f),
                -90f, 220f, false, strokePaint
            )
            canvas.drawArc(
                RectF(cx + curlR * 0.05f, cy - r - curlR * 0.5f, cx + curlR * 2.05f, cy - r + curlR * 1.5f),
                -40f, 220f, false, strokePaint
            )

            // 中央灵光暗金点
            fillPaint.shader = null
            fillPaint.color = Color.argb(0x99, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawCircle(cx, cy + r * 0.15f, w * 0.065f, fillPaint)
        }

        /** 5. 极客终端：CRT 终端命令行 `>_` */
        private fun drawMatrixTerminal(canvas: Canvas, w: Float, h: Float) {
            val pad = w * 0.10f
            val rect = RectF(pad, pad, w - pad, h - pad)

            fillPaint.shader = null
            fillPaint.color = Color.argb(0x26, Color.red(levelColor), Color.green(levelColor), Color.blue(levelColor))
            canvas.drawRoundRect(rect, w * 0.12f, w * 0.12f, fillPaint)

            strokePaint.strokeWidth = w * 0.055f
            strokePaint.color = levelColor
            canvas.drawRoundRect(rect, w * 0.12f, w * 0.12f, strokePaint)

            // 绘制 '>' 符号
            path.reset()
            path.moveTo(w * 0.27f, h * 0.34f)
            path.lineTo(w * 0.46f, h * 0.50f)
            path.lineTo(w * 0.27f, h * 0.66f)
            strokePaint.strokeWidth = w * 0.075f
            canvas.drawPath(path, strokePaint)

            // 绘制 '_' 光标
            canvas.drawLine(w * 0.52f, h * 0.66f, w * 0.73f, h * 0.66f, strokePaint)
        }

        /** 6. 江南宣纸：阳刻朱砂方印 */
        private fun drawInkSeal(canvas: Canvas, w: Float, h: Float) {
            val pad = w * 0.11f
            val rect = RectF(pad, pad, w - pad, h - pad)

            // 朱砂印底
            fillPaint.shader = null
            fillPaint.color = levelColor
            canvas.drawRoundRect(rect, w * 0.10f, w * 0.10f, fillPaint)

            // 宣纸白内框与篆书几何印纹
            val innerPad = w * 0.19f
            strokePaint.color = Color.rgb(0xF7, 0xF3, 0xE8)
            strokePaint.strokeWidth = w * 0.042f
            canvas.drawRect(RectF(innerPad, innerPad, w - innerPad, h - innerPad), strokePaint)

            val cx = w * 0.5f
            val cy = h * 0.5f
            canvas.drawLine(cx, innerPad, cx, h - innerPad, strokePaint)
            canvas.drawLine(innerPad, cy, w - innerPad, cy, strokePaint)
        }

        /** 7. 主机奖杯：黄金圣杯 + 底座 */
        private fun drawGamerTrophy(canvas: Canvas, w: Float, h: Float) {
            val cx = w * 0.5f

            // 左右双耳手柄
            strokePaint.color = levelColor
            strokePaint.strokeWidth = w * 0.06f
            canvas.drawArc(RectF(w * 0.12f, h * 0.20f, w * 0.36f, h * 0.48f), 90f, 180f, false, strokePaint)
            canvas.drawArc(RectF(w * 0.64f, h * 0.20f, w * 0.88f, h * 0.48f), -90f, 180f, false, strokePaint)

            // 奖杯杯身
            fillPaint.shader = LinearGradient(
                w * 0.26f, h * 0.16f, w * 0.74f, h * 0.58f,
                intArrayOf(Color.rgb(0xFF, 0xF0, 0xA8), levelColor),
                null,
                Shader.TileMode.CLAMP
            )
            val cupRect = RectF(w * 0.26f, h * 0.16f, w * 0.74f, h * 0.56f)
            canvas.drawRoundRect(cupRect, w * 0.16f, w * 0.16f, fillPaint)
            fillPaint.shader = null

            // 杯柱与底座
            fillPaint.color = levelColor
            canvas.drawRect(RectF(cx - w * 0.055f, h * 0.54f, cx + w * 0.055f, h * 0.74f), fillPaint)
            canvas.drawRoundRect(RectF(w * 0.26f, h * 0.73f, w * 0.74f, h * 0.85f), w * 0.04f, w * 0.04f, fillPaint)

            // 杯身中央白金星芒
            fillPaint.color = Color.WHITE
            canvas.drawCircle(cx, h * 0.34f, w * 0.065f, fillPaint)
        }
    }
}
