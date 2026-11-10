package com.zqc.tvnotify

import android.content.Context
import com.sendspin.protocol.ClientSettingsKeys
import com.sendspin.protocol.ClientSettingsStore

class SendspinPrefsStore(context: Context) : ClientSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("tvnotify_prefs", Context.MODE_PRIVATE)

    val automaticSync: Boolean
        get() = getInt(ClientSettingsKeys.OUTPUT_SYNC_AUTOMATIC, 1) != 0

    val manualOutputDelayMs: Int
        get() = getInt(ClientSettingsKeys.OUTPUT_DELAY_MS, 0).coerceIn(0, 5000)

    /** Changing mode must leave the saved manual compensation untouched. */
    fun setAutomaticSync(automatic: Boolean): Boolean = prefs.edit()
        .putInt("sendspin_${ClientSettingsKeys.OUTPUT_SYNC_AUTOMATIC}", if (automatic) 1 else 0)
        .commit()

    /** Persist both values before a client exists or a connection is established. */
    fun setManualOutputDelayMs(delayMs: Int): Boolean {
        if (delayMs !in 0..5000) return false
        return prefs.edit()
            .putInt("sendspin_${ClientSettingsKeys.OUTPUT_DELAY_MS}", delayMs)
            .putInt("sendspin_${ClientSettingsKeys.OUTPUT_SYNC_AUTOMATIC}", 0)
            .commit()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt("sendspin_$key", default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt("sendspin_$key", value).apply()
    }

    override fun getString(key: String, default: String?): String? =
        prefs.getString("sendspin_$key", default)

    override fun putString(key: String, value: String) {
        prefs.edit().putString("sendspin_$key", value).apply()
    }
}
