package com.zqc.tvnotify

import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 纯 Kotlin 零依赖 Sendspin 加密协议与 Noise_KKpsk2_25519_AESGCM_SHA256 实现。
 *
 * 核心特性：
 * 1. RFC 7748 X25519 纯 Kotlin 蒙哥马利阶梯实现，使用标准 java.math.BigInteger，
 *    无 Java 11+ XEC 依赖，完全兼容 Android 6.0（API 23）；
 * 2. 严格校验全零/低阶公钥，防伪造攻击；
 * 3. 完整支持 Noise KKpsk2 握手（Responder 状态机）、Sentinel PSK 回退与配对码生成；
 * 4. AES-GCM-128 加解密（Nonce 前 4 字节为 0，后 8 字节为大端序无符号帧计数器）。
 */
object SendspinCrypto {

    const val NOISE_PROTOCOL_NAME = "Noise_KKpsk2_25519_AESGCM_SHA256"

    val SENTINEL_PSK: ByteArray by lazy {
        sha256("sendspin-sentinel-psk-v1".toByteArray(Charsets.UTF_8))
    }

    const val SENTINEL_PSK_ID = "GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo"

    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT_U: BigInteger = BigInteger.valueOf(9)

    // ------------------------------------------------------------------
    // Base64URL 纯 Kotlin 编解码（无填充，API 23 与 JVM 通用）
    // ------------------------------------------------------------------

    private const val BASE64URL_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun base64UrlEncode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1

            out.append(BASE64URL_CHARS[b0 shr 2])
            if (b1 >= 0) {
                out.append(BASE64URL_CHARS[((b0 and 0x03) shl 4) or (b1 shr 4)])
                if (b2 >= 0) {
                    out.append(BASE64URL_CHARS[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                    out.append(BASE64URL_CHARS[b2 and 0x3F])
                } else {
                    out.append(BASE64URL_CHARS[(b1 and 0x0F) shl 2])
                }
            } else {
                out.append(BASE64URL_CHARS[(b0 and 0x03) shl 4])
            }
            i += 3
        }
        return out.toString()
    }

    fun base64UrlDecode(text: String): ByteArray {
        val clean = text.trim().replace("=", "")
        val len = clean.length
        val out = java.io.ByteArrayOutputStream(len * 3 / 4)
        var i = 0
        while (i < len) {
            val c0 = decodeChar(clean[i])
            val c1 = if (i + 1 < len) decodeChar(clean[i + 1]) else 0
            val c2 = if (i + 2 < len) decodeChar(clean[i + 2]) else -1
            val c3 = if (i + 3 < len) decodeChar(clean[i + 3]) else -1

            val b0 = (c0 shl 2) or (c1 shr 4)
            out.write(b0)
            if (c2 >= 0) {
                val b1 = ((c1 and 0x0F) shl 4) or (c2 shr 2)
                out.write(b1)
                if (c3 >= 0) {
                    val b2 = ((c2 and 0x03) shl 6) or c3
                    out.write(b2)
                }
            }
            i += 4
        }
        return out.toByteArray()
    }

    private fun decodeChar(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '-' -> 62
        '_' -> 63
        else -> -1
    }

    // ------------------------------------------------------------------
    // RFC 7748 X25519 纯 Kotlin 实现
    // ------------------------------------------------------------------

    fun x25519PublicFromPrivate(privateKey32: ByteArray): ByteArray {
        return x25519Ladder(privateKey32, BASE_POINT_U)
    }

    fun x25519SharedSecret(privateKey32: ByteArray, peerPublic32: ByteArray): ByteArray {
        if (peerPublic32.size != 32) {
            throw SecurityException("Invalid peer public key length: ${peerPublic32.size}")
        }
        val u = decodeUCoordinate(peerPublic32)
        val secret = x25519Ladder(privateKey32, u)
        var allZero = true
        for (b in secret) {
            if (b.toInt() != 0) {
                allZero = false
                break
            }
        }
        if (allZero) {
            throw SecurityException("X25519 shared secret is all zeros (low-order public key attack)")
        }
        return secret
    }

    private fun decodeUCoordinate(bytes: ByteArray): BigInteger {
        val copy = bytes.copyOf(32)
        copy[31] = (copy[31].toInt() and 0x7F).toByte()
        return decodeLittleEndian(copy).mod(P)
    }

    private fun decodeLittleEndian(buf: ByteArray): BigInteger {
        var res = BigInteger.ZERO
        for (i in 0 until 32) {
            val b = BigInteger.valueOf((buf[i].toInt() and 0xFF).toLong())
            res = res.or(b.shiftLeft(8 * i))
        }
        return res
    }

