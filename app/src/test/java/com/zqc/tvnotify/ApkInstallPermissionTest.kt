package com.zqc.tvnotify

import android.app.Application
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class ApkInstallPermissionTest {
    @Test fun `return after authorization resumes installation with read grant`() {
        val app = RuntimeEnvironment.getApplication()
        val apk = File(app.cacheDir, "updates/return.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        AppUpdater.installApk(app, apk)
        shadowOf(app).nextStartedActivity
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume().get()
        AppUpdater.resumePendingInstall(activity)
        val install = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, install.action)
        assertEquals("content", install.data!!.scheme)
        assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        AppUpdater.resumePendingInstall(activity)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `unknown-source denial opens settings without launching installer`() {
        val app = RuntimeEnvironment.getApplication()
        val apk = File(app.cacheDir, "updates/permission.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        AppUpdater.installApk(app, apk)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
