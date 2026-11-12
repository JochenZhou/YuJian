package com.zqc.tvnotify

import android.app.Application
import android.content.ClipData
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 24])
class ApkInstallTest {
    private lateinit var app: Application
    private lateinit var apk: File

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        apk = File(app.cacheDir, "updates/test-update.apk").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        }
    }

    @Test fun `installer receives content URI and explicit read grant including Android 6`() {
        AppUpdater.installApk(app, apk)
        val intent = shadowOf(app).nextStartedActivity
        assertNotNull("Installer must be launched", intent)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        assertEquals(apk.canonicalFile, File(app.cacheDir, "updates/${intent.data!!.lastPathSegment}").canonicalFile)
    }
}
