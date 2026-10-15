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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import ro.davidmontei.loom.storage.engine.JobStatus
import ro.davidmontei.loom.storage.engine.WaitReason
import ro.davidmontei.loom.storage.mapping.SystemFolder
import java.io.File
import java.util.concurrent.CountDownLatch
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

    /** X in the library: someone is there to answer. */
    private suspend fun fromLibrary(romId: Int) = queue.queue(romId, QueueGame.WhenUnsure.ASK)

    /** Sent from RomM's web UI, by install request [request]: nobody is there to ask. */
    private suspend fun fromRomm(romId: Int, request: String = "req-$romId") = queue.queue(romId, QueueGame.WhenUnsure.WAIT_IN_QUEUE, request)

    private fun syncsStarted() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get().size

    /** SNES games already in sfc/, none in snes/: they go in sfc/, said the one time it's decided, and kept. */
    @Test fun aPlatformWithOneFolderHoldingGamesQueuesThereAndSaysSo() = runBlocking<Unit> {
        folders("snes", "sfc")
        File(roms, "sfc/Super Metroid.sfc").writeBytes(ByteArray(8))

        val outcome = fromLibrary(10)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.Queued(job()!!.id, "Super Nintendo games go in sfc/"))
        assertThat(job()!!.plan().systemFolder).isEqualTo("sfc")
        assertThat(mapping.currentMapper().resolve("snes", "snes").folderChosen).isTrue()
    }

    /** Two empty SNES folders: which one Cocoon reads is asked, and nothing is queued or remembered meanwhile. */
    @Test fun anAmbiguousCardAsksWhenAskingIsWanted() = runBlocking<Unit> {
        folders("snes", "sfc")

        val outcome = fromLibrary(10)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.AskFolder(
            FolderAsk(10, 1, "Super Nintendo", "snes", "snes", listOf(SystemFolder("snes", 0), SystemFolder("sfc", 0)), "snes"),
        ))
        assertThat(job()).isNull()
        assertThat(mapping.currentMapper().resolve("snes", "snes").folderChosen).isFalse()
    }

    @Test fun theAnswerIsRememberedAndTheGameQueued() = runBlocking<Unit> {
        folders("snes", "sfc")
        fromLibrary(10)

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

        assertThat(fromLibrary(10)).isEqualTo(QueueGame.Outcome.AlreadyInstalled)
        assertThat(romm.requestCount).isEqualTo(0)
    }

    @Test fun aQueuedGameIsAlreadyQueued() = runBlocking<Unit> {
        folders("snes")
        fromLibrary(10)

        assertThat(fromLibrary(10)).isEqualTo(QueueGame.Outcome.AlreadyQueued)
        assertThat(romm.requestCount).isEqualTo(1)
    }

    /** Queued again after it failed: the same job goes back in the queue, now carrying the request RomM sent it with. */
    @Test fun aFailedDownloadIsRetried() = runBlocking<Unit> {
        folders("snes")
        val id = (fromLibrary(10) as QueueGame.Outcome.Queued).jobId
        db.downloadDao().setPhase(id, JobPhase.QUEUED.name, JobPhase.FAILED.name, null, "NETWORK", "Timed out", true, 1)

        assertThat(fromRomm(10, "req-1")).isEqualTo(QueueGame.Outcome.Queued(id))
        assertThat(job()!!.phase).isEqualTo(JobPhase.QUEUED.name)
        assertThat(job()!!.installRequestId).isEqualTo("req-1")
    }

    /** RomM out of reach can pass: asking again later may get through, so it is transient. */
    @Test fun rommOutOfReachIsATransientFailure() = runBlocking<Unit> {
        folders("snes")
        romm.close()

        assertThat(fromLibrary(10)).isEqualTo(QueueGame.Outcome.Failed("Can't reach the RomM server", transient = true))
        assertThat(job()).isNull()
    }

    /** RomM failing on its side, or busy, can pass too; a game it no longer has won't come back by asking again. */
    @Test fun rommFailingForNowIsTransientButAGameItNoLongerHasIsNot() = runBlocking<Unit> {
        folders("snes")
        answer = { MockResponse.Builder().code(503).build() }
        assertThat((fromLibrary(10) as QueueGame.Outcome.Failed).transient).isTrue()
        answer = { MockResponse.Builder().code(409).body("""{"detail":"Busy"}""").build() }
        assertThat((fromLibrary(10) as QueueGame.Outcome.Failed).transient).isTrue()

        answer = { MockResponse.Builder().code(404).body("""{"detail":"Rom not found"}""").build() }
        assertThat(fromLibrary(10)).isEqualTo(QueueGame.Outcome.Failed("RomM no longer has this game. Sync the library to update it."))
        assertThat(job()).isNull()
    }

    /** A game added on RomM since the last library sync: RomM says its platform, it's queued, and the library syncs. */
    @Test fun aGameNotInTheLibraryYetIsPlacedByWhatRommSaysAndTheLibrarySyncs() = runBlocking<Unit> {
        folders("sfc")

        val outcome = fromLibrary(77)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.Queued(db.downloadDao().jobForRom(77)!!.id, "Super Nintendo games go in sfc/"))
        assertThat(db.downloadDao().jobForRom(77)!!.plan().systemFolder).isEqualTo("sfc")
        assertThat(romm.requestCount).isEqualTo(1) // asked once, for its platform and its files
        assertThat(syncsStarted()).isEqualTo(1)
    }

    /**
     * Sent from RomM before the library has the game, only the game being new: the question (and the
     * notification that says where to answer it) names the platform as the library does.
     */
    @Test fun aSentGameNotInTheLibraryYetIsAskedAboutByItsPlatformsName() = runBlocking<Unit> {
        folders("snes", "sfc")

        val outcome = fromRomm(77) as QueueGame.Outcome.WaitingForFolder

        assertThat(outcome.ask.platformName).isEqualTo("Super Nintendo")
    }

    /** A platform the library doesn't have yet either: named as RomM names it, not by its slug. */
    @Test fun aSentGameOnAPlatformNewToTheLibraryIsAskedAboutByRommsName() = runBlocking<Unit> {
        folders("snes", "sfc")
        answer = { id ->
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body(romJson(id, 400, ByteArray(1_000) { it.toByte() }, platformId = 2, platformName = "Super Famicom").toString()).build()
        }

        val outcome = fromRomm(77) as QueueGame.Outcome.WaitingForFolder

        assertThat(outcome.ask.platformId).isEqualTo(2)
        assertThat(outcome.ask.platformName).isEqualTo("Super Famicom")
    }

    // --- a sent game waits in the queue for its folder ---

    /** Nobody to ask: the game waits in the queue, from RomM's request, planned for the default folder until it's chosen. */
    @Test fun anAmbiguousSentGameWaitsInTheQueue() = runBlocking<Unit> {
        folders("snes", "sfc")

        val outcome = fromRomm(10)

        val job = job()!!
        assertThat(outcome).isEqualTo(QueueGame.Outcome.WaitingForFolder(job.id,
            FolderAsk(10, 1, "Super Nintendo", "snes", "snes", listOf(SystemFolder("snes", 0), SystemFolder("sfc", 0)), "snes")))
        assertThat(job.status()).isEqualTo(JobStatus(JobPhase.WAITING, waitReason = WaitReason.FOLDER))
        assertThat(job.installRequestId).isEqualTo("req-10")
        assertThat(job.plan().systemFolder).isEqualTo("snes")
        assertThat(mapping.currentMapper().resolve("snes", "snes").folderChosen).isFalse()
        // "Pause all" from the notification leaves it waiting: resuming would put it in the default folder unasked.
        downloads.pauseAll()
        assertThat(job()!!.status().waitReason).isEqualTo(WaitReason.FOLDER)
    }

    /** One answer, asked about one of them, settles the platform: every game of it waiting goes in the chosen folder. */
    @Test fun answeringReleasesEveryWaitingGameOfThatPlatformIntoTheChosenFolder() = runBlocking<Unit> {
        folders("snes", "sfc")
        db.catalogDao().upsertRoms(listOf(rom(11)))
        val first = (fromRomm(10) as QueueGame.Outcome.WaitingForFolder).jobId
        val second = (fromRomm(11) as QueueGame.Outcome.WaitingForFolder).jobId

        val ask = (queue.askFolder(first) as QueueGame.Outcome.AskFolder).ask
        assertThat(queue.folderChosen(ask.fsSlug, "sfc", thenQueue = null, platformId = ask.platformId)).isNull()

        for (id in listOf(first, second)) {
            val job = db.downloadDao().job(id)!!
            assertThat(job.phase).isEqualTo(JobPhase.QUEUED.name)
            assertThat(job.plan().systemFolder).isEqualTo("sfc")
        }
        assertThat(mapping.currentMapper().resolve("snes", "snes").folder).isEqualTo("sfc")
    }

    /**
     * The folder chosen in the queue just as another game of the platform was sent, while RomM was asked
     * for its files: too early for the choice to release it, so it's released once queued, rather than
     * waiting (and asked about again) for a folder already chosen.
     */
    @Test fun aGameSentAsItsFolderIsChosenDoesntWaitForIt() = runBlocking<Unit> {
        folders("snes", "sfc")
        db.catalogDao().upsertRoms(listOf(rom(11)))
        val first = (fromRomm(10) as QueueGame.Outcome.WaitingForFolder).jobId
        val fetching = CountDownLatch(1)
        val chosen = CountDownLatch(1)
        val usual = answer
        answer = { id ->
            if (id == 11) {
                fetching.countDown()
                chosen.await(5, TimeUnit.SECONDS)
            }
            usual(id)
        }
        val second = async(Dispatchers.IO) { fromRomm(11) }
        withContext(Dispatchers.IO) { assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue() }

        val ask = (queue.askFolder(first) as QueueGame.Outcome.AskFolder).ask
        queue.folderChosen(ask.fsSlug, "sfc", thenQueue = null, platformId = ask.platformId)
        chosen.countDown()

        val outcome = second.await()
        val id = db.downloadDao().jobForRom(11)!!.id
        assertThat(outcome).isEqualTo(QueueGame.Outcome.Queued(id))
        assertThat(db.downloadDao().job(id)!!.let { it.phase to it.plan().systemFolder }).isEqualTo(JobPhase.QUEUED.name to "sfc")
    }

    /** The library's own question, answered, releases the platform's waiting games too. */
    @Test fun theLibrarysAnswerReleasesWaitingGamesAsWell() = runBlocking<Unit> {
        folders("snes", "sfc")
        db.catalogDao().upsertRoms(listOf(rom(11)))
        val sent = (fromRomm(10) as QueueGame.Outcome.WaitingForFolder).jobId
        val ask = (fromLibrary(11) as QueueGame.Outcome.AskFolder).ask

        val outcome = queue.folderChosen(ask.fsSlug, "sfc", thenQueue = 11, platformId = ask.platformId)

        assertThat(outcome).isEqualTo(QueueGame.Outcome.Queued(db.downloadDao().jobForRom(11)!!.id))
        assertThat(db.downloadDao().job(sent)!!.let { it.phase to it.plan().systemFolder }).isEqualTo(JobPhase.QUEUED.name to "sfc")
    }

    /** A folder chosen meanwhile in Settings › Platform folders: the waiting game goes there, and asking it does that too. */
    @Test fun aFolderChosenInSettingsReleasesWaitingGames() = runBlocking<Unit> {
        folders("snes", "sfc")
        db.catalogDao().upsertRoms(listOf(rom(11)))
        val first = (fromRomm(10) as QueueGame.Outcome.WaitingForFolder).jobId
        mapping.chooseFolder("snes", "sfc")

        queue.releaseDecided()

        assertThat(db.downloadDao().job(first)!!.let { it.phase to it.plan().systemFolder }).isEqualTo(JobPhase.QUEUED.name to "sfc")

        // Sent once the choice was made: it goes in at once.
        assertThat(fromRomm(11)).isEqualTo(QueueGame.Outcome.Queued(db.downloadDao().jobForRom(11)!!.id))
    }

    /** Asked about after its folder was settled some other way: there's nothing to ask, and it goes ahead. */
    @Test fun askingAboutAGameWhoseFolderWasSettledLetsItGo() = runBlocking<Unit> {
        folders("snes", "sfc")
        val id = (fromRomm(10) as QueueGame.Outcome.WaitingForFolder).jobId
        File(roms, "sfc/Super Metroid.sfc").writeBytes(ByteArray(8)) // the card now says where SNES games go

        assertThat(queue.askFolder(id)).isEqualTo(QueueGame.Outcome.Queued(id, "Super Nintendo games go in sfc/"))
        assertThat(job()!!.let { it.phase to it.plan().systemFolder }).isEqualTo(JobPhase.QUEUED.name to "sfc")
        assertThat(queue.askFolder(id)).isNull() // no longer waiting
    }
}
