package ro.davidmontei.loom.data.saves

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.data.download.Volumes
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.storage.saves.SaveHasher
import ro.davidmontei.loom.storage.saves.SaveRestorer
import ro.davidmontei.loom.storage.saves.SaveRoot
import ro.davidmontei.loom.storage.saves.SaveZipper
import ro.davidmontei.loom.storage.saves.ThreeDsTitleId
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Thor as save sync sees it: the SD card `6365-3230` with `ROMs/n3ds` holding the games and
 * Azahar's user directory, Eden's and melonDS's save folders and PPSSPP's memory stick in
 * `ROMs/saves`, a paired Loom, and [FakeRomm] behind a MockWebServer.
 */
abstract class SaveSyncTestBase {
    @get:Rule val tmp = TemporaryFolder()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()
    protected lateinit var romm: FakeRomm
    protected lateinit var db: LoomDatabase
    protected lateinit var settings: SettingsStore
    protected lateinit var credentials: CredentialStore
    protected lateinit var volume: File
    protected lateinit var n3ds: File
    /** Eden's save folder, moved to the card: `ROMs/saves/Eden`, with one profile. */
    protected lateinit var eden: File
    /** melonDS's save folder, as the owner sets it in melonDS: `ROMs/saves/melonDS`. */
    protected lateinit var melonDs: File
    /** PPSSPP's SAVEDATA on its memory stick, as the owner sets it in PPSSPP: `ROMs/saves/PPSSPP/PSP/SAVEDATA`. */
    protected lateinit var saveData: File

    /** The Thor's clock. Save files are dated [settle] minutes before it. */
    protected var clock: Instant = Instant.parse("2026-10-14T12:00:00Z")
    /** Runs between the two hashes of the quiet check: where an emulator "writes" in a test. */
    protected var onPause: () -> Unit = {}
    protected var mover: SaveRestorer.FileMover = SaveRestorer.FileMover.Rename
    protected var mounted = true
    private val lock = Mutex()

    protected val volumes = object : Volumes {
        override fun forPath(path: String) = if (mounted) VolumeInfo("6365-3230", volume, "SD card", true) else null
        override fun isMounted(volumeId: String) = mounted
    }

