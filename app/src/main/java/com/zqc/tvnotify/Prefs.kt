package com.zqc.tvnotify

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * 访问 token、主题、默认方位及闭关免扰等配置的唯一持久化入口。
 *
 * 设计要点：
 * 1. token 由「首次使用」时随机生成 8 位字母数字，写入 SharedPreferences；
 * 2. HTTP 服务端每次请求都通过 [getToken] 实时读取，因此「重置 Token」后
 *    旧地址立即失效、新地址立即生效，无需重启服务；
 * 3. 写入使用 commit() 而不是 apply()，保证重置后紧接着的请求一定能读到新值。
 */
object Prefs {

    private const val FILE_NAME = "tvnotify_prefs"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_THEME = "ui_theme"
    private const val KEY_POSITION = "banner_position"
    private const val KEY_MUTED = "banner_muted"
    private const val KEY_HISTORY = "notify_history"
    private const val KEY_SENDSPIN_ENABLED = "sendspin_enabled"
    private const val KEY_SENDSPIN_AUTO_POPUP = "sendspin_auto_popup"
    private const val KEY_SENDSPIN_SERVER_URL = "sendspin_server_url"
    private const val KEY_SENDSPIN_CLIENT_NAME = "sendspin_client_name"
    private const val KEY_SENDSPIN_PLAYER_STYLE = "sendspin_player_style"
    private const val KEY_WLED_SYNC_ENABLED = "wled_sync_enabled"
    private const val KEY_WLED_SYNC_PORT = "wled_sync_port"
    private const val KEY_WLED_SYNC_GAIN = "wled_sync_gain"
    private const val KEY_WLED_COLOR_SYNC_ENABLED = "wled_color_sync_enabled"
    private const val KEY_WLED_COLOR_HOST = "wled_color_host"
    private const val KEY_WLED_EFFECT = "wled_effect"

    const val DEFAULT_WLED_SYNC_PORT = 11988
    const val DEFAULT_WLED_SYNC_GAIN = 70
    const val MIN_WLED_SYNC_GAIN = 10
    const val MAX_WLED_SYNC_GAIN = 200
    const val DEFAULT_WLED_COLOR_HOST = "192.168.101.206"
    val DEFAULT_WLED_EFFECT: String get() = WledEffectPreset.CUSTOM.storageId

    enum class SendspinPlayerStyle(val storageId: String, val label: String) {
        VINYL("vinyl", "样式一（经典黑胶）"),
        COVER_BLUR("cover_blur", "样式二（方形封面 · 高斯模糊背景）");

        companion object {
            fun tryParse(raw: String?): SendspinPlayerStyle? = when (raw?.trim()?.lowercase()) {
                "vinyl", "style1" -> VINYL
                "cover_blur", "square", "style2" -> COVER_BLUR
                else -> null
            }

            fun fromRaw(raw: String?): SendspinPlayerStyle = tryParse(raw) ?: VINYL
        }
    }

    private const val TOKEN_LENGTH = 8

    /**
     * 字母表刻意剔除 i/l/o/0/1 等易混字符，避免用户在电视上用遥控器抄写地址时出错。
     * 仍然是「字母 + 数字」组合。
     */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    private val random = SecureRandom()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 读取 token；不存在则生成并持久化。 */
    fun getToken(context: Context): String {
        val store = prefs(context)
        val cached = store.getString(KEY_TOKEN, null)
        if (!cached.isNullOrEmpty()) {
            return cached
        }
        synchronized(this) {
            // 双重检查：HTTP 线程与 UI 线程可能同时首次读取
            val again = store.getString(KEY_TOKEN, null)
            if (!again.isNullOrEmpty()) {
                return again
            }
            val fresh = generateToken()
            store.edit().putString(KEY_TOKEN, fresh).commit()
            return fresh
        }
    }

    /** 重新生成 token，返回新值。运行中的 HTTP 服务因每次请求都读取，会自动生效。 */
    fun resetToken(context: Context): String {
        val store = prefs(context)
        synchronized(this) {
            val fresh = generateToken()
            store.edit().putString(KEY_TOKEN, fresh).commit()
            return fresh
        }
    }

    /** 读取已保存的主题 id；默认优先采用修仙玉简主题。 */
    fun getThemeId(context: Context): String =
        prefs(context).getString(KEY_THEME, "xiuxian") ?: "xiuxian"

