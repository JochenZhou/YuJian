package com.zqc.tvnotify

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 纯 Kotlin 零依赖 RFC 6455 WebSocket 协议编解码器与 Sendspin 分片/封面组装器。
 */
object SendspinWsFrameCodec {

    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private val random = SecureRandom()

    data class WsMessage(val opcode: Int, val payload: ByteArray)

    // ------------------------------------------------------------------
    // WebSocket 握手
    // ------------------------------------------------------------------

    fun performServerUpgrade(
        input: InputStream,
        output: OutputStream,
        expectedPath: String = "/sendspin"
    ): Boolean {
        val headerText = readHttpHeader(input) ?: return false
        val lines = headerText.lines()
        if (lines.isEmpty()) return false

        val requestLine = lines[0].trim()
        val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: ""
        if (path != expectedPath) {
            writeHttp404(output)
            return false
        }

        var secKey = ""
        var hasOrigin = false
        var isUpgrade = false

        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            if (key == "origin") {
                hasOrigin = true
            } else if (key == "upgrade" && value.contains("websocket", ignoreCase = true)) {
                isUpgrade = true
            } else if (key == "sec-websocket-key") {
                secKey = value
            }
        }

        // Sendspin 规范 line 183: The client MUST reject a WebSocket upgrade request to its Sendspin endpoint that carries an Origin header.
        if (hasOrigin) {
            writeHttp403(output, "Origin header forbidden on Sendspin endpoint")
            return false
        }

        if (!isUpgrade || secKey.isEmpty()) {
            writeHttp400(output, "Missing Upgrade or Sec-WebSocket-Key")
            return false
        }

