package com.zqc.tvnotify

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min
import kotlin.math.pow

/**
 * 9 大主题弹窗视觉调色板与材质规范，使全局所有弹窗（更新、历史、关于、主题选择、
 * 播放器设置/音量/连接）在色彩、圆角、边框、字体和焦点高亮上与当前主题深度呼应。
 */
internal data class ThemeDialogPalette(
    val theme: Theme,
    val isLightSurface: Boolean,
    val surfaceStart: Int,
    val surfaceEnd: Int,
    val strokeColor: Int,
    val strokeWidthDp: Float,
    val accentColor: Int,
    val titleColor: Int,
    val titleGlowColor: Int,
    val bodyColor: Int,
    val mutedColor: Int,
    val dividerColor: Int,
    val badgeBg: Int,
    val badgeText: Int,
    val itemBg: Int,
    val itemStroke: Int,
    val itemSelectedBg: Int,
    val focusRingColor: Int,
    val focusedTextColor: Int,
    val cornerRadiusDp: Float,
    val itemCornerRadiusDp: Float,
    val buttonCornerRadiusDp: Float,
    val titleTypeface: Typeface,
    val bodyTypeface: Typeface
) {
    companion object {
        fun from(theme: Theme): ThemeDialogPalette {
            val light = theme.isLightBanner
            val accent = if (light) theme.bannerIconColor(BannerLevel.INFO) else theme.accentColor
            val start = opaqueTint(theme.bannerBackgroundStart, if (light) 0xFD else 0xFB)
            val end = opaqueTint(theme.bannerBackgroundEnd, 0xFE)
            val stroke = if (theme == Theme.DEFAULT) {
                Color.argb(0x48, 0xFF, 0xFF, 0xFF)
            } else {
                theme.bannerStrokeColor(BannerLevel.INFO)
            }
            val strokeWidth = when (theme) {
                Theme.MINECRAFT -> 2.4f
                Theme.CYBERPUNK, Theme.MATRIX, Theme.PALACE -> 2.0f
                Theme.DEFAULT -> 1.2f
                else -> 1.6f
            }
            val titleGlow = if (!light && theme != Theme.DEFAULT) {
                Color.argb(0x78, Color.red(accent), Color.green(accent), Color.blue(accent))
            } else {
                Color.TRANSPARENT
            }
            val muted = if (light) Color.rgb(0x69, 0x5E, 0x50) else theme.hintColor
            val divider = if (light) {
                Color.argb(0x28, 0x22, 0x1E, 0x1A)
            } else {
                Color.argb(0x3C, Color.red(accent), Color.green(accent), Color.blue(accent))
            }
            val badgeBg = Color.argb(
                if (light) 0x22 else 0x2A,
                Color.red(accent),
                Color.green(accent),
                Color.blue(accent)
            )
            val badgeText = if (light) accent else blendColor(accent, Color.WHITE, 0.35f)
            val itemBg = if (light) {
                Color.rgb(0xEF, 0xE7, 0xD6)
            } else {
                blendColor(theme.buttonBg, theme.cardBg, 0.35f)
            }
            val itemStroke = if (light) {
                Color.argb(0x66, 0x8F, 0x7A, 0x62)
            } else {
                theme.buttonStroke
            }
            val itemSelected = Color.argb(
                if (light) 0x24 else 0x30,
                Color.red(accent),
                Color.green(accent),
                Color.blue(accent)
            )
            val focusRing = if (light) Color.rgb(0x22, 0x1E, 0x1A) else Color.WHITE
            val focusedInk = bestContrastTextColor(accent)
            val corner = theme.bannerCornerRadiusDp
            val itemCorner = when {
                theme == Theme.MINECRAFT -> 0f
                theme == Theme.CYBERPUNK || theme == Theme.MATRIX -> 6f
                else -> 10f
            }
            val buttonCorner = when {
                theme == Theme.MINECRAFT -> 0f
                theme == Theme.CYBERPUNK || theme == Theme.MATRIX -> 6f
                theme == Theme.GAMER -> 14f
                else -> 10f
            }
            return ThemeDialogPalette(
                theme = theme,
                isLightSurface = light,
                surfaceStart = start,
                surfaceEnd = end,
                strokeColor = stroke,
                strokeWidthDp = strokeWidth,
                accentColor = accent,
                titleColor = theme.bannerTitle,
                titleGlowColor = titleGlow,
                bodyColor = theme.bannerBodyText,
                mutedColor = muted,
                dividerColor = divider,
                badgeBg = badgeBg,
                badgeText = badgeText,
                itemBg = itemBg,
                itemStroke = itemStroke,
                itemSelectedBg = itemSelected,
                focusRingColor = focusRing,
                focusedTextColor = focusedInk,
                cornerRadiusDp = corner,
                itemCornerRadiusDp = itemCorner,
                buttonCornerRadiusDp = buttonCorner,
                titleTypeface = theme.bannerTitleTypeface,
                bodyTypeface = theme.bannerBodyTypeface
            )
        }

        private fun opaqueTint(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        internal fun blendColor(first: Int, second: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            return Color.rgb(
                (Color.red(first) + (Color.red(second) - Color.red(first)) * t).toInt(),
                (Color.green(first) + (Color.green(second) - Color.green(first)) * t).toInt(),
                (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * t).toInt()
            )
        }

        internal fun relativeLuminance(color: Int): Double {
            fun linear(c: Int): Double {
                val v = c / 255.0
                return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(Color.red(color)) +
                0.7152 * linear(Color.green(color)) +
                0.0722 * linear(Color.blue(color))
        }

        internal fun contrastRatio(first: Int, second: Int): Double {
            val l1 = relativeLuminance(first)
            val l2 = relativeLuminance(second)
            val hi = maxOf(l1, l2)
            val lo = minOf(l1, l2)
            return (hi + 0.05) / (lo + 0.05)
        }

        /** Picks pure white or deep ink to guarantee crisp WCAG AA contrast (>= 4.5:1) on any theme accent fill. */
        internal fun bestContrastTextColor(background: Int): Int {
            val darkInk = Color.rgb(0x0B, 0x0F, 0x13)
            return if (contrastRatio(Color.WHITE, background) >= contrastRatio(darkInk, background)) {
                Color.WHITE
            } else {
                darkInk
            }
        }
    }

    internal class ThemeSurfaceDrawable(
        orientation: Orientation,
        colors: IntArray,
        val cornerRadiusPx: Float,
        strokeWidthPx: Int,
        strokeColor: Int
    ) : GradientDrawable(orientation, colors) {
        init {
            shape = RECTANGLE
            cornerRadius = cornerRadiusPx
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }
    }

    fun buildContainerBackground(density: Float): ThemeSurfaceDrawable =
        ThemeSurfaceDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(surfaceStart, surfaceEnd),
            cornerRadiusDp * density,
            (strokeWidthDp * density + 0.5f).toInt().coerceAtLeast(1),
            strokeColor
        )

    fun buildButtonBackground(density: Float, emphasized: Boolean): StateListDrawable {
        val radius = buttonCornerRadiusDp * density
        val strokePx = (1.2f * density + 0.5f).toInt().coerceAtLeast(1)
        val focusStrokePx = (2.2f * density + 0.5f).toInt().coerceAtLeast(2)
        val normalFill = if (emphasized) {
            Color.argb(
                if (isLightSurface) 0x36 else 0x3A,
                Color.red(accentColor),
                Color.green(accentColor),
                Color.blue(accentColor)
            )
        } else {
            itemBg
        }
        val normalStroke = if (emphasized) accentColor else itemStroke
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rectDrawable(radius, accentColor, focusStrokePx, focusRingColor)
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rectDrawable(radius, accentColor, focusStrokePx, focusRingColor)
            )
            addState(
                intArrayOf(),
                rectDrawable(radius, normalFill, strokePx, normalStroke)
            )
        }
    }

    fun buildItemBackground(density: Float, selected: Boolean): StateListDrawable {
        val radius = itemCornerRadiusDp * density
        val strokePx = (1.1f * density + 0.5f).toInt().coerceAtLeast(1)
        val focusStrokePx = (2.0f * density + 0.5f).toInt().coerceAtLeast(2)
        val normalFill = if (selected) itemSelectedBg else itemBg
        val normalStroke = if (selected) accentColor else itemStroke
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rectDrawable(radius, accentColor, focusStrokePx, focusRingColor)
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                rectDrawable(radius, accentColor, focusStrokePx, focusRingColor)
            )
            addState(
                intArrayOf(),
                rectDrawable(radius, normalFill, strokePx, normalStroke)
            )
        }
    }

    fun buildInputBackground(density: Float): StateListDrawable {
        val radius = itemCornerRadiusDp * density
        val normalStrokePx = (1.2f * density + 0.5f).toInt().coerceAtLeast(1)
        val focusStrokePx = (2.0f * density + 0.5f).toInt().coerceAtLeast(2)
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                rectDrawable(radius, itemBg, focusStrokePx, accentColor)
            )
            addState(
                intArrayOf(),
                rectDrawable(radius, itemBg, normalStrokePx, itemStroke)
            )
        }
    }

    fun buildProgressDrawable(density: Float): Drawable {
        val radius = if (theme == Theme.MINECRAFT) 0f else 6f * density
        val track = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(if (isLightSurface) Color.argb(0x28, 0x22, 0x1E, 0x1A) else Color.argb(0x38, 0xFF, 0xFF, 0xFF))
        }
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(accentColor)
        }
        return LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    internal fun rectDrawable(radius: Float, fill: Int, strokeWidth: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
}

