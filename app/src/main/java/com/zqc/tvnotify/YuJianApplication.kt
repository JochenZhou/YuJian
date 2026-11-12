package com.zqc.tvnotify

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Tracks the whole app, not the player page. Services alone never enable local audio. */
class YuJianApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val visibility = AppVisibility { visible -> SendspinRuntime.current()?.setAppVisible(visible) }
    val isAppVisible: Boolean get() = visibility.visible

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) = visibility.started(activity)
    override fun onActivityStopped(activity: Activity) = visibility.stopped(activity, activity.isChangingConfigurations)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
