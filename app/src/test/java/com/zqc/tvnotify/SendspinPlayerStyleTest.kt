package com.zqc.tvnotify

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = "w960dp-h540dp-xhdpi")
class SendspinPlayerStyleTest {

    private fun buildPreparedSendspinActivity(theme: Theme = Theme.XIUXIAN): SendspinActivity {
        val activity = Robolectric.buildActivity(SendspinActivity::class.java).get()
        val clazz = SendspinActivity::class.java
        clazz.getDeclaredField("theme").apply { isAccessible = true }.set(activity, theme)
        val playerBase = playerBlend(Color.rgb(17, 20, 25), theme.windowBg, .25f)
        val accent = playerBlend(theme.accentColor, Color.rgb(192, 208, 210), .28f)
        clazz.getDeclaredField("playerBase").apply { isAccessible = true }.set(activity, playerBase)
        clazz.getDeclaredField("accent").apply { isAccessible = true }.set(activity, accent)
        val style = Prefs.getSendspinPlayerStyle(activity)
        clazz.getDeclaredField("playerStyle").apply { isAccessible = true }.set(activity, style)
        clazz.getDeclaredMethod("buildUi").apply { isAccessible = true }.invoke(activity)
        clazz.getDeclaredMethod("render", SendspinRuntime.Status::class.java)
            .apply { isAccessible = true }
            .invoke(activity, SendspinRuntime.Status())
        return activity
    }

    @Test
    fun `player style defaults to vinyl and persists switches cleanly`() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(Prefs.SendspinPlayerStyle.VINYL, Prefs.getSendspinPlayerStyle(context))
        assertEquals(Prefs.SendspinPlayerStyle.VINYL, Prefs.SendspinPlayerStyle.fromRaw(null))
        assertEquals(Prefs.SendspinPlayerStyle.VINYL, Prefs.SendspinPlayerStyle.fromRaw("unknown"))
        assertEquals(Prefs.SendspinPlayerStyle.COVER_BLUR, Prefs.SendspinPlayerStyle.fromRaw("cover_blur"))

        Prefs.setSendspinPlayerStyle(context, Prefs.SendspinPlayerStyle.COVER_BLUR)
        assertEquals(Prefs.SendspinPlayerStyle.COVER_BLUR, Prefs.getSendspinPlayerStyle(context))

