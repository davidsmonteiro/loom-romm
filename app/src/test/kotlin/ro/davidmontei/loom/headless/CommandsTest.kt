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
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import java.io.File

@RunWith(AndroidJUnit4::class)
class CommandsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var credentials: CredentialStore
    private lateinit var sync: CatalogSyncScheduler
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        sync = CatalogSyncScheduler(workManager)
        val cipher = object : TokenCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(blob: String) = blob
        }
        credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") }, cipher, scope)
    }

    @After fun tearDown() = scope.cancel()

    private fun intent(action: String, full: Boolean? = null) = Intent(action).apply { full?.let { putExtra(Commands.EXTRA_FULL, it) } }

    @Test fun syncNeedsPairing() = runBlocking<Unit> {
        val r = Commands.run(intent(Commands.SYNC_CATALOG), credentials, sync)
        assertThat(r.code).isEqualTo(Commands.RESULT_NOT_PAIRED)
        assertThat(workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get()).isEmpty()
    }

    @Test fun syncQueuesTheWorker() = runBlocking<Unit> {
        credentials.save(PairedCredentials("https://romm.example.org/".toHttpUrl(), "rmm_x", null, "david", null))
        credentials.current.filterNotNull().first()
        val r = Commands.run(intent(Commands.SYNC_CATALOG, full = true), credentials, sync)
        assertThat(r.code).isEqualTo(Commands.RESULT_OK)
        assertThat(r.message).contains("Full")
        val infos = workManager.getWorkInfosForUniqueWork(CatalogSyncWorker.UNIQUE_NAME).get()
        assertThat(infos.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test fun saveCommandsAreNotAvailableYet() = runBlocking<Unit> {
        assertThat(Commands.run(intent(Commands.PUSH_SAVES), credentials, sync).code).isEqualTo(Commands.RESULT_UNAVAILABLE)
        assertThat(Commands.run(intent(Commands.PULL_SAVES), credentials, sync).code).isEqualTo(Commands.RESULT_UNAVAILABLE)
        assertThat(Commands.run(intent("something.else"), credentials, sync).code).isEqualTo(Commands.RESULT_UNKNOWN)
    }
}
