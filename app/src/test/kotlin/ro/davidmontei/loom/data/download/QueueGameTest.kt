package ro.davidmontei.loom.data.download

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.StorageVolumeBuilder
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncWorker
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.mapping.SystemFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Queueing a game, as the library does and as a game sent from RomM will: where its platform's games
 * go is settled first (found on the card, or asked), and only a passing problem reaching RomM is
 * one that asking again later can get past.
 */
@RunWith(AndroidJUnit4::class)
class QueueGameTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val romm = MockWebServer()
    private lateinit var db: LoomDatabase
    private lateinit var mapping: MappingRepository
    private lateinit var downloads: DownloadRepository
    private lateinit var queue: QueueGame
    private lateinit var roms: File

    /** How RomM answers `GET api/roms/{id}`; a game it has by default. */
    @Volatile private var answer: (Int) -> MockResponse = { id ->
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
            .body(romJson(id, 400, ByteArray(1_000) { it.toByte() }).toString()).build()
    }

    @Before fun setUp() = runBlocking {
        romm.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                Regex("""/api/roms/(\d+)$""").find(request.url.encodedPath)?.let { answer(it.groupValues[1].toInt()) }
                    ?: MockResponse.Builder().code(404).build()
        }
        romm.start()
        val card = tmp.newFolder("6365-3230")
        roms = File(card, "ROMs").apply { mkdirs() }
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(
            StorageVolumeBuilder("sd", card, "SD card", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
                .setFsUuid("6365-3230").setIsRemovable(true).build(),
        )
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, LoomDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        settings.setServer(ServerConfig(romm.url("/")), "5.4.0")
        settings.setRomsRoot(roms.path)
        val credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") },
            object : TokenCipher {
                override fun encrypt(plaintext: String) = plaintext
                override fun decrypt(blob: String) = blob
            }, scope)
        credentials.save(PairedCredentials(romm.url("/"), "rmm_x", "dev-thor", "david", null))
        credentials.current.filterNotNull().first()
        val server = ActiveServer(settings, credentials, OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(), scope)
        mapping = MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "m.preferences_pb") })
        downloads = DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, StorageVolumes(context), QueueKicker(workManager))
        queue = QueueGame(db.catalogDao(), downloads, FolderChooser(settings, mapping), CatalogSyncScheduler(workManager))
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(1, "snes", "snes", "SNES", "Super Nintendo", 1, "t")))
        db.catalogDao().upsertRoms(listOf(rom(10)))
    }

    @After fun tearDown() {
        db.close()
        romm.close()
        scope.cancel()
    }

    private fun rom(id: Int) = RomEntity(
        id, 1, "snes", "snes", "Chrono Trigger", "chrono trigger", "chrono trigger", "Chrono Trigger.sfc", "Chrono Trigger", "sfc",
        1_000, true, false, false, null, null, "USA", "t", false,
    )

    private fun folders(vararg names: String) = names.forEach { File(roms, it).mkdirs() }

    private suspend fun job() = db.downloadDao().jobForRom(10)

    private fun syncsStarted() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get().size

    /** SNES games already in sfc/, none in snes/: they go in sfc/, said the one time it's decided, and kept. */
    @Test fun aPlatformWithOneFolderHoldingGamesQueuesThereAndSaysSo() = runBlocking<Unit> {
        folders("snes", "sfc")
        File(roms, "sfc/Super Metroid.sfc").writeBytes(ByteArray(8))

        val outcome = queue.queue(10)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.Queued(job()!!.id, "Super Nintendo games go in sfc/"))
        assertThat(job()!!.plan().systemFolder).isEqualTo("sfc")
        assertThat(mapping.currentMapper().resolve("snes", "snes").folderChosen).isTrue()
    }

    /** Two empty SNES folders: which one Cocoon reads is asked, and nothing is queued or remembered meanwhile. */
    @Test fun anAmbiguousCardAsksWhenAskingIsWanted() = runBlocking<Unit> {
        folders("snes", "sfc")

        val outcome = queue.queue(10)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.AskFolder(
            FolderAsk(10, "Super Nintendo", "snes", "snes", listOf(SystemFolder("snes", 0), SystemFolder("sfc", 0)), "snes"),
        ))
        assertThat(job()).isNull()
        assertThat(mapping.currentMapper().resolve("snes", "snes").folderChosen).isFalse()
    }

    @Test fun theAnswerIsRememberedAndTheGameQueued() = runBlocking<Unit> {
        folders("snes", "sfc")
        queue.queue(10)

        val outcome = queue.folderChosen("snes", "sfc", thenQueue = 10)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.Queued(job()!!.id))
        assertThat(job()!!.plan().systemFolder).isEqualTo("sfc")
        val snes = mapping.currentMapper().resolve("snes", "snes")
        assertThat(snes.folder to snes.folderChosen).isEqualTo("sfc" to true)
    }

    /** On the card already: nothing is asked or fetched, even on a card that would need asking. */
    @Test fun anInstalledGameIsAlreadyInstalled() = runBlocking<Unit> {
        folders("snes", "sfc")
        db.catalogDao().upsertInstall(InstallEntity(
            10, "6365-3230", roms.path, "snes/Chrono Trigger.sfc", "snes/chrono trigger.sfc", 1, InstallKind.FILE, 1_000,
            VerifiedBy.SIZE, InstallSource.ADOPTED, null, 0,
        ))

        assertThat(queue.queue(10)).isEqualTo(QueueGame.Outcome.AlreadyInstalled)
        assertThat(romm.requestCount).isEqualTo(0)
    }

    @Test fun aQueuedGameIsAlreadyQueued() = runBlocking<Unit> {
        folders("snes")
        queue.queue(10)

        assertThat(queue.queue(10)).isEqualTo(QueueGame.Outcome.AlreadyQueued)
        assertThat(romm.requestCount).isEqualTo(1)
    }

    /** Queued again after it failed: the same job goes back in the queue, now carrying the request RomM sent it with. */
    @Test fun aFailedDownloadIsRetried() = runBlocking<Unit> {
        folders("snes")
        val id = (queue.queue(10) as QueueGame.Outcome.Queued).jobId
        db.downloadDao().setPhase(id, JobPhase.QUEUED.name, JobPhase.FAILED.name, null, "NETWORK", "Timed out", true, 1)

        assertThat(queue.queue(10, installRequestId = "req-1")).isEqualTo(QueueGame.Outcome.Queued(id))
        assertThat(job()!!.phase).isEqualTo(JobPhase.QUEUED.name)
        assertThat(job()!!.installRequestId).isEqualTo("req-1")
    }

    /** RomM out of reach can pass: asking again later may get through, so it is transient. */
    @Test fun rommOutOfReachIsATransientFailure() = runBlocking<Unit> {
        folders("snes")
        romm.close()

        assertThat(queue.queue(10)).isEqualTo(QueueGame.Outcome.Failed("Can't reach the RomM server", transient = true))
        assertThat(job()).isNull()
    }

    /** RomM failing on its side, or busy, can pass too; a game it no longer has won't come back by asking again. */
    @Test fun rommFailingForNowIsTransientButAGameItNoLongerHasIsNot() = runBlocking<Unit> {
        folders("snes")
        answer = { MockResponse.Builder().code(503).build() }
        assertThat((queue.queue(10) as QueueGame.Outcome.Failed).transient).isTrue()
        answer = { MockResponse.Builder().code(409).body("""{"detail":"Busy"}""").build() }
        assertThat((queue.queue(10) as QueueGame.Outcome.Failed).transient).isTrue()

        answer = { MockResponse.Builder().code(404).body("""{"detail":"Rom not found"}""").build() }
        assertThat(queue.queue(10)).isEqualTo(QueueGame.Outcome.Failed("RomM no longer has this game. Sync the library to update it."))
        assertThat(job()).isNull()
    }

    /** A game added on RomM since the last library sync: RomM says its platform, it's queued, and the library syncs. */
    @Test fun aGameNotInTheLibraryYetIsPlacedByWhatRommSaysAndTheLibrarySyncs() = runBlocking<Unit> {
        folders("sfc")

        val outcome = queue.queue(77)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.Queued(db.downloadDao().jobForRom(77)!!.id, "snes games go in sfc/"))
        assertThat(db.downloadDao().jobForRom(77)!!.plan().systemFolder).isEqualTo("sfc")
        assertThat(romm.requestCount).isEqualTo(1) // asked once, for its platform and its files
        assertThat(syncsStarted()).isEqualTo(1)
    }
}
