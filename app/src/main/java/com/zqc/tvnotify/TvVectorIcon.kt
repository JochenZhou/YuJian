package com.zqc.tvnotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * 纯 Android Canvas 绘制的电视端矢量图标集，彻底替代 Android 6.0 电视上会缺字/显示方框的 Emoji。
 * 所有图形均基于 24×24 归一化坐标，支持任意分辨率清晰缩放与主题色动态染色。
 */
internal enum class TvIcon {
    // 9 大主题专属图标
    THEME_XIUXIAN,
    THEME_DEFAULT,
    THEME_PALACE,
    THEME_CYBERPUNK,
    THEME_WUKONG,
    THEME_MATRIX,
    THEME_INK_LIGHT,
    THEME_GAMER,
    THEME_MINECRAFT,

    // 主界面与播放页功能图标
    MUSIC_DISC,
    MUSIC_NOTE,
    WLED_SPARK,
    STATUS_DOT_SOLID,
    STATUS_DOT_RING,
    NETWORK,
    SHIELD,
    WARNING,
    ERROR_X,
    HOME_HA,
    PHONE,
    QR_CODE,
    HISTORY,
    PALETTE,
    BELL,
    IMAGE,
    VIDEO,
    SPEAKER,
    MUTE,
    POSITION,
    COPY,
    REFRESH,
    DOWNLOAD,
    INFO,
    ROCKET,
    CHECK,
    SWITCH_ARROWS,
    PLAY_TRIANGLE
}

internal fun Theme.vectorIcon(): TvIcon = when (this) {
    Theme.XIUXIAN -> TvIcon.THEME_XIUXIAN
    Theme.DEFAULT -> TvIcon.THEME_DEFAULT
    Theme.PALACE -> TvIcon.THEME_PALACE
    Theme.CYBERPUNK -> TvIcon.THEME_CYBERPUNK
    Theme.WUKONG -> TvIcon.THEME_WUKONG
    Theme.MATRIX -> TvIcon.THEME_MATRIX
    Theme.INK_LIGHT -> TvIcon.THEME_INK_LIGHT
    Theme.GAMER -> TvIcon.THEME_GAMER
    Theme.MINECRAFT -> TvIcon.THEME_MINECRAFT
}

