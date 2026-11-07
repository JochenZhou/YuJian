package com.zqc.tvnotify

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.sin

/**
 * 1080p 全屏专辑封面与大字同步滚动歌词播放器：
 * 1. 深度适配玉简 8 大视觉主题体系；
 * 2. 遥控器友好交互：底部 7 键导航，方向键上下直调音量，按键带微缩放与灵光聚焦边框；
 * 3. 硬件加速 Canvas 自绘阻尼平滑居中滚动歌词；
 * 4. 内置黑胶灵盘图腾自绘与屏幕配对二维码（SP:0...）弹窗。
 */
class SendspinActivity : Activity() {

    companion object {
        @Volatile
        private var activeInstanceRef: WeakReference<SendspinActivity>? = null

        fun closeIfOpen() {
            activeInstanceRef?.get()?.let { act ->
                act.runOnUiThread {
                    if (!act.isFinishing && !act.isDestroyed) {
                        act.finish()
                    }
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var theme: Theme = Theme.XIUXIAN

    private lateinit var statusBadge: TextView
    private lateinit var volumeBadge: TextView
    private lateinit var vinylArtworkView: VinylArtworkView
    private lateinit var trackTitleView: TextView
    private lateinit var trackArtistAlbumView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var timePositionView: TextView
    private lateinit var playPauseButton: Button
    private lateinit var lyricsScrollView: LyricsScrollView

    private var updateProgressRunnable: Runnable? = null
    private val sendspinListener: () -> Unit = {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                refreshPlayerUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstanceRef = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        theme = Theme.current(this)
        setContentView(buildRootLayout())

        SendspinManager.addListener(sendspinListener)
        refreshPlayerUi()
        startProgressTicker()

        playPauseButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        val latestTheme = Theme.current(this)
        if (latestTheme != theme) {
            theme = latestTheme
            setContentView(buildRootLayout())
            refreshPlayerUi()
            playPauseButton.requestFocus()
        }
    }

    override fun onDestroy() {
        activeInstanceRef = null
        stopProgressTicker()
        SendspinManager.removeListener(sendspinListener)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                SendspinManager.adjustLocalOrGroupVolume(+5)
                refreshPlayerUi()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                SendspinManager.adjustLocalOrGroupVolume(-5)
                refreshPlayerUi()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                SendspinManager.sendControllerCommand("next")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                SendspinManager.sendControllerCommand("previous")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------
    // UI 构建 (1080p 横屏双栏)
    // ------------------------------------------------------------------

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(theme.windowBg)
            val padH = dp(32)
            val padV = dp(24)
            setPadding(padH, padV, padH, padV)
            isFocusable = false
        }

        // === 左栏 (42% 宽度)：专辑封面、曲目信息、进度与 7 键播控栏 ===
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = false
        }

        // 1. 顶部状态胶囊栏
        val topStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        statusBadge = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(theme.accentColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedRect(dp(12).toFloat(), theme.cardBg, dp(1), theme.cardStroke)
            isClickable = true
            setOnClickListener { showPairingDialog() }
        }
        topStatusRow.addView(statusBadge, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val serverLinkBtn = buildControlButton("🔗 连接 MA", isPrimary = false) {
            showPairingDialog()
        }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        topStatusRow.addView(serverLinkBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(8)
        })

        volumeBadge = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundedRect(dp(12).toFloat(), theme.cardBg, dp(1), theme.cardStroke)
        }
        topStatusRow.addView(volumeBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        leftCol.addView(topStatusRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2. 居中黑胶/专辑卡片 (VinylArtworkView)
        vinylArtworkView = VinylArtworkView(this)
        val coverSizePx = dp(230)
        leftCol.addView(vinylArtworkView, LinearLayout.LayoutParams(coverSizePx, coverSizePx).apply {
            topMargin = dp(4)
            bottomMargin = dp(14)
        })

        // 3. 曲目名与歌手
        trackTitleView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(theme.bannerTitle)
            typeface = theme.bannerTitleTypeface
            gravity = Gravity.CENTER
            setSingleLine(true)
            isSelected = true
            setPadding(dp(8), 0, dp(8), dp(2))
        }
        leftCol.addView(trackTitleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        trackArtistAlbumView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme.hintColor)
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(8), 0, dp(8), dp(10))
        }
        leftCol.addView(trackArtistAlbumView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 4. 进度条与时间
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            isFocusable = false
        }
        leftCol.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        timePositionView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(theme.hintColor)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
            text = "00:00 / 00:00"
        }
        leftCol.addView(timePositionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 5. 底部 7 键播控栏 (左右切焦点，上下按键调音量)
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val prevBtn = buildControlButton("⏮") { SendspinManager.sendControllerCommand("previous") }
        playPauseButton = buildControlButton("⏯", isPrimary = true) { togglePlayPause() }
        val nextBtn = buildControlButton("⏭") { SendspinManager.sendControllerCommand("next") }
        val volDownBtn = buildControlButton("🔉") { SendspinManager.adjustLocalOrGroupVolume(-5); refreshPlayerUi() }
        val volUpBtn = buildControlButton("🔊") { SendspinManager.adjustLocalOrGroupVolume(+5); refreshPlayerUi() }
        val themeBtn = buildControlButton("🎨") { showThemeSelector() }
        val pairBtn = buildControlButton("🔗") { showPairingDialog() }

        controlRow.addView(prevBtn, buttonWeightParams())
        controlRow.addView(playPauseButton, buttonWeightParams(leftGap = 6))
        controlRow.addView(nextBtn, buttonWeightParams(leftGap = 6))
        controlRow.addView(volDownBtn, buttonWeightParams(leftGap = 6))
        controlRow.addView(volUpBtn, buttonWeightParams(leftGap = 6))
        controlRow.addView(themeBtn, buttonWeightParams(leftGap = 6))
        controlRow.addView(pairBtn, buttonWeightParams(leftGap = 6))

        leftCol.addView(controlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(leftCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f))

        // === 右栏 (58% 宽度)：Canvas 自绘阻尼平滑居中滚动歌词 (LyricsScrollView) ===
        lyricsScrollView = LyricsScrollView(this).apply {
            isFocusable = false
        }
        val rightParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f).apply {
            leftMargin = dp(24)
        }
        root.addView(lyricsScrollView, rightParams)

