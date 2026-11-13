package com.zqc.tvnotify

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
                    MainTab.OVERVIEW -> listOf("overviewTabButton", "systemTabButton", "musicButton", "quickHistoryButton", "haTabButton", "tokenTabButton")
                    MainTab.PRACTICE -> listOf("testButton", "imageTestButton", "historyButton", "positionButton")
                    MainTab.SYSTEM -> listOf("copyButton", "resetButton", "updateButton", "aboutButton")
                }
                for (name in names) {
                    val button = field(activity, name) as Button
                    val row = button.parent as ViewGroup
                    assertTrue("Button is laid out: $name width=$width", button.width > 0)
                    val focusScale = if (name in listOf("overviewTabButton", "systemTabButton")) 1.03f else 1.04f
                    val bounds = RectF(0f, 0f, button.width.toFloat(), button.height.toFloat())
                    Matrix().apply { setScale(focusScale, focusScale, button.width / 2f, button.height / 2f) }.mapRect(bounds)
                    bounds.offset(button.left.toFloat(), button.top.toFloat())
                    if (row.clipChildren) {
                        val left = if (row.clipToPadding) row.paddingLeft.toFloat() else 0f
                        val right = if (row.clipToPadding) (row.width - row.paddingRight).toFloat() else row.width.toFloat()
                        assertTrue("Focused left edge clipped by row: $name width=$width", bounds.left >= left)
                        assertTrue("Focused right edge clipped by row: $name width=$width", bounds.right <= right)
                    }
                    // The first/last action must stay within the actual card, not merely draw over it.
                    assertTrue("Focused control leaves card on left: $name", bounds.left + row.left >= 0f)
                    assertTrue("Focused control leaves card on right: $name", bounds.right + row.left <= (row.parent as View).width)
                }
            }
        }
    }

    @Test fun `service status is informational and down navigation enters real controls`() {
        val (_, activity) = activity()
        val overview = field(activity, "overviewPage") as ViewGroup
        val leftColumn = overview.getChildAt(0) as LinearLayout
        val statusCard = leftColumn.getChildAt(0)
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

    @Test fun `quick test, practice test and copy buttons are not pre-selected by default`() {
        for (theme in listOf(Theme.XIUXIAN, Theme.DEFAULT, Theme.MINECRAFT)) {
            val (_, activity) = activity(theme)
            val pairs = listOf(
                Triple(MainTab.OVERVIEW, "quickTestButton", "musicButton"),
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
                assertEquals("$targetName should highlight only when focused", theme.accentColor, focusedSurface.fillColor)
                assertEquals(Color.WHITE, focusedSurface.strokeColor)
                targetDrawable.state = intArrayOf()
            }
        }
    }
}
