package ro.davidmontei.loom.romm.socket

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Rule
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The device socket against a MockWebServer speaking RomM 5.4.0's side of Engine.IO v4 and Socket.IO v5. */
class DeviceSocketTest {
    @get:Rule val rule = MockWebServerRule()
    private val server get() = rule.server

    /** Under a path prefix, like the API tests, to prove the socket's path is relative too. */
    private val socket by lazy { DeviceSocket(OkHttpClient(), server.url("/romm/"), TOKEN) }

    /**
     * RomM's end: the Engine.IO handshake on open, then [onConnect] when the device asks to join the
     * namespace (accepting it by default). Records every frame the device sends and how it closes.
     */
    private inner class FakeRomm(
        private val pingInterval: Long = 25_000,
        private val pingTimeout: Long = 60_000,
        private val onConnect: (WebSocket) -> Unit = { it.send(CONNECTED) },
    ) : WebSocketListener() {
        val received = LinkedBlockingQueue<String>()
        val closeCodes = LinkedBlockingQueue<Int>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""0{"sid":"Lbo5JLzTotvW3g2LAAAA","upgrades":[],"pingInterval":$pingInterval,"pingTimeout":$pingTimeout,"maxPayload":1000000}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if (text.startsWith("40/devices,")) onConnect(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCodes.add(code)
            webSocket.close(code, null)
        }

        fun next(): String? = received.poll(5, TimeUnit.SECONDS)

        fun upgradeHere() = server.enqueue(MockResponse.Builder().webSocketUpgrade(this).build())
    }

    private fun <T> within(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(10_000, block) }

    @Test fun connectsToTheDevicesNamespaceWithTheTokenInItsConnectPayload() {
        val romm = FakeRomm().apply { upgradeHere() }
        assertThat(within { socket.events().first() }).isEqualTo(DeviceEvent.Connected)
        assertThat(romm.next()).isEqualTo("""40/devices,{"token":"$TOKEN"}""")
    }

    @Test fun asksForEngineIo4OverWebSocketUnderTheBasePath() {
        FakeRomm().upgradeHere()
        within { socket.events().first() }
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/romm/ws/socket.io/")
        assertThat(request.url.queryParameter("EIO")).isEqualTo("4")
        assertThat(request.url.queryParameter("transport")).isEqualTo("websocket")
        assertThat(request.headers["Upgrade"]).isEqualTo("websocket")
        // The token goes only in the connect payload: never in the URL, where proxies log it, nor a header.
        assertThat(request.url.toString()).doesNotContain(TOKEN)
        assertThat(request.headers["Authorization"]).isNull()
    }

    @Test fun answersEveryPingWithAPong() {
        val romm = FakeRomm(onConnect = { it.send(CONNECTED); it.send("2"); it.send("2") }).apply { upgradeHere() }
        within {
            val listening = socket.events().launchIn(CoroutineScope(Dispatchers.IO))
            try {
                assertThat(romm.next()).startsWith("40/devices,")
                assertThat(romm.next()).isEqualTo("3")
                assertThat(romm.next()).isEqualTo("3")
            } finally {
                listening.cancel()
            }
        }
    }

    @Test fun deliversQueuedAndCancelledInstalls() {
        FakeRomm(onConnect = {
            it.send(CONNECTED)
            it.send("""42/devices,["install:queued",{"id":"6f1c2b3a-0000-4000-8000-000000000001","rom_id":1234}]""")
            it.send("""42/devices,["install:updated",{"id":"6f1c2b3a-0000-4000-8000-000000000001","status":"taken"}]""")
            it.send("""42/devices,["install:cancelled",{"id":"6f1c2b3a-0000-4000-8000-000000000001","rom_id":1234}]""")
        }).upgradeHere()
        assertThat(within { socket.events().take(3).toList() }).containsExactly(
            DeviceEvent.Connected,
            DeviceEvent.InstallQueued("6f1c2b3a-0000-4000-8000-000000000001", 1234),
            DeviceEvent.InstallCancelled("6f1c2b3a-0000-4000-8000-000000000001", 1234),
        ).inOrder()
    }

