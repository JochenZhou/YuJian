package com.zqc.tvnotify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import java.util.WeakHashMap
import kotlin.math.min

/**
 * Shared low-overhead GPU focus motion primitives for TV D-pad controls.
 * All animations run strictly on scale / alpha / translation properties (no layout passes),
 * keeping 60fps responsiveness even on low-power Android 6.0 TV SoCs.
 */
internal object TvFocusMotion {
    val GAIN_INTERPOLATOR = OvershootInterpolator(1.25f)
    val LOSS_INTERPOLATOR = DecelerateInterpolator(1.6f)
    val GLIDE_INTERPOLATOR = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
    val TAB_INTERPOLATOR = DecelerateInterpolator(1.8f)
    val PULSE_PRESS_INTERPOLATOR = DecelerateInterpolator(1.5f)
    val PULSE_REBOUND_INTERPOLATOR = OvershootInterpolator(1.8f)

    private const val FOCUS_GAIN_DURATION_MS = 165L
    private const val FOCUS_LOSS_DURATION_MS = 115L
    private const val PULSE_PRESS_DURATION_MS = 55L
    private const val PULSE_REBOUND_DURATION_MS = 125L

    fun animateFocusScale(
        view: View,
        hasFocus: Boolean,
        focusedScale: Float = 1.04f,
        elevationLiftDp: Float = 4f
    ) {
        val targetScale = if (hasFocus) focusedScale else 1f
        val targetZ = if (hasFocus && elevationLiftDp > 0f) {
            elevationLiftDp * view.resources.displayMetrics.density
        } else {
            0f
        }
        view.animate().cancel()
        if (!view.isAttachedToWindow) {
            view.scaleX = targetScale
            view.scaleY = targetScale
            view.translationZ = targetZ
            return
        }
        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationZ(targetZ)
            .setDuration(if (hasFocus) FOCUS_GAIN_DURATION_MS else FOCUS_LOSS_DURATION_MS)
            .setInterpolator(if (hasFocus) GAIN_INTERPOLATOR else LOSS_INTERPOLATOR)
            .start()
    }

    fun animateClickPulse(view: View, restingFocusedScale: Float = 1.04f) {
        if (!view.isAttachedToWindow) return
        val dipScale = restingFocusedScale * 0.955f
        view.animate().cancel()
        view.animate()
            .scaleX(dipScale)
            .scaleY(dipScale)
            .setDuration(PULSE_PRESS_DURATION_MS)
            .setInterpolator(PULSE_PRESS_INTERPOLATOR)
            .withEndAction {
                if (!view.isAttachedToWindow) return@withEndAction
                val targetScale = if (view.hasFocus()) restingFocusedScale else 1f
                view.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(PULSE_REBOUND_DURATION_MS)
                    .setInterpolator(PULSE_REBOUND_INTERPOLATOR)
                    .start()
            }
            .start()
    }
}

/**
 * Target geometry metadata used by [TvFocusGlowOverlay] when gliding between buttons/pills.
 * Use [circular] = true for round transport controls, or supply the button's [cornerRadiusPx].
 */
internal data class TvFocusTargetSpec(
    val cornerRadiusPx: Float,
    val focusedScale: Float = 1.04f,
    val circular: Boolean = false
)

/**
 * Single lightweight GPU-drawn gliding focus halo layered inside an activity's root container.
 * Eliminates harsh focus hopping across D-pad buttons while drawing only one stroked ring per frame.
 */
