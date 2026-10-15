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
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import ro.davidmontei.loom.data.db.SaveKeyEntity
import ro.davidmontei.loom.data.db.SaveKeySource
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.saves.EdenSaves
import ro.davidmontei.loom.data.saves.MelonDsSaves
import ro.davidmontei.loom.data.saves.PpssppSaves
import ro.davidmontei.loom.data.saves.SaveLook
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.saves.SaveSyncEngine
import ro.davidmontei.loom.data.saves.SaveUnitResolver
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.ServerReachability
import ro.davidmontei.loom.storage.mapping.MappingSource
import ro.davidmontei.loom.ui.library.GameItem
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.LibraryViewModel
import ro.davidmontei.loom.ui.library.saveLine
import ro.davidmontei.loom.ui.library.toItem
import ro.davidmontei.loom.ui.saves.SaveFolderQuestion
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
        val eden = EdenSaves(settings, db.catalogDao(), db.saveDao())
        val ppsspp = PpssppSaves(settings, db.catalogDao(), db.saveDao())
        val units = SaveUnitResolver(settings, volumes, eden, MelonDsSaves(settings, db.catalogDao()), ppsspp)
        val engine = SaveSyncEngine(db.catalogDao(), db.saveDao(), server, credentials, settings, units, volumes, File(tmp.root, "cache"))
        vm = LibraryViewModel(
            db.catalogDao(),
            DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, volumes, QueueKicker(workManager)),
            InstallManager(db.catalogDao(), settings, mapping, volumes),
            CatalogSyncScheduler(workManager), server, settings, FolderChooser(settings, mapping),
            SaveRepository(engine, db.saveDao(), eden, ppsspp, scope),
            reachability,
        )
    }

    private val reachability = ServerReachability()

    /** The header says the library is the copy kept on the Thor while RomM can't be reached, and stops once it can. */
    @Test fun theHeaderSaysWhenRommCantBeReached() = runBlocking<Unit> {
        val header = async(Dispatchers.IO) { vm.header.first { it.offline } }
        reachability.unreachable()
        assertThat(withTimeout(5_000) { header.await() }.offline).isTrue()
        val back = async(Dispatchers.IO) { vm.header.first { !it.offline } }
        reachability.answered()
        assertThat(withTimeout(5_000) { back.await() }.offline).isFalse()
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
            .isEqualTo(GameSave(supported = false, SaveSyncState.UNSUPPORTED, "Loom syncs 3DS, Switch, DS and PSP saves only, for now", null))
    }

    /**
     * A Switch game whose title ID Loom can't read: its details ask which of Eden's save folders is
     * its, and the answer is remembered and can be changed from the details afterwards.
     */
    @Test fun theSaveFolderQuestionListsEdensFoldersAndRemembersTheAnswer() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(4, "switch", "switch", "Nintendo Switch", "Nintendo Switch", 1, "t")))
        db.catalogDao().upsertRoms(listOf(rom(40, 4, "switch", "Wonder", "Wonder.xci")))
        installed(40, 4, "switch/Wonder.xci")
        File(roms, "saves/Eden/user/save/0000000000000000/4E431822D6DEFD527268BA35B5C09533/0100AAAA00000000").apply { mkdirs() }
            .resolve("game_data.sav").writeBytes(ByteArray(64))
        db.saveDao().upsertUnit(SaveUnitEntity(40, "eden", null, null, 0, 0, null, null, SaveSyncState.NEEDS_FOLDER, EdenSaves.NO_KEYS, 0))

        vm.selectPlatform(4)
        val game = gamesOnScreen { games -> games.any { it.save != null } }.single()
        assertThat(game.save).isEqualTo(GameSave(supported = true, SaveSyncState.NEEDS_FOLDER, EdenSaves.NO_KEYS, "eden"))
        assertThat(saveLine(game.save!!)).isEqualTo("Save: choose its Eden save folder. ${EdenSaves.NO_KEYS}")

        vm.askSaveFolder(game).join()
        val question = vm.saveFolderQuestion.value!!
        assertThat(question.options.map { it.id }).containsExactly("0100AAAA00000000")
        assertThat(question.options.single().fileCount).isEqualTo(1)
        assertThat(question.current).isNull()

        val message = async { vm.messages.first() }
        vm.chooseSaveFolder("0100AAAA00000000").join()
        assertThat(vm.saveFolderQuestion.value).isNull()
        assertThat(db.saveDao().saveKey("eden", 40)!!.saveKey).isEqualTo("0100AAAA00000000")
        assertThat(withTimeout(5_000) { message.await() }).startsWith("Wonder's save is Eden's 0100AAAA00000000 folder")
        assertThat(gamesOnScreen { games -> games.single().save?.folderChosen == true }.single().save!!.folderChosen).isTrue()
    }

    /**
     * A Switch game whose save Eden keeps in its console-wide folder and the profile's: its details
     * ask which one Loom syncs, and the answer is the game's, remembered, so they can change it later.
     */
    @Test fun theSyncedFolderQuestionListsBothFoldersAndRemembersTheAnswer() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(4, "switch", "switch", "Nintendo Switch", "Nintendo Switch", 1, "t")))
        db.catalogDao().upsertRoms(listOf(rom(40, 4, "switch", "Wonder", "Wonder.xci")))
        installed(40, 4, "switch/Wonder.xci")
        db.saveDao().upsertSaveKey(SaveKeyEntity("eden", 40, "0100AAAA00000000", SaveKeySource.OWNER, null, 0))
        val users = File(roms, "saves/Eden/user/save/0000000000000000")
        val profile = File(users, "4E431822D6DEFD527268BA35B5C09533/0100AAAA00000000").apply { mkdirs() }
        File(profile, "game_data.sav").writeBytes(ByteArray(64))
        val consoleWide = File(users, "${"0".repeat(32)}/0100AAAA00000000").apply { mkdirs() }
        File(consoleWide, "device.bin").writeBytes(ByteArray(32))
        File(consoleWide, "device.bin").setLastModified(System.currentTimeMillis() - 86_400_000)
        db.saveDao().upsertUnit(SaveUnitEntity(40, "eden", null, null, 0, 0, null, null, SaveSyncState.MOVED, "Eden now keeps it elsewhere", 0))

        vm.selectPlatform(4)
        val game = gamesOnScreen { games -> games.any { it.save != null } }.single()
        vm.askSyncedFolder(game).join()
        val question = vm.saveFolderQuestion.value!!
        assertThat(question.kind).isEqualTo(SaveFolderQuestion.Kind.SYNCED)
        assertThat(question.options.map { it.name to it.fileCount }).containsExactly("Profile folder" to 1, "Console-wide folder" to 1).inOrder()
        assertThat(question.options.map { it.id }).containsExactly(profile.path, consoleWide.path).inOrder()

        val message = async { vm.messages.first() }
        vm.chooseSaveFolder(profile.path).join()
        assertThat(vm.saveFolderQuestion.value).isNull()
        assertThat(db.saveDao().syncedFolder(40)!!.folder).isEqualTo(profile.path)
        assertThat(withTimeout(5_000) { message.await() })
            .startsWith("Loom now syncs Wonder's save from Eden's profile folder, and leaves the other as it is")
        assertThat(gamesOnScreen { games -> games.single().save?.syncedFolderChosen == true }.single().save!!.syncedFolderChosen).isTrue()
        assertThat(consoleWide.resolve("device.bin").exists() && profile.resolve("game_data.sav").exists()).isTrue()
    }

    /** The details' words for a save depend on whether Loom uploads it by itself. */
    @Test fun aGamesSaveKnowsWhetherAutomaticUploadIsOn() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(3, "3ds", "3ds", "Nintendo 3DS", "Nintendo 3DS", 2, "t")))
        db.catalogDao().upsertRoms(listOf(rom(30, 3, "3ds", "Pokemon X", "Pokemon X.3ds")))
        installed(30, 3, "n3ds/Pokemon X.3ds")
        db.saveDao().upsertUnit(SaveUnitEntity(30, "azahar", "/sdmc/data", "f", 2, 4096, 0, "h", SaveSyncState.WRITING, null, 0))
        vm.selectPlatform(3)
        assertThat(gamesOnScreen { games -> games.any { it.save != null } }.single().save!!.autoUpload).isTrue()

        settings.setAutoUploadSaves(false)
        val off = gamesOnScreen { games -> games.any { it.save?.autoUpload == false } }
        assertThat(off.single().save).isEqualTo(GameSave(supported = true, SaveSyncState.WRITING, null, "azahar", autoUpload = false))
    }

    /** A game's details look at its save: the games on screen carry how that look went (here: Loom isn't paired). */
    @Test fun aLookAtASaveReachesTheGamesDetails() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(3, "3ds", "3ds", "Nintendo 3DS", "Nintendo 3DS", 1, "t")))
        db.catalogDao().upsertRoms(listOf(rom(30, 3, "3ds", "Pokemon X", "Pokemon X.3ds")))
        installed(30, 3, "n3ds/Pokemon X.3ds")
        vm.selectPlatform(3)
        val game = gamesOnScreen { games -> games.any { it.save != null } }.single()
        assertThat(game.save!!.look).isNull()

        vm.lookAtSave(game)

        val looked = gamesOnScreen { games -> games.single().save?.look is SaveLook.ThorOnly }.single()
        assertThat(looked.save!!.look).isEqualTo(SaveLook.ThorOnly("Couldn't check with RomM"))
    }

    /** Upload save and Restore server save say they started, then how it went (here: Loom isn't paired). */
    @Test fun saveRequestsSayHowTheyWent() = runBlocking<Unit> {
        val game = gameOn(2)
        val upload = async { vm.messages.take(2).toList() }
        vm.uploadSave(game).join()
        assertThat(withTimeout(5_000) { upload.await() }).containsExactly("Checking Sonic the Hedgehog's save…", "Loom isn't paired with a RomM server yet").inOrder()

        val restore = async { vm.messages.take(2).toList() }
        vm.restoreSave(game).join()
        assertThat(withTimeout(5_000) { restore.await() })
            .containsExactly("Restoring Sonic the Hedgehog's save from RomM…", "Didn't restore Sonic the Hedgehog's save: Loom isn't paired with a RomM server yet").inOrder()
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
