package com.sendspin.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

class SendSpinClientTest {
    private val clients = mutableListOf<SendSpinClient>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
    }

    @Test
    fun `missing mode migrates to automatic while retaining saved manual delay`() {
        val store = MemorySettingsStore(mutableMapOf(ClientSettingsKeys.OUTPUT_DELAY_MS to 175))
        val client = client(store)

        assertTrue(client.currentAutomaticSync)
        assertEquals(175, client.currentManualOutputDelayMs)
        assertEquals(0, client.currentOutputDelayMs)
        assertEquals(0L, client.audioBuffer.outputDelayMicros)
        assertTrue(client.audioBuffer.automaticSync)
        assertEquals(175, store.ints[ClientSettingsKeys.OUTPUT_DELAY_MS])

        client.setAutomaticSync(false)
        assertFalse(client.currentAutomaticSync)
        assertFalse(client.audioBuffer.automaticSync)
        assertEquals(175_000L, client.audioBuffer.outputDelayMicros)
        assertEquals(0, store.ints[ClientSettingsKeys.OUTPUT_SYNC_AUTOMATIC])
    }

    @Test
    fun `manual zero remains explicit and automatic toggles retain its saved value`() {
        val store = MemorySettingsStore()
        val client = client(store)
        client.setOutputDelayMs(0)
        assertFalse(client.currentAutomaticSync)
        assertFalse(client.audioBuffer.automaticSync)
        assertEquals(0, client.currentManualOutputDelayMs)
        assertEquals(0, store.ints[ClientSettingsKeys.OUTPUT_SYNC_AUTOMATIC])

        client.setAutomaticSync(true)
        client.setAutomaticSync(false)
        val restored = client(store)
        assertFalse(restored.currentAutomaticSync)
        assertEquals(0, restored.currentOutputDelayMs)
        assertEquals(0, restored.currentManualOutputDelayMs)
    }

    @Test
    fun `automatic toggles restore manual compensation without clearing buffered chunks`() {
        val store = MemorySettingsStore()
        val client = client(store)
        client.audioBuffer.offer(AudioChunk(100L, byteArrayOf(1)))
        client.setOutputDelayMs(240)
        client.setAutomaticSync(true)

        assertEquals(240, client.currentManualOutputDelayMs)
        assertEquals(0, client.currentOutputDelayMs)
        assertEquals(240, store.ints[ClientSettingsKeys.OUTPUT_DELAY_MS])
        assertEquals(1, store.ints[ClientSettingsKeys.OUTPUT_SYNC_AUTOMATIC])
        assertEquals(1, client.audioBuffer.size)
        client.setAutomaticSync(false)
        assertEquals(240, client.currentOutputDelayMs)
        assertEquals(240_000L, client.audioBuffer.outputDelayMicros)
        assertEquals(1, client.audioBuffer.size)
        val restored = client(store)
        assertEquals(240, restored.currentOutputDelayMs)
    }

    @Test
    fun `saved manual mode and delay bounds are applied during initialization`() {
        val store = MemorySettingsStore(mutableMapOf(
            ClientSettingsKeys.OUTPUT_SYNC_AUTOMATIC to 0,
            ClientSettingsKeys.OUTPUT_DELAY_MS to 9_000,
        ))
        val client = client(store)
        assertFalse(client.currentAutomaticSync)
        assertEquals(5_000, client.currentManualOutputDelayMs)
        assertEquals(5_000_000L, client.audioBuffer.outputDelayMicros)
        client.setOutputDelayMs(-1)
        assertEquals(0, client.currentOutputDelayMs)
        assertEquals(0, store.ints[ClientSettingsKeys.OUTPUT_DELAY_MS])
    }

    @Test
    fun `server output delay command selects manual mode and reports effective delay`() {
        val store = MemorySettingsStore()
        val client = client(store)
        val socket = CapturingSocket()
        client.acceptIncomingConnection(socket, hello())
        client.handleTextMessage("""{"type":"server/command","payload":{"player":{"command":"set_output_delay","output_delay_ms":125}}}""")
        assertFalse(client.currentAutomaticSync)
        assertEquals(125, client.currentManualOutputDelayMs)
        assertEquals(125, socket.lastPlayerState().getInt("output_delay_ms"))
        client.setAutomaticSync(true)
        assertEquals(0, socket.lastPlayerState().getInt("output_delay_ms"))
        assertEquals(125, store.ints[ClientSettingsKeys.OUTPUT_DELAY_MS])
    }

    @Test
    fun `first clock burst seeds readiness before initial audio flush`() = runBlocking {
        val player = TrackingPlayer()
        val client = client(player = player)
        val socket = CapturingSocket()
        client.acceptIncomingConnection(socket, hello())
        await { player.stops > 0 }
        player.flushes = 0
        var callbackChunks = 0
        client.onAudioChunk = { callbackChunks++ }
        val bytes = ByteBuffer.allocate(14).put(BINARY_TYPE_AUDIO).putLong(1_000L).putInt(0).put(7).array()
        client.handleBinaryMessage(bytes.toByteString())
        assertEquals(1, callbackChunks)
        assertEquals(1, client.audioBuffer.size)

        repeat(ClockSyncConfig.PROBES_PER_BURST - 1) { validReply(client) }
        assertFalse(client.clockSync.isReady)
        assertEquals(ClientState.CLOCK_SYNCING, client.state.value)
        assertEquals(0, player.flushes)

        validReply(client)
        assertTrue(client.clockSync.isReady)
        await { player.flushes == 1 }
        assertEquals(ClientState.STREAMING, client.state.value)
        repeat(ClockSyncConfig.PROBES_PER_BURST) { validReply(client) }
        delay(50L)
        assertEquals(1, player.flushes)
    }

    @Test
    fun `invalid minimum RTT cannot prevent a valid burst sample seeding readiness`() = runBlocking {
        val player = TrackingPlayer()
        val client = client(player = player)
        client.acceptIncomingConnection(CapturingSocket(), hello())
        await { player.stops > 0 }
        player.flushes = 0
        repeat(ClockSyncConfig.PROBES_PER_BURST - 1) { invalidReply(client) }
        validReply(client)
        assertTrue(client.clockSync.isReady)
        await { player.flushes == 1 }
    }

    @Test
    fun `invalid-only clock burst leaves clock and playback waiting`() = runBlocking {
        val player = TrackingPlayer()
        val client = client(player = player)
        client.acceptIncomingConnection(CapturingSocket(), hello())
        await { player.stops > 0 }
        player.flushes = 0
        repeat(ClockSyncConfig.PROBES_PER_BURST) { invalidReply(client) }
        delay(50L)
        assertFalse(client.clockSync.isReady)
        assertEquals(ClientState.CLOCK_SYNCING, client.state.value)
        assertEquals(0, player.flushes)
    }

    @Test
    fun `socket close and failure reset clock immediately and stop stale playback`() = runBlocking {
        for (failed in listOf(false, true)) {
            val player = TrackingPlayer()
            val client = client(player = player)
            client.acceptIncomingConnection(CapturingSocket(), hello())
            await { player.stops > 0 }
            client.clockSync.processMeasurement(100L, 10_180L, 10_220L, 300L)
            player.start()
            val priorStops = player.stops
            if (failed) client.onServerSocketError(IllegalStateException("test failure"))
            else client.onServerSocketClosed(1000, "test close")
            assertFalse(client.clockSync.isReady)
            assertEquals(if (failed) ClientState.ERROR else ClientState.DISCONNECTED, client.state.value)
            await { player.stops > priorStops }
            assertFalse(player.isPlaying)
            assertFalse(client.diagnostics.value.clockReady)
            assertFalse(client.diagnostics.value.isAudioPlaying)
        }
    }

    @Test
    fun `diagnostics expose clock and output controller state then clear on disconnect`() = runBlocking {
        val player = TrackingPlayer()
        val client = client(player = player)
        client.setOutputDelayMs(180)
        client.setAutomaticSync(true)
        client.acceptIncomingConnection(CapturingSocket(), hello())
        await { player.stops > 0 }
        client.clockSync.processMeasurement(1_000L, 11_080L, 11_120L, 1_200L)
        player.start()
        player.snapshot = OutputSyncSnapshot("playing", "timestamp", 120L, 2L, 3L, 4L, 5L)
        await { client.diagnostics.value.clockReady }
        val diagnostics = client.diagnostics.value
        assertEquals(0.04, diagnostics.clockUncertaintyMs, 0.000_001)
        assertEquals(player.snapshot, diagnostics.outputSync)
        assertTrue(diagnostics.automaticSync)
        assertEquals(0, diagnostics.outputDelayMs)
        assertEquals(180, diagnostics.manualOutputDelayMs)
        client.disconnect()
        assertFalse(client.diagnostics.value.clockReady)
        assertEquals(OutputSyncSnapshot(), client.diagnostics.value.outputSync)
        assertFalse(client.diagnostics.value.isAudioPlaying)
        assertEquals(ClientState.DISCONNECTED, client.diagnostics.value.state)
        assertEquals(180, client.diagnostics.value.manualOutputDelayMs)
    }

    @Test
    fun `player stream start clears old encoded chunks before accepting the new format`() = runBlocking {
        val player = TrackingPlayer()
        val client = client(player = player)
        client.audioBuffer.offer(AudioChunk(100L, byteArrayOf(1)))
        player.start()
        client.handleTextMessage("""{"type":"stream/start","payload":{"player":{"codec":"pcm","sample_rate":48000,"channels":2,"bit_depth":16}}}""")
        assertEquals(0, client.audioBuffer.size)
        client.audioBuffer.offer(AudioChunk(200L, byteArrayOf(2)))
        await { player.transitions == 1 }
        assertTrue(player.isPlaying)
        assertEquals(1, client.audioBuffer.size)
        assertEquals(200L, client.audioBuffer.take()!!.serverTimestampMicros)
    }

    private fun client(
        store: ClientSettingsStore = MemorySettingsStore(),
        player: AudioPlayer = TrackingPlayer(),
    ): SendSpinClient {
        val moshi = Moshi.Builder().add(JsonOptionalAdapterFactory()).addLast(KotlinJsonAdapterFactory()).build()
        return SendSpinClient(
            okHttpClient = OkHttpClient(),
            moshi = moshi,
            preferences = ClientPreferences(emptyList(), emptyList(), supportedOptionalRoles = setOf(OptionalRole.PLAYER)),
            manufacturer = "Test",
            productName = "Test",
            softwareVersion = "test",
            audioPlayerFactory = { _, _ -> player },
            reconnectEnabled = false,
            settingsStore = store,
        ).also { clients += it }
    }

    private fun hello() = ServerHello("test-server", "Test Server", 1, listOf("player@v1"))

    private fun validReply(client: SendSpinClient) {
        val t1 = ClockSync.localMicros() - 10_000L
        client.handleTextMessage("""{"type":"server/time","payload":{"client_transmitted":$t1,"server_received":${t1 + 20_000L},"server_transmitted":${t1 + 20_100L}}}""")
    }

    private fun invalidReply(client: SendSpinClient) {
        val t1 = ClockSync.localMicros()
        client.handleTextMessage("""{"type":"server/time","payload":{"client_transmitted":$t1,"server_received":$t1,"server_transmitted":${t1 + 10_000_000L}}}""")
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(3_000L) { while (!condition()) delay(10L) }
    }

    private class MemorySettingsStore(val ints: MutableMap<String, Int> = mutableMapOf()) : ClientSettingsStore {
        private val strings = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
    }

    private class CapturingSocket : SendSpinWebSocket {
        val messages = CopyOnWriteArrayList<String>()
        override fun send(text: String): Boolean { messages += text; return true }
        override fun close(code: Int, reason: String?) {}
        fun lastPlayerState(): JSONObject = messages.map(::JSONObject)
            .last { it.getString("type") == "client/state" }.getJSONObject("payload").getJSONObject("player")
    }

    private class TrackingPlayer : AudioPlayer {
        @Volatile override var isPlaying = false
        override val droppedDecodeFrames = 0L
        @Volatile var flushes = 0
        @Volatile var stops = 0
        @Volatile var transitions = 0
        @Volatile var snapshot = OutputSyncSnapshot()
        override val outputSync: OutputSyncSnapshot get() = snapshot
        override fun configure(format: StreamFormat) {}
        override fun start() { isPlaying = true }
        override fun flush() { flushes++ }
        override fun stop() { isPlaying = false; snapshot = OutputSyncSnapshot(); stops++ }
        override fun transition(format: StreamFormat) { configure(format); start(); transitions++ }
        override fun setVolume(gain: Float) {}
    }
}