        Prefs.setSendspinPlayerStyle(context, Prefs.SendspinPlayerStyle.VINYL)
        assertEquals(Prefs.SendspinPlayerStyle.VINYL, Prefs.getSendspinPlayerStyle(context))
    }

    @Test
    fun `blurred artwork backdrop preserves distinct album hue zones without flattening`() {
        val src = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val raw = IntArray(240 * 160)
        val leftColor = Color.rgb(20, 95, 210)
        val rightColor = Color.rgb(245, 115, 25)
        for (y in 0 until 160) {
            val row = y * 240
            for (x in 0 until 240) {
                raw[row + x] = if (x < 120) leftColor else rightColor
            }
        }
        src.setPixels(raw, 0, 240, 0, 0, 240, 160)

        val blurred = createBlurredArtworkBackdrop(src)
        assertNotNull(blurred)
        blurred!!
        assertEquals(96, blurred.width)
        assertEquals(96, blurred.height)

        val leftSample = blurred.getPixel(18, 48)
        val rightSample = blurred.getPixel(78, 48)
        val centerSample = blurred.getPixel(48, 48)

        // Left side stays blue-dominant, right side stays red/warm-dominant, and center blends across the seam
        assertTrue(
            "Blurred left should keep blue > red, got r=${Color.red(leftSample)} b=${Color.blue(leftSample)}",
            Color.blue(leftSample) > Color.red(leftSample) + 60
        )
        assertTrue(
            "Blurred right should keep red > blue, got r=${Color.red(rightSample)} b=${Color.blue(rightSample)}",
            Color.red(rightSample) > Color.blue(rightSample) + 60
        )
        assertTrue(
            "Center seam should soften between left and right instead of remaining a hard unblurred step",
            Color.red(centerSample) in (Color.red(leftSample) + 15) until Color.red(rightSample)
        )

        // Degenerate 1x1 artwork also blur-scales without throwing
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        assertNotNull(createBlurredArtworkBackdrop(tiny))
    }

    @Test
    fun `clicking artwork or backup settings item switches styles and updates focus targets`() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.setSendspinPlayerStyle(context, Prefs.SendspinPlayerStyle.VINYL)
        val activity = buildPreparedSendspinActivity()

        val switcher = SendspinActivity::class.java.getDeclaredField("artworkSwitcher")
            .apply { isAccessible = true }
            .get(activity) as SendspinArtworkSwitcher
        val back = SendspinActivity::class.java.getDeclaredField("back")
            .apply { isAccessible = true }
            .get(activity) as View
        val progress = SendspinActivity::class.java.getDeclaredField("progress")
            .apply { isAccessible = true }
            .get(activity) as View
        val ambient = SendspinActivity::class.java.getDeclaredField("ambient")
            .apply { isAccessible = true }
            .get(activity) as SendspinAmbientDrawable

        assertTrue(switcher.isFocusable)
        assertTrue(switcher.isClickable)
        assertEquals(switcher.id, back.nextFocusDownId)
        assertEquals(switcher.id, progress.nextFocusUpId)
        assertEquals(View.VISIBLE, switcher.vinylRecord.visibility)
        assertEquals(View.GONE, switcher.roundedCover.visibility)
        assertFalse(ambient.hasActiveBlurredBackdrop())

        // Simulate creating blurred art so Style 2 backdrop activates too
        val coverBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        ambient.setBlurredBitmap(createBlurredArtworkBackdrop(coverBmp))
        switcher.setArtwork(coverBmp)

        // Click vinyl disc -> switches to Style 2 (square cover + blurred art backdrop)
        assertTrue(switcher.performClick())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Prefs.SendspinPlayerStyle.COVER_BLUR, Prefs.getSendspinPlayerStyle(context))
        assertEquals(View.GONE, switcher.vinylRecord.visibility)
        assertEquals(View.VISIBLE, switcher.roundedCover.visibility)
        assertTrue(ambient.hasActiveBlurredBackdrop())

        // Click square cover -> switches back to Style 1 (vinyl disc)
        assertTrue(switcher.performClick())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Prefs.SendspinPlayerStyle.VINYL, Prefs.getSendspinPlayerStyle(context))
        assertEquals(View.VISIBLE, switcher.vinylRecord.visibility)
        assertEquals(View.GONE, switcher.roundedCover.visibility)
        assertFalse(ambient.hasActiveBlurredBackdrop())

        // Open settings dialog and verify backup entry explicitly explains cover/vinyl click toggle
        SendspinActivity::class.java.getDeclaredMethod("showSettingsDialog")
            .apply { isAccessible = true }
            .invoke(activity)
        val dialog = SendspinActivity::class.java.getDeclaredField("playerDialog")
            .apply { isAccessible = true }
            .get(activity) as ThemedDialog
        assertTrue(dialog.isShowing)

        val rowTexts = dialog.itemViews.map { collectAllTexts(it).joinToString(" | ") }
        val styleRowIndex = rowTexts.indexOfFirst { it.contains("播放页样式") }
        assertTrue("Settings dialog must expose player style backup entry", styleRowIndex >= 0)
        val styleText = rowTexts[styleRowIndex]
        assertTrue("Backup entry should note click-to-toggle cover/vinyl: $styleText",
            styleText.contains("点击") && styleText.contains("黑胶") && styleText.contains("方形封面"))

        // Clicking backup entry switches to Style 2 and closes settings dialog cleanly
        dialog.itemViews[styleRowIndex].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(Prefs.SendspinPlayerStyle.COVER_BLUR, Prefs.getSendspinPlayerStyle(context))
        assertEquals(View.VISIBLE, switcher.roundedCover.visibility)
    }

    private fun collectAllTexts(view: View): List<String> {
        val result = mutableListOf<String>()
        if (view is TextView && view.text.isNotEmpty()) {
            result.add(view.text.toString())
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                result.addAll(collectAllTexts(view.getChildAt(i)))
            }
        }
        return result
    }
}
