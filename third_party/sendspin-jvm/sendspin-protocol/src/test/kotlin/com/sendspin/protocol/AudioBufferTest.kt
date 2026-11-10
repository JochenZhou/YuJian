package com.sendspin.protocol

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioBufferTest {

    private lateinit var clockSync: ClockSync
    private lateinit var buffer: AudioBuffer

    @Before
    fun setUp() {
        clockSync = mockk()
        // Legacy timing tests run against a seeded clock with an identity offset.
        every { clockSync.isReady } returns true
        every { clockSync.toLocalMicros(any(), any()) } answers { firstArg() }
        buffer = AudioBuffer(clockSync, capacity = 8)
    }

    // ── Basic ordering ────────────────────────────────────────────────────────

    @Test
    fun `chunks are returned in timestamp order`() {
        val now = System.nanoTime() / 1_000L
        buffer.offer(chunk(now + 3_000_000))
        buffer.offer(chunk(now + 1_000_000))
        buffer.offer(chunk(now + 2_000_000))

        val c1 = buffer.poll(now + 1_500_000)
        val c2 = buffer.poll(now + 2_500_000)
        val c3 = buffer.poll(now + 3_500_000)

        assertNotNull(c1); assertEquals(now + 1_000_000, c1!!.serverTimestampMicros)
        assertNotNull(c2); assertEquals(now + 2_000_000, c2!!.serverTimestampMicros)
        assertNotNull(c3); assertEquals(now + 3_000_000, c3!!.serverTimestampMicros)
    }

    @Test
    fun `poll returns null when no chunk is ready yet`() {
        val now = System.nanoTime() / 1_000L
        buffer.offer(chunk(now + 5_000_000)) // 5 s in the future
        assertNull(buffer.poll(now))
    }

    @Test
    fun `poll returns chunk when its time has passed`() {
        val now = System.nanoTime() / 1_000L
        buffer.offer(chunk(now - 100_000)) // 100 ms in the past — still within threshold
        assertNotNull(buffer.poll(now))
    }

    // ── Late chunk dropping ───────────────────────────────────────────────────

    @Test
    fun `chunks older than 1s are dropped on offer`() {
        val now = System.nanoTime() / 1_000L
        // 1.1 s in the past → should be dropped
        buffer.offer(chunk(now - 1_100_000))

        assertEquals(0, buffer.size)
        assertEquals(1L, buffer.droppedChunks)
        assertEquals(1L, buffer.lateChunks)
    }

    @Test
    fun `chunks exactly at drop threshold are NOT dropped`() {
        val now = System.nanoTime() / 1_000L
        // 900 ms in the past → within threshold (1 s)
        every { clockSync.toLocalMicros(any()) } answers { firstArg<Long>() }
        buffer.offer(chunk(now - 900_000))
        assertEquals(1, buffer.size)
    }


    // ── Capacity ──────────────────────────────────────────────────────────────

    @Test
    fun `buffer evicts oldest when full`() {
        val now = System.nanoTime() / 1_000L + 10_000_000L // future
        repeat(9) { i ->
            buffer.offer(chunk(now + i * 1_000_000L))
        }
        // capacity=8, last offer should evict the first chunk
        assertEquals(8, buffer.size)
        assertEquals(1L, buffer.droppedChunks)
    }

    // ── Flush ─────────────────────────────────────────────────────────────────

    @Test
    fun `flush empties the buffer`() {
        val now = System.nanoTime() / 1_000L + 5_000_000L
        repeat(4) { buffer.offer(chunk(now + it * 1_000_000L)) }
        assertEquals(4, buffer.size)
        buffer.flush()
        assertEquals(0, buffer.size)
    }

    @Test
    fun `poll returns null after flush`() {
        val now = System.nanoTime() / 1_000L
        buffer.offer(chunk(now - 100_000))
        buffer.flush()
        assertNull(buffer.poll(now))
    }

    // ── nextChunkDelayMicros ──────────────────────────────────────────────────

    @Test
    fun `nextChunkDelayMicros returns null when empty`() {
        assertNull(buffer.nextChunkDelayMicros())
    }

    @Test
    fun `nextChunkDelayMicros returns delay for future chunk`() {
        val now = System.nanoTime() / 1_000L
        val futureTs = now + 2_000_000L
        buffer.offer(chunk(futureTs))
        val delay = buffer.nextChunkDelayMicros(now)
        assertNotNull(delay)
        assertTrue("Expected delay ~2000000 µs but was $delay", delay!! in 1_500_000L..2_100_000L)
    }

    // ── Underrun ─────────────────────────────────────────────────────────────

    @Test
    fun `underrunState is false initially`() {
        assertTrue(!buffer.underrunState.value)
    }

    @Test
    fun `signalUnderrun sets underrunState to true`() {
        buffer.signalUnderrun()
        assertTrue(buffer.underrunState.value)
    }

    @Test
    fun `offering chunk after underrun clears underrunState`() {
        buffer.signalUnderrun()
        val now = System.nanoTime() / 1_000L + 5_000_000L
        buffer.offer(chunk(now))
        assertTrue(!buffer.underrunState.value)
    }

    // ── Deferred conversion (live schedule recomputation) ────────────────────

    @Test
    fun `clock correction after offer immediately reshapes schedule of queued chunks`() {
        val now = System.nanoTime() / 1_000L
        // Initially identity: chunk scheduled for now + 2s
        buffer.offer(chunk(now + 2_000_000L))
        assertNull(buffer.poll(now)) // not ready yet under the original estimate

        // Simulate a clock correction: offset jumps by -1.5s, so the same server timestamp
        // now maps to now + 0.5s instead of now + 2s.
        every { clockSync.toLocalMicros(any(), any()) } answers { firstArg<Long>() - 1_500_000L }

        // The already-queued chunk's effective schedule should reflect the new estimate
        // immediately — not the stale value baked in at offer() time.
        assertNull(buffer.poll(now)) // still not ready: now + 0.5s > now
        assertNotNull(buffer.poll(now + 500_000L))
    }

    // ── Output delay ─────────────────────────────────────────────────────────

    @Test
    fun `outputDelayMicros shifts scheduled play time earlier`() {
        val now = System.nanoTime() / 1_000L
        buffer.setOutputDelayMicros(200_000L) // 200 ms

        // Server timestamp 300 ms in the future; with 200 ms delay, local time = now+100 ms
        buffer.offer(chunk(now + 300_000L))

        // Should not be ready at now (local time is now+100 ms)
        assertNull(buffer.poll(now))
        // Should be ready at now+100 ms
        assertNotNull(buffer.poll(now + 100_000L))
    }

    @Test
    fun `zero outputDelayMicros does not shift playback time`() {
        val now = System.nanoTime() / 1_000L
        buffer.setOutputDelayMicros(0L)

        buffer.offer(chunk(now + 300_000L))
        assertNull(buffer.poll(now + 100_000L))
        assertNotNull(buffer.poll(now + 300_000L))
    }

    @Test
    fun `peek leaves the earliest chunk queued and take ignores the playback window`() {
        val now = ClockSync.localMicros()
        val near = chunk(now + 2_000_000L)
        val far = chunk(now + 20_000_000L)
        buffer.offer(far)
        buffer.offer(near)

        assertSame(near, buffer.peek())
        assertSame(near, buffer.peek())
        assertEquals(2, buffer.size)
        assertSame(near, buffer.take())
        // A stalled controller can inspect and trim a chunk itself instead of losing it to poll().
        assertNull(buffer.poll(now + 25_000_000L))
        assertEquals(1L, buffer.lateChunks)
    }

    @Test
    fun `take preserves large timestamp gaps and does not discard stale chunks`() {
        val now = ClockSync.localMicros()
        val first = chunk(now + 1_000_000L)
        val next = chunk(now + 20_000_000L)
        buffer.offer(next)
        buffer.offer(first)

        assertSame(first, buffer.take())
        assertSame(next, buffer.take())
        assertNull(buffer.peek())
        assertNull(buffer.take())
        assertEquals(0L, buffer.droppedChunks)
    }

    @Test
    fun `before clock readiness chunks with unrelated server epoch remain bounded and ordered`() {
        val unseeded = ClockSync()
        val waiting = AudioBuffer(unseeded, capacity = 2)
        val first = chunk(100L)
        val second = chunk(200L)
        waiting.offer(second)
        waiting.offer(first)
        waiting.offer(chunk(300L))

        assertEquals(2, waiting.size)
        assertEquals(1L, waiting.droppedChunks)
        assertEquals(0L, waiting.lateChunks)
        assertSame(first, waiting.peek())
        assertNull(waiting.poll())
        assertNull(waiting.nextChunkDelayMicros())
        waiting.dropChunksOlderThan(Long.MAX_VALUE)
        assertEquals(2, waiting.size)
        assertSame(first, waiting.take())
        assertEquals(300L, waiting.take()!!.serverTimestampMicros)
    }

    @Test
    fun `automatic and manual zero are distinguishable without discarding network chunks`() {
        val now = ClockSync.localMicros()
        buffer.offer(chunk(now + 1_000_000L))
        assertTrue(buffer.automaticSync)
        buffer.setAutomaticSync(false)
        buffer.setOutputDelayMicros(0L)
        assertFalse(buffer.automaticSync)
        assertEquals(1, buffer.size)
        buffer.setAutomaticSync(true)
        assertTrue(buffer.automaticSync)
        assertEquals(1, buffer.size)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun chunk(serverTimestampMicros: Long) =
        AudioChunk(serverTimestampMicros, ByteArray(64) { 0 })
}
