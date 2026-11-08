package com.zqc.tvnotify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sendspin 传输连接、协议状态机与各子系统协调中枢：
 * 1. 双连接模式：默认端口 8928 被动等待发现（Server-Initiated），支持手动指定服务端（Client-Initiated）；
 * 2. 双协议自适应：现代 Noise_KKpsk2 加密信道优先，自动兼容旧版明文 WebSocket；
 * 3. 驱动音频播放（SendspinAudioPlayer）、歌词检索（LyricsFetcher）与时间同步（SendspinClockFilter）；
 * 4. 音乐流到达且处于播放态时，联动 NotifyService 自动唤起全屏歌词大屏。
 */
object SendspinManager {

    private const val TAG = "SendspinManager"
    private const val DEFAULT_PORT = 8928
    private const val SYNC_INITIAL_INTERVAL_MS = 250L
    private const val SYNC_STEADY_INTERVAL_MS = 5000L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var autoPopupCallback: (() -> Unit)? = null

    @Volatile
    private var running = false

    @Volatile
    var boundPort: Int = 0
        private set

    @Volatile
    private var isConnected = false

    @Volatile
    private var serverName = ""

    @Volatile
    private var activeServerUrl = ""

    @Volatile
    private var currentFormat: SendspinPlayerFormat? = null

    @Volatile
    private var currentMetadata = SendspinTrackMetadata()

    @Volatile
    private var currentController = SendspinControllerState()

    @Volatile
    private var currentColorPalette: SendspinColorPalette? = null

    @Volatile
    private var activeArtwork: Bitmap? = null

    @Volatile
    private var activeLyrics: ParsedLyrics? = null

    @Volatile
    private var playerVolume: Int = 100

    @Volatile
    private var playerMuted: Boolean = false

    @Volatile
    private var activePairingPsk: ByteArray? = null

    // 当前会话的 server_id（base64url 静态公钥）与 Noise 握手匹配到的 PSK 类别（lt/pr/sn）
    @Volatile
    private var currentServerId: String = ""

    @Volatile
    private var currentMatchedPskCategory: String = "sn"

    private val clockFilter = SendspinClockFilter()
    private var audioPlayer: SendspinAudioPlayer? = null
    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var workerThread: Thread? = null
    private var timeSyncThread: Thread? = null
    private var outboundJobThread: Thread? = null

    /** 入站连接处理线程（server-initiated 拨入，每连接一个，避免阻塞 accept 循环）。 */
    @Volatile
    private var inboundWorkerThread: Thread? = null

    @Volatile
    private var nsdHelper: SendspinNsdHelper? = null

    // 活跃传输层发送闭包 (Thread-safe)
    @Volatile
    private var frameSender: ((opcode: Int, payload: ByteArray) -> Unit)? = null

