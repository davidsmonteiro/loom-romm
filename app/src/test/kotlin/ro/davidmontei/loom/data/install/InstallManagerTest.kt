package ro.davidmontei.loom.data.install

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.RomFileEntity
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.data.download.Volumes
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.ui.library.toItem
import java.io.File

@RunWith(AndroidJUnit4::class)
class InstallManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: LoomDatabase
    private lateinit var volume: File
    private lateinit var romsRoot: File
    private lateinit var manager: InstallManager
    private var mounted = true

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LoomDatabase::class.java)
            .allowMainThreadQueries().build()
        volume = tmp.newFolder("9C33-6BBD")
        romsRoot = File(volume, "ROMs").apply { mkdirs() }
        val prefs = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "p.preferences_pb") }
        val settings = SettingsStore(prefs).apply { setRomsRoot(romsRoot.path) }
        val volumes = object : Volumes {
            override fun forPath(path: String) = if (mounted) VolumeInfo("9C33-6BBD", volume, "SD card", true) else null
            override fun isMounted(volumeId: String) = mounted
        }
        manager = InstallManager(db.catalogDao(), settings, MappingRepository(prefs), volumes, nowMs = { 7 })

        val dao = db.catalogDao()
        dao.upsertPlatforms(listOf(
            PlatformEntity(1, "snes", "snes", "SNES", "SNES", 2, "t"),
            PlatformEntity(2, "psx", "psx", "PlayStation", "PlayStation", 1, "t"),
            PlatformEntity(3, "nds", "nds", "DS", "Nintendo DS", 1, "t"),
        ))
        dao.writePage(
            listOf(rom(10, 1, "Chrono Trigger (USA).sfc", 4096), rom(11, 1, "Earthbound (USA).sfc", 3000),
                rom(20, 2, "Final Fantasy VII (USA)", 0, folder = true), rom(30, 3, "Mario Kart DS (USA).zip", 900)),
            listOf(
                RomFileEntity(100, 10, "Chrono Trigger (USA).sfc", "roms/snes", 4096, true, null, null, null, null, null),
                RomFileEntity(110, 11, "Earthbound (USA).sfc", "roms/snes", 3000, true, null, null, null, null, null),
                RomFileEntity(200, 20, "FF7 (Disc 1).cue", "roms/psx/Final Fantasy VII (USA)", 10, true, null, null, null, null, null),
                RomFileEntity(201, 20, "FF7 (Disc 2).cue", "roms/psx/Final Fantasy VII (USA)", 10, true, null, null, null, null, null),
                RomFileEntity(300, 30, "Mario Kart DS (USA).zip", "roms/nds", 900, true, null, null, null, null,
                    """[{"name":"Mario Kart DS (USA).nds","size":2048,"crc_hash":"0","md5_hash":"0","sha1_hash":"0"}]"""),
            ),
        )
    }

    @After fun tearDown() { db.close(); scope.cancel() }

    private fun rom(id: Int, platformId: Int, fsName: String, size: Long, folder: Boolean = false) = RomEntity(
        id = id, platformId = platformId, platformSlug = "", platformFsSlug = "", title = fsName, searchKey = fsName.lowercase(),
        sortKey = fsName.lowercase(), fsName = fsName, fsNameNoExt = fsName.substringBeforeLast('.'),
        fsExtension = fsName.substringAfterLast('.', ""), sizeBytes = size, hasSimpleSingleFile = !folder,
        hasNestedSingleFile = false, hasMultipleFiles = folder, coverSmall = null, coverLarge = null, regions = "",
        updatedAt = "t", missingFromFs = false,
    )

    private fun put(rel: String, size: Int): File = File(romsRoot, rel).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size)) }

    /** A ROM that is one archive, with the members RomM lists for it (null when it lists none). */
    private suspend fun archiveRom(id: Int, platformId: Int, fileName: String, members: String?) = db.catalogDao().writePage(
        listOf(rom(id, platformId, fileName, 900)),
        listOf(RomFileEntity(id * 10, id, fileName, "roms/x", 900, true, null, null, null, null, members)),
    )

    private fun members(vararg named: Pair<String, Int>) =
        named.joinToString(",", "[", "]") { (name, size) -> """{"name":"$name","size":$size,"crc_hash":"0","md5_hash":"0","sha1_hash":"0"}""" }

    @Test fun adoptsGamesAlreadyOnTheCard() = runBlocking<Unit> {
        put("snes/Chrono Trigger (USA).sfc", 4096)
        put("snes/Earthbound (USA).sfc", 1234) // same name, different dump: not adopted
        put("psx/Final Fantasy VII (USA).m3u/Final Fantasy VII (USA).m3u", 30)
        put("nds/Mario Kart DS (USA).nds", 2048) // unzipped by hand earlier

        val result = manager.reconcile()

        assertThat(result.adopted).isEqualTo(3)
        val dao = db.catalogDao()
        assertThat(dao.install(10)!!.source).isEqualTo(InstallSource.ADOPTED)
        assertThat(dao.install(10)!!.verifiedBy).isEqualTo(VerifiedBy.SIZE)
        assertThat(dao.install(11)).isNull()
        assertThat(dao.install(20)!!.relPath).isEqualTo("psx/Final Fantasy VII (USA).m3u")
        assertThat(dao.install(20)!!.kind).isEqualTo(InstallKind.DIRECTORY)
        assertThat(dao.install(20)!!.verifiedBy).isEqualTo(VerifiedBy.NAME) // a folder: no size to compare
        assertThat(dao.install(30)!!.relPath).isEqualTo("nds/Mario Kart DS (USA).nds")
    }

    /**
     * Found on the Thor: after Loom's data was lost, a game it had unpacked wasn't recognised. A lone
     * file out of an archive is named after the game, not after the file inside.
     */
    @Test fun adoptsAGameLoomUnpackedUnderTheNameLoomGaveIt() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(4, "ps2", "ps2", "PS2", "PlayStation 2", 2, "t")))
        archiveRom(40, 4, "Super Bust-A-Move (USA).7z", members("SLUS-20115 (1.00).bin" to 4096))
        archiveRom(41, 4, "Ico (USA).7z", members("SCUS-97113.iso" to 8192))
        put("ps2/Super Bust-A-Move (USA).bin", 4096)
        put("ps2/Ico (USA).iso", 1000) // the right name, but not the size RomM gives: another dump

        assertThat(manager.reconcile().adopted).isEqualTo(1)
        val install = db.catalogDao().install(40)!!
        assertThat(install.relPath).isEqualTo("ps2/Super Bust-A-Move (USA).bin")
        assertThat(install.bytes).isEqualTo(4096)
        assertThat(install.verifiedBy).isEqualTo(VerifiedBy.SIZE)
        assertThat(db.catalogDao().install(41)).isNull()
    }

    /**
     * Found on the Thor: RomM listed nothing inside "Pokemon X….zip", so after Loom's data was lost
     * neither the name nor the size of the .3ds it had unpacked could be predicted. Only on platforms
     * set to unpack, where Loom would have made it.
     */
    @Test fun adoptsByNameAGameUnpackedFromAnArchiveRommListsNothingIn() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(5, "3ds", "3ds", "3DS", "Nintendo 3DS", 1, "t")))
        archiveRom(50, 5, "Pokemon X (Europe) (En,Ja,Fr,De,Es,It,Ko).zip", null)
        archiveRom(51, 2, "Gran Turismo (USA).zip", "[]")
        archiveRom(52, 1, "Super Mario World (USA).zip", null) // SNES keeps archives: Loom never made this .sfc
        put("n3ds/Pokemon X (Europe) (En,Ja,Fr,De,Es,It,Ko).3ds", 2048)
        put("psx/Gran Turismo (USA)/Gran Turismo (USA).cue", 10)
        put("snes/Super Mario World (USA).sfc", 512)

        assertThat(manager.reconcile().adopted).isEqualTo(2)
        val dao = db.catalogDao()
        val pokemon = dao.install(50)!!
        assertThat(pokemon.relPath).isEqualTo("n3ds/Pokemon X (Europe) (En,Ja,Fr,De,Es,It,Ko).3ds")
        assertThat(pokemon.bytes).isEqualTo(2048)
        assertThat(pokemon.verifiedBy).isEqualTo(VerifiedBy.NAME)
        assertThat(dao.observeGame(50).first()!!.toItem().matchedByName).isTrue()
        assertThat(dao.install(51)!!.relPath).isEqualTo("psx/Gran Turismo (USA)")
        assertThat(dao.install(51)!!.kind).isEqualTo(InstallKind.DIRECTORY)
        assertThat(dao.install(52)).isNull()
    }

    @Test fun byNameNothingIsAdoptedWhenTwoEntriesFit() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(5, "3ds", "3ds", "3DS", "Nintendo 3DS", 2, "t")))
        archiveRom(50, 5, "Pokemon X (Europe).zip", null)
        archiveRom(53, 5, "Pokemon Y (Europe).zip", null)
        put("n3ds/Pokemon X (Europe).3ds", 2048)
        put("n3ds/Pokemon X (Europe).cia", 1024) // which one did Loom make?
        put("n3ds/Pokemon Y (Europe).zip", 5) // the archive itself (another copy) isn't what it was unpacked to
        put("n3ds/Pokemon Y (Europe).3ds", 2048)

        assertThat(manager.reconcile().adopted).isEqualTo(1)
        assertThat(db.catalogDao().install(50)).isNull()
        assertThat(db.catalogDao().install(53)!!.relPath).isEqualTo("n3ds/Pokemon Y (Europe).3ds")
    }

    @Test fun forgetsInstallsDeletedInAFileManagerButNotWhenTheCardIsOut() = runBlocking<Unit> {
        val dao = db.catalogDao()
        dao.upsertInstall(InstallEntity(10, "9C33-6BBD", romsRoot.path, "snes/Chrono Trigger (USA).sfc",
            "snes/chrono trigger (usa).sfc", 1, InstallKind.FILE, 4096, VerifiedBy.SHA1, InstallSource.LOOM, null, 1))

        mounted = false
        assertThat(manager.reconcile().forgotten).isEqualTo(0)
        assertThat(dao.install(10)).isNotNull()

        mounted = true
        assertThat(manager.reconcile().forgotten).isEqualTo(1)
        assertThat(dao.install(10)).isNull()
        assertThat(dao.rom(10)).isNotNull() // catalog entry stays
    }

    @Test fun offloadRemovesFilesKeepsCatalogAndLeavesNoTrash() = runBlocking<Unit> {
        put("psx/Final Fantasy VII (USA).m3u/FF7 (Disc 1).cue", 10)
        put("psx/Final Fantasy VII (USA).m3u/Final Fantasy VII (USA).m3u", 30)
        val dao = db.catalogDao()
        dao.upsertInstall(InstallEntity(20, "9C33-6BBD", romsRoot.path, "psx/Final Fantasy VII (USA).m3u",
            "psx/final fantasy vii (usa).m3u", 2, InstallKind.DIRECTORY, 40, VerifiedBy.SHA1, InstallSource.LOOM, null, 1))

        val result = manager.offload(20)

        assertThat(result).isEqualTo(OffloadResult.Removed(40))
        assertThat(File(romsRoot, "psx/Final Fantasy VII (USA).m3u").exists()).isFalse()
        assertThat(dao.install(20)).isNull()
        assertThat(dao.rom(20)).isNotNull()
        assertThat(File(volume, ".loom-staging/trash").listFiles().orEmpty()).isEmpty()
    }

    @Test fun offloadRefusesPathsOutsideTheRomsRoot() = runBlocking<Unit> {
        val outside = File(volume, "Android/precious.txt").apply { parentFile!!.mkdirs(); writeText("keep me") }
        db.catalogDao().upsertInstall(InstallEntity(11, "9C33-6BBD", romsRoot.path, "../Android/precious.txt",
            "x", 1, InstallKind.FILE, 7, VerifiedBy.SIZE, InstallSource.ADOPTED, null, 1))
        assertThat(manager.offload(11)).isInstanceOf(OffloadResult.Failed::class.java)
        assertThat(outside.readText()).isEqualTo("keep me")
    }

    @Test fun offloadWhileUnmountedFails() = runBlocking<Unit> {
        put("snes/Chrono Trigger (USA).sfc", 4096)
        db.catalogDao().upsertInstall(InstallEntity(10, "9C33-6BBD", romsRoot.path, "snes/Chrono Trigger (USA).sfc",
            "k", 1, InstallKind.FILE, 4096, VerifiedBy.SIZE, InstallSource.ADOPTED, null, 1))
        mounted = false
        assertThat(manager.offload(10)).isInstanceOf(OffloadResult.Failed::class.java)
        assertThat(db.catalogDao().install(10)).isNotNull()
    }
}
