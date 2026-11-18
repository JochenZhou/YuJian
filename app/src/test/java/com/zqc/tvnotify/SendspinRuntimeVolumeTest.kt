package com.zqc.tvnotify

import android.os.Looper
import com.sendspin.protocol.JsonOptional
import com.sendspin.protocol.MessageParser
import com.sendspin.protocol.SendSpinClient
import com.sendspin.protocol.SendSpinWebSocket
import com.sendspin.protocol.ServerHello
import com.sendspin.protocol.ServerState
import com.sendspin.protocol.TrackMetadataMsg
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class SendspinRuntimeVolumeTest {

    private class CapturingSocket : SendSpinWebSocket {
        val sent = CopyOnWriteArrayList<String>()
        override fun send(text: String): Boolean {
            sent += text
            return true
        }
        override fun close(code: Int, reason: String?) {}
    }

    @After
    fun tearDown() {
        SendspinRuntime.shutdown()
    }

    @Test
    fun `volume and mute commands control MA controller and sync MA updates back to status`() {
        val runtime = SendspinRuntime.init(RuntimeEnvironment.getApplication())
        val runtimeClass = SendspinRuntime::class.java
        val createClient = runtimeClass.getDeclaredMethod("createClient").apply { isAccessible = true }
        val instance = createClient.invoke(runtime) as SendSpinClient
        val socket = CapturingSocket()

        val acceptIncoming = SendSpinClient::class.java.declaredMethods
            .first { it.name == "acceptIncomingConnection" || it.name.startsWith("acceptIncomingConnection\$") }
            .apply { isAccessible = true }
        val handleText = SendSpinClient::class.java.declaredMethods
            .first { it.name == "handleTextMessage" || it.name.startsWith("handleTextMessage\$") }
            .apply { isAccessible = true }

        val hello = ServerHello(
            serverId = "srv-1",
            name = "Music Assistant",
            version = 1,
            activeRoles = listOf("player@v1", "controller@v1", "metadata@v1")
        )
        acceptIncoming.invoke(instance, socket, hello)

        runtimeClass.getDeclaredField("progressSession").apply { isAccessible = true }.set(runtime, hello)
        runtimeClass.getDeclaredField("progressSessionActive").apply { isAccessible = true }.set(runtime, true)
        runtimeClass.getDeclaredField("watchedClient").apply { isAccessible = true }.set(runtime, instance)

        val updateFromServerState = runtimeClass.getDeclaredMethod(
            "updateFromServerState",
            SendSpinClient::class.java,
            ServerState::class.java
        ).apply { isAccessible = true }
        val parser = SendSpinClient::class.java.getDeclaredField("parser").apply { isAccessible = true }.get(instance) as MessageParser

        val initialState = parser.parseText(
            """{"type":"server/state","payload":{"controller":{"supported_commands":["volume","mute","play","pause"],"volume":72,"muted":false},"metadata":{"title":"晴天","artist":"周杰伦"}}}"""
        ) as ServerState
        updateFromServerState.invoke(runtime, instance, initialState)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(72, runtime.status().volume)
        assertFalse(runtime.status().muted)
        assertTrue("volume" in runtime.status().controllerCommands)

        // TV sends MA controller commands over Sendspin client/command
        assertTrue(runtime.command("volume", volume = 38))
        assertEquals(38, runtime.status().volume)
        val lastCmd = JSONObject(socket.sent.last { JSONObject(it).getString("type") == "client/command" })
        val ctrlCmd = lastCmd.getJSONObject("payload").getJSONObject("controller")
        assertEquals("volume", ctrlCmd.getString("command"))
        assertEquals(38, ctrlCmd.getInt("volume"))

        assertTrue(runtime.command("mute", muted = true))
        assertTrue(runtime.status().muted)
        val muteCmd = JSONObject(socket.sent.last { JSONObject(it).getString("type") == "client/command" })
        val ctrlMute = muteCmd.getJSONObject("payload").getJSONObject("controller")
        assertEquals("mute", ctrlMute.getString("command"))
        assertTrue(ctrlMute.getBoolean("mute"))

        // Metadata-only update must preserve optimistic volume/muted without flickering back
        val metadataOnly = ServerState(
            metadata = JsonOptional.Present(TrackMetadataMsg(title = JsonOptional.Present("七里香"))),
            controller = JsonOptional.Absent
        )
        updateFromServerState.invoke(runtime, instance, metadataOnly)
        assertEquals(38, runtime.status().volume)
        assertTrue(runtime.status().muted)

        // MA pushes updated controller volume/mute -> TV UI status tracks MA cleanly
        val maPush = parser.parseText(
            """{"type":"server/state","payload":{"controller":{"supported_commands":["volume","mute"],"volume":55,"muted":false}}}"""
        ) as ServerState
        updateFromServerState.invoke(runtime, instance, maPush)
        assertEquals(55, runtime.status().volume)
        assertFalse(runtime.status().muted)

        // Incoming player server/command also updates local audio output state
        handleText.invoke(instance, """{"type":"server/command","payload":{"player":{"command":"volume","volume":55}}}""")
        assertEquals(55, instance.playerVolumeFlow.value)
    }
}
