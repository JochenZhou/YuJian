package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesMultiTimestampVariablePrecisionAndClampsNegativeOffset() {
        val raw = """
            [ti:青花瓷]
            [offset:-500]
            [00:00.20]前奏轻吟
            [00:15.5][01:15.50]天青色等烟雨 而我在等你
            [00:22.125]炊烟袅袅升起 隔江千万里
        """.trimIndent()

        val parsed = LrcParser.parse(raw, "lrclib")
        assertTrue(parsed.synced)
        assertEquals(4, parsed.lines.size)
        // [00:00.20] = 200ms, minus 500ms offset -> clamped to 0ms
        assertEquals(0L, parsed.lines[0].timeMs)
        assertEquals("前奏轻吟", parsed.lines[0].text)
        // [00:15.5] = 15500ms - 500ms = 15000ms
        assertEquals(15_000L, parsed.lines[1].timeMs)
        assertEquals("天青色等烟雨 而我在等你", parsed.lines[1].text)
        // [00:22.125] = 22125ms - 500ms = 21625ms
        assertEquals(21_625L, parsed.lines[2].timeMs)
        // [01:15.50] = 75500ms - 500ms = 75000ms
        assertEquals(75_000L, parsed.lines[3].timeMs)

        assertEquals(-1, LrcParser.findActiveLineIndex(emptyList(), 1_000L))
        assertEquals(-1, LrcParser.findActiveLineIndex(parsed.lines, -1L))
        assertEquals(0, LrcParser.findActiveLineIndex(parsed.lines, 14_999L))
        assertEquals(1, LrcParser.findActiveLineIndex(parsed.lines, 15_000L))
        assertEquals(2, LrcParser.findActiveLineIndex(parsed.lines, 50_000L))
        assertEquals(3, LrcParser.findActiveLineIndex(parsed.lines, 90_000L))
    }

    @Test
    fun fallsBackToPlainUnsyncedLinesWhenNoTimestampsExist() {
        val parsed = LrcParser.parse("第一行纯文本\n\n第二行纯文本", "plain")
        assertFalse(parsed.synced)
        assertEquals(2, parsed.lines.size)
        assertEquals("第一行纯文本", parsed.lines[0].text)
        assertEquals("第二行纯文本", parsed.lines[1].text)
    }
}
