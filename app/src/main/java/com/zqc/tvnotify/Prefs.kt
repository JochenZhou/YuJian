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
    private const val KEY_SENDSPIN_PRIV_KEY = "sendspin_priv_key"
    private const val KEY_SENDSPIN_PAIRINGS = "sendspin_pairings"

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

    /** 读取 Sendspin 服务是否启用 */
    fun isSendspinEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENDSPIN_ENABLED, true)

    /** 设置 Sendspin 服务是否启用 */
    fun setSendspinEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SENDSPIN_ENABLED, enabled).apply()
    }

    /** 读取收到音乐流时是否自动弹出全屏歌词页 */
    fun isSendspinAutoPopup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SENDSPIN_AUTO_POPUP, true)

    /** 设置收到音乐流时是否自动弹出全屏歌词页 */
    fun setSendspinAutoPopup(context: Context, autoPopup: Boolean) {
        prefs(context).edit().putBoolean(KEY_SENDSPIN_AUTO_POPUP, autoPopup).apply()
    }

    /** 读取手动配置的 Sendspin 服务端 WebSocket 地址（留空则为 mDNS 自动发现模式） */
    fun getSendspinServerUrl(context: Context): String =
        prefs(context).getString(KEY_SENDSPIN_SERVER_URL, "") ?: ""

    /** 设置手动配置的 Sendspin 服务端 WebSocket 地址 */
    fun setSendspinServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SENDSPIN_SERVER_URL, url.trim()).apply()
    }

    /** 读取 Sendspin 客户端对外展示的友好设备名 */
    fun getSendspinClientName(context: Context): String {
        val defaultName = "玉简·${(android.os.Build.MODEL ?: "TV").trim()}"
        return prefs(context).getString(KEY_SENDSPIN_CLIENT_NAME, defaultName) ?: defaultName
    }

    /** 设置 Sendspin 客户端对外展示的友好设备名 */
    fun setSendspinClientName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SENDSPIN_CLIENT_NAME, name.trim()).apply()
    }

    /** 读取或生成客户端静态 X25519 私钥（32 字节 CSPRNG） */
    fun getOrCreateSendspinPrivateKey(context: Context): ByteArray {
        val store = prefs(context)
        val encoded = store.getString(KEY_SENDSPIN_PRIV_KEY, null)
        if (!encoded.isNullOrEmpty()) {
            val decoded = SendspinCrypto.base64UrlDecode(encoded)
            if (decoded.size == 32) {
                return decoded
            }
        }
        synchronized(this) {
            val again = store.getString(KEY_SENDSPIN_PRIV_KEY, null)
            if (!again.isNullOrEmpty()) {
                val decoded = SendspinCrypto.base64UrlDecode(again)
                if (decoded.size == 32) {
                    return decoded
                }
            }
            val key = ByteArray(32)
            random.nextBytes(key)
            store.edit().putString(KEY_SENDSPIN_PRIV_KEY, SendspinCrypto.base64UrlEncode(key)).commit()
            return key
        }
    }

    /** 读取已持久化的配对记录 JSON */
    fun getSendspinPairingsJson(context: Context): String? =
        prefs(context).getString(KEY_SENDSPIN_PAIRINGS, null)

    /** 保存已持久化的配对记录 JSON */
    fun setSendspinPairingsJson(context: Context, raw: String) {
        prefs(context).edit().putString(KEY_SENDSPIN_PAIRINGS, raw).apply()
    }

    private fun generateToken(): String {
        val builder = StringBuilder(TOKEN_LENGTH)
        repeat(TOKEN_LENGTH) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
        return builder.toString()
    }
}
