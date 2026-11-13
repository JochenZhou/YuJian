package com.zqc.tvnotify

/** Started activities include dialogs/partly visible pages, unlike onPause-based tracking. */
internal class AppVisibility(private val changed: (Boolean) -> Unit) {
    private val started = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    var visible = false
        private set

    fun started(activity: Any) {
        if (!started.add(activity)) return
        setVisible(true)
    }

    fun stopped(activity: Any, changingConfigurations: Boolean) {
        if (!started.remove(activity)) return
        if (started.isEmpty() && !changingConfigurations) setVisible(false)
    }

    private fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        changed(value)
    }
}
