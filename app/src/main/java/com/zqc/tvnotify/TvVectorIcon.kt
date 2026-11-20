package com.zqc.tvnotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 电视端写实矢量图标枚举：全部映射到 `res/drawable/ic_tv_*.xml` 原生多色分层 VectorDrawable，
 * 严格兼容 Android 6.0 (API 23)，零字体依赖、彻底告别 Emoji 方框缺字。
 */
internal enum class TvIcon(
    val resId: Int,
    val tintable: Boolean = false
) {
    // 9 大主题写实彩色徽章
    THEME_XIUXIAN(R.drawable.ic_tv_theme_xiuxian),
    THEME_DEFAULT(R.drawable.ic_tv_theme_default),
    THEME_PALACE(R.drawable.ic_tv_theme_palace),
    THEME_CYBERPUNK(R.drawable.ic_tv_theme_cyberpunk),
    THEME_WUKONG(R.drawable.ic_tv_theme_wukong),
    THEME_MATRIX(R.drawable.ic_tv_theme_matrix),
    THEME_INK_LIGHT(R.drawable.ic_tv_theme_ink_light),
    THEME_GAMER(R.drawable.ic_tv_theme_gamer),
    THEME_MINECRAFT(R.drawable.ic_tv_theme_minecraft),

    // 主界面与播放页写实/双色调功能图标
    MUSIC_DISC(R.drawable.ic_tv_music_disc),
    MUSIC_NOTE(R.drawable.ic_tv_music_note),
    WLED_SPARK(R.drawable.ic_tv_wled_spark),
    STATUS_DOT_SOLID(R.drawable.ic_tv_status_solid, tintable = true),
    STATUS_DOT_RING(R.drawable.ic_tv_status_ring, tintable = true),
    NETWORK(R.drawable.ic_tv_network),
    SHIELD(R.drawable.ic_tv_shield),
    WARNING(R.drawable.ic_tv_warning),
    ERROR_X(R.drawable.ic_tv_error_x),
    HOME_HA(R.drawable.ic_tv_home_ha),
    PHONE(R.drawable.ic_tv_phone),
    QR_CODE(R.drawable.ic_tv_qr_code),
    HISTORY(R.drawable.ic_tv_history),
    PALETTE(R.drawable.ic_tv_palette),
    BELL(R.drawable.ic_tv_bell),
    IMAGE(R.drawable.ic_tv_image),
    VIDEO(R.drawable.ic_tv_video),
    SPEAKER(R.drawable.ic_tv_speaker),
    MUTE(R.drawable.ic_tv_mute),
    POSITION(R.drawable.ic_tv_position),
    COPY(R.drawable.ic_tv_copy),
    REFRESH(R.drawable.ic_tv_refresh),
    DOWNLOAD(R.drawable.ic_tv_download),
    INFO(R.drawable.ic_tv_info),
    ROCKET(R.drawable.ic_tv_rocket),
    CHECK(R.drawable.ic_tv_check, tintable = true),
    SWITCH_ARROWS(R.drawable.ic_tv_switch_arrows, tintable = true),
    PLAY_TRIANGLE(R.drawable.ic_tv_play_triangle, tintable = true)
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

/**
 * 加载并按指定 dp 尺寸渲染原生 VectorDrawable；
 * 写实多色图标保留完整层次色彩，纯符号类图标（状态圆点/播放三角/切换箭头）支持随焦点或主题动态染色。
 */
internal class TvVectorIconDrawable(
    context: Context,
    private val icon: TvIcon,
    initialColor: Int,
    sizeDp: Float = 18f,
    @Suppress("UNUSED_PARAMETER") strokeDp: Float = 1.85f
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val intrinsicPx = (sizeDp * density + 0.5f).toInt().coerceAtLeast(1)
    private val delegate: Drawable? = context.getDrawable(icon.resId)?.mutate()
    private var iconColor = initialColor

    init {
        if (icon.tintable) {
            delegate?.setTint(initialColor)
        }
        setBounds(0, 0, intrinsicPx, intrinsicPx)
    }

    fun setIconColor(color: Int) {
        if (!icon.tintable || iconColor == color) return
        iconColor = color
        delegate?.setTint(color)
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int = intrinsicPx
    override fun getIntrinsicHeight(): Int = intrinsicPx

    override fun draw(canvas: Canvas) {
        val d = delegate ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        d.bounds = b
        d.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        delegate?.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        delegate?.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
