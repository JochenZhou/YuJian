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
    @Test fun `unknown-source denial opens settings without launching installer`() {
        val app = RuntimeEnvironment.getApplication<Application>()
        val apk = File(app.cacheDir, "updates/permission.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        AppUpdater.installApk(app, apk)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
