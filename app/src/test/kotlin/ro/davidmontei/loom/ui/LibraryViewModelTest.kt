package ro.davidmontei.loom.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.ui.library.LibraryViewModel
import ro.davidmontei.loom.ui.onboarding.OnboardingStep
import ro.davidmontei.loom.ui.onboarding.resumeStep
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var credentials: CredentialStore
    private lateinit var vm: LibraryViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, LoomDatabase::class.java).allowMainThreadQueries().build()
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
        settings = SettingsStore(prefs)
        val cipher = object : TokenCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(blob: String) = blob
        }
        credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "credentials.preferences_pb") }, cipher, scope)
        val server = ActiveServer(settings, credentials, OkHttpClient(), scope)
        val mapping = MappingRepository(prefs)
        val volumes = StorageVolumes(context)
        vm = LibraryViewModel(
            db.catalogDao(),
            DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, volumes, QueueKicker(workManager)),
            InstallManager(db.catalogDao(), settings, mapping, volumes),
            CatalogSyncScheduler(workManager), server, settings, credentials,
        )
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    /**
     * Found by review: the library's "Pair again", offered when RomM rejects the token, opened
     * onboarding with that token still stored, so onboarding went past pairing.
     */
    @Test fun pairAgainForgetsTheRejectedTokenFirst() = runBlocking<Unit> {
        credentials.save(PairedCredentials("https://romm.example.org/".toHttpUrl(), "rmm_revoked", null, "david", null))
        settings.setOnboardingComplete(true)
        credentials.current.filterNotNull().first()
        var opened = false
        vm.repair { opened = true }.join()
        assertThat(opened).isTrue()
        withTimeout(5_000) { credentials.current.first { it == null } }
        assertThat(settings.snapshot().onboardingComplete).isFalse()
        val paired = credentials.current.value != null
        assertThat(resumeStep(hasServer = true, paired = paired, hasAllFilesAccess = true, hasRomsRoot = true)).isEqualTo(OnboardingStep.PAIR)
    }
}
