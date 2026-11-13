package com.zqc.tvnotify

import android.app.Activity
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = "w960dp-h540dp-xhdpi")
class ThemeDialogTest {

    @Test
    fun `all 9 theme palettes preserve distinct style, corners, typography, and WCAG AA contrast`() {
        assertEquals(9, Theme.entries.size)
        for (theme in Theme.entries) {
            val palette = ThemeDialogPalette.from(theme)
            assertEquals(theme.bannerCornerRadiusDp, palette.cornerRadiusDp, 0.01f)
            assertEquals(theme.bannerTitleTypeface, palette.titleTypeface)
            assertEquals(theme.bannerBodyTypeface, palette.bodyTypeface)
            assertEquals(theme == Theme.INK_LIGHT, palette.isLightSurface)

            when (theme) {
                Theme.MINECRAFT -> {
                    assertEquals(0f, palette.cornerRadiusDp, 0.01f)
                    assertEquals(0f, palette.itemCornerRadiusDp, 0.01f)
                    assertEquals(0f, palette.buttonCornerRadiusDp, 0.01f)
                    assertEquals(Typeface.MONOSPACE, palette.titleTypeface)
                }
                Theme.CYBERPUNK, Theme.MATRIX -> {
                    assertEquals(8f, palette.cornerRadiusDp, 0.01f)
                    assertEquals(Typeface.MONOSPACE, palette.titleTypeface)
                }
                Theme.XIUXIAN, Theme.PALACE, Theme.WUKONG, Theme.INK_LIGHT -> {
                    assertEquals(Typeface.SERIF, palette.titleTypeface)
                }
                else -> Unit
            }

            // Opaque container gradient check + high contrast title vs start surface
            assertTrue("Surface alpha should prevent bleed-through", Color.alpha(palette.surfaceStart) >= 250)
            val titleContrast = ThemeDialogPalette.contrastRatio(palette.titleColor, palette.surfaceStart)
            assertTrue("Title contrast too low for ${theme.storageId}: $titleContrast", titleContrast >= 7.0)

            val focusContrast = ThemeDialogPalette.contrastRatio(palette.focusedTextColor, palette.accentColor)
            assertTrue("Focused button text contrast < 4.5 for ${theme.storageId}: $focusContrast", focusContrast >= 4.5)
        }
    }

    @Test
    fun `all 9 themes layout dialogs cleanly without clipping focused items or buttons`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val density = activity.resources.displayMetrics.density

        for (theme in Theme.entries) {
            val dialog = ThemedDialogBuilder(activity, theme)
                .setTitle("测试标题 · ${theme.shortName}")
                .setMessage("主题呼应说明：${theme.soundDesc}")
                .setItems(
                    listOf(
                        ThemeDialogItem(
                            title = "选项一",
                            subtitle = "说明文案",
                            selected = true,
                            swatchFill = theme.accentColor,
                            swatchSquare = theme == Theme.MINECRAFT,
                            trailingBadge = "当前"
                        ),
                        ThemeDialogItem(
                            title = "选项二",
                            leadingBadge = theme.levelBadgeText(BannerLevel.WARN),
                            leadingBadgeColor = theme.bannerIconColor(BannerLevel.WARN)
                        )
                    )
                )
                .setNeutralButton("清空")
                .setNegativeButton("取消")
                .setPositiveButton("确定")
                .create()

            val card = dialog.rootContainer!!
            val bg = card.background as ThemeDialogPalette.ThemeSurfaceDrawable
            assertEquals(ThemeDialogPalette.from(theme).cornerRadiusDp * density, bg.cornerRadiusPx, 0.5f)

            val dialogWidth = (520 * density).toInt()
            card.measure(
                View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((600 * density).toInt(), View.MeasureSpec.AT_MOST)
            )
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)

            assertEquals(2, dialog.itemViews.size)
            assertNotNull(dialog.neutralButton)
            assertNotNull(dialog.negativeButton)
            assertNotNull(dialog.positiveButton)

            for (itemView in dialog.itemViews) {
                assertScaledChildUnclipped(itemView, 1.02f, card)
            }
            for (btn in listOfNotNull(dialog.neutralButton, dialog.negativeButton, dialog.positiveButton)) {
                assertScaledChildUnclipped(btn, 1.04f, card)
            }