internal data class ThemeDialogItem(
    val title: String,
    val subtitle: String? = null,
    val selected: Boolean = false,
    val leadingBadge: String? = null,
    val leadingBadgeColor: Int = Color.TRANSPARENT,
    val swatchFill: Int? = null,
    val swatchStroke: Int? = null,
    val swatchSquare: Boolean = false,
    val trailingBadge: String? = null,
    val dismissOnClick: Boolean = true,
    val onClick: (ThemedDialog) -> Unit = {}
)

internal class ThemedDialog(
    context: Context,
    val theme: Theme
) : Dialog(context) {
    val palette: ThemeDialogPalette = ThemeDialogPalette.from(theme)
    var positiveButton: Button? = null
        internal set
    var negativeButton: Button? = null
        internal set
    var neutralButton: Button? = null
        internal set
    internal val itemViews = mutableListOf<View>()
    internal var rootContainer: LinearLayout? = null
}

/**
 * 统一 9 大主题自绘弹窗构建器（纯 Framework View 实现，摆脱原生灰白 AlertDialog 割裂感）。
 */
internal class ThemedDialogBuilder(
    private val context: Context,
    private val theme: Theme
) {
    private val palette = ThemeDialogPalette.from(theme)
    private val density = context.resources.displayMetrics.density

    private var titleText: CharSequence? = null
    private var messageText: CharSequence? = null
    private var customView: View? = null
    private var items: List<ThemeDialogItem> = emptyList()
    private var maxWidthDp: Int = 520
    private var cancelable: Boolean = true
    private var dismissListener: DialogInterface.OnDismissListener? = null

    private var positiveLabel: CharSequence? = null
    private var positiveAction: ((ThemedDialog) -> Unit)? = null
    private var positiveAutoDismiss: Boolean = true

    private var negativeLabel: CharSequence? = null
    private var negativeAction: ((ThemedDialog) -> Unit)? = null
    private var negativeAutoDismiss: Boolean = true

    private var neutralLabel: CharSequence? = null
    private var neutralAction: ((ThemedDialog) -> Unit)? = null
    private var neutralAutoDismiss: Boolean = true

    fun setTitle(title: CharSequence?): ThemedDialogBuilder = apply { titleText = title }

    fun setMessage(message: CharSequence?): ThemedDialogBuilder = apply { messageText = message }

    fun setView(view: View?): ThemedDialogBuilder = apply { customView = view }

    fun setItems(items: List<ThemeDialogItem>): ThemedDialogBuilder = apply { this.items = items }

    fun setMaxWidthDp(widthDp: Int): ThemedDialogBuilder = apply { maxWidthDp = widthDp }

    fun setCancelable(flag: Boolean): ThemedDialogBuilder = apply { cancelable = flag }

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?): ThemedDialogBuilder = apply {
        dismissListener = listener
    }

    fun setPositiveButton(
        text: CharSequence,
        autoDismiss: Boolean = true,
        action: ((ThemedDialog) -> Unit)? = null
    ): ThemedDialogBuilder = apply {
        positiveLabel = text
        positiveAutoDismiss = autoDismiss
        positiveAction = action
    }

    fun setNegativeButton(
        text: CharSequence,
        autoDismiss: Boolean = true,
        action: ((ThemedDialog) -> Unit)? = null
    ): ThemedDialogBuilder = apply {
        negativeLabel = text
        negativeAutoDismiss = autoDismiss
        negativeAction = action
    }

    fun setNeutralButton(
        text: CharSequence,
        autoDismiss: Boolean = true,
        action: ((ThemedDialog) -> Unit)? = null
    ): ThemedDialogBuilder = apply {
        neutralLabel = text
        neutralAutoDismiss = autoDismiss
        neutralAction = action
    }

    fun create(): ThemedDialog {
        val dialog = ThemedDialog(context, theme)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        if (dismissListener != null) {
            dialog.setOnDismissListener(dismissListener)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = palette.buildContainerBackground(density)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            clipChildren = false
            clipToPadding = false
        }
        dialog.rootContainer = card

        // 顶部强调细线 + 标题与主题徽章
        val accentBar = View(context).apply {
            val barRadius = if (theme == Theme.MINECRAFT) 0f else dp(2).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = barRadius
                setColor(palette.accentColor)
            }
        }
        card.addView(
            accentBar,
            LinearLayout.LayoutParams(dp(42), dp(3)).apply { bottomMargin = dp(10) }
        )

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = titleText ?: theme.shortName
            setTextColor(palette.titleColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = palette.titleTypeface
            includeFontPadding = false
            if (palette.titleGlowColor != Color.TRANSPARENT) {
                setShadowLayer(7f, 0f, 1f, palette.titleGlowColor)
            }
        }
        headerRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val themeBadge = TextView(context).apply {
            text = "${theme.emoji} ${theme.shortName}"
            setTextColor(palette.badgeText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = palette.bodyTypeface
            includeFontPadding = false
            setPadding(dp(8), dp(3), dp(8), dp(3))
            val pillRadius = if (theme == Theme.MINECRAFT) 0f else dp(7).toFloat()
            background = palette.rectDrawable(pillRadius, palette.badgeBg, dp(1), Color.argb(0x66, Color.red(palette.accentColor), Color.green(palette.accentColor), Color.blue(palette.accentColor)))
        }
        headerRow.addView(themeBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(10)
        })
        card.addView(headerRow)

        val divider = View(context).apply { setBackgroundColor(palette.dividerColor) }
        card.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(10)
            bottomMargin = dp(12)
        })

        // 可选正文文本
        val message = messageText
        var focusTarget: View? = null
        if (!message.isNullOrBlank()) {
            val maxMsgHeight = (context.resources.displayMetrics.heightPixels * 0.42f).toInt().coerceAtLeast(dp(120))
            val scroll = MaxHeightScrollView(context, maxMsgHeight).apply {
                isFocusable = false
                isVerticalScrollBarEnabled = true
            }
            val msgView = TextView(context).apply {
                text = message
                setTextColor(palette.bodyColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = palette.bodyTypeface
                setLineSpacing(dp(3).toFloat(), 1.15f)
                includeFontPadding = false
            }
            scroll.addView(msgView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = if (customView != null || items.isNotEmpty()) dp(10) else dp(4)
            })
        }

        // 自定义视图内容（进度条 / 音量滑块 / 连接输入等）
        val bodyCustom = customView
        if (bodyCustom != null) {
            card.addView(bodyCustom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = if (items.isNotEmpty()) dp(10) else dp(4)
            })
        }

        // 可聚焦主题列表区
        val builtItemRows = mutableListOf<View>()
        if (items.isNotEmpty()) {
            val listMaxHeight = (context.resources.displayMetrics.heightPixels * 0.54f).toInt().coerceAtLeast(dp(180))
            val listScroll = MaxHeightScrollView(context, listMaxHeight).apply {
                isFocusable = false
                clipChildren = false
                clipToPadding = false
            }
            val listColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 给焦点放大保留充足边缘内边距，彻底杜绝裁剪
                setPadding(dp(7), dp(5), dp(7), dp(5))
                clipChildren = false
                clipToPadding = false
            }
            for ((index, item) in items.withIndex()) {
                val row = buildItemRow(dialog, item)
                row.id = View.generateViewId()
                builtItemRows.add(row)
                dialog.itemViews.add(row)
                if (item.selected && focusTarget == null) {
                    focusTarget = row
                }
                listColumn.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) topMargin = dp(7)
                })
            }
            if (focusTarget == null && builtItemRows.isNotEmpty()) {
                focusTarget = builtItemRows.first()
            }
            listScroll.addView(listColumn, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // 底部按钮栏（左 Neutral，右 Negative + Positive）
        val hasFooter = neutralLabel != null || negativeLabel != null || positiveLabel != null
        val footerButtons = mutableListOf<Button>()
        if (hasFooter) {
            val footer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), dp(5), dp(7), dp(5))
                clipChildren = false
                clipToPadding = false
            }
            neutralLabel?.let { label ->
                val btn = buildFooterButton(label, emphasized = false) {
                    neutralAction?.invoke(dialog)
                    if (neutralAutoDismiss) dialog.dismiss()
                }
                btn.id = View.generateViewId()
                dialog.neutralButton = btn
                footerButtons.add(btn)
                footer.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            negativeLabel?.let { label ->
                val btn = buildFooterButton(label, emphasized = false) {
                    negativeAction?.invoke(dialog)
                    if (negativeAutoDismiss) dialog.dismiss()
                }
                btn.id = View.generateViewId()
                dialog.negativeButton = btn
                footerButtons.add(btn)
                footer.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            positiveLabel?.let { label ->
                val btn = buildFooterButton(label, emphasized = true) {
                    positiveAction?.invoke(dialog)
                    if (positiveAutoDismiss) dialog.dismiss()
                }
                btn.id = View.generateViewId()
                dialog.positiveButton = btn
                footerButtons.add(btn)
                footer.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (negativeLabel != null) leftMargin = dp(10)
                })
            }
            card.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        // 串联列表与底部按钮遥控器焦点链
        val defaultFooter = dialog.positiveButton ?: dialog.negativeButton ?: dialog.neutralButton
        for (i in builtItemRows.indices) {
            val row = builtItemRows[i]
            row.nextFocusUpId = if (i > 0) builtItemRows[i - 1].id else row.id
            row.nextFocusDownId = if (i + 1 < builtItemRows.size) builtItemRows[i + 1].id else (defaultFooter?.id ?: row.id)
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = row.id
        }
        for (i in footerButtons.indices) {
            val btn = footerButtons[i]
            btn.nextFocusLeftId = if (i > 0) footerButtons[i - 1].id else btn.id
            btn.nextFocusRightId = if (i + 1 < footerButtons.size) footerButtons[i + 1].id else btn.id
            if (builtItemRows.isNotEmpty()) {
                btn.nextFocusUpId = builtItemRows.last().id
            }
        }

        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.setPadding(0, 0, 0, 0)
            setDimAmount(0.64f)
            val widthLimit = dp(maxWidthDp)
            val maxAllowed = context.resources.displayMetrics.widthPixels - dp(48)
            setLayout(min(widthLimit, maxAllowed).coerceAtLeast(dp(260)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val initial = focusTarget ?: dialog.positiveButton ?: dialog.negativeButton ?: dialog.neutralButton
        initial?.post {
            if (card.findFocus() == null && !initial.hasFocus()) {
                initial.requestFocus()
            }
        }
        return dialog
    }

    fun show(): ThemedDialog = create().also { it.show() }

    private fun buildItemRow(dialog: ThemedDialog, item: ThemeDialogItem): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = palette.buildItemBackground(density, item.selected)
        }

        val swatchIdleStroke = item.swatchStroke ?: palette.itemStroke
        val swatchView = item.swatchFill?.let { fill ->
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = if (item.swatchSquare) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
                    setColor(fill)
                    setStroke(dp(1.5f), swatchIdleStroke)
                }
            }.also { row.addView(it, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) }) }
        }

        val leadingBadgeIdleColor = if (item.leadingBadgeColor != Color.TRANSPARENT) item.leadingBadgeColor else palette.accentColor
        val leadingBadgeView = item.leadingBadge?.takeIf { it.isNotBlank() }?.let { badgeText ->
            TextView(context).apply {
                text = badgeText
                setTextColor(leadingBadgeIdleColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = palette.bodyTypeface
                includeFontPadding = false
                setPadding(dp(7), dp(3), dp(7), dp(3))
                val r = if (theme == Theme.MINECRAFT) 0f else dp(5).toFloat()
                background = palette.rectDrawable(
                    r,
                    Color.argb(0x24, Color.red(leadingBadgeIdleColor), Color.green(leadingBadgeIdleColor), Color.blue(leadingBadgeIdleColor)),
                    dp(1),
                    Color.argb(0x70, Color.red(leadingBadgeIdleColor), Color.green(leadingBadgeIdleColor), Color.blue(leadingBadgeIdleColor))
                )
            }.also {
                row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(10)
                })
            }
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleColorIdle = palette.titleColor
        val subColorIdle = palette.mutedColor

        val titleView = TextView(context).apply {
            text = item.title
            setTextColor(titleColorIdle)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = palette.titleTypeface
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(titleView)

        val subtitleView = item.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
            TextView(context).apply {
                text = sub
                setTextColor(subColorIdle)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = palette.bodyTypeface
                includeFontPadding = false
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }.also { textCol.addView(it) }
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val trailingView = item.trailingBadge?.takeIf { it.isNotBlank() }?.let { badge ->
            TextView(context).apply {
                text = badge
                setTextColor(if (item.selected) palette.accentColor else palette.mutedColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = palette.bodyTypeface
                includeFontPadding = false
                setPadding(dp(8), 0, 0, 0)
            }.also { row.addView(it) }
        }

        row.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            v.animate().scaleX(if (hasFocus) 1.02f else 1f).scaleY(if (hasFocus) 1.02f else 1f).setDuration(110L).start()
            val fg = if (hasFocus) palette.focusedTextColor else titleColorIdle
            val subFg = if (hasFocus) palette.focusedTextColor else subColorIdle
            titleView.setTextColor(fg)
            subtitleView?.setTextColor(subFg)
            trailingView?.setTextColor(if (hasFocus) palette.focusedTextColor else if (item.selected) palette.accentColor else subColorIdle)
            (swatchView?.background as? GradientDrawable)?.setStroke(
                dp(if (hasFocus) 2f else 1.5f),
                if (hasFocus) palette.focusRingColor else swatchIdleStroke
            )
            leadingBadgeView?.let { badge ->
                val badgeTone = if (hasFocus) palette.focusedTextColor else leadingBadgeIdleColor
                badge.setTextColor(badgeTone)
                val badgeRadius = if (theme == Theme.MINECRAFT) 0f else dp(5).toFloat()
                badge.background = palette.rectDrawable(
                    badgeRadius,
                    Color.argb(0x24, Color.red(badgeTone), Color.green(badgeTone), Color.blue(badgeTone)),
                    dp(1),
                    Color.argb(if (hasFocus) 0x90 else 0x70, Color.red(badgeTone), Color.green(badgeTone), Color.blue(badgeTone))
                )
            }
        }
        row.setOnClickListener {
            item.onClick(dialog)
            if (item.dismissOnClick && dialog.isShowing) {
                dialog.dismiss()
            }
        }
        return row
    }

    private fun buildFooterButton(
        label: CharSequence,
        emphasized: Boolean,
        onClick: () -> Unit
    ): Button {
        val idleTextColor = if (emphasized) palette.titleColor else palette.bodyColor
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(idleTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = palette.titleTypeface
            includeFontPadding = false
            minWidth = dp(82)
            minimumWidth = dp(82)
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = true
            isFocusableInTouchMode = false
            backgroundTintList = null
            stateListAnimator = null
            background = palette.buildButtonBackground(density, emphasized)
            setOnFocusChangeListener { v, hasFocus ->
                v.animate().cancel()
                v.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(110L).start()
                setTextColor(if (hasFocus) palette.focusedTextColor else idleTextColor)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()
}

internal fun ThemeDialogPalette.styleEditText(editText: EditText, density: Float) {
    editText.setTextColor(titleColor)
    editText.setHintTextColor(mutedColor)
    editText.typeface = bodyTypeface
    editText.backgroundTintList = null
    editText.background = buildInputBackground(density)
    val padH = (12f * density + 0.5f).toInt()
    val padV = (10f * density + 0.5f).toInt()
    editText.setPadding(padH, padV, padH, padV)
}

internal fun ThemeDialogPalette.styleProgressBar(progressBar: ProgressBar, density: Float) {
    progressBar.progressDrawable = buildProgressDrawable(density)
}

private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val capped = if (mode == View.MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
        val cappedHeight = View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedHeight)
    }
}
