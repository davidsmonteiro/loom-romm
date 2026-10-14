package ro.davidmontei.loom.headless

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncWorker
import ro.davidmontei.loom.data.saves.SaveSyncScheduler
import ro.davidmontei.loom.data.saves.SaveSyncWorker
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import java.io.File

@RunWith(AndroidJUnit4::class)
class CommandsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var credentials: CredentialStore
    private lateinit var settings: SettingsStore
    private lateinit var sync: CatalogSyncScheduler
    private lateinit var saves: SaveSyncScheduler
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        sync = CatalogSyncScheduler(workManager)
        saves = SaveSyncScheduler(workManager)
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        val cipher = object : TokenCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(blob: String) = blob
        }
        credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") }, cipher, scope)
    }

    @After fun tearDown() = scope.cancel()

    private fun intent(action: String, full: Boolean? = null) = Intent(action).apply { full?.let { putExtra(Commands.EXTRA_FULL, it) } }

    private suspend fun run(intent: Intent) = Commands.run(intent, credentials, settings, sync, saves)

    private suspend fun pair() {
        credentials.save(PairedCredentials("https://romm.example.com/".toHttpUrl(), "rmm_x", "dev-1", "david", null))
        credentials.current.filterNotNull().first()
    }

    /** The one-off save sync queued, with the kind of run it was asked for. */
    private fun queuedSaveRuns(): List<String?> = workManager.getWorkInfosForUniqueWork(SaveSyncWorker.NOW_NAME).get()
        .map { info -> info.tags.firstOrNull { it.startsWith("kind:") } }

    @Test fun syncNeedsPairing() = runBlocking<Unit> {
        val r = run(intent(Commands.SYNC_CATALOG))
        assertThat(r.code).isEqualTo(Commands.RESULT_NOT_PAIRED)
        assertThat(workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get()).isEmpty()
    }

    @Test fun syncQueuesTheWorker() = runBlocking<Unit> {
        pair()
        val r = run(intent(Commands.SYNC_CATALOG, full = true))
        assertThat(r.code).isEqualTo(Commands.RESULT_OK)
        assertThat(r.message).contains("Full")
        val infos = workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get()
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test fun pushSavesQueuesAnUploadRun() = runBlocking<Unit> {
        pair()
        settings.setOnboardingComplete(true)
        val r = run(intent(Commands.PUSH_SAVES))
        assertThat(r.code).isEqualTo(Commands.RESULT_OK)
        assertThat(queuedSaveRuns()).containsExactly("kind:PUSH")
    }

    /** Any app can send it, so it only ever queues the safe downloads; the worker does nothing more (SaveSyncWorkerTest). */
    @Test fun pullSavesQueuesASafeDownloadRun() = runBlocking<Unit> {
        pair()
        settings.setOnboardingComplete(true)
        val r = run(intent(Commands.PULL_SAVES))
        assertThat(r.code).isEqualTo(Commands.RESULT_OK)
        assertThat(r.message).contains("backed up")
        assertThat(queuedSaveRuns()).containsExactly("kind:PULL")
    }

    @Test fun saveCommandsNeedPairingAndAFinishedSetup() = runBlocking<Unit> {
        assertThat(run(intent(Commands.PUSH_SAVES)).code).isEqualTo(Commands.RESULT_NOT_PAIRED)
        assertThat(run(intent(Commands.PULL_SAVES)).code).isEqualTo(Commands.RESULT_NOT_PAIRED)
        pair()
        assertThat(run(intent(Commands.PUSH_SAVES)).code).isEqualTo(Commands.RESULT_UNAVAILABLE)
        assertThat(run(intent(Commands.PULL_SAVES)).code).isEqualTo(Commands.RESULT_UNAVAILABLE)
        assertThat(queuedSaveRuns()).isEmpty()
        assertThat(run(intent("something.else")).code).isEqualTo(Commands.RESULT_UNKNOWN)
    }
}
