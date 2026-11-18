package com.zqc.tvnotify

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

    @Test
    fun `header hosts settings and controls bar hosts playlist button with complete focus chain`() {
        val activity = buildPreparedSendspinActivity()
        val clazz = SendspinActivity::class.java
        val header = clazz.getDeclaredField("header").apply { isAccessible = true }.get(activity) as ViewGroup
        val controls = clazz.getDeclaredField("controls").apply { isAccessible = true }.get(activity) as ViewGroup
        val back = clazz.getDeclaredField("back").apply { isAccessible = true }.get(activity) as View
        val settings = clazz.getDeclaredField("settings").apply { isAccessible = true }.get(activity) as View
        val volumeControl = clazz.getDeclaredField("volumeControl").apply { isAccessible = true }.get(activity) as View
        val previous = clazz.getDeclaredField("previous").apply { isAccessible = true }.get(activity) as View
        val playPause = clazz.getDeclaredField("playPause").apply { isAccessible = true }.get(activity) as View
        val next = clazz.getDeclaredField("next").apply { isAccessible = true }.get(activity) as View
        val playlistButton = clazz.getDeclaredField("playlistButton").apply { isAccessible = true }.get(activity) as View

        assertTrue("Settings button must live in header", settings.parent === header)
        assertTrue("Playlist button must live in controls bar", playlistButton.parent === controls)
        assertEquals(settings.id, back.nextFocusRightId)
        assertEquals(back.id, settings.nextFocusLeftId)

        // Render connected state with controller-only volume & full transport controls
        val connected = SendspinRuntime.Status(
            state = "connected",
            title = "晴天",
            artist = "周杰伦",
            volume = 64,
            controllerCommands = setOf("play", "pause", "next", "previous", "volume", "mute"),
            playerCommands = emptySet()
        )
        clazz.getDeclaredMethod("render", SendspinRuntime.Status::class.java)
            .apply { isAccessible = true }
            .invoke(activity, connected)

        assertTrue("Controller volume command must enable TV volume button", volumeControl.isEnabled)
        assertEquals(previous.id, volumeControl.nextFocusRightId)
        assertEquals(playPause.id, previous.nextFocusRightId)
        assertEquals(next.id, playPause.nextFocusRightId)
        assertEquals(playlistButton.id, next.nextFocusRightId)
        assertEquals(next.id, playlistButton.nextFocusLeftId)
    }

    @Test
    fun `clicking playlist button opens current playlist track detail and highlights active song`() {
        PlaylistStore.update(null)
        PlaylistStore.markActivePlaylist(null)
        val catalog = PlaylistCatalog.parse(
            """
            {
              "callback": {"url": "http://192.168.1.10:8123/api/yujian/play", "token": "tok"},
              "playlists": [
                {
                  "id": "library://playlist/1",
                  "name": "华语精选",
                  "tracks": [
                    {"id": "library://track/1", "name": "稻香", "artist": "周杰伦"},
                    {"id": "library://track/2", "name": "晴天", "artist": "周杰伦"}
                  ]
                },
                {
                  "id": "library://playlist/2",
                  "name": "轻音乐",
                  "tracks": []
                }
              ]
            }
            """.trimIndent()
        )!!
        PlaylistStore.update(catalog)

        val activity = buildPreparedSendspinActivity()
        val clazz = SendspinActivity::class.java
        clazz.getDeclaredMethod("render", SendspinRuntime.Status::class.java)
            .apply { isAccessible = true }
            .invoke(activity, SendspinRuntime.Status(state = "connected", title = "晴天", artist = "周杰伦"))

        val playlistButton = clazz.getDeclaredField("playlistButton").apply { isAccessible = true }.get(activity) as View
        assertTrue(playlistButton.performClick())
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val dialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(dialog.isShowing)
        val rows = dialog.itemViews.map { collectAllTexts(it).joinToString(" | ") }
        assertTrue("Should offer switch playlist action: $rows", rows.any { it.contains("切换其他歌单") })
        assertTrue("Should offer play full playlist action: $rows", rows.any { it.contains("从头播放整张歌单") })
        assertTrue("Track 01 should appear with 点播 badge: $rows", rows.any { it.contains("01. 稻香") && it.contains("点播") })
        assertTrue("Playing track 02 should highlight with 正在播放 badge: $rows", rows.any { it.contains("02. 晴天") && it.contains("正在播放") })

        // Clicking 切换其他歌单 switches smoothly to all-playlists dialog; selecting empty-track playlist still opens detail cleanly
        dialog.itemViews[0].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val catalogDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(catalogDialog.isShowing)
        catalogDialog.itemViews[1].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val emptyDetailDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(emptyDetailDialog.isShowing)
        val emptyRows = emptyDetailDialog.itemViews.map { collectAllTexts(it).joinToString(" | ") }
        assertTrue("Playlist without tracks still supports whole-playlist play", emptyRows.any { it.contains("从头播放整张歌单") })
        emptyDetailDialog.dismiss()
        PlaylistStore.update(null)
        PlaylistStore.markActivePlaylist(null)
    }

    @Test
    fun `settings dialog exposes independent default-off WLED toggles and validates config inputs`() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(Prefs.isWledSyncEnabled(context))
        assertFalse(Prefs.isWledColorSyncEnabled(context))
        assertEquals(11988, Prefs.getWledSyncPort(context))
        assertEquals("192.168.101.206", Prefs.getWledColorHost(context))

        val activity = buildPreparedSendspinActivity()
        val clazz = SendspinActivity::class.java
        clazz.getDeclaredMethod("showSettingsDialog").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val settingsDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(settingsDialog.isShowing)
        val rows = settingsDialog.itemViews.map { collectAllTexts(it).joinToString(" | ") }
        val audioRowIdx = rows.indexOfFirst { it.contains("WLED 音乐律动") }
        val colorRowIdx = rows.indexOfFirst { it.contains("WLED 封面色彩同步") }
        val configRowIdx = rows.indexOfFirst { it.contains("WLED 参数设置") }
        assertTrue("Expected WLED audio toggle in settings: $rows", audioRowIdx >= 0 && rows[audioRowIdx].contains("已关闭"))
        assertTrue("Expected WLED color toggle in settings: $rows", colorRowIdx >= 0 && rows[colorRowIdx].contains("已关闭"))
        assertTrue("Expected WLED config row in settings: $rows", configRowIdx >= 0)

        // Toggle audio sync ON cleanly
        settingsDialog.itemViews[audioRowIdx].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Prefs.isWledSyncEnabled(context))
        assertFalse(Prefs.isWledColorSyncEnabled(context))

        // Open settings again and toggle color sync ON independently
        clazz.getDeclaredMethod("showSettingsDialog").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val settingsDialog2 = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        settingsDialog2.itemViews[colorRowIdx].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Prefs.isWledColorSyncEnabled(context))

        // Open WLED config sub-dialog
        clazz.getDeclaredMethod("showSettingsDialog").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val settingsDialog3 = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        settingsDialog3.itemViews[configRowIdx].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val configDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(configDialog.isShowing)
        val inputs = collectEditTexts(configDialog.window!!.decorView)
        assertEquals(2, inputs.size)
        val portInput = inputs[0]
        val hostInput = inputs[1]
        assertEquals("11988", portInput.text.toString())
        assertEquals("192.168.101.206", hostInput.text.toString())

        // Invalid port stays open without overwriting stored port
        portInput.setText("70000")
        configDialog.positiveButton!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(configDialog.isShowing)
        assertEquals(11988, Prefs.getWledSyncPort(context))

        // Valid port + invalid host stays open without overwriting host
        portInput.setText("11999")
        hostInput.setText("ftp://bad/path")
        configDialog.positiveButton!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(configDialog.isShowing)
        assertEquals(11988, Prefs.getWledSyncPort(context))
        assertEquals("192.168.101.206", Prefs.getWledColorHost(context))

        // Valid port + host saves, normalizes, and dismisses cleanly
        hostInput.setText("http://192.168.101.210/")
        configDialog.positiveButton!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(configDialog.isShowing)
        assertEquals(11999, Prefs.getWledSyncPort(context))
        assertEquals("192.168.101.210", Prefs.getWledColorHost(context))

        // Re-open config dialog and verify neutral button ("恢复默认") restores defaults cleanly
        clazz.getDeclaredMethod("showWledConfigDialog").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val resetDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(resetDialog.isShowing)
        val resetInputs = collectEditTexts(resetDialog.window!!.decorView)
        assertEquals("11999", resetInputs[0].text.toString())
        assertEquals("192.168.101.210", resetInputs[1].text.toString())
        resetDialog.neutralButton!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(resetDialog.isShowing)
        assertEquals("11988", resetInputs[0].text.toString())
        assertEquals("192.168.101.206", resetInputs[1].text.toString())
        resetDialog.positiveButton!!.performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(11988, Prefs.getWledSyncPort(context))
        assertEquals("192.168.101.206", Prefs.getWledColorHost(context))

        // Open settings -> recommended WLED effects dialog and verify preset persistence
        clazz.getDeclaredMethod("showSettingsDialog").apply { isAccessible = true }.invoke(activity)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val settingsDialog4 = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        val effectRowIdx = settingsDialog4.itemViews
            .map { collectAllTexts(it).joinToString(" | ") }
            .indexOfFirst { it.contains("推荐 WLED 律动效果") }
        assertTrue("Expected recommended WLED effect row in settings", effectRowIdx >= 0)
        settingsDialog4.itemViews[effectRowIdx].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val effectsDialog = clazz.getDeclaredField("playerDialog").apply { isAccessible = true }.get(activity) as ThemedDialog
        assertTrue(effectsDialog.isShowing)
        val gravIdx = effectsDialog.itemViews
            .map { collectAllTexts(it).joinToString(" | ") }
            .indexOfFirst { it.contains("Gravcentric") }
        assertTrue("Expected Gravcentric preset in dialog", gravIdx >= 0)
        effectsDialog.itemViews[gravIdx].performClick()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("gravcentric", Prefs.getWledEffect(context))
    }

    @Test
    fun `playlist store persists catalog across cold restarts`() {
        val context = RuntimeEnvironment.getApplication()
        PlaylistStore.update(null)
        val rawJson = """
            {
              "version": 1,
              "callback": {"url": "http://192.168.101.75:8123/api/yujian/play", "token": "tok"},
              "playlists": [
                {"id": "library://playlist/7", "name": "深夜微光", "track_count": 12}
              ]
            }
        """.trimIndent()
        assertEquals(1, PlaylistStore.updateFromJson(context, rawJson))
        assertEquals("深夜微光", PlaylistStore.current()?.playlists?.firstOrNull()?.name)

        // Simulate process restart clearing in-memory catalog
        PlaylistStore.update(null)
        assertEquals(null, PlaylistStore.current())
        PlaylistStore.restoreIfEmpty(context)
        assertEquals(1, PlaylistStore.current()?.playlists?.size)
        assertEquals("深夜微光", PlaylistStore.current()?.playlists?.firstOrNull()?.name)
        PlaylistStore.update(null)
    }

    private fun collectEditTexts(view: View): List<EditText> {
        val result = mutableListOf<EditText>()
        if (view is EditText) {
            result.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                result.addAll(collectEditTexts(view.getChildAt(i)))
            }
        }
        return result
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
