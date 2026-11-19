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
            """{"player_style":"classic"}""",
            """{"player_style":1}""",
            """{"wled_sync_enabled":"yes"}""",
            """{"wled_sync_port":0}""",
            """{"wled_sync_port":65536}""",
            """{"wled_sync_port":11988.5}""",
            """{"wled_sync_port":"11988"}""",
            """{"wled_color_sync_enabled":1}""",
            """{"wled_color_host":"ftp://192.168.101.206"}""",
            """{"wled_color_host":""}""",
            """{"wled_effect":"nonexistent_fx"}""",
            """{"wled_effect":"geq"}""",
            """{"wled_effect":"midnoise"}""",
            """{"wled_effect":"waterfall"}""",
            """{"wled_effect":"solid"}""",
            """{"wled_effect":128}""",
        )) {
            assertThrows(invalid, IllegalArgumentException::class.java) {
                parseSendspinConfig(JSONObject(invalid))
            }
        }
    }

    @Test
    fun `wled and player style fields parse and normalize cleanly`() {
        val config = parseSendspinConfig(
            JSONObject(
                """
                {
                  "player_style": "cover_blur",
                  "wled_sync_enabled": true,
                  "wled_sync_port": 11988,
                  "wled_color_sync_enabled": true,
                  "wled_color_host": "http://192.168.101.206/",
                  "wled_effect": "gravcentric"
                }
                """.trimIndent()
            )
        )
        assertEquals(Prefs.SendspinPlayerStyle.COVER_BLUR, config.playerStyle)
        assertEquals(true, config.wledSyncEnabled)
        assertEquals(11988, config.wledSyncPort)
        assertEquals(true, config.wledColorSyncEnabled)
        assertEquals("192.168.101.206", config.wledColorHost)
        assertEquals(WledEffectPreset.GRAVCENTRIC, config.wledEffect)
    }
}
