package com.zqc.tvnotify

import org.junit.Assert.*
import org.junit.Test

class ApkUpdateValidatorTest {
    private val installed = ApkUpdateValidator.Package("com.zqc.tvnotify", 47, setOf("release"))

    @Test fun `valid signed newer update is accepted`() {
        assertNull(ApkUpdateValidator.rejection(installed, installed.copy(versionCode = 48)))
    }
    @Test fun `unparseable APK is rejected`() {
        assertNotNull(ApkUpdateValidator.rejection(installed, null))
    }
    @Test fun `different package is rejected`() {
        assertNotNull(ApkUpdateValidator.rejection(installed, installed.copy(name = "other", versionCode = 48)))
    }
    @Test fun `older and same version cannot replace current app`() {
        for (code in listOf(46L, 47L)) assertNotNull(ApkUpdateValidator.rejection(installed, installed.copy(versionCode = code)))
    }
    @Test fun `different or missing signer cannot be installed`() {
        for (signatures in listOf(emptySet(), setOf("debug"), setOf("release", "other"))) {
            assertNotNull(ApkUpdateValidator.rejection(installed, installed.copy(versionCode = 48, signatures = signatures)))
        }
    }
}
