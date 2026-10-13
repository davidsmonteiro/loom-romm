package ro.davidmontei.loom.romm.pairing

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommFixtures
import ro.davidmontei.loom.romm.RommServiceFactory
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse

class PairingTest {
    @get:Rule val rule = MockWebServerRule()
    private val server get() = rule.server
    private val connector = RommConnector(OkHttpClient())
    private val device = DeviceIdentity("loom-6f1c", "AYN Thor (Loom)", "0.1.0")

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun pending() = json("""{"detail":"authorization_pending"}""", 400)
    private val me = """{"id":1,"username":"david","email":null,"enabled":true,"role":"admin","oauth_scopes":["roms.read"],
        "avatar_path":"","last_login":null,"last_active":null,"created_at":"2026-01-01T00:00:00+00:00","updated_at":"2026-01-01T00:00:00+00:00"}"""
    private val session = DeviceAuthInitResponse("dev-code", "ABCD2345", "/pair/device",
        "/pair/device?user_code=ABCD2345", expiresIn = 600, interval = 5)

    @Test fun probeReturnsVersionForRomm() = runTest {
        server.enqueue(json(RommFixtures.HEARTBEAT_5_3_1))
        val probe = connector.probe(server.url("/").toString())
        assertThat(probe.version).isEqualTo("5.3.1")
        assertThat(probe.baseUrl).isEqualTo(server.url("/"))
    }

    @Test fun probeRejectsRommOlderThanFive() {
        server.enqueue(json("""{"SYSTEM":{"VERSION":"4.9.0","SHOW_SETUP_WIZARD":false}}"""))
        val e = assertThrows(UnsupportedServerException::class.java) {
            runBlocking { connector.probe(server.url("/").toString()) }
        }
        assertThat(e.version).isEqualTo("4.9.0")
    }

    @Test fun probeRejectsSomethingThatIsNotRomm() {
        server.enqueue(MockResponse.Builder().code(200).body("<html>router login</html>").build())
        assertThrows(RommException.Protocol::class.java) {
            runBlocking { connector.probe(server.url("/").toString()) }
        }
    }

    /** What the Thor did: a DNS lookup that never returns. OkHttp's own timeouts can't stop it. */
    @Test fun probeGivesUpOnAHungDnsLookup() {
        val stuck = okhttp3.Dns { host ->
            if (host == "stuck.example.org") { Thread.sleep(60_000); emptyList() } else okhttp3.Dns.SYSTEM.lookup(host)
        }
        val connector = RommConnector(OkHttpClient.Builder().dns(stuck).build())
        val started = System.nanoTime()
        assertThrows(RommException.Unreachable::class.java) { runBlocking { connector.probe("https://stuck.example.org") } }
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(12_000)
    }

