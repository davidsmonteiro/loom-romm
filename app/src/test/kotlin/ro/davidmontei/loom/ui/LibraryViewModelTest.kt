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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.db.SaveUnitEntity
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.saves.SaveSyncEngine
import ro.davidmontei.loom.data.saves.SaveUnitResolver
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.storage.mapping.MappingSource
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.LibraryViewModel
import ro.davidmontei.loom.ui.library.toItem
import java.io.File

/** A platform's first download: where its games go, decided from the card or asked. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = UnconfinedTestDispatcher()
    private lateinit var db: LoomDatabase
    private lateinit var settings: SettingsStore
    private lateinit var mapping: MappingRepository
    private lateinit var vm: LibraryViewModel
    private lateinit var roms: File

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(main)
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
        val engine = SaveSyncEngine(db.catalogDao(), db.saveDao(), server, credentials, settings, SaveUnitResolver(settings, volumes), volumes,
            File(tmp.root, "cache"))
        vm = LibraryViewModel(
            db.catalogDao(),
            DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, volumes, QueueKicker(workManager)),
            InstallManager(db.catalogDao(), settings, mapping, volumes),
            CatalogSyncScheduler(workManager), server, settings, FolderChooser(settings, mapping),
            SaveRepository(engine, db.saveDao(), scope),
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

    private suspend fun installed(romId: Int, platform: Int, relPath: String) = db.catalogDao().upsertInstall(
        InstallEntity(romId, "sd", roms.path, relPath, relPath.lowercase(), platform, InstallKind.FILE, 1, VerifiedBy.SIZE, InstallSource.ADOPTED, null, 0),
    )

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
        assertThat(genesis.source).isEqualTo(MappingSource.DEFAULT) // found by Loom, not "Changed" by the user
    }

    /** Found by review: with the card unmounted (or no access) Loom remembered a folder it never saw. */
    @Test fun nothingIsRememberedWhenTheCardCantBeRead() = runBlocking<Unit> {
        settings.setRomsRoot(File(tmp.root, "unmounted/ROMs").path)
        vm.download(gameOn(2)).join()
        assertThat(vm.folderQuestion.value).isNull()
        assertThat(mapping.currentMapper().resolve("genesis", "genesis").folderChosen).isFalse()
    }

    /**
     * The games as the screen gets them. The search box's debounce waits on the test's Main
     * dispatcher, whose clock only moves when told to, so it is moved on while waiting.
     */
    private suspend fun gamesOnScreen(until: (List<GameItem>) -> Boolean): List<GameItem> = coroutineScope {
        val games = async(Dispatchers.IO) { vm.games.first(until) }
        withTimeout(5_000) { while (games.isActive) { main.scheduler.advanceTimeBy(200); delay(20) } }
        games.await()
    }

    /** A game's details say where its save stands once it's installed; other platforms say why theirs aren't synced. */
    @Test fun installedGamesCarryWhereTheirSaveStands() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(3, "3ds", "3ds", "Nintendo 3DS", "Nintendo 3DS", 2, "t")))
        db.catalogDao().upsertRoms(listOf(rom(30, 3, "3ds", "Pokemon X", "Pokemon X.3ds"), rom(31, 3, "3ds", "Pokemon Y", "Pokemon Y.3ds")))
        installed(30, 3, "n3ds/Pokemon X.3ds")
        installed(20, 2, "megadrive/Sonic the Hedgehog (USA, Europe).zip")
        db.saveDao().upsertUnit(SaveUnitEntity(30, "azahar", "/sdmc/data", "f", 2, 4096, 0, "h", SaveSyncState.SERVER_NEWER, null, 0))

        vm.selectPlatform(3)
        val n3ds = gamesOnScreen { games -> games.any { it.save != null } }
        assertThat(n3ds.single { it.id == 30 }.save).isEqualTo(GameSave(supported = true, SaveSyncState.SERVER_NEWER, null, "azahar"))
        assertThat(n3ds.single { it.id == 31 }.save).isNull() // not on the device

        vm.selectPlatform(2)
        val genesis = gamesOnScreen { games -> games.any { it.id == 20 && it.save != null } }
        assertThat(genesis.single().save)
            .isEqualTo(GameSave(supported = false, SaveSyncState.UNSUPPORTED, "Loom syncs 3DS saves (Azahar) only, for now", null))
    }

    /** Found by review: the folder was decided for the platform on screen, not the game's. */
    @Test fun theGamesOwnPlatformIsTheOneDecided() = runBlocking<Unit> {
        folders("arcade", "mame", "megadrive")
        val arcadeGame = gameOn(1)
        gameOn(2) // the screen has moved on to Genesis…
        vm.download(arcadeGame).join() // …as X lands on the arcade game still focused
        assertThat(withTimeout(5_000) { vm.folderQuestion.filterNotNull().first() }.platformName).isEqualTo("Arcade")
        assertThat(mapping.currentMapper().resolve("genesis", "genesis").folderChosen).isFalse()
    }
}
