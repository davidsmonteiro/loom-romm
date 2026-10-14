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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * The Thor as save sync sees it: the SD card `6365-3230` with `ROMs/n3ds` holding the games and
 * Azahar's user directory, a paired Loom, and [FakeRomm] behind a MockWebServer.
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
        volume = tmp.newFolder("6365-3230")
        n3ds = File(volume, "ROMs/n3ds")
        listOf("config", "log", "nand", "states", "sysdata").forEach { File(n3ds, it).mkdirs() }
        File(n3ds, "sdmc/Nintendo 3DS/$ZEROS/$ZEROS/title").mkdirs()
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(1, "3ds", "3ds", "Nintendo 3DS", "Nintendo 3DS", 2, "t")))
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

    protected fun engine(chunkSize: Int = 500, maxDownloadBytes: Long = SaveSyncEngine.MAX_DOWNLOAD_BYTES) = SaveSyncEngine(
        db.catalogDao(), db.saveDao(), ActiveServer(settings, credentials, OkHttpClient(), scope), credentials, settings,
        SaveUnitResolver(settings, volumes), volumes, File(tmp.root, "cache"),
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

    companion object {
        const val ZEROS = "00000000000000000000000000000000"
    }
}
