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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowEnvironment
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.CatalogRefresh
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.RommConnector
import ro.davidmontei.loom.ui.settings.SettingsViewModel
import java.io.File

/** The settings ViewModel's library sync: what it saves, and the sync it reports. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var sync: CatalogSyncScheduler
    private lateinit var vm: SettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "$name.preferences_pb") }
        db = Room.inMemoryDatabaseBuilder(context, LoomDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(prefs("settings"))
        val credentials = CredentialStore(prefs("credentials"), object : TokenCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(blob: String) = blob
        }, scope)
        val mapping = MappingRepository(prefs("mapping"))
        val volumes = StorageVolumes(context)
        sync = CatalogSyncScheduler(WorkManager.getInstance(context))
        vm = SettingsViewModel(
            context, settings, credentials, db.catalogDao(), mapping, FolderChooser(settings, mapping), volumes,
            InstallManager(db.catalogDao(), settings, mapping, volumes), sync, RommConnector(OkHttpClient()),
            ActiveServer(settings, credentials, OkHttpClient(), scope),
        )
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun theRefreshSettingsAreSaved() = runBlocking<Unit> {
        vm.setCatalogRefresh(CatalogRefresh.EVERY_24_HOURS).join()
        vm.setRefreshOnWifiOnly(true).join()
        val saved = settings.snapshot()
        assertThat(saved.catalogRefresh).isEqualTo(CatalogRefresh.EVERY_24_HOURS)
        assertThat(saved.refreshOnWifiOnly).isTrue()
    }

    @Test fun theStateFollowsTheSync() = runBlocking<Unit> {
        val watching = scope.launch { vm.state.collect { } } // the screen
        try {
            assertThat(withTimeout(5_000) { vm.state.first { it.settings != null } }.sync.running).isFalse()
            sync.enqueue() // waits for a network in tests, so it stays queued
            assertThat(withTimeout(5_000) { vm.state.first { it.sync.running } }.sync.running).isTrue()
        } finally {
            watching.cancel()
        }
    }
}
