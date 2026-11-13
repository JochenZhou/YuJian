package com.zqc.tvnotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun fallsBackAcrossSourcesUpgradesPlainToSyncedAndRespectsCancellation() {
        val invoked = ArrayList<String>()
        val syncedUpgradeSources = listOf(
            LyricSourceSpec("lrclib") { _, _, _, _, _ ->
                invoked.add("lrclib")
                "第一行纯文本\n第二行纯文本"
            },
            LyricSourceSpec("qq") { _, _, _, _, _ ->
                invoked.add("qq")
                null
            },
            LyricSourceSpec("kugou") { _, _, _, _, _ ->
                invoked.add("kugou")
                "[00:05.00]素胚勾勒出青花笔锋浓转淡"
            },
            LyricSourceSpec("netease") { _, _, _, _, _ ->
                invoked.add("netease")
                "[00:05.00]不应触发"
            }
        )
        val upgraded = LyricsFetcher.resolveFromProviders("青花瓷", "周杰伦", "", 239_000L, syncedUpgradeSources)
        assertNotNull(upgraded)
        assertEquals("kugou", upgraded!!.source)
        assertEquals(listOf("lrclib", "qq", "kugou"), invoked)

        invoked.clear()
        val plainOnlyFallbackSources = listOf(
            LyricSourceSpec("lrclib") { _, _, _, _, _ ->
                invoked.add("lrclib")
                "Pure text line 1\nPure text line 2"
            },
            LyricSourceSpec("qq") { _, _, _, _, _ ->
                invoked.add("qq")
                null
            },
            LyricSourceSpec("lyrics_ovh", plainOnly = true) { _, _, _, _, _ ->
                invoked.add("lyrics_ovh")
                "Should skip when plain fallback already exists"
            }
        )
        val plainOutcome = LyricsFetcher.resolveFromProviders("Track", "Artist", "", 180_000L, plainOnlyFallbackSources)
        assertNotNull(plainOutcome)
        assertEquals("plain", plainOutcome!!.source)
        assertEquals(listOf("lrclib", "qq"), invoked)

        invoked.clear()
        val cancelled = AtomicBoolean(false)
        val cancelSources = listOf(
            LyricSourceSpec("lrclib") { _, _, _, _, _ ->
                invoked.add("lrclib")
                cancelled.set(true)
                null
            },
            LyricSourceSpec("qq") { _, _, _, _, _ ->
                invoked.add("qq")
                "[00:01.00]Skipped"
            }
        )
        val aborted = LyricsFetcher.resolveFromProviders("Track", "Artist", "", 180_000L, cancelSources) { cancelled.get() }
        assertNull(aborted)
        assertEquals(listOf("lrclib"), invoked)
    }

    @Test
    fun filtersInstrumentalAndPlaceholderLyricsAcrossProviders() {
        assertNull(LyricsFetcher.parseLrclibGetResponse("""{"instrumental":true,"syncedLyrics":"[00:01.00]Hum"}"""))
        assertNull(LyricsFetcher.parseLrclibGetResponse("""{"instrumental":false,"plainLyrics":null,"syncedLyrics":null}"""))
        assertNull(
            LyricsFetcher.parseNeteaseLyricResponse(
                """{"pureMusic":true,"lrc":{"lyric":"[00:00.00] 作曲 : Yiruma\n[00:05.00]纯音乐，请欣赏\n"}}"""
            )
        )
        assertNull(
            LyricsFetcher.parseNeteaseLyricResponse(
                """{"nolyric":true,"lrc":{"lyric":"[00:00.00] 纯音乐，请欣赏"}}"""
            )
        )
        assertNull(
            LyricsFetcher.extractUsableLyrics("[00:00.00]River Flows In You - Yiruma\n[00:05.00]纯音乐，请欣赏")
        )
    }

    @Test
    fun parsesProviderSearchMatchesBase64BomAndHtmlEntities() {
        val qqSearchJson = """
            {
              "code": 0,
              "data": {
                "song": {
                  "list": [
                    {"songmid": "wrong_dur", "songname": "青花瓷", "interval": 95, "singer": [{"name": "周杰伦"}]},
                    {"songmid": "wrong_art", "songname": "青花瓷", "interval": 239, "singer": [{"name": "上海音乐出版社"}]},
                    {"songmid": "002qU5aY3Qu24y", "songname": "青花瓷", "interval": 239, "singer": [{"name": "周杰伦"}]}
                  ]
                }
              }
            }
        """.trimIndent()
        assertEquals(listOf("002qU5aY3Qu24y"), LyricsFetcher.selectQqCandidateMids(qqSearchJson, "青花瓷", "周杰伦", 239L))

        val qqLyricJson = """{"retcode":0,"code":0,"lyric":"[00:12.00]Don&apos;t stop &quot;believing&quot; &amp; shine"}"""
        val qqLrc = LyricsFetcher.parseQqLyricResponse(qqLyricJson)
        assertEquals("[00:12.00]Don't stop \"believing\" & shine", qqLrc)

        val kugouSearchJson = """
            {
              "status": 200,
              "candidates": [
                {"id": "clip", "accesskey": "k0", "song": "青花瓷", "singer": "周杰伦", "duration": 33000},
                {"id": "52241228", "accesskey": "KEY123", "song": "青花瓷", "singer": "周杰伦", "duration": 239000}
              ]
            }
        """.trimIndent()
        assertEquals(
            listOf("52241228" to "KEY123"),
            LyricsFetcher.selectKugouCandidates(kugouSearchJson, "青花瓷", "周杰伦", 239L)
        )

        val kugouDownloadJson = """
            {"status":200,"content":"77u/W2lkOiQwMDAwMDAwMF0NClt0aTrpnZLoirHnk7ddDQpbMDA6MjEuNTVd57Sg6IOa5Yu+5YuS5Ye66Z2S6Iqx56yU6ZSL5rWT6L2s5rehDQo="}
        """.trimIndent()
        val kugouDecoded = LyricsFetcher.parseKugouDownloadResponse(kugouDownloadJson)
        assertNotNull(kugouDecoded)
        val parsedKugou = LrcParser.parse(kugouDecoded!!, "kugou")
        assertTrue(parsedKugou.synced)
        assertEquals(1, parsedKugou.lines.size)
        assertEquals(21_550L, parsedKugou.lines[0].timeMs)
        assertEquals("素胚勾勒出青花笔锋浓转淡", parsedKugou.lines[0].text)

        val neteaseSearchJson = """
            {
              "result": {
                "songs": [
                  {"id": 1978148402, "name": "青花瓷", "duration": 236199, "artists": [{"name": "上海音乐出版社"}]},
                  {"id": 3381543730, "name": "青花瓷（正式版）", "duration": 95037, "artists": [{"name": "周杰伦."}]},
                  {"id": 66842, "name": "十年", "duration": 205423, "artists": [{"name": "陈奕迅"}]}
                ]
              }
            }
        """.trimIndent()
        assertTrue(LyricsFetcher.selectNeteaseCandidateIds(neteaseSearchJson, "青花瓷", "周杰伦", 239L).isEmpty())
        assertEquals(listOf(66842L), LyricsFetcher.selectNeteaseCandidateIds(neteaseSearchJson, "十年", "陈奕迅", 205L))

        val ovhPlain = LyricsFetcher.parseLyricsOvhResponse("""{"lyrics":"Look at the stars\nLook how they shine"}""")
        assertNotNull(ovhPlain)
        assertFalse(LrcParser.parse(ovhPlain!!, "lyrics_ovh").synced)
    }
}
