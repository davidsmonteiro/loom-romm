package ro.davidmontei.loom.data.catalog

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ServerInfo
import ro.davidmontei.loom.romm.RommJson
import ro.davidmontei.loom.romm.RommServiceFactory
import ro.davidmontei.loom.romm.dto.PlatformDto
import ro.davidmontei.loom.romm.dto.RomDto
import java.io.File

@RunWith(AndroidJUnit4::class)
class CatalogSyncerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var syncer: CatalogSyncer
    private val api by lazy { RommServiceFactory.create(server.url("/"), OkHttpClient()) }

    @Before fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LoomDatabase::class.java)
            .allowMainThreadQueries().build()
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        syncer = CatalogSyncer(db.catalogDao(), settings, nowMs = { 1_000L })
    }

    @After fun tearDown() { db.close(); server.close(); scope.cancel() }

    private fun json(body: String) = MockResponse.Builder().code(200)
        .setHeader("Content-Type", "application/json").body(body).build()

    private fun heartbeat() = json("""{"SYSTEM":{"VERSION":"5.3.1","SHOW_SETUP_WIZARD":false}}""")

    private fun platform(id: Int, slug: String) = buildJsonObject {
        put("id", id); put("slug", slug); put("fs_slug", slug); put("name", slug.uppercase())
        put("display_name", slug.uppercase()); put("rom_count", 1); put("fs_size_bytes", 0)
        put("updated_at", "2026-10-01T10:00:00+00:00")
    }

    private fun rom(id: Int, platformId: Int, updatedAt: String, files: List<JsonObject> = emptyList()) = buildJsonObject {
        put("id", id); put("platform_id", platformId); put("platform_slug", "snes"); put("platform_fs_slug", "snes")
        put("name", "Café Game $id"); put("name_sort_key", null as String?); put("fs_name", "Game $id.sfc")
        put("fs_name_no_tags", "Game $id"); put("fs_name_no_ext", "Game $id"); put("fs_extension", "sfc")
        put("full_path", "roms/snes/Game $id.sfc")
        put("fs_size_bytes", 2048); put("path_cover_small", ""); put("path_cover_large", "/assets/x.png")
        put("url_cover", null as String?); put("regions", buildJsonArray { add(JsonPrimitive("USA")) })
        put("languages", JsonArray(emptyList())); put("tags", JsonArray(emptyList())); put("revision", null as String?)
        put("crc_hash", ""); put("md5_hash", null as String?); put("sha1_hash", null as String?)
        put("has_simple_single_file", true); put("has_nested_single_file", false); put("has_multiple_files", false)
        put("missing_from_fs", false); put("is_physical", false)
        put("created_at", "2026-09-01T00:00:00+00:00"); put("updated_at", updatedAt)
        put("files", JsonArray(files))
    }

    private fun file(id: Int, romId: Int, sha1: String) = buildJsonObject {
        put("id", id); put("rom_id", romId); put("file_name", "Game $romId.sfc"); put("file_path", "roms/snes")
        put("full_path", "roms/snes/Game $romId.sfc"); put("file_size_bytes", 2048); put("is_top_level", true)
        put("crc_hash", ""); put("md5_hash", null as String?); put("sha1_hash", sha1)
        put("archive_members", null as String?); put("category", null as String?)
        put("updated_at", "2026-10-01T10:00:00+00:00")
    }

    private fun page(items: List<JsonObject>, total: Int?) = buildJsonObject {
        put("items", JsonArray(items)); put("total", total); put("limit", 250); put("offset", 0)
    }.toString()

    @Test fun fullSyncWritesCatalogAndWatermark() = runBlocking<Unit> {
        server.enqueue(heartbeat())
        server.enqueue(json(JsonArray(listOf(platform(1, "snes"))).toString()))
        server.enqueue(json(page(listOf(
            rom(10, 1, "2026-10-05T08:00:00.123456+00:00", listOf(file(100, 10, "a".repeat(40)))),
            rom(11, 1, "2026-10-07T09:30:00+00:00"),
        ), total = 2)))
        server.enqueue(json("[10,11]"))

        val result = syncer.sync(api, full = false)

        assertThat(result.full).isTrue() // no watermark yet → full
        assertThat(result.romsWritten).isEqualTo(2)
        val dao = db.catalogDao()
        val rom = dao.rom(10)!!
        assertThat(rom.title).isEqualTo("Café Game 10")
        assertThat(rom.searchKey).contains("cafe game 10")
        assertThat(rom.coverSmall).isNull() // "" means no cover
        assertThat(dao.filesOf(10).single().sha1).isEqualTo("a".repeat(40))
        assertThat(dao.filesOf(10).single().crc).isNull() // "" means not hashed
        val s = settings.snapshot()
        assertThat(s.catalogWatermark).isEqualTo("2026-10-07T09:30:00.000000+00:00")
        assertThat(s.serverVersion).isEqualTo("5.3.1")
        assertThat(s.lastCatalogSyncEpochMs).isEqualTo(1_000L)

        server.takeRequest(); server.takeRequest()
        val romsRequest = server.takeRequest().url
        assertThat(romsRequest.queryParameter("updated_after")).isNull()
        assertThat(romsRequest.queryParameter("with_files")).isEqualTo("true")
    }

    @Test fun incrementalSyncUsesOverlapAndAppliesDeletions() = runBlocking<Unit> {
        val dao = db.catalogDao()
        settings.setCatalogSynced("2026-10-07T09:30:00.000000+00:00", 0, CatalogSyncer.CATALOG_FIELDS)
        dao.upsertPlatforms(listOf(platform(1, "snes"), platform(2, "gba")).map { p ->
            RommJson.decodeFromJsonElement(PlatformDto.serializer(), p).toEntity()
        })
        val seed = listOf(rom(10, 1, "2026-10-05T00:00:00+00:00"), rom(11, 1, "2026-10-05T00:00:00+00:00"),
            rom(12, 2, "2026-10-05T00:00:00+00:00"))
            .map { RommJson.decodeFromJsonElement(RomDto.serializer(), it) }
        dao.writePage(seed.map { it.toEntity() }, emptyList())
        // ROM 12 (on gba, about to vanish from the server) is installed.
        dao.upsertInstall(InstallEntity(12, "9C33-6BBD", "/storage/9C33-6BBD/ROMs", "gba/Game 12.gba", "gba/game 12.gba", 2,
            InstallKind.FILE, 2048, VerifiedBy.SHA1, InstallSource.LOOM, null, 0))

        server.enqueue(heartbeat())
        server.enqueue(json(JsonArray(listOf(platform(1, "snes"))).toString())) // gba platform gone
        server.enqueue(json(page(listOf(rom(10, 1, "2026-10-08T10:00:00+00:00")), total = 1)))
        server.enqueue(json("[10]")) // 11 and 12 deleted on the server

        val result = syncer.sync(api, full = false)

        assertThat(result.full).isFalse()
        server.takeRequest(); server.takeRequest()
        assertThat(server.takeRequest().url.queryParameter("updated_after")).isEqualTo("2026-10-07T08:30:00.000000+00:00")
        assertThat(dao.rom(11)).isNull()
        assertThat(dao.rom(12)!!.removedOnServer).isTrue()
        assertThat(dao.platform(2)).isNotNull() // kept: an install still lives there
        assertThat(settings.snapshot().catalogWatermark).isEqualTo("2026-10-08T10:00:00.000000+00:00")
    }

    @Test fun fullSyncIgnoresWatermark() = runBlocking<Unit> {
        settings.setCatalogSynced("2026-10-07T09:30:00.000000+00:00", 0, CatalogSyncer.CATALOG_FIELDS)
        server.enqueue(heartbeat())
        server.enqueue(json("[]"))
        server.enqueue(json(page(emptyList(), total = 0)))
        server.enqueue(json("[]"))
        val result = syncer.sync(api, full = true)
        assertThat(result.full).isTrue()
        server.takeRequest(); server.takeRequest()
        assertThat(server.takeRequest().url.queryParameter("updated_after")).isNull()
    }

    /** A catalog written before Loom stored title ids gets one full pass, so every 3DS game has its id. */
    @Test fun theFirstSyncAfterAnUpgradeIsFullAndStoresTitleIds() = runBlocking<Unit> {
        settings.setCatalogSynced("2026-10-07T09:30:00.000000+00:00", 0, fields = 1)
        val pokemon = buildJsonObject {
            rom(10, 1, "2026-09-01T00:00:00+00:00").forEach { (k, v) -> put(k, v) }
            put("title_id", "000400000011C400"); put("save_target", "00040000/0011c400"); put("save_target_layout", "folder-split")
        }
        server.enqueue(heartbeat())
        server.enqueue(json(JsonArray(listOf(platform(1, "3ds"))).toString()))
        server.enqueue(json(page(listOf(pokemon), total = 1)))
        server.enqueue(json("[10]"))

        assertThat(syncer.sync(api, full = false).full).isTrue()
        server.takeRequest(); server.takeRequest()
        assertThat(server.takeRequest().url.queryParameter("updated_after")).isNull()
        val rom = db.catalogDao().rom(10)!!
        assertThat(rom.titleId).isEqualTo("000400000011C400")
        assertThat(rom.saveTarget).isEqualTo("00040000/0011c400")
        assertThat(rom.saveTargetLayout).isEqualTo("folder-split")

        // From then on, incremental again.
        server.enqueue(heartbeat()); server.enqueue(json("[]")); server.enqueue(json(page(emptyList(), total = 0))); server.enqueue(json("[10]"))
        assertThat(syncer.sync(api, full = false).full).isFalse()
    }

    /** Through the app's graph a sync's heartbeat goes to [ServerInfo], so it also learns whether RomM can send games here. */
    @Test fun aSyncRecordsWhatTheHeartbeatSays() = runBlocking<Unit> {
        val sendToDevice = SendToDeviceStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "d.preferences_pb") })
        val syncer = CatalogSyncer(db.catalogDao(), settings, nowMs = { 1_000L }, onHeartbeat = ServerInfo(settings, sendToDevice)::record)
        server.enqueue(json("""{"SYSTEM":{"VERSION":"5.4.0","SHOW_SETUP_WIZARD":false},
            "DEVICE_INSTALL":{"ENABLED":false,"EXCLUDED_PLATFORM_SLUGS":["win"]}}"""))
        server.enqueue(json("[]"))
        server.enqueue(json(page(emptyList(), total = 0)))
        server.enqueue(json("[]"))

        assertThat(syncer.sync(api, full = true).serverVersion).isEqualTo("5.4.0")
        assertThat(settings.snapshot().serverVersion).isEqualTo("5.4.0")
        assertThat(sendToDevice.snapshot().installsEnabled).isFalse()
    }

    @Test fun watermarkMathHandlesSpacesAndOffsets() {
        assertThat(CatalogSyncer.overlap("2026-10-10 15:16:10.123+02:00")).isEqualTo("2026-10-10T12:16:10.123000+00:00")
        assertThat(CatalogSyncer.overlap("garbage")).isNull()
    }
}
