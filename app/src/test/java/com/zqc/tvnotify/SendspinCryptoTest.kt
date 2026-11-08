package com.zqc.tvnotify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SendspinCryptoTest {
    @Test
    fun rfc7748VectorAndSentinelPskConstantsMatchSpec() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = SendspinCrypto.x25519PublicFromPrivate(alicePriv)
        val bobPub = SendspinCrypto.x25519PublicFromPrivate(bobPriv)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hex(alicePub))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hex(bobPub))
        val sharedA = SendspinCrypto.x25519SharedSecret(alicePriv, bobPub)
        val sharedB = SendspinCrypto.x25519SharedSecret(bobPriv, alicePub)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hex(sharedA))
        assertArrayEquals(sharedA, sharedB)
        assertEquals("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3", hex(SendspinCrypto.SENTINEL_PSK))
        assertEquals(SendspinCrypto.SENTINEL_PSK_ID, SendspinCrypto.computePskId(SendspinCrypto.SENTINEL_PSK))
    }

    @Test
    fun rejectsLowOrderAllZeroPublicKey() {
        val priv = ByteArray(32) { (it + 1).toByte() }
        try {
            SendspinCrypto.x25519SharedSecret(priv, ByteArray(32))
            fail("Expected SecurityException on all-zero public key")
        } catch (_: SecurityException) {}
    }

    @Test
    fun noiseKKpsk2HandshakeAndTransportRoundTrip() {
        val clientPriv = ByteArray(32) { (it + 11).toByte() }
        val clientPub = SendspinCrypto.x25519PublicFromPrivate(clientPriv)
        val serverPriv = ByteArray(32) { (it + 41).toByte() }
        val serverPub = SendspinCrypto.x25519PublicFromPrivate(serverPriv)
        val prologue = """{"type":"client/init"}{"type":"server/init"}""".toByteArray(Charsets.UTF_8)

        val initiator = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, prologue)
        val msg1 = initiator.buildMsg1(SendspinCrypto.SENTINEL_PSK_ID, "sn")

        val responder = SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
        val (msg2, clientTransport) = responder.processHandshakeMsg1AndBuildMsg2(msg1, emptyList(), null)
        val serverTransport = initiator.processMsg2(msg2, SendspinCrypto.SENTINEL_PSK)

        val s2cCipher = serverTransport.encryptInitiatorToResponder("hello-tv".toByteArray())
        assertEquals("hello-tv", String(clientTransport.decryptServerToClient(s2cCipher)))

        val c2sCipher = clientTransport.encryptClientToServer("hello-ma".toByteArray())
        assertEquals("hello-ma", String(serverTransport.decryptResponderToInitiator(c2sCipher)))
    }

    @Test
    fun rehandshakeWithPriorHandshakeHashAsPrologue() {
        val clientPriv = ByteArray(32) { (it + 11).toByte() }
        val clientPub = SendspinCrypto.x25519PublicFromPrivate(clientPriv)
        val serverPriv = ByteArray(32) { (it + 41).toByte() }
        val serverPub = SendspinCrypto.x25519PublicFromPrivate(serverPriv)
        val prologue = """{"type":"client/init"}{"type":"server/init"}""".toByteArray(Charsets.UTF_8)
        val longTermPsk = ByteArray(32) { (it + 91).toByte() }

        // 第一场握手（Sentinel）
        val initiator1 = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, prologue)
        val msg1 = initiator1.buildMsg1(SendspinCrypto.SENTINEL_PSK_ID, "sn")
        val responder1 = SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
        val (msg2, _) = responder1.processHandshakeMsg1AndBuildMsg2(msg1, emptyList(), null)
        initiator1.processMsg2(msg2, SendspinCrypto.SENTINEL_PSK)
        val priorHash = initiator1.handshakeDoneHash!!

        // re-handshake：prologue = 第一场握手的 hash h，配对记录的 lt PSK
        val initiator2 = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, priorHash)
        val msg1b = initiator2.buildMsg1(SendspinCrypto.computePskId(longTermPsk), "lt")
        val responder2 = SendspinCrypto.NoiseResponder.forRehandshake(priorHash, clientPriv, clientPub, serverPub)
        val (msg2b, clientTransport2) = responder2.processHandshakeMsg1AndBuildMsg2(
            msg1b,
            listOf(SendspinCrypto.PairingRecord(SendspinCrypto.base64UrlEncode(serverPub), longTermPsk)),
            null
        )
        assertEquals("lt", clientTransport2.matchedCategory)
        val serverTransport2 = initiator2.processMsg2(msg2b, longTermPsk)

        val s2c = serverTransport2.encryptInitiatorToResponder("paired".toByteArray())
        assertEquals("paired", String(clientTransport2.decryptServerToClient(s2c)))
        val c2s = clientTransport2.encryptClientToServer("ack".toByteArray())
        assertEquals("ack", String(serverTransport2.decryptResponderToInitiator(c2s)))
    }

    @Test
    fun fallsBackToSentinelOnUnknownPskAndRejectsMisboundServerId() {
        val clientPriv = ByteArray(32) { (it + 3).toByte() }
        val clientPub = SendspinCrypto.x25519PublicFromPrivate(clientPriv)
        val serverPriv = ByteArray(32) { (it + 53).toByte() }
        val serverPub = SendspinCrypto.x25519PublicFromPrivate(serverPriv)
        val prologue = "prologue".toByteArray()
        val unknownPsk = ByteArray(32) { 0x7A }

        // 1) Unknown lt psk_id -> falls back to Sentinel PSK on initial handshake
        val init1 = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, prologue)
        val msg1Unknown = init1.buildMsg1(SendspinCrypto.computePskId(unknownPsk), "lt")
        val (_, transport1) = SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
            .processHandshakeMsg1AndBuildMsg2(msg1Unknown, emptyList(), null, allowSentinelFallback = true)
        assertEquals("sn", transport1.matchedCategory)

        // 2) Matched lt psk_id bound to a DIFFERENT server_id -> must throw SecurityException
        val init2 = SendspinCrypto.createInitiatorForTest(serverPriv, serverPub, clientPub, prologue)
        val msg1Misbound = init2.buildMsg1(SendspinCrypto.computePskId(unknownPsk), "lt")
        val wrongServerRecord = SendspinCrypto.PairingRecord("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", unknownPsk)
        try {
            SendspinCrypto.NoiseResponder(clientPriv, clientPub, serverPub, prologue)
                .processHandshakeMsg1AndBuildMsg2(msg1Misbound, listOf(wrongServerRecord), null, allowSentinelFallback = true)
            fail("Expected SecurityException on misbound server_id")
        } catch (_: SecurityException) {}
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
