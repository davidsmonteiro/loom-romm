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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import ro.davidmontei.loom.storage.engine.PartState
import ro.davidmontei.loom.storage.plan.NameSanitizer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The download queue's worker, end to end against a fake RomM. Before a job downloads anything,
 * RomM is asked for the game again and the job is planned afresh if its files or folder changed:
 * found by review, checking that only alongside the download let a download finish or fail on a plan
 * RomM no longer had (the old folder, a file RomM had moved or replaced), retry after retry.
 */
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

    // --- the worker, end to end ---

    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val romm = MockWebServer()
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var mapping: MappingRepository
    private lateinit var server: ActiveServer
    private lateinit var downloads: DownloadRepository
    private lateinit var card: File

    /** What RomM is asked for, in order: "rom" (the game's details), "file:<id>" (a file's bytes). */
    private val events = CopyOnWriteArrayList<String>()
    private val romCalls = AtomicInteger()
    /** The files RomM serves, by id; any other is a 404. */
    private val served = mutableMapOf<Int, ByteArray>()
    /** What RomM lists for the game: its file's id and bytes; from the second time it's asked, [later] when set. */
    private val original = 400 to ByteArray(600_000) { (it * 7).toByte() }
    @Volatile private var later: Pair<Int, ByteArray>? = null
    /** RomM no longer has the game, from the second time it's asked. */
    @Volatile private var goneLater = false
    /** RomM answers the game's details with 503 (its files still come). */
    @Volatile private var detailsDown = false

    private fun rom(fileId: Int, data: ByteArray) = romJson(10, fileId, data)

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            Regex("""/api/roms/(\d+)/files/content/""").find(path)?.let { m ->
                val id = m.groupValues[1].toInt()
                events += "file:$id"
                val data = served[id] ?: return MockResponse.Builder().code(404).body("""{"detail":"File not found"}""").build()
                return MockResponse.Builder().code(200).setHeader("Last-Modified", "Wed, 01 Oct 2026 10:00:00 GMT")
                    .body(Buffer().write(data)).build()
            }
            if (path == "/api/roms/10") {
                events += "rom"
                if (detailsDown) return MockResponse.Builder().code(503).build()
                val later = romCalls.incrementAndGet() > 1
                if (later && goneLater) return MockResponse.Builder().code(404).body("""{"detail":"Rom not found"}""").build()
                val (fileId, data) = (if (later) this@DrainWorkerTest.later else null) ?: original
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
        mapping = MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "m.preferences_pb") })
        downloads = DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, StorageVolumes(context),
            QueueKicker(WorkManager.getInstance(context)))
    }

    @After fun tearDown() {
        db.close()
        romm.close()
        scope.cancel()
        ShadowStatFs.reset()
    }

    /** Queues the game as the library does: RomM is asked for its files once. */
    private fun queue(): Long = runBlocking {
        (downloads.enqueue(10) as EnqueueResult.Queued).jobId
    }

    /** Runs the queue until it's empty or every job is parked. */
    private fun drain(): ListenableWorker.Result = runBlocking {
        val worker = TestListenableWorkerBuilder<DrainWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) = DrainWorker(
                appContext, workerParameters, db.downloadDao(), RoomJobStore(db.downloadDao()), downloads, server, StorageVolumes(context),
                settings, DownloadNotifications(appContext), OkHttpClient(),
            )
        }).build()
        withTimeout(20_000) { worker.doWork() }
    }

    private fun installedAt(folder: String) = File(card, "ROMs/$folder/Chrono Trigger.sfc")
    private fun job() = runBlocking { db.downloadDao().jobForRom(10) }

    @Test fun rommIsAskedForTheGameAgainBeforeItsFileIsDownloaded() {
        queue()
        assertThat(drain()).isEqualTo(ListenableWorker.Result.success())
        assertThat(installedAt("snes").readBytes()).isEqualTo(original.second)
        assertThat(events).containsExactly("rom", "rom", "file:400").inOrder() // queueing, the check, the download
    }

    /** Found by review: a platform's folder changed after its game was queued, and the game was installed in the old one. */
    @Test fun aFolderChangedAfterQueueingIsWhereTheGameGoes() {
        queue()
        runBlocking { mapping.chooseFolder("snes", "sfc") }
        drain()
        assertThat(installedAt("sfc").readBytes()).isEqualTo(original.second)
        assertThat(installedAt("snes").exists()).isFalse()
    }

    /** Found by review: RomM gave the game's file a new id after it was queued, and the download failed on the old one's 404. */
    @Test fun aFileRommMovedAfterQueueingIsDownloadedFromItsNewPlace() {
        queue()
        served.remove(original.first) // the old URL is a 404 now
        later = 401 to original.second
        served[401] = original.second
        drain()
        assertThat(installedAt("snes").readBytes()).isEqualTo(original.second)
        assertThat(events).containsExactly("rom", "rom", "file:401").inOrder()
    }

    /** Found by review: RomM's file was replaced after it was queued; Loom downloaded it twice, then failed on the old checksum. */
    @Test fun aFileReplacedOnRommAfterQueueingIsDownloadedOnce() {
        queue()
        val replaced = ByteArray(500_000) { (it * 13).toByte() }
        served[original.first] = replaced
        later = original.first to replaced
        drain()
        assertThat(installedAt("snes").readBytes()).isEqualTo(replaced)
        assertThat(events.count { it == "file:400" }).isEqualTo(1)
    }

    /**
     * Found by review: a download that failed because RomM had moved its file failed the same way on
     * every retry. Retry plans it afresh from what RomM has now.
     */
    @Test fun retryGetsWhatRommHasNow() {
        val id = queue()
        served.remove(original.first) // gone from RomM's disk, though RomM still lists it
        drain()
        assertThat(job()!!.phase).isEqualTo(JobPhase.FAILED.name)

        later = 401 to original.second // RomM rescanned and found it under a new id
        served[401] = original.second
        runBlocking { downloads.retry(id) }
        drain()
        assertThat(installedAt("snes").readBytes()).isEqualTo(original.second)
        assertThat(job()).isNull()
    }

    /** A game deleted from RomM after it was queued fails saying so, before anything is downloaded. */
    @Test fun aGameGoneFromRommFailsBeforeDownloading() {
        queue()
        goneLater = true
        drain()
        assertThat(job()!!.phase).isEqualTo(JobPhase.FAILED.name)
        assertThat(job()!!.failureDetail).isEqualTo("This game is no longer on the RomM server")
        assertThat(events.filter { it.startsWith("file:") }).isEmpty()
        assertThat(installedAt("snes").exists()).isFalse()
    }

    /**
     * A game Loom had downloaded and checked, and was moving into place when it stopped, is finished
     * whatever RomM says now: its files were checked against RomM's, and it may already be in the
     * ROMs folder, which must never be left without its install.
     */
    @Test fun aGameBeingMovedIntoPlaceIsFinishedEvenIfRommNoLongerHasIt() {
        val id = queue()
        runBlocking {
            // Where the previous run stopped: the file downloaded and checked, its place in the ROMs folder fixed.
            val dao = db.downloadDao()
            val job = dao.job(id)!!
            File(job.stagingRoot, "jobs/$id-${job.generation}/dl/400.part").apply { parentFile!!.mkdirs() }.writeBytes(original.second)
            val part = dao.parts(id).single()
            dao.upsertPart(part.copy(durableBytes = original.second.size.toLong(), digest = sha1(original.second), state = PartState.VERIFIED.name))
            dao.setFinalEntry(id, "snes/Chrono Trigger.sfc", NameSanitizer.key("snes/Chrono Trigger.sfc"), "FILE")
        }
        goneLater = true
        drain()
        assertThat(installedAt("snes").readBytes()).isEqualTo(original.second)
        assertThat(job()).isNull()
    }

    // --- a game sent from RomM, waiting in the queue for its platform's folder ---

    /** Waiting for its folder, a game is neither downloaded nor paused by "Pause all": it stays waiting. */
    @Test fun aGameWaitingForItsFolderIsLeftAlone() {
        val id = runBlocking { (downloads.enqueue(10, waitForFolder = true) as EnqueueResult.Queued).jobId }
        runBlocking { downloads.pauseAll() }
        assertThat(drain()).isEqualTo(ListenableWorker.Result.success())
        assertThat(job()!!.id).isEqualTo(id)
        assertThat(job()!!.phase).isEqualTo(JobPhase.WAITING.name)
        assertThat(events).containsExactly("rom") // queueing only
    }

    /**
     * Released into the folder the owner chose, a game goes there even when RomM can't be asked about
     * it again before downloading (the check then keeps the job's own plan).
     */
    @Test fun aReleasedGameGoesInTheChosenFolderEvenWithRommOutOfReach() {
        runBlocking {
            downloads.enqueue(10, waitForFolder = true)
            mapping.chooseFolder("snes", "sfc")
            downloads.releaseFolderWaits(listOf(1), "sfc")
        }
        detailsDown = true
        drain()
        assertThat(installedAt("sfc").readBytes()).isEqualTo(original.second)
        assertThat(installedAt("snes").exists()).isFalse()
        assertThat(events).containsExactly("rom", "rom", "file:400").inOrder()
    }

    // --- queueing a game: RomM is asked for its files first ---

    /** Found by review: X on a game while RomM couldn't be reached threw out of the library, which closed Loom. */
    @Test fun queueingWhileRommCantBeReachedSaysSo() = runBlocking<Unit> {
        romm.close()
        assertThat(downloads.enqueue(10)).isEqualTo(EnqueueResult.Failed("Can't reach the RomM server", transient = true))
        assertThat(db.downloadDao().jobForRom(10)).isNull()
    }

    @Test fun queueingAGameRommNoLongerHasSaysSo() = runBlocking<Unit> {
        goneLater = true
        romCalls.set(1) // RomM's next answer is the "later" one: gone
        assertThat(downloads.enqueue(10)).isEqualTo(EnqueueResult.Failed("RomM no longer has this game. Sync the library to update it."))
    }
}
