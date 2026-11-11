package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun runsDiskCacheReadOnDaemonExecutorOffCallingThreadAndSkipsStaleNetworkFetch() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "yujian-lrc-test-${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val callerThread = Thread.currentThread()
            val providerCalledOnCaller = AtomicBoolean(false)
            val callbackThread = AtomicReference<Thread>()
            val resultRef = AtomicReference<ParsedLyrics?>()
            val firstNetworkStarted = CountDownLatch(1)
            val allowFirstNetworkFinish = CountDownLatch(1)
            val secondDone = CountDownLatch(1)
            val networkCalls = AtomicInteger(0)

            // Queue Track 1 (slow network) so Track 2 and Track 3 queue behind it on the single executor
            LyricsFetcher.fetchWithCacheDirAsync(
                cacheRootProvider = {
                    if (Thread.currentThread() === callerThread) providerCalledOnCaller.set(true)
                    tempDir
                },
                title = "Track One",
                artist = "Artist",
                album = "",
                durationMs = 180_000L,
                networkFetcher = { _, _, _, _ ->
                    networkCalls.incrementAndGet()
                    firstNetworkStarted.countDown()
                    allowFirstNetworkFinish.await(3, TimeUnit.SECONDS)
                    null
                },
                onResult = {}
            )
            assertTrue(firstNetworkStarted.await(3, TimeUnit.SECONDS))

            // Track 2 should be superseded by Track 3 before executing its network call
            LyricsFetcher.fetchWithCacheDirAsync(
                cacheRootProvider = { tempDir },
                title = "Track Two",
                artist = "Artist",
                album = "",
                durationMs = 180_000L,
                networkFetcher = { _, _, _, _ ->
                    networkCalls.incrementAndGet()
                    "[00:01.00]Should skip"
                },
                onResult = {}
            )

            // Track 3 fetches and writes to disk cache on daemon executor
            LyricsFetcher.fetchWithCacheDirAsync(
                cacheRootProvider = {
                    if (Thread.currentThread() === callerThread) providerCalledOnCaller.set(true)
                    tempDir
                },
                title = "Track Three",
                artist = "Artist",
                album = "",
                durationMs = 180_000L,
                networkFetcher = { _, _, _, _ ->
                    networkCalls.incrementAndGet()
                    "[00:02.00]第三首歌词"
                },
                onResult = { parsed ->
                    callbackThread.set(Thread.currentThread())
                    resultRef.set(parsed)
                    secondDone.countDown()
                }
            )
            allowFirstNetworkFinish.countDown()
            assertTrue(secondDone.await(3, TimeUnit.SECONDS))
            assertFalse(providerCalledOnCaller.get())
            val thread = callbackThread.get()
            assertNotNull(thread)
            assertTrue(thread!!.isDaemon)
            assertEquals("sendspin-lyrics", thread.name)
            assertEquals("第三首歌词", resultRef.get()?.lines?.firstOrNull()?.text)
            assertEquals(2, networkCalls.get())

            // Re-fetching Track 3 should read from disk cache without calling networkFetcher, still off caller thread
            val cacheHitDone = CountDownLatch(1)
            val cacheCallbackThread = AtomicReference<Thread>()
            val cacheResult = AtomicReference<ParsedLyrics?>()
            LyricsFetcher.fetchWithCacheDirAsync(
                cacheRootProvider = {
                    if (Thread.currentThread() === callerThread) providerCalledOnCaller.set(true)
                    tempDir
                },
                title = "Track Three",
                artist = "Artist",
                album = "",
                durationMs = 180_000L,
                networkFetcher = { _, _, _, _ ->
                    networkCalls.incrementAndGet()
                    null
                },
                onResult = { parsed ->
                    cacheCallbackThread.set(Thread.currentThread())
                    cacheResult.set(parsed)
                    cacheHitDone.countDown()
                }
            )
            assertTrue(cacheHitDone.await(3, TimeUnit.SECONDS))
            assertFalse(providerCalledOnCaller.get())
            assertEquals("disk_cache", cacheResult.get()?.source)
            assertTrue(cacheCallbackThread.get()!!.isDaemon)
            assertEquals(2, networkCalls.get())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
