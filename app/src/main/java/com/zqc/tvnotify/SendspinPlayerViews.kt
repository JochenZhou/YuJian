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

/** Low contrast gradients provide an artwork tint without any blur API or render loop. */
internal class SendspinAmbientDrawable(private val base: Int, initialColor: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var ambient = initialColor
    private var baseGradient: Shader? = null
    private var artGradient: Shader? = null

    fun setAmbientColor(color: Int) {
        if (ambient == color) return
        ambient = color
        updateShaders()
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) { updateShaders() }

    private fun updateShaders() {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        baseGradient = LinearGradient(0f, 0f, width, height, playerBlend(base, Color.rgb(27, 30, 35), .35f), playerBlend(base, Color.BLACK, .24f), Shader.TileMode.CLAMP)
        artGradient = RadialGradient(width * .24f, height * .47f, max(width * .62f, height), intArrayOf(playerAlpha(ambient, 65), playerAlpha(ambient, 22), playerAlpha(ambient, 0)), floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        paint.shader = baseGradient
        canvas.drawRect(bounds, paint)
        paint.shader = artGradient
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
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
    private val quiet: Boolean = false
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
            focused -> playerAlpha(accent, 20)
            quiet -> Color.TRANSPARENT
            else -> Color.argb(13, 255, 255, 255)
        }
        if (!enabled) paint.color = playerAlpha(paint.color, if (primary) 50 else 5)
        canvas.drawRoundRect(rect, radiusDp * density, radiusDp * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * if (focused) 2f else 1f
        paint.color = when {
            focused -> accent
            quiet -> Color.TRANSPARENT
            primary -> Color.argb(if (enabled) 65 else 10, 255, 255, 255)
            else -> Color.argb(if (enabled) 22 else 8, 255, 255, 255)
        }
        canvas.drawRoundRect(rect, radiusDp * density, radiusDp * density, paint)
    }

    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radiusDp * density) }
    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal class SendspinIconButton(context: Context, icon: SendspinIcon, accent: Int, primary: Boolean = false) : ImageButton(context) {
    private val symbol = SendspinIconDrawable(context, icon, if (primary) Color.rgb(18, 22, 26) else Color.rgb(234, 239, 240), if (primary) 32f else 24f)
    var icon: SendspinIcon
        get() = symbol.icon
        set(value) { if (symbol.icon != value) { symbol.icon = value; symbol.invalidateSelf() } }

    init {
        backgroundTintList = null
        background = SendspinControlDrawable(resources.displayMetrics.density, accent, primary)
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
internal class SendspinSeekBar(context: Context, private val accent: Int) : SeekBar(context) {
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
        paint.color = Color.argb(if (isEnabled) 44 else 22, 255, 255, 255)
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
                paint.color = activeColor
                paint.alpha = (alphaFactor * 255).toInt()
                paint.setShadowLayer(shadowRadius, 0f, context.playerDp(1f), shadowColor)
            } else {
                paint.clearShadowLayer()
                paint.color = Color.rgb(228, 234, 236)
                paint.alpha = (alphaFactor * 165).toInt()
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
        val label = if (loading) "正在检索歌词…" else "纯音乐 · 暂无歌词"
        canvas.drawText(label, cx, cy + context.playerDp(42f), emptyTextPaint)

        if (animateBars) {
            postInvalidateOnAnimation()
        }
    }
}