    fun normalizeSendspinUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("http://", ignoreCase = true)) {
            s = "ws://" + s.substring(7)
        } else if (s.startsWith("https://", ignoreCase = true)) {
            s = "wss://" + s.substring(8)
        } else if (!s.startsWith("ws://", ignoreCase = true) && !s.startsWith("wss://", ignoreCase = true)) {
            s = "ws://$s"
        }
        val uri = try { URI(s) } catch (_: Exception) { return s }
        val host = uri.host ?: return s
        val port = if (uri.port > 0) uri.port else 8927
        val path = if (uri.path.isNullOrEmpty() || uri.path == "/") "/sendspin" else uri.path
        return "ws://$host:$port$path"
    }

    fun init(context: Context, onAutoPopupRequested: () -> Unit) {
        if (running) return
        val app = context.applicationContext
        appContext = app
        autoPopupCallback = onAutoPopupRequested
        running = true

        nsdHelper = SendspinNsdHelper(app)
        audioPlayer = SendspinAudioPlayer(app, clockFilter)
        regeneratePairingPsk()

        startWorker()
    }

    fun reloadConfig() {
        val ctx = appContext ?: return
        if (!Prefs.isSendspinEnabled(ctx)) {
            shutdown()
            return
        }
        if (!running) {
            init(ctx, autoPopupCallback ?: {})
            return
        }
        // 重启底层 Socket 连接以应用新模式或新服务端 URL
        closeActiveConnection()
        startWorker()
    }

    fun shutdown() {
        running = false
        nsdHelper?.releaseAll()
        nsdHelper = null
        outboundJobThread?.interrupt()
        outboundJobThread = null
        closeActiveConnection()
        closeServerSocket()
        audioPlayer?.release()
        audioPlayer = null
        boundPort = 0
        isConnected = false
        activeArtwork = null
        activeLyrics = null
        currentMetadata = SendspinTrackMetadata()
        notifyListeners()
    }

    fun discoveredServers(): List<SendspinNsdHelper.DiscoveredServer> =
        nsdHelper?.discoveredServers ?: emptyList()

    fun shouldAdvertiseMdns(): Boolean {
        val ctx = appContext ?: return false
        return running && Prefs.isSendspinEnabled(ctx) && Prefs.getSendspinServerUrl(ctx).isBlank() && boundPort > 0
    }

    fun snapshot(): SendspinSnapshot {
        val ctx = appContext
        val enabled = if (ctx != null) Prefs.isSendspinEnabled(ctx) else false
        val autoPopup = if (ctx != null) Prefs.isSendspinAutoPopup(ctx) else true
        val clientName = if (ctx != null) Prefs.getSendspinClientName(ctx) else "YuJian TV"
        val privKey = if (ctx != null) Prefs.getOrCreateSendspinPrivateKey(ctx) else ByteArray(32)
        val pubKey = SendspinCrypto.x25519PublicFromPrivate(privKey)
        val clientId = SendspinCrypto.base64UrlEncode(pubKey)
        val psk = activePairingPsk ?: ByteArray(32)
        val token = SendspinCrypto.buildPairingToken(clientId, psk)

        return SendspinSnapshot(
            enabled = enabled,
            autoPopup = autoPopup,
            connected = isConnected,
            playing = currentMetadata.playbackSpeed > 0,
            boundPort = boundPort,
            serverName = serverName,
            serverUrl = activeServerUrl,
            clientName = clientName,
            clientId = clientId,
            pairingToken = token,
            format = currentFormat,
            metadata = currentMetadata,
            controller = currentController,
            playerVolume = playerVolume,
            playerMuted = playerMuted,
            rttMs = clockFilter.minRttUs / 1000L,
            synced = clockFilter.isSynced,
            colorPalette = currentColorPalette,
            discoveredServers = nsdHelper?.discoveredServers?.map { "${it.name} (${it.host}:${it.port})" } ?: emptyList()
        )
    }

    fun currentArtwork(): Bitmap? = activeArtwork

    /** 音频播放健康度，供 /health 远程诊断 */
    fun audioDebugStats(): org.json.JSONObject? = audioPlayer?.debugStats()

    /** 时钟同步诊断（/health） */
    fun clockSynced(): Boolean = clockFilter.isSynced
    fun clockRttMs(): Long = clockFilter.minRttUs / 1000L
    fun clockOffsetMs(): Long = clockFilter.currentOffsetUs() / 1000L

    fun currentLyrics(): ParsedLyrics? = activeLyrics

    fun currentPositionMs(): Long {
        return clockFilter.currentTrackPositionMs(currentMetadata, System.nanoTime() / 1000L)
    }

    fun sendControllerCommand(command: String, volume: Int? = null, mute: Boolean? = null) {
        val sender = frameSender ?: return
        val payload = JSONObject().apply {
            put("type", "client/command")
            val ctrl = JSONObject().apply {
                put("command", command)
                if (volume != null) put("volume", volume)
                if (mute != null) put("mute", mute)
            }
            put("payload", JSONObject().apply { put("controller", ctrl) })
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        sender(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + bytes)
    }

    fun adjustLocalOrGroupVolume(deltaPercent: Int) {
        val newVol = (playerVolume + deltaPercent).coerceIn(0, 100)
        playerVolume = newVol
        audioPlayer?.setVolumeAndMute(newVol, playerMuted)
        sendControllerCommand("volume", volume = newVol)
        notifyListeners()
    }

    fun regeneratePairingToken(): String {
        // 重置配对：作废旧 pairing PSK 与全部服务端配对记录，生成全新凭据（re-pairing 用）
        val ctx = appContext
        if (ctx != null) {
            Prefs.resetSendspinPairing(ctx)
        }
        regeneratePairingPsk()
        notifyListeners()
        return snapshot().pairingToken
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        mainHandler.post {
            for (l in listeners) {
                try { l() } catch (_: Exception) {}
            }
        }
    }

    private fun regeneratePairingPsk() {
        // 配对 PSK 按 Sendspin 规范为 per-device 长期凭据：持久化后保持不变，
        // 否则服务端 re-handshake 到 pairing PSK 时会因客户端不认识 psk_id 而失败。
        activePairingPsk = appContext?.let { Prefs.getOrCreateSendspinPairingPsk(it) }
            ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
    }

    // ------------------------------------------------------------------
    // 连接驱动与生命周期
    // ------------------------------------------------------------------

    private fun startWorker() {
        workerThread?.interrupt()
        val thread = Thread({
            val ctx = appContext ?: return@Thread
            val manualUrl = Prefs.getSendspinServerUrl(ctx)

            if (manualUrl.isNotBlank()) {
                // 主动连接模式 (Client-Initiated)
                closeServerSocket()
                runOutboundClientLoop(manualUrl)
            } else {
                // 被动等待发现模式 (Server-Initiated) + 局域网 MA 服务端自动扫描
                runInboundServerLoop()
            }
        }, "sendspin-worker")
        thread.isDaemon = true
        workerThread = thread
        thread.start()
    }

    private fun runInboundServerLoop() {
        var ssocket: ServerSocket? = null
        for (port in DEFAULT_PORT..(DEFAULT_PORT + 4)) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(port))
                ssocket = ss
                boundPort = port
                Log.i(TAG, "Sendspin 服务端已在端口 $port 监听")
                break
            } catch (_: Exception) {}
        }
        if (ssocket == null) {
            Log.e(TAG, "无法绑定 Sendspin 端口 8928..8932")
            return
        }
        serverSocket = ssocket

        val ctx = appContext
        if (ctx != null) {
            val clientName = Prefs.getSendspinClientName(ctx)
            // 1. 系统级 NsdManager 广播自身播放器能力（无 5353 端口冲突，手机与电视全通用）
            nsdHelper?.registerSendspinPlayer(boundPort, clientName) {
                notifyListeners()
            }
            // 2. 自动扫描局域网 Music Assistant 服务端 (_sendspin-server._tcp)
            nsdHelper?.startDiscoveringServers { discovered ->
                notifyListeners()
                val manual = Prefs.getSendspinServerUrl(ctx)
                if (manual.isBlank() && !isConnected) {
                    Log.i(TAG, "检测到局域网 MA 服务端: ${discovered.wsUrl}，尝试自动对接")
                    triggerOutboundConnect(discovered.wsUrl)
                }
            }
        }

        notifyListeners()

        while (running && !ssocket.isClosed) {
            try {
                val clientSocket = ssocket.accept()
                clientSocket.tcpNoDelay = true
                // 读写超时：升级阶段与异常连接必须能超时退出，否则会话线程永久阻塞、
                // accept 循环卡死，后续 MA 的所有拨入都进 backlog 永不被处理。
                clientSocket.soTimeout = 45_000
                // 会话期间对端会周期性 ping/发流，每帧读都会重置超时，45s 足够宽容。
                val previousWorker = inboundWorkerThread
                if (previousWorker != null && previousWorker.isAlive) {
                    // 新拨入优先：打断并关闭旧会话（其线程会因 socket 关闭/中断退出）
                    closeActiveConnection()
                    previousWorker.interrupt()
                }
                activeSocket = clientSocket
                val worker = Thread({
                    try {
                        handleConnectedSocket(clientSocket, isOutbound = false, manualUrl = "")
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.i(TAG, "入站连接空闲超时，关闭")
                    } catch (e: Exception) {
                        Log.w(TAG, "入站会话异常结束: ${e.message}")
                    } finally {
                        try { clientSocket.close() } catch (_: Exception) {}
                        if (inboundWorkerThread === Thread.currentThread()) {
                            inboundWorkerThread = null
                        }
                    }
                }, "sendspin-inbound")
                worker.isDaemon = true
                inboundWorkerThread = worker
                worker.start()
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    fun triggerOutboundConnect(url: String) {
        if (isConnected) return
        outboundJobThread?.interrupt()
        val thread = Thread({
            try {
                val normalized = normalizeSendspinUrl(url)
                val uri = URI(normalized)
                val host = uri.host ?: return@Thread
                val port = if (uri.port > 0) uri.port else 8927
                val path = if (!uri.path.isNullOrEmpty()) uri.path else "/sendspin"

                Log.i(TAG, "正在主动连接 Sendspin 服务端 $host:$port$path")
                val socket = Socket(host, port).apply { tcpNoDelay = true; soTimeout = 45_000 }
                closeActiveConnection()
                activeSocket = socket
                activeServerUrl = normalized

                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val upgraded = SendspinWsFrameCodec.performClientUpgrade(input, output, "$host:$port", path)
                if (!upgraded) {
                    socket.close()
                    return@Thread
                }
                handleConnectedSocket(socket, isOutbound = true, manualUrl = normalized)
            } catch (e: Exception) {
                Log.w(TAG, "主动对接服务端失败: ${e.message}")
            }
        }, "sendspin-outbound-auto")
        thread.isDaemon = true
        outboundJobThread = thread
        thread.start()
    }

    private fun runOutboundClientLoop(manualUrl: String) {
        var backoffMs = 2000L
        val normalized = normalizeSendspinUrl(manualUrl)
        while (running) {
            try {
                val uri = URI(normalized)
                val host = uri.host ?: "127.0.0.1"
                val port = if (uri.port > 0) uri.port else 8927
                val path = if (!uri.path.isNullOrEmpty()) uri.path else "/sendspin"

                Log.i(TAG, "尝试连接 Sendspin 服务端 $host:$port$path")
                val socket = Socket(host, port).apply { tcpNoDelay = true; soTimeout = 45_000 }
                closeActiveConnection()
                activeSocket = socket
                activeServerUrl = normalized

                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val upgraded = SendspinWsFrameCodec.performClientUpgrade(input, output, "$host:$port", path)
                if (!upgraded) {
                    socket.close()
                    throw java.io.IOException("WebSocket upgrade failed")
                }

                backoffMs = 2000L
                handleConnectedSocket(socket, isOutbound = true, manualUrl = normalized)
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "连接 Sendspin 服务端失败，将在 ${backoffMs}ms 后重试: ${e.message}")
                try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
                backoffMs = minOf(backoffMs * 2, 30_000L)
            }
        }
    }

    private fun closeActiveConnection() {
        val s = activeSocket
        activeSocket = null
        frameSender = null
        isConnected = false
        timeSyncThread?.interrupt()
        timeSyncThread = null
        try { s?.close() } catch (_: Exception) {}
    }

    private fun closeServerSocket() {
        val ss = serverSocket
        serverSocket = null
        boundPort = 0
        try { ss?.close() } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
    // 会话交互、握手与报文流转
    // ------------------------------------------------------------------

    private fun handleConnectedSocket(socket: Socket, isOutbound: Boolean, manualUrl: String) {
        val ctx = appContext ?: return
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        if (!isOutbound) {
            // 服务端模式：先校验 HTTP Upgrade，包含 Origin 时直接 403 阻断
            val upgraded = SendspinWsFrameCodec.performServerUpgrade(input, output)
            if (!upgraded) {
                try { socket.close() } catch (_: Exception) {}
                return
            }
        }

        val privKey = Prefs.getOrCreateSendspinPrivateKey(ctx)
        val pubKey = SendspinCrypto.x25519PublicFromPrivate(privKey)
        val clientId = SendspinCrypto.base64UrlEncode(pubKey)
        val clientName = Prefs.getSendspinClientName(ctx)

        // 1. 发送 client/init
        val clientInitJson = JSONObject().apply {
            put("type", "client/init")
            put("payload", JSONObject().apply {
                put("version", 1)
                put("client_id", clientId)
                put("suite", "25519_AESGCM_SHA256")
            })
        }
        val clientInitBytes = clientInitJson.toString().toByteArray(Charsets.UTF_8)
        SendspinWsFrameCodec.writeFrame(output, SendspinWsFrameCodec.OPCODE_TEXT, clientInitBytes, maskClientToServer = isOutbound)

        // 2. 读取对端第一条消息（判断为 Noise 握手还是旧版明文）
        val firstMsg = SendspinWsFrameCodec.readMessage(input) { pingData ->
            SendspinWsFrameCodec.writeFrame(output, SendspinWsFrameCodec.OPCODE_PONG, pingData, maskClientToServer = isOutbound)
        } ?: return

        val firstText = String(firstMsg.payload, Charsets.UTF_8)
        val firstJson = try { JSONObject(firstText) } catch (e: Exception) { return }

        if (firstJson.optString("type") == "server/init") {
            // 模式 A: 现代 Noise_KKpsk2 加密握手
            handleNoiseEncryptedSession(socket, input, output, firstMsg.payload, firstJson, clientInitBytes, privKey, pubKey, clientName, isOutbound)
        } else if (firstJson.optString("type") == "server/hello") {
            // 模式 B: 旧版明文 Sendspin
            handleCleartextSession(socket, input, output, firstJson, clientName, isOutbound)
        }
    }

    private fun handleNoiseEncryptedSession(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        serverInitRawBytes: ByteArray,
        serverInitJson: JSONObject,
        clientInitRawBytes: ByteArray,
        clientPriv: ByteArray,
        clientPub: ByteArray,
        clientName: String,
        isOutbound: Boolean
    ) {
        val serverPayload = serverInitJson.optJSONObject("payload") ?: return
        val serverId = serverPayload.optString("server_id")
        val serverPub = SendspinCrypto.base64UrlDecode(serverId)
        if (serverPub.size != 32) return
        currentServerId = serverId

        // 读取 noise/handshake msg1
        val msg1Packet = SendspinWsFrameCodec.readMessage(input) {} ?: return
        val msg1Json = try { JSONObject(String(msg1Packet.payload, Charsets.UTF_8)) } catch (e: Exception) { return }
        if (msg1Json.optString("type") != "noise/handshake") return

        val msg1DataBase64 = msg1Json.optJSONObject("payload")?.optString("data") ?: return
        val msg1Bytes = SendspinCrypto.base64UrlDecode(msg1DataBase64)

        val prologue = ByteArray(clientInitRawBytes.size + serverInitRawBytes.size)
        System.arraycopy(clientInitRawBytes, 0, prologue, 0, clientInitRawBytes.size)
        System.arraycopy(serverInitRawBytes, 0, prologue, clientInitRawBytes.size, serverInitRawBytes.size)

        val responder = SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
        val (msg2Bytes, transport) = try {
            responder.processHandshakeMsg1AndBuildMsg2(msg1Bytes, loadPairingRecords(), activePairingPsk, allowSentinelFallback = true)
        } catch (e: Exception) {
            Log.e(TAG, "Noise 握手失败", e)
            return
        }
        currentMatchedPskCategory = transport.matchedCategory

        // 发送 noise/handshake msg2
        val msg2Json = JSONObject().apply {
            put("type", "noise/handshake")
            put("payload", JSONObject().apply {
                put("data", SendspinCrypto.base64UrlEncode(msg2Bytes))
            })
        }
        SendspinWsFrameCodec.writeFrame(
            output,
            SendspinWsFrameCodec.OPCODE_TEXT,
            msg2Json.toString().toByteArray(Charsets.UTF_8),
            maskClientToServer = isOutbound
        )

        // 切换至传输模式：设置双向加密发送器
        val lock = Any()
        frameSender = { opcode, plainBytes ->
            synchronized(lock) {
                try {
                    val cipher = transport.encryptClientToServer(plainBytes)
                    SendspinWsFrameCodec.writeFrame(output, SendspinWsFrameCodec.OPCODE_BINARY, cipher, maskClientToServer = isOutbound)
                } catch (e: Exception) {
                    Log.w(TAG, "发送加密帧异常", e)
                }
            }
        }

        // 切换至传输模式后，严守 Sendspin 规范状态机：
        // 1. 等待服务端主动发送 server/hello，客户端才回复 client/hello；
        // 2. 等待服务端发送 server/activate，客户端才发送 client/state 并启动 client/time 时钟同步；
        // 3. 收到带 pairing 的 server/activate（配对 PSK 会话）时，发送 client/pair-init +
        //    client/pair-finalize 完成配对，随后服务端会发起新 Noise 握手（re-handshake）；
        // 4. 传输模式下收到 noise/handshake 即为 re-handshake msg1，以旧握手 hash 为 prologue 应答。
        var noiseResponder = responder
        var noiseTransport = transport
        var priorHandshakeHash = transport.handshakeHash
        var pairingIndex = 0
        val fragReassembler = SendspinWsFrameCodec.FragmentReassembler()
        val artworkAssembler = SendspinWsFrameCodec.ArtworkAssembler()

        notifyListeners()

        while (running && !socket.isClosed) {
            val rawMsg = SendspinWsFrameCodec.readMessage(input) { pingData ->
                SendspinWsFrameCodec.writeFrame(output, SendspinWsFrameCodec.OPCODE_PONG, pingData, maskClientToServer = isOutbound)
            } ?: break

            val plain = try {
                noiseTransport.decryptServerToClient(rawMsg.payload)
            } catch (e: Exception) {
                Log.w(TAG, "解密传输帧失败", e)
                break
            }

            // 传输模式下 re-handshake 走 type 0 JSON 消息（type "noise/handshake"）
            if (plain.isNotEmpty() && plain[0].toInt() == 0) {
                val jsonStr = String(plain, 1, plain.size - 1, Charsets.UTF_8)
                val json = try { JSONObject(jsonStr) } catch (_: Exception) { null }
                if (json?.optString("type") == "noise/handshake") {
                    val result = handleRehandshake(
                        json, priorHandshakeHash, noiseResponder, clientPriv, clientPub
                    )
                    if (result == null) {
                        Log.e(TAG, "re-handshake 失败，断开连接")
                        break
                    }
                    noiseResponder = result.first
                    noiseTransport = result.second
                    priorHandshakeHash = noiseTransport.handshakeHash
                    currentMatchedPskCategory = noiseTransport.matchedCategory
                    pairingIndex = 0
                    Log.i(TAG, "re-handshake 完成，会话已切换至新密钥")
                    notifyListeners()
                    continue
                }
            }

            if (dispatchSendspinPacket(plain, clientName, fragReassembler, artworkAssembler)) {
                pairingIndex++
            }
        }

        closeActiveConnection()
    }

    /**
     * 处理传输模式下的 re-handshake：以旧握手 hash 为 prologue 建立新 Responder，
     * 处理 msg1（PSK 按类别重新匹配），回 msg2，返回新的传输密钥对。
     */
    private fun handleRehandshake(
        json: JSONObject,
        priorHandshakeHash: ByteArray,
        oldResponder: SendspinCrypto.NoiseResponder,
        clientPriv: ByteArray,
        clientPub: ByteArray
    ): Pair<SendspinCrypto.NoiseResponder, SendspinCrypto.TransportCipherPair>? {
        val dataB64 = json.optJSONObject("payload")?.optString("data") ?: return null
        val msg1Bytes = try {
            SendspinCrypto.base64UrlDecode(dataB64)
        } catch (_: Exception) { return null }

        // 服务端在 msg1 里指明新 PSK（升级为已配对会话时为配对记录的 lt PSK）
        val newResponder = SendspinCrypto.NoiseResponder.forRehandshake(
            priorHandshakeHash, clientPriv, clientPub, oldResponder.remoteStaticPub()
        )
        return try {
            val (msg2Bytes, newTransport) = newResponder.processHandshakeMsg1AndBuildMsg2(
                msg1Bytes, loadPairingRecords(), activePairingPsk, allowSentinelFallback = false
            )
            val sender = frameSender
            if (sender != null) {
                val msg2Json = JSONObject().apply {
                    put("type", "noise/handshake")
                    put("payload", JSONObject().apply {
                        put("data", SendspinCrypto.base64UrlEncode(msg2Bytes))
                    })
                }
                sender(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + msg2Json.toString().toByteArray(Charsets.UTF_8))
            }
            Pair(newResponder, newTransport)
        } catch (e: Exception) {
            Log.e(TAG, "re-handshake 握手失败", e)
            null
        }
    }

    private fun handleCleartextSession(
        socket: Socket,
        input: InputStream,
        output: OutputStream,
        serverHelloJson: JSONObject,
        clientName: String,
        isOutbound: Boolean
    ) {
        val serverPayload = serverHelloJson.optJSONObject("payload")
        serverName = serverPayload?.optString("name", "") ?: ""

        val lock = Any()
        frameSender = { opcode, payloadBytes ->
            synchronized(lock) {
                try {
                    SendspinWsFrameCodec.writeFrame(output, opcode, payloadBytes, maskClientToServer = isOutbound)
                } catch (e: Exception) {
                    Log.w(TAG, "发送明文帧异常", e)
                }
            }
        }

        // 收到 server/hello，回复 client/hello，等待 server/activate 激活
        sendClientHello(clientName)
        notifyListeners()

        val fragReassembler = SendspinWsFrameCodec.FragmentReassembler()
        val artworkAssembler = SendspinWsFrameCodec.ArtworkAssembler()

        while (running && !socket.isClosed) {
            val msg = SendspinWsFrameCodec.readMessage(input) { pingData ->
                SendspinWsFrameCodec.writeFrame(output, SendspinWsFrameCodec.OPCODE_PONG, pingData, maskClientToServer = isOutbound)
            } ?: break

            if (msg.opcode == SendspinWsFrameCodec.OPCODE_TEXT) {
                handleJsonPayload(JSONObject(String(msg.payload, Charsets.UTF_8)), clientName)
            } else if (msg.opcode == SendspinWsFrameCodec.OPCODE_BINARY) {
                dispatchSendspinPacket(msg.payload, clientName, fragReassembler, artworkAssembler)
            }
        }

        closeActiveConnection()
    }

    fun buildClientHelloJson(clientName: String, clientId: String, appVersion: String = "1.5.2"): JSONObject {
        return JSONObject().apply {
            put("type", "client/hello")
            put("payload", JSONObject().apply {
                put("version", 1)
                put("client_id", clientId)
                put("name", clientName)
                put("device_info", JSONObject().apply {
                    put("product_name", "YuJian TV")
                    put("manufacturer", "Android")
                    put("software_version", appVersion)
                })
                put("unpaired_access", JSONObject().apply { put("enabled", true) })
                put("supported_pair_methods", JSONObject().apply {
                    put("pairing_psk", JSONObject().apply {
                        put("locations", JSONArray().apply { put("device") })
                    })
                })
                put("supported_roles", JSONArray().apply {
                    put("player@v1")
                    put("metadata@v1")
                    put("artwork@v1")
                    put("controller@v1")
                })
                put("player@v1_support", JSONObject().apply {
                    put("supported_formats", JSONArray().apply {
                        put(JSONObject().apply { put("codec", "pcm"); put("channels", 2); put("sample_rate", 48000); put("bit_depth", 16) })
                        put(JSONObject().apply { put("codec", "pcm"); put("channels", 2); put("sample_rate", 44100); put("bit_depth", 16) })
                        put(JSONObject().apply { put("codec", "pcm"); put("channels", 1); put("sample_rate", 48000); put("bit_depth", 16) })
                        put(JSONObject().apply { put("codec", "pcm"); put("channels", 1); put("sample_rate", 44100); put("bit_depth", 16) })
                    })
                    // 声明 512KB ≈ 2.7s (48k/16bit/立体声)：仅作防断流上限。
                    // 播放时机由客户端按时间戳调度，预推多少不影响延迟
                    put("buffer_capacity", 524288)
                })
            })
        }
    }

    private fun sendClientHello(clientName: String) {
        val ctx = appContext
        val privKey = if (ctx != null) Prefs.getOrCreateSendspinPrivateKey(ctx) else ByteArray(32)
        val pubKey = SendspinCrypto.x25519PublicFromPrivate(privKey)
        val clientId = SendspinCrypto.base64UrlEncode(pubKey)
        val appVersion = if (ctx != null) {
            try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.5.2"
            } catch (_: Exception) { "1.5.2" }
        } else "1.5.2"

        val hello = buildClientHelloJson(clientName, clientId, appVersion)
        val bytes = hello.toString().toByteArray(Charsets.UTF_8)
        frameSender?.invoke(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + bytes)
    }

    private fun sendClientState() {
        val state = JSONObject().apply {
            put("type", "client/state")
            put("payload", JSONObject().apply {
                // 规范：可用性写在 client/state 顶层 available；旧的 player.state 已废弃
                put("available", true)
                put("player", JSONObject().apply {
                    put("volume", playerVolume)
                    put("muted", playerMuted)
                    // 必填时序字段：服务端据此在时间戳里预留启动/持续缓冲。
                    // output_delay_ms 上报实测播出误差（err），MA 据此平移本设备时间戳
                    put("output_delay_ms", reportedOutputDelayMs())
                    put("required_lead_time_ms", 250)
                    put("min_buffer_ms", 250)
                    put("supported_commands", JSONArray().apply {
                        put("volume")
                        put("mute")
                        put("set_output_delay")
                    })
                })
                put("artwork", JSONObject().apply {
                    put("channels", JSONArray().apply {
                        put(JSONObject().apply {
                            put("source", "album")
                            put("format", "jpeg")
                            put("width", 500)
                            put("height", 500)
                        })
                    })
                })
            })
        }
        val bytes = state.toString().toByteArray(Charsets.UTF_8)
        frameSender?.invoke(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + bytes)
    }

    /**
     * 上报给 MA 的输出延迟：实测播出误差（写末端 vs 时间戳），EMA 平滑。
     * 服务端用它计算本设备的缓冲完成时刻与迟到判定（发送节奏更贴合）；
     * 时间戳本身不平移（所有客户端收到相同时间戳，同步靠客户端锚定调度）。
     */
    private var reportedErrEmaUs = 0L

    private fun reportedOutputDelayMs(): Int {
        val errMs = audioPlayer?.measuredErrMs() ?: return 100
        reportedErrEmaUs = if (reportedErrEmaUs == 0L) errMs * 1000L
                          else (reportedErrEmaUs * 3 + errMs * 1000L) / 4
        return (reportedErrEmaUs / 1000L).toInt().coerceIn(0, 1000)
    }

    private fun startTimeSyncLoop() {
        timeSyncThread?.interrupt()
        val thread = Thread({
            var samplesSent = 0
            while (running && isConnected) {
                val t1 = System.nanoTime() / 1000L
                val timeMsg = JSONObject().apply {
                    put("type", "client/time")
                    put("payload", JSONObject().apply {
                        put("client_transmitted", t1)
                    })
                }
                val bytes = timeMsg.toString().toByteArray(Charsets.UTF_8)
                frameSender?.invoke(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + bytes)

                samplesSent++
                val sleepMs = if (samplesSent < 8) SYNC_INITIAL_INTERVAL_MS else SYNC_STEADY_INTERVAL_MS
                try { Thread.sleep(sleepMs) } catch (e: InterruptedException) { break }
            }
        }, "sendspin-time-sync")
        thread.isDaemon = true
        timeSyncThread = thread
        thread.start()
    }

    private fun dispatchSendspinPacket(
        packet: ByteArray,
        clientName: String,
        fragReassembler: SendspinWsFrameCodec.FragmentReassembler,
        artworkAssembler: SendspinWsFrameCodec.ArtworkAssembler
    ): Boolean {
        if (packet.isEmpty()) return false
        val msgId = packet[0].toInt() and 0xFF

        when (msgId) {
            0 -> {
                // UTF-8 JSON 消息
                val jsonStr = String(packet, 1, packet.size - 1, Charsets.UTF_8)
                val json = try { JSONObject(jsonStr) } catch (_: Exception) { return false }
                return handleJsonPayload(json, clientName)
            }
            1 -> {
                // 分片重组
                val assembled = fragReassembler.pushFragment(packet)
                if (assembled != null) {
                    val (innerId, data) = assembled
                    val merged = ByteArray(1 + data.size)
                    merged[0] = innerId.toByte()
                    System.arraycopy(data, 0, merged, 1, data.size)
                    return dispatchSendspinPacket(merged, clientName, fragReassembler, artworkAssembler)
                }
                return false
            }
            4 -> {
                // PCM 音频帧 [4][timestamp:8B BE][pcm...]
                if (packet.size >= 9) {
                    val ts = readLongBE(packet, 1)
                    val pcm = packet.copyOfRange(9, packet.size)
                    audioPlayer?.enqueueAudioChunk(ts, pcm)
                }
                return false
            }
            in 8..11 -> {
                // 专辑封面流
                when (val ev = artworkAssembler.onBinaryArtwork(packet)) {
                    is SendspinWsFrameCodec.ArtworkEvent.Completed -> {
                        val bmp = decodeSampledBitmap(ev.imageBytes, 600, 600)
                        activeArtwork = bmp
                        notifyListeners()
                    }
                    is SendspinWsFrameCodec.ArtworkEvent.Clear -> {
                        activeArtwork = null
                        notifyListeners()
                    }
                    else -> {}
                }
                return false
            }
        }
        return false
    }

    private fun handleJsonPayload(json: JSONObject, clientName: String): Boolean {
        val type = json.optString("type")
        val payload = json.optJSONObject("payload") ?: JSONObject()

        when (type) {
            "server/hello" -> {
                serverName = payload.optString("name", "")
                sendClientHello(clientName)
                notifyListeners()
            }
            "server/activate" -> {
                val pairingObj = payload.optJSONObject("pairing")
                val activities = payload.optJSONArray("activities")
                val isPairingActivation = pairingObj != null ||
                        (activities != null && hasString(activities, "pairing"))
                if (isPairingActivation) {
                    // 配对激活：按 Pairing PSK Flow，紧接发送 pair-init + pair-finalize（不等响应）。
                    // 客户端只支持 pairing_psk 方法；连接 PSK 不是配对 PSK 时按规范中止。
                    val method = pairingObj?.optString("method", "") ?: ""
                    val matchedCategory = currentMatchedPskCategory
                    if (method == "pairing_psk" && matchedCategory == "pr") {
                        sendPairingExchange()
                    } else {
                        sendPairAbort(if (method != "pairing_psk") "method_not_supported" else "method_not_supported")
                        Log.w(TAG, "配对激活被拒绝: method=$method matchedPsk=$matchedCategory")
                    }
                    notifyListeners()
                    return true
                }
                isConnected = true
                sendClientState()
                startTimeSyncLoop()
                notifyListeners()
            }
            "server/time" -> {
                if (!isConnected) {
                    isConnected = true
                    startTimeSyncLoop()
                }
                val t1 = payload.optLong("client_transmitted")
                val t2 = payload.optLong("server_received")
                val t3 = payload.optLong("server_transmitted")
                val t4 = System.nanoTime() / 1000L
                clockFilter.onTimeExchange(t1, t2, t3, t4)
                notifyListeners()
            }
            "stream/start" -> {
                if (!isConnected) {
                    isConnected = true
                    startTimeSyncLoop()
                }
                val playerObj = payload.optJSONObject("player")
                if (playerObj != null) {
                    val sampleRate = playerObj.optInt("sample_rate", 48000)
                    val channels = playerObj.optInt("channels", 2)
                    val bitDepth = playerObj.optInt("bit_depth", 16)
                    val format = SendspinPlayerFormat("pcm", sampleRate, channels, bitDepth)
                    currentFormat = format
                    // 新流开始：丢掉上一条流可能残留的音频，避免陈旧数据延后播放
                    audioPlayer?.clearBuffer()
                    audioPlayer?.configureStream(format)
                }
                notifyListeners()
            }
            "stream/clear" -> {
                val playerObj = payload.optJSONObject("player")
                val fromTs = if (playerObj?.has("from_timestamp") == true) playerObj.optLong("from_timestamp") else null
                audioPlayer?.clearBuffer(fromTs)
            }
            "stream/end" -> {
                val roles = payload.optJSONArray("roles")
                var hasPlayer = true
                if (roles != null) {
                    hasPlayer = false
                    for (i in 0 until roles.length()) {
                        if (roles.optString(i) == "player@v1") hasPlayer = true
                    }
                }
                if (hasPlayer) {
                    audioPlayer?.stopStream()
                }
            }
            "server/state" -> {
                // 1. Metadata
                val metaObj = payload.optJSONObject("metadata")
                if (metaObj != null) {
                    val wasPlaying = currentMetadata.playbackSpeed > 0
                    val oldTitle = currentMetadata.title
                    val oldArtist = currentMetadata.artist

                    val ts = metaObj.optLong("timestamp")
                    val title = metaObj.optString("title", "")
                    val artist = metaObj.optString("artist", "")
                    val albumArtist = metaObj.optString("album_artist", "")
                    val album = metaObj.optString("album", "")
                    val artworkUrl = metaObj.optString("artwork_url", "")
                    val year = metaObj.optInt("year", 0)
                    val track = metaObj.optInt("track", 0)
                    val progObj = metaObj.optJSONObject("progress")
                    val hasProg = progObj != null
                    val progMs = progObj?.optLong("track_progress", 0L) ?: 0L
                    val durMs = progObj?.optLong("track_duration", 0L) ?: 0L
                    val speed = progObj?.optInt("playback_speed", 0) ?: 0
                    val embeddedLrc = metaObj.optString("lyrics", metaObj.optString("lrc", ""))

                    currentMetadata = SendspinTrackMetadata(
                        timestampUs = ts,
                        title = title,
                        artist = artist,
                        albumArtist = albumArtist,
                        album = album,
                        artworkUrl = artworkUrl,
                        year = year,
                        track = track,
                        hasProgress = hasProg,
                        trackProgressMs = progMs,
                        trackDurationMs = durMs,
                        playbackSpeed = speed,
                        embeddedLyrics = embeddedLrc
                    )

                    // 曲目变更时异步检索歌词
                    if (title != oldTitle || artist != oldArtist) {
                        activeLyrics = null
                        val ctx = appContext
                        if (ctx != null && title.isNotBlank()) {
                            LyricsFetcher.fetchAsync(ctx, title, artist, album, durMs, embeddedLrc) { lyrics ->
                                if (currentMetadata.title == title && currentMetadata.artist == artist) {
                                    activeLyrics = lyrics
                                    notifyListeners()
                                }
                            }
                        }
                    }

                    // 自动拉起全屏歌词大屏
                    if (!wasPlaying && speed > 0) {
                        val ctx = appContext
                        if (ctx != null && Prefs.isSendspinAutoPopup(ctx)) {
                            autoPopupCallback?.invoke()
                        }
                    }
                }

                // 2. Controller
                val ctrlObj = payload.optJSONObject("controller")
                if (ctrlObj != null) {
                    val cmds = HashSet<String>()
                    val cmdArr = ctrlObj.optJSONArray("supported_commands")
                    if (cmdArr != null) {
                        for (i in 0 until cmdArr.length()) cmds.add(cmdArr.optString(i))
                    }
                    val vol = ctrlObj.optInt("volume", 100)
                    val muted = ctrlObj.optBoolean("muted", false)
                    val rep = ctrlObj.optString("repeat", "off")
                    val shuf = ctrlObj.optBoolean("shuffle", false)
                    val seekMax = ctrlObj.optLong("seek_max_ms", 0L)

                    currentController = SendspinControllerState(cmds, vol, muted, rep, shuf, seekMax)
                    playerVolume = vol
                    playerMuted = muted
                    audioPlayer?.setVolumeAndMute(vol, muted)
                }

                // 3. Color
                val colorObj = payload.optJSONObject("color")
                if (colorObj != null) {
                    val ts = colorObj.optLong("timestamp")
                    val bgRgb = parseRgbArray(colorObj.optJSONArray("background_dark"))
                    val priRgb = parseRgbArray(colorObj.optJSONArray("primary"))
                    val accRgb = parseRgbArray(colorObj.optJSONArray("accent"))
                    currentColorPalette = SendspinColorPalette(ts, bgRgb, priRgb, accRgb)
                }

                notifyListeners()
            }
            "server/command" -> {
                // 规范：播放器命令在 payload.player 里；旧实现读 payload.controller 是错的
                val cmdObj = payload.optJSONObject("player") ?: payload.optJSONObject("controller")
                val cmd = cmdObj?.optString("command")
                if (cmd == "volume") {
                    val vol = cmdObj.optInt("volume", playerVolume)
                    playerVolume = vol
                    audioPlayer?.setVolumeAndMute(vol, playerMuted)
                    notifyListeners()
                } else if (cmd == "mute") {
                    val muted = cmdObj.optBoolean("mute", playerMuted)
                    playerMuted = muted
                    audioPlayer?.setVolumeAndMute(playerVolume, muted)
                    notifyListeners()
                } else if (cmd == "set_output_delay") {
                    val delayMs = cmdObj.optInt("output_delay_ms", -1)
                    if (delayMs >= 0) {
                        audioPlayer?.setOutputDelayMs(delayMs)
                        Log.i(TAG, "服务端设置输出延迟补偿: ${delayMs}ms")
                    }
                }
            }
            "server/pair-finalize" -> {
                // 规范：payload 为空对象，仅表示服务端已持久化配对记录。
                // 长期 PSK 由客户端在 client/pair-finalize 中生成并先于服务端保存（见 sendPairingExchange）。
                Log.i(TAG, "服务端已确认配对记录（server/pair-finalize），等待 re-handshake 升级为已配对会话")
                notifyListeners()
            }
        }
        return false
    }

    private fun hasString(arr: JSONArray, value: String): Boolean {
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == value) return true
        }
        return false
    }

    /**
     * Pairing PSK Flow 的配对交换：生成 32 字节长期 PSK，本地先保存配对记录，
     * 随后连续发送 client/pair-init 与 client/pair-finalize（不等服务端响应）。
     * 服务端确认后会发起 re-handshake 切换到新长期 PSK。
     */
    private fun sendPairingExchange() {
        val ctx = appContext ?: return
        val serverId = currentServerId
        if (serverId.isEmpty()) {
            Log.w(TAG, "无法配对：未知 server_id")
            return
        }
        val longTermPsk = ByteArray(32)
        SecureRandom().nextBytes(longTermPsk)
        savePairingRecord(serverId, longTermPsk)
        Log.i(TAG, "开始 Pairing PSK 配对交换（server_id=${serverId.take(8)}…）")

        val sender = frameSender ?: return
        val pairInit = JSONObject().apply {
            put("type", "client/pair-init")
            put("payload", JSONObject().apply {
                put("pairing_index", 0)
            })
        }
        val pairFinalize = JSONObject().apply {
            put("type", "client/pair-finalize")
            put("payload", JSONObject().apply {
                put("long_term_psk", SendspinCrypto.base64UrlEncode(longTermPsk))
            })
        }
        sender(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + pairInit.toString().toByteArray(Charsets.UTF_8))
        sender(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + pairFinalize.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendPairAbort(reason: String) {
        val sender = frameSender ?: return
        val abort = JSONObject().apply {
            put("type", "pair/abort")
            put("payload", JSONObject().apply {
                put("reason", reason)
            })
        }
        sender(SendspinWsFrameCodec.OPCODE_BINARY, byteArrayOf(0) + abort.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readLongBE(b: ByteArray, offset: Int): Long {
        return ((b[offset].toLong() and 0xFF shl 56) or
                (b[offset + 1].toLong() and 0xFF shl 48) or
                (b[offset + 2].toLong() and 0xFF shl 40) or
                (b[offset + 3].toLong() and 0xFF shl 32) or
                (b[offset + 4].toLong() and 0xFF shl 24) or
                (b[offset + 5].toLong() and 0xFF shl 16) or
                (b[offset + 6].toLong() and 0xFF shl 8) or
                (b[offset + 7].toLong() and 0xFF))
    }

    private fun parseRgbArray(arr: JSONArray?): Int? {
        if (arr == null || arr.length() < 3) return null
        val r = arr.optInt(0, 0).coerceIn(0, 255)
        val g = arr.optInt(1, 0).coerceIn(0, 255)
        val b = arr.optInt(2, 0).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun decodeSampledBitmap(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadPairingRecords(): List<SendspinCrypto.PairingRecord> {
        val ctx = appContext ?: return emptyList()
        val raw = Prefs.getSendspinPairingsJson(ctx) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<SendspinCrypto.PairingRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val sId = obj.getString("server_id")
                val psk = SendspinCrypto.base64UrlDecode(obj.getString("psk"))
                list.add(SendspinCrypto.PairingRecord(sId, psk))
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePairingRecord(serverId: String, psk: ByteArray) {
        val ctx = appContext ?: return
        val records = loadPairingRecords().filter { it.serverId != serverId }.toMutableList()
        records.add(SendspinCrypto.PairingRecord(serverId, psk))
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("server_id", r.serverId)
                put("psk", SendspinCrypto.base64UrlEncode(r.psk))
            })
        }
        Prefs.setSendspinPairingsJson(ctx, arr.toString())
    }
}
