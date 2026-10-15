package ro.davidmontei.loom.data.sent

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
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
import ro.davidmontei.loom.data.download.DownloadNotifications
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueGame
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.download.romJson
import ro.davidmontei.loom.data.download.status
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.ServerInfo
import ro.davidmontei.loom.net.ServerReachability
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.romm.socket.DeviceEvent
import ro.davidmontei.loom.romm.socket.DeviceSocket
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.JobStatus
import ro.davidmontei.loom.storage.engine.WaitReason
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Games sent from RomM's web UI, against a RomM that keeps install requests as 5.4.0 does: each game
 * claimed is queued like one picked in the library and reported, and RomM refusing the device or the
 * feature stops the claims rather than looping on them.
 */
@RunWith(AndroidJUnit4::class)
class SendToDeviceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fake = FakeRommDevices()
    private val romm = MockWebServer()
    private val reachability = ServerReachability()

    /** What RomM's device socket says while Loom is in front; a refusal ends the connection, as RomM's do. */
    private val socket = Channel<DeviceEvent>(Channel.UNLIMITED)
    private val connections = AtomicInteger()

    /** The send-to-device file's next write fails, as on a full disk. */
    private val failNextWrite = AtomicBoolean(false)
    private val app = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var credentials: CredentialStore
    private lateinit var store: SendToDeviceStore
    private lateinit var queue: QueueGame
    private lateinit var sendToDevice: SendToDevice
    private lateinit var roms: File

    @Before fun setUp() = runBlocking {
        romm.dispatcher = fake
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
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        settings.setServer(ServerConfig(romm.url("/")), "5.4.0")
        settings.setRomsRoot(roms.path)
        settings.setOnboardingComplete(true)
        credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") },
            object : TokenCipher {
                override fun encrypt(plaintext: String) = plaintext
                override fun decrypt(blob: String) = blob
            }, scope)
        credentials.save(PairedCredentials(romm.url("/"), "rmm_x", "dev-thor", "david", null))
        credentials.current.filterNotNull().first()
        val sentFile = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "d.preferences_pb") }
        store = SendToDeviceStore(object : DataStore<Preferences> {
            override val data = sentFile.data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                if (failNextWrite.getAndSet(false)) throw IOException("No space left on device")
                return sentFile.updateData(transform)
            }
        })
        val server = ActiveServer(settings, credentials, OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(), scope)
        val mapping = MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "m.preferences_pb") })
        val downloads = DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, StorageVolumes(context), QueueKicker(workManager))
        queue = QueueGame(db.catalogDao(), downloads, FolderChooser(settings, mapping), CatalogSyncScheduler(workManager))
        sendToDevice = SendToDevice(
            settings, credentials, store, server, ServerInfo(settings, store), reachability, queue, downloads,
            DownloadNotifications(context), ClaimScheduler(workManager), LiveNudges({ _, _ -> connection() }), scope,
            restartAfterMs = 50,
        )
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(1, "snes", "snes", "SNES", "Super Nintendo", 1, "t")))
        db.catalogDao().upsertRoms(listOf(rom(10), rom(11)))
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

    private suspend fun jobFor(romId: Int) = db.downloadDao().jobForRom(romId)

    /** Runs the coordinator, as Loom does while it runs: started by the time this returns. */
    private fun running() = scope.launch(start = CoroutineStart.UNDISPATCHED) { sendToDevice.run() }

    private suspend fun until(what: suspend () -> Boolean) = withTimeout(5_000) { while (!what()) delay(10) }

    private fun connection(): Flow<DeviceEvent> = flow {
        connections.incrementAndGet()
        for (event in socket) {
            emit(event)
            if (event is DeviceEvent.Refused) break
        }
    }

    /** Loom comes to the front, with the coordinator running as in Loom. */
    private suspend fun inFront() {
        running()
        sendToDevice.follow(app.lifecycle)
        app.lifecycle.currentState = Lifecycle.State.STARTED
        until { connections.get() == 1 }
    }

    private fun periodicClaim() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ClaimWorker.UNIQUE_NAME).get()

    private fun notifications(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    // --- declaring the device ---

    /** RomM lists the Thor under "Install on device" once told; told at start, and again only when the switch changes. */
    @Test fun declaresRemoteInstallOnceAndAgainWhenTheSwitchChanges() = runBlocking<Unit> {
        running()
        until { fake.declared.size == 1 }
        sendToDevice.drain() // claims without telling RomM again
        assertThat(fake.declared).containsExactly("dev-thor" to """{"capabilities":{"remote_install":true}}""")
        assertThat(fake.claims.get()).isEqualTo(1)

        store.setReceive(false)
        until { fake.declared.size == 2 }
        assertThat(fake.declared[1]).isEqualTo("dev-thor" to """{"capabilities":{"remote_install":false}}""")
        until { sendToDevice.state.value == SendToDeviceState.Off }
        sendToDevice.drain()
        assertThat(fake.claims.get()).isEqualTo(1) // switched off: nothing more is claimed
    }

    // --- what is reported ---

    /** In the queue is what RomM calls "Added to downloads": reported done at once, the job knowing which request sent it. */
    @Test fun aSentGameIsQueuedAndReportedDone() = runBlocking<Unit> {
        folders("snes")
        fake.send("req-1", 10)

        sendToDevice.drain()

        val job = jobFor(10)!!
        assertThat(job.installRequestId).isEqualTo("req-1")
        assertThat(job.phase).isEqualTo(JobPhase.QUEUED.name)
        assertThat(fake.reports).containsExactly("req-1" to """{"status":"done"}""")
    }

    @Test fun aGameAlreadyOnTheCardIsReportedAlreadyInstalled() = runBlocking<Unit> {
        folders("snes")
        db.catalogDao().upsertInstall(InstallEntity(
            10, "6365-3230", roms.path, "snes/Chrono Trigger.sfc", "snes/chrono trigger.sfc", 1, InstallKind.FILE, 1_000,
            VerifiedBy.SIZE, InstallSource.ADOPTED, null, 0,
        ))
        fake.send("req-1", 10)

        sendToDevice.drain()

        assertThat(fake.reports).containsExactly("req-1" to """{"status":"already_installed"}""")
        assertThat(jobFor(10)).isNull()
    }

    /** Already picked in the library: RomM's request is done by the job already in the queue. */
    @Test fun aGameAlreadyQueuedIsReportedDone() = runBlocking<Unit> {
        folders("snes")
        val id = (queue.queue(10, QueueGame.WhenUnsure.ASK) as QueueGame.Outcome.Queued).jobId
        fake.send("req-1", 10)

        sendToDevice.drain()

        assertThat(fake.reports).containsExactly("req-1" to """{"status":"done"}""")
        assertThat(jobFor(10)!!.id).isEqualTo(id)
    }

    /** RomM shows the reason beside "Failed", and takes at most 500 characters of it. */
    @Test fun aGameLoomCantQueueIsReportedFailedWithAReasonOf500CharactersAtMost() = runBlocking<Unit> {
        folders("snes")
        fake.romAnswer = { MockResponse.Builder().code(400).body("""{"detail":"${"x".repeat(600)}"}""").build() }
        fake.send("req-1", 10)

        sendToDevice.drain()

        val (id, body) = fake.reports.single()
        val report = Json.parseToJsonElement(body).jsonObject
        assertThat(id).isEqualTo("req-1")
        assertThat(report["status"]!!.jsonPrimitive.content).isEqualTo("failed")
        assertThat(report["reason"]!!.jsonPrimitive.content).isEqualTo("HTTP 400: " + "x".repeat(490))
        assertThat(jobFor(10)).isNull()
    }

    /**
     * Two empty SNES folders and nobody to ask: the game waits in the queue for its folder, which is in the
     * queue all the same, so RomM is told done; a notification says where to choose.
     */
    @Test fun anAmbiguousFolderWaitsInTheQueueStillReportsDoneAndNotifies() = runBlocking<Unit> {
        folders("snes", "sfc")
        fake.send("req-1", 10)

        sendToDevice.drain()

        assertThat(jobFor(10)!!.status()).isEqualTo(JobStatus(JobPhase.WAITING, waitReason = WaitReason.FOLDER))
        assertThat(fake.reports).containsExactly("req-1" to """{"status":"done"}""")
        val notice = notifications().single()
        assertThat(notice.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("Chrono Trigger is waiting for its folder")
        assertThat(notice.extras.getString(Notification.EXTRA_TEXT))
            .isEqualTo("Sent from RomM. Open the queue and press A to choose where Super Nintendo games go.")
    }

    /** RomM out of reach while Loom asks it about the game: nothing is reported, and the next claim returns the request. */
    @Test fun rommOutOfReachLeavesTheRequestForTheNextClaim() = runBlocking<Unit> {
        folders("snes")
        fake.romAnswer = { MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build() }
        fake.send("req-1", 10)

        sendToDevice.drain()

        assertThat(fake.reports).isEmpty()
        assertThat(jobFor(10)).isNull()

        fake.romAnswer = null
        sendToDevice.drain()
        assertThat(fake.reports).containsExactly("req-1" to """{"status":"done"}""")
    }

    /**
     * The owner cancelled one request just as Loom claimed it: RomM says it's gone when Loom reports, so its
     * game leaves the queue again, and the next request is still reported. Without the socket, as in the
     * background, that 404 is the only sign of the cancel.
     */
    @Test fun aRequestCancelledAsLoomClaimedItLeavesTheQueue() = runBlocking<Unit> {
        folders("snes")
        fake.send("req-1", 10)
        fake.send("req-2", 11)
        fake.goneAfterClaim += "req-1"

        sendToDevice.drain()

        assertThat(fake.reportAttempts).containsExactly("req-1", "req-2").inOrder()
        assertThat(fake.reports).containsExactly("req-2" to """{"status":"done"}""")
        assertThat(jobFor(10)!!.phase).isEqualTo(JobPhase.CANCELLING.name)
        assertThat(jobFor(11)!!.phase).isEqualTo(JobPhase.QUEUED.name)
        sendToDevice.drain()
        assertThat(fake.reportAttempts).hasSize(2) // RomM has nothing more to hand back
    }

    /** Picked in the library before RomM sent it: a 404 on the report leaves the owner's own pick alone. */
    @Test fun aRequestGoneByTheReportLeavesAGameQueuedInTheLibrary() = runBlocking<Unit> {
        folders("snes")
        queue.queue(10, QueueGame.WhenUnsure.ASK)
        fake.send("req-1", 10)
        fake.goneAfterClaim += "req-1"

        sendToDevice.drain()

        assertThat(fake.reportAttempts).containsExactly("req-1")
        assertThat(jobFor(10)!!.phase).isEqualTo(JobPhase.QUEUED.name)
    }

    /** RomM refusing the report is RomM refusing the device: Loom asks to pair again, and stops there. */
    @Test fun aReportRommRefusesAsksToPairAgainAndStops() = runBlocking<Unit> {
        folders("snes")
        fake.send("req-1", 10)
        fake.send("req-2", 11)
        fake.reportAnswer = { MockResponse.Builder().code(403).body("""{"detail":"Only the device itself can do this"}""").build() }

        sendToDevice.drain()

        assertThat(fake.reportAttempts).containsExactly("req-1")
        assertThat(store.snapshot().needsPairing).isTrue()
    }

    /** RomM out of reach as Loom reports: it stops there, and the next claim reports both, queueing neither twice. */
    @Test fun aReportThatDoesntReachRommIsMadeAtTheNextClaim() = runBlocking<Unit> {
        folders("snes")
        fake.send("req-1", 10)
        fake.send("req-2", 11)
        fake.reportAnswer = { MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build() }

        sendToDevice.drain()

        assertThat(fake.reportAttempts.toSet()).containsExactly("req-1")
        assertThat(fake.reports).isEmpty()
        fake.reportAnswer = null
        sendToDevice.drain()
        assertThat(fake.reports).containsExactly("req-1" to """{"status":"done"}""", "req-2" to """{"status":"done"}""").inOrder()
        assertThat(db.downloadDao().jobsIn(JobPhase.QUEUED.name).map { it.romId }).containsExactly(10, 11)
    }

    /** A report RomM won't take (a 409 or 422) is let go: the game stays queued, and the next request is still reported. */
    @Test fun aReportRommDoesntTakeIsLetGo() = runBlocking<Unit> {
        folders("snes")
        fake.send("req-1", 10)
        fake.send("req-2", 11)
        fake.reportAnswer = { id -> if (id == "req-1") MockResponse.Builder().code(422).body("""{"detail":"Bad status"}""").build() else null }

        sendToDevice.drain()

        assertThat(fake.reportAttempts).containsExactly("req-1", "req-2").inOrder()
        assertThat(fake.reports).containsExactly("req-2" to """{"status":"done"}""")
        assertThat(jobFor(10)!!.phase).isEqualTo(JobPhase.QUEUED.name)
    }

    /** The owner cancelled on RomM before the game started: it leaves the queue. */
    @Test fun aCancelledRequestTakesItsGameOutOfTheQueue() = runBlocking<Unit> {
        folders("snes", "sfc")
        fake.send("req-1", 10)
        sendToDevice.drain()

        sendToDevice.cancelled("req-1")
        sendToDevice.cancelled("req-unknown") // never queued here

        assertThat(jobFor(10)!!.phase).isEqualTo(JobPhase.CANCELLING.name)
    }

    // --- when RomM won't take the claims ---

    /** A token that isn't the device's can't claim: pairing again by QR code is what fixes it, so Loom stops until then. */
    @Test fun aForbiddenClaimAsksToPairAgainAndStopsClaiming() = runBlocking<Unit> {
        running()
        until { periodicClaim().singleOrNull()?.state == WorkInfo.State.ENQUEUED }
        fake.claimAnswer = 403

        sendToDevice.drain()
        sendToDevice.drain()

        assertThat(fake.claims.get()).isEqualTo(1)
        assertThat(store.snapshot().needsPairing).isTrue()
        until { sendToDevice.state.value == SendToDeviceState.NeedsPairing }
        until { periodicClaim().single().state == WorkInfo.State.CANCELLED }
    }

    /** The Thor deleted on RomM's Devices page: telling RomM what it can do is refused, and Loom asks to pair again. */
    @Test fun aDeviceDeletedInRommAsksToPairAgain() = runBlocking<Unit> {
        fake.deviceDeleted = true

        sendToDevice.drain()

        assertThat(store.snapshot().needsPairing).isTrue()
        assertThat(fake.claims.get()).isEqualTo(0)
    }

    /** Deleted after Loom declared it this run: the claim's 404 isn't the feature being off, the heartbeat says, so it's the device. */
    @Test fun aClaimForADeviceRommNoLongerHasAsksToPairAgain() = runBlocking<Unit> {
        sendToDevice.drain()
        fake.deviceDeleted = true

        sendToDevice.drain()

        assertThat(store.snapshot().needsPairing).isTrue()
        assertThat(fake.heartbeats.get()).isEqualTo(1)
    }

    /** RomM's owner switched device installs off: the heartbeat says so, and Loom stops claiming until one says otherwise. */
    @Test fun installsTurnedOffOnTheServerAreRecordedNotLooped() = runBlocking<Unit> {
        fake.installsEnabled = false

        sendToDevice.drain()
        sendToDevice.drain()

        assertThat(fake.claims.get()).isEqualTo(1)
        assertThat(fake.heartbeats.get()).isEqualTo(1)
        assertThat(store.snapshot().installsEnabled).isFalse()
        assertThat(store.snapshot().needsPairing).isFalse() // pairing again wouldn't help
        until { sendToDevice.state.value == SendToDeviceState.OffOnServer }
    }

    /**
     * A RomM built from source says "development" for its version, which reads as the newest; one from
     * before 5.4 has no device installs, says nothing of them in its heartbeat, and has no claim. Loom
     * stops claiming, as for installs switched off there, rather than claiming every minute for nothing.
     */
    @Test fun aRommThatSaysNothingOfDeviceInstallsIsntClaimedFromAgain() = runBlocking<Unit> {
        settings.setServerVersion("development")
        fake.version = "development"
        fake.installsOffered = false
        running()
        until { periodicClaim().singleOrNull()?.state == WorkInfo.State.ENQUEUED }

        sendToDevice.drain()
        sendToDevice.drain()

        assertThat(fake.claims.get()).isEqualTo(1)
        assertThat(fake.heartbeats.get()).isEqualTo(1)
        assertThat(store.snapshot().needsPairing).isFalse()
        until { sendToDevice.state.value == SendToDeviceState.OffOnServer }
        until { periodicClaim().single().state == WorkInfo.State.CANCELLED }
    }

    /** Paired by pasting a token or a pair code: RomM wouldn't take the claim, so it isn't made. */
    @Test fun aTokenWithoutADeviceNeverClaims() = runBlocking<Unit> {
        credentials.save(PairedCredentials(romm.url("/"), "rmm_y", null, "david", null))
        running()

        sendToDevice.drain()

        assertThat(romm.requestCount).isEqualTo(0)
        until { sendToDevice.state.value == SendToDeviceState.NeedsPairing }
        assertThat(periodicClaim()).isEmpty()
    }

    @Test fun aRomm531ServerIsNeverAsked() = runBlocking<Unit> {
        settings.setServerVersion("5.3.1")
        running()

        sendToDevice.drain()

        assertThat(romm.requestCount).isEqualTo(0)
        until { sendToDevice.state.value == SendToDeviceState.OldServer("5.3.1") }
        assertThat(periodicClaim()).isEmpty()
    }

    // --- when Loom claims ---

    /** Paired again after RomM refused the device: what was sent meanwhile arrives at once. */
    @Test fun pairingAgainClaimsAtOnce() = runBlocking<Unit> {
        folders("snes")
        store.setNeedsPairing(true)
        fake.send("req-1", 10)
        running()
        until { sendToDevice.state.value == SendToDeviceState.NeedsPairing }

        credentials.save(PairedCredentials(romm.url("/"), "rmm_y", "dev-thor", "david", null))

        until { fake.reports.isNotEmpty() }
        assertThat(store.snapshot().needsPairing).isFalse()
        assertThat(fake.reports).containsExactly("req-1" to """{"status":"done"}""")
    }

    /** A game sent while RomM was out of reach is waiting there: RomM answering again claims it. */
    @Test fun rommAnsweringAgainClaims() = runBlocking<Unit> {
        running()
        reachability.answered()
        reachability.unreachable()
        delay(SendToDevice.NUDGE_GATHER_MS * 2)
        assertThat(fake.claims.get()).isEqualTo(0)

        reachability.answered()

        until { fake.claims.get() == 1 }
    }

    /** Added on RomM since the library last synced: RomM says where it goes, and the library syncs. */
    @Test fun aGameNotInTheLibraryYetStartsALibrarySync() = runBlocking<Unit> {
        folders("snes")
        fake.send("req-77", 77)

        sendToDevice.drain()

        assertThat(jobFor(77)!!.installRequestId).isEqualTo("req-77")
        assertThat(fake.reports).containsExactly("req-77" to """{"status":"done"}""")
        assertThat(WorkManager.getInstance(context).getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get()).hasSize(1)
    }

    /** Loom coming to the front as RomM answers again, or a socket nudge with it: one claim for all. */
    @Test fun twoNudgesAtOnceClaimOnce() = runBlocking<Unit> {
        running()

        sendToDevice.nudge()
        sendToDevice.nudge()

        until { fake.claims.get() == 1 }
        delay(SendToDevice.NUDGE_GATHER_MS * 3)
        assertThat(fake.claims.get()).isEqualTo(1)
    }

    // --- while Loom is in front ---

    /** Coming to the front claims, and RomM's socket then says the moment a game is sent. */
    @Test fun inFrontASentGameArrivesAtOnce() = runBlocking<Unit> {
        folders("snes")
        inFront()
        until { fake.claims.get() == 1 }

        fake.send("req-1", 10)
        socket.send(DeviceEvent.InstallQueued("req-1", 10))

        until { fake.reports.isNotEmpty() }
        assertThat(fake.reports).containsExactly("req-1" to """{"status":"done"}""")
        assertThat(fake.claims.get()).isEqualTo(2)
    }

    /** The socket refusing Loom's token is RomM refusing the device: Loom asks to pair again, and the socket closes. */
    @Test fun aSocketThatRefusesTheTokenAsksToPairAgain() = runBlocking<Unit> {
        inFront()

        socket.send(DeviceEvent.Refused(DeviceSocket.UNAUTHORIZED))

        until { store.snapshot().needsPairing }
        until { sendToDevice.state.value == SendToDeviceState.NeedsPairing }
    }

    /** The socket saying device installs are off on the server stops the claims until a heartbeat says they're back. */
    @Test fun aSocketThatSaysInstallsAreOffStopsTheClaims() = runBlocking<Unit> {
        inFront()
        until { periodicClaim().singleOrNull()?.state == WorkInfo.State.ENQUEUED }

        socket.send(DeviceEvent.Refused(DeviceSocket.DISABLED))

        until { sendToDevice.state.value == SendToDeviceState.OffOnServer }
        until { periodicClaim().single().state == WorkInfo.State.CANCELLED }
        assertThat(store.snapshot().needsPairing).isFalse()
    }

    /**
     * The owner cancelled while Loom was queueing the game, before its job existed: the cancel waits for
     * that, and the game still leaves the queue.
     */
    @Test fun aCancelHeardWhileTheGameIsBeingQueuedStillTakesItOut() = runBlocking<Unit> {
        folders("snes")
        inFront()
        until { fake.claims.get() == 1 }
        val fetching = CountDownLatch(1)
        val answer = CountDownLatch(1)
        fake.romAnswer = {
            fetching.countDown()
            answer.await(5, TimeUnit.SECONDS)
            null
        }
        fake.send("req-1", 10)
        socket.send(DeviceEvent.InstallQueued("req-1", 10))
        withContext(Dispatchers.IO) { assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue() }

        fake.gone += "req-1" // RomM drops a cancelled request, taken or not
        socket.send(DeviceEvent.InstallCancelled("req-1", 10))
        delay(300)
        answer.countDown()

        until { jobFor(10)?.phase == JobPhase.CANCELLING.name }
        assertThat(fake.reports).isEmpty()
    }

    /** The owner cancelled on RomM while the game waits for its folder: it leaves the queue. */
    @Test fun aCancelFromTheSocketTakesTheGameOutOfTheQueue() = runBlocking<Unit> {
        folders("snes", "sfc")
        fake.send("req-1", 10)
        inFront()
        until { jobFor(10) != null }

        socket.send(DeviceEvent.InstallCancelled("req-1", 10))

        until { jobFor(10)!!.phase == JobPhase.CANCELLING.name }
    }

    /**
     * A write that fails (a full disk, say) as the socket's refusal is recorded doesn't stop the rest for
     * as long as Loom runs: the socket opens again, and Loom still claims when nudged.
     */
    @Test fun aWriteThatFailsDoesntStopTheSocketOrTheClaims() = runBlocking<Unit> {
        inFront()
        until { fake.claims.get() == 1 }
        failNextWrite.set(true)

        socket.send(DeviceEvent.Refused(DeviceSocket.UNAUTHORIZED))

        until { connections.get() == 2 }
        sendToDevice.nudge()
        until { fake.claims.get() == 2 }
    }

    /** Paired again while recording that failed: the refusal is let go all the same, once the write works. */
    @Test fun pairingAgainWhileAWriteFailsStillLetsTheRefusalGo() = runBlocking<Unit> {
        store.setNeedsPairing(true)
        running()
        until { sendToDevice.state.value == SendToDeviceState.NeedsPairing }
        failNextWrite.set(true)

        credentials.save(PairedCredentials(romm.url("/"), "rmm_y", "dev-thor", "david", null))

        until { !store.snapshot().needsPairing }
        until { sendToDevice.state.value == SendToDeviceState.Ready("dev-thor") }
    }

    // --- in the background ---

    /** Every 15 minutes with a network, only while games can come: switched off, it stops; on again, it's back. */
    @Test fun thePeriodicClaimRunsOnlyWhileReady() = runBlocking<Unit> {
        running()
        until { periodicClaim().singleOrNull()?.state == WorkInfo.State.ENQUEUED }
        val periodic = periodicClaim().single()
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(periodic.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)

        store.setReceive(false)
        until { periodicClaim().single().state == WorkInfo.State.CANCELLED }
        store.setReceive(true)
        until { periodicClaim().single().state == WorkInfo.State.ENQUEUED }
    }

    @Test fun theBackgroundClaimQueuesWhatWasSent() = runBlocking<Unit> {
        folders("snes")
        fake.send("req-1", 10)
        val worker = TestListenableWorkerBuilder<ClaimWorker>(context).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                ClaimWorker(appContext, workerParameters, sendToDevice)
        }).build()

        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(jobFor(10)!!.installRequestId).isEqualTo("req-1")
    }

    /**
     * RomM 5.4.0's device endpoints, keeping install requests as it does: pending until claimed, then
     * taken until reported (or cancelled), and handed back by every claim while taken.
     */
    private class FakeRommDevices : Dispatcher() {
        private val requests = ConcurrentHashMap<String, Int>() // request id → rom id, in the order sent
        private val order = CopyOnWriteArrayList<String>()

        /** What `PUT api/devices/{id}` was sent, with the device it named. */
        val declared = CopyOnWriteArrayList<Pair<String, String>>()

        /** What was reported, by request. */
        val reports = CopyOnWriteArrayList<Pair<String, String>>()
        val claims = AtomicInteger()
        val heartbeats = AtomicInteger()

        /** Requests that ended meanwhile (cancelled, expired): a report on one is a 404. */
        val gone: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Requests the owner cancels just as Loom claims them: handed back by the claim, gone by the report. */
        val goneAfterClaim: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Every report Loom tried, by request, whatever RomM answered. */
        val reportAttempts = CopyOnWriteArrayList<String>()

        @Volatile var installsEnabled = true

        /** False: a RomM without device installs, whose heartbeat says nothing of them and which has no claim. */
        @Volatile var installsOffered = true
        @Volatile var version = "5.4.0"
        @Volatile var deviceDeleted = false

        /** The claim's answer when not the usual 200. */
        @Volatile var claimAnswer: Int? = null

        /** `PUT …/installs/{id}`'s answer, by request, when not RomM's usual one (which a null answer gives). */
        @Volatile var reportAnswer: ((String) -> MockResponse?)? = null

        /** `GET api/roms/{id}` when not the game RomM has (which a null answer gives). */
        @Volatile var romAnswer: ((Int) -> MockResponse?)? = null

        fun send(id: String, romId: Int) {
            requests[id] = romId
            order += id
        }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val method = request.method
            if (path == "/api/heartbeat") {
                heartbeats.incrementAndGet()
                if (!installsOffered) return json("""{"SYSTEM":{"VERSION":"$version","SHOW_SETUP_WIZARD":false}}""")
                return json("""{"SYSTEM":{"VERSION":"$version","SHOW_SETUP_WIZARD":false},
                    "DEVICE_INSTALL":{"ENABLED":$installsEnabled,"EXCLUDED_PLATFORM_SLUGS":[]}}""")
            }
            Regex("""/api/roms/(\d+)$""").find(path)?.let { m ->
                val id = m.groupValues[1].toInt()
                return romAnswer?.invoke(id) ?: json(romJson(id, 400 + id, ByteArray(1_000) { it.toByte() }).toString())
            }
            Regex("""/api/devices/([^/]+)$""").find(path)?.takeIf { method == "PUT" }?.let { m ->
                if (deviceDeleted) return error(404, "Device not found")
                val body = request.body?.utf8().orEmpty()
                declared += m.groupValues[1] to body
                val capabilities = Json.parseToJsonElement(body).jsonObject["capabilities"] as JsonObject
                return json("""{"id":"${m.groupValues[1]}","capabilities":$capabilities}""")
            }
            if (method == "POST" && path.endsWith("/installs/claim")) {
                claims.incrementAndGet()
                if (!installsOffered) return error(404, "Not Found")
                claimAnswer?.let { return error(it, "Only the device itself can do this") }
                if (!installsEnabled) return error(404, "Installing on a device is disabled on this server")
                if (deviceDeleted) return error(404, "Device not found")
                val taken = order.filter { requests.containsKey(it) && it !in gone }
                gone += goneAfterClaim
                return json(taken.joinToString(",", "[", "]") { request(it, requests.getValue(it), "taken") })
            }
            Regex("""/api/devices/[^/]+/installs/([^/]+)$""").find(path)?.takeIf { method == "PUT" }?.let { m ->
                val id = m.groupValues[1]
                reportAttempts += id
                reportAnswer?.invoke(id)?.let { return it }
                val romId = requests[id]
                if (romId == null || id in gone) return error(404, "Install request not found")
                requests.remove(id)
                val body = request.body?.utf8().orEmpty()
                reports += id to body
                val status = Json.parseToJsonElement(body).jsonObject["status"]!!.jsonPrimitive.content
                return json(request(id, romId, status))
            }
            return error(404, "Not found")
        }

        private fun request(id: String, romId: Int, status: String) =
            """{"id":"$id","user_id":1,"device_id":"dev-thor","rom_id":$romId,"file_ids":[${400 + romId}],"status":"$status",
            "reason":null,"created_at":"2026-10-15T10:00:00Z","updated_at":"2026-10-15T10:00:00Z"}"""

        private fun json(body: String) =
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()

        private fun error(code: Int, detail: String) =
            MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body("""{"detail":"$detail"}""").build()
    }
}
