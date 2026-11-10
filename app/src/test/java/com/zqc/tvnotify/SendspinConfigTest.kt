package com.zqc.tvnotify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SendspinConfigTest {
    @Test
    fun `mode can change without replacing saved delay`() {
        val automatic = parseSendspinConfig(JSONObject("""{"sync_mode":"auto"}"""))
        assertEquals(true, automatic.automaticSync)
        assertNull(automatic.outputDelayMs)

        val manual = parseSendspinConfig(JSONObject("""{"sync_mode":"manual"}"""))
        assertEquals(false, manual.automaticSync)
        assertNull(manual.outputDelayMs)
    }

    @Test
    fun `explicit mode and manual compensation are retained separately`() {
        val config = parseSendspinConfig(JSONObject("""{"sync_mode":"auto","output_delay_ms":850}"""))
        assertEquals(true, config.automaticSync)
        assertEquals(850, config.outputDelayMs)
    }

    @Test
    fun `delay only and both range limits remain supported`() {
        for (delay in listOf(0, 5000)) {
            val config = parseSendspinConfig(JSONObject().put("output_delay_ms", delay))
            assertNull(config.automaticSync)
            assertEquals(delay, config.outputDelayMs)
        }
    }

    @Test
    fun `invalid modes and noninteger or out of range delays are rejected`() {
        for (mode in listOf("", "AUTO", "automatic", "manual ", 1, true, JSONObject.NULL)) {
            assertThrows("Mode $mode", IllegalArgumentException::class.java) {
                parseSendspinConfig(JSONObject().put("sync_mode", mode))
            }
        }
        for (delay in listOf(-1, 5001, 0.5, 4999.9, "0", "", true, JSONObject.NULL, Long.MAX_VALUE)) {
            assertThrows("Delay $delay", IllegalArgumentException::class.java) {
                parseSendspinConfig(JSONObject().put("output_delay_ms", delay))
            }
        }
    }

    @Test
    fun `all typed config fields validate before mutation`() {
        for (invalid in listOf(
            """{"enabled":"true"}""",
            """{"auto_popup":1}""",
            """{"server_url":null}""",
            """{"client_name":42}""",
            """{"enabled":true,"sync_mode":"invalid"}""",
        )) {
            assertThrows(invalid, IllegalArgumentException::class.java) {
                parseSendspinConfig(JSONObject(invalid))
            }
        }
    }
}
