package com.zqc.tvnotify

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sendspin.protocol.ArtworkChannel
import com.sendspin.protocol.AudioFormat
import com.sendspin.protocol.ClientPreferences
import com.sendspin.protocol.ClientState
import com.sendspin.protocol.ControllerState
import com.sendspin.protocol.DiscoveryService
import com.sendspin.protocol.DiagnosticsSnapshot
import com.sendspin.protocol.JsonOptionalAdapterFactory
import com.sendspin.protocol.OptionalRole
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.ProgressInfo
import com.sendspin.protocol.orNull
import com.sendspin.protocol.orFallback
import com.sendspin.protocol.SendSpinClient
import com.sendspin.protocol.ServerState
import com.sendspin.protocol.TrackMetadataMsg
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Owns the upstream client and Android adapters for the lifetime of NotifyService. */
class SendspinRuntime private constructor(private val context: Context) {
    data class Status(
        val state: String = "disabled",
        val server: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val progressMs: Long = 0L,
        val durationMs: Long = 0L,
        val playing: Boolean = false,
        val volume: Int = 100,
        val muted: Boolean = false,
        val playerGain: Float = 1f,
        val outputDelayMs: Int = 0,
        val automaticSync: Boolean = true,
        val manualOutputDelayMs: Int = 0,
        val clockReady: Boolean = false,
        val clockUncertaintyMs: Double = 0.0,
        val syncState: String = "idle",
        val positionSource: String = "unavailable",
        val syncErrorMs: Double? = null,
        val resyncCount: Long = 0L,
        val underrunCount: Long = 0L,
        val insertedFrames: Long = 0L,
        val droppedFrames: Long = 0L,
        val commands: Set<String> = emptySet(),
        val controllerCommands: Set<String> = emptySet(),
        val playerCommands: Set<String> = emptySet(),
        val artwork: ByteArray? = null,
        val error: String = "",
    )

    private val app = context.applicationContext
    private val settingsStore = SendspinPrefsStore(app)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientId = appPrefsClientId(context.applicationContext)
    private fun appPrefsClientId(context: Context): String {
        val prefs = context.getSharedPreferences("tvnotify_prefs", Context.MODE_PRIVATE)
        return prefs.getString("sendspin_client_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("sendspin_client_id", it).commit()
        }
    }
    private val okHttp = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val moshi = Moshi.Builder()
        .add(JsonOptionalAdapterFactory())
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val wifiLock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.createMulticastLock("yujian-sendspin")
        ?.apply { setReferenceCounted(false) }

    @Volatile private var client: SendSpinClient? = null
    @Volatile private var discoveryJob: Job? = null
    @Volatile private var stateJob: Job? = null
    @Volatile private var watchedClient: SendSpinClient? = null
    @Volatile private var enabled = false
    @Volatile private var endpoint = ""
    @Volatile private var activityVisible = false
    @Volatile var onOpenUiRequested: ((Boolean) -> Unit)? = null
    @Volatile private var lastMetadata: TrackMetadataMsg? = null
    @Volatile private var lastController: ControllerState? = null
    @Volatile private var currentStatus = savedStatus()

    private fun savedStatus(state: String = "disabled"): Status {
        val automatic = settingsStore.automaticSync
        val manualDelay = settingsStore.manualOutputDelayMs
        return Status(
            state = state,
            automaticSync = automatic,
            manualOutputDelayMs = manualDelay,
            outputDelayMs = if (automatic) 0 else manualDelay,
        )
    }

    fun start() {
        if (enabled && (client != null || discoveryJob?.isActive == true)) return
        if (!Prefs.isSendspinEnabled(app)) {
            publish(currentStatus.copy(state = "disabled", playing = false))
            return
        }
        enabled = true
        ensureMulticastLock()
        val savedUrl = Prefs.getSendspinServerUrl(app).trim()
        if (savedUrl.isNotEmpty()) {
            val normalized = normalizeUrl(savedUrl)
            if (normalized != null && normalized != endpoint) connect(normalized)
            else if (client == null) connect(savedUrl)
        } else if (client == null) {
            startDiscovery()
        }
    }

