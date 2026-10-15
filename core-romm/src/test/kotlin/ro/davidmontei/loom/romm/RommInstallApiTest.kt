package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import ro.davidmontei.loom.romm.RommFixtures.device
import ro.davidmontei.loom.romm.RommFixtures.installRequest
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.dto.DeviceUpdateRequest
import ro.davidmontei.loom.romm.dto.INSTALL_DONE
import ro.davidmontei.loom.romm.dto.INSTALL_FAILED
import ro.davidmontei.loom.romm.dto.InstallReportRequest
import ro.davidmontei.loom.romm.dto.MAX_INSTALL_REASON
import ro.davidmontei.loom.romm.dto.REMOTE_INSTALL
import ro.davidmontei.loom.romm.dto.installReason

/** The send-to-device calls against a MockWebServer shaped like RomM 5.4.0. */
class RommInstallApiTest {
    @get:Rule val rule = MockWebServerRule()
    private val server get() = rule.server

    /** Under a path prefix, like RommApiTest, to prove the new paths are relative too. */
    private val api: RommApi by lazy { RommServiceFactory.create(server.url("/romm/"), OkHttpClient()) }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Test fun declaringRemoteInstallSendsOnlyTheCapability() = runTest {
        val declared = JsonObject(device("dev-thor", "AYN Thor (Loom)") + ("capabilities" to buildJsonObject { put(REMOTE_INSTALL, true) }))
        server.enqueue(json(declared.toString()))

        val device = rommCall { api.updateDevice("dev-thor", DeviceUpdateRequest(mapOf(REMOTE_INSTALL to true))) }

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.url.encodedPath).isEqualTo("/romm/api/devices/dev-thor")
        // RomM replaces the whole dict and would take a name or sync setting sent alongside it, so nothing else goes.
        assertThat(request.body!!.utf8()).isEqualTo("""{"capabilities":{"remote_install":true}}""")
        assertThat(device.id).isEqualTo("dev-thor")
        assertThat(device.capabilities).containsExactly(REMOTE_INSTALL, true)
    }

    @Test fun aClaimPostsNothingAndReadsTheTakenRequests() = runTest {
        server.enqueue(json(JsonArray(listOf(
            installRequest("6f1c2b3a-0000-4000-8000-000000000001", 1234),
            installRequest("6f1c2b3a-0000-4000-8000-000000000002", 1235),
        )).toString()))

        val taken = rommCall { api.claimInstalls("dev-thor") }

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/romm/api/devices/dev-thor/installs/claim")
        assertThat(request.body?.size ?: 0).isEqualTo(0)
        // Oldest first, as RomM sends them; the file ids RomM picked are left unread.
        assertThat(taken.map { it.romId }).containsExactly(1234, 1235).inOrder()
        assertThat(taken[0].id).isEqualTo("6f1c2b3a-0000-4000-8000-000000000001")
        assertThat(taken[0].status).isEqualTo("taken")
    }

    @Test fun aReportSendsItsStatusAndLeavesOutANullReason() = runTest {
        val id = "6f1c2b3a-0000-4000-8000-000000000001"
        server.enqueue(json(installRequest(id, 1234, status = INSTALL_DONE).toString()))
        server.enqueue(json(installRequest(id, 1235, status = INSTALL_FAILED, reason = "The SD card isn't there").toString()))

        val done = rommCall { api.reportInstall("dev-thor", id, InstallReportRequest(INSTALL_DONE)) }
        rommCall { api.reportInstall("dev-thor", id, InstallReportRequest(INSTALL_FAILED, "The SD card isn't there")) }

        val first = server.takeRequest()
        assertThat(first.method).isEqualTo("PUT")
        assertThat(first.url.encodedPath).isEqualTo("/romm/api/devices/dev-thor/installs/$id")
        assertThat(first.body!!.utf8()).isEqualTo("""{"status":"done"}""")
        assertThat(done.status).isEqualTo(INSTALL_DONE)
        val second = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertThat(second.keys).containsExactly("status", "reason")
    }

    /** A request that ended, was cancelled or expired is gone: the report has nothing left to do. */
    @Test fun aReportOnARequestThatsGoneIsNotFound() = runTest {
        server.enqueue(json("""{"detail":"Install request not found"}""", code = 404))
        val e = runCatching { rommCall { api.reportInstall("dev-thor", "gone", InstallReportRequest(INSTALL_DONE)) } }.exceptionOrNull()
        assertThat(e).isInstanceOf(RommException.NotFound::class.java)
    }

    /** A token from a pair code or pasted in isn't bound to the device, and only the device itself may claim. */
    @Test fun aClaimByATokenWithoutADeviceIsForbidden() = runTest {
        server.enqueue(json("""{"detail":"Only the device itself can do this"}""", code = 403))
        val e = runCatching { rommCall { api.claimInstalls("dev-thor") } }.exceptionOrNull()
        assertThat(e).isInstanceOf(RommException.Forbidden::class.java)
        assertThat(e!!.message).isEqualTo("Only the device itself can do this")
    }

    @Test fun the540HeartbeatSaysInstallsAreOnAnd531SaysNothing() = runTest {
        server.enqueue(json(RommFixtures.HEARTBEAT_5_4_0))
        server.enqueue(json(RommFixtures.HEARTBEAT_5_3_1))
        assertThat(rommCall { api.heartbeat() }.deviceInstall?.enabled).isTrue()
        assertThat(rommCall { api.heartbeat() }.deviceInstall).isNull()
    }

    /** RomM answers 422 to a reason over 500 characters; one cut short never splits a character in two. */
    @Test fun aReasonIsCutToWhatRommTakes() {
        assertThat(installReason("No space left")).isEqualTo("No space left")
        assertThat(installReason("x".repeat(600))).hasLength(MAX_INSTALL_REASON)
        val emoji = "x".repeat(MAX_INSTALL_REASON - 1) + "🎮" // the pair would straddle the cut
        assertThat(installReason(emoji)).isEqualTo("x".repeat(MAX_INSTALL_REASON - 1))
    }
}
