package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import ro.davidmontei.loom.romm.RommFixtures.member
import ro.davidmontei.loom.romm.RommFixtures.page
import ro.davidmontei.loom.romm.RommFixtures.platform
import ro.davidmontei.loom.romm.RommFixtures.rom
import ro.davidmontei.loom.romm.RommFixtures.romFile
import ro.davidmontei.loom.romm.api.RommApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class RommApiTest {
    @get:Rule val rule = MockWebServerRule()
    private val server get() = rule.server

    /** RomM mounted under a path prefix, to prove relative paths keep it. */
    private val api: RommApi by lazy { RommServiceFactory.create(server.url("/romm/"), OkHttpClient()) }

    private fun json(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Test fun heartbeatReadsVersionAndIgnoresUnknownSections() = runTest {
        server.enqueue(json(RommFixtures.HEARTBEAT_5_3_1))
        val hb = rommCall { api.heartbeat() }
        assertThat(hb.system.version).isEqualTo("5.3.1")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/romm/api/heartbeat")
    }

    @Test fun the540HeartbeatReadsToo() = runTest {
        server.enqueue(json(RommFixtures.HEARTBEAT_5_4_0))
        assertThat(rommCall { api.heartbeat() }.system.version).isEqualTo("5.4.0")
    }

    @Test fun platformsParse() = runTest {
        server.enqueue(json(JsonArray(listOf(platform(1, "snes"), platform(2, "ngc", name = "GameCube"))).toString()))
        val platforms = rommCall { api.platforms() }
        assertThat(platforms.map { it.slug }).containsExactly("snes", "ngc").inOrder()
        assertThat(platforms[1].displayName).isEqualTo("GameCube")
    }

    @Test fun romsRequestAsksForFilesAndSkipsExpensiveIndexes() = runTest {
        server.enqueue(json(page(listOf(rom(1)), total = 1, limit = 250, offset = 0).toString()))
        rommCall { api.roms(offset = 0, limit = 250, updatedAfter = "2026-10-01T09:00:00+00:00") }
        val url = server.takeRequest().url
        assertThat(url.encodedPath).isEqualTo("/romm/api/roms")
        assertThat(url.queryParameter("with_files")).isEqualTo("true")
        assertThat(url.queryParameter("with_char_index")).isEqualTo("false")
        assertThat(url.queryParameter("with_filter_values")).isEqualTo("false")
        assertThat(url.queryParameter("with_rom_id_index")).isEqualTo("false")
        assertThat(url.queryParameter("order_by")).isEqualTo("id")
        assertThat(url.queryParameter("updated_after")).isEqualTo("2026-10-01T09:00:00+00:00")
    }

    @Test fun pagerWalksUntilShortPageAndOnlyCountsOnce() = runTest {
        server.enqueue(json(page(listOf(rom(1), rom(2)), total = 5, limit = 2, offset = 0).toString()))
        server.enqueue(json(page(listOf(rom(3), rom(4)), total = null, limit = 2, offset = 2).toString()))
        server.enqueue(json(page(listOf(rom(5)), total = null, limit = 2, offset = 4).toString()))
        val pages = api.pageRoms(pageSize = 2).toList()
        assertThat(pages.flatMap { p -> p.page.items.map { it.id } }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(pages.last().fetched).isEqualTo(5)
        assertThat(pages.all { it.total == 5 }).isTrue()
        val requests = List(3) { server.takeRequest().url }
        assertThat(requests.map { it.queryParameter("offset") }).containsExactly("0", "2", "4").inOrder()
        assertThat(requests.map { it.queryParameter("with_total") }).containsExactly("true", "false", "false").inOrder()
    }

    @Test fun pagerStopsOnEmptyFirstPage() = runTest {
        server.enqueue(json(page(emptyList(), total = 0, limit = 250, offset = 0).toString()))
        assertThat(api.pageRoms().toList().single().page.items).isEmpty()
    }

    @Test fun romDetailParsesFilesHashesAndArchiveMembers() = runTest {
        val files = listOf(
            romFile(11, 7, "Game (Disc 1).cue", 120, sha1 = "a".repeat(40)),
            romFile(12, 7, "Game (Disc 1).bin", 650_000_000, sha1 = ""),
            romFile(13, 7, "Game.zip", 2048, crc = "deadbeef",
                members = JsonArray(listOf(member("Game.iso", 4096, "cafebabe")))),
            romFile(14, 7, "Manual.pdf", 10, category = "manual", topLevel = false),
        )
        server.enqueue(json(rom(7, platformSlug = "psx", fsName = "Game", files = files).toString()))
        val detail = rommCall { api.rom(7) }
        assertThat(detail.hasMultipleFiles).isTrue()
        assertThat(detail.files.map { it.id }).containsExactly(11, 12, 13, 14).inOrder()
        assertThat(detail.files[1].sha1Hash).isEmpty()
        assertThat(detail.files[2].archiveMembers!!.single().crcHash).isEqualTo("cafebabe")
        assertThat(detail.files[3].category).isEqualTo("manual")
        assertThat(detail.fsSizeBytes).isEqualTo(1024)
    }

    @Test fun romsCarryTheirSaveKeys() = runTest {
        server.enqueue(json(rom(7, platformSlug = "n3ds", fsName = "Pokemon X.3ds", titleId = "0004000000055D00",
            saveTarget = "00040000/00055d00", saveTargetLayout = "folder-split").toString()))
        val detail = rommCall { api.rom(7) }
        assertThat(detail.titleId).isEqualTo("0004000000055D00")
        assertThat(detail.saveTarget).isEqualTo("00040000/00055d00")
        assertThat(detail.saveTargetLayout).isEqualTo("folder-split")
    }

    /** A server that predates these fields leaves them out; the ROM still parses. */
    @Test fun romsWithoutSaveKeysStillParse() = runTest {
        val old = JsonObject(rom(7) - setOf("title_id", "save_target", "save_target_layout"))
        server.enqueue(json(old.toString()))
        val detail = rommCall { api.rom(7) }
        assertThat(detail.titleId).isNull()
        assertThat(detail.saveTargetLayout).isNull()
    }

    @Test fun romIdentifiersParse() = runTest {
        server.enqueue(json(RommFixtures.ids(3, 1, 2)))
        assertThat(rommCall { api.romIdentifiers() }).containsExactly(3, 1, 2)
    }

    @Test fun errorsMapToTypedExceptionsWithFastApiDetail() = runTest {
        server.enqueue(json("""{"detail":"Invalid or expired pairing code"}""", code = 404))
        val notFound = assertThrows(RommException.NotFound::class.java) {
            kotlinx.coroutines.runBlocking { rommCall { api.romIdentifiers() } }
        }
        assertThat(notFound.message).isEqualTo("Invalid or expired pairing code")

        server.enqueue(json("""{"detail":"Not authenticated"}""", code = 401))
        assertThrows(RommException.Unauthorized::class.java) {
            kotlinx.coroutines.runBlocking { rommCall { api.me() } }
        }

        server.enqueue(json("""{"detail":[{"loc":["query","limit"],"msg":"Input should be less than 10000"}]}""", code = 422))
        val invalid = assertThrows(RommException.Http::class.java) {
            kotlinx.coroutines.runBlocking { rommCall { api.romIdentifiers() } }
        }
        assertThat(invalid.code).isEqualTo(422)
        assertThat(invalid.detail).isEqualTo("Input should be less than 10000")
    }

    @Test fun nonJsonBodyIsAProtocolError() = runTest {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "text/html")
            .body("<html>Cloudflare Access</html>").build())
        assertThrows(RommException.Protocol::class.java) {
            kotlinx.coroutines.runBlocking { rommCall { api.heartbeat() } }
        }
    }

    @Test fun unreachableServerIsUnreachable() = runTest {
        val dead = RommServiceFactory.create(RommUrls.normalize("http://127.0.0.1:1/")!!, OkHttpClient())
        assertThrows(RommException.Unreachable::class.java) {
            kotlinx.coroutines.runBlocking { rommCall { dead.heartbeat() } }
        }
    }
}
