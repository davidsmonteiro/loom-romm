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
import kotlinx.coroutines.flow.first
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
import java.util.TimeZone

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

    // --- what RomM says of each game beyond its files: summary, release year, genres, companies ---

    /** [rom] with RomM's summary and metadata, as 5.3.1 and 5.4.0 send them in a page of ROMs. */
    private fun withDetails(
        rom: JsonObject,
        summary: String?,
        genres: List<String> = emptyList(),
        companies: List<String> = emptyList(),
        firstReleaseDate: Long? = null,
    ) = buildJsonObject {
        rom.forEach { (k, v) -> put(k, v) }
        put("summary", summary)
        put("metadatum", buildJsonObject {
            put("rom_id", rom["id"]!!)
            put("genres", JsonArray(genres.map(::JsonPrimitive))); put("companies", JsonArray(companies.map(::JsonPrimitive)))
            listOf("franchises", "collections", "publishers", "developers", "game_modes", "age_ratings").forEach { put(it, JsonArray(emptyList())) }
            put("player_count", "1"); put("first_release_date", firstReleaseDate); put("average_rating", null as Double?)
        })
    }

    private fun decode(rom: JsonObject) = RommJson.decodeFromJsonElement(RomDto.serializer(), rom)

    /** A full sync of [roms], from an empty catalog. */
    private suspend fun syncPage(vararg roms: JsonObject, full: Boolean = true) {
        server.enqueue(heartbeat())
        server.enqueue(json(JsonArray(listOf(platform(1, "snes"))).toString()))
        server.enqueue(json(page(roms.toList(), total = roms.size)))
        server.enqueue(json(JsonArray(roms.map { it["id"]!! }).toString()))
        syncer.sync(api, full = full)
    }

    /**
     * Each game's summary on one line, the year it came out, its first three genres and first two
     * companies are stored with the page; a game RomM says none of that about gets no row.
     */
    @Test fun aSyncStoresEachGamesDetails() = runBlocking<Unit> {
        syncPage(
            withDetails(rom(10, 1, "2026-10-05T08:00:00+00:00"), "Crono, a boy from 1000 AD,\n\n  travels through time.",
                genres = listOf("Role-playing (RPG)", "Adventure", " Turn-based strategy (TBS) ", "Adventure", "Puzzle"),
                companies = listOf("Square Co., Ltd.", "", "Nintendo", "Enix"), firstReleaseDate = 794_880_000_000),
            rom(11, 1, "2026-10-05T08:00:00+00:00"),
        )
        val details = db.catalogDao().observeDetails(10).first()!!
        assertThat(details.summary).isEqualTo("Crono, a boy from 1000 AD, travels through time.")
        assertThat(details.releaseYear).isEqualTo(1995)
        assertThat(details.genres.lines()).containsExactly("Role-playing (RPG)", "Adventure", "Turn-based strategy (TBS)").inOrder()
        assertThat(details.companies.lines()).containsExactly("Square Co., Ltd.", "Nintendo").inOrder()
        assertThat(db.catalogDao().observeDetails(11).first()).isNull()
    }

    /** RomM's summaries run to thousands of characters: about a thousand are kept, cut after a word, with an ellipsis. */
    @Test fun aSummaryIsKeptShort() = runBlocking<Unit> {
        val words = (1..400).joinToString(" ") { "word$it" } // about 2,800 characters
        syncPage(withDetails(rom(10, 1, "2026-10-05T08:00:00+00:00"), words))
        val kept = db.catalogDao().observeDetails(10).first()!!.summary!!
        assertThat(kept.length).isAtMost(CatalogSyncer.SUMMARY_LENGTH)
        assertThat(kept.length).isAtLeast(CatalogSyncer.SUMMARY_LENGTH - 10)
        assertThat(kept).endsWith("…")
        assertThat("$words ").startsWith(kept.removeSuffix("…") + " ") // whole words only

        // One unbroken string has no word to cut after: it is cut where the room ends, never inside a character.
        val oneWord = "🎮".repeat(800)
        assertThat(shortSummary(oneWord)!!.length).isAtMost(CatalogSyncer.SUMMARY_LENGTH)
        assertThat(shortSummary(oneWord)!!.removeSuffix("…").last().isHighSurrogate()).isFalse()
        assertThat(shortSummary("A short one.")).isEqualTo("A short one.")
        assertThat(shortSummary(" \n ")).isNull()
    }

    /** A game whose summary and metadata RomM dropped (unmatched since) loses what Loom kept of them at the next sync. */
    @Test fun detailsRommDroppedAreDropped() = runBlocking<Unit> {
        syncPage(withDetails(rom(10, 1, "2026-10-05T08:00:00+00:00"), "Crono travels through time.", firstReleaseDate = 794_880_000_000))
        assertThat(db.catalogDao().observeDetails(10).first()).isNotNull()

        server.enqueue(heartbeat())
        server.enqueue(json(JsonArray(listOf(platform(1, "snes"))).toString()))
        server.enqueue(json(page(listOf(withDetails(rom(10, 1, "2026-10-08T10:00:00+00:00"), null)), total = 1)))
        server.enqueue(json("[10]"))
        assertThat(syncer.sync(api, full = false).full).isFalse()
        assertThat(db.catalogDao().observeDetails(10).first()).isNull()
    }

    /** Metadata with nothing in it (no genres, companies or date, a blank summary) is no details at all. */
    @Test fun aGameWithoutDetailsStoresNone() = runBlocking<Unit> {
        syncPage(withDetails(rom(10, 1, "2026-10-05T08:00:00+00:00"), "  \n ", genres = listOf(" "), companies = emptyList()))
        assertThat(db.catalogDao().observeDetails(10).first()).isNull()
        assertThat(decode(rom(11, 1, "t")).toDetails()).isNull() // a server that sends none of it
    }

    /**
     * A catalog written before Loom kept RomM's details gets one full pass, so games that haven't
     * changed on RomM since get theirs too; then syncs are incremental again.
     */
    @Test fun theFirstSyncAfterThisUpgradeIsFull() = runBlocking<Unit> {
        settings.setCatalogSynced("2026-10-07T09:30:00.000000+00:00", 0, fields = 2)
        server.enqueue(heartbeat())
        server.enqueue(json(JsonArray(listOf(platform(1, "snes"))).toString()))
        server.enqueue(json(page(listOf(withDetails(rom(10, 1, "2026-09-01T00:00:00+00:00"), "Crono travels through time.")), total = 1)))
        server.enqueue(json("[10]"))

        assertThat(syncer.sync(api, full = false).full).isTrue()
        server.takeRequest(); server.takeRequest()
        assertThat(server.takeRequest().url.queryParameter("updated_after")).isNull()
        assertThat(db.catalogDao().observeDetails(10).first()!!.summary).isEqualTo("Crono travels through time.")
        assertThat(settings.snapshot().catalogFields).isEqualTo(CatalogSyncer.CATALOG_FIELDS)

        server.enqueue(heartbeat()); server.enqueue(json("[]")); server.enqueue(json(page(emptyList(), total = 0))); server.enqueue(json("[10]"))
        assertThat(syncer.sync(api, full = false).full).isFalse()
        assertThat(db.catalogDao().observeDetails(10).first()).isNotNull() // unchanged games keep theirs
    }

    /** RomM sends the first release in milliseconds; its year is the one in UTC, wherever the Thor is. */
    @Test fun releaseYearComesFromMilliseconds() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14: already 2000 there
        try {
            fun year(ms: Long?) = decode(withDetails(rom(10, 1, "t"), null, firstReleaseDate = ms)).toDetails()?.releaseYear
            assertThat(year(794_880_000_000)).isEqualTo(1995) // 1995-03-11
            assertThat(year(946_684_799_000)).isEqualTo(1999) // 1999-12-31 23:59:59 UTC
            assertThat(year(946_684_800_000)).isEqualTo(2000)
            assertThat(year(-31_536_000_000)).isEqualTo(1969)
            assertThat(year(null)).isNull()
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test fun watermarkMathHandlesSpacesAndOffsets() {
        assertThat(CatalogSyncer.overlap("2026-10-10 15:16:10.123+02:00")).isEqualTo("2026-10-10T12:16:10.123000+00:00")
        assertThat(CatalogSyncer.overlap("garbage")).isNull()
    }
}
