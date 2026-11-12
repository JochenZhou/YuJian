package com.zqc.tvnotify

import android.app.Activity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ApplicationVisibilityTest {
    @Test fun `application uses started lifecycle so pause and page transitions do not silence audio`() {
        val app = RuntimeEnvironment.getApplication() as YuJianApplication
        assertFalse(app.isAppVisible)
        val first = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        assertTrue(app.isAppVisible)
        first.pause()
        assertTrue("Dialogs and partial coverage are not leaving the app", app.isAppVisible)
        val second = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        first.stop().destroy()
        assertTrue(app.isAppVisible)
        second.pause().stop().destroy()
        assertFalse(app.isAppVisible)
    }
}
