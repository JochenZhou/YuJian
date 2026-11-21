package com.zqc.tvnotify

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowInstrumentation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = "w960dp-h540dp-xhdpi")
class MainFocusLayoutTest {
    private fun field(activity: MainActivity, name: String): View =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as View

    private fun activity(theme: Theme = Theme.DEFAULT): Pair<org.robolectric.android.controller.ActivityController<MainActivity>, MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        // Exercise the real UI builder without starting notification/audio/network services.
        MainActivity::class.java.getDeclaredField("theme").apply { isAccessible = true }.set(activity, theme)
        val root = MainActivity::class.java.getDeclaredMethod("buildRootView").apply { isAccessible = true }.invoke(activity) as View
        activity.setContentView(root)
        return controller to activity
    }

    private fun layout(activity: MainActivity, width: Int) {
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, 1080)
    }

    @Test fun `edge buttons keep entire focused bounds inside row drawing area and card`() {
        val (_, activity) = activity()
        for (width in listOf(1920, 1280, 800)) {
            for (tab in MainTab.entries) {
                MainActivity::class.java.getDeclaredMethod("selectMainTab", MainTab::class.java).apply { isAccessible = true }.invoke(activity, tab)
                layout(activity, width)
                val names = when (tab) {
                    MainTab.OVERVIEW -> listOf(
                        "overviewTabButton", "systemTabButton",
                        "musicButton", "quickHistoryButton", "quickThemeButton", "quickConnectButton"
                    )
                    MainTab.PRACTICE -> listOf(
                        "testButton", "imageTestButton", "videoTestButton", "ttsTestButton",
                        "historyButton", "positionButton", "muteButton", "themeButton"
                    )
                    MainTab.SYSTEM -> listOf(
                        "copyButton", "resetButton", "launchPlayerButton", "updateButton", "aboutButton",
                        "haTabButton", "tokenTabButton"
                    )
                }
                for (name in names) {
                    val button = field(activity, name) as Button
                    assertTrue("Button is laid out: $name width=$width", button.width > 0)
                    val focusScale = if (name in listOf("overviewTabButton", "systemTabButton")) 1.03f else 1.04f
                    val bounds = RectF(0f, 0f, button.width.toFloat(), button.height.toFloat())
                    Matrix().apply { setScale(focusScale, focusScale, button.width / 2f, button.height / 2f) }.mapRect(bounds)

                    // Simulate Android ViewGroup.drawChild clipping along the entire ancestor chain:
                    // any ancestor with clipChildren=true clips its child to that child's own bounds [0..w, 0..h].
                    var child: View = button
                    var ancestor = child.parent as? ViewGroup
                    while (ancestor != null && ancestor.id != android.R.id.content && ancestor !is android.widget.ScrollView) {
                        if (ancestor.clipChildren) {
                            assertTrue(
                                "Focused left edge clipped at ${child.javaClass.simpleName} by parent ${ancestor.javaClass.simpleName}: $name width=$width left=${bounds.left}",
                                bounds.left >= 0f
                            )
                            assertTrue(
                                "Focused right edge clipped at ${child.javaClass.simpleName} by parent ${ancestor.javaClass.simpleName}: $name width=$width right=${bounds.right} > ${child.width}",
                                bounds.right <= child.width.toFloat()
                            )
                            assertTrue(
                                "Focused top edge clipped at ${child.javaClass.simpleName} by parent ${ancestor.javaClass.simpleName}: $name width=$width top=${bounds.top}",
                                bounds.top >= 0f
                            )
                            assertTrue(
                                "Focused bottom edge clipped at ${child.javaClass.simpleName} by parent ${ancestor.javaClass.simpleName}: $name width=$width bottom=${bounds.bottom} > ${child.height}",
                                bounds.bottom <= child.height.toFloat()
                            )
                        }
                        bounds.offset(child.left.toFloat(), child.top.toFloat())
                        if (ancestor.clipToPadding) {
                            assertTrue(
                                "Focused left edge clipped by padding of ${ancestor.javaClass.simpleName}: $name width=$width",
                                bounds.left >= ancestor.paddingLeft.toFloat()
                            )
                            assertTrue(
                                "Focused right edge clipped by padding of ${ancestor.javaClass.simpleName}: $name width=$width",
                                bounds.right <= (ancestor.width - ancestor.paddingRight).toFloat()
                            )
                            assertTrue(
                                "Focused top edge clipped by padding of ${ancestor.javaClass.simpleName}: $name width=$width",
                                bounds.top >= ancestor.paddingTop.toFloat()
                            )
                            assertTrue(
                                "Focused bottom edge clipped by padding of ${ancestor.javaClass.simpleName}: $name width=$width",
                                bounds.bottom <= (ancestor.height - ancestor.paddingBottom).toFloat()
                            )
                        }
                        child = ancestor
                        ancestor = child.parent as? ViewGroup
                    }
                }
            }
        }
    }

    @Test fun `service status is informational and down navigation enters real controls`() {
        val (_, activity) = activity()
        val overview = field(activity, "overviewPage") as ViewGroup
        val rightColumn = overview.getChildAt(1) as LinearLayout
        val statusCard = rightColumn.getChildAt(0)
        assertFalse("Status card must not be a D-pad target", statusCard.isFocusable)
        assertFalse("Status card has no action", statusCard.isClickable)
        val tab = field(activity, "overviewTabButton")
        val music = field(activity, "musicButton")
        assertEquals(music.id, tab.nextFocusDownId)
        assertEquals(tab.id, music.nextFocusUpId)
        val focusables = ArrayList<View>()
        overview.addFocusables(focusables, View.FOCUS_DOWN)
        assertFalse(focusables.contains(statusCard))
        assertTrue(focusables.contains(music))
    }

    @Test fun `overview, practice and system buttons are not pre-selected by default`() {
        for (theme in listOf(Theme.XIUXIAN, Theme.DEFAULT, Theme.MINECRAFT)) {
            val (_, activity) = activity(theme)
            val pairs = listOf(
                Triple(MainTab.OVERVIEW, "quickHistoryButton", "musicButton"),
                Triple(MainTab.PRACTICE, "testButton", "imageTestButton"),
                Triple(MainTab.SYSTEM, "copyButton", "resetButton")
            )
            for ((tab, targetName, siblingName) in pairs) {
                MainActivity::class.java.getDeclaredMethod("selectMainTab", MainTab::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, tab)
                val target = field(activity, targetName) as Button
                val sibling = field(activity, siblingName) as Button

                assertFalse("$targetName must not be selected", target.isSelected)
                assertFalse("$targetName must not be focused", target.isFocused)
                assertFalse("$targetName must not be activated", target.isActivated)

                val targetDrawable = target.background as StateListDrawable
                val siblingDrawable = sibling.background as StateListDrawable
                targetDrawable.state = intArrayOf()
                siblingDrawable.state = intArrayOf()

                val targetSurface = targetDrawable.current as MainActivity.RoundedSurfaceDrawable
                val siblingSurface = siblingDrawable.current as MainActivity.RoundedSurfaceDrawable
                assertEquals("$targetName should match default fill", theme.buttonBg, targetSurface.fillColor)
                assertEquals("$targetName should match default stroke", theme.buttonStroke, targetSurface.strokeColor)
                assertEquals("$targetName should match sibling fill", siblingSurface.fillColor, targetSurface.fillColor)
                assertEquals("$targetName should match sibling stroke", siblingSurface.strokeColor, targetSurface.strokeColor)

                targetDrawable.state = intArrayOf(android.R.attr.state_focused)
                val focusedSurface = targetDrawable.current as MainActivity.RoundedSurfaceDrawable
                val expectedFocusedFill = MainActivity::class.java
                    .getDeclaredMethod("focusedButtonFillColor")
                    .apply { isAccessible = true }
                    .invoke(activity) as Int
                val expectedFocusedStroke = MainActivity::class.java
                    .getDeclaredMethod("focusedBorderStrokeColor")
                    .apply { isAccessible = true }
                    .invoke(activity) as Int
                assertEquals("$targetName should use refined soft highlight when focused", expectedFocusedFill, focusedSurface.fillColor)
                assertEquals(expectedFocusedStroke, focusedSurface.strokeColor)
                assertNotEquals("Focused fill should be softer than raw 100% accent glare", theme.accentColor, focusedSurface.fillColor)
                targetDrawable.state = intArrayOf()
            }
        }
    }

    private fun activeTab(activity: MainActivity): MainTab =
        MainActivity::class.java.getDeclaredField("activeMainTab").apply { isAccessible = true }.get(activity) as MainTab

    private fun selectTab(activity: MainActivity, tab: MainTab) {
        MainActivity::class.java.getDeclaredMethod("selectMainTab", MainTab::class.java).apply { isAccessible = true }.invoke(activity, tab)
    }

    private fun runInKeyMode(activity: MainActivity, block: () -> Unit) {
        val prevTouchMode = field(activity, "overviewTabButton").isInTouchMode
        ShadowInstrumentation.getInstrumentation().setInTouchMode(false)
        try {
            block()
        } finally {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            ShadowInstrumentation.getInstrumentation().setInTouchMode(prevTouchMode)
        }
    }

    private fun pressDpadEdge(
        activity: MainActivity,
        sourceName: String,
        keyCode: Int,
        expectedTab: MainTab,
        expectedFocusName: String
    ) {
        val source = field(activity, sourceName)
        assertTrue("$sourceName must accept focus before key dispatch", source.requestFocus())
        assertTrue(
            "$sourceName should handle key $keyCode",
            source.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0))
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val overview = field(activity, "overviewPage")
        val practice = field(activity, "practicePage")
        val system = field(activity, "systemPage")
        assertEquals(expectedTab, activeTab(activity))
        assertEquals(if (expectedTab == MainTab.OVERVIEW) View.VISIBLE else View.GONE, overview.visibility)
        assertEquals(if (expectedTab == MainTab.PRACTICE) View.VISIBLE else View.GONE, practice.visibility)
        assertEquals(if (expectedTab == MainTab.SYSTEM) View.VISIBLE else View.GONE, system.visibility)

        val expectedTarget = field(activity, expectedFocusName)
        val expectedPage = when (expectedTab) {
            MainTab.OVERVIEW -> overview
            MainTab.PRACTICE -> practice
            MainTab.SYSTEM -> system
        }
        assertFalse("$sourceName should lose focus after switching to $expectedTab", source.isFocused)
        assertTrue("$expectedFocusName should receive focus after switching to $expectedTab", expectedTarget.isFocused)
        assertTrue("$expectedTab page should hold focus", expectedPage.hasFocus())
        assertSame(expectedTarget, expectedPage.findFocus())
        if (expectedPage !== overview) {
            assertFalse("OVERVIEW page must not hold focus when active tab is $expectedTab", overview.hasFocus())
        }
        if (expectedPage !== practice) {
            assertFalse("PRACTICE page must not hold focus when active tab is $expectedTab", practice.hasFocus())
        }
        if (expectedPage !== system) {
            assertFalse("SYSTEM page must not hold focus when active tab is $expectedTab", system.hasFocus())
        }
        assertFalse(field(activity, "overviewTabButton").isFocused)
        assertFalse(field(activity, "practiceTabButton").isFocused)
        assertFalse(field(activity, "systemTabButton").isFocused)
    }

    @Test fun `wide layout wires cross-page dpad edge jumps and keeps page edges locked`() {
        val (_, activity) = activity()
        runInKeyMode(activity) {
            val musicButton = field(activity, "musicButton")
            val quickHistoryButton = field(activity, "quickHistoryButton")
            val quickThemeButton = field(activity, "quickThemeButton")
            val haTabButton = field(activity, "haTabButton")
            val tokenTabButton = field(activity, "tokenTabButton")
            val imageTestButton = field(activity, "imageTestButton")
            val ttsTestButton = field(activity, "ttsTestButton")
            val historyButton = field(activity, "historyButton")
            val muteButton = field(activity, "muteButton")
            val resetButton = field(activity, "resetButton")
            val aboutButton = field(activity, "aboutButton")

            assertEquals(quickHistoryButton.id, musicButton.nextFocusRightId)
            assertEquals(musicButton.id, quickHistoryButton.nextFocusLeftId)
            assertEquals(historyButton.id, imageTestButton.nextFocusRightId)
            assertEquals(imageTestButton.id, historyButton.nextFocusLeftId)
            assertEquals(muteButton.id, ttsTestButton.nextFocusRightId)
            assertEquals(ttsTestButton.id, muteButton.nextFocusLeftId)
            assertEquals(haTabButton.id, resetButton.nextFocusRightId)
            assertEquals(haTabButton.id, aboutButton.nextFocusRightId)
            assertEquals(resetButton.id, haTabButton.nextFocusLeftId)

            assertEquals(musicButton.id, musicButton.nextFocusLeftId)
            assertEquals(tokenTabButton.id, tokenTabButton.nextFocusRightId)

            assertTrue(musicButton.requestFocus())
            assertFalse(musicButton.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0)))
            assertEquals(MainTab.OVERVIEW, activeTab(activity))

            assertTrue(quickThemeButton.requestFocus())
            assertFalse(quickThemeButton.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0)))
            assertEquals(MainTab.OVERVIEW, activeTab(activity))
            assertTrue(quickThemeButton.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1)))
            assertEquals(MainTab.OVERVIEW, activeTab(activity))
            assertTrue(quickThemeButton.isFocused)

            pressDpadEdge(activity, "quickThemeButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.PRACTICE, "testButton")
            pressDpadEdge(activity, "testButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.OVERVIEW, "quickThemeButton")

            selectTab(activity, MainTab.PRACTICE)
            pressDpadEdge(activity, "videoTestButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.OVERVIEW, "quickConnectButton")

            selectTab(activity, MainTab.PRACTICE)
            pressDpadEdge(activity, "positionButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.SYSTEM, "copyButton")
            pressDpadEdge(activity, "copyButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.PRACTICE, "positionButton")
            pressDpadEdge(activity, "themeButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.SYSTEM, "updateButton")
            pressDpadEdge(activity, "updateButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.PRACTICE, "themeButton")

            selectTab(activity, MainTab.SYSTEM)
            assertTrue(tokenTabButton.requestFocus())
            assertFalse(tokenTabButton.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0)))
            assertEquals(MainTab.SYSTEM, activeTab(activity))
        }
    }

    @Config(sdk = [23], qualifiers = "w400dp-h800dp-xhdpi")
    @Test fun `narrow layout jumps across stacked card edges between pages`() {
        val (_, activity) = activity()
        runInKeyMode(activity) {
            val quickThemeButton = field(activity, "quickThemeButton")
            val quickHistoryButton = field(activity, "quickHistoryButton")
            val imageTestButton = field(activity, "imageTestButton")
            val ttsTestButton = field(activity, "ttsTestButton")
            val historyButton = field(activity, "historyButton")
            val muteButton = field(activity, "muteButton")

            assertEquals(View.NO_ID, quickThemeButton.nextFocusRightId)
            assertEquals(quickHistoryButton.id, quickHistoryButton.nextFocusLeftId)
            assertEquals(View.NO_ID, imageTestButton.nextFocusRightId)
            assertEquals(View.NO_ID, ttsTestButton.nextFocusRightId)
            assertEquals(View.NO_ID, historyButton.nextFocusLeftId)
            assertEquals(View.NO_ID, muteButton.nextFocusLeftId)

            pressDpadEdge(activity, "quickThemeButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.PRACTICE, "testButton")
            pressDpadEdge(activity, "historyButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.OVERVIEW, "quickThemeButton")
            pressDpadEdge(activity, "quickConnectButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.PRACTICE, "videoTestButton")
            pressDpadEdge(activity, "muteButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.OVERVIEW, "quickConnectButton")

            selectTab(activity, MainTab.PRACTICE)
            pressDpadEdge(activity, "imageTestButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.SYSTEM, "copyButton")
            pressDpadEdge(activity, "copyButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.PRACTICE, "imageTestButton")
            pressDpadEdge(activity, "ttsTestButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.SYSTEM, "updateButton")
            pressDpadEdge(activity, "updateButton", KeyEvent.KEYCODE_DPAD_LEFT, MainTab.PRACTICE, "ttsTestButton")
            pressDpadEdge(activity, "positionButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.SYSTEM, "copyButton")

            selectTab(activity, MainTab.PRACTICE)
            pressDpadEdge(activity, "themeButton", KeyEvent.KEYCODE_DPAD_RIGHT, MainTab.SYSTEM, "updateButton")
        }
    }

    @Test fun `all tv vector icons draw cleanly and tv dashboard contains zero emojis`() {
        val (_, activity) = activity(Theme.XIUXIAN)
        MainActivity::class.java.getDeclaredMethod("updateStateAndQr", String::class.java, Boolean::class.java, Boolean::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(activity, "abc12345", true, true, null)

        // Every TvIcon must render onto a Canvas without throwing on API 23
        val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        for (icon in TvIcon.entries) {
            val drawable = TvVectorIconDrawable(activity, icon, Color.CYAN, 20f)
            drawable.setBounds(0, 0, 48, 48)
            drawable.draw(canvas)
        }

        // Verify zero emoji codepoints appear in any TextView/Button across all tabs
        fun containsEmoji(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp == 0xFE0F) return true
                i += Character.charCount(cp)
            }
            return false
        }
        fun checkNoEmoji(view: View) {
            if (view is android.widget.TextView) {
                val text = view.text?.toString().orEmpty()
                assertFalse("TV UI text must not contain emoji (renders as tofu on Android 6): '$text'", containsEmoji(text))
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) checkNoEmoji(view.getChildAt(i))
            }
        }
        checkNoEmoji(activity.findViewById(android.R.id.content))

        // Verify launch-to-player pref defaults false and persists cleanly
        assertFalse(Prefs.isLaunchToPlayer(activity))
        Prefs.setLaunchToPlayer(activity, true)
        assertTrue(Prefs.isLaunchToPlayer(activity))
        Prefs.setLaunchToPlayer(activity, false)
        assertFalse(Prefs.isLaunchToPlayer(activity))
    }

    @Test fun `fresh install lands on system tab once then opens overview, while configured upgrades skip to overview`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val rawPrefs = context.getSharedPreferences("tvnotify_prefs", android.content.Context.MODE_PRIVATE)

        // 1. Brand-new clean install: opens on SYSTEM ("互联与系统") first, then OVERVIEW ("首页") afterwards
        rawPrefs.edit().clear().commit()
        assertFalse(Prefs.isMainSetupShown(context))
        assertEquals(MainTab.SYSTEM, Prefs.resolveAndConsumeInitialMainTab(context))
        assertTrue(Prefs.isMainSetupShown(context))
        assertEquals(MainTab.OVERVIEW, Prefs.resolveAndConsumeInitialMainTab(context))

        // 2. Upgraded existing device that already saved token / server / theme: skips onboarding straight to OVERVIEW
        for (existingKey in listOf("access_token", "sendspin_server_url", "ui_theme", "sendspin_last_centered")) {
            rawPrefs.edit().clear().putString(existingKey, "configured").commit()
            assertEquals("Existing setup ($existingKey) should skip to OVERVIEW", MainTab.OVERVIEW, Prefs.resolveAndConsumeInitialMainTab(context))
            assertTrue(Prefs.isMainSetupShown(context))
        }
    }

    @Test fun `focus glow overlay tracks focused targets and draws cleanly across themes`() {
        for (theme in listOf(Theme.XIUXIAN, Theme.MINECRAFT)) {
            val (_, activity) = activity(theme)
            layout(activity, 1280)
            val overlay = field(activity, "focusOverlay") as TvFocusGlowOverlay
            val musicButton = field(activity, "musicButton") as Button
            val quickHistoryButton = field(activity, "quickHistoryButton") as Button

            overlay.measure(
                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            )
            overlay.layout(0, 0, 1280, 1080)

            runInKeyMode(activity) {
                assertTrue(musicButton.requestFocus())
                assertTrue(quickHistoryButton.requestFocus())
            }
            overlay.syncToCurrentFocus(animate = false)
            val bmp = android.graphics.Bitmap.createBitmap(1280, 1080, android.graphics.Bitmap.Config.ARGB_8888)
            overlay.draw(android.graphics.Canvas(bmp))
            overlay.cancelAnimations()
        }
    }
}
