package com.zqc.tvnotify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun playerBlend(first: Int, second: Int, amount: Float): Int {
    val mix = amount.coerceIn(0f, 1f)
    fun channel(a: Int, b: Int) = (a + (b - a) * mix).toInt()
    return Color.rgb(channel(Color.red(first), Color.red(second)), channel(Color.green(first), Color.green(second)), channel(Color.blue(first), Color.blue(second)))
}

internal fun playerAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun Context.playerDp(value: Float): Float = value * resources.displayMetrics.density

private fun View.animatePlayerFocus(focused: Boolean) {
    animate().cancel()
    animate().scaleX(if (focused) 1.04f else 1f).scaleY(if (focused) 1.04f else 1f).setDuration(120L).start()
}

/** Two-column TV player stage (left: vinyl disc & controls, right: scrolling lyrics), stacking on narrow screens. */
internal class SendspinStageLayout(
    context: Context,
    private val leftColumn: View,
    private val rightColumn: View
) : ViewGroup(context) {
    private var contentWidth = 0
    private var leftWidth = 0
    private var rightWidth = 0
    private var gap = 0
    private var stacked = false

    init {
        clipChildren = false
        clipToPadding = false
        addView(leftColumn)
        addView(rightColumn)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        contentWidth = min(width, context.playerDp(1260f).toInt())
        val widthDp = contentWidth / density
        stacked = widthDp < 680f

        if (stacked) {
            gap = context.playerDp(16f).toInt()
            val maxLeftHeight = ((height - gap) * 0.62f).toInt().coerceAtLeast(0)
            leftColumn.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(maxLeftHeight, MeasureSpec.AT_MOST)
            )
            val remainHeight = (height - leftColumn.measuredHeight - gap).coerceAtLeast(0)
            rightColumn.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(remainHeight, MeasureSpec.EXACTLY)
            )
        } else {
            gap = context.playerDp(if (widthDp < 860f) 28f else 48f).toInt().coerceAtMost(contentWidth / 10)
            leftWidth = ((contentWidth - gap) * 0.43f).toInt().coerceAtLeast(0)
            rightWidth = (contentWidth - gap - leftWidth).coerceAtLeast(0)
            leftColumn.measure(
                MeasureSpec.makeMeasureSpec(leftWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            rightColumn.measure(
                MeasureSpec.makeMeasureSpec(rightWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val start = (width - contentWidth) / 2
        if (stacked) {
            val leftH = leftColumn.measuredHeight
            leftColumn.layout(start, 0, start + contentWidth, leftH)
            rightColumn.layout(start, leftH + gap, start + contentWidth, height)
        } else {
            leftColumn.layout(start, 0, start + leftWidth, height)
            rightColumn.layout(start + leftWidth + gap, 0, start + contentWidth, height)
        }
    }
}

/**
 * 播放器全屏底图：样式一采用柔和专辑主色微光渐变；样式二采用居中裁切的歌曲原图高斯模糊背景
 * 并叠加轻量暗角可读蒙版，无封面时平滑回退为微光渐变。
 */
internal class SendspinAmbientDrawable(private val base: Int, initialColor: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapMatrix = Matrix()
    private val boundsRectF = RectF()
    private var ambient = initialColor
    private var playerStyle = Prefs.SendspinPlayerStyle.VINYL
    private var blurredBitmap: Bitmap? = null
    private var baseGradient: Shader? = null
    private var artGradient: Shader? = null
    private var blurRadialScrim: Shader? = null
    private var blurLinearScrim: Shader? = null

    fun setAmbientColor(color: Int) {
        if (ambient == color) return
        ambient = color
        updateShaders()
        invalidateSelf()
    }

    fun setPlayerStyle(style: Prefs.SendspinPlayerStyle) {
        if (playerStyle == style) return
        playerStyle = style
        invalidateSelf()
    }

    fun setBlurredBitmap(bitmap: Bitmap?) {
        if (blurredBitmap === bitmap) return
        blurredBitmap = bitmap
        updateBitmapMatrix()
        invalidateSelf()
    }

    fun hasActiveBlurredBackdrop(): Boolean =
        playerStyle == Prefs.SendspinPlayerStyle.COVER_BLUR && blurredBitmap != null

    override fun onBoundsChange(bounds: Rect) {
        boundsRectF.set(bounds)
        updateShaders()
        updateBitmapMatrix()
    }

    private fun updateShaders() {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        baseGradient = LinearGradient(0f, 0f, width, height, playerBlend(base, Color.rgb(27, 30, 35), .35f), playerBlend(base, Color.BLACK, .24f), Shader.TileMode.CLAMP)
        artGradient = RadialGradient(width * .24f, height * .47f, max(width * .62f, height), intArrayOf(playerAlpha(ambient, 65), playerAlpha(ambient, 22), playerAlpha(ambient, 0)), floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        blurRadialScrim = RadialGradient(
            width * 0.26f,
            height * 0.48f,
            max(width * 0.72f, height * 0.95f),
            intArrayOf(Color.argb(36, 8, 10, 14), Color.argb(92, 8, 10, 14), Color.argb(132, 8, 10, 14)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        blurLinearScrim = LinearGradient(
            0f, 0f, 0f, height,
            intArrayOf(Color.argb(62, 8, 10, 14), Color.argb(40, 8, 10, 14), Color.argb(112, 8, 10, 14)),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun updateBitmapMatrix() {
        val bmp = blurredBitmap ?: return
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f || bmp.width <= 0 || bmp.height <= 0) return
        val scale = max(w / bmp.width.toFloat(), h / bmp.height.toFloat())
        val dx = bounds.left + (w - bmp.width * scale) / 2f
        val dy = bounds.top + (h - bmp.height * scale) / 2f
        bitmapMatrix.reset()
        bitmapMatrix.setScale(scale, scale)
        bitmapMatrix.postTranslate(dx, dy)
    }

    override fun draw(canvas: Canvas) {
        val blur = if (playerStyle == Prefs.SendspinPlayerStyle.COVER_BLUR) blurredBitmap else null
        if (blur != null && !blur.isRecycled) {
            val save = canvas.save()
            canvas.clipRect(bounds)
            canvas.drawBitmap(blur, bitmapMatrix, bitmapPaint)
            canvas.restoreToCount(save)
            paint.shader = blurRadialScrim
            canvas.drawRect(boundsRectF, paint)
            paint.shader = blurLinearScrim
            canvas.drawRect(boundsRectF, paint)
            paint.shader = null
            return
        }
        paint.shader = baseGradient
        canvas.drawRect(bounds, paint)
        paint.shader = artGradient
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; bitmapPaint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; bitmapPaint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.OPAQUE
}

internal enum class SendspinIcon { BACK, PLAY, PAUSE, PREVIOUS, NEXT, VOLUME, MUTED, SETTINGS, MINUS, PLUS, NOTE }

/** All symbols are paths, so the player does not depend on emoji or a device icon font. */
internal class SendspinIconDrawable(
    private val context: Context,
    var icon: SendspinIcon,
    private val color: Int,
    private val sizeDp: Float = 24f
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        val side = min(bounds.width(), bounds.height()).toFloat()
        canvas.translate(bounds.left + (bounds.width() - side) / 2f, bounds.top + (bounds.height() - side) / 2f)
        canvas.scale(side / 24f, side / 24f)
        paint.color = color
        paint.alpha = drawableAlpha
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 1.7f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        path.reset()
        when (icon) {
            SendspinIcon.PLAY -> {
                path.moveTo(8f, 4.6f); path.lineTo(19f, 11.1f); path.quadTo(20.5f, 12f, 19f, 12.9f)
                path.lineTo(8f, 19.4f); path.quadTo(6.6f, 20.2f, 6.6f, 18.5f); path.lineTo(6.6f, 5.5f); path.quadTo(6.6f, 3.8f, 8f, 4.6f)
                canvas.drawPath(path, paint)
            }
            SendspinIcon.PAUSE -> {
                canvas.drawRoundRect(6f, 4.5f, 10f, 19.5f, 1f, 1f, paint)
                canvas.drawRoundRect(14f, 4.5f, 18f, 19.5f, 1f, 1f, paint)
            }
            SendspinIcon.PREVIOUS, SendspinIcon.NEXT -> {
                if (icon == SendspinIcon.PREVIOUS) { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }
                path.moveTo(5.5f, 5.5f); path.lineTo(15.5f, 12f); path.lineTo(5.5f, 18.5f); path.close()
                canvas.drawPath(path, paint)
                canvas.drawRoundRect(17f, 5f, 19.5f, 19f, .8f, .8f, paint)
            }
            SendspinIcon.BACK -> {
                paint.style = Paint.Style.STROKE
                path.moveTo(13.5f, 5f); path.lineTo(6.5f, 12f); path.lineTo(13.5f, 19f)
                canvas.drawPath(path, paint); canvas.drawLine(7f, 12f, 20f, 12f, paint)
            }
            SendspinIcon.VOLUME, SendspinIcon.MUTED -> {
                path.moveTo(3f, 9f); path.lineTo(7f, 9f); path.lineTo(12f, 5f); path.lineTo(12f, 19f); path.lineTo(7f, 15f); path.lineTo(3f, 15f); path.close()
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                if (icon == SendspinIcon.VOLUME) {
                    canvas.drawArc(10f, 7f, 20f, 17f, -55f, 110f, false, paint)
                    canvas.drawArc(7f, 3.5f, 23f, 20.5f, -48f, 96f, false, paint)
                } else {
                    canvas.drawLine(16f, 9f, 22f, 15f, paint); canvas.drawLine(22f, 9f, 16f, 15f, paint)
                }
            }
            SendspinIcon.SETTINGS -> {
                paint.style = Paint.Style.STROKE
                for (index in 0..2) {
                    val y = 6f + index * 6f
                    val knob = if (index == 1) 15f else 9f
                    canvas.drawLine(4f, y, knob - 2.3f, y, paint)
                    canvas.drawLine(knob + 2.3f, y, 20f, y, paint)
                    canvas.drawCircle(knob, y, 2.3f, paint)
                }
            }
            SendspinIcon.MINUS, SendspinIcon.PLUS -> {
                paint.style = Paint.Style.STROKE
                canvas.drawLine(5f, 12f, 19f, 12f, paint)
                if (icon == SendspinIcon.PLUS) canvas.drawLine(12f, 5f, 12f, 19f, paint)
            }
            SendspinIcon.NOTE -> {
                paint.style = Paint.Style.STROKE
                path.moveTo(9f, 17f); path.lineTo(9f, 6f); path.lineTo(19f, 4f); path.lineTo(19f, 15f)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                canvas.drawOval(3f, 15f, 10f, 20f, paint); canvas.drawOval(13f, 13f, 20f, 18f, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun getIntrinsicWidth(): Int = context.playerDp(sizeDp).toInt()
    override fun getIntrinsicHeight(): Int = intrinsicWidth
    override fun setAlpha(alpha: Int) { drawableAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal class SendspinControlDrawable(
    private val density: Float,
    private val accent: Int,
    private val primary: Boolean = false,
    private val radiusDp: Float = 100f,
    private val quiet: Boolean = false,
    private val idleFillColor: Int = Color.argb(13, 255, 255, 255),
    private val idleStrokeColor: Int = Color.argb(22, 255, 255, 255)
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun isStateful(): Boolean = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }

    override fun draw(canvas: Canvas) {
        val enabled = android.R.attr.state_enabled in state
        val focused = android.R.attr.state_focused in state && enabled
        val pressed = android.R.attr.state_pressed in state && enabled
        val inset = density * 1.5f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        paint.style = Paint.Style.FILL
        paint.color = when {
            primary -> playerBlend(Color.rgb(236, 241, 241), accent, if (pressed) .28f else .08f)
            pressed -> playerAlpha(accent, 42)
            focused -> playerAlpha(accent, 24)
            quiet -> Color.TRANSPARENT
            else -> idleFillColor
        }
        if (!enabled) paint.color = playerAlpha(paint.color, if (primary) 50 else 5)
        canvas.drawRoundRect(rect, radiusDp * density, radiusDp * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * if (focused) 2f else 1f
        paint.color = when {
            focused -> accent
            quiet -> Color.TRANSPARENT
            primary -> Color.argb(if (enabled) 65 else 10, 255, 255, 255)
            else -> if (enabled) idleStrokeColor else playerAlpha(idleStrokeColor, 30)
        }
        canvas.drawRoundRect(rect, radiusDp * density, radiusDp * density, paint)
    }

    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radiusDp * density) }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal class SendspinIconButton(
    context: Context,
    icon: SendspinIcon,
    accent: Int,
    primary: Boolean = false,
    iconColor: Int = if (primary) Color.rgb(18, 22, 26) else Color.rgb(234, 239, 240),
    radiusDp: Float = 100f,
    idleFillColor: Int = Color.argb(13, 255, 255, 255),
    idleStrokeColor: Int = Color.argb(22, 255, 255, 255)
) : ImageButton(context) {
    private val symbol = SendspinIconDrawable(context, icon, iconColor, if (primary) 32f else 24f)
    var icon: SendspinIcon
        get() = symbol.icon
        set(value) { if (symbol.icon != value) { symbol.icon = value; symbol.invalidateSelf() } }

    init {
        backgroundTintList = null
        background = SendspinControlDrawable(
            density = resources.displayMetrics.density,
            accent = accent,
            primary = primary,
            radiusDp = radiusDp,
            idleFillColor = idleFillColor,
            idleStrokeColor = idleStrokeColor
        )
        stateListAnimator = null
        setImageDrawable(symbol)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(0, 0, 0, 0)
        isFocusable = true
        isFocusableInTouchMode = false
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        imageAlpha = if (isEnabled) 255 else 85
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animatePlayerFocus(gainFocus)
    }
}

internal class SendspinFocusPill(context: Context, accent: Int) : LinearLayout(context) {
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        background = SendspinControlDrawable(resources.displayMetrics.density, accent, radiusDp = 14f)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animatePlayerFocus(gainFocus)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }
}

/** Native seeking semantics with a thin painted track and explicit D-pad/accessibility commits. */
internal class SendspinSeekBar(
    context: Context,
    private val accent: Int,
    private val trackColor: Int = Color.argb(44, 255, 255, 255),
    private val disabledTrackColor: Int = Color.argb(22, 255, 255, 255)
) : SeekBar(context) {
    var onSeekCommit: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        max = 10_000
        progressDrawable = ColorDrawable(Color.TRANSPARENT)
        thumb = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setSize(context.playerDp(12f).toInt(), context.playerDp(12f).toInt()) }
        thumbOffset = context.playerDp(6f).toInt()
        background = SendspinControlDrawable(resources.displayMetrics.density, accent, radiusDp = 10f, quiet = true)
        splitTrack = false
        setPadding(context.playerDp(12f).toInt(), 0, context.playerDp(12f).toInt(), 0)
        isFocusable = true
        isFocusableInTouchMode = false
    }

    override fun onDraw(canvas: Canvas) {
        val start = paddingLeft.toFloat()
        val end = (width - paddingRight).toFloat()
        val y = height / 2f
        val x = start + (end - start) * progress / max.coerceAtLeast(1)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = context.playerDp(if (isFocused) 3f else 2f)
        paint.color = if (isEnabled) trackColor else disabledTrackColor
        canvas.drawLine(start, y, end, y, paint)
        paint.color = playerAlpha(accent, if (isEnabled) 255 else 100)
        if (x > start) canvas.drawLine(start, y, x, y, paint)
        if (isEnabled) canvas.drawCircle(x, y, context.playerDp(if (isFocused || isPressed) 5f else 3f), paint)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            onSeekCommit?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyUp(keyCode, event)
        if (isEnabled && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            onSeekCommit?.invoke()
            return true
        }
        return handled
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val handled = super.performAccessibilityAction(action, arguments)
        if (handled) onSeekCommit?.invoke()
        return handled
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animatePlayerFocus(gainFocus)
        if (!gainFocus) onSeekCommit?.invoke()
    }
}

/** Tiny sampled palette; the full artwork is never scanned on a progress update. */
internal fun sendspinArtworkColor(bitmap: Bitmap, fallback: Int): Int {
    val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
    val pixels = IntArray(24 * 24)
    sample.getPixels(pixels, 0, 24, 0, 0, 24, 24)
    var red = 0.0; var green = 0.0; var blue = 0.0; var total = 0.0
    for (pixel in pixels) {
        if (Color.alpha(pixel) < 128) continue
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        val high = max(r, max(g, b)); val low = min(r, min(g, b))
        if (high < 25 || low > 230) continue
        val weight = .15 + (high - low).toDouble() / high.coerceAtLeast(1)
        red += r * weight; green += g * weight; blue += b * weight; total += weight
    }
    if (total == 0.0) return fallback
    val hsv = FloatArray(3)
    Color.RGBToHSV((red / total).toInt(), (green / total).toInt(), (blue / total).toInt(), hsv)
    hsv[1] = hsv[1].coerceIn(.12f, .6f)
    hsv[2] = hsv[2].coerceIn(.3f, .7f)
    return Color.HSVToColor(hsv)
}

/**
 * 兼容 API 23+ 的纯软件高斯模糊底图生成器：先居中裁切并降采样至 96x96（适度提升色彩饱和度），
 * 再执行 3 趟滑动均值模糊（等价高斯卷积核），既保留封面清晰可辨的色彩与光影轮廓，又零卡顿低内存。
 */
internal fun createBlurredArtworkBackdrop(source: Bitmap): Bitmap? {
    val srcW = source.width
    val srcH = source.height
    if (srcW <= 0 || srcH <= 0 || source.isRecycled) return null
    val readable = if (android.os.Build.VERSION.SDK_INT >= 26 && source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else {
        source
    }
    val size = 96
    val cropSide = min(srcW, srcH)
    val cropX = (srcW - cropSide) / 2
    val cropY = (srcH - cropSide) / 2
    val pixels = IntArray(size * size)
    val rowBuf0 = IntArray(cropSide)
    val rowBuf1 = IntArray(cropSide)
    var cachedRow0 = -1
    var cachedRow1 = -1

    // 适度提升饱和度并压暗底色，效果对齐 ColorMatrix(sat=1.32, scale=0.94)，同时兼容纯像素与硬件位图
    val sat = 1.32f
    val invSat = 1f - sat
    val rw = 0.213f * invSat
    val gw = 0.715f * invSat
    val bw = 0.072f * invSat
    val scale = 0.94f

    for (y in 0 until size) {
        val fy = ((y + 0.5f) * cropSide / size.toFloat() - 0.5f).coerceIn(0f, (cropSide - 1).toFloat())
        val sy0 = fy.toInt().coerceIn(0, cropSide - 1)
        val sy1 = (sy0 + 1).coerceAtMost(cropSide - 1)
        val wy = fy - sy0
        val iwy = 1f - wy

        if (cachedRow0 != sy0) {
            if (cachedRow1 == sy0) {
                System.arraycopy(rowBuf1, 0, rowBuf0, 0, cropSide)
            } else {
                readable.getPixels(rowBuf0, 0, cropSide, cropX, cropY + sy0, cropSide, 1)
            }
            cachedRow0 = sy0
        }
        if (sy1 == sy0) {
            System.arraycopy(rowBuf0, 0, rowBuf1, 0, cropSide)
            cachedRow1 = sy1
        } else if (cachedRow1 != sy1) {
            readable.getPixels(rowBuf1, 0, cropSide, cropX, cropY + sy1, cropSide, 1)
            cachedRow1 = sy1
        }

        val rowOffset = y * size
        for (x in 0 until size) {
            val fx = ((x + 0.5f) * cropSide / size.toFloat() - 0.5f).coerceIn(0f, (cropSide - 1).toFloat())
            val sx0 = fx.toInt().coerceIn(0, cropSide - 1)
            val sx1 = (sx0 + 1).coerceAtMost(cropSide - 1)
            val wx = fx - sx0
            val iwx = 1f - wx

            val c00 = rowBuf0[sx0]; val c10 = rowBuf0[sx1]
            val c01 = rowBuf1[sx0]; val c11 = rowBuf1[sx1]
            val w00 = iwx * iwy; val w10 = wx * iwy; val w01 = iwx * wy; val w11 = wx * wy

            val a = (((c00 ushr 24) and 0xFF) * w00 + ((c10 ushr 24) and 0xFF) * w10 +
                ((c01 ushr 24) and 0xFF) * w01 + ((c11 ushr 24) and 0xFF) * w11) / 255f
            val rSrc = ((c00 ushr 16) and 0xFF) * w00 + ((c10 ushr 16) and 0xFF) * w10 +
                ((c01 ushr 16) and 0xFF) * w01 + ((c11 ushr 16) and 0xFF) * w11
            val gSrc = ((c00 ushr 8) and 0xFF) * w00 + ((c10 ushr 8) and 0xFF) * w10 +
                ((c01 ushr 8) and 0xFF) * w01 + ((c11 ushr 8) and 0xFF) * w11
            val bSrc = (c00 and 0xFF) * w00 + (c10 and 0xFF) * w10 +
                (c01 and 0xFF) * w01 + (c11 and 0xFF) * w11

            val rSat = ((rw + sat) * rSrc + gw * gSrc + bw * bSrc) * scale
            val gSat = (rw * rSrc + (gw + sat) * gSrc + bw * bSrc) * scale
            val bSat = (rw * rSrc + gw * gSrc + (bw + sat) * bSrc) * scale

            val invA = 1f - a
            val rOut = (rSat * a + 18f * invA + 0.5f).toInt().coerceIn(0, 255)
            val gOut = (gSat * a + 22f * invA + 0.5f).toInt().coerceIn(0, 255)
            val bOut = (bSat * a + 28f * invA + 0.5f).toInt().coerceIn(0, 255)
            pixels[rowOffset + x] = (0xFF shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
        }
    }

    if (readable !== source) readable.recycle()
    applyThreePassGaussianBlur(pixels, size, size, radius = 4)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

internal fun applyThreePassGaussianBlur(pixels: IntArray, w: Int, h: Int, radius: Int) {
    if (w <= 0 || h <= 0 || radius <= 0) return
    val tmp = IntArray(w * h)
    val div = radius * 2 + 1
    val half = div / 2
    val maxX = w - 1
    val maxY = h - 1

    repeat(3) {
        // Horizontal box pass: pixels -> tmp
        for (y in 0 until h) {
            val row = y * w
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val c = pixels[row + i.coerceIn(0, maxX)]
                rSum += (c ushr 16) and 0xFF
                gSum += (c ushr 8) and 0xFF
                bSum += c and 0xFF
            }
            for (x in 0 until w) {
                val rAvg = (rSum + half) / div
                val gAvg = (gSum + half) / div
                val bAvg = (bSum + half) / div
                tmp[row + x] = (0xFF shl 24) or (rAvg shl 16) or (gAvg shl 8) or bAvg
                if (x + 1 < w) {
                    val cOut = pixels[row + (x - radius).coerceIn(0, maxX)]
                    val cIn = pixels[row + (x + radius + 1).coerceIn(0, maxX)]
                    rSum += ((cIn ushr 16) and 0xFF) - ((cOut ushr 16) and 0xFF)
                    gSum += ((cIn ushr 8) and 0xFF) - ((cOut ushr 8) and 0xFF)
                    bSum += (cIn and 0xFF) - (cOut and 0xFF)
                }
            }
        }
        // Vertical box pass: tmp -> pixels
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -radius..radius) {
                val c = tmp[i.coerceIn(0, maxY) * w + x]
                rSum += (c ushr 16) and 0xFF
                gSum += (c ushr 8) and 0xFF
                bSum += c and 0xFF
            }
            for (y in 0 until h) {
                val rAvg = (rSum + half) / div
                val gAvg = (gSum + half) / div
                val bAvg = (bSum + half) / div
                pixels[y * w + x] = (0xFF shl 24) or (rAvg shl 16) or (gAvg shl 8) or bAvg
                if (y + 1 < h) {
                    val cOut = tmp[(y - radius).coerceIn(0, maxY) * w + x]
                    val cIn = tmp[(y + radius + 1).coerceIn(0, maxY) * w + x]
                    rSum += ((cIn ushr 16) and 0xFF) - ((cOut ushr 16) and 0xFF)
                    gSum += ((cIn ushr 8) and 0xFF) - ((cOut ushr 8) and 0xFF)
                    bSum += (cIn and 0xFF) - (cOut and 0xFF)
                }
            }
        }
    }
}

/** Custom-drawn rotating vinyl disc with concentric grooves and circular center artwork label. */
internal class VinylRecordView(context: Context, initialAccent: Int) : View(context) {
    private var accent = initialAccent
    private var artwork: Bitmap? = null
    private var artworkShader: BitmapShader? = null
    private val shaderMatrix = Matrix()
    private var playing = false
    private var rotationDegrees = 0f
    private var lastFrameNs = 0L

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var labelRadius = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val platterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val vinylPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(18, 255, 255, 255)
    }
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spindlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val vinylBounds = RectF()
    private val noteDrawable = SendspinIconDrawable(context, SendspinIcon.NOTE, Color.WHITE)

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "黑胶唱片封面"
    }

    fun setAccentColor(color: Int) {
        if (accent == color) return
        accent = color
        rebuildShaders()
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (artwork === bitmap) return
        artwork = bitmap
        artworkShader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        artworkPaint.shader = artworkShader
        updateArtworkMatrix()
        invalidate()
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        lastFrameNs = 0L
        if (playing && isAttachedToWindow && windowVisibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) * 0.44f
        labelRadius = radius * 0.45f
        vinylBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        rebuildShaders()
        updateArtworkMatrix()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            lastFrameNs = 0L
        } else if (playing && isAttachedToWindow) {
            lastFrameNs = 0L
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        lastFrameNs = 0L
        super.onDetachedFromWindow()
    }

    private fun rebuildShaders() {
        if (radius <= 0f) return
        glowPaint.shader = RadialGradient(
            cx, cy, radius * 1.16f,
            intArrayOf(playerAlpha(accent, 44), playerAlpha(accent, 14), Color.TRANSPARENT),
            floatArrayOf(0.65f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        vinylPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(Color.rgb(22, 25, 30), Color.rgb(16, 18, 22), Color.rgb(26, 29, 35), Color.rgb(13, 15, 18)),
            floatArrayOf(0.40f, 0.62f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun updateArtworkMatrix() {
        val bmp = artwork ?: return
        val shader = artworkShader ?: return
        if (labelRadius <= 0f || bmp.width <= 0 || bmp.height <= 0) return
        val diameter = labelRadius * 2f
        val scale = max(diameter / bmp.width.toFloat(), diameter / bmp.height.toFloat())
        val dx = cx - bmp.width * scale / 2f
        val dy = cy - bmp.height * scale / 2f
        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(dx, dy)
        shader.setLocalMatrix(shaderMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        // Ambient glow & outer turntable platter bezel
        canvas.drawCircle(cx, cy, radius * 1.15f, glowPaint)
        platterPaint.color = Color.argb(26, 255, 255, 255)
        canvas.drawCircle(cx, cy, radius * 1.032f, platterPaint)

        val save = canvas.save()
        canvas.rotate(rotationDegrees, cx, cy)

        // Main black vinyl body
        canvas.drawCircle(cx, cy, radius, vinylPaint)

        // Specular light sheen wedges to convey rotation even with symmetrical labels
        canvas.drawArc(vinylBounds, -38f, 48f, true, sheenPaint)
        canvas.drawArc(vinylBounds, 142f, 48f, true, sheenPaint)

        // Concentric vinyl grooves
        val strokeBase = max(1f, radius / 160f)
        groovePaint.strokeWidth = strokeBase
        val grooveFractions = floatArrayOf(0.51f, 0.57f, 0.63f, 0.69f, 0.75f, 0.81f, 0.87f, 0.93f)
        for (index in grooveFractions.indices) {
            groovePaint.color = Color.argb(if (index % 2 == 0) 28 else 16, 255, 255, 255)
            canvas.drawCircle(cx, cy, radius * grooveFractions[index], groovePaint)
        }

        // Outer vinyl bevel rim
        rimPaint.strokeWidth = strokeBase * 1.5f
        rimPaint.color = Color.argb(42, 255, 255, 255)
        canvas.drawCircle(cx, cy, radius * 0.985f, rimPaint)

        // Center album art label or accent vinyl label
        if (artworkShader != null) {
            labelBgPaint.color = Color.rgb(28, 31, 36)
            canvas.drawCircle(cx, cy, labelRadius, labelBgPaint)
            canvas.drawCircle(cx, cy, labelRadius, artworkPaint)
        } else {
            labelBgPaint.color = playerBlend(Color.rgb(30, 35, 42), accent, 0.36f)
            canvas.drawCircle(cx, cy, labelRadius, labelBgPaint)
            rimPaint.strokeWidth = strokeBase * 1.2f
            rimPaint.color = Color.argb(38, 255, 255, 255)
            canvas.drawCircle(cx, cy, labelRadius * 0.78f, rimPaint)
            val noteHalf = (labelRadius * 0.36f).toInt()
            noteDrawable.setBounds(
                cx.toInt() - noteHalf,
                cy.toInt() - noteHalf,
                cx.toInt() + noteHalf,
                cy.toInt() + noteHalf
            )
            noteDrawable.alpha = 210
            noteDrawable.draw(canvas)
        }

        // Label border ring
        rimPaint.strokeWidth = strokeBase * 1.8f
        rimPaint.color = Color.argb(95, 255, 255, 255)
        canvas.drawCircle(cx, cy, labelRadius, rimPaint)

        // Center spindle collar & hole
        spindlePaint.color = playerBlend(Color.rgb(205, 214, 218), accent, 0.25f)
        canvas.drawCircle(cx, cy, radius * 0.088f, spindlePaint)
        spindlePaint.color = Color.rgb(16, 18, 22)
        canvas.drawCircle(cx, cy, radius * 0.056f, spindlePaint)

        canvas.restoreToCount(save)

        if (playing && isAttachedToWindow && windowVisibility == VISIBLE) {
            val nowNs = System.nanoTime()
            if (lastFrameNs != 0L) {
                val dtSec = ((nowNs - lastFrameNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
                rotationDegrees = (rotationDegrees + dtSec * 18f) % 360f
            }
            lastFrameNs = nowNs
            postInvalidateOnAnimation()
        } else {
            lastFrameNs = 0L
        }
    }
}

/** 样式二：微圆角正方形唱片封面视图，未加载封面时展示音符占位卡。 */
internal class RoundedCoverView(context: Context, initialAccent: Int) : View(context) {
    private var accent = initialAccent
    private var artwork: Bitmap? = null
    private var artworkShader: BitmapShader? = null
    private val shaderMatrix = Matrix()

    private val coverRect = RectF()
    private val glowRect = RectF()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val artworkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val noteDrawable = SendspinIconDrawable(context, SendspinIcon.NOTE, Color.rgb(226, 236, 238))

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun setAccentColor(color: Int) {
        if (accent == color) return
        accent = color
        rebuildGlow()
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (artwork === bitmap) return
        artwork = bitmap
        artworkShader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        artworkPaint.shader = artworkShader
        updateArtworkMatrix()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val half = min(w, h) * 0.44f
        coverRect.set(cx - half, cy - half, cx + half, cy + half)
        val glowHalf = half * 1.12f
        glowRect.set(cx - glowHalf, cy - glowHalf, cx + glowHalf, cy + glowHalf)
        rebuildGlow()
        updateArtworkMatrix()
    }

    private fun rebuildGlow() {
        if (coverRect.width() <= 0f) return
        val cx = coverRect.centerX()
        val cy = coverRect.centerY()
        val radius = coverRect.width() * 0.78f
        glowPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(playerAlpha(accent, 95), playerAlpha(accent, 28), Color.TRANSPARENT),
            floatArrayOf(0.45f, 0.80f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun updateArtworkMatrix() {
        val bmp = artwork ?: return
        val shader = artworkShader ?: return
        val size = coverRect.width()
        if (size <= 0f || bmp.width <= 0 || bmp.height <= 0) return
        val scale = max(size / bmp.width.toFloat(), size / bmp.height.toFloat())
        val dx = coverRect.centerX() - bmp.width * scale / 2f
        val dy = coverRect.centerY() - bmp.height * scale / 2f
        shaderMatrix.reset()
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(dx, dy)
        shader.setLocalMatrix(shaderMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        if (coverRect.width() <= 0f) return
        val corner = min(context.playerDp(10f), coverRect.width() * 0.12f)
        canvas.drawRoundRect(glowRect, corner * 1.3f, corner * 1.3f, glowPaint)

        if (artworkShader != null) {
            cardBgPaint.color = Color.rgb(24, 28, 34)
            canvas.drawRoundRect(coverRect, corner, corner, cardBgPaint)
            canvas.drawRoundRect(coverRect, corner, corner, artworkPaint)
            borderPaint.color = Color.argb(64, 255, 255, 255)
        } else {
            cardBgPaint.color = Color.argb(190, 30, 36, 44)
            canvas.drawRoundRect(coverRect, corner, corner, cardBgPaint)
            val noteHalf = (coverRect.width() * 0.24f).toInt()
            val cx = coverRect.centerX().toInt()
            val cy = coverRect.centerY().toInt()
            noteDrawable.setBounds(cx - noteHalf, cy - noteHalf, cx + noteHalf, cy + noteHalf)
            noteDrawable.alpha = 205
            noteDrawable.draw(canvas)
            borderPaint.color = Color.argb(46, 255, 255, 255)
        }
        borderPaint.strokeWidth = max(1f, context.playerDp(1f))
        canvas.drawRoundRect(coverRect, corner, corner, borderPaint)
    }
}

/** 可点击、可遥控器聚焦的封面切换容器，兼容样式一（黑胶）与样式二（微圆角方图）。 */
internal class SendspinArtworkSwitcher(context: Context, initialAccent: Int) : FrameLayout(context) {
    val vinylRecord = VinylRecordView(context, initialAccent)
    val roundedCover = RoundedCoverView(context, initialAccent).apply { visibility = GONE }

    private var accent = initialAccent
    private var style = Prefs.SendspinPlayerStyle.VINYL
    private var playing = false
    private val focusRect = RectF()
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(vinylRecord, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(roundedCover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateContentDescription()
    }

    fun setStyle(newStyle: Prefs.SendspinPlayerStyle) {
        if (style == newStyle) return
        style = newStyle
        val isVinyl = style == Prefs.SendspinPlayerStyle.VINYL
        vinylRecord.visibility = if (isVinyl) VISIBLE else GONE
        roundedCover.visibility = if (isVinyl) GONE else VISIBLE
        vinylRecord.setPlaying(playing && isVinyl)
        updateContentDescription()
        invalidate()
    }

    fun setArtwork(bitmap: Bitmap?) {
        vinylRecord.setArtwork(bitmap)
        roundedCover.setArtwork(bitmap)
    }

    fun setAccentColor(color: Int) {
        accent = color
        vinylRecord.setAccentColor(color)
        roundedCover.setAccentColor(color)
        if (isFocused) invalidate()
    }

    fun setPlaying(isPlaying: Boolean) {
        playing = isPlaying
        vinylRecord.setPlaying(isPlaying && style == Prefs.SendspinPlayerStyle.VINYL)
    }

    private fun updateContentDescription() {
        contentDescription = if (style == Prefs.SendspinPlayerStyle.VINYL) {
            "黑胶唱片封面，点击切换为方形封面样式"
        } else {
            "方形唱片封面，点击切换为黑胶唱片样式"
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        animatePlayerFocus(gainFocus)
        invalidate()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!isFocused || width <= 0 || height <= 0) return
        val half = min(width, height) * 0.44f + context.playerDp(3f)
        val cx = width / 2f
        val cy = height / 2f
        focusPaint.strokeWidth = context.playerDp(2.2f)
        focusPaint.color = accent
        if (style == Prefs.SendspinPlayerStyle.VINYL) {
            canvas.drawCircle(cx, cy, half, focusPaint)
        } else {
            val corner = min(context.playerDp(12f), half * 2f * 0.14f)
            focusRect.set(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(focusRect, corner, corner, focusPaint)
        }
    }
}

/** Hardware-accelerated damped smooth scrolling LRC view with multi-line wrapping. */
internal class LyricsScrollView(context: Context, initialAccent: Int) : View(context) {
    private data class LyricItemLayout(
        val layout: StaticLayout,
        val topY: Float,
        val centerY: Float,
        val isActive: Boolean
    )

    private var accent = initialAccent
    private var lyrics: ParsedLyrics? = null
    private var loading = false
    private var playing = false
    private var blurredBackdropActive = false
    private var currentPositionMs = 0L

    private var currentScrollY = 0f
    private var targetScrollY = 0f
    private var hasScrollAnchor = false

    private var cachedLyrics: ParsedLyrics? = null
    private var cachedWidth = -1
    private var cachedActiveIdx = Int.MIN_VALUE
    private var cachedAccent = initialAccent
    private var cachedItems: List<LyricItemLayout> = emptyList()

    private val activeMeasurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.playerDp(22f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val inactiveMeasurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.playerDp(16.5f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val emptyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.playerDp(15f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val waveRect = RectF()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        setPadding(
            context.playerDp(12f).toInt(),
            context.playerDp(16f).toInt(),
            context.playerDp(12f).toInt(),
            context.playerDp(16f).toInt()
        )
    }

    fun setAccentColor(color: Int) {
        if (accent == color) return
        accent = color
        invalidate()
    }

    fun setPlaying(isPlaying: Boolean) {
        if (playing == isPlaying) return
        playing = isPlaying
        if (lyrics == null || lyrics?.lines.isNullOrEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    fun setLoading(isLoading: Boolean) {
        if (loading == isLoading) return
        loading = isLoading
        invalidate()
    }

    fun setBlurredBackdropActive(active: Boolean) {
        if (blurredBackdropActive == active) return
        blurredBackdropActive = active
        invalidate()
    }

    fun setLyrics(parsed: ParsedLyrics?) {
        lyrics = parsed
        currentScrollY = 0f
        targetScrollY = 0f
        hasScrollAnchor = false
        cachedLyrics = null
        cachedActiveIdx = Int.MIN_VALUE
        invalidate()
    }

    fun updatePosition(positionMs: Long) {
        val oldActive = activeLineIndex()
        currentPositionMs = positionMs
        val lrc = lyrics
        if (lrc != null && lrc.synced) {
            val newActive = activeLineIndex()
            if (newActive != oldActive || abs(targetScrollY - currentScrollY) > 0.5f) {
                postInvalidateOnAnimation()
            }
        }
    }

    private fun activeLineIndex(): Int {
        val lrc = lyrics ?: return -1
        if (lrc.lines.isEmpty()) return -1
        return if (lrc.synced) LrcParser.findActiveLineIndex(lrc.lines, currentPositionMs) else 0
    }

    private fun ensureLayouts(lrc: ParsedLyrics, availWidth: Int, activeIdx: Int) {
        if (cachedLyrics === lrc && cachedWidth == availWidth && cachedActiveIdx == activeIdx && cachedAccent == accent) {
            return
        }
        cachedLyrics = lrc
        cachedWidth = availWidth
        cachedActiveIdx = activeIdx
        cachedAccent = accent

        val items = ArrayList<LyricItemLayout>(lrc.lines.size)
        var y = 0f
        val itemGap = context.playerDp(20f)
        for (i in lrc.lines.indices) {
            val isActive = lrc.synced && i == activeIdx
            val text = lrc.lines[i].text
            val paint = if (isActive) activeMeasurePaint else inactiveMeasurePaint
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, availWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.16f)
                .setIncludePad(false)
                .build()
            val h = layout.height.toFloat()
            items.add(LyricItemLayout(layout, y, y + h / 2f, isActive))
            y += h + itemGap
        }
        cachedItems = items
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val lrc = lyrics
        if (lrc == null || lrc.lines.isEmpty()) {
            drawEmptyState(canvas, w, h)
            return
        }

        val availWidth = (width - paddingLeft - paddingRight).coerceAtLeast(context.playerDp(120f).toInt())
        val activeIdx = activeLineIndex()
        ensureLayouts(lrc, availWidth, activeIdx)
        if (cachedItems.isEmpty()) {
            drawEmptyState(canvas, w, h)
            return
        }

        val focusIdx = if (activeIdx >= 0) activeIdx.coerceAtMost(cachedItems.lastIndex) else 0
        targetScrollY = cachedItems[focusIdx].centerY
        if (!hasScrollAnchor) {
            currentScrollY = targetScrollY
            hasScrollAnchor = true
        } else {
            val diff = targetScrollY - currentScrollY
            if (abs(diff) > 0.5f) {
                currentScrollY += diff * 0.22f
                postInvalidateOnAnimation()
            } else {
                currentScrollY = targetScrollY
            }
        }

        val viewCenterY = h / 2f
        val padLeft = paddingLeft.toFloat()
        val activeColor = playerBlend(Color.WHITE, accent, 0.34f)
        val shadowColor = playerAlpha(accent, 185)
        val shadowRadius = context.playerDp(8f)

        for (item in cachedItems) {
            val drawTop = viewCenterY + item.topY - currentScrollY
            val drawBottom = drawTop + item.layout.height
            if (drawBottom < 0f || drawTop > h) continue

            val distFromCenter = abs((viewCenterY + item.centerY - currentScrollY) - viewCenterY)
            val alphaFactor = (1f - distFromCenter / (h * 0.50f)).coerceIn(0.14f, 1f)
            val paint = item.layout.paint

            if (item.isActive) {
                paint.color = if (blurredBackdropActive) Color.WHITE else activeColor
                paint.alpha = (alphaFactor * 255).toInt()
                val actShadowColor = if (blurredBackdropActive) Color.argb(170, 0, 0, 0) else shadowColor
                paint.setShadowLayer(shadowRadius, 0f, context.playerDp(1f), actShadowColor)
            } else {
                if (blurredBackdropActive) {
                    paint.setShadowLayer(context.playerDp(3f), 0f, context.playerDp(1f), Color.argb(125, 0, 0, 0))
                } else {
                    paint.clearShadowLayer()
                }
                paint.color = if (blurredBackdropActive) Color.rgb(238, 243, 245) else Color.rgb(228, 234, 236)
                paint.alpha = (alphaFactor * if (blurredBackdropActive) 185 else 165).toInt()
            }

            canvas.save()
            canvas.translate(padLeft, drawTop)
            item.layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun drawEmptyState(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f - context.playerDp(12f)
        val barWidth = context.playerDp(6f)
        val spacing = context.playerDp(13f)
        val barCorner = context.playerDp(3f)
        val animateBars = (loading || playing) && isAttachedToWindow && windowVisibility == VISIBLE
        val phaseBase = if (animateBars) System.currentTimeMillis() / 220.0 else 0.0

        wavePaint.color = playerAlpha(accent, if (animateBars) 220 else 135)
        for (i in -2..2) {
            val barH = if (animateBars) {
                context.playerDp(12f) + abs(sin(phaseBase + i * 0.85)).toFloat() * context.playerDp(22f)
            } else {
                context.playerDp(12f + (2 - abs(i)) * 4f)
            }
            val bx = cx + i * spacing - barWidth / 2f
            val by = cy - barH / 2f
            waveRect.set(bx, by, bx + barWidth, by + barH)
            canvas.drawRoundRect(waveRect, barCorner, barCorner, wavePaint)
        }

        emptyTextPaint.color = Color.rgb(166, 182, 185)
        emptyTextPaint.alpha = 205
        val label = if (loading) "正在检索歌词…" else "暂无歌词"
        canvas.drawText(label, cx, cy + context.playerDp(42f), emptyTextPaint)

        if (animateBars) {
            postInvalidateOnAnimation()
        }
    }
}
