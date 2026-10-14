package ro.davidmontei.loom.data.saves

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.settings.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SaveSyncSchedulerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: SaveSyncScheduler
    private lateinit var settings: SettingsStore
    private lateinit var triggers: SaveSyncTriggers

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        scheduler = SaveSyncScheduler(workManager)
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        triggers = SaveSyncTriggers(settings, scheduler)
    }

    @After fun tearDown() = scope.cancel()

    private fun periodic() = workManager.getWorkInfosForUniqueWork(SaveSyncWorker.PERIODIC_NAME).get()
    private fun oneOff() = workManager.getWorkInfosForUniqueWork(SaveSyncWorker.NOW_NAME).get()
    private fun WorkInfo.kind() = tags.single { it.startsWith("kind:") }.removePrefix("kind:")

    @Test fun thePeriodicScanRunsEveryFifteenMinutesWithANetwork() {
        scheduler.setPeriodic(true)
        scheduler.setPeriodic(true) // kept, not restarted
        val info = periodic().single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(info.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test fun turningAutomaticUploadOffStopsThePeriodicScan() {
        scheduler.setPeriodic(true)
        scheduler.setPeriodic(false)
        assertThat(periodic().single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    @Test fun anAutomaticRunIsQueuedOnceButCommandsAlwaysRun() {
        scheduler.runNow(SaveSyncKind.AUTO)
        scheduler.runNow(SaveSyncKind.AUTO)
        assertThat(oneOff().map { it.kind() }).containsExactly("AUTO")
        scheduler.runNow(SaveSyncKind.PULL)
        assertThat(oneOff().map { it.kind() }).containsExactly("AUTO", "PULL")
        assertThat(oneOff().map { it.constraints.requiredNetworkType }.distinct()).containsExactly(NetworkType.CONNECTED)
    }

    @Test fun openingLoomLooksForChangedSavesAndKeepsThePeriodicScan() = runBlocking<Unit> {
        settings.setOnboardingComplete(true)
        triggers.appOpened()
        assertThat(periodic().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(oneOff().map { it.kind() }).containsExactly("AUTO")
    }

    @Test fun withAutomaticUploadOffOpeningLoomSchedulesNothing() = runBlocking<Unit> {
        settings.setOnboardingComplete(true)
        scheduler.setPeriodic(true)
        settings.setAutoUploadSaves(false)
        triggers.appOpened()
        assertThat(periodic().single().state).isEqualTo(WorkInfo.State.CANCELLED)
        assertThat(oneOff()).isEmpty()
    }

    @Test fun beforeSetupIsFinishedNothingIsScheduled() = runBlocking<Unit> {
        triggers.appOpened()
        assertThat(periodic()).isEmpty()
        assertThat(oneOff()).isEmpty()
    }

    @Test fun aMountedCardLooksForChangedSaves() {
        triggers.cardMounted()
        assertThat(oneOff().map { it.kind() }).containsExactly("AUTO")
    }
}