internal class TvVectorIconDrawable(
    context: Context,
    private val icon: TvIcon,
    initialColor: Int,
    sizeDp: Float = 18f,
    private val strokeDp: Float = 1.85f
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val intrinsicPx = (sizeDp * density + 0.5f).toInt().coerceAtLeast(1)
    private var iconColor = initialColor

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = initialColor
        strokeWidth = strokeDp * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = initialColor
    }
    private val path = Path()
    private val rect = RectF()

    init {
        setBounds(0, 0, intrinsicPx, intrinsicPx)
    }

    fun setIconColor(color: Int) {
        if (iconColor == color) return
        iconColor = color
        strokePaint.color = color
        fillPaint.color = color
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = intrinsicPx
    override fun getIntrinsicHeight(): Int = intrinsicPx

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val size = min(w, h)
        val ox = b.left + (w - size) / 2f
        val oy = b.top + (h - size) / 2f
        fun x(v: Float) = ox + v / 24f * size
        fun y(v: Float) = oy + v / 24f * size
        fun r(v: Float) = v / 24f * size

        strokePaint.strokeWidth = (strokeDp * (size / (18f * density))).coerceAtLeast(1.2f * density)
        path.reset()

        when (icon) {
            TvIcon.THEME_XIUXIAN -> {
                // 灵玉菱晶
                path.moveTo(x(12f), y(2.5f))
                path.lineTo(x(19.5f), y(12f))
                path.lineTo(x(12f), y(21.5f))
                path.lineTo(x(4.5f), y(12f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(12f), y(2.5f), x(12f), y(21.5f), strokePaint)
                canvas.drawCircle(x(12f), y(12f), r(2.2f), fillPaint)
            }

            TvIcon.THEME_DEFAULT -> {
                // 极简同心光环
                canvas.drawCircle(x(12f), y(12f), r(8.5f), strokePaint)
                canvas.drawCircle(x(12f), y(12f), r(3.4f), fillPaint)
            }

            TvIcon.THEME_PALACE -> {
                // 宫廷灯笼
                canvas.drawLine(x(8.5f), y(4f), x(15.5f), y(4f), strokePaint)
                rect.set(x(6f), y(6f), x(18f), y(17.5f))
                canvas.drawRoundRect(rect, r(5f), r(5f), strokePaint)
                canvas.drawLine(x(12f), y(6f), x(12f), y(17.5f), strokePaint)
                canvas.drawLine(x(9.5f), y(19.5f), x(14.5f), y(19.5f), strokePaint)
                canvas.drawLine(x(12f), y(17.5f), x(12f), y(22f), strokePaint)
            }

            TvIcon.THEME_CYBERPUNK -> {
                // 赛博闪电
                path.moveTo(x(13.5f), y(2.5f))
                path.lineTo(x(5.5f), y(13f))
                path.lineTo(x(11.5f), y(13f))
                path.lineTo(x(10.5f), y(21.5f))
                path.lineTo(x(18.5f), y(11f))
                path.lineTo(x(12.5f), y(11f))
                path.close()
                canvas.drawPath(path, fillPaint)
            }

            TvIcon.THEME_WUKONG -> {
                // 紧箍金环与双卷纹
                rect.set(x(4f), y(7.5f), x(20f), y(18.5f))
                canvas.drawArc(rect, 195f, 150f, false, strokePaint)
                canvas.drawCircle(x(8f), y(12f), r(2.2f), strokePaint)
                canvas.drawCircle(x(16f), y(12f), r(2.2f), strokePaint)
                canvas.drawLine(x(10.2f), y(12f), x(13.8f), y(12f), strokePaint)
            }

            TvIcon.THEME_MATRIX -> {
                // 终端 >_
                rect.set(x(3f), y(4.5f), x(21f), y(19.5f))
                canvas.drawRoundRect(rect, r(2f), r(2f), strokePaint)
                path.moveTo(x(7f), y(9.5f))
                path.lineTo(x(10.5f), y(12f))
                path.lineTo(x(7f), y(14.5f))
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(12.5f), y(14.5f), x(17f), y(14.5f), strokePaint)
            }

            TvIcon.THEME_INK_LIGHT -> {
                // 江南清竹叶
                path.moveTo(x(5f), y(19f))
                path.quadTo(x(6f), y(6f), x(19f), y(5f))
                path.quadTo(x(18f), y(18f), x(5f), y(19f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(5f), y(19f), x(15.5f), y(8.5f), strokePaint)
            }

            TvIcon.THEME_GAMER -> {
                // 奖杯
                path.moveTo(x(7f), y(4f))
                path.lineTo(x(17f), y(4f))
                path.lineTo(x(16f), y(11.5f))
                path.quadTo(x(12f), y(15.5f), x(8f), y(11.5f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(12f), y(14f), x(12f), y(19f), strokePaint)
                canvas.drawLine(x(8.5f), y(19.5f), x(15.5f), y(19.5f), strokePaint)
                canvas.drawArc(RectF(x(3.5f), y(5.5f), x(7.5f), y(10.5f)), 90f, 180f, false, strokePaint)
                canvas.drawArc(RectF(x(16.5f), y(5.5f), x(20.5f), y(10.5f)), -90f, 180f, false, strokePaint)
            }

            TvIcon.THEME_MINECRAFT -> {
                // 直角 3D 像素方块
                path.moveTo(x(12f), y(3f))
                path.lineTo(x(20f), y(7.5f))
                path.lineTo(x(20f), y(16.5f))
                path.lineTo(x(12f), y(21f))
                path.lineTo(x(4f), y(16.5f))
                path.lineTo(x(4f), y(7.5f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(12f), y(12f), x(12f), y(21f), strokePaint)
                canvas.drawLine(x(12f), y(12f), x(20f), y(7.5f), strokePaint)
                canvas.drawLine(x(12f), y(12f), x(4f), y(7.5f), strokePaint)
            }

            TvIcon.MUSIC_DISC -> {
                // 大号唱片与音符
                canvas.drawCircle(x(12f), y(12f), r(9.5f), strokePaint)
                canvas.drawCircle(x(12f), y(12f), r(5.8f), strokePaint)
                canvas.drawCircle(x(12f), y(12f), r(2.2f), fillPaint)
            }

            TvIcon.MUSIC_NOTE -> {
                // 双音符
                canvas.drawLine(x(9.5f), y(16.5f), x(9.5f), y(6f), strokePaint)
                canvas.drawLine(x(18f), y(14.5f), x(18f), y(4.5f), strokePaint)
                canvas.drawLine(x(9.5f), y(6f), x(18f), y(4.5f), strokePaint)
                canvas.drawCircle(x(7.2f), y(17.2f), r(2.5f), fillPaint)
                canvas.drawCircle(x(15.7f), y(15.2f), r(2.5f), fillPaint)
            }

            TvIcon.WLED_SPARK -> {
                // 律动频谱灯条
                canvas.drawLine(x(5f), y(14f), x(5f), y(18f), strokePaint)
                canvas.drawLine(x(9.5f), y(9f), x(9.5f), y(18f), strokePaint)
                canvas.drawLine(x(14.5f), y(5.5f), x(14.5f), y(18f), strokePaint)
                canvas.drawLine(x(19f), y(11f), x(19f), y(18f), strokePaint)
            }

            TvIcon.STATUS_DOT_SOLID -> {
                canvas.drawCircle(x(12f), y(12f), r(5.5f), fillPaint)
            }

            TvIcon.STATUS_DOT_RING -> {
                canvas.drawCircle(x(12f), y(12f), r(5.5f), strokePaint)
            }

            TvIcon.NETWORK -> {
                // 局域网地球
                canvas.drawCircle(x(12f), y(12f), r(8.8f), strokePaint)
                canvas.drawLine(x(3.5f), y(12f), x(20.5f), y(12f), strokePaint)
                rect.set(x(8f), y(3.2f), x(16f), y(20.8f))
                canvas.drawOval(rect, strokePaint)
            }

            TvIcon.SHIELD -> {
                // 悬浮窗权限盾牌
                path.moveTo(x(12f), y(3f))
                path.lineTo(x(19f), y(6f))
                path.lineTo(x(19f), y(12f))
                path.quadTo(x(19f), y(18f), x(12f), y(21f))
                path.quadTo(x(5f), y(18f), x(5f), y(12f))
                path.lineTo(x(5f), y(6f))
                path.close()
                canvas.drawPath(path, strokePaint)
            }

            TvIcon.WARNING -> {
                // 警告三角
                path.moveTo(x(12f), y(3.5f))
                path.lineTo(x(20.5f), y(19.5f))
                path.lineTo(x(3.5f), y(19.5f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(12f), y(9.5f), x(12f), y(14f), strokePaint)
                canvas.drawCircle(x(12f), y(17f), r(1.1f), fillPaint)
            }

            TvIcon.ERROR_X -> {
                canvas.drawCircle(x(12f), y(12f), r(8.5f), strokePaint)
                canvas.drawLine(x(8.5f), y(8.5f), x(15.5f), y(15.5f), strokePaint)
                canvas.drawLine(x(15.5f), y(8.5f), x(8.5f), y(15.5f), strokePaint)
            }

            TvIcon.HOME_HA -> {
                // 智能家居小屋
                path.moveTo(x(3.5f), y(11.5f))
                path.lineTo(x(12f), y(4f))
                path.lineTo(x(20.5f), y(11.5f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(x(6f), y(10f))
                path.lineTo(x(6f), y(20f))
                path.lineTo(x(18f), y(20f))
                path.lineTo(x(18f), y(10f))
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(x(12f), y(14.5f), r(1.6f), fillPaint)
            }

            TvIcon.PHONE -> {
                // 手机轮廓
                rect.set(x(6.5f), y(3f), x(17.5f), y(21f))
                canvas.drawRoundRect(rect, r(2.2f), r(2.2f), strokePaint)
                canvas.drawCircle(x(12f), y(17.8f), r(1.1f), fillPaint)
            }

            TvIcon.QR_CODE -> {
                // 二维码三定位角
                rect.set(x(3.5f), y(3.5f), x(9.5f), y(9.5f))
                canvas.drawRect(rect, strokePaint)
                rect.set(x(14.5f), y(3.5f), x(20.5f), y(9.5f))
                canvas.drawRect(rect, strokePaint)
                rect.set(x(3.5f), y(14.5f), x(9.5f), y(20.5f))
                canvas.drawRect(rect, strokePaint)
                canvas.drawRect(x(14.5f), y(14.5f), x(17.2f), y(17.2f), fillPaint)
                canvas.drawRect(x(18.2f), y(18.2f), x(20.8f), y(20.8f), fillPaint)
            }

            TvIcon.HISTORY -> {
                // 历史时钟
                canvas.drawCircle(x(12f), y(12f), r(8.5f), strokePaint)
                canvas.drawLine(x(12f), y(7.5f), x(12f), y(12.2f), strokePaint)
                canvas.drawLine(x(12f), y(12.2f), x(15.5f), y(14.5f), strokePaint)
            }

            TvIcon.PALETTE -> {
                // 主题色板
                canvas.drawCircle(x(12f), y(12f), r(8.5f), strokePaint)
                canvas.drawCircle(x(9f), y(9.5f), r(1.4f), fillPaint)
                canvas.drawCircle(x(15f), y(9.5f), r(1.4f), fillPaint)
                canvas.drawCircle(x(8.5f), y(14.5f), r(1.4f), fillPaint)
                canvas.drawCircle(x(14.5f), y(15f), r(1.8f), fillPaint)
            }

            TvIcon.BELL -> {
                // 通知铃铛
                path.moveTo(x(6f), y(16f))
                path.lineTo(x(18f), y(16f))
                path.lineTo(x(16.8f), y(10.5f))
                path.quadTo(x(16.5f), y(5.5f), x(12f), y(5.5f))
                path.quadTo(x(7.5f), y(5.5f), x(7.2f), y(10.5f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawCircle(x(12f), y(19f), r(1.5f), fillPaint)
            }

            TvIcon.IMAGE -> {
                rect.set(x(3.5f), y(5f), x(20.5f), y(19f))
                canvas.drawRoundRect(rect, r(2f), r(2f), strokePaint)
                canvas.drawCircle(x(8.5f), y(9.5f), r(1.5f), fillPaint)
                path.moveTo(x(5.5f), y(17.5f))
                path.lineTo(x(11f), y(12.5f))
                path.lineTo(x(14.5f), y(15.5f))
                path.lineTo(x(17f), y(13.5f))
                path.lineTo(x(19.5f), y(17.5f))
                canvas.drawPath(path, strokePaint)
            }

            TvIcon.VIDEO -> {
                rect.set(x(3.5f), y(5.5f), x(20.5f), y(18.5f))
                canvas.drawRoundRect(rect, r(2.2f), r(2.2f), strokePaint)
                path.moveTo(x(10f), y(9f))
                path.lineTo(x(15.8f), y(12f))
                path.lineTo(x(10f), y(15f))
                path.close()
                canvas.drawPath(path, fillPaint)
            }

            TvIcon.SPEAKER -> {
                path.moveTo(x(4f), y(9.5f))
                path.lineTo(x(8f), y(9.5f))
                path.lineTo(x(13f), y(5.5f))
                path.lineTo(x(13f), y(18.5f))
                path.lineTo(x(8f), y(14.5f))
                path.lineTo(x(4f), y(14.5f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawArc(RectF(x(13.5f), y(8.5f), x(18f), y(15.5f)), -55f, 110f, false, strokePaint)
                canvas.drawArc(RectF(x(15f), y(6f), x(21f), y(18f)), -55f, 110f, false, strokePaint)
            }

            TvIcon.MUTE -> {
                path.moveTo(x(4f), y(9.5f))
                path.lineTo(x(8f), y(9.5f))
                path.lineTo(x(13f), y(5.5f))
                path.lineTo(x(13f), y(18.5f))
                path.lineTo(x(8f), y(14.5f))
                path.lineTo(x(4f), y(14.5f))
                path.close()
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(16f), y(9.5f), x(20.5f), y(14.5f), strokePaint)
                canvas.drawLine(x(20.5f), y(9.5f), x(16f), y(14.5f), strokePaint)
            }

            TvIcon.POSITION -> {
                rect.set(x(3.5f), y(5f), x(20.5f), y(19f))
                canvas.drawRoundRect(rect, r(2f), r(2f), strokePaint)
                canvas.drawRect(x(12.5f), y(7.5f), x(18f), y(11.5f), fillPaint)
            }

            TvIcon.COPY -> {
                rect.set(x(8.5f), y(4f), x(19.5f), y(16f))
                canvas.drawRoundRect(rect, r(1.8f), r(1.8f), strokePaint)
                path.moveTo(x(15.5f), y(16f))
                path.lineTo(x(15.5f), y(20f))
                path.lineTo(x(4.5f), y(20f))
                path.lineTo(x(4.5f), y(8f))
                path.lineTo(x(8.5f), y(8f))
                canvas.drawPath(path, strokePaint)
            }

            TvIcon.REFRESH -> {
                canvas.drawArc(RectF(x(4.5f), y(4.5f), x(19.5f), y(19.5f)), 35f, 290f, false, strokePaint)
                path.moveTo(x(19.5f), y(4.5f))
                path.lineTo(x(19.5f), y(9.5f))
                path.lineTo(x(14.8f), y(9.5f))
                canvas.drawPath(path, strokePaint)
            }

            TvIcon.DOWNLOAD -> {
                canvas.drawLine(x(12f), y(4f), x(12f), y(15.5f), strokePaint)
                path.moveTo(x(7.5f), y(11.5f))
                path.lineTo(x(12f), y(16f))
                path.lineTo(x(16.5f), y(11.5f))
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(5f), y(19.5f), x(19f), y(19.5f), strokePaint)
            }

            TvIcon.INFO -> {
                canvas.drawCircle(x(12f), y(12f), r(8.5f), strokePaint)
                canvas.drawCircle(x(12f), y(8f), r(1.2f), fillPaint)
                canvas.drawLine(x(12f), y(11.5f), x(12f), y(16.5f), strokePaint)
            }

            TvIcon.ROCKET -> {
                // 直达播放快进箭头
                path.moveTo(x(5f), y(12f))
                path.lineTo(x(18.5f), y(12f))
                canvas.drawPath(path, strokePaint)
                path.reset()
                path.moveTo(x(13.5f), y(6.5f))
                path.lineTo(x(19f), y(12f))
                path.lineTo(x(13.5f), y(17.5f))
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(5f), y(6.5f), x(5f), y(17.5f), strokePaint)
            }

            TvIcon.CHECK -> {
                path.moveTo(x(5f), y(12.5f))
                path.lineTo(x(10f), y(17.5f))
                path.lineTo(x(19.5f), y(7f))
                canvas.drawPath(path, strokePaint)
            }

            TvIcon.SWITCH_ARROWS -> {
                canvas.drawLine(x(4.5f), y(8.5f), x(19.5f), y(8.5f), strokePaint)
                path.moveTo(x(15.5f), y(5f))
                path.lineTo(x(19.5f), y(8.5f))
                path.lineTo(x(15.5f), y(12f))
                canvas.drawPath(path, strokePaint)
                canvas.drawLine(x(4.5f), y(15.5f), x(19.5f), y(15.5f), strokePaint)
                path.reset()
                path.moveTo(x(8.5f), y(12f))
                path.lineTo(x(4.5f), y(15.5f))
                path.lineTo(x(8.5f), y(19f))
                canvas.drawPath(path, strokePaint)
            }

            TvIcon.PLAY_TRIANGLE -> {
                path.moveTo(x(7f), y(5f))
                path.lineTo(x(19f), y(12f))
                path.lineTo(x(7f), y(19f))
                path.close()
                canvas.drawPath(path, fillPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        strokePaint.alpha = alpha
        fillPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