    @Test fun deviceAuthHappyPath() = runTest {
        server.enqueue(json("""{"device_code":"dev-code","user_code":"ABCD2345","verification_path":"/pair/device",
            "verification_path_complete":"/pair/device?user_code=ABCD2345","expires_in":600,"interval":5}""", 201))
        val started = connector.startDeviceAuth(server.url("/"), device)
        assertThat(started.displayCode).isEqualTo("ABCD-2345")
        assertThat(started.verificationUrlComplete.toString()).isEqualTo(server.url("/pair/device?user_code=ABCD2345").toString())

        val init = server.takeRequest()
        val body = Json.parseToJsonElement(init.body!!.utf8()).jsonObject
        assertThat(body["client"]!!.jsonPrimitive.content).isEqualTo("loom")
        assertThat(body["client_device_identifier"]!!.jsonPrimitive.content).isEqualTo("loom-6f1c")
        assertThat(body["requested_scopes"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactlyElementsIn(LOOM_SCOPES)

        server.enqueue(pending())
        server.enqueue(json("""{"access_token":"rmm_abc","device_id":"d-1","scopes":["roms.read"],"expires_at":null}"""))
        server.enqueue(json(me))
        val poller = DeviceAuthPoller(RommServiceFactory.create(server.url("/"), OkHttpClient()), clockMs = { testScheduler.currentTime })
        val result = connector.awaitDeviceAuth(started, poller) as DeviceAuthResult.Paired
        assertThat(result.credentials.token).isEqualTo("rmm_abc")
        assertThat(result.credentials.deviceId).isEqualTo("d-1")
        assertThat(result.credentials.username).isEqualTo("david")

        server.takeRequest() // pending poll
        val tokenPoll = server.takeRequest()
        assertThat(tokenPoll.url.encodedPath).isEqualTo("/api/auth/device/token")
        assertThat(tokenPoll.body!!.utf8()).contains("dev-code")
        val meRequest = server.takeRequest()
        assertThat(meRequest.headers["Authorization"]).isEqualTo("Bearer rmm_abc")
    }

    @Test fun pollerHonoursIntervalAndSlowDown() = runTest {
        server.enqueue(pending())
        server.enqueue(json("""{"detail":"slow_down"}""", 400))
        server.enqueue(json("""{"access_token":"rmm_x","device_id":"d","scopes":[],"expires_at":null}"""))
        val start = testScheduler.currentTime
        val poller = DeviceAuthPoller(RommServiceFactory.create(server.url("/"), OkHttpClient()), clockMs = { testScheduler.currentTime })
        val outcome = poller.await(session)
        assertThat(outcome).isInstanceOf(DeviceAuthOutcome.Approved::class.java)
        // 5 s, 5 s, then 10 s after slow_down.
        assertThat(testScheduler.currentTime - start).isEqualTo(20_000)
    }

    @Test fun pollerReportsDenial() = runTest {
        server.enqueue(json("""{"detail":"access_denied"}""", 400))
        val poller = DeviceAuthPoller(RommServiceFactory.create(server.url("/"), OkHttpClient()), clockMs = { testScheduler.currentTime })
        assertThat(poller.await(session)).isEqualTo(DeviceAuthOutcome.Denied)
    }

    @Test fun pollerStopsAtDeadline() = runTest {
        repeat(3) { server.enqueue(pending()) }
        val shortSession = session.copy(expiresIn = 12)
        val poller = DeviceAuthPoller(RommServiceFactory.create(server.url("/"), OkHttpClient()), clockMs = { testScheduler.currentTime })
        assertThat(poller.await(shortSession)).isEqualTo(DeviceAuthOutcome.Expired)
        assertThat(server.requestCount).isEqualTo(2) // polls at 5 s and 10 s; 15 s is past the deadline
    }

    @Test fun pollerRidesOutTransientServerErrors() = runTest {
        server.enqueue(json("""{"detail":"Bad gateway"}""", 502))
        server.enqueue(json("""{"access_token":"rmm_y","device_id":"d","scopes":[],"expires_at":null}"""))
        val poller = DeviceAuthPoller(RommServiceFactory.create(server.url("/"), OkHttpClient()), clockMs = { testScheduler.currentTime })
        assertThat((poller.await(session) as DeviceAuthOutcome.Approved).token).isEqualTo("rmm_y")
    }

    @Test fun pairCodeIsNormalisedThenValidated() = runTest {
        server.enqueue(json("""{"id":3,"name":"Thor","scopes":["roms.read"],"expires_at":null,"last_used_at":null,
            "created_at":"2026-10-13T00:00:00+00:00","user_id":1,"device_id":null,"raw_token":"rmm_paired"}"""))
        server.enqueue(json(me))
        val creds = connector.exchangePairCode(server.url("/"), " abcd-2345 ")
        assertThat(creds.token).isEqualTo("rmm_paired")
        assertThat(server.takeRequest().body!!.utf8()).isEqualTo("""{"code":"ABCD2345"}""")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer rmm_paired")
    }

    @Test fun badPairCodeIsRejectedLocally() {
        assertThat(RommConnector.normalizePairCode("ABC")).isNull()
        assertThat(RommConnector.normalizePairCode("abcd 2345")).isEqualTo("ABCD2345")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { connector.exchangePairCode(server.url("/"), "nope") }
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun expiredPairCodeSurfacesServerMessage() {
        server.enqueue(json("""{"detail":"Invalid or expired pairing code"}""", 404))
        val e = assertThrows(RommException.NotFound::class.java) {
            runBlocking { connector.exchangePairCode(server.url("/"), "ABCD2345") }
        }
        assertThat(e.message).isEqualTo("Invalid or expired pairing code")
    }

    @Test fun pastedTokenIsCheckedAndBearerPrefixStripped() = runTest {
        server.enqueue(json(me))
        val creds = connector.usePastedToken(server.url("/"), "Bearer rmm_pasted\n")
        assertThat(creds.token).isEqualTo("rmm_pasted")
        assertThat(server.takeRequest().headers["Authorization"]).isEqualTo("Bearer rmm_pasted")
    }

    @Test fun rejectedPastedTokenIsUnauthorized() {
        server.enqueue(json("""{"detail":"Could not validate credentials"}""", 401))
        assertThrows(RommException.Unauthorized::class.java) {
            runBlocking { connector.usePastedToken(server.url("/"), "rmm_wrong") }
        }
    }
}