    private val cipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(blob: String) = blob
    }

    @Before fun setUpThor() = runBlocking {
        romm = FakeRomm(tmp.newFolder("server").toPath())
        server.dispatcher = romm
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LoomDatabase::class.java)
            .allowMainThreadQueries().build()
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        settings.setServer(ServerConfig(server.url("/")), "5.3.1")
        settings.setOnboardingComplete(true)
        credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") }, cipher, scope)
        pair(deviceId = "dev-thor")
        // The device Loom's token is bound to. Not under Loom's own identifier: with an unbound
        // token, Loom finds no device unless a test lists one.
        romm.devices += romm.device("dev-thor", "loom-thor")
        volume = tmp.newFolder("6365-3230")
        n3ds = File(volume, "ROMs/n3ds")
        listOf("config", "log", "nand", "states", "sysdata").forEach { File(n3ds, it).mkdirs() }
        File(n3ds, "sdmc/Nintendo 3DS/$ZEROS/$ZEROS/title").mkdirs()
        eden = File(volume, "ROMs/saves/Eden")
        File(eden, "user/save/0000000000000000/$PROFILE").mkdirs()
        File(eden, "system/save/8000000000000010").mkdirs()
        melonDs = File(volume, "ROMs/saves/melonDS").apply { mkdirs() }
        saveData = File(volume, "ROMs/saves/PPSSPP/PSP/SAVEDATA").apply { mkdirs() }
        File(volume, "ROMs/saves/PPSSPP/PSP/SYSTEM").mkdirs()
        File(volume, "ROMs/saves/PPSSPP/PSP/SYSTEM/ppsspp.ini").writeText("[General]\n")
        db.catalogDao().upsertPlatforms(listOf(
            PlatformEntity(1, "3ds", "3ds", "Nintendo 3DS", "Nintendo 3DS", 2, "t"),
            PlatformEntity(2, "switch", "switch", "Nintendo Switch", "Nintendo Switch", 2, "t"),
            PlatformEntity(3, "nds", "nds", "Nintendo DS", "Nintendo DS", 2, "t"),
            PlatformEntity(4, "nintendo-dsi", "nintendo-dsi", "Nintendo DSi", "Nintendo DSi", 2, "t"),
            PlatformEntity(5, "psp", "psp", "PlayStation Portable", "PlayStation Portable", 2, "t"),
            PlatformEntity(6, "psp-minis", "psp-minis", "PlayStation minis", "PlayStation minis", 2, "t"),
        ))
    }

    @After fun tearDownThor() {
        db.close()
        server.close()
        scope.cancel()
    }

    protected suspend fun pair(deviceId: String?) {
        credentials.save(PairedCredentials(server.url("/"), "rmm_x", deviceId, "david", null))
        credentials.current.filterNotNull().first { it.deviceId == deviceId }
    }

    /** The largest Switch save Loom restores (and uploads), smaller in tests that need to go past it. */
    protected var edenMaxUnitBytes = EdenSaves.MAX_UNIT_BYTES

    protected fun eden() = EdenSaves(settings, db.catalogDao(), db.saveDao(), now = { clock.toEpochMilli() }, maxUnitBytes = edenMaxUnitBytes)

    protected fun melonDsSaves() = MelonDsSaves(settings, db.catalogDao())

    /** The largest PSP save Loom restores (and uploads), smaller in tests that need to go past it. */
    protected var ppssppMaxUnitBytes = SaveZipper.MAX_UNIT_BYTES

    protected fun ppssppSaves() = PpssppSaves(settings, db.catalogDao(), db.saveDao(), now = { clock.toEpochMilli() }, maxUnitBytes = ppssppMaxUnitBytes)

    protected fun resolver() = SaveUnitResolver(settings, volumes, eden(), melonDsSaves(), ppssppSaves())

    protected fun engine(chunkSize: Int = 500, maxDownloadBytes: Long = SaveSyncEngine.MAX_DOWNLOAD_BYTES) = SaveSyncEngine(
        db.catalogDao(), db.saveDao(), ActiveServer(settings, credentials, OkHttpClient(), scope), credentials, settings,
        resolver(), volumes, File(tmp.root, "cache"),
        now = { clock }, pause = { onPause() }, restorer = SaveRestorer(mover = mover), chunkSize = chunkSize,
        maxDownloadBytes = maxDownloadBytes, lock = lock,
    )

    // --- games and saves ---

    protected class Game(val romId: Int, val title: String, val titleId: ThreeDsTitleId)

    protected val pokemonX = Game(7, "Pokemon X", ThreeDsTitleId(0x0004000000055D00L))
    protected val omegaRuby = Game(8, "Pokemon Omega Ruby", ThreeDsTitleId(0x000400000011C400L))

    /** Installs [game] as a cartridge image in ROMs/n3ds whose header names [cartridgeId]. */
    protected suspend fun install(
        game: Game, cartridgeId: ThreeDsTitleId? = game.titleId, file: String = "${game.title}.3ds",
        rommTitleId: String? = null, rommSaveTarget: String? = null,
    ) {
        val rom = File(n3ds, file)
        rom.parentFile!!.mkdirs()
        val header = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN)
        if (cartridgeId != null) {
            header.position(0x100); header.put("NCSD".toByteArray()); header.putLong(0x108, cartridgeId.value)
        }
        rom.writeBytes(header.array())
        val dao = db.catalogDao()
        dao.upsertRoms(listOf(RomEntity(
            id = game.romId, platformId = 1, platformSlug = "3ds", platformFsSlug = "3ds", title = game.title,
            searchKey = game.title.lowercase(), sortKey = game.title.lowercase(), fsName = file, fsNameNoExt = file.substringBeforeLast('.'),
            fsExtension = file.substringAfterLast('.'), sizeBytes = 512, hasSimpleSingleFile = true, hasNestedSingleFile = false,
            hasMultipleFiles = false, coverSmall = null, coverLarge = null, regions = "", updatedAt = "t", missingFromFs = false,
            titleId = rommTitleId, saveTarget = rommSaveTarget,
        )))
        dao.upsertInstall(InstallEntity(game.romId, "6365-3230", File(volume, "ROMs").path, "n3ds/$file", "n3ds/${file.lowercase()}", 1,
            InstallKind.FILE, 512, VerifiedBy.SIZE, InstallSource.ADOPTED, null, 0))
    }

    protected fun saveFolder(game: Game): File =
        File(n3ds, "sdmc/Nintendo 3DS/$ZEROS/$ZEROS/title/${game.titleId.high}/${game.titleId.low}/data")

    protected fun unit(game: Game) = listOf(SaveRoot("data", saveFolder(game).toPath()))

    /** Azahar's save as it writes it, last touched [minutesAgo] before [clock]. */
    protected fun writeSave(game: Game, main: ByteArray, minutesAgo: Long = 10): String {
        val data = saveFolder(game)
        File(data, "00000001").mkdirs()
        File(data, "00000001/main").writeBytes(main)
        File(data, "00000001.metadata").writeBytes("META".toByteArray() + ByteArray(28))
        settle(game, minutesAgo)
        return SaveHasher.hashUnit(unit(game))!!
    }

    protected fun settle(game: Game, minutesAgo: Long = 10) {
        saveFolder(game).walkTopDown().filter { it.isFile }.forEach {
            Files.setLastModifiedTime(it.toPath(), FileTime.from(clock.minus(Duration.ofMinutes(minutesAgo))))
        }
    }

    protected fun localMain(game: Game): ByteArray = File(saveFolder(game), "00000001/main").readBytes()

    /** A save archive as Loom (or Argosy) uploads it, holding `data/` with this `main`. */
    protected fun archiveOf(main: ByteArray): ByteArray {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "archive")
        File(dir.toFile(), "data/00000001").mkdirs()
        File(dir.toFile(), "data/00000001/main").writeBytes(main)
        File(dir.toFile(), "data/00000001.metadata").writeBytes("META".toByteArray() + ByteArray(28))
        val zip = dir.resolve("unit.zip")
        SaveZipper.zip(listOf(SaveRoot("data", dir.resolve("data"))), zip)
        return Files.readAllBytes(zip)
    }

    protected fun backups(game: Game): List<File> =
        File(volume, ".loom/save-backups/${game.romId}").listFiles().orEmpty().filter { it.name.endsWith(".zip") }.sortedBy { it.name }

    protected fun bytes(seed: Int, size: Int = 4096) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    // --- Switch games in Eden ---

    /** A Switch game installed from one of core-storage's synthetic images, whose made-up title ID it holds. */
    protected class SwitchGame(val romId: Int, val title: String, val titleId: String, val fixture: String)

    protected val wonder = SwitchGame(30, "Super Mario Bros Wonder", "0100A0B00C0D0000", "game.xci")
    protected val arceus = SwitchGame(31, "Pokemon Legends Arceus", "01002B300C1D2000", "game.nsp")

    /** The synthetic images and their made-up header key, from core-storage's test resources: no console's key. */
    protected fun switchFixture(name: String): File =
        listOf(File("../core-storage/src/test/resources/switch/$name"), File("core-storage/src/test/resources/switch/$name")).first { it.isFile }

    protected val testKey: String get() = switchFixture("test-header-key.txt").readText().trim()

    /** Puts the owner's prod.keys at the root of the ROMs folder, with [headerKey] among other (made-up) keys. */
    protected fun putProdKeys(headerKey: String = testKey, at: File = File(volume, "ROMs/prod.keys")) {
        at.parentFile!!.mkdirs()
        at.writeText("header_kek_source = ${"ab".repeat(16)}\nheader_key = $headerKey\nkey_area_key_application_00 = ${"cd".repeat(16)}\n")
    }

    /** Installs [game] in ROMs/switch from its fixture image, as Loom would after a download. */
    protected suspend fun installSwitch(game: SwitchGame, file: String = "${game.title}.${game.fixture.substringAfterLast('.')}",
                                        fixture: String = game.fixture) {
        val rom = File(volume, "ROMs/switch/$file")
        rom.parentFile!!.mkdirs()
        switchFixture(fixture).copyTo(rom, overwrite = true)
        val dao = db.catalogDao()
        dao.upsertRoms(listOf(RomEntity(
            id = game.romId, platformId = 2, platformSlug = "switch", platformFsSlug = "switch", title = game.title,
            searchKey = game.title.lowercase(), sortKey = game.title.lowercase(), fsName = file, fsNameNoExt = file.substringBeforeLast('.'),
            fsExtension = file.substringAfterLast('.'), sizeBytes = rom.length(), hasSimpleSingleFile = true, hasNestedSingleFile = false,
            hasMultipleFiles = false, coverSmall = null, coverLarge = null, regions = "", updatedAt = "t", missingFromFs = false,
        )))
        dao.upsertInstall(InstallEntity(game.romId, "6365-3230", File(volume, "ROMs").path, "switch/$file", "switch/${file.lowercase()}", 2,
            InstallKind.FILE, rom.length(), VerifiedBy.SIZE, InstallSource.ADOPTED, null, 0))
    }

    protected fun edenFolder(game: SwitchGame, profile: String = PROFILE): File = File(eden, "user/save/0000000000000000/$profile/${game.titleId}")

    protected fun edenUnit(game: SwitchGame) = listOf(SaveRoot(game.titleId, edenFolder(game).toPath()))

    /** Eden's save of [game] as it writes it, last touched [minutesAgo] before [clock]. */
    protected fun writeEdenSave(game: SwitchGame, data: ByteArray, minutesAgo: Long = 10): String {
        val folder = edenFolder(game)
        folder.mkdirs()
        File(folder, "game_data.sav").writeBytes(data)
        File(folder, "option_data.sav").writeBytes("OPTIONS".toByteArray())
        folder.walkTopDown().filter { it.isFile }.forEach {
            Files.setLastModifiedTime(it.toPath(), FileTime.from(clock.minus(Duration.ofMinutes(minutesAgo))))
        }
        return SaveHasher.hashUnit(edenUnit(game))!!
    }

    /** A Switch save archive as Loom (or Argosy) uploads it: the title folder under its own name. */
    protected fun edenArchiveOf(game: SwitchGame, data: ByteArray): ByteArray {
        val dir = Files.createTempDirectory(tmp.root.toPath(), "archive")
        File(dir.toFile(), "${game.titleId}/game_data.sav").apply { parentFile!!.mkdirs() }.writeBytes(data)
        File(dir.toFile(), "${game.titleId}/option_data.sav").writeBytes("OPTIONS".toByteArray())
        val zip = dir.resolve("unit.zip")
        SaveZipper.zip(listOf(SaveRoot(game.titleId, dir.resolve(game.titleId))), zip)
        return Files.readAllBytes(zip)
    }

    // --- DS games in melonDS ---

    protected class DsGame(val romId: Int, val title: String)

    protected val marioDs = DsGame(40, "New Super Mario Bros")
    protected val zeldaDs = DsGame(41, "The Legend of Zelda Phantom Hourglass")

    /**
     * Installs [game] as [file] in the folder of [platform] (`nds`, or `nintendo-dsi` in `ndsi`):
     * a ROM whose header has a cartridge's game code, or DSiWare's game code and category.
     */
    protected suspend fun installDs(game: DsGame, file: String = "${game.title}.nds", platform: String = "nds", dsiWare: Boolean = false) {
        val folder = if (platform == "nds") "nds" else "ndsi"
        val rom = File(volume, "ROMs/$folder/$file")
        rom.parentFile!!.mkdirs()
        val header = ByteBuffer.allocate(0x1000).order(ByteOrder.LITTLE_ENDIAN)
        header.position(0x0C); header.put((if (dsiWare) "KQ9E" else "A2DE").toByteArray())
        if (dsiWare) header.putInt(0x234, 0x0003_0004)
        rom.writeBytes(header.array())
        val platformId = if (platform == "nds") 3 else 4
        val dao = db.catalogDao()
        dao.upsertRoms(listOf(RomEntity(
            id = game.romId, platformId = platformId, platformSlug = platform, platformFsSlug = platform, title = game.title,
            searchKey = game.title.lowercase(), sortKey = game.title.lowercase(), fsName = file, fsNameNoExt = file.substringBeforeLast('.'),
            fsExtension = file.substringAfterLast('.'), sizeBytes = rom.length(), hasSimpleSingleFile = true, hasNestedSingleFile = false,
            hasMultipleFiles = false, coverSmall = null, coverLarge = null, regions = "", updatedAt = "t", missingFromFs = false,
        )))
        dao.upsertInstall(InstallEntity(game.romId, "6365-3230", File(volume, "ROMs").path, "$folder/$file", "$folder/${file.lowercase()}",
            platformId, InstallKind.FILE, rom.length(), VerifiedBy.SIZE, InstallSource.ADOPTED, null, 0))
    }

    /** melonDS's save of a game installed as [rom]: the ROM's name with its last extension replaced by .sav. */
    protected fun dsSave(rom: String): File = File(melonDs, rom.replaceAfterLast('.', "sav", "$rom.sav"))

    protected fun dsSave(game: DsGame): File = dsSave("${game.title}.nds")

    /** melonDS's save of [game] as it writes it, last touched [minutesAgo] before [clock]. */
    protected fun writeDsSave(game: DsGame, data: ByteArray, minutesAgo: Long = 10): File = dsSave(game).apply {
        writeBytes(data)
        Files.setLastModifiedTime(toPath(), FileTime.from(clock.minus(Duration.ofMinutes(minutesAgo))))
    }

    // --- PSP games in PPSSPP ---

    protected class PspGame(val romId: Int, val title: String)

    protected val lumines = PspGame(50, "Lumines")
    protected val patapon = PspGame(51, "Patapon")

    /** core-storage's synthetic PSP images (game.iso is ULUS10064 in UMD_DATA.BIN and PARAM.SFO): no game's data. */
    protected fun pspFixture(name: String): File =
        listOf(File("../core-storage/src/test/resources/psp/$name"), File("core-storage/src/test/resources/psp/$name")).first { it.isFile }

    /**
     * Installs [game] as [file] in the folder of [platform] (`psp`, or `psp-minis` in `pspminis`),
     * a copy of the [fixture] image or these [bytes]; with RomM's [rommTitleId], and its
     * [rommSaveTarget] in [rommLayout].
     */
    protected suspend fun installPsp(
        game: PspGame, fixture: String = "game.iso", file: String = "${game.title}.${fixture.substringAfterLast('.')}",
        bytes: ByteArray? = null, platform: String = "psp",
        rommTitleId: String? = null, rommSaveTarget: String? = null, rommLayout: String? = rommSaveTarget?.let { "folder-prefix" },
    ) {
        val folder = if (platform == "psp") "psp" else "pspminis"
        val rom = File(volume, "ROMs/$folder/$file")
        rom.parentFile!!.mkdirs()
        if (bytes != null) rom.writeBytes(bytes) else pspFixture(fixture).copyTo(rom, overwrite = true)
        val platformId = if (platform == "psp") 5 else 6
        val dao = db.catalogDao()
        dao.upsertRoms(listOf(RomEntity(
            id = game.romId, platformId = platformId, platformSlug = platform, platformFsSlug = platform, title = game.title,
            searchKey = game.title.lowercase(), sortKey = game.title.lowercase(), fsName = file, fsNameNoExt = file.substringBeforeLast('.'),
            fsExtension = file.substringAfterLast('.'), sizeBytes = rom.length(), hasSimpleSingleFile = true, hasNestedSingleFile = false,
            hasMultipleFiles = false, coverSmall = null, coverLarge = null, regions = "", updatedAt = "t", missingFromFs = false,
            titleId = rommTitleId, saveTarget = rommSaveTarget, saveTargetLayout = rommLayout,
        )))
        dao.upsertInstall(InstallEntity(game.romId, "6365-3230", File(volume, "ROMs").path, "$folder/$file", "$folder/${file.lowercase()}",
            platformId, InstallKind.FILE, rom.length(), VerifiedBy.SIZE, InstallSource.ADOPTED, null, 0))
    }

    /** One of PPSSPP's save folders in SAVEDATA, as it writes it, last touched [minutesAgo] before [clock]. */
    protected fun writePspSave(folder: String, vararg files: Pair<String, ByteArray>, minutesAgo: Long = 10): File =
        File(saveData, folder).apply {
            mkdirs()
            for ((name, data) in files) File(this, name).writeBytes(data)
            walkTopDown().filter { it.isFile }.forEach { Files.setLastModifiedTime(it.toPath(), FileTime.from(clock.minus(Duration.ofMinutes(minutesAgo)))) }
        }

    /** A save archive as Argosy uploads PPSSPP's saves: each folder's directory entry, then its files. */
    protected fun pspArchiveOf(vararg entries: Pair<String, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (data != null) zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** `bytes((i * step + start) % 256 for i in range(size))`, as Python writes the hashed save files. */
    protected fun pattern(size: Int, step: Int, start: Int) = ByteArray(size) { (it * step + start).toByte() }

    companion object {
        /**
         * A stand-in PARAM.SFO for a save PPSSPP wrote: Argosy and Loom tell a save from installed
         * data by these two keys alone. [INSTALL_SFO] has neither, as an install's doesn't.
         */
        val SAVE_SFO = "\u0000PSF SAVEDATA_FILE_LIST SAVEDATA_PARAMS".toByteArray()
        val INSTALL_SFO = "\u0000PSF TITLE SAVEDATA_TITLE".toByteArray()

        const val ZEROS = "00000000000000000000000000000000"

        /** The Eden profile on the Thor. */
        const val PROFILE = "4E431822D6DEFD527268BA35B5C09533"
    }
}