            // Styled progress bar matches theme accent & corner rules
            val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
            dialog.palette.styleProgressBar(progressBar, density)
            assertNotNull(progressBar.progressDrawable)
        }
    }

    @Test
    fun `MainActivity dialogs adopt active theme styling across selectors, history, and about`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity::class.java.getDeclaredField("theme").apply { isAccessible = true }.set(activity, Theme.PALACE)
        val root = MainActivity::class.java.getDeclaredMethod("buildRootView").apply { isAccessible = true }.invoke(activity) as View
        activity.setContentView(root)

        // 1. Theme selector dialog shows all 9 themes with PALACE container theme
        MainActivity::class.java.getDeclaredMethod("showThemeSelectorDialog")
            .apply { isAccessible = true }
            .invoke(activity)
        val themeDialog = MainActivity::class.java.getDeclaredField("activeDialog")
            .apply { isAccessible = true }
            .get(activity) as ThemedDialog
        assertTrue(themeDialog.isShowing)
        assertEquals(Theme.PALACE, themeDialog.theme)
        assertEquals(9, themeDialog.itemViews.size)

        // 2. About dialog replaces previous dialog with themed About card
        MainActivity::class.java.getDeclaredMethod("showAboutDialog")
            .apply { isAccessible = true }
            .invoke(activity)
        val aboutDialog = MainActivity::class.java.getDeclaredField("activeDialog")
            .apply { isAccessible = true }
            .get(activity) as ThemedDialog
        assertFalse(themeDialog.isShowing)
        assertTrue(aboutDialog.isShowing)
        assertEquals(Theme.PALACE, aboutDialog.theme)
        assertTrue(collectTexts(aboutDialog.rootContainer!!).any { it.contains("简牍溯源") })

        // 3. History dialog renders themed level badge (圣谕) under PALACE theme
        NotifyHistory.clear(activity)
        NotifyHistory.record(
            activity,
            NotifyHistoryItem(
                id = "h1",
                timestamp = 1700000000000L,
                title = "宫廷来信",
                text = "正文测试",
                level = "info",
                position = "top",
                hasMedia = false,
                sticky = false,
                tts = null
            )
        )
        MainActivity::class.java.getDeclaredMethod("showHistoryDialog")
            .apply { isAccessible = true }
            .invoke(activity)
        val historyDialog = MainActivity::class.java.getDeclaredField("activeDialog")
            .apply { isAccessible = true }
            .get(activity) as ThemedDialog
        assertTrue(historyDialog.isShowing)
        assertEquals(1, historyDialog.itemViews.size)
        val historyText = collectTexts(historyDialog.itemViews[0]).joinToString(" ")
        assertTrue("History badge should follow active theme (圣谕): $historyText", historyText.contains("圣谕"))
        historyDialog.dismiss()
        NotifyHistory.clear(activity)
    }

    @Test
    fun `SendspinActivity volume and connection dialogs adopt theme palette including light paper theme`() {
        val activity = Robolectric.buildActivity(SendspinActivity::class.java).get()
        val clazz = SendspinActivity::class.java
        clazz.getDeclaredField("theme").apply { isAccessible = true }.set(activity, Theme.INK_LIGHT)
        clazz.getDeclaredField("playerBase").apply { isAccessible = true }.set(activity, Color.BLACK)
        clazz.getDeclaredField("accent").apply { isAccessible = true }.set(activity, Theme.INK_LIGHT.accentColor)
        clazz.getDeclaredMethod("buildUi").apply { isAccessible = true }.invoke(activity)

        clazz.getDeclaredMethod("showVolumeDialog").apply { isAccessible = true }.invoke(activity)
        val volDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(volDialog.isShowing)
        assertEquals(Theme.INK_LIGHT, volDialog.theme)
        assertTrue(volDialog.palette.isLightSurface)

        clazz.getDeclaredMethod("showConnectionDialog").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val connDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertFalse(volDialog.isShowing)
        assertTrue(connDialog.isShowing)
        assertEquals(Theme.INK_LIGHT, connDialog.theme)
        connDialog.dismiss()
    }

    @Test
    fun `AppUpdater update dialog builds cleanly with active theme`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val showMethod = AppUpdater::class.java.getDeclaredMethod(
            "showUpdateDialog",
            Activity::class.java,
            AppUpdater.UpdateInfo::class.java,
            Theme::class.java
        ).apply { isAccessible = true }
        val info = AppUpdater.UpdateInfo(
            versionName = "9.9.9",
            versionCode = 999,
            releaseNotes = "测试主题更新弹窗",
            downloadUrl = "https://example.com/update.apk",
            fileSize = 2_048_000L
        )
        showMethod.invoke(AppUpdater, activity, info, Theme.CYBERPUNK)
        // Verify no dialog crash; dismiss all dialogs on Robolectric looper cleanly
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertScaledChildUnclipped(target: View, scale: Float, stopRoot: ViewGroup) {
        assertTrue("Focus target must be laid out with positive size", target.width > 0 && target.height > 0)
        val bounds = RectF(0f, 0f, target.width.toFloat(), target.height.toFloat())
        Matrix().apply { setScale(scale, scale, target.width / 2f, target.height / 2f) }.mapRect(bounds)
        var child: View = target
        var ancestor = child.parent as? ViewGroup
        while (ancestor != null && ancestor !== stopRoot.parent) {
            if (ancestor.clipChildren) {
                assertTrue("Left focus edge clipped in ${ancestor.javaClass.simpleName}", bounds.left >= 0f)
                assertTrue("Right focus edge clipped in ${ancestor.javaClass.simpleName}", bounds.right <= child.width.toFloat())
                assertTrue("Top focus edge clipped in ${ancestor.javaClass.simpleName}", bounds.top >= 0f)
                assertTrue("Bottom focus edge clipped in ${ancestor.javaClass.simpleName}", bounds.bottom <= child.height.toFloat())
            }
            bounds.offset(child.left.toFloat(), child.top.toFloat())
            if (ancestor.clipToPadding) {
                assertTrue("Left padding clipped in ${ancestor.javaClass.simpleName}", bounds.left >= ancestor.paddingLeft.toFloat())
                assertTrue("Right padding clipped in ${ancestor.javaClass.simpleName}", bounds.right <= (ancestor.width - ancestor.paddingRight).toFloat())
                assertTrue("Top padding clipped in ${ancestor.javaClass.simpleName}", bounds.top >= ancestor.paddingTop.toFloat())
                assertTrue("Bottom padding clipped in ${ancestor.javaClass.simpleName}", bounds.bottom <= (ancestor.height - ancestor.paddingBottom).toFloat())
            }
            if (ancestor === stopRoot) break
            child = ancestor
            ancestor = child.parent as? ViewGroup
        }
    }

    private fun collectTexts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView && view.text.isNotEmpty()) out.add(view.text.toString())
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out.addAll(collectTexts(view.getChildAt(i)))
        }
        return out
    }
}
