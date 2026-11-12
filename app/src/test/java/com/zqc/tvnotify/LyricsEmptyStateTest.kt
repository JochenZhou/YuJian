package com.zqc.tvnotify

import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class LyricsEmptyStateTest {
    private fun drawnText(view: LyricsScrollView): String {
        view.layout(0, 0, 640, 480)
        val canvas = Canvas()
        view.draw(canvas)
        val shadow = shadowOf(canvas)
        return (0 until shadow.textHistoryCount).joinToString("\n") { shadow.getDrawnTextEvent(it).text }
    }

    @Test fun `missing lyrics say unavailable rather than classifying track as instrumental`() {
        val view = LyricsScrollView(RuntimeEnvironment.getApplication(), Color.CYAN)
        view.setLoading(false)
        view.setLyrics(null)
        assertEquals("暂无歌词", drawnText(view))
    }

    @Test fun `loading message remains distinct from an unsuccessful search`() {
        val view = LyricsScrollView(RuntimeEnvironment.getApplication(), Color.CYAN)
        view.setLoading(true)
        assertEquals("正在检索歌词…", drawnText(view))
        view.setLoading(false)
        assertEquals("暂无歌词", drawnText(view))
    }
}
