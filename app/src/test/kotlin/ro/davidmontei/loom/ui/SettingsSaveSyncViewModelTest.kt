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
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.saves.SaveSyncScheduler
import ro.davidmontei.loom.data.saves.SaveSyncTriggers
import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.saves.SaveSyncWorker
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.RommConnector
import ro.davidmontei.loom.ui.settings.SettingsViewModel
import ro.davidmontei.loom.ui.settings.edenFolderSaved
import ro.davidmontei.loom.ui.settings.melonDsFolderSaved
import ro.davidmontei.loom.ui.settings.ppssppFolderSaved
import java.io.File

/** Settings' save sync: where Azahar's data is found, choosing its folder, and the automatic upload switch. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsSaveSyncViewModelTest {
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
            context, settings, credentials, db.catalogDao(), mapping, FolderChooser(settings, mapping), volumes,
            InstallManager(db.catalogDao(), settings, mapping, volumes),
            CatalogSyncScheduler(workManager), RommConnector(OkHttpClient()), ActiveServer(settings, credentials, OkHttpClient(), scope),
            SaveSyncTriggers(settings, SaveSyncScheduler(workManager)),
            SendToDeviceStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "send_to_device.preferences_pb") }),
            {},
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

    /** On the Thor: Eden's save folder in ROMs/saves/Eden with one profile, and the owner's prod.keys in the ROMs folder. */
    @Test fun edensFolderItsProfilesAndProdKeysAreFound() = runBlocking<Unit> {
        File(roms, "saves/Eden/user/save/0000000000000000/4E431822D6DEFD527268BA35B5C09533").mkdirs()
        File(roms, "saves/Eden/user/save/0000000000000000/${"0".repeat(32)}").mkdirs() // device saves: no profile
        File(roms, "prod.keys").writeText("header_key = ${"ab".repeat(32)}\n")
        val vm = viewModel()
        val storage = withTimeout(5_000) { vm.state.first { it.storage.edenFound != null } }.storage
        assertThat(storage.edenFound).isEqualTo(File(roms, "saves/Eden").path)
        assertThat(storage.edenProfiles.map { it.id }).containsExactly("4E431822D6DEFD527268BA35B5C09533")
        assertThat(storage.prodKeys).isEqualTo(File(roms, "prod.keys").path)
        assertThat(storage.prodKeysWithoutKey).isFalse()
        assertThat(storage.toString()).doesNotContain("abab") // where the keys are, never what they are
        vm.viewModelScope.cancel()
    }

    /** Settings tells a prod.keys Loom can use from one without a header_key, and finds a good one behind a bad one. */
    @Test fun aProdKeysWithoutAHeaderKeyIsToldApart() = runBlocking<Unit> {
        File(roms, "prod.keys").writeText("key_area_key_application_00 = ${"cd".repeat(16)}\n")
        val vm = viewModel()
        val storage = withTimeout(5_000) { vm.state.first { it.storage.romsRoot != null } }.storage
        assertThat(storage.prodKeys).isNull()
        assertThat(storage.prodKeysWithoutKey).isTrue()
        File(roms, "switch").mkdirs()
        File(roms, "switch/prod.keys").writeText("header_key = ${"ab".repeat(32)}\n")
        vm.refreshDevice().join()
        val found = withTimeout(5_000) { vm.state.first { it.storage.prodKeys != null } }.storage
        assertThat(found.prodKeys).isEqualTo(File(roms, "switch/prod.keys").path)
        assertThat(found.prodKeysWithoutKey).isFalse()
        vm.viewModelScope.cancel()
    }

    @Test fun choosingEdensFolderIsKeptAndItsProfilesAreRead() = runBlocking<Unit> {
        val vm = viewModel()
        val chosen = tmp.newFolder("Eden").apply {
            File(this, "user/save/0000000000000000/0123456789ABCDEF0123456789ABCDEF").mkdirs()
            File(this, "user/save/0000000000000000/4E431822D6DEFD527268BA35B5C09533").mkdirs()
        }
        val message = async { vm.messages.first() }
        vm.setEdenFolder(chosen).join()
        assertThat(settings.snapshot().edenSaveDir).isEqualTo(chosen.path)
        assertThat(withTimeout(5_000) { message.await() }).isEqualTo("Saved. Loom finds Switch saves in ${chosen.path}")
        assertThat(withTimeout(5_000) { vm.state.first { it.storage.edenProfiles.size == 2 } }.storage.edenProfiles.map { it.id })
            .containsExactly("0123456789ABCDEF0123456789ABCDEF", "4E431822D6DEFD527268BA35B5C09533").inOrder()
        val chose = async { vm.messages.first() }
        vm.setEdenProfile("4E431822D6DEFD527268BA35B5C09533").join()
        assertThat(settings.snapshot().edenProfile).isEqualTo("4E431822D6DEFD527268BA35B5C09533")
        assertThat(withTimeout(5_000) { chose.await() }).isEqualTo("Loom syncs the Switch saves of Eden's profile 4E43…9533")
        vm.setEdenFolder(null).join()
        assertThat(settings.snapshot().edenSaveDir).isNull()
        vm.viewModelScope.cancel()
    }

    @Test fun choosingAFolderWithoutEdensSavesSaysSo() {
        val empty = tmp.newFolder("Empty")
        assertThat(edenFolderSaved(empty)).isEqualTo("Saved, but there's no user/save in ${empty.path} yet. Choose the folder Eden saves in.")
        assertThat(edenFolderSaved(null)).isEqualTo("Loom looks for Eden's saves in the ROMs folder's saves/Eden again")
    }

    /** melonDS's folder is found in the ROMs folder's saves/melonDS; one chosen is kept, and Settings says whether it is there. */
    @Test fun melonDsFolderIsFoundOrChosen() = runBlocking<Unit> {
        File(roms, "saves/melonDS").mkdirs()
        val vm = viewModel()
        assertThat(withTimeout(5_000) { vm.state.first { it.storage.romsRoot != null } }.storage.melonDsFound)
            .isEqualTo(File(roms, "saves/melonDS").path)
        val chosen = tmp.newFolder("melonDS saves")
        val message = async { vm.messages.first() }
        vm.setMelonDsFolder(chosen).join()
        assertThat(settings.snapshot().melonDsSaveDir).isEqualTo(chosen.path)
        assertThat(withTimeout(5_000) { message.await() }).isEqualTo("Saved. Loom finds DS saves in ${chosen.path}: set melonDS's Save file directory to it too")
        vm.setMelonDsFolder(null).join()
        assertThat(settings.snapshot().melonDsSaveDir).isNull()
        assertThat(melonDsFolderSaved(File(tmp.root, "nowhere"))).isEqualTo("Saved, but there's no folder at ${File(tmp.root, "nowhere").path}. Choose the folder melonDS saves in.")
        assertThat(melonDsFolderSaved(null)).isEqualTo("Loom looks for melonDS's saves in the ROMs folder's saves/melonDS again")
        vm.viewModelScope.cancel()
    }

    /** PPSSPP's memory stick is found in the ROMs folder's saves/PPSSPP once PPSSPP keeps its settings there; one chosen is kept. */
    @Test fun ppssppsMemoryStickIsFoundOrChosen() = runBlocking<Unit> {
        File(roms, "saves/PPSSPP/PSP/SAVEDATA").mkdirs()
        val vm = viewModel()
        assertThat(withTimeout(5_000) { vm.state.first { it.storage.romsRoot != null } }.storage.ppssppFound).isNull() // PPSSPP hasn't used it
        File(roms, "saves/PPSSPP/PSP/SYSTEM").mkdirs()
        File(roms, "saves/PPSSPP/PSP/SYSTEM/ppsspp.ini").writeText("[General]\n")
        vm.refreshDevice().join()
        assertThat(withTimeout(5_000) { vm.state.first { it.storage.ppssppFound != null } }.storage.ppssppFound)
            .isEqualTo(File(roms, "saves/PPSSPP").path)
        val message = async { vm.messages.first() }
        vm.setPpssppFolder(File(roms, "saves/PPSSPP/PSP")).join()
        assertThat(settings.snapshot().ppssppMemoryStick).isEqualTo(File(roms, "saves/PPSSPP/PSP").path)
        assertThat(withTimeout(5_000) { message.await() }).isEqualTo("Saved. Loom finds PSP saves in ${File(roms, "saves/PPSSPP/PSP").path}")
        vm.setPpssppFolder(null).join()
        assertThat(settings.snapshot().ppssppMemoryStick).isNull()
        val elsewhere = tmp.newFolder("Memory Stick")
        assertThat(ppssppFolderSaved(elsewhere))
            .isEqualTo("Saved, but PPSSPP hasn't used ${elsewhere.path} as its memory stick yet. Set its Memory Stick folder there too.")
        assertThat(ppssppFolderSaved(null)).isEqualTo("Loom looks for PPSSPP's memory stick in the ROMs folder's saves/PPSSPP again")
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
