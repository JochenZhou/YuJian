package com.zqc.tvnotify

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.max

class SendspinActivity : Activity() {
    companion object {
        @Volatile private var active: SendspinActivity? = null

        fun closeCurrent() {
            active?.runOnUiThread { active?.finish() }
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var artwork: ImageView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var progress: SeekBar
    private lateinit var position: TextView
    private lateinit var playPause: Button
    private lateinit var volume: TextView
    private var runtime: SendspinRuntime? = null
    private var seekMaxMs = 0L
    private var seekPending = false
    private var currentStatus = SendspinRuntime.Status()
    private val listener: (SendspinRuntime.Status) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        runtime = SendspinRuntime.current() ?: SendspinRuntime.init(this).also { it.start() }
        buildUi()
        runtime?.addListener(listener)
        playPause.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        runtime?.setActivityVisible(true)
    }

    override fun onPause() {
        runtime?.setActivityVisible(false)
        super.onPause()
    }

    override fun onDestroy() {
        runtime?.removeListener(listener)
        if (active === this) active = null
        super.onDestroy()
    }

    private fun buildUi() {
        val theme = Theme.current(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(22), dp(30), dp(22))
            setBackgroundColor(theme.windowBg)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = button("返回") { finish() }
        top.addView(back)
        status = text("正在发现 Music Assistant…", 15f, theme.accentColor).apply { gravity = Gravity.END }
        top.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        root.addView(top, params(bottom = dp(18)))

        artwork = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(theme.cardBg)
            contentDescription = "专辑封面"
        }
        root.addView(artwork, LinearLayout.LayoutParams(dp(300), dp(300)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        title = text("未播放", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER; maxLines = 2 }
        root.addView(title, params(top = dp(18)))
        artist = text("", 17f, theme.hintColor).apply { gravity = Gravity.CENTER }
        root.addView(artist, params(top = dp(6)))
        album = text("", 13f, theme.hintColor).apply { gravity = Gravity.CENTER }
        root.addView(album, params(top = dp(2)))

        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        position = text("0:00", 12f, theme.hintColor)
        timeRow.addView(position)
        progress = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        position.text = formatTime(seekMaxMs * value / 1000L)
                        seekPending = true
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { seekPending = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val state = currentStatus
                    if ("seek" in state.commands && seekMaxMs > 0) {
                        // Sendspin's upstream client exposes absolute and relative seek separately.
                        // MA receiver playback supports seek via controller@v1; use the bounded relative path.
                        runtime?.command(
                            "seek",
                            positionMs = (seekMaxMs * (seekBar?.progress ?: 0) / 1000L).coerceIn(0L, seekMaxMs)
                        )
                    }
                    seekPending = false
                }
            })
        }
        timeRow.addView(progress, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(10); rightMargin = dp(10) })
        val duration = text("0:00", 12f, theme.hintColor)
        timeRow.addView(duration)
        root.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); leftMargin = dp(40); rightMargin = dp(40)
        })
        progress.tag = duration

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(button("上一首") { send("previous") })
        playPause = button("播放 / 暂停") { togglePlayback() }
        controls.addView(playPause, LinearLayout.LayoutParams(dp(170), dp(54)).apply { leftMargin = dp(12); rightMargin = dp(12) })
        controls.addView(button("下一首") { send("next") })
        root.addView(controls, params(top = dp(12)))

        val lower = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        volume = text("音量 100%", 14f, theme.hintColor)
        lower.addView(volume, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(14) })
        lower.addView(button("− 音量") { adjustVolume(-5) })
        lower.addView(button("+ 音量") { adjustVolume(5) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        lower.addView(button("静音") { toggleMute() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        lower.addView(button("连接设置") { showConnectionDialog() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(18) })
        lower.addView(button("自动弹出") { toggleAutoPopup() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(lower, params(top = dp(12)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }

    private fun render(state: SendspinRuntime.Status) {
        currentStatus = state
        status.text = when (state.state) {
            "connected" -> "已连接 · ${state.server.ifBlank { "Music Assistant" }}"
            "connecting" -> "正在连接…"
            "discovering", "idle" -> "正在发现 Music Assistant…"
            "error" -> state.error.ifBlank { "连接失败" }
            "disabled" -> "Sendspin 已停用"
            "stopped" -> "服务已停止"
            else -> state.error.ifBlank { "未连接" }
        }
        title.text = state.title.ifBlank { if (state.error.isNotEmpty()) "连接提示" else "未播放" }
        artist.text = state.artist
        album.text = state.album
        val duration = state.durationMs.coerceAtLeast(0L)
        seekMaxMs = duration
        if (!seekPending) {
            progress.progress = if (duration > 0) (state.progressMs.coerceIn(0L, duration) * 1000L / duration).toInt() else 0
            position.text = formatTime(state.progressMs)
        }
        (progress.tag as? TextView)?.text = formatTime(duration)
        volume.text = "音量 ${state.volume.coerceIn(0, 100)}%${if (state.muted) " · 静音" else ""}"
        playPause.text = if (state.playing) "暂停" else "播放"
        val art = state.artwork
        if (art != null && art.isNotEmpty()) {
            val bitmap = BitmapFactory.decodeByteArray(art, 0, art.size)
            if (bitmap != null) artwork.setImageBitmap(bitmap) else artwork.setImageDrawable(null)
        } else {
            artwork.setImageDrawable(null)
        }
        playPause.isEnabled = state.state == "connected" && ("play" in state.commands || "pause" in state.commands)
    }

    private fun togglePlayback() {
        val command = if (currentStatus.playing) "pause" else "play"
        if (!send(command)) Toast.makeText(this, "Music Assistant 未公布此控制命令", Toast.LENGTH_SHORT).show()
    }

    private fun adjustVolume(delta: Int) {
        if ("volume" !in currentStatus.playerCommands) {
            Toast.makeText(this, "服務端未提供音量控制", Toast.LENGTH_SHORT).show()
            return
        }
        val target = (currentStatus.volume + delta).coerceIn(0, 100)
        if (runtime?.command("volume", volume = target) != true) {
            Toast.makeText(this, "音量控制未生效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun send(command: String): Boolean {
        if (command !in currentStatus.controllerCommands) return false
        return runtime?.command(command) == true
    }

    private fun toggleMute() {
        if ("mute" !in currentStatus.playerCommands) {
            Toast.makeText(this, "播放器未提供静音控制", Toast.LENGTH_SHORT).show()
            return
        }
        if (runtime?.command("mute", muted = !currentStatus.muted) != true) {
            Toast.makeText(this, "静音控制未生效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConnectionDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "ws://music-assistant:8927/sendspin"
            setText(Prefs.getSendspinServerUrl(this@SendspinActivity))
            setSingleLine(true)
        }
        val auto = Button(this).apply {
            text = "清除地址并使用自动发现"
            setOnClickListener {
                runtime?.reconnectByDiscovery()
                Toast.makeText(this@SendspinActivity, "正在发现 Sendspin 服务…", Toast.LENGTH_SHORT).show()
            }
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0); addView(input); addView(auto) }
        AlertDialog.Builder(this)
            .setTitle("Music Assistant 连接")
            .setMessage("使用 MA 的 Sendspin 服务（默认端口 8927）。此客户端使用 legacy client 兼容模式，不支持 Noise/PSK 配对。")
            .setView(column)
            .setPositiveButton("连接") { _, _ -> runtime?.connect(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleAutoPopup() {
        val next = !Prefs.isSendspinAutoPopup(this)
        Prefs.setSendspinAutoPopup(this, next)
        Toast.makeText(this, if (next) "收到播放时尝试打开音乐页" else "已关闭自动弹出", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> { send("play"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { send("pause"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { send("next"); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { send("previous"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isFocusable = true
        isFocusableInTouchMode = false
        minHeight = dp(48)
        setTextColor(Color.WHITE)
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun params(top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top; bottomMargin = bottom; leftMargin = left; rightMargin = right
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun formatTime(ms: Long): String {
        val total = max(0L, ms / 1000L)
        return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }
}
