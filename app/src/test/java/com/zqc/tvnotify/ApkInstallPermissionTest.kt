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
    private fun validArchive(apk: File) {
        val app = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val installed = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_SIGNATURES).apply {
            signatures = arrayOf(android.content.pm.Signature("1234"))
        }
        shadowOf(app.packageManager).installPackage(installed)
        val candidate = android.content.pm.PackageInfo().apply {
            packageName = installed.packageName
            versionCode = installed.versionCode + 1
            signatures = installed.signatures
        }
        shadowOf(app.packageManager).setPackageArchiveInfo(apk.absolutePath, candidate)
    }

    private fun resumedIntent(app: Application): Intent? {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            shadowOf(app).nextStartedActivity?.let { return it }
            Thread.sleep(10)
        }
        return null
    }
    @Test fun `return after authorization resumes installation with read grant`() {
        val app = RuntimeEnvironment.getApplication()
        val apk = File(app.cacheDir, "updates/return.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        AppUpdater.installApk(app, apk)
        shadowOf(app).nextStartedActivity
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume().get()
        validArchive(apk)
        AppUpdater.resumePendingInstall(activity)
        val install = resumedIntent(app)!!
        assertEquals(Intent.ACTION_VIEW, install.action)
        assertEquals("content", install.data!!.scheme)
        assertTrue(install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        AppUpdater.resumePendingInstall(activity)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun `pending installer survives process state loss while permission settings is open`() {
        val app = RuntimeEnvironment.getApplication()
        val apk = File(app.cacheDir, "updates/process-return.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        AppUpdater.installApk(app, apk)
        shadowOf(app).nextStartedActivity
        // Simulate process-local state loss; app-private preferences and cached APK survive.
        val pending = AppUpdater::class.java.getDeclaredField("pendingInstall").apply { isAccessible = true }
        pending.set(null, null)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume().get()
        validArchive(apk)
        AppUpdater.resumePendingInstall(activity)
        val intent = resumedIntent(app)
        assertNotNull("An already downloaded APK must not require another download", intent)
        assertEquals(Intent.ACTION_VIEW, intent!!.action)
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
