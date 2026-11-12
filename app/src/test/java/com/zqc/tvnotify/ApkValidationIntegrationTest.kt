package com.zqc.tvnotify

import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ApkValidationIntegrationTest {
    @Test fun `unparseable archive fails validation before installer handoff`() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "updates/corrupt.apk").apply { parentFile!!.mkdirs(); writeText("not an APK") }
        assertNotNull(AppUpdater.validateUpdate(app, file))
    }
}
