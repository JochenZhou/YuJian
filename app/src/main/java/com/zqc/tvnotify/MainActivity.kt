package com.zqc.tvnotify

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 电视端设置与大屏仪表盘界面：全部基于 Android Framework View 纯代码构建。
 *
 * 优化特性：
 * 1. 顶部一级三页签 + 焦点即切：通过「总览与互联」「演法调阵 / 演练调控」「符钥天机 / 系统运维」
 *    三大可切换主页标签组织功能模块，遥控器焦点移上即刻切换，告别单屏多排纵向拥挤；
 * 2. 电视横屏双栏排版：每个页签内部均采用左右对称双栏卡片布局，在 1080p 电视大屏上一屏尽显；
 * 3. 遥控器友好交互：遥控器方向键（D-Pad）在顶部页签与各卡片按钮间平滑流转，
 *    锁定左右边界防误触越界，按返回键先回到顶部当前页签；
 * 4. 仙侠玉简美学浸润：支持「修仙玉简」「大唐鎏金」「方块世界」等 9 大主题一键切换；
 * 5. 丰富调控：Sendspin 音乐直达、TTS 语音试诵、传音简录历史回看、默认显形方位切换、
 *    闭关免扰（静音）模式，以及双模式二维码极速互联。
 */
class MainActivity : Activity() {

    enum class QrTab {
        HA_INTEGRATION,
        WEB_CONSOLE
    }

    companion object {
        private const val TAG = "TVNotifyMain"
        private const val REQUEST_CODE_NOTIFICATION = 1001
        private const val QR_PIXEL_SIZE = 188
        private const val STATE_ACTIVE_MAIN_TAB = "active_main_tab"
        const val HA_INTEGRATION_URL = "https://github.com/JochenZhou/ha-yujian"
        const val APP_REPO_URL = "https://github.com/JochenZhou/YuJian"

        private val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var overviewTabButton: Button
    private lateinit var practiceTabButton: Button
    private lateinit var systemTabButton: Button

    private lateinit var overviewPage: View
    private lateinit var practicePage: View
    private lateinit var systemPage: View
    private lateinit var focusOverlay: TvFocusGlowOverlay

    private lateinit var headerBadgeView: TextView
    private lateinit var stateMetricValue: TextView
    private lateinit var overlayMetricValue: TextView
    private lateinit var ipMetricValue: TextView
    private lateinit var wledMetricValue: TextView
    private lateinit var musicHeroTitle: TextView
    private lateinit var musicHeroSubtitle: TextView
    private lateinit var wledSummaryBadge: TextView
    private lateinit var tokenValue: TextView
    private lateinit var urlValue: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrTitleView: TextView
    private lateinit var qrSubtitleView: TextView
    private lateinit var qrCaptionView: TextView
    private lateinit var haTabButton: Button
    private lateinit var tokenTabButton: Button

    // Tab 1 首页：音乐中枢主按钮 + 日常快捷入口
    private lateinit var musicButton: Button
    private lateinit var quickHistoryButton: Button
    private lateinit var quickThemeButton: Button
    private lateinit var quickConnectButton: Button

    // Tab 2 左卡：演练测试
    private lateinit var testButton: Button
    private lateinit var imageTestButton: Button
    private lateinit var videoTestButton: Button
    private lateinit var ttsTestButton: Button

    // Tab 2 右卡：通知与外观调控
    private lateinit var historyButton: Button
    private lateinit var positionButton: Button
    private lateinit var muteButton: Button
    private lateinit var themeButton: Button
    private lateinit var controlHintView: TextView

    // Tab 3 左卡：互联与系统管理
    private lateinit var copyButton: Button
    private lateinit var resetButton: Button
    private lateinit var launchPlayerButton: Button
    private lateinit var aboutButton: Button
    private lateinit var updateButton: Button

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var callInfoTitleView: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    private var bannerController: BannerController? = null
    private var activeDialog: ThemedDialog? = null
    private var currentUrl: String = ""
    private var webConsoleUrl: String = ""
    private var cachedIp: String = "127.0.0.1"
    private var theme: Theme = Theme.XIUXIAN
    private var activeMainTab: MainTab = MainTab.OVERVIEW
    private var activeQrTab: QrTab = QrTab.HA_INTEGRATION
    private var currentlyRenderedContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = Theme.current(this)
        activeMainTab = savedInstanceState?.getString(STATE_ACTIVE_MAIN_TAB)
            ?.let { raw -> MainTab.entries.firstOrNull { it.name == raw } }
            ?: Prefs.resolveAndConsumeInitialMainTab(this)
        bannerController = BannerController(this)
        setContentView(buildRootView())
        requestNotificationPermissionIfNeeded()
        refreshUi()

        NotifyService.start(this)
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 600L)
        mainHandler.postDelayed({ if (!isFinishing) refreshUi() }, 1600L)

        // 启动时后台静默检查更新，发现新版时提醒
        AppUpdater.checkUpdate(this, manual = false) {
            if (!isFinishing) {
                updateButton.text = AppUpdater.getButtonText(this, theme)
            }
        }

        currentMainTabButton().requestFocus()

        // 仅在冷启动且用户开启「启动直达播放页」时跳转一次；播放页按返回可平滑退回主界面
        if (savedInstanceState == null && Prefs.isLaunchToPlayer(this)) {
            startActivity(Intent(this, SendspinActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ACTIVE_MAIN_TAB, activeMainTab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.resumePendingInstall(this)
        val latestTheme = Theme.current(this)
        if (latestTheme != theme) {
            theme = latestTheme
            setContentView(buildRootView())
            currentMainTabButton().requestFocus()
        }
        refreshUi()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val focusOnTabBar = overviewTabButton.hasFocus() ||
            practiceTabButton.hasFocus() ||
            systemTabButton.hasFocus()
        if (!focusOnTabBar) {
            currentMainTabButton().requestFocus()
            return
        }
        if (activeMainTab != MainTab.OVERVIEW) {
            overviewTabButton.requestFocus()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (::focusOverlay.isInitialized) {
            focusOverlay.cancelAnimations()
        }
        activeDialog?.dismiss()
        activeDialog = null
        bannerController?.dismissImmediately()
        bannerController?.release()
        bannerController = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // UI 构建：顶部 3 页签切换 + 电视 1080p 一屏双栏布局（自适应窄屏单列）
    // ------------------------------------------------------------------

    private fun buildRootView(): View {
        val outerFrame = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            background = buildWindowBackground()
        }
        focusOverlay = TvFocusGlowOverlay(
            context = this,
            hostContainer = outerFrame,
            initialAccent = theme.accentColor,
            pixelSharp = theme == Theme.MINECRAFT
        )

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false
        scroll.clipChildren = false
        scroll.clipToPadding = false

        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val isWideScreen = screenWidthDp >= 600f

        val rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(if (isWideScreen) 34 else 18)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
            clipChildren = false
            clipToPadding = false
            isFocusable = false
        }
        scroll.addView(rootColumn, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // 1. 顶部品牌与实时状态栏
        rootColumn.addView(buildHeaderBar(isWideScreen))

        // 2. 顶部主导航页签栏（焦点移上即切换页面）
        rootColumn.addView(buildMainTabBar(), linearParams(topMargin = dp(2)))

        // 3. 分页内容容器（三页均按双栏等高对称排布）
        val contentContainer = FrameLayout(this).apply {
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
        overviewPage = buildOverviewPage(isWideScreen)
        practicePage = buildPracticePage(isWideScreen)
        systemPage = buildSystemPage(isWideScreen)

        wireMainTabNavigation(isWideScreen)

        contentContainer.addView(overviewPage, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        contentContainer.addView(practicePage, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        contentContainer.addView(systemPage, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        rootColumn.addView(contentContainer, linearParams(topMargin = dp(12)))

        // 4. 底部遥控器快捷键引导条（纯中文零特殊字符，不抢占焦点）
        val navHint = makeText(
            if (theme.isXianxia) "方向键 切换页签与焦距  ·  确认 施法  ·  返回 归位导航"
            else "方向键 切换页签与移动焦点  ·  确认 执行  ·  返回 回到导航",
            11.5f,
            theme.hintColor,
            false
        ).apply {
            gravity = Gravity.CENTER
            alpha = 0.78f
            isFocusable = false
            setPadding(0, dp(8), 0, dp(2))
        }
        rootColumn.addView(navHint, linearParams(topMargin = dp(4)))

        selectMainTab(activeMainTab)
        outerFrame.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        outerFrame.addView(focusOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return outerFrame
    }

    private fun buildWindowBackground(): GradientDrawable {
        if (theme == Theme.MINECRAFT) {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(theme.windowBg)
            }
        }
        val glowTop = blendColor(theme.windowBg, theme.accentColor, 0.11f)
        val deepBottom = blendColor(theme.windowBg, Color.BLACK, 0.22f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(glowTop, theme.windowBg, deepBottom)
        )
    }

    private fun blendColor(base: Int, tint: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(base) + (Color.red(tint) - Color.red(base)) * r).toInt(),
            (Color.green(base) + (Color.green(tint) - Color.green(base)) * r).toInt(),
            (Color.blue(base) + (Color.blue(tint) - Color.blue(base)) * r).toInt()
        )
    }

    private fun themeCardRadiusPx(): Float = when (theme) {
        Theme.MINECRAFT -> 0f
        Theme.CYBERPUNK, Theme.MATRIX -> dp(6).toFloat()
        else -> dp(14).toFloat()
    }

    private fun themeButtonRadiusPx(): Float = when (theme) {
        Theme.MINECRAFT -> 0f
        Theme.CYBERPUNK, Theme.MATRIX -> dp(5).toFloat()
        else -> dp(10).toFloat()
    }

    private fun buildHeaderBar(isWideScreen: Boolean): View {
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val appIconView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        headerBar.addView(appIconView, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            rightMargin = dp(14)
        })

        val headerTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView = makeText(theme.appTitle, 23f, Color.WHITE, true).apply {
            typeface = theme.bannerTitleTypeface
            if (theme == Theme.XIUXIAN || theme == Theme.CYBERPUNK || theme == Theme.PALACE) {
                setShadowLayer(10f, 0f, 1f, Color.argb(0x70, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor)))
            }
        }
        titleRow.addView(titleView)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        if (versionName.isNotEmpty()) {
            val verBadge = makeText("v$versionName", 11.5f, theme.accentColor, true).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundedRect(
                    if (theme == Theme.MINECRAFT) 0f else dp(6).toFloat(),
                    Color.argb(0x26, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor)),
                    dp(1),
                    Color.argb(0x66, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor))
                )
            }
            titleRow.addView(verBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                leftMargin = dp(10)
            })
        }
        headerTitleCol.addView(titleRow)

