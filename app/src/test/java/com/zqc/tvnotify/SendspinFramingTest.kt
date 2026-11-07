package com.zqc.tvnotify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SendspinFramingTest {
    @Test
    fun reassemblesExtendedMaskedContinuationFramesAndRejectsOrigin() {
        // 1) Server upgrade MUST reject requests containing an Origin header
        val reqWithOrigin = (
            "GET /sendspin HTTP/1.1\r\n" +
            "Host: 192.168.1.50:8928\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Origin: http://evil.example\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)
        val respOut1 = ByteArrayOutputStream()
        assertFalse(SendspinWsFrameCodec.performServerUpgrade(ByteArrayInputStream(reqWithOrigin), respOut1))
        assertTrue(respOut1.toString("UTF-8").contains("403"))

        // 2) Valid server upgrade without Origin succeeds with RFC 6455 accept hash
        val validReq = (
            "GET /sendspin HTTP/1.1\r\n" +
            "Host: 192.168.1.50:8928\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)
        val respOut2 = ByteArrayOutputStream()
        assertTrue(SendspinWsFrameCodec.performServerUpgrade(ByteArrayInputStream(validReq), respOut2))
        assertTrue(respOut2.toString("UTF-8").contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))

        // 3) Masked >125-byte (len=126, 8192 bytes) split across two continuation frames
        val original = ByteArray(8192) { (it * 31 and 0xFF).toByte() }
        val wire = ByteArrayOutputStream()
        SendspinWsFrameCodec.writeRawFrameForTest(wire, fin = false, opcode = 0x2, payload = original.copyOfRange(0, 5000), masked = true)
        SendspinWsFrameCodec.writeRawFrameForTest(wire, fin = true, opcode = 0x0, payload = original.copyOfRange(5000, 8192), masked = true)

        val decoded = SendspinWsFrameCodec.readMessage(ByteArrayInputStream(wire.toByteArray())) {}
        assertNotNull(decoded)
        assertEquals(0x2, decoded!!.opcode)
        assertArrayEquals(original, decoded.payload)
    }

    @Test
    fun reassemblesSendspinFragmentsAndArtworkAnnouncePartCancel() {
        val frag = SendspinWsFrameCodec.FragmentReassembler()
        assertNull(frag.pushFragment(byteArrayOf(1, 0x00, 0x00) + "{\"type\":".toByteArray(Charsets.UTF_8)))
        val done = frag.pushFragment(byteArrayOf(1, 0x01, 0x00) + "\"ok\"}".toByteArray(Charsets.UTF_8))
        assertNotNull(done)
        assertEquals(0, done!!.first)
        assertEquals("{\"type\":\"ok\"}", String(done.second, Charsets.UTF_8))

        val art = SendspinWsFrameCodec.ArtworkAssembler()
        val imgBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        // Announce: [8][0x02][timestamp=1000us (8B BE)][total_size=6 (4B BE)]
        val announce = byteArrayOf(8, 2, 0, 0, 0, 0, 0, 0, 0x03, 0xE8.toByte(), 0, 0, 0, 6)
        assertTrue(art.onBinaryArtwork(announce) is SendspinWsFrameCodec.ArtworkEvent.InFlight)
        assertTrue(art.onBinaryArtwork(byteArrayOf(8, 0) + imgBytes.copyOfRange(0, 3)) is SendspinWsFrameCodec.ArtworkEvent.InFlight)
        val completed = art.onBinaryArtwork(byteArrayOf(8, 0) + imgBytes.copyOfRange(3, 6))
        assertTrue(completed is SendspinWsFrameCodec.ArtworkEvent.Completed)
        assertArrayEquals(imgBytes, (completed as SendspinWsFrameCodec.ArtworkEvent.Completed).imageBytes)
    }
}
