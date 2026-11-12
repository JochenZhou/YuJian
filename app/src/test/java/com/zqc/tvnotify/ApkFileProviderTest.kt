package com.zqc.tvnotify

import android.app.Application
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ApkFileProviderTest {
    @Test fun `provider opens the exact shared private cache APK read-only`() {
        val app = RuntimeEnvironment.getApplication<Application>()
        val file = File(app.cacheDir, "updates/provider.apk").apply { parentFile!!.mkdirs(); writeText("private") }
        val uri = ApkFileProvider.getUriForFile(app, file)
        assertEquals("private", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        try {
            app.contentResolver.openFileDescriptor(uri, "rw")?.close()
            fail("Write mode must be rejected")
        } catch (_: java.io.FileNotFoundException) { }
    }

    @Test fun `provider refuses files outside the updates directory`() {
        val app = RuntimeEnvironment.getApplication<Application>()
        val file = File(app.cacheDir, "private-token.txt").apply { writeText("secret") }
        try {
            ApkFileProvider.getUriForFile(app, file)
            fail("Only updater-owned APKs may be shared")
        } catch (_: IllegalArgumentException) { }
        try {
            app.contentResolver.openInputStream(Uri.parse("content://${ApkFileProvider.AUTHORITY}/private-token.txt"))?.close()
            fail("Private cache file must never be served")
        } catch (_: java.io.FileNotFoundException) { }
    }
}