internal class TvFocusGlowOverlay(
    context: Context,
    private val hostContainer: ViewGroup,
    initialAccent: Int,
    private var pixelSharp: Boolean = false
) : View(context) {

    private val density = resources.displayMetrics.density
    private val targetSpecs = WeakHashMap<View, TvFocusTargetSpec>()
    private val tempBounds = Rect()

    private val currentRect = RectF()
    private val startRect = RectF()
    private val targetRect = RectF()
    private var currentRadius = 0f
    private var startRadius = 0f
    private var targetRadius = 0f
    private var hasActiveGeometry = false
    private var haloAlpha = 0f
    private var accentColor = initialAccent

    private var currentFocusedView: View? = null
    private var pendingLayoutTarget: View? = null
    private var glideAnimator: ValueAnimator? = null
    private var alphaAnimator: ValueAnimator? = null
    private var listenersRegistered = false

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        onGlobalFocusChanged(newFocus)
    }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        val active = currentFocusedView
        if (active != null && active.hasFocus() && targetSpecs.containsKey(active)) {
            syncToTarget(active, animate = false)
        }
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        refreshPaintColors()
    }

    fun registerTarget(view: View, spec: TvFocusTargetSpec) {
        targetSpecs[view] = spec
        if (view.hasFocus()) {
            currentFocusedView = view
            syncToTarget(view, animate = false)
        }
    }

    fun updateAccent(newAccent: Int, sharpCorners: Boolean = pixelSharp) {
        if (accentColor == newAccent && pixelSharp == sharpCorners) return
        accentColor = newAccent
        pixelSharp = sharpCorners
        refreshPaintColors()
        if (hasActiveGeometry && haloAlpha > 0.01f) {
            invalidate()
        }
    }

    fun syncToCurrentFocus(animate: Boolean = false) {
        syncToTarget(currentFocusedView ?: hostContainer.findFocus(), animate = animate)
    }

    fun cancelAnimations() {
        glideAnimator?.cancel()
        glideAnimator = null
        alphaAnimator?.cancel()
        alphaAnimator = null
        pendingLayoutTarget = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!listenersRegistered) {
            hostContainer.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            hostContainer.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            listenersRegistered = true
        }
        syncToCurrentFocus(animate = false)
    }

    override fun onDetachedFromWindow() {
        cancelAnimations()
        if (listenersRegistered && hostContainer.viewTreeObserver.isAlive) {
            hostContainer.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            hostContainer.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        }
        listenersRegistered = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (!hasActiveGeometry || haloAlpha <= 0.01f || currentRect.width() <= 0f || currentRect.height() <= 0f) {
            return
        }
        val aScale = haloAlpha.coerceIn(0f, 1f)
        outerPaint.alpha = (96f * aScale).toInt().coerceIn(0, 255)
        innerPaint.alpha = (212f * aScale).toInt().coerceIn(0, 255)
        if (pixelSharp || currentRadius <= 0.5f) {
            canvas.drawRect(currentRect, outerPaint)
            canvas.drawRect(currentRect, innerPaint)
        } else {
            canvas.drawRoundRect(currentRect, currentRadius, currentRadius, outerPaint)
            canvas.drawRoundRect(currentRect, currentRadius, currentRadius, innerPaint)
        }
    }

    private fun refreshPaintColors() {
        outerPaint.color = accentColor
        outerPaint.isAntiAlias = !pixelSharp
        innerPaint.color = blendHighlight(accentColor)
        innerPaint.isAntiAlias = !pixelSharp
    }

    private fun blendHighlight(color: Int): Int {
        val mix = 0.62f
        val r = (Color.red(color) + (255 - Color.red(color)) * mix).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * mix).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * mix).toInt()
        return Color.rgb(r, g, b)
    }

    private fun onGlobalFocusChanged(newFocus: View?) {
        currentFocusedView = newFocus
        syncToTarget(newFocus, animate = isAttachedToWindow)
    }

    private fun syncToTarget(target: View?, animate: Boolean) {
        val spec = if (target != null && target.isShown) targetSpecs[target] else null
        if (target == null || spec == null) {
            pendingLayoutTarget = null
            fadeHalo(visible = false, animate = animate && isAttachedToWindow)
            return
        }
        if (target.width <= 0 || target.height <= 0) {
            pendingLayoutTarget = target
            target.post {
                if (pendingLayoutTarget === target && target.hasFocus()) {
                    pendingLayoutTarget = null
                    syncToTarget(target, animate = false)
                }
            }
            return
        }
        pendingLayoutTarget = null

        if (!computeExpandedBounds(target, spec, targetRect)) {
            fadeHalo(visible = false, animate = false)
            return
        }
        targetRadius = resolveCornerRadius(spec, targetRect)

        val shouldGlide = animate && isAttachedToWindow && hasActiveGeometry && haloAlpha > 0.1f
        if (!shouldGlide) {
            glideAnimator?.cancel()
            glideAnimator = null
            currentRect.set(targetRect)
            currentRadius = targetRadius
            hasActiveGeometry = true
            fadeHalo(visible = true, animate = animate && isAttachedToWindow)
            invalidate()
            return
        }

        startRect.set(currentRect)
        startRadius = currentRadius
        fadeHalo(visible = true, animate = true)
        glideAnimator?.cancel()
        glideAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 165L
            interpolator = TvFocusMotion.GLIDE_INTERPOLATOR
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                currentRect.set(
                    lerp(startRect.left, targetRect.left, f),
                    lerp(startRect.top, targetRect.top, f),
                    lerp(startRect.right, targetRect.right, f),
                    lerp(startRect.bottom, targetRect.bottom, f)
                )
                currentRadius = lerp(startRadius, targetRadius, f)
                invalidate()
            }
            start()
        }
    }

    private fun computeExpandedBounds(target: View, spec: TvFocusTargetSpec, out: RectF): Boolean {
        tempBounds.set(0, 0, target.width, target.height)
        try {
            hostContainer.offsetDescendantRectToMyCoords(target, tempBounds)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val cx = tempBounds.exactCenterX()
        val cy = tempBounds.exactCenterY()
        val pad = 1.8f * density
        val halfW = (tempBounds.width() * spec.focusedScale) / 2f + pad
        val halfH = (tempBounds.height() * spec.focusedScale) / 2f + pad
        out.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        return true
    }

    private fun resolveCornerRadius(spec: TvFocusTargetSpec, rect: RectF): Float {
        if (pixelSharp) return 0f
        if (spec.circular) return min(rect.width(), rect.height()) / 2f
        return if (spec.cornerRadiusPx <= 0f) 0f else spec.cornerRadiusPx + 2.2f * density
    }

    private fun fadeHalo(visible: Boolean, animate: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        if (!animate) {
            alphaAnimator?.cancel()
            alphaAnimator = null
            haloAlpha = targetAlpha
            invalidate()
            return
        }
        if (kotlin.math.abs(haloAlpha - targetAlpha) < 0.01f) return
        val fromAlpha = haloAlpha
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(fromAlpha, targetAlpha).apply {
            duration = if (visible) 130L else 100L
            interpolator = TvFocusMotion.LOSS_INTERPOLATOR
            addUpdateListener { anim ->
                haloAlpha = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