    private fun encodeLittleEndian(n: BigInteger): ByteArray {
        val buf = ByteArray(32)
        var v = n.mod(P)
        for (i in 0 until 32) {
            buf[i] = v.and(BigInteger.valueOf(0xFF)).toByte()
            v = v.shiftRight(8)
        }
        return buf
    }

    private fun clampKey(priv: ByteArray): BigInteger {
        val k = priv.copyOf(32)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return decodeLittleEndian(k)
    }

    private fun x25519Ladder(privateKey32: ByteArray, u: BigInteger): ByteArray {
        val k = clampKey(privateKey32)
        val x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (i in 254 downTo 0) {
            val ki = k.shiftRight(i).and(BigInteger.ONE).toInt()
            swap = swap xor ki
            if (swap != 0) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = ki

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            val daPlusCb = da.add(cb).mod(P)
            x3 = daPlusCb.multiply(daPlusCb).mod(P)
            val daMinusCb = da.subtract(cb).mod(P)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            val a24E = A24.multiply(e).mod(P)
            z2 = e.multiply(aa.add(a24E).mod(P)).mod(P)
        }
        if (swap != 0) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }
        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        return encodeLittleEndian(result)
    }

    // ------------------------------------------------------------------
    // 哈希、HKDF 与 PSK 计算
    // ------------------------------------------------------------------

    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdf(ck: ByteArray, ikm: ByteArray, numOutputs: Int): List<ByteArray> {
        val prk = hmacSha256(ck, ikm)
        val outputs = ArrayList<ByteArray>(numOutputs)
        var prev = ByteArray(0)
        for (i in 1..numOutputs) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            if (prev.isNotEmpty()) {
                mac.update(prev)
            }
            mac.update(byteArrayOf(i.toByte()))
            prev = mac.doFinal()
            outputs.add(prev)
        }
        return outputs
    }

    fun computePskId(psk32: ByteArray): String {
        val prefix = "sendspin-psk-id-v1".toByteArray(Charsets.UTF_8)
        val merged = ByteArray(prefix.size + psk32.size)
        System.arraycopy(prefix, 0, merged, 0, prefix.size)
        System.arraycopy(psk32, 0, merged, prefix.size, psk32.size)
        return base64UrlEncode(sha256(merged))
    }

    fun buildPairingToken(clientId43: String, pairingPsk32: ByteArray): String {
        return "SP:0$clientId43${base64UrlEncode(pairingPsk32)}"
    }

    data class PairingRecord(val serverId: String, val psk: ByteArray)

    class TransportCipherPair(
        val decryptServerToClient: (ByteArray) -> ByteArray,
        val encryptClientToServer: (ByteArray) -> ByteArray,
        val handshakeHash: ByteArray,
        val matchedCategory: String
    )

    // ------------------------------------------------------------------
    // AES-GCM 单帧处理
    // ------------------------------------------------------------------

    private fun buildNonce(counter: Long): ByteArray {
        val nonce = ByteArray(12)
        nonce[4] = (counter ushr 56 and 0xFF).toByte()
        nonce[5] = (counter ushr 48 and 0xFF).toByte()
        nonce[6] = (counter ushr 40 and 0xFF).toByte()
        nonce[7] = (counter ushr 32 and 0xFF).toByte()
        nonce[8] = (counter ushr 24 and 0xFF).toByte()
        nonce[9] = (counter ushr 16 and 0xFF).toByte()
        nonce[10] = (counter ushr 8 and 0xFF).toByte()
        nonce[11] = (counter and 0xFF).toByte()
        return nonce
    }

    fun aesGcmEncrypt(key: ByteArray, counter: Long, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, buildNonce(counter))
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(plaintext)
    }

    fun aesGcmDecrypt(key: ByteArray, counter: Long, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, buildNonce(counter))
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        if (aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    // ------------------------------------------------------------------
    // Noise_KKpsk2_25519_AESGCM_SHA256 握手状态机
    // ------------------------------------------------------------------

    class NoiseResponder(
        private val clientPriv: ByteArray,
        private val clientPub: ByteArray,
        private val serverPub: ByteArray,
        private val prologue: ByteArray
    ) {
        private var ck: ByteArray = NOISE_PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        private var h: ByteArray = NOISE_PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)

        init {
            // MixHash(prologue)
            mixHash(prologue)
            // Pre-messages for KK:
            // -> s (server)
            mixHash(serverPub)
            // <- s (client)
            mixHash(clientPub)
        }

        private fun mixHash(data: ByteArray) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(h)
            md.update(data)
            h = md.digest()
        }

        private fun mixKey(ikm: ByteArray): ByteArray {
            val outputs = hkdf(ck, ikm, 2)
            ck = outputs[0]
            return outputs[1]
        }

        fun processHandshakeMsg1AndBuildMsg2(
            msg1Bytes: ByteArray,
            longTermRecords: List<PairingRecord>,
            activePairingPsk: ByteArray?,
            allowSentinelFallback: Boolean = true
        ): Pair<ByteArray, TransportCipherPair> {
            if (msg1Bytes.size < 32 + 16) {
                throw SecurityException("Handshake message 1 is too short: ${msg1Bytes.size}")
            }
            // 1. Token 'e': 读取前 32 字节服务端临时公钥 re
            val re = msg1Bytes.copyOfRange(0, 32)
            mixHash(re)

            // 2. Token 'es': es = X25519(clientPriv, re)
            val es = x25519SharedSecret(clientPriv, re)
            var k = mixKey(es)

            // 3. Token 'ss': ss = X25519(clientPriv, serverPub)
            val ss = x25519SharedSecret(clientPriv, serverPub)
            k = mixKey(ss)

            // 4. Decrypt payload
            val ciphertext = msg1Bytes.copyOfRange(32, msg1Bytes.size)
            val payloadBytes = try {
                aesGcmDecrypt(k, 0L, ciphertext, h)
            } catch (e: Exception) {
                throw SecurityException("Failed to decrypt msg1 payload", e)
            }
            mixHash(ciphertext)

            val innerJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
            val pskId = innerJson.optString("psk_id", "")
            val pskCategory = innerJson.optString("psk_category", "sn")

            // 匹配 PSK
            var selectedPsk: ByteArray? = null
            var finalCategory = pskCategory

            when (pskCategory) {
                "lt" -> {
                    val match = longTermRecords.firstOrNull { computePskId(it.psk) == pskId }
                    if (match != null) {
                        // 匹配成功时校验 serverId 是否一致
                        val expectedServerId = base64UrlEncode(serverPub)
                        if (match.serverId != expectedServerId) {
                            throw SecurityException("Long-term PSK bound to server_id ${match.serverId} does not match actual $expectedServerId")
                        }
                        selectedPsk = match.psk
                    }
                }
                "pr" -> {
                    if (activePairingPsk != null && computePskId(activePairingPsk) == pskId) {
                        selectedPsk = activePairingPsk
                    }
                }
                "sn" -> {
                    selectedPsk = SENTINEL_PSK
                }
            }

            if (selectedPsk == null) {
                if (allowSentinelFallback) {
                    selectedPsk = SENTINEL_PSK
                    finalCategory = "sn"
                } else {
                    throw SecurityException("No matching PSK found for psk_id: $pskId ($pskCategory)")
                }
            }

            // 构造 Message 2: tokens [e, ee, se, psk]
            val random = SecureRandom()
            val ePriv = ByteArray(32)
            random.nextBytes(ePriv)
            val ePub = x25519PublicFromPrivate(ePriv)

            // 1. Token 'e'
            mixHash(ePub)

            // 2. Token 'ee': ee = X25519(ePriv, re)
            val ee = x25519SharedSecret(ePriv, re)
            k = mixKey(ee)

            // 3. Token 'se': se = X25519(ePriv, serverPub)
            val se = x25519SharedSecret(ePriv, serverPub)
            k = mixKey(se)

            // 4. Token 'psk': MixKeyAndHash(selectedPsk)
            val hkdfPsk = hkdf(ck, selectedPsk, 3)
            ck = hkdfPsk[0]
            val tempHash = hkdfPsk[1]
            k = hkdfPsk[2]
            mixHash(tempHash)

            // 5. Encrypt empty payload "{}"
            val innerPayload2 = "{}".toByteArray(Charsets.UTF_8)
            val cipherPayload2 = aesGcmEncrypt(k, 0L, innerPayload2, h)
            mixHash(cipherPayload2)

            val msg2Bytes = ByteArray(32 + cipherPayload2.size)
            System.arraycopy(ePub, 0, msg2Bytes, 0, 32)
            System.arraycopy(cipherPayload2, 0, msg2Bytes, 32, cipherPayload2.size)

            // Split into transport mode
            val splitOutputs = hkdf(ck, ByteArray(0), 2)
            val k1 = splitOutputs[0] // Initiator -> Responder (Server to Client)
            val k2 = splitOutputs[1] // Responder -> Initiator (Client to Server)

            var serverToClientCounter = 0L
            var clientToServerCounter = 0L

            val decryptServerToClient: (ByteArray) -> ByteArray = { cipherData ->
                synchronized(this) {
                    val plain = aesGcmDecrypt(k1, serverToClientCounter, cipherData, ByteArray(0))
                    serverToClientCounter++
                    plain
                }
            }

            val encryptClientToServer: (ByteArray) -> ByteArray = { plainData ->
                synchronized(this) {
                    val cipher = aesGcmEncrypt(k2, clientToServerCounter, plainData, ByteArray(0))
                    clientToServerCounter++
                    cipher
                }
            }

            val transport = TransportCipherPair(decryptServerToClient, encryptClientToServer, h.clone(), finalCategory)
            return Pair(msg2Bytes, transport)
        }
    }

    // 仅供测试的 Initiator 辅助类（模拟服务端）
    class TestInitiator(
        private val serverPriv: ByteArray,
        private val serverPub: ByteArray,
        private val clientPub: ByteArray,
        prologue: ByteArray
    ) {
        private var ck: ByteArray = NOISE_PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        private var h: ByteArray = NOISE_PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        private val ePriv = ByteArray(32) { (it + 7).toByte() }
        private val ePub = x25519PublicFromPrivate(ePriv)

        init {
            mixHash(prologue)
            mixHash(serverPub)
            mixHash(clientPub)
        }

        private fun mixHash(data: ByteArray) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(h)
            md.update(data)
            h = md.digest()
        }

        private fun mixKey(ikm: ByteArray): ByteArray {
            val outputs = hkdf(ck, ikm, 2)
            ck = outputs[0]
            return outputs[1]
        }

        fun buildMsg1(pskId: String, pskCategory: String): ByteArray {
            mixHash(ePub)
            val es = x25519SharedSecret(ePriv, clientPub)
            var k = mixKey(es)
            val ss = x25519SharedSecret(serverPriv, clientPub)
            k = mixKey(ss)
            val payload = "{\"psk_id\":\"$pskId\",\"psk_category\":\"$pskCategory\"}".toByteArray(Charsets.UTF_8)
            val ciphertext = aesGcmEncrypt(k, 0L, payload, h)
            mixHash(ciphertext)
            val msg1 = ByteArray(32 + ciphertext.size)
            System.arraycopy(ePub, 0, msg1, 0, 32)
            System.arraycopy(ciphertext, 0, msg1, 32, ciphertext.size)
            return msg1
        }

        fun processMsg2(msg2Bytes: ByteArray, expectedPsk: ByteArray): ServerTransportForTest {
            val re = msg2Bytes.copyOfRange(0, 32)
            mixHash(re)
            val ee = x25519SharedSecret(ePriv, re)
            var k = mixKey(ee)
            val se = x25519SharedSecret(serverPriv, re)
            k = mixKey(se)
            val hkdfPsk = hkdf(ck, expectedPsk, 3)
            ck = hkdfPsk[0]
            mixHash(hkdfPsk[1])
            k = hkdfPsk[2]
            val ciphertext = msg2Bytes.copyOfRange(32, msg2Bytes.size)
            aesGcmDecrypt(k, 0L, ciphertext, h)
            mixHash(ciphertext)

            val split = hkdf(ck, ByteArray(0), 2)
            val k1 = split[0]
            val k2 = split[1]

            var c1Counter = 0L
            var c2Counter = 0L

            return ServerTransportForTest(
                encryptInitiatorToResponder = { data ->
                    val enc = aesGcmEncrypt(k1, c1Counter, data, ByteArray(0))
                    c1Counter++
                    enc
                },
                decryptResponderToInitiator = { data ->
                    val dec = aesGcmDecrypt(k2, c2Counter, data, ByteArray(0))
                    c2Counter++
                    dec
                }
            )
        }
    }

    class ServerTransportForTest(
        val encryptInitiatorToResponder: (ByteArray) -> ByteArray,
        val decryptResponderToInitiator: (ByteArray) -> ByteArray
    )

    fun createInitiatorForTest(
        serverPriv: ByteArray,
        serverPub: ByteArray,
        clientPub: ByteArray,
        prologue: ByteArray
    ): TestInitiator {
        return TestInitiator(serverPriv, serverPub, clientPub, prologue)
    }
}
