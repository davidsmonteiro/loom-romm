package ro.davidmontei.loom.data.download

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.StorageVolumeBuilder
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.storage.engine.JobPhase
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class DrainWorkerTest {
    private fun job(phase: JobPhase) = DownloadJobEntity(
        id = 7, romId = 10, platformId = 1, title = "Gran Turismo 2", phase = phase.name, planJson = "{}", fingerprint = "f",
        volumeUuid = "9C33-6BBD", stagingRoot = "/staging", romsRoot = "/roms", bytesTotal = 1, queueOrder = 1, updatedAt = 1,
    )

    /** The notification's Cancel would do nothing once the game is being moved into place, so it isn't offered then. */
    @Test fun theNotificationOffersCancelUntilTheGameIsMovedIntoPlace() {
        assertThat(DrainWorker.cancelTarget(job(JobPhase.DOWNLOADING))).isEqualTo(7L)
        assertThat(DrainWorker.cancelTarget(job(JobPhase.EXTRACTING))).isEqualTo(7L)
        assertThat(DrainWorker.cancelTarget(job(JobPhase.PROMOTING))).isNull()
        assertThat(DrainWorker.cancelTarget(null)).isNull() // installed meanwhile
    }

    // --- checking a job's plan alongside its download, in virtual time ---

    /** Found by review: a resumed download waited for RomM's answer about its plan, a round trip, before it started. */
    @Test fun theWorkStartsWithoutWaitingForTheCheck() = runTest {
        var startedAt = -1L
        DrainWorker.workWhileChecking(
            work = { startedAt = currentTime; delay(5_000) },
            check = { delay(2_000); null },
            act = { _: String -> error("nothing to act on") },
        )
        assertThat(startedAt).isEqualTo(0)
        assertThat(currentTime).isEqualTo(5_000)
    }

    @Test fun anAnswerStopsTheWorkBeforeItIsActedOn() = runTest {
        var finished = false
        var stoppedAt = -1L
        var acted: Pair<String, Long>? = null
        DrainWorker.workWhileChecking(
            work = { try { delay(10_000); finished = true } finally { stoppedAt = currentTime } },
            check = { delay(2_000); "changed" },
            act = { answer -> acted = answer to stoppedAt },
        )
        assertThat(finished).isFalse()
        assertThat(acted).isEqualTo("changed" to 2_000L) // acted on only once the work had stopped
        assertThat(currentTime).isEqualTo(2_000)
    }

    /** A download that finishes before RomM answers doesn't keep the queue waiting for the answer. */
    @Test fun workEndingFirstDropsTheCheck() = runTest {
        var acted = false
        DrainWorker.workWhileChecking(work = { delay(1_000) }, check = { delay(10_000); "changed" }, act = { _: String -> acted = true })
        assertThat(currentTime).isEqualTo(1_000)
        assertThat(acted).isFalse()
    }

    @Test fun aCheckThatFailsLeavesTheWorkAlone() = runTest {
        var finished = false
        DrainWorker.workWhileChecking(
            work = { delay(3_000); finished = true },
            check = { delay(100); throw IOException("RomM can't be reached") },
            act = { _: String -> error("nothing to act on") },
        )
        assertThat(finished).isTrue()
    }

    // --- the worker, end to end ---

    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val romm = MockWebServer()
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var server: ActiveServer
    private lateinit var downloads: DownloadRepository
    private lateinit var card: File

    /** What RomM is asked for, in order: "rom" (answered), "file:<id>" (asked for). */
    private val events = CopyOnWriteArrayList<String>()
    private val romCalls = AtomicInteger()
    private val fileAsked = CountDownLatch(1)
    private val served = mutableMapOf<Int, ByteArray>()
    @Volatile private var throttleBytesPer100Ms: Long? = null
    /** RomM's answers to the plan check (the second time the ROM is fetched): the ROM's files, or null for gone. */
    @Volatile private var later: Pair<Int, ByteArray>? = null
    @Volatile private var goneLater = false

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rom(fileId: Int, data: ByteArray) = buildJsonObject {
        put("id", 10); put("platform_id", 1); put("platform_slug", "snes"); put("platform_fs_slug", "snes")
        put("name", "Chrono Trigger"); put("name_sort_key", null as String?); put("fs_name", "Chrono Trigger.sfc")
        put("fs_name_no_tags", "Chrono Trigger"); put("fs_name_no_ext", "Chrono Trigger"); put("fs_extension", "sfc")
        put("full_path", "roms/snes/Chrono Trigger.sfc")
        put("fs_size_bytes", data.size); put("path_cover_small", ""); put("path_cover_large", "")
        put("url_cover", null as String?); put("regions", buildJsonArray { add(JsonPrimitive("USA")) })
        put("languages", JsonArray(emptyList())); put("tags", JsonArray(emptyList())); put("revision", null as String?)
        put("crc_hash", ""); put("md5_hash", null as String?); put("sha1_hash", null as String?)
        put("has_simple_single_file", true); put("has_nested_single_file", false); put("has_multiple_files", false)
        put("missing_from_fs", false); put("is_physical", false)
        put("created_at", "2026-09-01T00:00:00+00:00"); put("updated_at", "2026-10-01T10:00:00+00:00")
        put("files", JsonArray(listOf(buildJsonObject {
            put("id", fileId); put("rom_id", 10); put("file_name", "Chrono Trigger.sfc"); put("file_path", "roms/snes")
            put("full_path", "roms/snes/Chrono Trigger.sfc"); put("file_size_bytes", data.size); put("is_top_level", true)
            put("crc_hash", ""); put("md5_hash", null as String?); put("sha1_hash", sha1(data))
            put("archive_members", null as String?); put("category", null as String?)
            put("updated_at", "2026-10-01T10:00:00+00:00")
        })))
    }

    private val original = 400 to ByteArray(600_000) { (it * 7).toByte() }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            Regex("""/api/roms/(\d+)/files/content/""").find(path)?.let { m ->
                val id = m.groupValues[1].toInt()
                events += "file:$id"
                fileAsked.countDown()
                val data = served[id] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).setHeader("Last-Modified", "Wed, 01 Oct 2026 10:00:00 GMT")
                    .apply { throttleBytesPer100Ms?.let { throttleBody(it, 100, TimeUnit.MILLISECONDS) } }
                    .body(Buffer().write(data)).build()
            }
            if (path == "/api/roms/10") {
                val call = romCalls.incrementAndGet()
                // The plan check: answered only once the download has asked for its file (or after 5 s).
                if (call > 1) fileAsked.await(5, TimeUnit.SECONDS)
                val (fileId, data) = if (call > 1) later ?: original else original
                events += "rom"
                if (call > 1 && goneLater) return MockResponse.Builder().code(404).body("""{"detail":"Rom not found"}""").build()
                return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(rom(fileId, data).toString()).build()
            }
            return MockResponse.Builder().code(404).build()
        }
    }

    @Before fun setUp() = runBlocking {
        romm.dispatcher = dispatcher
        romm.start()
        served[original.first] = original.second
        card = tmp.newFolder("6365-3230")
        File(card, "ROMs").mkdirs()
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("sd", card, "SD card", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setFsUuid("6365-3230").setIsRemovable(true).build(),
        )
        ShadowStatFs.registerStats(card, 2_000_000, 2_000_000, 2_000_000) // 8 GB free
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, LoomDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        settings.setServer(ServerConfig(romm.url("/")), "5.3.1")
        settings.setRomsRoot(File(card, "ROMs").path)
        val credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") },
            object : TokenCipher {
                override fun encrypt(plaintext: String) = plaintext
                override fun decrypt(blob: String) = blob
            }, scope)
        credentials.save(PairedCredentials(romm.url("/"), "rmm_x", "dev-thor", "david", null))
        credentials.current.filterNotNull().first()
        server = ActiveServer(settings, credentials, OkHttpClient(), scope)
        val mapping = MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "m.preferences_pb") })
        downloads = DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, StorageVolumes(context),
            QueueKicker(WorkManager.getInstance(context)))
    }

    @After fun tearDown() {
        db.close()
        romm.close()
        scope.cancel()
        ShadowStatFs.reset()
    }

    /** Queues the game as the library does (RomM is asked for its files once), then runs the queue. */
    private fun drain(): ListenableWorker.Result = runBlocking {
        assertThat(downloads.enqueue(10)).isInstanceOf(EnqueueResult.Queued::class.java)
        val worker = TestListenableWorkerBuilder<DrainWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = DrainWorker(
                appContext, workerParameters, db.downloadDao(), RoomJobStore(db.downloadDao()), downloads, server, StorageVolumes(context),
                settings, DownloadNotifications(appContext), OkHttpClient(),
            )
        }).build()
        withTimeout(20_000) { worker.doWork() }
    }

    private val installed get() = File(card, "ROMs/snes/Chrono Trigger.sfc")

    /**
     * Found by review: a download (a resumed one too) waited for RomM's answer about its plan, the
     * ROM's full details, before it started. It now asks for its file first.
     */
    @Test fun aDownloadStartsBeforeRommAnswersForItsPlan() {
        assertThat(drain()).isEqualTo(ListenableWorker.Result.success())
        assertThat(installed.readBytes()).isEqualTo(original.second)
        // rom (queueing), the file asked for, then the plan check's answer
        assertThat(events).containsExactly("rom", "file:400", "rom").inOrder()
    }

    /** RomM no longer has the game: found out while it downloads, which stops, and the job fails. */
    @Test fun aGameGoneFromRommStopsDownloadingAndFails() {
        throttleBytesPer100Ms = 20_000 // three seconds for the file
        goneLater = true
        drain()
        assertThat(installed.exists()).isFalse()
        val job = runBlocking { db.downloadDao().jobForRom(10)!! }
        assertThat(job.phase).isEqualTo(JobPhase.FAILED.name)
        assertThat(job.failureDetail).isEqualTo("This game is no longer on the RomM server")
    }

    // --- queueing a game: RomM is asked for its files first ---

    /** Found by review: X on a game while RomM couldn't be reached threw out of the library, which closed Loom. */
    @Test fun queueingWhileRommCantBeReachedSaysSo() = runBlocking<Unit> {
        romm.close()
        assertThat(downloads.enqueue(10)).isEqualTo(EnqueueResult.Failed("Can't reach the RomM server"))
        assertThat(db.downloadDao().jobForRom(10)).isNull()
    }

    @Test fun queueingAGameRommNoLongerHasSaysSo() = runBlocking<Unit> {
        goneLater = true
        romCalls.set(1) // RomM's next answer is the "later" one: gone
        fileAsked.countDown()
        assertThat(downloads.enqueue(10)).isEqualTo(EnqueueResult.Failed("RomM no longer has this game. Sync the library to update it."))
    }

    /** RomM's file changed since the game was queued: the download stops, is planned afresh and gets the new file. */
    @Test fun filesThatChangedOnRommAreDownloadedAfresh() {
        throttleBytesPer100Ms = 20_000
        val changed = 401 to ByteArray(300_000) { (it * 13).toByte() }
        served[changed.first] = changed.second
        later = changed
        drain()
        assertThat(installed.readBytes()).isEqualTo(changed.second)
        assertThat(events).contains("file:401")
        assertThat(runBlocking { db.downloadDao().jobForRom(10) }).isNull()
        assertThat(File(card, ".loom-staging/jobs").listFiles().orEmpty()).isEmpty() // the old plan's bytes are gone
    }
}
