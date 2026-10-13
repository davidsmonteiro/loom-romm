package ro.davidmontei.loom.data.catalog

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.settings.CatalogRefresh
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.headless.Commands
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import java.io.File
import java.util.concurrent.TimeUnit

/** The scheduled catalog refresh: how it is scheduled from the settings, and what each run does. */
@RunWith(AndroidJUnit4::class)
class CatalogRefreshTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var now = 1_000_000_000L
    private lateinit var workManager: WorkManager
    private lateinit var settings: SettingsStore
    private lateinit var credentials: CredentialStore
    private lateinit var server: ActiveServer
    private lateinit var sync: CatalogSyncScheduler
    private lateinit var refresh: CatalogRefreshScheduler

    private val hour = TimeUnit.HOURS.toMillis(1)
    private val romm = MockWebServer()

    @Before fun setUp() {
        romm.start()
        // WorkManager's clock is the test's, so "when is the next refresh" has an exact answer.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).setClock { now }.build(),
        )
        workManager = WorkManager.getInstance(context)
        fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "$name.preferences_pb") }
        settings = SettingsStore(prefs("settings"))
        credentials = CredentialStore(prefs("credentials"), object : TokenCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(blob: String) = blob
        }, scope)
        server = ActiveServer(settings, credentials, OkHttpClient(), scope)
        sync = CatalogSyncScheduler(workManager)
        refresh = CatalogRefreshScheduler(workManager)
    }

    @After fun tearDown() { romm.close(); scope.cancel() }

    /** What onboarding leaves behind: a server, a ROMs folder, and setup marked done. */
    private suspend fun finishSetup(url: HttpUrl = "http://100.64.0.7:8080/".toHttpUrl()) {
        settings.setServer(ServerConfig(url), "5.3.1")
        settings.setRomsRoot("/storage/9C33-6BBD/ROMs")
        settings.setOnboardingComplete(true)
    }

    private suspend fun pair() {
        credentials.save(PairedCredentials("http://100.64.0.7:8080/".toHttpUrl(), "rmm_x", "dev-1", "david", null))
        credentials.current.filterNotNull().first()
    }

    private suspend fun scheduleFromSettings() = refresh.schedule(settings.snapshot().refreshSchedule())

    private fun refreshWork(): WorkInfo? = workManager.getWorkInfosForUniqueWork(CatalogRefreshWorker.UNIQUE_NAME).get().singleOrNull()

    private fun syncWork(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get()

    /** Waits for the scheduled refresh to look like [what] (the settings reach the scheduler asynchronously). */
    private fun awaitRefresh(what: (WorkInfo?) -> Boolean): WorkInfo? = runBlocking {
        withTimeout(5_000) { workManager.getWorkInfosForUniqueWorkFlow(CatalogRefreshWorker.UNIQUE_NAME).first { what(it.singleOrNull()) } }
            .singleOrNull()
    }

    @Test fun onceSetUpLoomRefreshesEvery12HoursOnAnyNetworkUnlessTheBatteryIsLow() = runBlocking<Unit> {
        finishSetup()
        scheduleFromSettings()
        val work = refreshWork()!!
        assertThat(work.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(work.periodicityInfo!!.repeatIntervalMillis).isEqualTo(12 * hour)
        assertThat(work.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(work.constraints.requiresBatteryNotLow()).isTrue()
        // Setup has just synced: the first refresh is one interval away, not now.
        assertThat(work.nextScheduleTimeMillis).isEqualTo(now + 12 * hour)
    }

    @Test fun nothingIsScheduledBeforeSetupIsDone() = runBlocking<Unit> {
        settings.setServer(ServerConfig("http://100.64.0.7:8080/".toHttpUrl()), "5.3.1") // a server, but no ROMs folder yet
        settings.setOnboardingComplete(true)
        scheduleFromSettings()
        assertThat(refreshWork()).isNull()
    }

    @Test fun wifiOnlyWaitsForAnUnmeteredNetwork() = runBlocking<Unit> {
        finishSetup()
        settings.setRefreshOnWifiOnly(true)
        scheduleFromSettings()
        assertThat(refreshWork()!!.constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
    }

    /** A new interval changes the one schedule in place (not a second one, nor a restart), counting from when it started. */
    @Test fun aNewIntervalUpdatesTheScheduleInPlace() = runBlocking<Unit> {
        finishSetup()
        scheduleFromSettings()
        val started = now
        val first = refreshWork()!!
        now += 2 * hour
        settings.setCatalogRefresh(CatalogRefresh.EVERY_6_HOURS)
        scheduleFromSettings()
        val updated = refreshWork()!!
        assertThat(updated.id).isEqualTo(first.id)
        assertThat(updated.periodicityInfo!!.repeatIntervalMillis).isEqualTo(6 * hour)
        assertThat(updated.nextScheduleTimeMillis).isEqualTo(started + 6 * hour)
    }

    /** Loom schedules at every start: that mustn't put the next refresh off each time. */
    @Test fun schedulingAgainAtTheNextStartKeepsTheNextRefresh() = runBlocking<Unit> {
        finishSetup()
        scheduleFromSettings()
        val first = refreshWork()!!
        now += 5 * hour
        scheduleFromSettings()
        val again = refreshWork()!!
        assertThat(again.id).isEqualTo(first.id)
        assertThat(again.nextScheduleTimeMillis).isEqualTo(first.nextScheduleTimeMillis)
    }

    @Test fun offCancelsTheRefreshAndOnStartsItAgain() = runBlocking<Unit> {
        finishSetup()
        scheduleFromSettings()
        settings.setCatalogRefresh(CatalogRefresh.OFF)
        scheduleFromSettings()
        assertThat(refreshWork()!!.state).isEqualTo(WorkInfo.State.CANCELLED)
        settings.setCatalogRefresh(CatalogRefresh.EVERY_24_HOURS)
        scheduleFromSettings()
        val again = refreshWork()!!
        assertThat(again.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(again.periodicityInfo!!.repeatIntervalMillis).isEqualTo(24 * hour)
    }

    /** What the app does from its start: the schedule follows setup finishing and every change to its settings. */
    @Test fun followingTheSettingsSchedulesWhenSetupFinishesAndTracksChanges() = runBlocking<Unit> {
        val following = scope.launch { refresh.follow(settings.settings) }
        try {
            settings.setServer(ServerConfig("http://100.64.0.7:8080/".toHttpUrl()), "5.3.1")
            settings.setRomsRoot("/storage/9C33-6BBD/ROMs")
            Thread.sleep(200)
            assertThat(refreshWork()).isNull() // onboarding isn't finished yet
            settings.setOnboardingComplete(true)
            val scheduled = awaitRefresh { it?.state == WorkInfo.State.ENQUEUED }!!
            assertThat(scheduled.periodicityInfo!!.repeatIntervalMillis).isEqualTo(12 * hour)

            settings.setRefreshOnWifiOnly(true)
            awaitRefresh { it?.constraints?.requiredNetworkType == NetworkType.UNMETERED }
            settings.setCatalogRefresh(CatalogRefresh.EVERY_24_HOURS)
            val updated = awaitRefresh { it?.periodicityInfo?.repeatIntervalMillis == 24 * hour }!!
            assertThat(updated.id).isEqualTo(scheduled.id)

            // A sync records its time in the same settings; that leaves the schedule alone.
            settings.setCatalogSynced(null, now)
            Thread.sleep(200)
            assertThat(refreshWork()!!.generation).isEqualTo(updated.generation)

            settings.setCatalogRefresh(CatalogRefresh.OFF)
            awaitRefresh { it?.state == WorkInfo.State.CANCELLED }
        } finally {
            following.cancel()
        }
    }

    private fun refreshWorker(runAttempts: Int = 0) = TestListenableWorkerBuilder<CatalogRefreshWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                CatalogRefreshWorker(appContext, workerParameters, settings, credentials, server, sync)
        })
        .setRunAttemptCount(runAttempts)
        .build()

    /** Set up and paired with a RomM that answers. */
    private suspend fun readyToRefresh() {
        romm.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
            .body("""{"SYSTEM":{"VERSION":"5.3.1","SHOW_SETUP_WIZARD":false}}""").build())
        finishSetup(romm.url("/"))
        pair()
    }

    @Test fun aRefreshQueuesTheIncrementalSync() = runBlocking<Unit> {
        readyToRefresh()
        assertThat(refreshWorker().doWork()).isEqualTo(ListenableWorker.Result.success())
        val queued = syncWork().single()
        assertThat(queued.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(queued.tags).contains(CatalogSyncWorker.UNIQUE_NAME)
    }

    @Test fun aRefreshDoesNothingWhenNotPaired() = runBlocking<Unit> {
        finishSetup()
        credentials.awaitLoaded()
        assertThat(refreshWorker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(syncWork()).isEmpty()
    }

    @Test fun aRefreshDoesNothingBeforeSetupIsDone() = runBlocking<Unit> {
        pair()
        settings.setServer(ServerConfig("http://100.64.0.7:8080/".toHttpUrl()), "5.3.1") // no ROMs folder, setup not finished
        assertThat(refreshWorker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(syncWork()).isEmpty()
    }

    /** The headless SYNC_CATALOG and a refresh share the one incremental sync: the refresh keeps the one queued. */
    @Test fun aRefreshWhileTheHeadlessSyncIsQueuedDoesNotQueueASecond() = runBlocking<Unit> {
        readyToRefresh()
        val headless = Commands.run(Intent(Commands.SYNC_CATALOG), credentials, sync)
        assertThat(headless.code).isEqualTo(Commands.RESULT_OK)
        val queued = syncWork().single()
        refreshWorker().doWork()
        assertThat(syncWork().single().id).isEqualTo(queued.id)
    }

    /**
     * Found by review: away from RomM (Tailscale off, say), the refresh's sync failed and left "Sync failed" in
     * the library for hours, for a sync nobody asked for. Now it tries again a few times, then waits for the next one.
     */
    @Test fun aRefreshThatCantReachRommQueuesNoSync() = runBlocking<Unit> {
        val gone = MockWebServer().apply { start() }
        finishSetup(gone.url("/"))
        gone.close() // nothing answers there any more
        pair()
        assertThat(refreshWorker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(refreshWorker(runAttempts = 3).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(syncWork()).isEmpty()
    }

    /** Only "can't reach" is kept quiet: RomM refusing the token is the sync's to report, with Pair again beside it. */
    @Test fun aRefreshThatRommAnswersWithAnErrorStillQueuesTheSync() = runBlocking<Unit> {
        romm.enqueue(MockResponse.Builder().code(401).body("""{"detail":"Invalid token"}""").build())
        finishSetup(romm.url("/"))
        pair()
        assertThat(refreshWorker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(syncWork().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }
}
