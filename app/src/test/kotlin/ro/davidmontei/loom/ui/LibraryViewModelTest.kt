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
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.ui.library.LibraryViewModel
import ro.davidmontei.loom.ui.library.toItem
import java.io.File

/** A platform's first download: where its games go, decided from the card or asked. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var mapping: MappingRepository
    private lateinit var vm: LibraryViewModel
    private lateinit var roms: File

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, LoomDatabase::class.java).allowMainThreadQueries().build()
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
        settings = SettingsStore(prefs)
        mapping = MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "mapping.preferences_pb") })
        val credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "credentials.preferences_pb") },
            object : TokenCipher {
                override fun encrypt(plaintext: String) = plaintext
                override fun decrypt(blob: String) = blob
            }, scope)
        val server = ActiveServer(settings, credentials, OkHttpClient(), scope)
        val volumes = StorageVolumes(context)
        roms = tmp.newFolder("ROMs")
        settings.setRomsRoot(roms.path)
        db.catalogDao().upsertPlatforms(listOf(
            PlatformEntity(1, "arcade", "arcade", "Arcade", "Arcade", 1, "t"),
            PlatformEntity(2, "genesis", "genesis", "Sega Mega Drive/Genesis", "Sega Mega Drive/Genesis", 1, "t"),
        ))
        db.catalogDao().upsertRoms(listOf(
            rom(10, 1, "arcade", "Alley Master", "alleymas.zip"),
            rom(20, 2, "genesis", "Sonic the Hedgehog", "Sonic the Hedgehog (USA, Europe).zip"),
        ))
        vm = LibraryViewModel(
            db.catalogDao(),
            DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, volumes, QueueKicker(workManager)),
            InstallManager(db.catalogDao(), settings, mapping, volumes),
            CatalogSyncScheduler(workManager), server, settings, FolderChooser(settings, mapping),
        )
    }

    @After fun tearDown() {
        vm.viewModelScope.cancel()
        db.close()
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun rom(id: Int, platform: Int, slug: String, title: String, file: String) = RomEntity(
        id, platform, slug, slug, title, title.lowercase(), title.lowercase(), file, file.substringBeforeLast('.'), "zip",
        100_000, true, false, false, null, null, "World", "t", false,
    )

    private fun folders(vararg names: String) = names.forEach { File(roms, it).mkdirs() }

    /** The platform's one game, with that platform on screen (where X is pressed). */
    private suspend fun gameOn(platformId: Int) = run {
        vm.selectPlatform(platformId)
        withTimeout(5_000) { vm.selected.first { it?.platform?.id == platformId } }
        db.catalogDao().observeGames(platformId, 0, "").first().single().toItem()
    }

    /** The Thor's ES-DE template: arcade, mame, fbneo and fba all exist, empty. Loom asks, once. */
    @Test fun severalCandidateFoldersMeanAQuestionWhoseAnswerIsKept() = runBlocking<Unit> {
        folders("arcade", "mame", "fbneo", "fba", "snes")
        vm.download(gameOn(1)).join()
        val question = withTimeout(5_000) { vm.folderQuestion.filterNotNull().first() }
        assertThat(question.options.map { it.name }).containsExactly("arcade", "fba", "fbneo", "mame")
        assertThat(question.suggested).isEqualTo("mame")

        vm.chooseFolder("arcade").join()
        assertThat(vm.folderQuestion.value).isNull()
        val arcade = mapping.currentMapper().resolve("arcade", "arcade")
        assertThat(arcade.folder).isEqualTo("arcade")
        assertThat(arcade.folderChosen).isTrue()

        vm.download(gameOn(1)).join() // the next one doesn't ask
        assertThat(vm.folderQuestion.value).isNull()
    }

    @Test fun anObviousFolderIsUsedWithoutAskingAndKept() = runBlocking<Unit> {
        folders("megadrive", "snes")
        vm.download(gameOn(2)).join()
        assertThat(vm.folderQuestion.value).isNull()
        val genesis = mapping.currentMapper().resolve("genesis", "genesis")
        assertThat(genesis.folder).isEqualTo("megadrive")
        assertThat(genesis.folderChosen).isTrue()
    }
}
