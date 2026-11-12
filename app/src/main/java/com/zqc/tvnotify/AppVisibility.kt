package com.zqc.tvnotify

/** App-wide started-activity state, independent of Android callbacks for deterministic tests. */
internal class AppVisibility(private val changed: (Boolean) -> Unit) {
    var visible = false
        private set
    fun started(activity: Any) {}
    fun stopped(activity: Any, changingConfigurations: Boolean) {}
}
