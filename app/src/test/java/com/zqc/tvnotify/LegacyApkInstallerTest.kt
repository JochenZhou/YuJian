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

    private fun awaitStartedActivity(app: Application): Intent? {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            shadowOf(app).nextStartedActivity?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    @Test fun `file-only API23 installer gets a readable APK in internal update cache`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "updates/legacy.apk").apply { parentFile!!.mkdirs(); writeText("verified apk bytes") }
        registerInstaller(app, "file")
        val content = Intent(Intent.ACTION_VIEW).setDataAndType(ApkFileProvider.getUriForFile(app, source), "application/vnd.android.package-archive")
        assertNull("Reproduce OEM missing content filter", content.resolveActivity(app.packageManager))
        AppUpdater.installApk(app, source)
        val intent = awaitStartedActivity(app)
        assertNotNull(intent)
        assertEquals("file", intent!!.data!!.scheme)
        val delivered = File(intent.data!!.path!!)
        assertEquals(source.canonicalFile, delivered.canonicalFile)
        assertEquals(File(app.cacheDir, "updates").canonicalFile, delivered.canonicalFile.parentFile)
        assertTrue(delivered.canRead())
        assertEquals("verified apk bytes", delivered.readText())
    }

    @Test fun `file-only API23 installer succeeds even when external cache updates cannot be opened`() {
        val app = RuntimeEnvironment.getApplication()
        val source = File(app.cacheDir, "updates/no-external.apk").apply { parentFile!!.mkdirs(); writeText("internal apk bytes") }
        val blockingExternalUpdates = app.externalCacheDir?.let { extRoot ->
            File(extRoot, "updates").apply {
                parentFile?.mkdirs()
                deleteRecursively()
                // Blocking updates path as a regular file guarantees any copy to externalCacheDir throws.
                writeText("not a directory")
            }
        }
        try {
            registerInstaller(app, "file")
            AppUpdater.installApk(app, source)
            val intent = awaitStartedActivity(app)
            assertNotNull("Installer launch must not depend on externalCacheDir", intent)
            assertEquals("file", intent!!.data!!.scheme)
            val delivered = File(intent.data!!.path!!)
            assertEquals(source.canonicalFile, delivered.canonicalFile)
            assertEquals("internal apk bytes", delivered.readText())
        } finally {
            blockingExternalUpdates?.delete()
        }
    }

    @Test fun `prepareLegacyApkFile stages external source and cleans stale legacy file`() {
        val app = RuntimeEnvironment.getApplication()
        val updatesDir = File(app.cacheDir, "updates").apply { mkdirs() }
        val stale = File(updatesDir, "YuJian-legacy-update.apk").apply {
            writeText("stale bytes")
            setReadOnly()
        }
        val outside = File(app.cacheDir, "staging/outside.apk").apply { parentFile!!.mkdirs(); writeText("staged apk bytes") }
        val staged = AppUpdater.prepareLegacyApkFile(app, outside)
        assertEquals(stale.canonicalFile, staged.canonicalFile)
        assertTrue(staged.canRead())
        assertEquals("staged apk bytes", staged.readText())

        val internal = File(updatesDir, "YuJian-update.apk").apply {
            writeText("primary internal apk")
            setReadable(false, false)
            setReadable(true, true)
        }
        updatesDir.setReadable(false, false)
        updatesDir.setReadable(true, true)
        updatesDir.setExecutable(false, false)
        updatesDir.setExecutable(true, true)
        val preparedInternal = AppUpdater.prepareLegacyApkFile(app, internal)
        assertEquals(internal.canonicalFile, preparedInternal.canonicalFile)
        assertFalse("Stale legacy copy should be removed once internal primary is used", stale.exists())
        assertTrue(preparedInternal.canRead())
        assertEquals("primary internal apk", preparedInternal.readText())
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            @Suppress("NewApi")
            run {
                val dirPerms = java.nio.file.Files.getPosixFilePermissions(updatesDir.toPath())
                assertTrue(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE in dirPerms)
                assertFalse(java.nio.file.attribute.PosixFilePermission.OTHERS_READ in dirPerms)
                val filePerms = java.nio.file.Files.getPosixFilePermissions(preparedInternal.toPath())
                assertTrue(java.nio.file.attribute.PosixFilePermission.OTHERS_READ in filePerms)
            }
        }
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
