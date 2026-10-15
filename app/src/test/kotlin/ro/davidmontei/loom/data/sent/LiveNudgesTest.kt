package ro.davidmontei.loom.data.sent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test
import ro.davidmontei.loom.di.NetworkModule
import ro.davidmontei.loom.net.AuthSource
import ro.davidmontei.loom.net.ReachabilityInterceptor
import ro.davidmontei.loom.net.ServerReachability
import ro.davidmontei.loom.romm.socket.DeviceSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * RomM's device socket while Loom is in front, against a MockWebServer speaking RomM 5.4.0's side of
 * it, through the client Loom gives the socket.
 */
class LiveNudgesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = NetworkModule.socketClient(NetworkModule.bareClient())
    private val romm = FakeRomm()
    private val heard = Heard()
    private val target = MutableStateFlow<LiveNudges.Target?>(null)
    private val live = LiveNudges({ base, token -> DeviceSocket(client, base, token).events() }, pollEveryMs = 200, waitBeforeReconnecting = { delay(20) })

    @After fun tearDown() {
        scope.cancel()
        romm.server.close()
    }

    private fun running() = scope.launch { live.run(target, heard) }

    private fun at(server: MockWebServer, token: String = TOKEN) = LiveNudges.Target(server.url("/"), token)

    private suspend fun until(what: () -> Boolean) = withTimeout(5_000) { while (!what()) delay(10) }

    @Test fun inFrontASocketOpensAndClaimsOnceConnected() = runBlocking<Unit> {
        target.value = at(romm.server)
        running()
        delay(300)
        assertThat(romm.server.requestCount).isEqualTo(0) // in the background, no socket

        live.inFront(true)

        assertThat(romm.next()).isEqualTo("""40/devices,{"token":"$TOKEN"}""")
        until { heard.nudges.get() == 1 }
    }

    /** RomM's owner sent a game, or cancelled one: Loom hears at once. */
    @Test fun aNudgeClaimsAtOnce() = runBlocking<Unit> {
        target.value = at(romm.server)
        running()
        live.inFront(true)
        until { heard.nudges.get() == 1 }

        romm.say("""42/devices,["install:queued",{"id":"6f1c2b3a-0000-4000-8000-000000000001","rom_id":1234}]""")
        romm.say("""42/devices,["install:cancelled",{"id":"6f1c2b3a-0000-4000-8000-000000000002","rom_id":99}]""")

        until { heard.nudges.get() == 2 && heard.cancelled.isNotEmpty() }
        assertThat(heard.cancelled).containsExactly("6f1c2b3a-0000-4000-8000-000000000002")
    }

    /** Nothing keeps a socket open in the background: Loom says goodbye and closes it, and the periodic claim takes over. */
    @Test fun inTheBackgroundTheSocketCloses() = runBlocking<Unit> {
        target.value = at(romm.server)
        running()
        live.inFront(true)
        until { heard.nudges.get() == 1 }
        assertThat(romm.next()).startsWith("40/devices,")

        live.inFront(false)

        assertThat(romm.next()).isEqualTo("41/devices,")
        assertThat(romm.closeCodes.poll(5, TimeUnit.SECONDS)).isEqualTo(1000)
        delay(500)
        assertThat(romm.server.requestCount).isEqualTo(1) // not opened again
        assertThat(heard.nudges.get()).isEqualTo(1) // nor claimed by the minute
    }

    /** RomM refusing the token is passed on, once, and the socket isn't tried again with it: the answer wouldn't change. */
    @Test fun anUnauthorizedRefusalIsPassedOnAndTheSocketStopsTrying() = runBlocking<Unit> {
        romm.onConnect = { it.send("""44/devices,{"message":"unauthorized"}""") }
        target.value = at(romm.server)
        running()
        live.inFront(true)

        until { heard.refused.isNotEmpty() }
        delay(300)
        assertThat(heard.refused).containsExactly(DeviceSocket.UNAUTHORIZED to TOKEN)
        assertThat(romm.server.requestCount).isEqualTo(1)
    }

    /**
     * A front that doesn't pass WebSockets answers the upgrade itself. After three such answers in a row the
     * socket is left alone until Loom next comes to the front, and Loom claims by the minute instead.
     */
    @Test fun aProxyThatWontUpgradeFallsBackToPollingQuietly() = runBlocking<Unit> {
        romm.refuseUpgrades = true
        target.value = at(romm.server)
        running()
        live.inFront(true)

        until { heard.nudges.get() >= 3 } // a claim every 200 ms here, every minute in Loom
        assertThat(romm.server.requestCount).isEqualTo(LiveNudges.GIVE_UP_AFTER)

        live.inFront(false)
        delay(100)
        romm.refuseUpgrades = false
        live.inFront(true) // the next time in front, the socket is tried again
        assertThat(romm.next()).startsWith("40/devices,")
    }

    /** A proxy's 502 to the upgrade isn't RomM out of reach: the library mustn't say "Offline" for it. */
    @Test fun aRefusedUpgradeDoesntMakeTheLibrarySayOffline() = runBlocking<Unit> {
        romm.refuseUpgrades = true
        val reachability = ServerReachability()
        // As Loom's API client reports on RomM: a heartbeat answered.
        val api = NetworkModule.bareClient().newBuilder().addInterceptor(ReachabilityInterceptor(reachability, RommAt(romm.server.url("/")))).build()
        api.newCall(Request.Builder().url(romm.server.url("/api/heartbeat")).build()).execute().close()
        assertThat(reachability.current.value).isEqualTo(ServerReachability.State.REACHABLE)
        target.value = at(romm.server)
        running()
        live.inFront(true)

        until { romm.server.requestCount == 1 + LiveNudges.GIVE_UP_AFTER }

        assertThat(reachability.current.value).isEqualTo(ServerReachability.State.REACHABLE)
        assertThat(client.interceptors.none { it is ReachabilityInterceptor }).isTrue()
        assertThat(romm.upgrades.map { it.headers["Authorization"] }).containsExactly(null, null, null)
    }

    /** The server's address changed in Settings (or Loom paired again): the socket moves with it. */
    @Test fun aNewAddressMovesTheSocket() = runBlocking<Unit> {
        val elsewhere = FakeRomm()
        try {
            target.value = at(romm.server)
            running()
            live.inFront(true)
            assertThat(romm.next()).startsWith("40/devices,")

            target.value = at(elsewhere.server, "rmm_new")

            assertThat(romm.next()).isEqualTo("41/devices,")
            assertThat(elsewhere.next()).isEqualTo("""40/devices,{"token":"rmm_new"}""")
            until { heard.nudges.get() == 2 }
        } finally {
            elsewhere.server.close()
        }
    }

    /** No games can come (switched off, say): the socket closes. */
    @Test fun noTargetNoSocket() = runBlocking<Unit> {
        target.value = at(romm.server)
        running()
        live.inFront(true)
        assertThat(romm.next()).startsWith("40/devices,")

        target.value = null

        assertThat(romm.next()).isEqualTo("41/devices,")
    }

    /** What the socket told Loom. */
    private class Heard : LiveNudges.Listener {
        val nudges = AtomicInteger()
        val cancelled = CopyOnWriteArrayList<String>()
        val refused = CopyOnWriteArrayList<Pair<String, String>>()

        override fun nudge() {
            nudges.incrementAndGet()
        }

        override suspend fun cancelled(requestId: String) {
            cancelled += requestId
        }

        override suspend fun refused(message: String, token: String) {
            refused += message to token
        }
    }

    /** The token's server, for the API client's interceptors. */
    private class RommAt(private val url: HttpUrl) : AuthSource {
        override fun serverUrl() = url
        override fun token() = TOKEN
    }

    /**
     * RomM's end of the socket at `/ws/socket.io/`: the Engine.IO handshake on open, then [onConnect] when
     * the device asks to join the namespace (accepting it by default). With [refuseUpgrades], a proxy in
     * front answers the upgrade with a 502 instead. Anything else is a heartbeat.
     */
    private class FakeRomm : WebSocketListener() {
        val server = MockWebServer()
        val received = LinkedBlockingQueue<String>()
        val closeCodes = LinkedBlockingQueue<Int>()
        val upgrades = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var refuseUpgrades = false
        @Volatile var onConnect: (WebSocket) -> Unit = { it.send(CONNECTED) }
        @Volatile private var socket: WebSocket? = null

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.url.encodedPath != "/ws/socket.io/") return MockResponse.Builder().code(200).body("{}").build()
                    upgrades += request
                    if (refuseUpgrades) return MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build()
                    return MockResponse.Builder().webSocketUpgrade(this@FakeRomm).build()
                }
            }
            server.start()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            webSocket.send("""0{"sid":"Lbo5JLzTotvW3g2LAAAA","upgrades":[],"pingInterval":25000,"pingTimeout":60000,"maxPayload":1000000}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if (text.startsWith("40/devices,")) onConnect(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCodes.add(code)
            webSocket.close(code, null)
        }

        fun say(frame: String) = socket!!.send(frame)

        fun next(): String? = received.poll(5, TimeUnit.SECONDS)
    }

    private companion object {
        const val TOKEN = "rmm_0123456789abcdef"
        const val CONNECTED = """40/devices,{"sid":"vYbRyQ0tYyVwOE1oAAAB"}"""
    }
}
