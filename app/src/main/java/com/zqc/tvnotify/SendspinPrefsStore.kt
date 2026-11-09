package com.zqc.tvnotify

import android.content.Context
import com.sendspin.protocol.ClientSettingsStore

class SendspinPrefsStore(context: Context) : ClientSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("tvnotify_prefs", Context.MODE_PRIVATE)

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
