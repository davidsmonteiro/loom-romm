package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.romm.RommFixtures.device
import ro.davidmontei.loom.romm.RommFixtures.deviceSync
import ro.davidmontei.loom.romm.RommFixtures.negotiated
import ro.davidmontei.loom.romm.RommFixtures.save
import ro.davidmontei.loom.romm.RommFixtures.syncOp
import ro.davidmontei.loom.romm.RommFixtures.syncSession
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.api.saveFilePart
import ro.davidmontei.loom.romm.dto.ClientSaveStateDto
import ro.davidmontei.loom.romm.dto.SaveDeviceRequest
import ro.davidmontei.loom.romm.dto.SyncAction
import ro.davidmontei.loom.romm.dto.SyncCompleteRequest
import ro.davidmontei.loom.romm.dto.SyncNegotiateRequest
import java.time.Instant

/** The save-sync calls against a MockWebServer shaped like RomM 5.3.1 (and 5.4.0 where it differs). */
class RommSaveSyncApiTest {
    @get:Rule val rule = MockWebServerRule()
    @get:Rule val tmp = TemporaryFolder()
    private val server get() = rule.server

    /** Under a path prefix, like RommApiTest, to prove the new paths are relative too. */
    private val api: RommApi by lazy { RommServiceFactory.create(server.url("/romm/"), OkHttpClient()) }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).setHeader("Content-Type", "application/json").body(body).build()

    private fun clientSave(romId: Int, hash: String? = "c43ef632a43368c6ec5e23a4e9653a50") = ClientSaveStateDto(
        romId = romId, fileName = "Pokemon X.zip", slot = "autosave", emulator = "azahar",
        contentHash = hash, updatedAt = "2026-10-13T17:49:45Z", fileSizeBytes = 415_232,
    )

    private fun requestJson(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test fun negotiatePostsSavesAndScopeAndReadsEachOperation() = runTest {
        server.enqueue(json(negotiated(41, listOf(
            syncOp("upload", 7),
            syncOp("download", 8, saveId = 80, fileName = "Pokemon Y [2026-10-12_08-00-00].zip",
                reason = "Save exists on server but not on client",
                serverUpdatedAt = "2026-10-12T08:00:00+00:00", serverHash = "a".repeat(32)),
            syncOp("conflict", 9, saveId = 90, reason = "Both client and server changed since last sync",
                serverUpdatedAt = "2026-10-12T09:00:00+00:00", serverHash = "b".repeat(32)),
            syncOp("no_op", 10, saveId = 100, reason = "Save is untracked on this device"),
        )).toString()))

        val response = rommCall {
            api.negotiateSync(SyncNegotiateRequest("dev-thor", listOf(clientSave(7), clientSave(9, hash = null)), listOf(7, 8, 9, 10)))
        }

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/romm/api/sync/negotiate")
        val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertThat(body["device_id"]!!.jsonPrimitive.content).isEqualTo("dev-thor")
        assertThat(body["rom_ids"]!!.jsonArray.map { it.jsonPrimitive.int }).containsExactly(7, 8, 9, 10).inOrder()
        val first = body["saves"]!!.jsonArray[0].jsonObject
        assertThat(first["slot"]!!.jsonPrimitive.content).isEqualTo("autosave")
        assertThat(first["content_hash"]!!.jsonPrimitive.content).isEqualTo("c43ef632a43368c6ec5e23a4e9653a50")
        assertThat(first["updated_at"]!!.jsonPrimitive.content).isEqualTo("2026-10-13T17:49:45Z")
        assertThat(body["saves"]!!.jsonArray[1].jsonObject.keys).doesNotContain("content_hash")

        assertThat(response.sessionId).isEqualTo(41)
        assertThat(response.operations.map { it.syncAction })
            .containsExactly(SyncAction.UPLOAD, SyncAction.DOWNLOAD, SyncAction.CONFLICT, SyncAction.NO_OP).inOrder()
        assertThat(response.operations[0].saveId).isNull()
        assertThat(response.operations[0].serverUpdatedAt).isNull()
        val download = response.operations[1]
        assertThat(download.saveId).isEqualTo(80)
        assertThat(download.serverContentHash).isEqualTo("a".repeat(32))
        assertThat(RommTime.parse(download.serverUpdatedAt!!)).isEqualTo(Instant.parse("2026-10-12T08:00:00Z"))
        assertThat(response.totalConflict).isEqualTo(1)
        assertThat(response.totalDelete).isEqualTo(0) // 5.3.1 doesn't send it
    }

    /** RomM 5.4.0 adds `delete` and `total_delete`; a value Loom doesn't know must still parse. */
    @Test fun negotiateToleratesWhatANewerServerSends() = runTest {
        val ops = listOf(
            syncOp("delete", 7, reason = "Slot was emptied on the server"),
            syncOp("merge", 8, saveId = 80, reason = "Something new"),
        )
        server.enqueue(json(negotiated(42, ops, totalDelete = 1).toString()))
        val response = rommCall { api.negotiateSync(SyncNegotiateRequest(null, emptyList(), listOf(7, 8))) }
        assertThat(response.operations.map { it.syncAction }).containsExactly(SyncAction.DELETE, SyncAction.UNKNOWN).inOrder()
        assertThat(response.operations[1].action).isEqualTo("merge")
        assertThat(response.totalDelete).isEqualTo(1)
    }

    /** Without rom_ids RomM scopes the whole library; an empty list must reach it as `[]`. */
    @Test fun negotiateAlwaysSendsItsScope() = runTest {
        server.enqueue(json(negotiated(43, emptyList()).toString()))
        rommCall { api.negotiateSync(SyncNegotiateRequest(null, listOf(clientSave(7)), emptyList())) }
        val body = requestJson()
        assertThat(body["rom_ids"]).isEqualTo(JsonArray(emptyList()))
        assertThat(body.keys).doesNotContain("device_id") // the token's device is used
    }

    @Test fun negotiateScopeIsCappedAtRommsLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncNegotiateRequest(null, emptyList(), (1..501).toList())
        }
    }

    @Test fun completeSessionSendsTheCounters() = runTest {
        server.enqueue(json("""{"session":${syncSession(41, "COMPLETED", completed = 2, failed = 1)},"play_session_ingest":null}"""))
        val done = rommCall { api.completeSyncSession(41, SyncCompleteRequest(operationsCompleted = 2, operationsFailed = 1)) }
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/romm/api/sync/sessions/41/complete")
        assertThat(request.body!!.utf8()).isEqualTo("""{"operations_completed":2,"operations_failed":1}""")
        assertThat(done.session.status).isEqualTo("COMPLETED")
        assertThat(done.session.operationsFailed).isEqualTo(1)
    }

    @Test fun uploadSendsTheSaveAsMultipartWithEverySyncParameter() = runTest {
        val file = tmp.newFile("unit.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 1, 2)) }
        server.enqueue(json(save(501, 7).toString()))

        val saved = rommCall {
            api.uploadSave(
                romId = 7, emulator = "azahar", slot = "autosave", deviceId = "dev-thor", sessionId = 41,
                contentHash = "c43ef632a43368c6ec5e23a4e9653a50", saveFile = saveFilePart(file, "Pokemon X.zip"),
            )
        }

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        val url = request.url
        assertThat(url.encodedPath).isEqualTo("/romm/api/saves")
        val query = url.queryParameterNames.associateWith { url.queryParameter(it) }
        assertThat(query).containsExactly(
            "rom_id", "7", "emulator", "azahar", "slot", "autosave", "device_id", "dev-thor",
            "session_id", "41", "content_hash", "c43ef632a43368c6ec5e23a4e9653a50",
            "overwrite", "false", "autocleanup", "true", "autocleanup_limit", "10",
        )
        assertThat(request.headers["Content-Type"]).startsWith("multipart/form-data; boundary=")
        val body = request.body!!
        val text = body.string(Charsets.ISO_8859_1)
        assertThat(text).contains("Content-Disposition: form-data; name=\"saveFile\"; filename=\"Pokemon X.zip\"")
        assertThat(Buffer().write(body).indexOf(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0, 1, 2).toByteString())).isAtLeast(0L)

        assertThat(saved.id).isEqualTo(501)
        assertThat(saved.contentHash).isEqualTo("c43ef632a43368c6ec5e23a4e9653a50")
        assertThat(saved.emulator).isEqualTo("azahar")
    }

    @Test fun uploadLeavesOutWhatItDoesntHave() = runTest {
        val file = tmp.newFile("main").apply { writeBytes(ByteArray(16)) }
        server.enqueue(json(save(502, 7).toString()))
        rommCall {
            api.uploadSave(
                romId = 7, emulator = "azahar", slot = "autosave", deviceId = "dev-thor", sessionId = null,
                contentHash = null, overwrite = true, saveFile = saveFilePart(file, "main"),
            )
        }
        val url = server.takeRequest().url
        assertThat(url.queryParameterNames).containsNoneOf("session_id", "content_hash")
        assertThat(url.queryParameter("overwrite")).isEqualTo("true")
    }

    /** "Keep both" parks the Thor's copy outside every slot, where negotiate never pairs it. */
    @Test fun anArchivalUploadHasNoSlot() = runTest {
        val file = tmp.newFile("unit.zip").apply { writeBytes(ByteArray(8)) }
        server.enqueue(json(save(503, 7, slot = null, fileName = "Pokemon X (Thor 2026-10-13 22-15-07).zip").toString()))
        val saved = rommCall {
            api.uploadSave(
                romId = 7, emulator = "azahar", slot = null, deviceId = "dev-thor", sessionId = null,
                contentHash = null, saveFile = saveFilePart(file, "Pokemon X (Thor 2026-10-13 22-15-07).zip"),
            )
        }
        assertThat(server.takeRequest().url.queryParameterNames).doesNotContain("slot")
        assertThat(saved.slot).isNull()
    }

    /** RomM's 409 guard: the slot has a version this device never synced. It must surface as a conflict. */
    @Test fun uploadRejectedBecauseTheSlotMovedOnIsAConflict() = runTest {
        val file = tmp.newFile("unit.zip").apply { writeBytes(ByteArray(8)) }
        server.enqueue(json("""{"detail":"Slot has a newer save since your last sync"}""", code = 409))
        val conflict = assertThrows(RommException.Conflict::class.java) {
            runBlocking {
                rommCall {
                    api.uploadSave(
                        romId = 7, emulator = "azahar", slot = "autosave", deviceId = "dev-thor", sessionId = 41,
                        contentHash = null, saveFile = saveFilePart(file, "Pokemon X.zip"),
                    )
                }
            }
        }
        assertThat(conflict.detail).isEqualTo("Slot has a newer save since your last sync")
    }

    @Test fun savesListsVersionsWithTheirSyncRows() = runTest {
        val syncs = listOf(deviceSync("dev-thor", "2026-10-13T17:49:45+00:00"))
        server.enqueue(json(JsonArray(listOf(
            save(501, 7, syncs = syncs),
            save(500, 7, fileName = "Pokemon X [2026-10-01_10-00-00].zip", emulator = null, contentHash = null,
                updatedAt = "2026-10-01T10:00:00+00:00", originDeviceId = null),
        )).toString()))

        val saves = rommCall { api.saves(romIds = listOf(7, 8), slot = "autosave", deviceId = "dev-thor") }

        val url = server.takeRequest().url
        assertThat(url.encodedPath).isEqualTo("/romm/api/saves")
        assertThat(url.queryParameterValues("rom_ids")).containsExactly("7", "8").inOrder()
        assertThat(url.queryParameter("slot")).isEqualTo("autosave")
        assertThat(url.queryParameter("device_id")).isEqualTo("dev-thor")
        assertThat(url.queryParameterNames).doesNotContain("rom_id")
        assertThat(saves.map { it.id }).containsExactly(501, 500).inOrder()
        assertThat(saves[0].deviceSyncs.single().lastSyncedAt).isEqualTo("2026-10-13T17:49:45+00:00")
        assertThat(saves[1].originDeviceId).isNull()
        assertThat(saves[1].emulator).isNull()
        assertThat(saves[1].contentHash).isNull()
    }

    /** `optimistic=true` (RomM's default) would mark the save synced before Loom has stored it. */
    @Test fun contentIsFetchedNonOptimisticallyAndStreamed() = runTest {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/zip")
            .body(Buffer().write(bytes)).build())

        val body = rommCall { api.saveContent(80, deviceId = "dev-thor", sessionId = 41) }
        val received = body.use { it.bytes() }

        val url = server.takeRequest().url
        assertThat(url.encodedPath).isEqualTo("/romm/api/saves/80/content")
        assertThat(url.queryParameter("optimistic")).isEqualTo("false")
        assertThat(url.queryParameter("device_id")).isEqualTo("dev-thor")
        assertThat(url.queryParameter("session_id")).isEqualTo("41")
        assertThat(received).isEqualTo(bytes)
    }

    @Test fun downloadedTrackAndUntrackPostTheDevice() = runTest {
        repeat(3) { server.enqueue(json(save(80, 8).toString())) }
        rommCall { api.confirmSaveDownloaded(80, SaveDeviceRequest("dev-thor")) }
        rommCall { api.untrackSave(80, SaveDeviceRequest("dev-thor")) }
        rommCall { api.trackSave(80, SaveDeviceRequest("dev-thor")) }
        val requests = List(3) { server.takeRequest() }
        assertThat(requests.map { it.method }).containsExactly("POST", "POST", "POST")
        assertThat(requests.map { it.url.encodedPath }).containsExactly(
            "/romm/api/saves/80/downloaded", "/romm/api/saves/80/untrack", "/romm/api/saves/80/track",
        ).inOrder()
        assertThat(requests.map { it.body!!.utf8() }.distinct()).containsExactly("""{"device_id":"dev-thor"}""")
    }

    @Test fun deviceIsLookedUpById() = runTest {
        server.enqueue(json(device("3f2a-uuid", "Steam Deck").toString()))
        val found = rommCall { api.device("3f2a-uuid") }
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/romm/api/devices/3f2a-uuid")
        assertThat(found.name).isEqualTo("Steam Deck")
        assertThat(found.syncEnabled).isTrue()
    }

    @Test fun devicesAreListedWithTheirIdentifiers() = runTest {
        server.enqueue(json(JsonArray(listOf(device("3f2a-uuid", "Steam Deck"), device("9b1c-uuid", "AYN Thor (Loom)"))).toString()))
        val devices = rommCall { api.devices() }
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/romm/api/devices")
        assertThat(devices.map { it.id }).containsExactly("3f2a-uuid", "9b1c-uuid").inOrder()
        assertThat(devices[0].clientDeviceIdentifier).isEqualTo("argosy-1")
    }

    @Test fun rommTimesParseWithOrWithoutOffsetAndFraction() {
        assertThat(RommTime.parse("2026-10-13T17:49:45+00:00")).isEqualTo(Instant.parse("2026-10-13T17:49:45Z"))
        assertThat(RommTime.parse("2026-10-13T18:49:45.123456+01:00")).isEqualTo(Instant.parse("2026-10-13T17:49:45.123456Z"))
        assertThat(RommTime.parse("2026-10-13 17:49:45")).isEqualTo(Instant.parse("2026-10-13T17:49:45Z")) // naive = UTC
        assertThat(RommTime.parse("2026-10-13T17:49:45Z")).isEqualTo(Instant.parse("2026-10-13T17:49:45Z"))
        assertThat(RommTime.parse("yesterday")).isNull()
    }
}