    /** 保存主题 id。UI 切换后立即生效，不需要重启服务。 */
    fun setThemeId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_THEME, id).apply()
    }

    /** 读取默认横幅方位（top, bottom, top-right, bottom-right, top-left, bottom-left） */
    fun getBannerPosition(context: Context): String =
        prefs(context).getString(KEY_POSITION, "top") ?: "top"

    /** 保存默认横幅方位 */
    fun setBannerPosition(context: Context, position: String) {
        prefs(context).edit().putString(KEY_POSITION, position.trim().lowercase()).apply()
    }

    /** 读取是否处于闭关免扰（全局静音）模式 */
    fun isMuted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUTED, false)

    /** 设置闭关免扰（全局静音）模式 */
    fun setMuted(context: Context, muted: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTED, muted).apply()
    }

    /** 读取历史记录 JSON 字符串 */
    fun getHistoryRaw(context: Context): String? =
        prefs(context).getString(KEY_HISTORY, null)

    /** 保存历史记录 JSON 字符串 */
    fun setHistoryRaw(context: Context, rawJson: String) {
        prefs(context).edit().putString(KEY_HISTORY, rawJson).apply()
    }

    fun isSendspinEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENDSPIN_ENABLED, true)

    fun setSendspinEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SENDSPIN_ENABLED, enabled).apply()
    }

    fun isSendspinAutoPopup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENDSPIN_AUTO_POPUP, true)

    fun setSendspinAutoPopup(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SENDSPIN_AUTO_POPUP, enabled).apply()
    }

    fun getSendspinServerUrl(context: Context): String =
        prefs(context).getString(KEY_SENDSPIN_SERVER_URL, "") ?: ""

    fun setSendspinServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SENDSPIN_SERVER_URL, url.trim()).apply()
    }

    fun getSendspinClientName(context: Context): String {
        val fallback = "玉简·${android.os.Build.MODEL.orEmpty().ifBlank { "TV" }}"
        return prefs(context).getString(KEY_SENDSPIN_CLIENT_NAME, fallback) ?: fallback
    }

    fun setSendspinClientName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SENDSPIN_CLIENT_NAME, name.trim()).apply()
    }

    /** 读取播放页视觉样式；默认样式一（经典黑胶唱片）。 */
    fun getSendspinPlayerStyle(context: Context): SendspinPlayerStyle =
        SendspinPlayerStyle.fromRaw(prefs(context).getString(KEY_SENDSPIN_PLAYER_STYLE, SendspinPlayerStyle.VINYL.storageId))

    /** 持久化播放页视觉样式。 */
    fun setSendspinPlayerStyle(context: Context, style: SendspinPlayerStyle) {
        prefs(context).edit().putString(KEY_SENDSPIN_PLAYER_STYLE, style.storageId).apply()
    }

    /** 是否开启 WLED 音乐律动 UDP 广播；默认关闭。 */
    fun isWledSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WLED_SYNC_ENABLED, false)

    /** 持久化 WLED 音乐律动总开关。 */
    fun setWledSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WLED_SYNC_ENABLED, enabled).apply()
    }

    /** 读取 WLED AudioReactive UDP Sync 端口；默认 11988。 */
    fun getWledSyncPort(context: Context): Int {
        val port = prefs(context).getInt(KEY_WLED_SYNC_PORT, DEFAULT_WLED_SYNC_PORT)
        return if (port in 1..65535) port else DEFAULT_WLED_SYNC_PORT
    }

    /** 持久化有效的 WLED UDP 端口（1..65535）；无效值直接拒绝且不覆盖原配置。 */
    fun setWledSyncPort(context: Context, port: Int): Boolean {
        if (port !in 1..65535) return false
        prefs(context).edit().putInt(KEY_WLED_SYNC_PORT, port).apply()
        return true
    }

    /** 解析并校验 WLED UDP 端口字符串。 */
    fun parseWledSyncPort(raw: String): Int? {
        val port = raw.trim().toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    /** 读取 WLED 音乐律动灵敏度百分比（10–200，默认 70）。 */
    fun getWledSyncGain(context: Context): Int {
        val gain = prefs(context).getInt(KEY_WLED_SYNC_GAIN, DEFAULT_WLED_SYNC_GAIN)
        return if (gain in MIN_WLED_SYNC_GAIN..MAX_WLED_SYNC_GAIN) gain else DEFAULT_WLED_SYNC_GAIN
    }

    /** 持久化 WLED 音乐律动灵敏度（10–200）；越界值拒绝且不覆盖原配置。 */
    fun setWledSyncGain(context: Context, gainPercent: Int): Boolean {
        if (gainPercent !in MIN_WLED_SYNC_GAIN..MAX_WLED_SYNC_GAIN) return false
        prefs(context).edit().putInt(KEY_WLED_SYNC_GAIN, gainPercent).apply()
        return true
    }

    /** 解析并校验 WLED 律动灵敏度百分比字符串。 */
    fun parseWledSyncGain(raw: String): Int? {
        val gain = raw.trim().removeSuffix("%").trim().toIntOrNull() ?: return null
        return if (gain in MIN_WLED_SYNC_GAIN..MAX_WLED_SYNC_GAIN) gain else null
    }

    /** 是否开启 WLED 封面主色调同步；默认关闭。 */
    fun isWledColorSyncEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WLED_COLOR_SYNC_ENABLED, false)

    /** 持久化 WLED 封面色彩同步开关。 */
    fun setWledColorSyncEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WLED_COLOR_SYNC_ENABLED, enabled).apply()
    }

    /** 读取目标 WLED 设备的 IP 或主机名；默认 192.168.101.206。 */
    fun getWledColorHost(context: Context): String {
        val stored = prefs(context).getString(KEY_WLED_COLOR_HOST, DEFAULT_WLED_COLOR_HOST).orEmpty()
        return normalizeWledHost(stored) ?: DEFAULT_WLED_COLOR_HOST
    }

    /** 持久化校验后的 WLED 主机名 / IP（可选 :port）；无效输入拒绝且不覆盖原配置。 */
    fun setWledColorHost(context: Context, host: String): Boolean {
        val normalized = normalizeWledHost(host) ?: return false
        prefs(context).edit().putString(KEY_WLED_COLOR_HOST, normalized).apply()
        return true
    }

    /** 读取最近一次选中的推荐 WLED 律动效果（默认 custom，不主动改写灯带自身效果）。 */
    fun getWledEffect(context: Context): String {
        val stored = prefs(context).getString(KEY_WLED_EFFECT, DEFAULT_WLED_EFFECT)
        return WledEffectPreset.fromRaw(stored).storageId
    }

    /** 持久化推荐 WLED 律动效果标识；未知值返回 false 且不覆盖原配置。 */
    fun setWledEffect(context: Context, rawEffect: String): Boolean {
        val preset = WledEffectPreset.tryParse(rawEffect) ?: return false
        prefs(context).edit().putString(KEY_WLED_EFFECT, preset.storageId).apply()
        return true
    }

    /** 规范化并校验 WLED 设备地址（支持 host 或 host:port，自动去除 http/https 前缀与末尾斜杠）。 */
    fun normalizeWledHost(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (value.any { it.isWhitespace() || it.code < 0x20 || it == '?' || it == '#' || it == '@' }) return null

        val schemeIndex = value.indexOf("://")
        if (schemeIndex >= 0) {
            val scheme = value.substring(0, schemeIndex).lowercase()
            if (scheme != "http" && scheme != "https") return null
            value = value.substring(schemeIndex + 3)
        }
        value = value.trimEnd('/')
        if (value.isEmpty() || value.contains('/')) return null

        if (value.startsWith('[')) {
            val closeIdx = value.indexOf(']')
            if (closeIdx <= 1) return null
            val addr = value.substring(1, closeIdx)
            if (!addr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) return null
            val tail = value.substring(closeIdx + 1)
            if (tail.isNotEmpty()) {
                if (!tail.startsWith(':')) return null
                val port = tail.substring(1).toIntOrNull() ?: return null
                if (port !in 1..65535) return null
            }
            return value
        }

        val firstColon = value.indexOf(':')
        val hostPart: String
        if (firstColon >= 0) {
            if (value.indexOf(':', firstColon + 1) >= 0) return null
            hostPart = value.substring(0, firstColon)
            val port = value.substring(firstColon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        } else {
            hostPart = value
        }

        if (hostPart.isEmpty() || hostPart.startsWith('.') || hostPart.endsWith('.') || hostPart.startsWith('-') || hostPart.endsWith('-')) {
            return null
        }
        if (!hostPart.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }) {
            return null
        }
        return value
    }

    private fun generateToken(): String {
        val builder = StringBuilder(TOKEN_LENGTH)
        repeat(TOKEN_LENGTH) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return builder.toString()
    }
}
