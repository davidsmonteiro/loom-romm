package ro.davidmontei.loom.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.saves.SaveSyncScheduler
import ro.davidmontei.loom.data.saves.SaveSyncTriggers
import ro.davidmontei.loom.data.saves.SaveSyncWorker
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.RommConnector
import ro.davidmontei.loom.ui.settings.SettingsViewModel
import java.io.File

/** Settings' save sync: where Azahar's data is found, choosing its folder, and the automatic upload switch. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var workManager: WorkManager
    private lateinit var roms: File

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, LoomDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") })
        roms = tmp.newFolder("ROMs")
        settings.setRomsRoot(roms.path)
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(3, "3ds", "3ds", "Nintendo 3DS", "Nintendo 3DS", 2, "t")))
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mapping = MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "mapping.preferences_pb") })
        val credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "credentials.preferences_pb") },
            object : TokenCipher {
                override fun encrypt(plaintext: String) = plaintext
                override fun decrypt(blob: String) = blob
            }, scope)
        val volumes = StorageVolumes(context)
        return SettingsViewModel(
            context, settings, credentials, db.catalogDao(), mapping, volumes, InstallManager(db.catalogDao(), settings, mapping, volumes),
            CatalogSyncScheduler(workManager), RommConnector(OkHttpClient()), ActiveServer(settings, credentials, OkHttpClient(), scope),
            SaveSyncTriggers(settings, SaveSyncScheduler(workManager)),
        )
    }

    @Test fun azaharsDataIsFoundInThe3dsSystemFolder() = runBlocking<Unit> {
        File(roms, "n3ds/sdmc/Nintendo 3DS").mkdirs()
        val vm = viewModel()
        val found = withTimeout(5_000) { vm.state.first { it.storage.azaharFound != null } }.storage.azaharFound
        assertThat(found).isEqualTo(File(roms, "n3ds").path)
        vm.viewModelScope.cancel()
    }

    @Test fun choosingAzaharsFolderIsKeptAndSaysWhatsThere() = runBlocking<Unit> {
        val vm = viewModel()
        val chosen = tmp.newFolder("Azahar").apply { File(this, "sdmc/Nintendo 3DS").mkdirs() }
        val message = async { vm.messages.first() }
        vm.setAzaharFolder(chosen).join()
        assertThat(settings.snapshot().azaharUserDir).isEqualTo(chosen.path)
        assertThat(withTimeout(5_000) { message.await() }).isEqualTo("Saved. Loom finds 3DS saves in ${chosen.path}")
        vm.setAzaharFolder(null).join()
        assertThat(settings.snapshot().azaharUserDir).isNull()
        vm.viewModelScope.cancel()
    }

    /** Going back to the 3DS folder looks there again: Azahar may have made its data since Settings opened. */
    @Test fun goingBackToThe3dsFolderLooksForAzaharsDataAgain() = runBlocking<Unit> {
        val vm = viewModel()
        withTimeout(5_000) { vm.state.first { it.storage.romsRoot != null } }
        File(roms, "n3ds/sdmc/Nintendo 3DS").mkdirs() // a game run in Azahar meanwhile
        vm.setAzaharFolder(null).join()
        val found = withTimeout(5_000) { vm.state.first { it.storage.azaharFound != null } }.storage.azaharFound
        assertThat(found).isEqualTo(File(roms, "n3ds").path)
        vm.viewModelScope.cancel()
    }

    @Test fun theAutomaticUploadSwitchStartsAndStopsThePeriodicScan() = runBlocking<Unit> {
        val vm = viewModel()
        vm.setAutoUploadSaves(true).join()
        assertThat(workManager.getWorkInfosForUniqueWork(SaveSyncWorker.PERIODIC_NAME).get().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        vm.setAutoUploadSaves(false).join()
        assertThat(settings.snapshot().autoUploadSaves).isFalse()
        assertThat(workManager.getWorkInfosForUniqueWork(SaveSyncWorker.PERIODIC_NAME).get().single().state).isEqualTo(WorkInfo.State.CANCELLED)
        vm.viewModelScope.cancel()
    }
}
