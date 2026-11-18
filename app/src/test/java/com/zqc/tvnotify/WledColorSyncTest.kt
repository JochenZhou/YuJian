package com.zqc.tvnotify

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class WledColorSyncTest {

    private val inlineExecutor = Executor { runnable -> runnable.run() }

    private fun buildSplitBitmap(
        width: Int = 32,
        height: Int = 32,
        topRatio: Double,
        topColor: Int,
        bottomColor: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val splitRow = (height * topRatio).toInt().coerceIn(0, height)
        for (y in 0 until height) {
            val color = if (y < splitRow) topColor else bottomColor
            for (x in 0 until width) {
                pixels[y * width + x] = color
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    @Test
    fun `color sync prefs default off with default host and reject invalid hosts`() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(Prefs.isWledColorSyncEnabled(context))
        assertEquals("192.168.101.206", Prefs.getWledColorHost(context))

        assertTrue(Prefs.setWledColorHost(context, "http://192.168.101.210:8080/"))
        assertEquals("192.168.101.210:8080", Prefs.getWledColorHost(context))

        assertFalse(Prefs.setWledColorHost(context, ""))
        assertFalse(Prefs.setWledColorHost(context, "   "))
        assertFalse(Prefs.setWledColorHost(context, "ftp://192.168.101.206"))
        assertFalse(Prefs.setWledColorHost(context, "192.168.101.206/json/state"))
        assertFalse(Prefs.setWledColorHost(context, "192.168.101.206:70000"))
        assertEquals("192.168.101.210:8080", Prefs.getWledColorHost(context))

        Prefs.setWledColorSyncEnabled(context, true)
        assertTrue(Prefs.isWledColorSyncEnabled(context))
        Prefs.setWledColorSyncEnabled(context, false)
        assertFalse(Prefs.isWledColorSyncEnabled(context))
    }

    @Test
    fun `extracts primary color when cover is colorful`() {
        val blue = Color.rgb(30, 136, 229)
        val amber = Color.rgb(255, 193, 7)
        val bitmap = buildSplitBitmap(topRatio = 0.72, topColor = blue, bottomColor = amber)

        val colors = WledArtworkColorExtractor.extract(bitmap)
        assertEquals(blue, colors.primaryRgb)
        assertEquals(amber, colors.secondaryRgb)
        assertEquals(blue, colors.selectedRgb)
    }

    @Test
    fun `falls back to secondary color when primary color is black or near black`() {
        val black = Color.rgb(0, 0, 0)
        val crimson = Color.rgb(229, 57, 53)
        val bitmap = buildSplitBitmap(topRatio = 0.75, topColor = black, bottomColor = crimson)

        val colors = WledArtworkColorExtractor.extract(bitmap)
        assertNotNull(colors.primaryRgb)
        assertTrue(WledArtworkColorExtractor.isDarkColor(colors.primaryRgb!!))
        assertEquals(crimson, colors.secondaryRgb)
        assertEquals(crimson, colors.selectedRgb)
    }

    @Test
    fun `skips all-black or near-black and transparent bitmaps cleanly`() {
        val allBlack = buildSplitBitmap(topRatio = 0.6, topColor = Color.rgb(0, 0, 0), bottomColor = Color.rgb(18, 18, 18))
        val darkColors = WledArtworkColorExtractor.extract(allBlack)
        assertNull(darkColors.selectedRgb)

        val transparent = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        transparent.eraseColor(Color.TRANSPARENT)
        val emptyColors = WledArtworkColorExtractor.extract(transparent)
        assertNull(emptyColors.primaryRgb)
        assertNull(emptyColors.selectedRgb)
    }

    @Test
    fun `builds partial seg0 primary color json without effect or brightness overrides`() {
        val body = WledColorSync.buildStateJson(Color.rgb(229, 57, 53))
        val json = JSONObject(body)
        assertFalse(json.has("on"))
        assertFalse(json.has("bri"))
        val seg = json.getJSONArray("seg").getJSONObject(0)
        assertEquals(0, seg.getInt("id"))
        assertFalse(seg.has("fx"))
        assertFalse(seg.has("pal"))
        val col0 = seg.getJSONArray("col").getJSONArray(0)
        assertEquals(229, col0.getInt(0))
        assertEquals(57, col0.getInt(1))
        assertEquals(53, col0.getInt(2))
    }

    @Test
    fun `color sync honors toggle suppresses duplicates and survives http errors`() {
        val calls = mutableListOf<Pair<String, String>>()
        var failNext = false
        val blueBitmap = buildSplitBitmap(topRatio = 0.7, topColor = Color.rgb(33, 150, 243), bottomColor = Color.rgb(255, 255, 255))
        val blackAndRedBitmap = buildSplitBitmap(topRatio = 0.8, topColor = Color.rgb(0, 0, 0), bottomColor = Color.rgb(244, 67, 54))
        val allBlackBitmap = buildSplitBitmap(topRatio = 1.0, topColor = Color.rgb(0, 0, 0), bottomColor = Color.rgb(0, 0, 0))

        var decodedBitmap: Bitmap? = blueBitmap
        val sync = WledColorSync(
            initialEnabled = false,
            initialHost = "192.168.101.206",
            poster = WledHttpPoster { url, body ->
                if (failNext) {
                    failNext = false
                    throw IOException("unreachable")
                }
                calls.add(url to body)
            },
            bitmapDecoder = { decodedBitmap },
            customExecutor = inlineExecutor,
            debounceMs = 0L
        )

        // Disabled: no HTTP request dispatched, but caches latest artwork.
        val bytes1 = byteArrayOf(1, 2, 3, 4)
        sync.onArtworkBytes(bytes1)
        assertEquals(0, calls.size)

        // Enabling pushes cached artwork immediately.
        sync.setEnabled(true)
        assertEquals(1, calls.size)
        assertEquals("http://192.168.101.206/json/state", calls[0].first)
        assertEquals("""{"seg":[{"id":0,"col":[[33,150,243]]}]}""", calls[0].second)

        // Duplicate identical bytes do not trigger repeat POST.
        sync.onArtworkBytes(byteArrayOf(1, 2, 3, 4))
        assertEquals(1, calls.size)

        // Black-primary cover pushes crimson secondary color.
        decodedBitmap = blackAndRedBitmap
        sync.onArtworkBytes(byteArrayOf(5, 6, 7, 8))
        assertEquals(2, calls.size)
        assertEquals("""{"seg":[{"id":0,"col":[[244,67,54]]}]}""", calls[1].second)

        // All-black cover skips update completely.
        decodedBitmap = allBlackBitmap
        sync.onArtworkBytes(byteArrayOf(9, 10, 11, 12))
        assertEquals(2, calls.size)

        // HTTP error does not crash; host switch pushes next valid color cleanly.
        failNext = true
        decodedBitmap = blueBitmap
        sync.onArtworkBytes(byteArrayOf(13, 14, 15, 16))
        assertEquals(2, calls.size)

        assertTrue(sync.setHost("192.168.101.222"))
        assertEquals(3, calls.size)
        assertEquals("http://192.168.101.222/json/state", calls[2].first)
        assertEquals("""{"seg":[{"id":0,"col":[[33,150,243]]}]}""", calls[2].second)

        sync.close()
    }
}