        subtitleView = makeText(theme.appSubtitle, 12.5f, theme.hintColor, false).apply {
            setPadding(0, dp(3), 0, 0)
        }
        headerTitleCol.addView(subtitleView)
        headerBar.addView(headerTitleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        headerBadgeView = makeText("", 12.5f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(18).toFloat(),
                theme.cardBg,
                dp(1),
                theme.cardStroke
            )
        }
        if (isWideScreen) {
            headerBar.addView(headerBadgeView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        return headerBar
    }

    private fun buildMainTabBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Keep the scaled end tabs inside the navigation card, not against its border.
            setPadding(dp(12), dp(5), dp(12), dp(5))
            clipChildren = false
            clipToPadding = false
            background = roundedRect(
                themeCardRadiusPx(),
                Color.argb(0x66, Color.red(theme.cardBg), Color.green(theme.cardBg), Color.blue(theme.cardBg)),
                dp(1),
                theme.cardStroke
            )
            isFocusable = false
        }
        overviewTabButton = buildMainTabButton(MainTab.OVERVIEW)
        practiceTabButton = buildMainTabButton(MainTab.PRACTICE)
        systemTabButton = buildMainTabButton(MainTab.SYSTEM)

        bar.addView(overviewTabButton, weightedParams(0, gapDp = 8))
        bar.addView(practiceTabButton, weightedParams(1, gapDp = 8))
        bar.addView(systemTabButton, weightedParams(2, gapDp = 8))
        return bar
    }

    private fun buildMainTabButton(tab: MainTab): Button {
        val button = Button(this).apply {
            id = View.generateViewId()
            text = theme.mainTabTitle(tab)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(9), dp(12), dp(9))
            isFocusable = true
            isFocusableInTouchMode = false
            stateListAnimator = null
            setSingleLine(true)
        }
        val liftDp = if (theme == Theme.MINECRAFT) 0f else 3f
        focusOverlay.registerTarget(
            button,
            TvFocusTargetSpec(cornerRadiusPx = themeButtonRadiusPx(), focusedScale = 1.03f)
        )
        button.setOnFocusChangeListener { v, hasFocus ->
            TvFocusMotion.animateFocusScale(v, hasFocus, focusedScale = 1.03f, elevationLiftDp = liftDp)
            if (hasFocus && activeMainTab != tab) {
                selectMainTab(tab)
            }
        }
        button.setOnClickListener {
            TvFocusMotion.animateClickPulse(button, 1.03f)
            selectMainTab(tab)
        }
        return button
    }

    private fun buildOverviewPage(isWideScreen: Boolean): View {
        // 左栏：音乐播放中枢大卡片（配置稳定后最高频的日常主入口）
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = false
        }
        val musicCard = makeCard(padH = 20, padV = 18).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val heroHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        val heroDiscView = ImageView(this).apply {
            setImageDrawable(TvVectorIconDrawable(this@MainActivity, TvIcon.MUSIC_DISC, theme.accentColor, 42f, 2.0f))
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
        }
        val heroTitleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }
        heroTitleCol.addView(
            makeText(
                if (theme.isXianxia) "灵音传韵中枢" else "音乐播放中枢",
                18f,
                Color.WHITE,
                true
            ).apply { typeface = theme.bannerTitleTypeface }
        )
        heroTitleCol.addView(
            makeText(
                if (theme.isXianxia) "Sendspin 局域网无损推流与歌单点播" else "Sendspin 局域网无损音乐与歌单点播",
                12f,
                theme.hintColor,
                false
            ).apply { setPadding(0, dp(3), 0, 0) }
        )
        wledSummaryBadge = makeText("WLED 未开启", 11.5f, theme.hintColor, true).apply {
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(5)
            setPadding(dp(9), dp(4), dp(10), dp(4))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(12).toFloat(),
                Color.argb(0x2A, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor)),
                dp(1),
                Color.argb(0x60, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor))
            )
        }
        heroHeader.addView(heroDiscView, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })
        heroHeader.addView(heroTitleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        heroHeader.addView(wledSummaryBadge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        musicCard.addView(heroHeader)

        val nowPlayingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(10).toFloat(),
                Color.argb(0x46, 0x00, 0x00, 0x00),
                dp(1),
                Color.argb(0x42, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor))
            )
            isFocusable = false
        }
        musicHeroTitle = makeText("音乐待机中", 17f, Color.WHITE, true).apply {
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8)
        }
        musicHeroSubtitle = makeText("已连接 Music Assistant 后即可大屏听歌、看歌词与灯光律动", 12.5f, theme.hintColor, false).apply {
            setLineSpacing(dp(2.5f).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        }
        nowPlayingBox.addView(musicHeroTitle)
        nowPlayingBox.addView(musicHeroSubtitle)
        musicCard.addView(nowPlayingBox, linearParams(topMargin = dp(16)))

        val musicActionRow = buildButtonRow()
        musicButton = buildButton(
            label = if (theme.isXianxia) "进入灵音播放页" else "进入音乐播放页",
            padV = 14,
            icon = TvIcon.PLAY_TRIANGLE
        ) {
            startActivity(Intent(this@MainActivity, SendspinActivity::class.java))
        }.apply { id = View.generateViewId() }
        musicActionRow.addView(musicButton, weightedParams(0, gapDp = 0))
        musicCard.addView(musicActionRow, linearParams(topMargin = dp(16)))
        leftColumn.addView(musicCard, LinearLayout.LayoutParams(MATCH_PARENT, if (isWideScreen) MATCH_PARENT else WRAP_CONTENT))

        // 右栏：精简健康状态（2x2 紧凑格）+ 日常 3 项高频快捷操作（移走二维码与开发 URL）
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = false
        }

        val statusCard = makeCard(padH = 14, padV = 12).apply {
            isFocusable = false
            isClickable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val metricRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (stateCell, stateVal) = buildMetricCell(if (theme.isXianxia) "阵法状态" else "服务状态")
        val (overlayCell, overlayVal) = buildMetricCell(if (theme.isXianxia) "悬浮显形" else "悬浮窗权限")
        stateMetricValue = stateVal
        overlayMetricValue = overlayVal
        metricRow1.addView(stateCell, weightedParams(0, gapDp = 8))
        metricRow1.addView(overlayCell, weightedParams(1, gapDp = 8))
        statusCard.addView(metricRow1)

        val metricRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (ipCell, ipVal) = buildMetricCell(if (theme.isXianxia) "本机地址" else "局域网地址")
        val (wledCell, wledVal) = buildMetricCell("WLED 灯光联动")
        ipMetricValue = ipVal
        wledMetricValue = wledVal
        metricRow2.addView(ipCell, weightedParams(0, gapDp = 8))
        metricRow2.addView(wledCell, weightedParams(1, gapDp = 8))
        statusCard.addView(metricRow2, linearParams(topMargin = dp(8)))
        rightColumn.addView(statusCard)

        val quickCard = makeCard(padH = 14, padV = 13)
        quickCard.addView(makeText(theme.quickActionsTitle, 12.5f, theme.hintColor, true))
        val quickRow1 = buildButtonRow()
        quickHistoryButton = buildButton(theme.historyButtonText, padV = 12, icon = TvIcon.HISTORY) {
            showHistoryDialog()
        }.apply { id = View.generateViewId() }
        quickThemeButton = buildButton(
            label = if (theme.isXianxia) "切换法相" else "切换主题",
            padV = 12,
            icon = TvIcon.PALETTE
        ) {
            showThemeSelectorDialog()
        }.apply { id = View.generateViewId() }
        quickRow1.addView(quickHistoryButton, weightedParams(0, gapDp = 8))
        quickRow1.addView(quickThemeButton, weightedParams(1, gapDp = 8))
        quickCard.addView(quickRow1, linearParams(topMargin = dp(10)))

        val quickRow2 = buildButtonRow()
        quickConnectButton = buildButton(
            label = if (theme.isXianxia) "扫码互联与符钥" else "扫码互联 / 手机端",
            padV = 12,
            icon = TvIcon.QR_CODE
        ) {
            switchMainTabToControl(MainTab.SYSTEM, haTabButton)
        }.apply { id = View.generateViewId() }
        quickRow2.addView(quickConnectButton, weightedParams(0, gapDp = 0))
        quickCard.addView(quickRow2, linearParams(topMargin = dp(8)))
        rightColumn.addView(
            quickCard,
            LinearLayout.LayoutParams(MATCH_PARENT, if (isWideScreen) 0 else WRAP_CONTENT, if (isWideScreen) 1f else 0f).apply {
                topMargin = dp(10)
            }
        )

        return buildTwoColumnRow(isWideScreen, leftColumn, rightColumn, leftWeight = 1.15f, rightWeight = 0.95f)
    }

    private fun buildPracticePage(isWideScreen: Boolean): View {
        // 左栏：四大演练测试
        val practiceCard = makeCard(padH = 18, padV = 16)
        buildSectionHeader(practiceCard, theme.practiceCardTitle, theme.practiceCardSubtitle)

        testButton = buildButton(
            label = theme.testButtonText,
            padV = 13,
            icon = TvIcon.BELL,
            onClick = { showTestBanner() }
        ).apply { id = View.generateViewId() }
        imageTestButton = buildButton(theme.imageTestButtonText, padV = 13, icon = TvIcon.IMAGE) {
            showTestImageBanner()
        }.apply { id = View.generateViewId() }
        videoTestButton = buildButton(theme.videoTestButtonText, padV = 13, icon = TvIcon.VIDEO) {
            showTestVideoBanner()
        }.apply { id = View.generateViewId() }
        ttsTestButton = buildButton(theme.ttsTestButtonText, padV = 13, icon = TvIcon.SPEAKER) {
            showTestTtsBanner()
        }.apply { id = View.generateViewId() }

        val practiceRow1 = buildButtonRow()
        practiceRow1.addView(testButton, weightedParams(0, gapDp = 10))
        practiceRow1.addView(imageTestButton, weightedParams(1, gapDp = 10))
        practiceCard.addView(practiceRow1, linearParams(topMargin = dp(12)))

        val practiceRow2 = buildButtonRow()
        practiceRow2.addView(videoTestButton, weightedParams(0, gapDp = 10))
        practiceRow2.addView(ttsTestButton, weightedParams(1, gapDp = 10))
        practiceCard.addView(practiceRow2, linearParams(topMargin = dp(10)))

        practiceCard.addView(buildHintBox(theme.practiceCardHint), linearParams(topMargin = dp(14)))

        // 右栏：通知与外观调控
        val controlCard = makeCard(padH = 18, padV = 16)
        buildSectionHeader(controlCard, theme.controlCardTitle, theme.controlCardSubtitle)

        historyButton = buildButton(theme.historyButtonText, padV = 13, icon = TvIcon.HISTORY) {
            showHistoryDialog()
        }.apply { id = View.generateViewId() }
        positionButton = buildButton(theme.positionButtonText(Prefs.getBannerPosition(this)), padV = 13, icon = TvIcon.POSITION) {
            cycleBannerPosition()
        }.apply { id = View.generateViewId() }
        muteButton = buildButton(
            theme.muteButtonText(Prefs.isMuted(this)),
            padV = 13,
            icon = if (Prefs.isMuted(this)) TvIcon.MUTE else TvIcon.SPEAKER
        ) {
            toggleMute()
        }.apply { id = View.generateViewId() }
        themeButton = buildButton(
            label = themeToggleLabel(),
            padV = 13,
            icon = theme.vectorIcon(),
            onClick = { showThemeSelectorDialog() },
            onLongClick = { cycleTheme(); true }
        ).apply { id = View.generateViewId() }

        val controlRow1 = buildButtonRow()
        controlRow1.addView(historyButton, weightedParams(0, gapDp = 10))
        controlRow1.addView(positionButton, weightedParams(1, gapDp = 10))
        controlCard.addView(controlRow1, linearParams(topMargin = dp(12)))

        val controlRow2 = buildButtonRow()
        controlRow2.addView(muteButton, weightedParams(0, gapDp = 10))
        controlRow2.addView(themeButton, weightedParams(1, gapDp = 10))
        controlCard.addView(controlRow2, linearParams(topMargin = dp(10)))

        controlHintView = buildHintBox(theme.controlCardHint)
        controlCard.addView(controlHintView, linearParams(topMargin = dp(14)))

        return buildTwoColumnRow(isWideScreen, practiceCard, controlCard)
    }

    private fun buildSystemPage(isWideScreen: Boolean): View {
        // 左栏：精简调用信息 + 符钥/直达/更新/关于与保活
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = false
        }

        val urlCard = makeCard(padH = 16, padV = 10)
        val urlHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        callInfoTitleView = makeText(theme.callInfoTitle, 12.5f, theme.hintColor, true)
        urlHeaderRow.addView(callInfoTitleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        tokenValue = makeText("", 12.5f, Color.WHITE, true).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(6).toFloat(),
                Color.argb(0x40, 0x00, 0x00, 0x00),
                dp(1),
                theme.cardStroke
            )
        }
        urlHeaderRow.addView(tokenValue)
        urlCard.addView(urlHeaderRow)

        urlValue = makeText("", 12.5f, theme.accentColor, false).apply {
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(8).toFloat(),
                Color.argb(0x48, 0x00, 0x00, 0x00),
                0,
                Color.TRANSPARENT
            )
            setOnClickListener { copyCallUrl() }
        }
        urlCard.addView(urlValue, linearParams(topMargin = dp(6)))
        leftColumn.addView(urlCard)

        val systemCard = makeCard(padH = 16, padV = 13)
        buildSectionHeader(systemCard, theme.systemCardTitle, theme.systemCardSubtitle)

        copyButton = buildButton(theme.copyButtonText, padV = 11, icon = TvIcon.COPY) {
            copyCallUrl()
        }.apply { id = View.generateViewId() }
        resetButton = buildButton(theme.resetButtonText, padV = 11, icon = TvIcon.REFRESH) {
            resetToken()
        }.apply { id = View.generateViewId() }
        launchPlayerButton = buildButton(launchToPlayerLabel(), padV = 11, icon = TvIcon.ROCKET) {
            toggleLaunchToPlayer()
        }.apply { id = View.generateViewId() }
        updateButton = buildButton(AppUpdater.getButtonText(this, theme), padV = 11, icon = TvIcon.DOWNLOAD) {
            checkUpdateManual()
        }.apply { id = View.generateViewId() }
        aboutButton = buildButton(
            if (theme.isXianxia) "溯源与保活" else "关于与保活",
            padV = 11,
            icon = TvIcon.INFO
        ) {
            showAboutDialog()
        }.apply { id = View.generateViewId() }

        val sysRow1 = buildButtonRow()
        sysRow1.addView(copyButton, weightedParams(0, gapDp = 10))
        sysRow1.addView(resetButton, weightedParams(1, gapDp = 10))
        systemCard.addView(sysRow1, linearParams(topMargin = dp(10)))

        val sysRow2 = buildButtonRow()
        sysRow2.addView(launchPlayerButton, weightedParams(0, gapDp = 0))
        systemCard.addView(sysRow2, linearParams(topMargin = dp(8)))

        val sysRow3 = buildButtonRow()
        sysRow3.addView(updateButton, weightedParams(0, gapDp = 10))
        sysRow3.addView(aboutButton, weightedParams(1, gapDp = 10))
        systemCard.addView(sysRow3, linearParams(topMargin = dp(8)))
        leftColumn.addView(
            systemCard,
            LinearLayout.LayoutParams(MATCH_PARENT, if (isWideScreen) 0 else WRAP_CONTENT, if (isWideScreen) 1f else 0f).apply {
                topMargin = dp(10)
            }
        )

        // 右栏：HA 自动发现集成 & 手机控制台二维码卡片（从首页归位至互联页）
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = false
        }
        val qrCard = makeCard(padH = 16, padV = 14).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        }
        val tabRow = buildButtonRow().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        haTabButton = buildTabButton("HA 自动发现集成", activeQrTab == QrTab.HA_INTEGRATION, TvIcon.HOME_HA) {
            if (activeQrTab != QrTab.HA_INTEGRATION) {
                activeQrTab = QrTab.HA_INTEGRATION
                refreshQrDisplay()
            }
        }.apply { id = View.generateViewId() }
        tokenTabButton = buildTabButton("手机控制台", activeQrTab == QrTab.WEB_CONSOLE, TvIcon.PHONE) {
            if (activeQrTab != QrTab.WEB_CONSOLE) {
                activeQrTab = QrTab.WEB_CONSOLE
                refreshQrDisplay()
            }
        }.apply { id = View.generateViewId() }
        tabRow.addView(haTabButton, weightedParams(0, gapDp = 8))
        tabRow.addView(tokenTabButton, weightedParams(1, gapDp = 8))
        qrCard.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        qrTitleView = makeText("", 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = theme.bannerTitleTypeface
            setPadding(0, dp(2), 0, dp(3))
        }
        qrSubtitleView = makeText("", 12f, theme.hintColor, false).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(6), 0, dp(6), dp(10))
        }
        qrCard.addView(qrTitleView)
        qrCard.addView(qrSubtitleView)

        val qrBox = LinearLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(12).toFloat(),
                Color.WHITE,
                dp(2),
                theme.accentColor
            )
            gravity = Gravity.CENTER
        }
        qrImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            setBackgroundColor(Color.WHITE)
        }
        qrBox.addView(qrImage, LinearLayout.LayoutParams(dp(QR_PIXEL_SIZE), dp(QR_PIXEL_SIZE)))
        qrCard.addView(qrBox)

        qrCaptionView = makeText("", 11.5f, theme.accentColor, false).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(8), dp(10), dp(4))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(8).toFloat(),
                Color.argb(0x38, 0x00, 0x00, 0x00),
                0,
                Color.TRANSPARENT
            )
            setOnClickListener { copyActiveQrUrl() }
        }
        qrCard.addView(qrCaptionView, linearParams(topMargin = dp(10)))
        rightColumn.addView(qrCard, LinearLayout.LayoutParams(MATCH_PARENT, if (isWideScreen) MATCH_PARENT else WRAP_CONTENT))

        return buildTwoColumnRow(isWideScreen, leftColumn, rightColumn, leftWeight = 1.15f, rightWeight = 0.95f)
    }

    private fun buildTwoColumnRow(
        isWideScreen: Boolean,
        leftView: View,
        rightView: View,
        leftWeight: Float = 1f,
        rightWeight: Float = 1f
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = if (isWideScreen) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
        if (isWideScreen) {
            val leftParams = LinearLayout.LayoutParams(0, MATCH_PARENT, leftWeight).apply {
                rightMargin = dp(16)
            }
            val rightParams = LinearLayout.LayoutParams(0, MATCH_PARENT, rightWeight)
            row.addView(leftView, leftParams)
            row.addView(rightView, rightParams)
        } else {
            row.addView(leftView, linearParams())
            row.addView(rightView, linearParams(topMargin = dp(10)))
        }
        return row
    }

    private fun buildSectionHeader(card: LinearLayout, title: String, subtitle: String? = null) {
        card.addView(
            makeText(title, 17f, Color.WHITE, true).apply {
                typeface = theme.bannerTitleTypeface
            }
        )
        if (!subtitle.isNullOrBlank()) {
            val subView = makeText(subtitle, 12.5f, theme.hintColor, false).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            card.addView(subView, linearParams(topMargin = dp(4)))
        }
    }

    private fun buildHintBox(text: String): TextView = makeText(text, 12f, theme.hintColor, false).apply {
        setLineSpacing(dp(3.5f).toFloat(), 1f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedRect(
            if (theme == Theme.MINECRAFT) 0f else dp(8).toFloat(),
            Color.argb(0x3C, 0x00, 0x00, 0x00),
            dp(1),
            Color.argb(0x40, Color.red(theme.accentColor), Color.green(theme.accentColor), Color.blue(theme.accentColor))
        )
    }

    private fun wireMainTabNavigation(isWideScreen: Boolean) {
        val ovId = overviewTabButton.id
        val prId = practiceTabButton.id
        val syId = systemTabButton.id

        // 顶部主导航栏：左右锁定边界，向下精确切入当前页主控件
        overviewTabButton.nextFocusLeftId = ovId
        overviewTabButton.nextFocusUpId = ovId
        overviewTabButton.nextFocusRightId = prId
        overviewTabButton.nextFocusDownId = musicButton.id

        practiceTabButton.nextFocusLeftId = ovId
        practiceTabButton.nextFocusUpId = prId
        practiceTabButton.nextFocusRightId = syId
        practiceTabButton.nextFocusDownId = testButton.id

        systemTabButton.nextFocusLeftId = prId
        systemTabButton.nextFocusUpId = syId
        systemTabButton.nextFocusRightId = syId
        systemTabButton.nextFocusDownId = copyButton.id

        // 各页顶部控件向上均先回到当前所属主 Tab，防止跨页触发焦点切换
        musicButton.nextFocusUpId = ovId
        quickHistoryButton.nextFocusUpId = ovId
        quickThemeButton.nextFocusUpId = ovId
        quickConnectButton.nextFocusUpId = quickHistoryButton.id
        quickHistoryButton.nextFocusDownId = quickConnectButton.id
        quickThemeButton.nextFocusDownId = quickConnectButton.id

        testButton.nextFocusUpId = prId
        imageTestButton.nextFocusUpId = prId
        historyButton.nextFocusUpId = prId
        positionButton.nextFocusUpId = prId

        copyButton.nextFocusUpId = syId
        resetButton.nextFocusUpId = syId
        haTabButton.nextFocusUpId = syId
        tokenTabButton.nextFocusUpId = syId
        copyButton.nextFocusDownId = launchPlayerButton.id
        resetButton.nextFocusDownId = launchPlayerButton.id
        launchPlayerButton.nextFocusUpId = copyButton.id
        launchPlayerButton.nextFocusDownId = updateButton.id
        updateButton.nextFocusUpId = launchPlayerButton.id
        aboutButton.nextFocusUpId = launchPlayerButton.id

        // 页内左右流转
        quickHistoryButton.nextFocusRightId = quickThemeButton.id
        quickThemeButton.nextFocusLeftId = quickHistoryButton.id

        testButton.nextFocusRightId = imageTestButton.id
        imageTestButton.nextFocusLeftId = testButton.id
        videoTestButton.nextFocusRightId = ttsTestButton.id
        ttsTestButton.nextFocusLeftId = videoTestButton.id
        historyButton.nextFocusRightId = positionButton.id
        positionButton.nextFocusLeftId = historyButton.id
        muteButton.nextFocusRightId = themeButton.id
        themeButton.nextFocusLeftId = muteButton.id

        copyButton.nextFocusRightId = resetButton.id
        resetButton.nextFocusLeftId = copyButton.id
        updateButton.nextFocusRightId = aboutButton.id
        aboutButton.nextFocusLeftId = updateButton.id
        haTabButton.nextFocusRightId = tokenTabButton.id
        tokenTabButton.nextFocusLeftId = haTabButton.id

        bindDirectionalEdge(musicButton)
        bindDirectionalEdge(tokenTabButton)
        bindDirectionalEdge(quickThemeButton, onRightTab = MainTab.PRACTICE, onRightTarget = { testButton })
        bindDirectionalEdge(quickConnectButton, onRightTab = MainTab.PRACTICE, onRightTarget = { videoTestButton })

        if (isWideScreen) {
            musicButton.nextFocusRightId = quickHistoryButton.id
            quickHistoryButton.nextFocusLeftId = musicButton.id
            quickConnectButton.nextFocusLeftId = musicButton.id

            imageTestButton.nextFocusRightId = historyButton.id
            historyButton.nextFocusLeftId = imageTestButton.id
            ttsTestButton.nextFocusRightId = muteButton.id
            muteButton.nextFocusLeftId = ttsTestButton.id

            resetButton.nextFocusRightId = haTabButton.id
            launchPlayerButton.nextFocusRightId = haTabButton.id
            aboutButton.nextFocusRightId = haTabButton.id
            haTabButton.nextFocusLeftId = resetButton.id

            bindDirectionalEdge(testButton, onLeftTab = MainTab.OVERVIEW, onLeftTarget = { quickThemeButton })
            bindDirectionalEdge(videoTestButton, onLeftTab = MainTab.OVERVIEW, onLeftTarget = { quickConnectButton })
            bindDirectionalEdge(positionButton, onRightTab = MainTab.SYSTEM, onRightTarget = { copyButton })
            bindDirectionalEdge(themeButton, onRightTab = MainTab.SYSTEM, onRightTarget = { updateButton })

            bindDirectionalEdge(copyButton, onLeftTab = MainTab.PRACTICE, onLeftTarget = { positionButton })
            bindDirectionalEdge(launchPlayerButton, onLeftTab = MainTab.PRACTICE, onLeftTarget = { positionButton })
            bindDirectionalEdge(updateButton, onLeftTab = MainTab.PRACTICE, onLeftTarget = { themeButton })
        } else {
            bindDirectionalEdge(musicButton, onRightTab = MainTab.PRACTICE, onRightTarget = { testButton })
            bindDirectionalEdge(quickHistoryButton)
            bindDirectionalEdge(quickConnectButton, onRightTab = MainTab.PRACTICE, onRightTarget = { videoTestButton })

            bindDirectionalEdge(testButton, onLeftTab = MainTab.OVERVIEW, onLeftTarget = { quickThemeButton })
            bindDirectionalEdge(videoTestButton, onLeftTab = MainTab.OVERVIEW, onLeftTarget = { quickConnectButton })
            bindDirectionalEdge(historyButton, onLeftTab = MainTab.OVERVIEW, onLeftTarget = { quickThemeButton })
            bindDirectionalEdge(muteButton, onLeftTab = MainTab.OVERVIEW, onLeftTarget = { quickConnectButton })

            bindDirectionalEdge(imageTestButton, onRightTab = MainTab.SYSTEM, onRightTarget = { copyButton })
            bindDirectionalEdge(ttsTestButton, onRightTab = MainTab.SYSTEM, onRightTarget = { updateButton })
            bindDirectionalEdge(positionButton, onRightTab = MainTab.SYSTEM, onRightTarget = { copyButton })
            bindDirectionalEdge(themeButton, onRightTab = MainTab.SYSTEM, onRightTarget = { updateButton })

            bindDirectionalEdge(copyButton, onLeftTab = MainTab.PRACTICE, onLeftTarget = { imageTestButton })
            bindDirectionalEdge(launchPlayerButton, onLeftTab = MainTab.PRACTICE, onLeftTarget = { imageTestButton })
            bindDirectionalEdge(updateButton, onLeftTab = MainTab.PRACTICE, onLeftTarget = { ttsTestButton })
            bindDirectionalEdge(haTabButton, onLeftTab = MainTab.PRACTICE, onLeftTarget = { ttsTestButton })
            bindDirectionalEdge(resetButton)
            bindDirectionalEdge(aboutButton)
        }
    }

    private fun bindDirectionalEdge(
        button: Button,
        onLeftTab: MainTab? = null,
        onLeftTarget: (() -> Button)? = null,
        onRightTab: MainTab? = null,
        onRightTarget: (() -> Button)? = null
    ) {
        if (onLeftTab == null && button.nextFocusLeftId == View.NO_ID) {
            button.nextFocusLeftId = button.id
        }
        if (onRightTab == null && button.nextFocusRightId == View.NO_ID) {
            button.nextFocusRightId = button.id
        }
        button.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when {
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT && onLeftTab != null && onLeftTarget != null -> {
                    if (event.repeatCount == 0) {
                        switchMainTabToControl(onLeftTab, onLeftTarget())
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && onRightTab != null && onRightTarget != null -> {
                    if (event.repeatCount == 0) {
                        switchMainTabToControl(onRightTab, onRightTarget())
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun pageForTab(tab: MainTab): View = when (tab) {
        MainTab.OVERVIEW -> overviewPage
        MainTab.PRACTICE -> practicePage
        MainTab.SYSTEM -> systemPage
    }

    private fun applyTabPageVisibility(previousTab: MainTab, nextTab: MainTab) {
        val targetPage = pageForTab(nextTab)
        for (tab in MainTab.entries) {
            val page = pageForTab(tab)
            page.animate().cancel()
            if (tab != nextTab) {
                page.alpha = 1f
                page.translationX = 0f
                page.visibility = View.GONE
            }
        }
        targetPage.visibility = View.VISIBLE
        if (previousTab != nextTab && targetPage.isAttachedToWindow && targetPage.width > 0) {
            val direction = if (nextTab.ordinal > previousTab.ordinal) 1f else -1f
            targetPage.alpha = 0.42f
            targetPage.translationX = direction * dp(16).toFloat()
            targetPage.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(185L)
                .setInterpolator(TvFocusMotion.TAB_INTERPOLATOR)
                .withEndAction {
                    targetPage.alpha = 1f
                    targetPage.translationX = 0f
                }
                .start()
        } else {
            targetPage.alpha = 1f
            targetPage.translationX = 0f
        }
    }

    private fun switchMainTabToControl(targetTab: MainTab, targetControl: View) {
        // Show the destination page and move focus BEFORE hiding the previous page, so ViewRoot
        // does not temporarily fall back to the top tab bar and re-trigger selectMainTab.
        val previousTab = activeMainTab
        pageForTab(targetTab).visibility = View.VISIBLE
        activeMainTab = targetTab
        updateMainTabButtonStyles()
        targetControl.requestFocus()
        applyTabPageVisibility(previousTab, targetTab)
    }

    private fun selectMainTab(tab: MainTab) {
        val previousTab = activeMainTab
        activeMainTab = tab
        applyTabPageVisibility(previousTab, tab)
        updateMainTabButtonStyles()
    }

    private fun updateMainTabButtonStyles() {
        val pairs = listOf(
            overviewTabButton to (activeMainTab == MainTab.OVERVIEW),
            practiceTabButton to (activeMainTab == MainTab.PRACTICE),
            systemTabButton to (activeMainTab == MainTab.SYSTEM)
        )
        for ((button, isSelected) in pairs) {
            button.setTextColor(if (isSelected) Color.WHITE else theme.hintColor)
            button.background = createMainTabStateDrawable(isSelected)
        }
    }

    private fun createMainTabStateDrawable(isSelected: Boolean): StateListDrawable {
        val radius = themeButtonRadiusPx()
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(radius, theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedRect(radius, theme.accentColor, dp(2), Color.WHITE)
        )
        if (isSelected) {
            val selectedBg = Color.argb(
                0x44,
                Color.red(theme.accentColor),
                Color.green(theme.accentColor),
                Color.blue(theme.accentColor)
            )
            states.addState(
                intArrayOf(),
                roundedRect(radius, selectedBg, dp(2), theme.accentColor)
            )
        } else {
            states.addState(
                intArrayOf(),
                roundedRect(radius, theme.cardBg, dp(1), theme.cardStroke)
            )
        }
        return states
    }

    private fun currentMainTabButton(): Button = when (activeMainTab) {
        MainTab.OVERVIEW -> overviewTabButton
        MainTab.PRACTICE -> practiceTabButton
        MainTab.SYSTEM -> systemTabButton
    }

    private fun buildMetricCell(label: String): Pair<LinearLayout, TextView> {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundedRect(
                if (theme == Theme.MINECRAFT) 0f else dp(8).toFloat(),
                Color.argb(0x38, 0x00, 0x00, 0x00),
                0,
                Color.TRANSPARENT
            )
        }
        val labelView = makeText(label, 11.5f, theme.hintColor, false)
        val valueView = makeText("", 13f, Color.WHITE, true).apply {
            setPadding(0, dp(4), 0, 0)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(6)
        }
        cell.addView(labelView)
        cell.addView(valueView)
        return cell to valueView
    }

    private fun makeCard(padH: Int = 16, padV: Int = 12): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
        card.background = roundedRect(themeCardRadiusPx(), theme.cardBg, dp(1), theme.cardStroke)
        card.clipChildren = false
        card.clipToPadding = false
        return card
    }

    private fun buildButtonRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        // Focus scaling uses the card's inner padding rather than being cut at row bounds.
        clipChildren = false
        clipToPadding = false
    }

    private fun makeText(text: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val view = TextView(this)
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        view.setTextColor(color)
        view.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.includeFontPadding = false
        return view
    }

    private fun buildTabButton(
        label: String,
        isSelected: Boolean,
        icon: TvIcon? = null,
        onClick: () -> Unit
    ): Button {
        val button = Button(this)
        button.text = label
        button.isAllCaps = false
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        button.typeface = Typeface.DEFAULT_BOLD
        button.gravity = Gravity.CENTER
        button.setPadding(dp(9), dp(7), dp(9), dp(7))
        button.isFocusable = true
        button.isFocusableInTouchMode = false
        button.stateListAnimator = null
        button.setSingleLine(true)

        val bgNormal = if (isSelected) theme.accentColor else theme.buttonBg
        val textNormal = if (isSelected) Color.WHITE else theme.hintColor
        button.setTextColor(textNormal)

        val iconDrawable = icon?.let {
            TvVectorIconDrawable(this, it, textNormal, sizeDp = 14f, strokeDp = 1.75f).also { d ->
                button.compoundDrawablePadding = dp(6)
                button.setCompoundDrawables(d, null, null, null)
            }
        }
        button.tag = iconDrawable

        val radius = themeButtonRadiusPx()
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(radius, theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(radius, bgNormal, dp(1), theme.buttonStroke)
        )
        button.background = states

        val liftDp = if (theme == Theme.MINECRAFT) 0f else 4f
        focusOverlay.registerTarget(
            button,
            TvFocusTargetSpec(cornerRadiusPx = radius, focusedScale = 1.04f)
        )
        button.setOnFocusChangeListener { v, hasFocus ->
            TvFocusMotion.animateFocusScale(v, hasFocus, focusedScale = 1.04f, elevationLiftDp = liftDp)
            if (hasFocus) {
                iconDrawable?.setIconColor(Color.WHITE)
            } else {
                val isCurrentlySelected = (v === haTabButton && activeQrTab == QrTab.HA_INTEGRATION) ||
                    (v === tokenTabButton && activeQrTab == QrTab.WEB_CONSOLE)
                iconDrawable?.setIconColor(if (isCurrentlySelected) Color.WHITE else theme.hintColor)
            }
        }
        button.setOnClickListener {
            TvFocusMotion.animateClickPulse(button, 1.04f)
            onClick()
        }
        return button
    }

    private fun buildButton(
        label: String,
        padV: Int = 10,
        icon: TvIcon? = null,
        onLongClick: (() -> Boolean)? = null,
        onClick: () -> Unit
    ): Button {
        val radius = themeButtonRadiusPx()
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(radius, theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedRect(radius, theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(radius, theme.buttonBg, dp(1), theme.buttonStroke)
        )

        val button = Button(this)
        button.text = label
        button.isAllCaps = false
        button.setTextColor(Color.WHITE)
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        button.typeface = Typeface.DEFAULT_BOLD
        button.gravity = Gravity.CENTER
        button.background = states
        button.setPadding(dp(10), dp(padV), dp(10), dp(padV))
        button.isFocusable = true
        button.isFocusableInTouchMode = false
        button.stateListAnimator = null
        button.setSingleLine(true)

        val iconDrawable = icon?.let {
            TvVectorIconDrawable(this, it, theme.accentColor, sizeDp = 15.5f, strokeDp = 1.85f).also { d ->
                button.compoundDrawablePadding = dp(7)
                button.setCompoundDrawables(d, null, null, null)
            }
        }
        button.tag = iconDrawable

        val liftDp = if (theme == Theme.MINECRAFT) 0f else 4f
        focusOverlay.registerTarget(
            button,
            TvFocusTargetSpec(cornerRadiusPx = radius, focusedScale = 1.04f)
        )
        button.setOnFocusChangeListener { v, hasFocus ->
            TvFocusMotion.animateFocusScale(v, hasFocus, focusedScale = 1.04f, elevationLiftDp = liftDp)
            (v.tag as? TvVectorIconDrawable)?.setIconColor(if (hasFocus) Color.WHITE else theme.accentColor)
        }

        button.setOnClickListener {
            TvFocusMotion.animateClickPulse(button, 1.04f)
            onClick()
        }
        if (onLongClick != null) {
            button.setOnLongClickListener { onLongClick() }
        }
        return button
    }

    private fun setLeadingIcon(view: TextView, icon: TvIcon, color: Int, sizeDp: Float = 13.5f) {
        view.compoundDrawablePadding = dp(6)
        view.gravity = Gravity.CENTER_VERTICAL
        view.setCompoundDrawables(TvVectorIconDrawable(this, icon, color, sizeDp, 1.8f), null, null, null)
    }

    private fun linearParams(
        width: Int = MATCH_PARENT,
        height: Int = WRAP_CONTENT,
        topMargin: Int = 0
    ): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(width, height)
        params.topMargin = topMargin
        return params
    }

    private fun weightedParams(position: Int, gapDp: Int = 8): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        if (position > 0) {
            params.leftMargin = dp(gapDp)
        }
        return params
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    private fun refreshUi() {
        val latestTheme = Theme.current(this)
        if (latestTheme != theme) {
            theme = latestTheme
            setContentView(buildRootView())
        }
        val token = Prefs.getToken(this)
        val overlayGranted = Settings.canDrawOverlays(this)
        val running = NotifyService.isServerRunning
        val errorMsg = NotifyService.lastError

        Thread({
            val freshIp = NetUtils.localIpv4()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                cachedIp = freshIp
                updateStateAndQr(token, overlayGranted, running, errorMsg)
            }
        }, "tvnotify-ip").start()

        updateStateAndQr(token, overlayGranted, running, errorMsg)
    }

    private fun updateStateAndQr(
        token: String,
        overlayGranted: Boolean,
        running: Boolean,
        errorMsg: String?
    ) {
        val ip = cachedIp
        val ipUsable = NetUtils.hasUsableIp(ip)
        currentUrl = "http://$ip:${NotifyService.PORT}/$token/notify"
        webConsoleUrl = "http://$ip:${NotifyService.PORT}/$token"

        val warnAmber = Color.rgb(0xFF, 0xB0, 0x20)
        val errRed = Color.rgb(0xFF, 0x6B, 0x6B)

        val headerColor = if (running) theme.accentColor else warnAmber
        headerBadgeView.text = if (running) {
            if (theme.isXianxia) "灵脉通畅 · :${NotifyService.PORT}" else "服务运行中 · :${NotifyService.PORT}"
        } else {
            if (theme.isXianxia) "阵法待启" else "服务未运行"
        }
        headerBadgeView.setTextColor(headerColor)
        setLeadingIcon(
            headerBadgeView,
            if (running) TvIcon.STATUS_DOT_SOLID else TvIcon.STATUS_DOT_RING,
            headerColor,
            sizeDp = 11f
        )

        val stateColor = if (running) theme.accentColor else errRed
        stateMetricValue.text = when {
            running -> if (theme.isXianxia) "已开启（常驻）" else "运行中（常驻）"
            !errorMsg.isNullOrBlank() -> "异常：$errorMsg"
            else -> if (theme.isXianxia) "灵光未聚" else "未运行"
        }
        stateMetricValue.setTextColor(stateColor)
        setLeadingIcon(
            stateMetricValue,
            when {
                running -> TvIcon.STATUS_DOT_SOLID
                !errorMsg.isNullOrBlank() -> TvIcon.ERROR_X
                else -> TvIcon.STATUS_DOT_RING
            },
            stateColor,
            sizeDp = 12f
        )

        overlayMetricValue.text = if (overlayGranted) {
            if (theme.isXianxia) "已授予" else "已允许"
        } else {
            if (theme.isXianxia) "未授予" else "未允许"
        }
        val overlayColor = if (overlayGranted) Color.WHITE else warnAmber
        overlayMetricValue.setTextColor(overlayColor)
        setLeadingIcon(
            overlayMetricValue,
            if (overlayGranted) TvIcon.SHIELD else TvIcon.WARNING,
            if (overlayGranted) theme.accentColor else warnAmber,
            sizeDp = 13f
        )

        ipMetricValue.text = if (ipUsable) "$ip:${NotifyService.PORT}" else "未连接局域网"
        val ipColor = if (ipUsable) Color.WHITE else warnAmber
        ipMetricValue.setTextColor(ipColor)
        setLeadingIcon(
            ipMetricValue,
            if (ipUsable) TvIcon.NETWORK else TvIcon.WARNING,
            if (ipUsable) theme.accentColor else warnAmber,
            sizeDp = 13f
        )

        PlaylistStore.restoreIfEmpty(applicationContext)
        val playlistCount = PlaylistStore.current()?.playlists?.size ?: 0
        val musicStatus = SendspinRuntime.current()?.status()
        val trackTitle = musicStatus?.title?.trim().orEmpty()
        val trackArtist = musicStatus?.artist?.trim().orEmpty()
        val isPlaying = musicStatus != null && musicStatus.playing && trackTitle.isNotEmpty()
        musicHeroTitle.text = when {
            isPlaying -> "正在播放：$trackTitle"
            trackTitle.isNotEmpty() -> "已暂停：$trackTitle"
            else -> "音乐待机中"
        }
        setLeadingIcon(musicHeroTitle, TvIcon.MUSIC_NOTE, theme.accentColor, sizeDp = 16f)
        musicHeroSubtitle.text = when {
            trackTitle.isNotEmpty() && trackArtist.isNotEmpty() ->
                "$trackArtist" + if (playlistCount > 0) "  ·  已同步 $playlistCount 张歌单" else ""
            playlistCount > 0 ->
                "已同步 $playlistCount 张歌单，按确认键立刻进入播放页与选歌列表"
            else ->
                "连接 Music Assistant 后即可大屏无损听歌、歌词同步与 WLED 律动"
        }

        val wledAudioOn = Prefs.isWledSyncEnabled(this)
        val wledColorOn = Prefs.isWledColorSyncEnabled(this)
        val wledDynamics = Prefs.getWledSyncDynamics(this)
        val wledActive = wledAudioOn || wledColorOn
        val wledLabel = when {
            wledAudioOn && wledColorOn -> "律动 $wledDynamics% · 色彩"
            wledAudioOn -> "律动 $wledDynamics%"
            wledColorOn -> "封面色彩"
            else -> "未开启"
        }
        wledSummaryBadge.text = if (wledActive) "WLED $wledLabel" else "WLED 未开启"
        wledSummaryBadge.setTextColor(if (wledActive) theme.accentColor else theme.hintColor)
        setLeadingIcon(wledSummaryBadge, TvIcon.WLED_SPARK, if (wledActive) theme.accentColor else theme.hintColor, sizeDp = 12.5f)

        wledMetricValue.text = wledLabel
        wledMetricValue.setTextColor(if (wledActive) Color.WHITE else theme.hintColor)
        setLeadingIcon(wledMetricValue, TvIcon.WLED_SPARK, if (wledActive) theme.accentColor else theme.hintColor, sizeDp = 13f)

        tokenValue.text = theme.tokenLabel(token)
        urlValue.text = currentUrl

        positionButton.text = theme.positionButtonText(Prefs.getBannerPosition(this))
        val muted = Prefs.isMuted(this)
        muteButton.text = theme.muteButtonText(muted)
        val muteDrawable = TvVectorIconDrawable(this, if (muted) TvIcon.MUTE else TvIcon.SPEAKER, if (muteButton.isFocused) Color.WHITE else theme.accentColor, 15.5f, 1.85f)
        muteButton.tag = muteDrawable
        muteButton.setCompoundDrawables(muteDrawable, null, null, null)

        themeButton.text = themeToggleLabel()
        controlHintView.text = theme.controlCardHint
        launchPlayerButton.text = launchToPlayerLabel()
        updateButton.text = AppUpdater.getButtonText(this, theme)

        refreshQrDisplay()
    }

    private fun refreshQrDisplay() {
        val isHa = activeQrTab == QrTab.HA_INTEGRATION
        val content = if (isHa) HA_INTEGRATION_URL else webConsoleUrl

        qrTitleView.text = if (isHa) {
            "Home Assistant 专属集成"
        } else {
            "手机控制台 & 符钥"
        }

        qrSubtitleView.text = if (isHa) {
            "在 HA 安装 ha-yujian 集成后，局域网 mDNS 自动发现此电视，免配 IP 与 Token！"
        } else {
            "手机扫码在浏览器中秒开发送通知、查阅简录历史、调控免扰模式与 WLED 参数"
        }

        qrCaptionView.text = if (isHa) {
            "$HA_INTEGRATION_URL\n（扫码直达 GitHub 仓库 · 点此复制链接）"
        } else {
            "$webConsoleUrl\n（扫码直达 Web 控制台 · 点此复制链接）"
        }

        updateTabButtonStyles()

        if (content.isNotEmpty()) {
            renderQrCode(content)
        } else {
            qrImage.setImageDrawable(null)
        }
    }

    private fun updateTabButtonStyles() {
        val isHa = activeQrTab == QrTab.HA_INTEGRATION
        haTabButton.setTextColor(if (isHa) Color.WHITE else theme.hintColor)
        tokenTabButton.setTextColor(if (!isHa) Color.WHITE else theme.hintColor)
        (haTabButton.tag as? TvVectorIconDrawable)?.setIconColor(if (isHa || haTabButton.isFocused) Color.WHITE else theme.hintColor)
        (tokenTabButton.tag as? TvVectorIconDrawable)?.setIconColor(if (!isHa || tokenTabButton.isFocused) Color.WHITE else theme.hintColor)

        haTabButton.background = createTabStateDrawable(isHa)
        tokenTabButton.background = createTabStateDrawable(!isHa)
    }

    private fun createTabStateDrawable(isSelected: Boolean): StateListDrawable {
        val bgNormal = if (isSelected) theme.accentColor else theme.buttonBg
        val radius = themeButtonRadiusPx()
        val states = StateListDrawable()
        states.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(radius, theme.accentColor, dp(2), Color.WHITE)
        )
        states.addState(
            intArrayOf(),
            roundedRect(radius, bgNormal, dp(1), theme.buttonStroke)
        )
        return states
    }

    private fun renderQrCode(content: String) {
        if (content == currentlyRenderedContent && qrImage.drawable != null) {
            return
        }
        Thread({
            val bitmap = createQrBitmap(content, QR_PIXEL_SIZE)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (bitmap != null) {
                    currentlyRenderedContent = content
                    qrImage.setImageBitmap(bitmap)
                } else {
                    qrImage.setImageDrawable(null)
                }
            }
        }, "tvnotify-qr").start()
    }

    private fun createQrBitmap(content: String, sizePx: Int): Bitmap? {
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.MARGIN] = 1
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val rowOffset = y * width
                for (x in 0 until width) {
                    pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "二维码生成失败", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // 交互操作
    // ------------------------------------------------------------------

    private fun showTestBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = theme.testBannerTitle,
                text = theme.testBannerText,
                durationSeconds = 5
            )
        )
    }

    private fun showTestImageBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val base64 = try {
            resources.openRawResource(R.raw.test_sample).use { input ->
                android.util.Base64.encodeToString(input.readBytes(), android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取测试图失败", e)
            toast("测试图不可用")
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = if (theme.isXianxia) "画影之术" else "图片测试",
                text = if (theme.isXianxia) "以灵力凝塑画影一幅，请君品鉴。" else "内置测试图渲染检查。",
                durationSeconds = 10,
                imageBase64 = base64
            )
        )
    }

    private fun showTestVideoBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = if (theme.isXianxia) "灵镜自演" else "视频测试",
                text = if (theme.isXianxia) "灵镜自内映照，影像循环不熄。" else "内置抽帧动画循环播放。",
                durationSeconds = 30,
                assetFrames = true
            )
        )
    }

    private fun showTestTtsBanner() {
        if (!Settings.canDrawOverlays(this)) {
            toast(theme.overlayPermissionToast)
            openOverlaySettings()
            return
        }
        val controller = bannerController ?: BannerController(this).also { bannerController = it }
        controller.show(
            BannerRequest(
                title = if (theme.isXianxia) "灵音传诵" else "语音播报",
                text = if (theme.isXianxia) "千里传音，玉简已同步为您语音朗诵此条灵讯。" else "这是一条来自玉简的语音播报测试通知，正在为您朗读此条内容。",
                durationSeconds = 8,
                tts = "true"
            )
        )
    }

    /** 传音简录（历史记录弹窗）：展示最近 20 条通知，遥控器点击可重新演练展示该通知 */
    private fun showHistoryDialog() {
        val items = NotifyHistory.getAll(this)
        if (items.isEmpty()) {
            toast(if (theme.isXianxia) "暂无传音简录" else "暂无通知历史")
            return
        }
        val dialogItems = items.map { item ->
            val level = BannerLevel.fromRaw(item.level)
            val posName = theme.positionDisplayName(item.position)
            val ttsTag = if (!item.tts.isNullOrBlank()) " · 语音" else ""
            val bodyPreview = item.text.replace('\n', ' ').trim().take(42)
            ThemeDialogItem(
                title = item.title.ifEmpty { if (theme.isXianxia) "（无题灵简）" else "（无标题）" },
                subtitle = "[$posName] ${item.formattedTime}$ttsTag${if (bodyPreview.isNotEmpty()) " · $bodyPreview" else ""}",
                leadingBadge = theme.levelBadgeText(level),
                leadingBadgeColor = theme.bannerIconColor(level),
                trailingBadge = if (theme.isXianxia) "重演" else "重播"
            ) {
                val controller = bannerController ?: BannerController(this).also { bannerController = it }
                controller.show(
                    BannerRequest(
                        title = item.title,
                        text = item.text,
                        level = level,
                        position = BannerPosition.fromRaw(item.position),
                        durationSeconds = 6,
                        tts = item.tts
                    )
                )
                toast(if (theme.isXianxia) "已重演此简" else "已重新展示该通知")
            }
        }

        showThemedDialog(
            ThemedDialogBuilder(this, theme)
                .setTitle(theme.historyButtonText)
                .setItems(dialogItems)
                .setMaxWidthDp(580)
                .setNeutralButton(if (theme.isXianxia) "拭去简录 (清空)" else "清空历史") {
                    NotifyHistory.clear(this)
                    toast(if (theme.isXianxia) "简录已拭去" else "历史已清空")
                }
                .setPositiveButton(if (theme.isXianxia) "收起" else "关闭")
                .create()
        )
    }

    private fun cycleBannerPosition() {
        val current = Prefs.getBannerPosition(this)
        val positions = listOf("top", "top-right", "bottom", "bottom-right", "top-left", "bottom-left")
        val nextIndex = (positions.indexOf(current) + 1) % positions.size
        val nextPos = positions[nextIndex]
        Prefs.setBannerPosition(this, nextPos)
        refreshUi()
        toast(if (theme.isXianxia) "默认方位：${theme.positionDisplayName(nextPos)}" else "默认位置：${theme.positionDisplayName(nextPos)}")
    }

    private fun toggleMute() {
        val muted = !Prefs.isMuted(this)
        Prefs.setMuted(this, muted)
        refreshUi()
        toast(
            if (muted) (if (theme.isXianxia) "已开启闭关免扰（静音）" else "已开启静音模式")
            else (if (theme.isXianxia) "已开启灵音畅通（发声）" else "已开启提示声音")
        )
    }

    private fun launchToPlayerLabel(): String {
        val on = Prefs.isLaunchToPlayer(this)
        return if (theme.isXianxia) {
            "启动直达灵音页：${if (on) "已开启" else "已关闭"}"
        } else {
            "启动直达音乐播放页：${if (on) "已开启" else "已关闭"}"
        }
    }

    private fun toggleLaunchToPlayer() {
        val enabled = !Prefs.isLaunchToPlayer(this)
        Prefs.setLaunchToPlayer(this, enabled)
        launchPlayerButton.text = launchToPlayerLabel()
        toast(
            if (enabled) "已开启：下次启动玉简直接进入音乐播放页"
            else "已关闭：启动玉简默认进入首页"
        )
    }

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "unknown"
        }
        val message = buildString {
            append("玉简（YuJian）v").append(version).append('\n')
            append("Android TV 局域网灵讯通知横幅与无损音乐终端\n\n")
            append("后台常驻与保活：\n").append(theme.footerHint).append("\n\n")
            append("应用仓库：\n").append(APP_REPO_URL).append("\n\n")
            append("HA 集成：\n").append(HA_INTEGRATION_URL).append("\n\n")
            append("致谢：\n感谢开源电视桌面 Aerial 的 ADB 保活机制，助力实现后台拉起服务通知。")
        }
        showThemedDialog(
            ThemedDialogBuilder(this, theme)
                .setTitle(if (theme.isXianxia) "简牍溯源与保活" else "关于与保活指南")
                .setMessage(message)
                .setMaxWidthDp(560)
                .setPositiveButton(if (theme.isXianxia) "知道了" else "确定")
                .create()
        )
    }

    private fun checkUpdateManual() {
        AppUpdater.checkUpdate(this, manual = true) {
            if (!isFinishing) {
                updateButton.text = AppUpdater.getButtonText(this, theme)
            }
        }
    }

    private fun copyCallUrl() {
        if (currentUrl.isEmpty()) {
            toast(theme.urlNotReadyToast)
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(theme.clipLabel, currentUrl))
        toast(theme.copiedToast(currentUrl))
    }

    private fun copyActiveQrUrl() {
        val isHa = activeQrTab == QrTab.HA_INTEGRATION
        val (label, url) = if (isHa) {
            "ha-yujian 集成仓库" to HA_INTEGRATION_URL
        } else {
            "玉简 Web 控制台" to webConsoleUrl
        }
        if (url.isEmpty()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, url))
        toast(if (theme.isXianxia) "法诀已录入剪贴：$url" else "已复制：$url")
    }

    private fun resetToken() {
        val token = Prefs.resetToken(this)
        refreshUi()
        toast(theme.tokenResetToast(token))
    }

    private fun themeToggleLabel(): String = "主题：${theme.shortName}"

    private fun showThemeSelectorDialog() {
        val allThemes = Theme.entries
        val dialogItems = allThemes.map { item ->
            val previewPalette = ThemeDialogPalette.from(item)
            val detail = item.displayLabel.substringAfter("（", "").removeSuffix("）").ifBlank { item.soundDesc }
            val selected = item == theme
            ThemeDialogItem(
                title = item.shortName,
                subtitle = detail,
                selected = selected,
                leadingIcon = item.vectorIcon(),
                leadingIconColor = previewPalette.accentColor,
                swatchFill = previewPalette.accentColor,
                swatchStroke = previewPalette.strokeColor,
                swatchSquare = item == Theme.MINECRAFT,
                trailingBadge = if (selected) (if (theme.isXianxia) "当前法相" else "当前主题") else null
            ) {
                applySelectedTheme(item)
            }
        }

        showThemedDialog(
            ThemedDialogBuilder(this, theme)
                .setTitle(
                    if (theme.isXianxia) "选择法相主题（共 ${Theme.entries.size} 款）"
                    else "选择界面与横幅主题（共 ${Theme.entries.size} 款）"
                )
                .setItems(dialogItems)
                .setMaxWidthDp(560)
                .setNegativeButton(if (theme.isXianxia) "收起" else "取消")
                .create()
        )
    }

    private fun showThemedDialog(dialog: ThemedDialog) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun cycleTheme() {
        val allThemes = Theme.entries
        val next = allThemes[(theme.ordinal + 1) % allThemes.size]
        applySelectedTheme(next)
    }

    private fun applySelectedTheme(selected: Theme) {
        theme = selected
        Theme.save(this, theme)
        setContentView(buildRootView())
        refreshUi()
        if (activeMainTab == MainTab.OVERVIEW) {
            quickThemeButton.requestFocus()
        } else {
            themeButton.requestFocus()
        }
        if (Settings.canDrawOverlays(this)) {
            showTestBanner()
        } else {
            toast("已切换至：${theme.displayLabel}")
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法打开悬浮窗授权页", e)
            toast(theme.overlayPermissionFallbackToast)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATION)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    internal class RoundedSurfaceDrawable(
        radiusPx: Float,
        val fillColor: Int,
        val strokeWidthPx: Int,
        val strokeColor: Int
    ) : GradientDrawable() {
        init {
            shape = RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private fun roundedRect(
        radiusPx: Float,
        fillColor: Int,
        strokeWidthPx: Int,
        strokeColor: Int
    ): RoundedSurfaceDrawable = RoundedSurfaceDrawable(radiusPx, fillColor, strokeWidthPx, strokeColor)

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    ).toInt()
}