        val acceptKey = computeAcceptKey(secKey)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
        return true
    }

    fun performClientUpgrade(
        input: InputStream,
        output: OutputStream,
        hostHeader: String,
        path: String
    ): Boolean {
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        val key = SendspinCrypto.base64Encode(nonce)

        val req = "GET $path HTTP/1.1\r\n" +
                "Host: $hostHeader\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
        output.write(req.toByteArray(Charsets.US_ASCII))
        output.flush()

        val respHeader = readHttpHeader(input) ?: return false
        val statusLine = respHeader.lines().firstOrNull()?.trim() ?: ""
        return statusLine.contains("101")
    }

    private fun computeAcceptKey(key: String): String {
        val merged = (key.trim() + WS_GUID).toByteArray(Charsets.US_ASCII)
        val sha1 = MessageDigest.getInstance("SHA-1").digest(merged)
        return SendspinCrypto.base64Encode(sha1)
    }

    private fun readHttpHeader(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            out.write(b)
            if (b == '\r'.code && (matched == 0 || matched == 2)) {
                matched++
            } else if (b == '\n'.code && (matched == 1 || matched == 3)) {
                matched++
                if (matched == 4) break
            } else {
                matched = 0
            }
            if (out.size() > 8192) return null
        }
        return out.toString("UTF-8")
    }

    private fun writeHttp403(output: OutputStream, reason: String) {
        val resp = "HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Type: text/plain\r\n\r\n$reason\r\n"
        output.write(resp.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeHttp404(output: OutputStream) {
        val resp = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
        output.write(resp.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun writeHttp400(output: OutputStream, msg: String) {
        val resp = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Type: text/plain\r\n\r\n$msg\r\n"
        output.write(resp.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    // ------------------------------------------------------------------
    // WebSocket 帧收发
    // ------------------------------------------------------------------

    fun readMessage(
        input: InputStream,
        maxPayloadBytes: Int = 2 * 1024 * 1024,
        onPing: (ByteArray) -> Unit
    ): WsMessage? {
        var firstOpcode = -1
        val continuationBuffer = ByteArrayOutputStream()

        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F

            val b1 = input.read()
            if (b1 < 0) return null
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val len0 = input.read()
                val len1 = input.read()
                if (len0 < 0 || len1 < 0) return null
                payloadLen = ((len0 shl 8) or len1).toLong()
            } else if (payloadLen == 127L) {
                var len64 = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b < 0) return null
                    len64 = (len64 shl 8) or (b.toLong() and 0xFF)
                }
                payloadLen = len64
            }

            if (payloadLen > maxPayloadBytes || payloadLen < 0L) {
                return null
            }

            val maskKey = if (masked) {
                val m = ByteArray(4)
                readFully(input, m)
                m
            } else null

            val chunk = ByteArray(payloadLen.toInt())
            readFully(input, chunk)
            if (maskKey != null) {
                for (i in chunk.indices) {
                    chunk[i] = (chunk[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }

            when (opcode) {
                OPCODE_PING -> {
                    onPing(chunk)
                    continue
                }
                OPCODE_PONG -> {
                    continue
                }
                OPCODE_CLOSE -> {
                    return WsMessage(OPCODE_CLOSE, chunk)
                }
                OPCODE_TEXT, OPCODE_BINARY -> {
                    if (firstOpcode == -1) {
                        firstOpcode = opcode
                    }
                    continuationBuffer.write(chunk)
                    if (fin) {
                        return WsMessage(firstOpcode, continuationBuffer.toByteArray())
                    }
                }
                OPCODE_CONTINUATION -> {
                    continuationBuffer.write(chunk)
                    if (fin) {
                        val finalOpcode = if (firstOpcode != -1) firstOpcode else OPCODE_BINARY
                        return WsMessage(finalOpcode, continuationBuffer.toByteArray())
                    }
                }
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw java.io.EOFException("Premature EOF reading websocket frame")
            offset += count
        }
    }

    fun writeFrame(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
        maskClientToServer: Boolean = false
    ) {
        val b0 = 0x80 or (opcode and 0x0F) // FIN=1
        output.write(b0)

        val maskBit = if (maskClientToServer) 0x80 else 0x00
        val len = payload.size
        when {
            len <= 125 -> {
                output.write(maskBit or len)
            }
            len <= 65535 -> {
                output.write(maskBit or 126)
                output.write((len ushr 8) and 0xFF)
                output.write(len and 0xFF)
            }
            else -> {
                output.write(maskBit or 127)
                for (i in 7 downTo 0) {
                    output.write(((len.toLong() ushr (i * 8)) and 0xFF).toInt())
                }
            }
        }

        if (maskClientToServer) {
            val mask = ByteArray(4)
            random.nextBytes(mask)
            output.write(mask)
            val maskedPayload = ByteArray(len)
            for (i in 0 until len) {
                maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            output.write(maskedPayload)
        } else {
            output.write(payload)
        }
        output.flush()
    }

    fun writeRawFrameForTest(
        output: OutputStream,
        fin: Boolean,
        opcode: Int,
        payload: ByteArray,
        masked: Boolean
    ) {
        val b0 = (if (fin) 0x80 else 0x00) or (opcode and 0x0F)
        output.write(b0)
        val maskBit = if (masked) 0x80 else 0x00
        val len = payload.size
        when {
            len <= 125 -> output.write(maskBit or len)
            len <= 65535 -> {
                output.write(maskBit or 126)
                output.write((len ushr 8) and 0xFF)
                output.write(len and 0xFF)
            }
            else -> {
                output.write(maskBit or 127)
                for (i in 7 downTo 0) output.write(((len.toLong() ushr (i * 8)) and 0xFF).toInt())
            }
        }
        if (masked) {
            val mask = byteArrayOf(1, 2, 3, 4)
            output.write(mask)
            val maskedPayload = ByteArray(len)
            for (i in 0 until len) {
                maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            output.write(maskedPayload)
        } else {
            output.write(payload)
        }
    }

    // ------------------------------------------------------------------
    // Sendspin 分片与封面流重组
    // ------------------------------------------------------------------

    class FragmentReassembler {
        private var buffer = ByteArrayOutputStream()
        private var innerMsgId: Int = -1

        fun pushFragment(packet: ByteArray): Pair<Int, ByteArray>? {
            if (packet.size < 3 || packet[0].toInt() != 1) return null
            val flags = packet[1].toInt() and 0xFF
            val isFinal = (flags and 0x01) != 0
            if (innerMsgId == -1) {
                innerMsgId = packet[2].toInt() and 0xFF
            }
            buffer.write(packet, 3, packet.size - 3)
            if (isFinal) {
                val assembled = buffer.toByteArray()
                val id = innerMsgId
                buffer = ByteArrayOutputStream()
                innerMsgId = -1
                return Pair(id, assembled)
            }
            return null
        }
    }

    sealed class ArtworkEvent {
        object InFlight : ArtworkEvent()
        object Clear : ArtworkEvent()
        class Completed(val imageBytes: ByteArray) : ArtworkEvent()
    }

    class ArtworkAssembler {
        private var totalSize = -1
        private var buffer = ByteArrayOutputStream()

        fun onBinaryArtwork(packet: ByteArray): ArtworkEvent {
            if (packet.size < 2) return ArtworkEvent.InFlight
            val flags = packet[1].toInt() and 0xFF

            // 旧版单包格式兼容：[8][timestamp:8][image_bytes]
            if (packet.size >= 10 &&
                ((packet[9].toInt() and 0xFF) == 0xFF && (packet[10].toInt() and 0xFF) == 0xD8 ||
                 (packet[9].toInt() and 0xFF) == 0x89 && (packet[10].toInt() and 0xFF) == 0x50)
            ) {
                val img = packet.copyOfRange(9, packet.size)
                return ArtworkEvent.Completed(img)
            }

            // 新版 Sendspin 规范 (Flags: bit 0 = cancel, bit 1 = announce, 0 = part)
            if ((flags and 0x01) != 0) { // Cancel
                buffer.reset()
                totalSize = -1
                return ArtworkEvent.Clear
            }

            if ((flags and 0x02) != 0) { // Announce: [type:1][flags:1][timestamp:8][total_size:4]
                if (packet.size >= 14) {
                    val size = ((packet[10].toLong() and 0xFF shl 24) or
                            (packet[11].toLong() and 0xFF shl 16) or
                            (packet[12].toLong() and 0xFF shl 8) or
                            (packet[13].toLong() and 0xFF)).toInt()
                    totalSize = size
                    buffer.reset()
                    if (size == 0) {
                        return ArtworkEvent.Clear
                    }
                }
                return ArtworkEvent.InFlight
            }

            // Part: [type:1][flags:1][data...]
            if (totalSize > 0) {
                buffer.write(packet, 2, packet.size - 2)
                if (buffer.size() >= totalSize) {
                    val completed = buffer.toByteArray()
                    buffer.reset()
                    totalSize = -1
                    return ArtworkEvent.Completed(completed)
                }
            }
            return ArtworkEvent.InFlight
        }
    }
}