    fun reconfigure() {
        if (!Prefs.isSendspinEnabled(app)) {
            stop()
            publish(savedStatus(state = "disabled"))
            return
        }
        enabled = true
        val savedUrl = Prefs.getSendspinServerUrl(app).trim()
        if (savedUrl.isNotEmpty()) {
            val normalized = normalizeUrl(savedUrl)
            if (normalized != null && normalized != endpoint) connect(normalized)
        } else if (endpoint.isNotEmpty()) {
            synchronized(lock) {
                client?.close()
                client = null
            }
            endpoint = ""
            startDiscovery()
        } else if (client == null && discoveryJob?.isActive != true) {
            startDiscovery()
        }
    }

    fun stop() {
        enabled = false
        endpoint = ""
        discoveryJob?.cancel()
        discoveryJob = null
        stateJob?.cancel()
        stateJob = null
        watchedClient = null
        synchronized(lock) {
            client?.close()
            client = null
        }
        releaseMulticastLock()
        publish(savedStatus(state = "stopped"))
    }

    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
    }

    fun connect(url: String) {
        val normalized = normalizeUrl(url) ?: run {
            publish(currentStatus.copy(state = "error", error = "请输入有效的 ws:// 或 wss:// 地址"))
            return
        }
        enabled = true
        val previousEndpoint = endpoint
        endpoint = normalized
        Prefs.setSendspinServerUrl(app, normalized)
        discoveryJob?.cancel()
        if (previousEndpoint.isNotEmpty() && previousEndpoint != normalized) {
            synchronized(lock) {
                client?.close()
                client = null
            }
        }
        ensureMulticastLock()
        val instance = createClient()
        instance.connect(normalized)
        watch(instance)
    }

    fun reconnectByDiscovery() {
        Prefs.setSendspinServerUrl(app, "")
        synchronized(lock) {
            client?.close()
            client = null
        }
        endpoint = ""
        enabled = true
        startDiscovery()
    }

    fun command(
        command: String,
        volume: Int? = null,
        muted: Boolean? = null,
        positionMs: Long? = null,
        offsetMs: Long? = null,
    ): Boolean {
        val current = client ?: return false
        val supported = currentStatus.controllerCommands
        val playerSupported = currentStatus.playerCommands
        when (command) {
            "play", "pause", "stop", "next", "previous" -> {
                if (command !in supported) return false
                current.sendControllerCommand(command)
            }
            "seek" -> {
                if (command !in supported || positionMs == null) return false
                current.sendSeek(positionMs)
            }
            "seek_relative" -> {
                if (command !in supported || offsetMs == null) return false
                current.sendSeekRelative(offsetMs)
            }
            "volume" -> {
                if ("volume" !in playerSupported || volume == null) return false
                current.setLocalVolume(volume)
                val gain = current.currentPlayerGain
                current.audioPlayer.setVolume(gain)
                publish(currentStatus.copy(volume = volume.coerceIn(0, 100), playerGain = gain))
            }
            "mute" -> {
                if ("mute" !in playerSupported || muted == null) return false
                current.setLocalMuted(muted)
                val gain = current.currentPlayerGain
                current.audioPlayer.setVolume(gain)
                publish(currentStatus.copy(muted = muted, playerGain = gain))
            }
            else -> return false
        }
        return true
    }

    fun status(): Status = currentStatus

    /** Enable automatic timing or restore the previously saved manual compensation. */
    fun setAutomaticSync(automatic: Boolean): Boolean = synchronized(lock) {
        if (!settingsStore.setAutomaticSync(automatic)) return@synchronized false
        client?.setAutomaticSync(automatic)
        updateStatus {
            it.copy(
                automaticSync = automatic,
                manualOutputDelayMs = settingsStore.manualOutputDelayMs,
                outputDelayMs = if (automatic) 0 else settingsStore.manualOutputDelayMs,
            )
        }
        true
    }

    /** Explicit compensation selects manual mode, including while disconnected. */
    fun setOutputDelayMs(delayMs: Int): Boolean = synchronized(lock) {
        if (!settingsStore.setManualOutputDelayMs(delayMs)) return@synchronized false
        client?.setOutputDelayMs(delayMs)
        updateStatus {
            it.copy(automaticSync = false, manualOutputDelayMs = delayMs, outputDelayMs = delayMs)
        }
        true
    }

    private fun startDiscovery() {
        if (!enabled) return
        discoveryJob?.cancel()
        val instance = createClient()
        watch(instance)
        val browser = AndroidSendspinNsdBrowser(app)
        val discovery = DiscoveryService(browser)
        discoveryJob = scope.launch {
            discovery.discover().collectLatest { servers ->
                val server = servers.firstOrNull()
                if (server != null && enabled && Prefs.getSendspinServerUrl(app).isBlank()) {
                    val path = server.path
                    val host = if (server.host.startsWith("[") || !server.host.contains(":")) server.host else "[${server.host}]"
                    val url = "ws://$host:${server.port}$path"
                    if (url != endpoint) {
                        endpoint = url
                        instance.connect(url)
                    }
                }
                if (server == null && client?.state?.value !in setOf(ClientState.STREAMING, ClientState.CLOCK_SYNCING)) {
                    updateStatus { it.copy(state = "discovering", server = "") }
                }
            }
        }
    }

    private fun createClient(): SendSpinClient = synchronized(lock) {
        client?.let { return@synchronized it }
        val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val prefs = ClientPreferences(
            supportedFormats = listOf(AudioFormat("pcm", 2, 48_000, 16), AudioFormat("pcm", 2, 44_100, 16), AudioFormat("pcm", 1, 48_000, 16)),
            artworkChannels = listOf(ArtworkChannel(source = "album", width = 512, height = 512)),
            playerSupportedCommands = listOf("volume", "mute", "set_output_delay"),
            supportedOptionalRoles = setOf(OptionalRole.PLAYER, OptionalRole.METADATA, OptionalRole.ARTWORK, OptionalRole.CONTROLLER),
        )
        SendSpinClient(
            okHttpClient = okHttp,
            moshi = moshi,
            preferences = prefs,
            clientId = clientId,
            clientName = Prefs.getSendspinClientName(app),
            manufacturer = "JochenZhou",
            productName = "YuJian TV",
            softwareVersion = BuildConfig.VERSION_NAME,
            audioPlayerFactory = { buffer, clock ->
                AndroidAudioPlayer(app, buffer, clock, audioManager)
            },
            settingsStore = settingsStore,
        ).also { created ->
            client = created
            created.onAudioChunk = { chunk ->
                if (chunk.data.isNotEmpty() && chunk.data.size < 2) {
                    Log.w(TAG, "Received undersized PCM chunk")
                }
            }
        }
    }

    private fun watch(instance: SendSpinClient) = synchronized(lock) {
        if (watchedClient === instance && stateJob?.isActive == true) return@synchronized
        stateJob?.cancel()
        watchedClient = instance
        stateJob = scope.launch {
            launch {
                instance.serverState.collect { state -> updateFromServerState(state) }
            }
            launch {
                instance.albumArtwork.collect { bytes -> updateStatus { it.copy(artwork = bytes) } }
            }
            launch {
                instance.streamFormat.collectLatest { format ->
                    if (format != null) instance.audioPlayer.setVolume(instance.currentPlayerGain)
                    updateStatus { it.copy(playerGain = instance.currentPlayerGain) }
                }
            }
            launch {
                instance.controllerState.collectLatest { controller ->
                    val active = controller?.supportedCommands?.toSet() ?: emptySet()
                    updateStatus {
                        it.copy(
                            commands = active + it.playerCommands,
                            controllerCommands = active,
                        )
                    }
                }
            }
            launch {
                instance.diagnostics.collectLatest { diagnostics ->
                    if (client !== instance) return@collectLatest
                    val previous = synchronized(lock) { currentStatus }
                    val streaming = instance.state.value == ClientState.STREAMING
                    updateStatus {
                        val next = it.copy(
                            playing = streaming && diagnostics.isAudioPlaying,
                            automaticSync = instance.currentAutomaticSync,
                            outputDelayMs = instance.currentOutputDelayMs,
                            manualOutputDelayMs = instance.currentManualOutputDelayMs,
                            clockReady = diagnostics.clockReady,
                            clockUncertaintyMs = diagnostics.clockUncertaintyMs,
                            syncState = diagnostics.outputSync.state,
                            positionSource = diagnostics.outputSync.positionSource,
                            syncErrorMs = diagnostics.outputSync.errorMicros?.div(1000.0),
                            resyncCount = diagnostics.outputSync.resyncCount,
                            underrunCount = diagnostics.outputSync.underrunCount,
                            insertedFrames = diagnostics.outputSync.insertedFrames,
                            droppedFrames = diagnostics.outputSync.droppedFrames,
                        )
                        if (streaming) next else next.withoutOutputTiming(instance.state.value)
                    }
                    if (streaming && diagnostics.isAudioPlaying && !previous.playing && !activityVisible) {
                        onOpenUiRequested?.invoke(true)
                    }
                }
            }
            launch {
                instance.serverHello.collectLatest { hello ->
                    val activeRoles = hello?.activeRoles.orEmpty().toSet()
                    val controllerEnabled = "controller@v1" in activeRoles || "controller" in activeRoles
                    val playerEnabled = "player@v1" in activeRoles || "player" in activeRoles
                    val pairingRequired = "pairing@v1" in activeRoles
                    updateStatus {
                        val controller = it.controllerCommands.takeIf { _ -> controllerEnabled } ?: emptySet()
                        val player = setOf("volume", "mute").takeIf { _ -> playerEnabled } ?: emptySet()
                        it.copy(
                            state = if (pairingRequired) "error" else it.state,
                            error = if (pairingRequired) "Music Assistant 要求配对；当前客户端不支持 Noise/PSK，请启用 legacy client 兼容" else it.error,
                            controllerCommands = controller,
                            playerCommands = player,
                            commands = controller + player,
                            volume = instance.currentPlayerVolume,
                            muted = instance.currentPlayerMuted,
                            playerGain = instance.currentPlayerGain,
                            outputDelayMs = instance.currentOutputDelayMs,
                            automaticSync = instance.currentAutomaticSync,
                            manualOutputDelayMs = instance.currentManualOutputDelayMs,
                        )
                    }
                }
            }
            launch {
                instance.state.collectLatest { state ->
                    if (client !== instance) return@collectLatest
                    val reason = instance.lastConnectionReason.orEmpty()
                    val error = when {
                        reason.contains("pair", true) || reason.contains("encrypt", true) ->
                            "Music Assistant 拒绝未配对客户端；请在 MA 中启用 legacy client 兼容"
                        state == ClientState.ERROR -> "连接失败；请检查 MA Sendspin 服务和 legacy client 兼容设置"
                        else -> ""
                    }
                    val label = when (state) {
                        ClientState.IDLE -> "idle"
                        ClientState.CONNECTING, ClientState.HANDSHAKING, ClientState.CLOCK_SYNCING -> "connecting"
                        ClientState.STREAMING -> "connected"
                        ClientState.ERROR -> "error"
                        ClientState.DISCONNECTED -> "disconnected"
                    }
                    updateStatus {
                        val next = it.copy(
                            state = label,
                            server = instance.serverName.value,
                            playing = state == ClientState.STREAMING && instance.audioPlayer.isPlaying,
                            error = error,
                        )
                        if (state == ClientState.STREAMING) next else next.withoutOutputTiming(state)
                    }
                }
            }
        }
    }

    private fun Status.withoutOutputTiming(state: ClientState): Status = copy(
        playing = false,
        clockReady = false,
        clockUncertaintyMs = 0.0,
        syncState = when (state) {
            ClientState.CONNECTING, ClientState.HANDSHAKING, ClientState.CLOCK_SYNCING -> "waiting_clock"
            else -> "idle"
        },
        positionSource = "unavailable",
        syncErrorMs = null,
    )

    private fun updateFromServerState(message: ServerState) {
        val previousMetadata = lastMetadata
        val incomingMetadata = message.metadata.orNull()
        val metadataNow = when {
            message.metadata is JsonOptional.Present && incomingMetadata == null -> null
            incomingMetadata != null -> mergeMetadata(previousMetadata, incomingMetadata)
            else -> previousMetadata
        }
        lastMetadata = metadataNow

        val incomingController = message.controller.orNull()
        val controllerNow = when {
            message.controller is JsonOptional.Present && incomingController == null -> null
            incomingController != null -> incomingController
            else -> lastController
        }
        lastController = controllerNow

        val existing = currentStatus
        val controllerCommands = controllerNow?.supportedCommands?.toSet()
            ?: if (message.controller is JsonOptional.Present) emptySet() else existing.controllerCommands
        val progress = metadataNow?.progress?.orNull()
        publish(
            existing.copy(
                title = metadataNow?.title?.orNull().orEmpty(),
                artist = metadataNow?.artist?.orNull().orEmpty(),
                album = metadataNow?.album?.orNull().orEmpty(),
                progressMs = when {
                    progress != null -> progress.trackProgress
                    metadataNow == null || metadataNow.progress is JsonOptional.Present -> 0L
                    else -> existing.progressMs
                },
                durationMs = when {
                    progress != null -> progress.trackDuration
                    metadataNow == null || metadataNow.progress is JsonOptional.Present -> 0L
                    else -> existing.durationMs
                },
                commands = controllerCommands + existing.playerCommands,
                controllerCommands = controllerCommands,
            )
        )
    }

    private fun mergeMetadata(previous: TrackMetadataMsg?, incoming: TrackMetadataMsg): TrackMetadataMsg {
        fun <T> merge(field: JsonOptional<T>, old: JsonOptional<T>): JsonOptional<T> =
            if (field is JsonOptional.Absent) old else field
        return incoming.copy(
            title = merge(incoming.title, previous?.title ?: JsonOptional.Absent),
            artist = merge(incoming.artist, previous?.artist ?: JsonOptional.Absent),
            albumArtist = merge(incoming.albumArtist, previous?.albumArtist ?: JsonOptional.Absent),
            album = merge(incoming.album, previous?.album ?: JsonOptional.Absent),
            artworkUrl = merge(incoming.artworkUrl, previous?.artworkUrl ?: JsonOptional.Absent),
            year = merge(incoming.year, previous?.year ?: JsonOptional.Absent),
            track = merge(incoming.track, previous?.track ?: JsonOptional.Absent),
            progress = merge(incoming.progress, previous?.progress ?: JsonOptional.Absent),
        )
    }

    private fun publish(status: Status) {
        // watch() 的多个 collector 在不同协程上发布增量更新；currentStatus.copy 在
        // 并发下会相互覆盖（诊断流的 playing 更新能把状态流的 connected 退回旧值），
        // 读改写必须在同一锁内完成。
        synchronized(lock) {
            currentStatus = status
        }
        listeners.toList().forEach { callback -> main.post { callback(currentStatus) } }
    }

    /** Thread-safe incremental update of [currentStatus]. */
    private fun updateStatus(transform: (Status) -> Status) {
        val next = synchronized(lock) {
            currentStatus = transform(currentStatus)
            currentStatus
        }
        listeners.toList().forEach { callback -> main.post { callback(next) } }
    }

    private fun ensureMulticastLock() {
        try {
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to acquire Sendspin multicast lock", error)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to release Sendspin multicast lock", error)
        }
    }

    private fun normalizeUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val withScheme = if (value.startsWith("ws://", true) || value.startsWith("wss://", true)) value else "ws://$value"
        return try {
            val uri = android.net.Uri.parse(withScheme)
            if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            val port = if (uri.port > 0) uri.port else 8927
            val path = uri.encodedPath?.takeIf { it.isNotBlank() && it != "/" } ?: "/sendspin"
            val host = uri.host!!.let { if (it.contains(":") && !it.startsWith("[")) "[$it]" else it }
            "${uri.scheme}://$host:$port$path${uri.encodedQuery?.let { "?$it" }.orEmpty()}"
        } catch (_: Exception) {
            null
        }
    }

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        main.post { listener(currentStatus) }
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }

    companion object {
        private const val TAG = "YuJianSendspin"
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Status) -> Unit>()
        @Volatile private var instance: SendspinRuntime? = null

        fun init(context: Context): SendspinRuntime = synchronized(this) {
            instance ?: SendspinRuntime(context.applicationContext).also { instance = it }
        }

        fun current(): SendspinRuntime? = instance

        fun shutdown() {
            val old = synchronized(this) { instance.also { instance = null } } ?: return
            old.stop()
            old.scope.cancel()
            old.okHttp.dispatcher.executorService.shutdown()
            old.okHttp.connectionPool.evictAll()
        }
    }
}