        return root
    }

    private fun buttonWeightParams(leftGap: Int = 0): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        if (leftGap > 0) lp.leftMargin = dp(leftGap)
        return lp
    }

    private fun buildControlButton(label: String, isPrimary: Boolean = false, onClick: () -> Unit): Button {
        val btn = Button(this)
        btn.text = label
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        btn.typeface = Typeface.DEFAULT_BOLD
        btn.setTextColor(Color.WHITE)
        btn.setPadding(0, dp(8), 0, dp(8))
        btn.isFocusable = true
        btn.isFocusableInTouchMode = false
        btn.stateListAnimator = null

        val normalBg = if (isPrimary) {
            Color.argb(0x40, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor))
        } else {
            theme.buttonBg
        }
        val stroke = if (isPrimary) theme.accentColor else theme.buttonStroke

        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(dp(8).toFloat(), theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(dp(8).toFloat(), normalBg, dp(1), stroke)
        )
        btn.background = states

        btn.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
        btn.setOnClickListener { onClick() }
        return btn
    }

    // ------------------------------------------------------------------
    // 状态刷新与进度调度
    // ------------------------------------------------------------------

    private fun refreshPlayerUi() {
        val snap = SendspinManager.snapshot()

        // 1. 状态栏
        if (snap.connected) {
            val server = if (snap.serverName.isNotBlank()) snap.serverName else "Music Assistant"
            val rate = snap.format?.sampleRate ?: 48000
            statusBadge.text = "● $server · PCM ${rate / 1000}kHz"
            statusBadge.setTextColor(theme.accentColor)
        } else {
            val portText = if (snap.boundPort > 0) ":${snap.boundPort}" else ""
            statusBadge.text = "○ 等待 Music Assistant 发现 $portText"
            statusBadge.setTextColor(Color.rgb(0xFF, 0xB0, 0x20))
        }

        volumeBadge.text = if (snap.playerMuted) "🔇 静音" else "🔊 ${snap.playerVolume}%"

        // 2. 封面
        vinylArtworkView.setArtwork(SendspinManager.currentArtwork(), theme)

        // 3. 曲目信息
        val title = if (snap.metadata.title.isNotBlank()) snap.metadata.title else "灵音台（Sendspin）"
        val artist = snap.metadata.artist
        val album = snap.metadata.album
        trackTitleView.text = title

        val subBuilder = StringBuilder()
        if (artist.isNotBlank()) subBuilder.append(artist)
        if (album.isNotBlank()) {
            if (subBuilder.isNotEmpty()) subBuilder.append(" · ")
            subBuilder.append(album)
        }
        if (subBuilder.isEmpty()) {
            subBuilder.append(if (snap.connected) "正在等待音频流传输..." else "请在 Music Assistant 中投送音频至此电视")
        }
        trackArtistAlbumView.text = subBuilder.toString()

        // 4. 播控按钮文字
        playPauseButton.text = if (snap.playing) "⏸" else "▶"

        // 5. 歌词视图
        lyricsScrollView.setLyrics(SendspinManager.currentLyrics(), theme)

        updateProgressUi()
    }

    private fun updateProgressUi() {
        val snap = SendspinManager.snapshot()
        val curMs = SendspinManager.currentPositionMs().coerceAtLeast(0L)
        val durMs = snap.metadata.trackDurationMs

        if (durMs > 0L) {
            val progressFrac = (curMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)
            progressBar.progress = (progressFrac * 1000).toInt()
            timePositionView.text = "${formatTime(curMs)} / ${formatTime(durMs)}"
        } else {
            progressBar.progress = 0
            timePositionView.text = if (curMs > 0L) formatTime(curMs) else "00:00 / 00:00"
        }

        lyricsScrollView.updatePosition(curMs)
    }

    private fun startProgressTicker() {
        stopProgressTicker()
        val ticker = object : Runnable {
            override fun run() {
                updateProgressUi()
                mainHandler.postDelayed(this, 100L)
            }
        }
        updateProgressRunnable = ticker
        mainHandler.post(ticker)
    }

    private fun stopProgressTicker() {
        updateProgressRunnable?.let { mainHandler.removeCallbacks(it) }
        updateProgressRunnable = null
    }

    private fun togglePlayPause() {
        val snap = SendspinManager.snapshot()
        val cmd = if (snap.playing) "pause" else "play"
        SendspinManager.sendControllerCommand(cmd)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return "%02d:%02d".format(min, sec)
    }

    // ------------------------------------------------------------------
    // 配对与设置对话框 (二维码 SP:0...)
    // ------------------------------------------------------------------

    private fun showPairingDialog() {
        val snap = SendspinManager.snapshot()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(18)
            setPadding(p, p, p, p)
            setBackgroundColor(theme.cardBg)
        }

        val titleTv = TextView(this).apply {
            text = "🔗 Sendspin 配对与服务端配置"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        dialogView.addView(titleTv)

        // 二维码区域
        val qrBox = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        val qrSize = dp(160)
        dialogView.addView(qrBox, LinearLayout.LayoutParams(qrSize, qrSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        Thread({
            val qrBmp = createQrBitmap(snap.pairingToken, qrSize)
            mainHandler.post { qrBox.setImageBitmap(qrBmp) }
        }, "sendspin-qr").start()

        val tokenHint = TextView(this).apply {
            text = "在 Music Assistant 中添加 Sendspin 播放器并扫描上方二维码配对\n配对码：${snap.pairingToken.take(20)}..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(theme.hintColor)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        dialogView.addView(tokenHint)

        // 自动弹出歌词屏开关
        val autoPopupCb = CheckBox(this).apply {
            text = "收到音乐播放流时自动弹出此歌词大屏"
            setTextColor(Color.WHITE)
            isChecked = Prefs.isSendspinAutoPopup(this@SendspinActivity)
        }
        dialogView.addView(autoPopupCb)

        val discovered = SendspinManager.discoveredServers()
        if (discovered.isNotEmpty()) {
            val discLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
            val discLabel = TextView(this).apply {
                text = "💡 局域网已发现 Music Assistant (点击填入)："
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(theme.accentColor)
            }
            discLayout.addView(discLabel)
            for (srv in discovered) {
                val srvBtn = Button(this).apply {
                    text = "▶ 连接 ${srv.name} (${srv.host}:${srv.port})"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(Color.WHITE)
                    background = roundedRect(dp(4).toFloat(), theme.buttonBg, dp(1), theme.accentColor)
                    setOnClickListener {
                        Prefs.setSendspinServerUrl(this@SendspinActivity, srv.wsUrl)
                        SendspinManager.reloadConfig()
                        Toast.makeText(this@SendspinActivity, "正在连接至 ${srv.name}...", Toast.LENGTH_SHORT).show()
                        refreshPlayerUi()
                    }
                }
                discLayout.addView(srvBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                })
            }
            dialogView.addView(discLayout)
        }

        // 手动服务端地址
        val urlEt = EditText(this).apply {
            hint = "输入 MA 服务端 IP 或地址，例如 192.168.1.50:8927"
            setTextColor(Color.WHITE)
            setHintTextColor(theme.hintColor)
            setText(Prefs.getSendspinServerUrl(this@SendspinActivity))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedRect(dp(6).toFloat(), Color.argb(0x30, 0, 0, 0), dp(1), theme.cardStroke)
        }
        dialogView.addView(urlEt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("立即连接") { _, _ ->
                Prefs.setSendspinAutoPopup(this, autoPopupCb.isChecked)
                val newUrl = urlEt.text.toString().trim()
                val normalized = if (newUrl.isNotBlank()) SendspinManager.normalizeSendspinUrl(newUrl) else ""
                Prefs.setSendspinServerUrl(this, normalized)
                SendspinManager.reloadConfig()
                Toast.makeText(this, if (normalized.isNotBlank()) "正在连接至 $normalized" else "已恢复局域网自动发现模式", Toast.LENGTH_SHORT).show()
                refreshPlayerUi()
            }
            .setNeutralButton("重置为自动发现") { _, _ ->
                Prefs.setSendspinServerUrl(this, "")
                SendspinManager.reloadConfig()
                Toast.makeText(this, "已重置为局域网自动发现模式", Toast.LENGTH_SHORT).show()
                refreshPlayerUi()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showThemeSelector() {
        val themes = Theme.entries.toTypedArray()
        val names = themes.map { it.fullName }.toTypedArray()
        val currentIdx = themes.indexOf(theme)

        AlertDialog.Builder(this)
            .setTitle("切换视觉与声效主题")
            .setSingleChoiceItems(names, currentIdx) { dialog, which ->
                val selected = themes[which]
                Theme.save(this, selected)
                theme = selected
                dialog.dismiss()
                setContentView(buildRootLayout())
                refreshPlayerUi()
                playPauseButton.requestFocus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createQrBitmap(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun roundedRect(radius: Float, fill: Int, strokeWidth: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }
}

// ------------------------------------------------------------------
// 自绘黑胶灵盘与专辑封面卡片 (VinylArtworkView)
// ------------------------------------------------------------------

class VinylArtworkView(context: Context) : View(context) {
    private var artwork: Bitmap? = null
    private var currentTheme: Theme = Theme.XIUXIAN
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setArtwork(bitmap: Bitmap?, theme: Theme) {
        artwork = bitmap
        currentTheme = theme
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)
        val rect = RectF((w - size) / 2f, (h - size) / 2f, (w + size) / 2f, (h + size) / 2f)

        val bmp = artwork
        if (bmp != null) {
            // 绘制圆角封面
            val radius = size * 0.08f
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            canvas.drawRoundRect(rect, radius, radius, paint)

            val srcRect = android.graphics.Rect(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, srcRect, rect, paint)

            // 边框微光
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = currentTheme.accentColor
            canvas.drawRoundRect(rect, radius, radius, paint)
        } else {
            // 自绘黑胶灵盘
            val cx = w / 2f
            val cy = h / 2f
            val r = size * 0.46f

            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(0x18, 0x1A, 0x1E)
            canvas.drawCircle(cx, cy, r, paint)

            // 同心纹理圈
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(0x35, 0xFF, 0xFF, 0xFF)
            paint.strokeWidth = 1.5f
            for (step in 1..4) {
                canvas.drawCircle(cx, cy, r * (0.35f + step * 0.13f), paint)
            }

            // 中心唱片标与灵玉色核
            paint.style = Paint.Style.FILL
            paint.color = currentTheme.accentColor
            canvas.drawCircle(cx, cy, r * 0.28f, paint)

            paint.color = currentTheme.windowBg
            canvas.drawCircle(cx, cy, r * 0.08f, paint)
        }
    }
}

// ------------------------------------------------------------------
// Canvas 硬件加速阻尼平滑居中滚动歌词 (LyricsScrollView)
// ------------------------------------------------------------------

class LyricsScrollView(context: Context) : View(context) {

    private var lyrics: ParsedLyrics? = null
    private var currentTheme: Theme = Theme.XIUXIAN
    private var currentPositionMs: Long = 0L

    private var currentScrollY = 0f
    private var targetScrollY = 0f

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLyrics(parsed: ParsedLyrics?, theme: Theme) {
        lyrics = parsed
        currentTheme = theme
        currentScrollY = 0f
        targetScrollY = 0f
        invalidate()
    }

    fun updatePosition(posMs: Long) {
        currentPositionMs = posMs
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val lrc = lyrics
        if (lrc == null || lrc.lines.isEmpty()) {
            drawEmptyState(canvas, w, h)
            return
        }

        val lines = lrc.lines
        val activeIdx = if (lrc.synced) {
            LrcParser.findActiveLineIndex(lines, currentPositionMs)
        } else {
            0
        }

        // 计算目标滚动偏移（将当前高亮行垂直居中）
        val lineHeight = dp(46).toFloat()
        targetScrollY = activeIdx * lineHeight

        // 阻尼平滑插值 (EMA)
        val diff = targetScrollY - currentScrollY
        if (abs(diff) > 0.5f) {
            currentScrollY += diff * 0.22f
            postInvalidateOnAnimation()
        } else {
            currentScrollY = targetScrollY
        }

        val centerY = h / 2f

        for (i in lines.indices) {
            val lineY = centerY + (i * lineHeight) - currentScrollY
            if (lineY < -lineHeight || lineY > h + lineHeight) continue

            val distFromCenter = abs(lineY - centerY)
            val alphaFactor = (1f - (distFromCenter / (h * 0.52f))).coerceIn(0.12f, 1f)

            if (i == activeIdx && lrc.synced) {
                // 当前演唱高亮行 (24sp，加粗辉光)
                textPaint.textSize = dp(24).toFloat()
                textPaint.typeface = Typeface.DEFAULT_BOLD
                textPaint.color = currentTheme.accentColor
                textPaint.setShadowLayer(8f, 0f, 1f, currentTheme.accentColor)
                textPaint.alpha = (alphaFactor * 255).toInt()
            } else {
                // 邻近行 (17sp，渐隐透明度)
                textPaint.textSize = dp(17).toFloat()
                textPaint.typeface = Typeface.DEFAULT
                textPaint.clearShadowLayer()
                textPaint.color = Color.WHITE
                textPaint.alpha = (alphaFactor * 160).toInt()
            }

            canvas.drawText(lines[i].text, dp(12).toFloat(), lineY + dp(6), textPaint)
        }
    }

    private fun drawEmptyState(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f

        // 绘制 5 柱动态律动波形
        wavePaint.color = currentTheme.accentColor
        wavePaint.style = Paint.Style.FILL
        val barWidth = dp(7).toFloat()
        val spacing = dp(14).toFloat()
        val timeNow = System.currentTimeMillis() / 220.0

        for (i in -2..2) {
            val phase = timeNow + i * 0.9
            val barHeight = dp(16) + (sin(phase) * dp(14)).toFloat().coerceAtLeast(dp(4).toFloat())
            val bx = cx + (i * spacing) - (barWidth / 2f)
            val by = cy - (barHeight / 2f)
            canvas.drawRoundRect(RectF(bx, by, bx + barWidth, by + barHeight), 6f, 6f, wavePaint)
        }

        // 提示文本
        textPaint.textSize = dp(15).toFloat()
        textPaint.typeface = Typeface.DEFAULT
        textPaint.clearShadowLayer()
        textPaint.color = currentTheme.hintColor
        textPaint.alpha = 200
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("纯音乐 · 或正在从灵网检索歌词", cx, cy + dp(40), textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