    /** A token that isn't bound to a device, or a deleted device: trying again wouldn't change RomM's answer. */
    @Test fun anUnauthorizedRefusalEndsTheFlow() {
        FakeRomm(onConnect = { it.send("""44/devices,{"message":"unauthorized"}""") }).upgradeHere()
        assertThat(within { socket.events().toList() }).containsExactly(DeviceEvent.Refused(DeviceSocket.UNAUTHORIZED))
    }

    /** A proxy that doesn't pass WebSockets on answers the upgrade itself; Loom says what it answered. */
    @Test fun aProxyThatWontUpgradeSaysWhatItAnswered() {
        server.enqueue(MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build())
        assertThat(within { socket.events().toList() }).containsExactly(DeviceEvent.UpgradeRefused(502))
    }

    /** RomM pings every interval; a connection that has gone quiet past the interval and the timeout is dead. */
    @Test fun aServerThatStopsPingingIsDropped() {
        FakeRomm(pingInterval = 100, pingTimeout = 100).upgradeHere()
        val events = within { socket.events().toList() }
        assertThat(events.first()).isEqualTo(DeviceEvent.Connected)
        assertThat((events.last() as DeviceEvent.Dropped).cause).isInstanceOf(SocketTimeoutException::class.java)
        assertThat(events).hasSize(2)
    }

    /**
     * RomM's connect handler failed (its Redis out for a moment, say): it never answers the join, yet keeps
     * pinging. The connection is dropped, so the next one can be let in, rather than kept open for nothing.
     */
    @Test fun aJoinRommNeverAnswersDropsTheSocket() {
        val pinging = Executors.newSingleThreadScheduledExecutor()
        try {
            FakeRomm(pingInterval = 100, pingTimeout = 100, onConnect = { ws ->
                pinging.scheduleWithFixedDelay({ ws.send("2") }, 0, 50, TimeUnit.MILLISECONDS)
            }).upgradeHere()
            val joining = DeviceSocket(OkHttpClient(), server.url("/romm/"), TOKEN, joinTimeoutMs = 1_000)

            val events = within { joining.events().toList() }

            assertThat((events.single() as DeviceEvent.Dropped).cause).isInstanceOf(SocketTimeoutException::class.java)
        } finally {
            pinging.shutdownNow()
        }
    }

    /** RomM leaves the namespace when the token is rotated or the device deleted; the next connection finds out which. */
    @Test fun theServerLeavingTheNamespaceDropsTheSocket() {
        FakeRomm(onConnect = { it.send(CONNECTED); it.send("41/devices") }).upgradeHere()
        assertThat(within { socket.events().toList() }).containsExactly(DeviceEvent.Connected, DeviceEvent.Dropped(null)).inOrder()
        FakeRomm(onConnect = { it.send(CONNECTED); it.send("1") }).upgradeHere()
        assertThat(within { socket.events().toList() }).containsExactly(DeviceEvent.Connected, DeviceEvent.Dropped(null)).inOrder()
    }

    @Test fun stoppingSaysGoodbyeAndClosesNormally() {
        val romm = FakeRomm().apply { upgradeHere() }
        val seen = CopyOnWriteArrayList<DeviceEvent>()
        within { socket.events().onEach { seen.add(it) }.first() }
        assertThat(romm.next()).startsWith("40/devices,")
        assertThat(romm.next()).isEqualTo("41/devices,")
        assertThat(romm.closeCodes.poll(5, TimeUnit.SECONDS)).isEqualTo(1000)
        assertThat(seen).containsExactly(DeviceEvent.Connected)
    }

    private companion object {
        const val TOKEN = "rmm_0123456789abcdef"
        const val CONNECTED = """40/devices,{"sid":"vYbRyQ0tYyVwOE1oAAAB"}"""
    }
}
