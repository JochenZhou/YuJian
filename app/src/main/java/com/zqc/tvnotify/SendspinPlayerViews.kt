package com.zqc.tvnotify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.max
import kotlin.math.min

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

/** A square cover beside a bounded reading column, even on large TV windows. */
internal class SendspinStageLayout(context: Context, private val cover: View, private val details: View) : ViewGroup(context) {
    private var coverSize = 0
    private var contentWidth = 0
    private var gap = 0

    init {
        clipChildren = false
        clipToPadding = false
        addView(cover)
        addView(details)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val density = resources.displayMetrics.density
        contentWidth = min(width, context.playerDp(1260f).toInt())
        val narrow = contentWidth / density < 700f
        gap = context.playerDp(if (narrow) 24f else 48f).toInt().coerceAtMost(contentWidth / 10)
        coverSize = min(
            min((height - context.playerDp(if (narrow) 4f else 20f)).toInt(), (contentWidth * if (narrow) .39f else .445f).toInt()),
            context.playerDp(540f).toInt()
        ).coerceAtLeast(0)
        cover.measure(MeasureSpec.makeMeasureSpec(coverSize, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(coverSize, MeasureSpec.EXACTLY))
        details.measure(MeasureSpec.makeMeasureSpec((contentWidth - coverSize - gap).coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val start = (width - contentWidth) / 2
        val coverTop = (height - coverSize) / 2
        cover.layout(start, coverTop, start + coverSize, coverTop + coverSize)
        details.layout(start + coverSize + gap, 0, start + contentWidth, height)
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

internal class SendspinArtworkPlaceholder(context: Context, private val accent: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val note = SendspinIconDrawable(context, SendspinIcon.NOTE, playerBlend(Color.rgb(143, 154, 163), accent, .2f))

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val size = min(bounds.width(), bounds.height()).toFloat()
        paint.shader = LinearGradient(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), Color.rgb(35, 40, 46), Color.rgb(22, 25, 30), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, size / 500f)
        for (ring in 0..4) {
            paint.color = Color.argb(10 + ring * 2, 210, 222, 227)
            canvas.drawCircle(cx, cy, size * (.22f + ring * .044f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = playerAlpha(accent, 11)
        canvas.drawCircle(cx, cy, size * .125f, paint)
        val half = (size * .072f).toInt()
        note.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        note.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { }
    override fun setColorFilter(colorFilter: ColorFilter?) { }
    @Suppress("DEPRECATION") override fun getOpacity(): Int = PixelFormat.OPAQUE
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
