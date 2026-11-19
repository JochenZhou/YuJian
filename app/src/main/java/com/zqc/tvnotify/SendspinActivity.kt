package com.zqc.tvnotify

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.sendspin.protocol.ClockSync
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SendspinActivity : Activity() {
    companion object {
        @Volatile private var active: SendspinActivity? = null
        private const val PROGRESS_INTERVAL_MS = 125L
        private const val ARTWORK_MAX_SIZE = 768

        fun closeCurrent() {
            active?.runOnUiThread { active?.finish() }
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var stage: SendspinStageLayout
    private lateinit var leftColumn: LinearLayout
    private lateinit var artworkSwitcher: SendspinArtworkSwitcher
    private lateinit var lyricsView: LyricsScrollView
    private lateinit var ambient: SendspinAmbientDrawable
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var progress: SendspinSeekBar
    private lateinit var timeline: LinearLayout
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var controls: LinearLayout
    private lateinit var previous: SendspinIconButton
    private lateinit var playPause: SendspinIconButton
    private lateinit var next: SendspinIconButton
    private lateinit var back: SendspinIconButton
    private lateinit var volume: TextView
    private lateinit var volumeIcon: ImageView
    private lateinit var volumeControl: SendspinFocusPill
    private lateinit var playlistButton: SendspinFocusPill
    private lateinit var playlistLabel: TextView
    private lateinit var settings: SendspinFocusPill
    private lateinit var settingsLabel: TextView
    private lateinit var theme: Theme
    private var playerStyle = Prefs.SendspinPlayerStyle.VINYL
    private var accent = Color.WHITE
    private var playerBase = Color.BLACK
    private var runtime: SendspinRuntime? = null
    private var seekMaxMs = 0L
    private var seekPending = false
    private var awaitingSeek = false
    private var seekTargetMs = 0L
    private var seekSentAtUs = 0L
    private var seekSentAnchorUs = 0L
    private var currentStatus = SendspinRuntime.Status()
    private var visible = false
    private var destroyed = false
    private var appliedLayout = ""
    private var artworkExecutor: ExecutorService? = null
    private var artworkFuture: Future<*>? = null
    private var artworkGeneration = 0L
    private var artworkLoading = false
    private var requestedArtwork: ByteArray? = null
    private var appliedArtwork: ByteArray? = null
    private var lyricsGeneration = 0L
    private var lyricsLoading = false
    private var lastLyricsTrackKey = ""
    private var playerDialog: ThemedDialog? = null
    private var volumeDialogSeek: SendspinSeekBar? = null
    private var volumeDialogLabel: TextView? = null
    private var volumeDialogMute: SendspinIconButton? = null
    private var volumeDialogMinus: SendspinIconButton? = null
    private var volumeDialogPlus: SendspinIconButton? = null
    private var volumeDialogEditing = false
    private val main = Handler(Looper.getMainLooper())
    private val listener: (SendspinRuntime.Status) -> Unit = { if (visible && !destroyed) render(it) }
    private val progressTick = object : Runnable {
        override fun run() {
            if (!visible || destroyed) return
            updateProgress()
            main.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PlaylistStore.restoreIfEmpty(applicationContext)
        theme = Theme.current(this)
        playerStyle = Prefs.getSendspinPlayerStyle(this)
        // Keep the player's surfaces graphite, while retaining the selected theme's hue.
        playerBase = playerBlend(Color.rgb(17, 20, 25), theme.windowBg, .25f)
        accent = playerBlend(theme.accentColor, Color.rgb(192, 208, 210), .28f)
        window.statusBarColor = playerBase
        window.navigationBarColor = playerBase
        hideSystemBars()
        runtime = SendspinRuntime.current() ?: SendspinRuntime.init(this).also { it.start() }
        buildUi()
        render(runtime?.status() ?: currentStatus)
        if (playPause.isEnabled) playPause.requestFocus() else playlistButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        theme = Theme.current(this)
        visible = true
        runtime?.setActivityVisible(true)
        runtime?.addListener(listener)
        render(runtime?.status() ?: currentStatus)
        main.removeCallbacks(progressTick)
        main.post(progressTick)
    }

    override fun onPause() {
        visible = false
        runtime?.setActivityVisible(false)
        runtime?.removeListener(listener)
        main.removeCallbacksAndMessages(null)
        seekPending = false
        awaitingSeek = false
        cancelArtworkDecode()
        artworkExecutor?.shutdownNow()
        artworkExecutor = null
        artworkSwitcher.setPlaying(false)
        lyricsView.setPlaying(false)
        if (lyricsLoading) {
            lyricsGeneration++
            lyricsLoading = false
            lyricsView.setLoading(false)
            lastLyricsTrackKey = ""
        }
        for (view in arrayOf(back, artworkSwitcher, progress, previous, playPause, next, volumeControl, playlistButton, settings)) view.animate().cancel()
        playerDialog?.dismiss()
        playerDialog = null
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        runtime?.removeListener(listener)
        main.removeCallbacksAndMessages(null)
        cancelArtworkDecode()
        artworkExecutor?.shutdownNow()
        artworkExecutor = null
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutMetrics(newConfig.screenWidthDp, newConfig.screenHeightDp)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun buildUi() {
        ambient = SendspinAmbientDrawable(playerBase, accent)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ambient
            clipChildren = false
            clipToPadding = false
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        back = iconButton(SendspinIcon.BACK, "返回") { finish() }
        header.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))
        val brand = text("玉简 / SENDSPIN", 12f, Color.rgb(202, 212, 215), true).apply { letterSpacing = .16f; setSingleLine(true) }
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) })
        status = text("正在连接", 12f, Color.rgb(166, 182, 185)).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setSingleLine(true)
            compoundDrawablePadding = dp(7)
        }
        header.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        settingsLabel = text("设置", 12f, Color.rgb(176, 189, 193)).apply { setSingleLine(true) }
        settings = SendspinFocusPill(this, accent).apply {
            id = View.generateViewId()
            setPadding(dp(11), 0, dp(11), 0)
            contentDescription = "播放器设置"
            addView(ImageView(this@SendspinActivity).apply {
                setImageDrawable(SendspinIconDrawable(this@SendspinActivity, SendspinIcon.SETTINGS, Color.rgb(176, 189, 193), 18f))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(settingsLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            setOnClickListener { showSettingsDialog() }
        }
        header.addView(settings, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(12) })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        artworkSwitcher = SendspinArtworkSwitcher(this, accent).apply {
            id = View.generateViewId()
            setStyle(playerStyle)
            setOnClickListener { togglePlayerStyle() }
        }
        leftColumn.addView(artworkSwitcher, LinearLayout.LayoutParams(dp(200), dp(200)))

        title = text("音乐，在此刻", 26f, Color.rgb(245, 247, 247), true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
        }
        leftColumn.addView(title, params(top = dp(10)))
        artist = text("从 Music Assistant 选择音乐", 15f, Color.rgb(192, 201, 204)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        leftColumn.addView(artist, params(top = dp(4)))
        album = text("", 12.5f, Color.rgb(138, 152, 158)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        leftColumn.addView(album, params(top = dp(2)))

        timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        progress = SendspinSeekBar(this, accent).apply {
            id = View.generateViewId()
            contentDescription = "播放进度"
            onSeekCommit = { commitSeek() }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    seekPending = true
                    awaitingSeek = false
                    position.text = formatTime(seekMaxMs * value / max.toLong())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { seekPending = true; awaitingSeek = false }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { commitSeek() }
            })
        }
        timeline.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        position = text("0:00", 11.5f, Color.rgb(148, 163, 169)).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL); fontFeatureSettings = "'tnum'" }
        duration = text("0:00", 11.5f, Color.rgb(148, 163, 169)).apply { gravity = Gravity.END; fontFeatureSettings = "'tnum'" }
        times.addView(position, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(duration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        timeline.addView(times)
        leftColumn.addView(timeline, params(top = dp(8)))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        volumeControl = SendspinFocusPill(this, accent).apply {
            id = View.generateViewId()
            setPadding(dp(10), 0, dp(12), 0)
            setOnClickListener { showVolumeDialog() }
        }
        volumeIcon = ImageView(this).apply {
            setImageDrawable(SendspinIconDrawable(this@SendspinActivity, SendspinIcon.VOLUME, Color.rgb(176, 189, 193), 18f))
        }
        volumeControl.addView(volumeIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
        volume = text("100%", 12f, Color.rgb(176, 189, 193)).apply { setSingleLine(true) }
        volumeControl.addView(volume, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })

        previous = iconButton(SendspinIcon.PREVIOUS, "上一首") { send("previous") }
        playPause = iconButton(SendspinIcon.PLAY, "播放", primary = true) { togglePlayback() }
        next = iconButton(SendspinIcon.NEXT, "下一首") { send("next") }

        playlistLabel = text("歌单", 12f, Color.rgb(176, 189, 193)).apply { setSingleLine(true) }
        playlistButton = SendspinFocusPill(this, accent).apply {
            id = View.generateViewId()
            setPadding(dp(11), 0, dp(11), 0)
            contentDescription = "当前歌单详单"
            addView(ImageView(this@SendspinActivity).apply {
                setImageDrawable(SendspinIconDrawable(this@SendspinActivity, SendspinIcon.PLAYLIST, Color.rgb(176, 189, 193), 18f))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(playlistLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            setOnClickListener { showCurrentPlaylistOrCatalogDialog() }
        }

        controls.addView(volumeControl, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(previous, LinearLayout.LayoutParams(dp(46), dp(46)))
        controls.addView(playPause, LinearLayout.LayoutParams(dp(64), dp(64)).apply { leftMargin = dp(12); rightMargin = dp(12) })
        controls.addView(next, LinearLayout.LayoutParams(dp(46), dp(46)))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        controls.addView(playlistButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        leftColumn.addView(controls, params(top = dp(12)))

        lyricsView = LyricsScrollView(this, accent)
        stage = SendspinStageLayout(this, leftColumn, lyricsView)
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        applyLayoutMetrics(resources.configuration.screenWidthDp, resources.configuration.screenHeightDp)
        applyPlayerStyleState()
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                val density = resources.displayMetrics.density
                applyLayoutMetrics(((right - left) / density).toInt(), ((bottom - top) / density).toInt())
            }
        }
        setContentView(root)
    }

    private fun applyLayoutMetrics(widthDp: Int, heightDp: Int) {
        val stacked = widthDp < 680
        val compact = heightDp < 460 || widthDp < 760
        val tiny = heightDp < 360
        val hideSettingsText = widthDp < 840
        val vinylDp = when {
            stacked && tiny -> 110
            stacked -> 148
            tiny -> 124
            compact -> 164
            else -> (heightDp * 0.37f).toInt().coerceIn(168, 250)
        }
        val key = "$stacked/$compact/$tiny/$hideSettingsText/$vinylDp"
        if (appliedLayout == key) return
        appliedLayout = key

        root.setPadding(
            dp(if (compact) 24 else 40),
            dp(if (tiny) 10 else if (compact) 14 else 22),
            dp(if (compact) 24 else 40),
            dp(if (tiny) 10 else if (compact) 14 else 22)
        )
        val headerHeight = if (tiny) 32 else if (compact) 36 else 40
        header.layoutParams.height = dp(headerHeight)
        back.layoutParams = LinearLayout.LayoutParams(dp(headerHeight), dp(headerHeight))
        settings.layoutParams.height = dp(if (tiny) 30 else if (compact) 34 else 36)
        settingsLabel.visibility = if (hideSettingsText) View.GONE else View.VISIBLE
        (stage.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (tiny) 4 else if (compact) 8 else 12)

        artworkSwitcher.layoutParams = LinearLayout.LayoutParams(dp(vinylDp), dp(vinylDp))
        title.textSize = if (tiny) 19f else if (compact) 22f else 26f
        artist.textSize = if (compact) 13.5f else 15f
        album.textSize = if (compact) 11.5f else 12.5f
        (title.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (tiny) 6 else if (compact) 8 else 12)
        (artist.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (compact) 2 else 4)
        (album.layoutParams as LinearLayout.LayoutParams).topMargin = dp(2)
        (timeline.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (tiny) 4 else if (compact) 6 else 10)
        progress.layoutParams.height = dp(if (tiny) 26 else 30)
        position.textSize = if (compact) 11f else 11.5f
        duration.textSize = if (compact) 11f else 11.5f

        (controls.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (tiny) 6 else if (compact) 8 else 12)
        val secondarySize = dp(if (tiny) 38 else if (compact) 42 else 46)
        val primarySize = dp(if (tiny) 48 else if (compact) 54 else 64)
        previous.layoutParams = LinearLayout.LayoutParams(secondarySize, secondarySize)
        next.layoutParams = LinearLayout.LayoutParams(secondarySize, secondarySize)
        playPause.layoutParams = LinearLayout.LayoutParams(primarySize, primarySize).apply {
            leftMargin = dp(if (compact) 8 else 12)
            rightMargin = leftMargin
        }
        val pillHeight = dp(if (tiny) 34 else if (compact) 36 else 40)
        volumeControl.layoutParams.height = pillHeight
        playlistButton.layoutParams.height = pillHeight
        playlistLabel.visibility = if (hideSettingsText) View.GONE else View.VISIBLE
        root.requestLayout()
    }

    private fun render(state: SendspinRuntime.Status) {
        val latestStyle = Prefs.getSendspinPlayerStyle(this)
        if (latestStyle != playerStyle) {
            playerStyle = latestStyle
            applyPlayerStyleState()
        }
        val old = currentStatus
        val trackChanged = state.title != old.title || state.artist != old.artist || state.album != old.album
        if (trackChanged) {
            seekPending = false
            awaitingSeek = false
            cancelArtworkDecode()
        }
        currentStatus = state
        val connected = state.state == "connected"
        val isPlaying = connected && state.playing
        artworkSwitcher.setPlaying(isPlaying)
        lyricsView.setPlaying(isPlaying)

        val statusLabel = when (state.state) {
            "connected" -> if (state.playing) "正在播放" else "已连接"
            "connecting" -> "正在连接"
            "discovering", "idle" -> "正在发现"
            "error" -> "连接未完成"
            "disabled", "stopped" -> "服务未开启"
            else -> "等待连接"
        }
        setTextIfChanged(status, statusLabel)
        if (old.state != state.state || old.playing != state.playing || status.compoundDrawables[0] == null) {
            val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (connected) accent else Color.rgb(123, 137, 145)); setSize(dp(5), dp(5)); setBounds(0, 0, dp(5), dp(5)) }
            status.setCompoundDrawables(dot, null, null, null)
        }
        setTextIfChanged(title, state.title.ifBlank { "音乐，在此刻" })
        setTextIfChanged(artist, state.artist.ifBlank { if (state.title.isBlank()) "从 Music Assistant 选择音乐" else "" })
        setTextIfChanged(album, state.album)
        album.visibility = if (state.album.isBlank()) View.GONE else View.VISIBLE
        artist.visibility = if (artist.text.isEmpty()) View.GONE else View.VISIBLE
        seekMaxMs = state.durationMs.coerceAtLeast(0L)
        setTextIfChanged(duration, formatTime(seekMaxMs))
        progress.isEnabled = connected && "seek" in state.controllerCommands && seekMaxMs > 0L
        progress.keyProgressIncrement = if (seekMaxMs > 0L) (10_000.0 / seekMaxMs * progress.max).toInt().coerceIn(1, progress.max) else 1
        if (!progress.isEnabled) { seekPending = false; awaitingSeek = false }
        updateProgress()
        playPause.icon = if (state.playing) SendspinIcon.PAUSE else SendspinIcon.PLAY
        playPause.contentDescription = if (state.playing) "暂停" else "播放"
        playPause.isEnabled = connected && (if (state.playing) "pause" else "play") in state.controllerCommands
        previous.isEnabled = connected && "previous" in state.controllerCommands
        next.isEnabled = connected && "next" in state.controllerCommands
        val canAdjustVolume = connected && (
            "volume" in state.controllerCommands ||
                "mute" in state.controllerCommands ||
                "volume" in state.playerCommands ||
                "mute" in state.playerCommands
            )
        volumeControl.isEnabled = canAdjustVolume
        volumeControl.alpha = if (canAdjustVolume) 1f else .38f
        val volumeLabel = if (state.muted) "静音" else "${state.volume.coerceIn(0, 100)}%"
        setTextIfChanged(volume, volumeLabel)
        volumeControl.contentDescription = "${if (state.muted) "已静音" else "音量 ${state.volume.coerceIn(0, 100)}%"}，调整 Music Assistant 音量"
        if (old.muted != state.muted) {
            volumeIcon.setImageDrawable(SendspinIconDrawable(this, if (state.muted) SendspinIcon.MUTED else SendspinIcon.VOLUME, Color.rgb(176, 189, 193), 18f))
        }
        updateFocusTargets()
        updateVolumeDialog(state)
        updateArtwork(state.artwork)
        updateLyricsForTrack(state)
    }

    private fun updateFocusTargets() {
        val rowButtons = listOf(volumeControl, previous, playPause, next, playlistButton)
        val enabledRow = rowButtons.filter { it.isEnabled }
        val defaultTransport = when {
            playPause.isEnabled -> playPause.id
            previous.isEnabled -> previous.id
            next.isEnabled -> next.id
            volumeControl.isEnabled -> volumeControl.id
            else -> playlistButton.id
        }
        back.nextFocusDownId = artworkSwitcher.id
        back.nextFocusRightId = settings.id
        back.nextFocusLeftId = View.NO_ID
        settings.nextFocusLeftId = back.id
        settings.nextFocusRightId = View.NO_ID
        settings.nextFocusDownId = artworkSwitcher.id
        settings.nextFocusUpId = View.NO_ID
        artworkSwitcher.nextFocusUpId = back.id
        artworkSwitcher.nextFocusDownId = if (progress.isEnabled) progress.id else defaultTransport
        artworkSwitcher.nextFocusLeftId = artworkSwitcher.id
        artworkSwitcher.nextFocusRightId = artworkSwitcher.id
        progress.nextFocusUpId = artworkSwitcher.id
        progress.nextFocusDownId = defaultTransport
        for (i in enabledRow.indices) {
            val view = enabledRow[i]
            view.nextFocusUpId = if (progress.isEnabled) progress.id else artworkSwitcher.id
            view.nextFocusDownId = View.NO_ID
            view.nextFocusLeftId = if (i > 0) enabledRow[i - 1].id else View.NO_ID
            view.nextFocusRightId = if (i + 1 < enabledRow.size) enabledRow[i + 1].id else View.NO_ID
        }
        if (currentFocus?.isEnabled == false && playerDialog?.isShowing != true) playlistButton.requestFocus()
    }

    private fun projectedPosition(state: SendspinRuntime.Status = currentStatus, nowUs: Long = ClockSync.localMicros()): Long {
        val base = state.progressMs.coerceAtLeast(0L)
        if (state.progressUpdatedAtUs == 0L) return if (state.durationMs > 0) base.coerceAtMost(state.durationMs) else base
        return ClockSync.calculateProgress(
            baseProgressMs = base,
            baseLocalTimeMicros = state.progressUpdatedAtUs,
            playbackSpeed = if (state.playing && state.state == "connected") state.playbackSpeed else 0,
            durationMs = state.durationMs.coerceAtLeast(0L),
            nowMicros = max(nowUs, state.progressUpdatedAtUs),
        )
    }

    /** Only lightweight widgets update on the 125 ms visible-page tick. */
    private fun updateProgress() {
        val nowUs = ClockSync.localMicros()
        if (seekPending) {
            if (!awaitingSeek) return
            val acknowledged = currentStatus.progressUpdatedAtUs > seekSentAnchorUs && abs(projectedPosition(nowUs = nowUs) - seekTargetMs) < 2_000L
            if (!acknowledged && nowUs - seekSentAtUs < 1_800_000L) return
            seekPending = false
            awaitingSeek = false
        }
        val elapsed = projectedPosition(nowUs = nowUs)
        val value = if (seekMaxMs > 0L) (elapsed.toDouble() / seekMaxMs * progress.max).toInt().coerceIn(0, progress.max) else 0
        if (progress.progress != value) progress.progress = value
        setTextIfChanged(position, formatTime(elapsed))
        lyricsView.updatePosition(elapsed)
    }

    private fun commitSeek() {
        if (!seekPending || awaitingSeek) return
        if (!progress.isEnabled || seekMaxMs <= 0L) {
            seekPending = false
            updateProgress()
            return
        }
        seekTargetMs = (seekMaxMs.toDouble() * progress.progress / progress.max).toLong().coerceIn(0L, seekMaxMs)
        seekSentAtUs = ClockSync.localMicros()
        seekSentAnchorUs = currentStatus.progressUpdatedAtUs
        awaitingSeek = runtime?.command("seek", positionMs = seekTargetMs) == true
        seekPending = awaitingSeek
        if (!awaitingSeek) updateProgress()
    }

    private fun cancelArtworkDecode() {
        artworkGeneration++
        artworkFuture?.cancel(true)
        artworkFuture = null
        artworkLoading = false
    }

    private fun updateArtwork(incoming: ByteArray?) {
        val bytes = incoming?.takeIf { it.isNotEmpty() }
        val same = requestedArtwork === bytes || (requestedArtwork != null && bytes != null && requestedArtwork!!.contentEquals(bytes))
        if (!same) {
            cancelArtworkDecode()
            requestedArtwork = bytes
            appliedArtwork = null
            artworkSwitcher.setArtwork(null)
            artworkSwitcher.setAccentColor(accent)
            lyricsView.setAccentColor(accent)
            ambient.setAmbientColor(accent)
            ambient.setBlurredBitmap(null)
            applyPlayerStyleState()
        }
        if (bytes == null || !visible || destroyed || artworkLoading || appliedArtwork === requestedArtwork) return
        val snapshot = requestedArtwork ?: return
        val generation = artworkGeneration
        artworkLoading = true
        val executor = artworkExecutor ?: Executors.newSingleThreadExecutor { task -> Thread(task, "sendspin-player-art").apply { isDaemon = true } }.also { artworkExecutor = it }
        artworkFuture = executor.submit {
            val bitmap = decodeArtwork(snapshot)
            val blurred = if (bitmap != null && !Thread.currentThread().isInterrupted) {
                createBlurredArtworkBackdrop(bitmap)
            } else {
                null
            }
            if (Thread.currentThread().isInterrupted) return@submit
            main.post {
                if (!visible || destroyed || generation != artworkGeneration || requestedArtwork !== snapshot) return@post
                artworkLoading = false
                artworkFuture = null
                // Mark corrupt data handled as well, instead of retrying it every status update.
                appliedArtwork = snapshot
                if (bitmap != null) {
                    val artColor = sendspinArtworkColor(bitmap, accent)
                    artworkSwitcher.setArtwork(bitmap)
                    artworkSwitcher.setAccentColor(artColor)
                    lyricsView.setAccentColor(artColor)
                    ambient.setAmbientColor(artColor)
                    ambient.setBlurredBitmap(blurred)
                    applyPlayerStyleState()
                }
            }
        }
    }

    private fun togglePlayerStyle() {
        playerStyle = if (playerStyle == Prefs.SendspinPlayerStyle.VINYL) {
            Prefs.SendspinPlayerStyle.COVER_BLUR
        } else {
            Prefs.SendspinPlayerStyle.VINYL
        }
        Prefs.setSendspinPlayerStyle(this, playerStyle)
        applyPlayerStyleState()
        val msg = if (playerStyle == Prefs.SendspinPlayerStyle.COVER_BLUR) {
            "已切换为样式二：方形封面 · 高斯模糊背景（点击封面可切回）"
        } else {
            "已切换为样式一：经典黑胶（点击唱片可切换）"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun applyPlayerStyleState() {
        artworkSwitcher.setStyle(playerStyle)
        ambient.setPlayerStyle(playerStyle)
        val hasBlur = ambient.hasActiveBlurredBackdrop()
        lyricsView.setBlurredBackdropActive(hasBlur)
        val shadowRadius = if (hasBlur) 6f else 0f
        val shadowColor = if (hasBlur) Color.argb(145, 0, 0, 0) else Color.TRANSPARENT
        for (tv in arrayOf(status, title, artist, album, position, duration, volume, settingsLabel, playlistLabel)) {
            tv.setShadowLayer(shadowRadius, 0f, if (hasBlur) 1.5f else 0f, shadowColor)
        }
    }

    private fun updateLyricsForTrack(state: SendspinRuntime.Status) {
        val cleanTitle = state.title.trim()
        if (cleanTitle.isEmpty()) {
            if (lastLyricsTrackKey.isNotEmpty() || lyricsLoading) {
                lyricsGeneration++
                lyricsLoading = false
                lastLyricsTrackKey = ""
                lyricsView.setLoading(false)
                lyricsView.setLyrics(null)
            }
            return
        }
        val trackKey = "$cleanTitle|${state.artist.trim()}|${state.album.trim()}"
        if (trackKey == lastLyricsTrackKey) return

        lastLyricsTrackKey = trackKey
        val generation = ++lyricsGeneration
        lyricsLoading = true
        lyricsView.setLyrics(null)
        lyricsView.setLoading(true)

        LyricsFetcher.fetchAsync(
            context = this,
            title = cleanTitle,
            artist = state.artist,
            album = state.album,
            durationMs = state.durationMs
        ) { parsed ->
            main.post {
                if (destroyed || generation != lyricsGeneration) return@post
                lyricsLoading = false
                lyricsView.setLoading(false)
                lyricsView.setLyrics(parsed)
                lyricsView.updatePosition(projectedPosition())
            }
        }
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || Thread.currentThread().isInterrupted) null
        else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight).toLong() > ARTWORK_MAX_SIZE * sample.toLong()) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun togglePlayback() {
        if (!send(if (currentStatus.playing) "pause" else "play")) Toast.makeText(this, "当前无法控制播放", Toast.LENGTH_SHORT).show()
    }

    private fun send(command: String): Boolean {
        if (currentStatus.state != "connected" || command !in currentStatus.controllerCommands) return false
        return runtime?.command(command) == true
    }

    private fun adjustVolume(delta: Int) { setVolume((currentStatus.volume + delta).coerceIn(0, 100)) }

    private fun setVolume(target: Int) {
        if (currentStatus.state != "connected" ||
            ("volume" !in currentStatus.controllerCommands && "volume" !in currentStatus.playerCommands)
        ) return
        if (runtime?.command("volume", volume = target.coerceIn(0, 100)) != true) Toast.makeText(this, "音量调整暂不可用", Toast.LENGTH_SHORT).show()
    }

    private fun toggleMute() {
        if (currentStatus.state != "connected" ||
            ("mute" !in currentStatus.controllerCommands && "mute" !in currentStatus.playerCommands)
        ) return
        if (runtime?.command("mute", muted = !currentStatus.muted) != true) Toast.makeText(this, "静音控制暂不可用", Toast.LENGTH_SHORT).show()
    }

    private fun showVolumeDialog() {
        val palette = ThemeDialogPalette.from(theme)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)); clipChildren = false; clipToPadding = false }
        val label = text("", 18f, palette.titleColor).apply {
            gravity = Gravity.CENTER
            typeface = palette.titleTypeface
        }
        column.addView(label, params(bottom = dp(8)))
        var pending = false
        val sliderTrack = if (palette.isLightSurface) Color.argb(48, 34, 30, 26) else Color.argb(44, 255, 255, 255)
        val sliderDisabledTrack = if (palette.isLightSurface) Color.argb(24, 34, 30, 26) else Color.argb(22, 255, 255, 255)
        val slider = SendspinSeekBar(this, palette.accentColor, trackColor = sliderTrack, disabledTrackColor = sliderDisabledTrack).apply {
            id = View.generateViewId()
            max = 100
            keyProgressIncrement = 5
            contentDescription = "音量"
            onSeekCommit = {
                if (pending) {
                    pending = false
                    volumeDialogEditing = false
                    setVolume(progress)
                }
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { pending = true; volumeDialogEditing = true; label.text = "音量 $value%" }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { volumeDialogEditing = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) { onSeekCommit?.invoke(); volumeDialogEditing = false }
            })
        }
        column.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        val minus = dialogIconButton(SendspinIcon.MINUS, "降低音量", palette) { adjustVolume(-5) }
        val plus = dialogIconButton(SendspinIcon.PLUS, "提高音量", palette) { adjustVolume(5) }
        val mute = dialogIconButton(SendspinIcon.MUTED, "静音", palette) { toggleMute() }
        row.addView(minus, LinearLayout.LayoutParams(dp(48), dp(48)))
        row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(16) })
        row.addView(mute, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(28) })
        column.addView(row, params(top = dp(12)))
        playerDialog?.dismiss()
        volumeDialogSeek = slider
        volumeDialogLabel = label
        volumeDialogMute = mute
        volumeDialogMinus = minus
        volumeDialogPlus = plus
        volumeDialogEditing = false
        updateVolumeDialog(currentStatus)
        val dialog = ThemedDialogBuilder(this, theme)
            .setTitle("音量")
            .setView(column)
            .setMaxWidthDp(460)
            .setPositiveButton("完成")
            .create()
        dialog.setOnDismissListener {
            volumeDialogSeek = null; volumeDialogLabel = null; volumeDialogMute = null; volumeDialogMinus = null; volumeDialogPlus = null; volumeDialogEditing = false
            if (playerDialog === dialog) playerDialog = null
            hideSystemBars()
        }
        slider.nextFocusDownId = (if (minus.isEnabled) minus else mute).id
        dialog.positiveButton?.let { doneBtn ->
            for (btn in arrayOf(minus, plus, mute)) btn.nextFocusDownId = doneBtn.id
            doneBtn.nextFocusUpId = (if (mute.isEnabled) mute else slider).id
        }
        showPlayerDialog(dialog)
        if (slider.isEnabled) slider.requestFocus() else mute.requestFocus()
    }

    private fun updateVolumeDialog(state: SendspinRuntime.Status) {
        val connected = state.state == "connected"
        val canVolume = connected && ("volume" in state.controllerCommands || "volume" in state.playerCommands)
        volumeDialogSeek?.isEnabled = canVolume
        volumeDialogMinus?.isEnabled = canVolume
        volumeDialogPlus?.isEnabled = canVolume
        volumeDialogMute?.apply {
            isEnabled = connected && ("mute" in state.controllerCommands || "mute" in state.playerCommands)
            icon = if (state.muted) SendspinIcon.VOLUME else SendspinIcon.MUTED
            contentDescription = if (state.muted) "取消静音" else "静音"
        }
        if (!volumeDialogEditing) {
            volumeDialogSeek?.progress = state.volume.coerceIn(0, 100)
            val maTag = if ("volume" in state.controllerCommands) " · MA" else ""
            val muteTag = if (state.muted) " · 已静音" else ""
            volumeDialogLabel?.text = "音量 ${state.volume.coerceIn(0, 100)}%$maTag$muteTag"
        }
    }

    private fun showSettingsDialog() {
        val styleLabel = if (playerStyle == Prefs.SendspinPlayerStyle.VINYL) {
            "样式一（黑胶唱片）"
        } else {
            "样式二（方形封面 · 高斯模糊）"
        }
        val autoEnabled = Prefs.isSendspinAutoPopup(this)
        val savedUrl = Prefs.getSendspinServerUrl(this).trim()
        val serverSummary = if (savedUrl.isEmpty()) "当前使用局域网自动发现，点击可手动指定地址" else "当前地址：$savedUrl"
        val wledAudioEnabled = Prefs.isWledSyncEnabled(this)
        val wledPort = Prefs.getWledSyncPort(this)
        val wledColorEnabled = Prefs.isWledColorSyncEnabled(this)
        val wledHost = Prefs.getWledColorHost(this)
        val wledEffect = WledEffectPreset.fromRaw(Prefs.getWledEffect(this))
        val items = listOf(
            ThemeDialogItem(
                title = "浏览歌单",
                subtitle = "查看已同步歌单与曲目详单，或选歌点播",
                trailingBadge = "歌单",
                dismissOnClick = false
            ) {
                showPlaylistDialog()
            },
            ThemeDialogItem(
                title = "连接 Music Assistant",
                subtitle = serverSummary,
                dismissOnClick = false
            ) {
                showConnectionDialog()
            },
            ThemeDialogItem(
                title = "播放页样式：$styleLabel",
                subtitle = "备用入口：也可以直接点击左侧黑胶唱片或方形封面切换",
                trailingBadge = if (playerStyle == Prefs.SendspinPlayerStyle.VINYL) "样式一" else "样式二"
            ) {
                togglePlayerStyle()
            },
            ThemeDialogItem(
                title = "播放时自动打开：${if (autoEnabled) "已开启" else "已关闭"}",
                subtitle = "检测到局域网音乐推流播放时自动弹出此页面",
                trailingBadge = if (autoEnabled) "已开启" else "已关闭"
            ) {
                toggleAutoPopup()
            },
            ThemeDialogItem(
                title = "WLED 音乐律动：${if (wledAudioEnabled) "已开启" else "已关闭"}",
                subtitle = "向局域网组播 239.0.0.1:$wledPort 广播 AudioReactive V2 音频律动",
                trailingBadge = if (wledAudioEnabled) "已开启" else "已关闭"
            ) {
                toggleWledAudioSync()
            },
            ThemeDialogItem(
                title = "WLED 封面色彩同步：${if (wledColorEnabled) "已开启" else "已关闭"}",
                subtitle = "向 $wledHost 同步封面主色调（主色调为黑时自动采用次色调）",
                trailingBadge = if (wledColorEnabled) "已开启" else "已关闭"
            ) {
                toggleWledColorSync()
            },
            ThemeDialogItem(
                title = "推荐 WLED 律动效果：${wledEffect.displayName}",
                subtitle = "为 $wledHost 一键应用柔和耐看的律动效果预设（可选保持当前）",
                trailingBadge = if (wledEffect == WledEffectPreset.CUSTOM) "推荐" else "已选",
                dismissOnClick = false
            ) {
                showWledEffectsDialog()
            },
            ThemeDialogItem(
                title = "WLED 参数设置",
                subtitle = "组播端口 $wledPort · 色彩目标 $wledHost",
                trailingBadge = "配置",
                dismissOnClick = false
            ) {
                showWledConfigDialog()
            }
        )
        val dialog = ThemedDialogBuilder(this, theme)
            .setTitle("播放器设置")
            .setItems(items)
            .setMaxWidthDp(520)
            .setNegativeButton("关闭")
            .create()
        dialog.setOnDismissListener {
            if (playerDialog === dialog) playerDialog = null
            hideSystemBars()
        }
        showPlayerDialog(dialog)
    }

    private fun showCurrentPlaylistOrCatalogDialog() {
        val catalog = PlaylistStore.current()
        if (catalog == null || catalog.playlists.isEmpty()) {
            showPlaylistDialog()
            return
        }
        val active = PlaylistStore.resolveActivePlaylist(currentStatus.title, currentStatus.artist)
        if (active != null) {
            showPlaylistDetailDialog(active)
        } else {
            showPlaylistDialog()
        }
    }

    private fun showPlaylistDialog() {
        val catalog = PlaylistStore.current()
        if (catalog == null || catalog.playlists.isEmpty()) {
            val dialog = ThemedDialogBuilder(this, theme)
                .setTitle("歌单")
                .setMessage("尚未同步到歌单。请确认 Home Assistant 的玉简集成已开启「同步歌单」，电视与 HA 在同一局域网，稍后再试。")
                .setPositiveButton("知道了")
                .create()
            dialog.setOnDismissListener {
                if (playerDialog === dialog) playerDialog = null
                hideSystemBars()
            }
            showPlayerDialog(dialog)
            return
        }
        val activeId = PlaylistStore.resolveActivePlaylist(currentStatus.title, currentStatus.artist)?.id
        val items = catalog.playlists.map { entry ->
            val isCurrent = entry.id == activeId
            val countLabel = entry.effectiveTrackCount?.let { "$it 首" }
            ThemeDialogItem(
                title = entry.name,
                subtitle = if (isCurrent && countLabel != null) "当前歌单 · $countLabel" else if (isCurrent) "当前歌单" else countLabel,
                trailingBadge = if (isCurrent) "当前" else "详情",
                selected = isCurrent,
                dismissOnClick = false
            ) {
                showPlaylistDetailDialog(entry)
            }
        }
        val dialog = ThemedDialogBuilder(this, theme)
            .setTitle("歌单（${catalog.playlists.size}）")
            .setItems(items)
            .setMaxWidthDp(560)
            .setNegativeButton("关闭")
            .create()
        dialog.setOnDismissListener {
            if (playerDialog === dialog) playerDialog = null
            hideSystemBars()
        }
        showPlayerDialog(dialog)
    }

    private fun showPlaylistDetailDialog(entry: PlaylistEntry) {
        val catalog = PlaylistStore.current()
        val playlistCount = catalog?.playlists?.size ?: 0
        val trackCountText = entry.effectiveTrackCount?.let { "共 $it 首" }
        val items = mutableListOf<ThemeDialogItem>()
        if (playlistCount > 1) {
            items.add(
                ThemeDialogItem(
                    title = "⇄ 切换其他歌单",
                    subtitle = "已同步 $playlistCount 张歌单，点击切换",
                    trailingBadge = "全部歌单",
                    dismissOnClick = false
                ) {
                    showPlaylistDialog()
                }
            )
        }
        items.add(
            ThemeDialogItem(
                title = "▶ 从头播放整张歌单",
                subtitle = trackCountText ?: "从第 1 首开始播放整张歌单",
                trailingBadge = "整单",
                dismissOnClick = false
            ) { dialog ->
                dialog.dismiss()
                playPlaylist(entry)
            }
        )
        for ((index, track) in entry.tracks.withIndex()) {
            val number = String.format(Locale.ROOT, "%02d", index + 1)
            val isPlayingTrack = track.matchesPlaying(currentStatus.title, currentStatus.artist)
            items.add(
                ThemeDialogItem(
                    title = "$number. ${track.name}",
                    subtitle = track.artist,
                    trailingBadge = if (isPlayingTrack) "正在播放" else "点播",
                    selected = isPlayingTrack,
                    dismissOnClick = false
                ) { dialog ->
                    dialog.dismiss()
                    playTrack(entry, track)
                }
            )
        }
        val emptyTracksNote = if (entry.tracks.isEmpty()) {
            "暂未收到该歌单的曲目列表，可直接点击上方整单播放；请将 HA 玉简集成更新至最新版以同步歌单详单。"
        } else {
            null
        }
        val dialog = ThemedDialogBuilder(this, theme)
            .setTitle(entry.name + (entry.effectiveTrackCount?.let { "（$it 首）" }.orEmpty()))
            .setMessage(emptyTracksNote)
            .setItems(items)
            .setMaxWidthDp(580)
            .setNegativeButton("关闭")
            .create()
        dialog.setOnDismissListener {
            if (playerDialog === dialog) playerDialog = null
            hideSystemBars()
        }
        showPlayerDialog(dialog)
    }

    private fun playPlaylist(entry: PlaylistEntry) {
        val catalog = PlaylistStore.current()
        if (catalog == null || catalog.callbackUrl.isBlank()) {
            Toast.makeText(this, "播放回调未配置，请先在 HA 侧开启歌单同步", Toast.LENGTH_SHORT).show()
            return
        }
        PlaylistStore.markActivePlaylist(entry.id)
        Toast.makeText(this, "正在请求播放「${entry.name}」…", Toast.LENGTH_SHORT).show()
        PlaylistPlayer.request(
            url = catalog.callbackUrl,
            token = catalog.callbackToken,
            id = entry.id,
            name = entry.name
        ) { ok, error ->
            main.post {
                Toast.makeText(
                    this,
                    if (ok) "已请求播放「${entry.name}」" else (error ?: "点播失败"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun playTrack(entry: PlaylistEntry, track: PlaylistTrack) {
        val catalog = PlaylistStore.current()
        if (catalog == null || catalog.callbackUrl.isBlank()) {
            Toast.makeText(this, "播放回调未配置，请先在 HA 侧开启歌单同步", Toast.LENGTH_SHORT).show()
            return
        }
        PlaylistStore.markActivePlaylist(entry.id)
        Toast.makeText(this, "正在点播「${track.name}」…", Toast.LENGTH_SHORT).show()
        PlaylistPlayer.request(
            url = catalog.callbackUrl,
            token = catalog.callbackToken,
            id = entry.id,
            name = entry.name,
            trackId = track.id,
            trackName = track.name
        ) { ok, error ->
            main.post {
                Toast.makeText(
                    this,
                    if (ok) "已点播「${track.name}」" else (error ?: "点播失败"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showConnectionDialog() {
        val palette = ThemeDialogPalette.from(theme)
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "ws://music-assistant:8927/sendspin"
            setText(Prefs.getSendspinServerUrl(this@SendspinActivity))
            setTextSize(14f)
            setSingleLine(true)
            palette.styleEditText(this, density)
        }
        val auto = Button(this).apply {
            id = View.generateViewId()
            text = "使用自动发现"
            isAllCaps = false
            setTextColor(palette.titleColor)
            typeface = palette.titleTypeface
            backgroundTintList = null
            background = palette.buildButtonBackground(density, emphasized = true)
            stateListAnimator = null
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(110L).start()
                setTextColor(if (hasFocus) palette.focusedTextColor else palette.titleColor)
            }
            setOnClickListener {
                runtime?.reconnectByDiscovery()
                playerDialog?.dismiss()
                Toast.makeText(this@SendspinActivity, "正在发现 Music Assistant…", Toast.LENGTH_SHORT).show()
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
            addView(input, params())
            addView(auto, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
        }
        val dialog = ThemedDialogBuilder(this, theme)
            .setTitle("连接 Music Assistant")
            .setMessage("输入服务地址，或在同一网络中自动发现。")
            .setView(column)
            .setMaxWidthDp(500)
            .setPositiveButton("连接") { runtime?.connect(input.text.toString()) }
            .setNegativeButton("取消")
            .create()
        input.nextFocusDownId = auto.id
        auto.nextFocusUpId = input.id
        dialog.positiveButton?.let { posBtn ->
            auto.nextFocusDownId = posBtn.id
            posBtn.nextFocusUpId = auto.id
        }
        dialog.negativeButton?.nextFocusUpId = auto.id
        dialog.setOnDismissListener {
            if (playerDialog === dialog) playerDialog = null
            hideSystemBars()
        }
        showPlayerDialog(dialog)
    }

    private fun showPlayerDialog(dialog: ThemedDialog) {
        playerDialog?.dismiss()
        playerDialog = dialog
        dialog.show()
    }

    private fun dialogIconButton(
        icon: SendspinIcon,
        label: String,
        palette: ThemeDialogPalette,
        action: () -> Unit
    ): SendspinIconButton = SendspinIconButton(
        context = this,
        icon = icon,
        accent = palette.accentColor,
        primary = false,
        iconColor = palette.titleColor,
        radiusDp = palette.buttonCornerRadiusDp,
        idleFillColor = palette.itemBg,
        idleStrokeColor = palette.itemStroke
    ).apply {
        id = View.generateViewId()
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun toggleAutoPopup() {
        val enabled = !Prefs.isSendspinAutoPopup(this)
        Prefs.setSendspinAutoPopup(this, enabled)
        Toast.makeText(this, if (enabled) "播放时自动打开音乐页" else "已关闭自动打开", Toast.LENGTH_SHORT).show()
    }

    private fun toggleWledAudioSync() {
        val enabled = !Prefs.isWledSyncEnabled(this)
        Prefs.setWledSyncEnabled(this, enabled)
        val port = Prefs.getWledSyncPort(this)
        runtime?.configureWledAudioSync(enabled, port)
        Toast.makeText(
            this,
            if (enabled) "已开启 WLED 音乐律动（239.0.0.1:$port）" else "已关闭 WLED 音乐律动",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleWledColorSync() {
        val enabled = !Prefs.isWledColorSyncEnabled(this)
        Prefs.setWledColorSyncEnabled(this, enabled)
        val host = Prefs.getWledColorHost(this)
        runtime?.configureWledColorSync(enabled, host)
        Toast.makeText(
            this,
            if (enabled) "已开启 WLED 封面色彩同步（$host）" else "已关闭 WLED 封面色彩同步",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showWledEffectsDialog() {
        val current = WledEffectPreset.fromRaw(Prefs.getWledEffect(this))
        val wledHost = Prefs.getWledColorHost(this)
        val items = WledEffectPreset.entries.map { preset ->
            val selected = preset == current
            ThemeDialogItem(
                title = preset.displayName,
                subtitle = preset.subtitle,
                trailingBadge = when {
                    selected -> "当前"
                    preset == WledEffectPreset.GRAVCENTRIC -> "推荐"
                    preset == WledEffectPreset.GRAVFREQ -> "频谱"
                    else -> "切换"
                },
                selected = selected
            ) {
                Prefs.setWledEffect(this, preset.storageId)
                if (preset == WledEffectPreset.CUSTOM) {
                    Toast.makeText(this, "已设为保持灯带当前效果", Toast.LENGTH_SHORT).show()
                } else {
                    val activeRuntime = runtime
                    if (activeRuntime != null) {
                        activeRuntime.applyWledEffect(preset) { _, msg ->
                            main.post {
                                if (!destroyed) {
                                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(this, "已选择 WLED 效果：${preset.displayName}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val dialog = ThemedDialogBuilder(this, theme)
            .setTitle("推荐 WLED 律动效果")
            .setMessage("目标设备：$wledHost · 以下效果已针对舒缓音乐律动调优；选择「保持当前效果」则完全不改变灯带自身预设。")
            .setItems(items)
            .setMaxWidthDp(560)
            .setNegativeButton("关闭")
            .create()
        dialog.setOnDismissListener {
            if (playerDialog === dialog) playerDialog = null
            hideSystemBars()
        }
        showPlayerDialog(dialog)
    }

    private fun showWledConfigDialog() {
        val palette = ThemeDialogPalette.from(theme)
        val density = resources.displayMetrics.density
        val portLabel = text("AudioReactive UDP 端口（默认 ${Prefs.DEFAULT_WLED_SYNC_PORT}）", 12f, palette.mutedColor, bold = true)
        val portInput = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = Prefs.DEFAULT_WLED_SYNC_PORT.toString()
            setText(Prefs.getWledSyncPort(this@SendspinActivity).toString())
            setTextSize(14f)
            setSingleLine(true)
            palette.styleEditText(this, density)
        }
        val hostLabel = text("WLED 设备 IP / 地址（用于封面色彩同步，默认 ${Prefs.DEFAULT_WLED_COLOR_HOST}）", 12f, palette.mutedColor, bold = true)
        val hostInput = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = Prefs.DEFAULT_WLED_COLOR_HOST
            setText(Prefs.getWledColorHost(this@SendspinActivity))
            setTextSize(14f)
            setSingleLine(true)
            palette.styleEditText(this, density)
        }
        val errorText = text("", 12f, theme.bannerIconColor(BannerLevel.ERROR), bold = true).apply {
            visibility = View.GONE
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
            addView(portLabel, params(bottom = dp(6)))
            addView(portInput, params(bottom = dp(12)))
            addView(hostLabel, params(bottom = dp(6)))
            addView(hostInput, params())
            addView(errorText, params(top = dp(8)))
        }
        val dialog = ThemedDialogBuilder(this, theme)
            .setTitle("WLED 参数设置")
            .setMessage("音乐律动广播到局域网组播 239.0.0.1；封面色彩同步仅更新目标灯带的当前主色，不改效果与亮度。")
            .setView(column)
            .setMaxWidthDp(520)
            .setPositiveButton("保存", autoDismiss = false) { dlg ->
                val port = Prefs.parseWledSyncPort(portInput.text.toString())
                if (port == null) {
                    errorText.text = "请输入有效 UDP 端口（1..65535）"
                    errorText.visibility = View.VISIBLE
                    portInput.requestFocus()
                    return@setPositiveButton
                }
                val host = Prefs.normalizeWledHost(hostInput.text.toString())
                if (host == null) {
                    errorText.text = "请输入有效 WLED 设备 IP 或主机名"
                    errorText.visibility = View.VISIBLE
                    hostInput.requestFocus()
                    return@setPositiveButton
                }
                errorText.visibility = View.GONE
                Prefs.setWledSyncPort(this, port)
                Prefs.setWledColorHost(this, host)
                runtime?.configureWledAudioSync(Prefs.isWledSyncEnabled(this), port)
                runtime?.configureWledColorSync(Prefs.isWledColorSyncEnabled(this), host)
                dlg.dismiss()
                Toast.makeText(this, "已保存 WLED 参数（端口 $port · $host）", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("恢复默认", autoDismiss = false) {
                portInput.setText(Prefs.DEFAULT_WLED_SYNC_PORT.toString())
                hostInput.setText(Prefs.DEFAULT_WLED_COLOR_HOST)
                errorText.visibility = View.GONE
            }
            .setNegativeButton("取消")
            .create()
        portInput.nextFocusDownId = hostInput.id
        hostInput.nextFocusUpId = portInput.id
        val firstButtonId = dialog.positiveButton?.id ?: dialog.negativeButton?.id ?: View.NO_ID
        if (firstButtonId != View.NO_ID) {
            hostInput.nextFocusDownId = firstButtonId
        }
        dialog.positiveButton?.nextFocusUpId = hostInput.id
        dialog.negativeButton?.nextFocusUpId = hostInput.id
        dialog.neutralButton?.nextFocusUpId = hostInput.id
        dialog.setOnDismissListener {
            if (playerDialog === dialog) playerDialog = null
            hideSystemBars()
        }
        showPlayerDialog(dialog)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> { send("play"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { send("pause"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { send("next"); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { send("previous"); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { send("stop"); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun iconButton(icon: SendspinIcon, label: String, primary: Boolean = false, action: () -> Unit): SendspinIconButton =
        SendspinIconButton(this, icon, accent, primary).apply {
            id = View.generateViewId()
            contentDescription = label
            setOnClickListener { action() }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun setTextIfChanged(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }

    private fun params(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top; bottomMargin = bottom }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private fun formatTime(ms: Long): String {
        val total = max(0L, ms / 1000L)
        return if (total >= 3600L) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
        else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
    }
}
