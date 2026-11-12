package com.zqc.tvnotify

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Model the observed Xiaomi installer: VIEW accepts file, never content. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class LegacyApkInstallerTest {
    private fun registerInstaller(app: Application, scheme: String) {
        val component = ComponentName("com.android.packageinstaller", "com.android.packageinstaller.Installer")
        val activity = ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            exported = true
            enabled = true
            applicationInfo = ApplicationInfo().apply {
                packageName = component.packageName
                enabled = true
                flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_INSTALLED
            }
        }
        shadowOf(app.packageManager).addOrUpdateActivity(activity)
        shadowOf(app.packageManager).addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme(scheme)
            addDataType("application/vnd.android.package-archive")
        })
    }

    @Test fun `file-only API23 installer gets a readable copy in app-specific external cache`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "updates/legacy.apk").apply { parentFile!!.mkdirs(); writeText("verified apk bytes") }
        registerInstaller(app, "file")
        val content = Intent(Intent.ACTION_VIEW).setDataAndType(ApkFileProvider.getUriForFile(app, source), "application/vnd.android.package-archive")
        assertNull("Reproduce OEM missing content filter", content.resolveActivity(app.packageManager))
        AppUpdater.installApk(app, source)
        val deadline = System.nanoTime() + 5_000_000_000L
        var intent: Intent? = null
        while (System.nanoTime() < deadline && intent == null) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            intent = shadowOf(app).nextStartedActivity
            if (intent == null) Thread.sleep(10)
        }
        assertNotNull(intent)
        assertEquals("file", intent!!.data!!.scheme)
        val delivered = File(intent.data!!.path!!)
        assertEquals(File(app.externalCacheDir, "updates").canonicalFile, delivered.canonicalFile.parentFile)
        assertEquals("verified apk bytes", delivered.readText())
        assertEquals("verified apk bytes", source.readText())
    }

    @Test fun `API23 installer with content support retains private read-grant delivery`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "updates/modern.apk").apply { parentFile!!.mkdirs(); writeText("verified") }
        registerInstaller(app, "content")
        AppUpdater.installApk(app, source)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("content", intent.data!!.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
    }

    @Test @Config(sdk = [24]) fun `API24 never exposes file URI to a legacy-only installer`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "updates/new-android.apk").apply { parentFile!!.mkdirs(); writeText("verified") }
        registerInstaller(app, "file")
        AppUpdater.installApk(app, source)
        val intent = shadowOf(app).nextStartedActivity
        assertTrue("Must either use content or reject, never file URI", intent == null || intent.data?.scheme == "content")
    }
}
