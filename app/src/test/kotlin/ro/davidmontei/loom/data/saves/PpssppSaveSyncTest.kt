package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.SaveKeySource
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.saves.PspDiscId
import ro.davidmontei.loom.storage.saves.SyncDecision
import java.io.File
import java.util.zip.ZipInputStream

/**
 * PSP games' saves in PPSSPP, through the same sync as the other emulators': every folder in the
 * memory stick's SAVEDATA starting with the game's disc ID, zipped as Argosy zips them, with the
 * disc ID from RomM or the game's image.
 */
@RunWith(AndroidJUnit4::class)
class PpssppSaveSyncTest : SaveSyncTestBase() {
    private val saves get() = db.saveDao()
    private suspend fun state(game: PspGame) = saves.unit(game.romId)!!.state
    private suspend fun detail(game: PspGame) = saves.unit(game.romId)!!.detail

    private fun archiveEntries(bytes: ByteArray): List<String> =
        ZipInputStream(bytes.inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }

    /** A CHD, which Loom doesn't read on the Thor: only its magic matters. */
    private val chd = "MComprHD".toByteArray() + ByteArray(120)

    /** Lumines' two save folders, as [LUMINES_HASH] hashes them. */
    private fun writeLuminesSaves() {
        writePspSave("ULUS10064DATA00", "DATA.BIN" to pattern(3000, 7, 1), "ICON0.PNG" to pattern(500, 11, 2), "PARAM.SFO" to SAVE_SFO)
        writePspSave("ULUS10064DATA01", "DATA.BIN" to pattern(1200, 13, 3), "PARAM.SFO" to SAVE_SFO)
    }

    /** The same two folders as Argosy zips them: each folder's directory entry, then its files. */
    private fun argosyArchive(vararg extra: Pair<String, ByteArray?>) = pspArchiveOf(
        "ULUS10064DATA00/" to null,
        "ULUS10064DATA00/DATA.BIN" to pattern(3000, 7, 1),
        "ULUS10064DATA00/ICON0.PNG" to pattern(500, 11, 2),
        "ULUS10064DATA00/PARAM.SFO" to SAVE_SFO,
        "ULUS10064DATA01/" to null,
        "ULUS10064DATA01/DATA.BIN" to pattern(1200, 13, 3),
        "ULUS10064DATA01/PARAM.SFO" to SAVE_SFO,
        *extra,
    )

    private fun folder(name: String) = File(saveData, name)

    @Test fun aPspSaveGoesUpAsOneZipOfItsFolders() = runBlocking<Unit> {
        installPsp(lumines)
        writeLuminesSaves()
        writePspSave("ULUS10065DATA00", "DATA.BIN" to byteArrayOf(9), "PARAM.SFO" to SAVE_SFO) // another game's

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).containsExactly(lumines.romId)
        val upload = romm.uploads().single().url
        assertThat(upload.queryParameter("emulator")).isEqualTo("ppsspp")
        assertThat(upload.queryParameter("slot")).isEqualTo("autosave")
        assertThat(upload.queryParameter("content_hash")).isEqualTo(LUMINES_HASH)
        val head = romm.head(lumines.romId)!!
        assertThat(head.fileName).startsWith("Lumines [")
        assertThat(head.hash).isEqualTo(LUMINES_HASH)
        // Each folder under its own name, as Argosy and Sigil write a PPSSPP save.
        assertThat(archiveEntries(head.bytes)).containsExactly(
            "ULUS10064DATA00/DATA.BIN", "ULUS10064DATA00/ICON0.PNG", "ULUS10064DATA00/PARAM.SFO",
            "ULUS10064DATA01/DATA.BIN", "ULUS10064DATA01/PARAM.SFO",
        ).inOrder()
        assertThat(saves.baseline(lumines.romId, "autosave")!!.unitPath).isEqualTo("${saveData.path}/ULUS10064*")
        val key = saves.saveKey("ppsspp", lumines.romId)!!
        assertThat(key.saveKey).isEqualTo("ULUS10064")
        assertThat(key.source).isEqualTo(SaveKeySource.IMAGE)
        assertThat(state(lumines)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    /** Data a game installs to the memory stick is a copy of the disc, not progress: it never goes up. */
    @Test fun installDataIsLeftOutOfTheUpload() = runBlocking<Unit> {
        installPsp(lumines)
        writeLuminesSaves()
        writePspSave("ULUS10064INSTALL", "PARAM.SFO" to INSTALL_SFO, "DATA.DAT" to ByteArray(8192) { 1 })

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(lumines.romId)

        val head = romm.head(lumines.romId)!!
        assertThat(head.hash).isEqualTo(LUMINES_HASH)
        assertThat(archiveEntries(head.bytes).filter { it.startsWith("ULUS10064INSTALL") }).isEmpty()
    }

    /** Loom can't read a CHD on the Thor; RomM's ID, read when it scanned the game, names its folders. */
    @Test fun rommsDiscIdIsUsedForAChd() = runBlocking<Unit> {
        installPsp(lumines, file = "Lumines.chd", bytes = chd, rommTitleId = "ULUS10064")
        installPsp(patapon, file = "Patapon.chd", bytes = chd)
        writeLuminesSaves()

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).containsExactly(lumines.romId)
        assertThat(romm.head(lumines.romId)!!.hash).isEqualTo(LUMINES_HASH)
        assertThat(saves.saveKey("ppsspp", lumines.romId)!!.source).isEqualTo(SaveKeySource.ROMM)
        assertThat(state(patapon)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        assertThat(detail(patapon)).isEqualTo("RomM didn't read this game's disc ID, and Loom can't read a CHD on the Thor")
    }

    /** RomM and the disc naming different IDs, or the disc naming two: the owner says which folders are the game's. */
    @Test fun rommAndTheDiscDisagreeingAsksForTheFolders() = runBlocking<Unit> {
        installPsp(lumines, rommTitleId = "ULUS10065")
        installPsp(patapon, fixture = "disagree.iso")
        writeLuminesSaves()

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(romm.uploads()).isEmpty()
        assertThat(state(lumines)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        assertThat(report.unsupported.getValue(lumines.romId))
            .isEqualTo("RomM says this is disc ULUS10065 but its image says ULUS10064, so Loom can't tell which its save folders start with")
        assertThat(report.unsupported.getValue(patapon.romId)).isEqualTo(
            "Its image names two disc IDs, ULUS10064 in UMD_DATA.BIN and ULUS10065 in PARAM.SFO, so Loom can't tell which its save folders start with",
        )
        assertThat(saves.saveKey("ppsspp", lumines.romId)).isNull()
    }

    /** A scan every 15 minutes mustn't read every image again: the ID is kept while the image and RomM's ID are as they were. */
    @Test fun aDiscIdReadFromTheImageIsntReadAgain() = runBlocking<Unit> {
        installPsp(lumines)
        writeLuminesSaves()
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(saves.saveKey("ppsspp", lumines.romId)!!.source).isEqualTo(SaveKeySource.IMAGE)

        // Blanked in place, its size and time kept: read again, it would name no disc.
        val image = File(volume, "ROMs/psp/Lumines.iso")
        val at = image.lastModified()
        image.writeBytes(ByteArray(image.length().toInt()))
        image.setLastModified(at)
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(lumines)).isEqualTo(SaveSyncState.IN_SYNC)

        // A replaced image is read again, and the ID it no longer names is forgotten.
        image.setLastModified(at + 60_000)
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(lumines)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        assertThat(saves.saveKey("ppsspp", lumines.romId)).isNull()
    }

    /**
     * RomM's fields hold a disc ID only in its own shape: four capitals and five digits. Its save
     * target names the folders when it is a prefix, before its title ID.
     */
    @Test fun rommsTitleIdOfTheWrongShapeIsIgnored() = runBlocking<Unit> {
        installPsp(lumines, rommTitleId = "ULUS-10064")
        installPsp(patapon, file = "Patapon.chd", bytes = chd, rommTitleId = "0004000000055D00")
        writeLuminesSaves()

        engine().sync(SaveSyncRequest.PUSH)

        assertThat(saves.saveKey("ppsspp", lumines.romId)!!.source).isEqualTo(SaveKeySource.IMAGE)
        assertThat(state(patapon)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        val dao = db.catalogDao()
        installPsp(patapon, file = "Patapon.chd", bytes = chd, rommTitleId = "UCUS98711", rommSaveTarget = "UCUS98662")
        assertThat(PpssppSaves.rommDiscId(dao.rom(patapon.romId)!!).toString()).isEqualTo("UCUS98662")
        installPsp(patapon, file = "Patapon.chd", bytes = chd, rommTitleId = "UCUS98711", rommSaveTarget = "UCUS98662", rommLayout = "file-exact")
        assertThat(PpssppSaves.rommDiscId(dao.rom(patapon.romId)!!).toString()).isEqualTo("UCUS98711")
    }

    /**
     * Argosy's archive restores as Argosy restores it: every folder of the game is replaced, one the
     * Thor lacked is made, and one the archive lacks goes rather than staying behind empty.
     */
    @Test fun anArgosyArchiveRestoresAndEmptiedFoldersGo() = runBlocking<Unit> {
        installPsp(lumines)
        writePspSave("ULUS10064DATA00", "DATA.BIN" to byteArrayOf(1), "PARAM.SFO" to SAVE_SFO)
        writePspSave("ULUS10064DATA02", "DATA.BIN" to byteArrayOf(2), "PARAM.SFO" to SAVE_SFO)
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(lumines.romId, argosyArchive(), emulator = "ppsspp", device = "dev-argosy", fileName = "Lumines.zip")

        assertThat(engine().sync(SaveSyncRequest.PULL).downloaded).containsExactly(lumines.romId)

        assertThat(File(folder("ULUS10064DATA00"), "DATA.BIN").readBytes()).isEqualTo(pattern(3000, 7, 1))
        assertThat(File(folder("ULUS10064DATA01"), "DATA.BIN").readBytes()).isEqualTo(pattern(1200, 13, 3))
        assertThat(folder("ULUS10064DATA02").exists()).isFalse()
        assertThat(saveData.list()!!.filter { it.startsWith(".") }).isEmpty() // no work folder for PPSSPP to list
        assertThat(saves.baseline(lumines.romId, "autosave")!!.localHash).isEqualTo(LUMINES_HASH)
        assertThat(File(volume, ".loom/save-backups/${lumines.romId}").listFiles()!!.single().name).endsWith(" Lumines.zip")
        assertThat(state(lumines)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    @Test fun aRestoreLeavesInstallDataAlone() = runBlocking<Unit> {
        installPsp(lumines)
        val installed = ByteArray(8192) { 1 }
        writePspSave("ULUS10064INSTALL", "PARAM.SFO" to INSTALL_SFO, "DATA.DAT" to installed)
        romm.addSave(lumines.romId, argosyArchive(), emulator = "ppsspp", device = "dev-argosy")

        assertThat(engine().sync(SaveSyncRequest.PULL).downloaded).containsExactly(lumines.romId)

        assertThat(File(folder("ULUS10064INSTALL"), "DATA.DAT").readBytes()).isEqualTo(installed)
        assertThat(File(folder("ULUS10064INSTALL"), "PARAM.SFO").readBytes()).isEqualTo(INSTALL_SFO)
        assertThat(folder("ULUS10064DATA00").isDirectory).isTrue()
        assertThat(folder("ULUS10064DATA01").isDirectory).isTrue()
    }

    /**
     * An archive holding no folder of this game's can never be restored: one conflict saying why.
     * So is the web player's PSP save, whose shape isn't PPSSPP's folders.
     */
    @Test fun anArchiveWithoutThisGamesFoldersIsAConflict() = runBlocking<Unit> {
        installPsp(lumines)
        installPsp(patapon, fixture = "game.pbp", platform = "psp-minis")
        romm.addSave(lumines.romId, pspArchiveOf("ULUS10065DATA00/" to null, "ULUS10065DATA00/DATA.BIN" to byteArrayOf(9)), emulator = "ppsspp")
        romm.addSave(patapon.romId, bytes(3), emulator = "ppsspp", device = null, fileName = "Patapon.srm")

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.downloaded).isEmpty()
        assertThat(report.conflicts).containsExactly(lumines.romId, patapon.romId)
        assertThat(saves.conflict(lumines.romId)!!.reason).isEqualTo(SyncDecision.SERVER_UNREADABLE.name)
        assertThat(detail(lumines)).isEqualTo("RomM's save holds ULUS10065DATA00, not folders starting with ULUS10064; nothing was replaced")
        assertThat(detail(patapon)).isEqualTo("RomM's save for this game isn't an archive of PPSSPP's save folders; nothing was replaced")
        assertThat(saveData.list()).asList().isEmpty()
    }

    /**
     * Argosy before 2.21 zipped a game's installed data with its saves. An archive larger than Loom
     * downloads stays so: one conflict saying what it likely holds, and no backup at each pull.
     */
    @Test fun anArchiveLargerThanLoomDownloadsIsOneConflict() = runBlocking<Unit> {
        installPsp(lumines)
        writeLuminesSaves()
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(lumines.romId, argosyArchive("ULUS10064INSTALL/DATA.DAT" to ByteArray(64 * 1024).also(java.util.Random(1)::nextBytes)), emulator = "ppsspp", device = "dev-argosy")

        repeat(3) { assertThat(engine(maxDownloadBytes = 32 * 1024).sync(SaveSyncRequest.PULL).conflicts).containsExactly(lumines.romId) }

        assertThat(saves.conflict(lumines.romId)!!.reason).isEqualTo(SyncDecision.SERVER_UNREADABLE.name)
        assertThat(detail(lumines))
            .isEqualTo("RomM's save is over 32.0 KB, more than Loom downloads: it likely holds data the game installed; nothing was replaced")
        assertThat(romm.requests.filter { it.url.encodedPath.endsWith("/content") }).hasSize(1)
        assertThat(File(volume, ".loom/save-backups/${lumines.romId}").exists()).isFalse()
    }

    /** PPSSPP Gold keeps its saves on the same memory stick: Argosy's upload from it is PPSSPP's save. */
    @Test fun ppssppGoldsSaveIsTheSameEmulators() = runBlocking<Unit> {
        installPsp(lumines)
        romm.addSave(lumines.romId, argosyArchive(), emulator = "ppsspp_gold", device = "dev-argosy")

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.conflicts).isEmpty()
        assertThat(report.downloaded).containsExactly(lumines.romId)
        assertThat(File(folder("ULUS10064DATA01"), "DATA.BIN").readBytes()).isEqualTo(pattern(1200, 13, 3))

        romm.addSave(lumines.romId, argosyArchive("ULUS10064DATA02/DATA.BIN" to byteArrayOf(5)), emulator = "ppsspp_gold", device = "dev-argosy")
        assertThat(engine().restoreFromServer(lumines.romId)).isEqualTo(ResolveResult.Done)
        assertThat(File(folder("ULUS10064DATA02"), "DATA.BIN").readBytes()).isEqualTo(byteArrayOf(5))
    }

    /** A save outside any slot (an archival copy, an upload without one) is never the slot's: the Thor's save goes up as usual. */
    @Test fun retroArchsUnslottedZipsAreIgnored() = runBlocking<Unit> {
        installPsp(lumines)
        writeLuminesSaves()
        romm.addSave(lumines.romId, pspArchiveOf("ULUS10064DATA00/DATA.BIN" to byteArrayOf(7)), emulator = "ppsspp", slot = null)

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.conflicts).isEmpty()
        assertThat(report.uploaded).containsExactly(lumines.romId)
        assertThat(romm.head(lumines.romId)!!.hash).isEqualTo(LUMINES_HASH)
    }

    /**
     * Two installed games with one disc ID would sync one set of folders: neither does. RomM's IDs
     * count before either game is read, so neither syncs once first.
     */
    @Test fun twoGamesWithOneDiscIdSyncNeither() = runBlocking<Unit> {
        installPsp(lumines)
        installPsp(patapon, file = "Patapon.chd", bytes = chd, rommTitleId = "ULUS10064")
        writeLuminesSaves()

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(romm.uploads()).isEmpty()
        assertThat(report.unsupported.getValue(lumines.romId))
            .isEqualTo("Patapon has the same PSP disc ID (ULUS10064), and Loom syncs a game's save folders for one game only")
        assertThat(report.unsupported.getValue(patapon.romId))
            .isEqualTo("Lumines has the same PSP disc ID (ULUS10064), and Loom syncs a game's save folders for one game only")
    }

    /** Folders far larger than a save are most likely data the game installed with an SFO that doesn't say so. */
    @Test fun aSaveTooLargeSaysTheGameLikelyInstallsData() = runBlocking<Unit> {
        ppssppMaxUnitBytes = 4096
        installPsp(lumines)
        writeLuminesSaves()
        val bytes = saveData.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(romm.uploads()).isEmpty()
        assertThat(report.unsupported.getValue(lumines.romId)).isEqualTo(
            "PPSSPP keeps ${formatBytes(bytes)} in this game's save folders, more than Loom syncs (${formatBytes(4096)}): the game likely installs data there",
        )
        assertThat(state(lumines)).isEqualTo(SaveSyncState.UNSUPPORTED)
    }

    /** Without PPSSPP's memory stick in ROMs/saves, Loom says what to set in PPSSPP; one chosen in Settings is used wherever it is. */
    @Test fun withoutTheMemoryStickItSaysWhatToSetInPpsspp() = runBlocking<Unit> {
        installPsp(lumines)
        File(volume, "ROMs/saves/PPSSPP").deleteRecursively()

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.unsupported.getValue(lumines.romId)).isEqualTo(
            "PPSSPP's memory stick isn't ${File(volume, "ROMs/saves/PPSSPP").path}: in PPSSPP's System settings, set the Memory Stick " +
                "folder there, or choose PPSSPP's folder in Settings",
        )
        val stick = File(volume, "Memory Stick")
        saveData = File(stick, "PSP/SAVEDATA")
        writeLuminesSaves()
        settings.setPpssppMemoryStick(File(stick, "PSP").path) // the PSP folder in it does as well
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(lumines.romId)
        assertThat(romm.head(lumines.romId)!!.hash).isEqualTo(LUMINES_HASH)
    }

    // --- the owner's pick, when Loom can't tell the disc ID ---

    /** A game's folders as one disc ID's, dated [minutesAgo] before [clock]. */
    private fun writePrefix(discId: String, minutesAgo: Long, vararg folders: String) {
        for (name in folders) writePspSave("$discId$name", "DATA.BIN" to byteArrayOf(1, 2, 3), "PARAM.SFO" to SAVE_SFO, minutesAgo = minutesAgo)
    }

    @Test fun aPspGameWithoutADiscIdListsTheMemoryStickPrefixesNewestFirst() = runBlocking<Unit> {
        installPsp(lumines, file = "Lumines.chd", bytes = chd)
        writePrefix("ULUS10064", minutesAgo = 60, "DATA00", "DATA01")
        writePrefix("NPUZ00001", minutesAgo = 5, "SAVE")
        writePspSave("ULES00151INSTALL", "PARAM.SFO" to INSTALL_SFO, "DATA.DAT" to ByteArray(64)) // installed data, nothing to pick
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(lumines)).isEqualTo(SaveSyncState.NEEDS_FOLDER)

        val choice = ppssppSaves().folderChoice(lumines.romId) as FolderChoice.Options

        assertThat(choice.options.map { it.key }).containsExactly("NPUZ00001", "ULUS10064").inOrder()
        val ulus = choice.options.single { it.key == "ULUS10064" }
        assertThat(ulus.folderCount).isEqualTo(2)
        assertThat(ulus.fileCount).isEqualTo(4)
        assertThat(ulus.bytes).isEqualTo(2L * (3 + SAVE_SFO.size))
        assertThat(choice.current).isNull()
    }

    /** Folders another installed game syncs, by its save key or the ID RomM gave it, aren't offered. */
    @Test fun prefixesOtherGamesHaveAreNotOffered() = runBlocking<Unit> {
        installPsp(lumines, file = "Lumines.chd", bytes = chd)
        installPsp(patapon, fixture = "game.pbp", platform = "psp-minis") // NPUZ00001, read from its PBP
        installPsp(PspGame(52, "Daxter"), file = "Daxter.chd", bytes = chd, rommTitleId = "UCUS98618")
        writePrefix("ULUS10064", minutesAgo = 60, "DATA00")
        writePrefix("NPUZ00001", minutesAgo = 5, "SAVE")
        writePrefix("UCUS98618", minutesAgo = 5, "SAVE")
        engine().sync(SaveSyncRequest.PUSH)

        val choice = ppssppSaves().folderChoice(lumines.romId) as FolderChoice.Options

        assertThat(choice.options.map { it.key }).containsExactly("ULUS10064")
    }

    /**
     * A game the owner gave other folders than its RomM ID's syncs those alone: that ID no longer
     * stops another game that has it, nor is it kept from that game's choice.
     */
    @Test fun aPickOverRommsIdLeavesThatIdToTheGameWithIt() = runBlocking<Unit> {
        installPsp(lumines, rommTitleId = "ULUS10064")
        installPsp(patapon, fixture = "disagree.iso", rommTitleId = "ULUS10064") // its image says ULUS10064 and ULUS10065
        writeLuminesSaves()
        writePrefix("ULUS10065", minutesAgo = 5, "DATA00")
        val first = engine().sync(SaveSyncRequest.PUSH)
        assertThat(first.unsupported.getValue(lumines.romId)).startsWith("Patapon has the same PSP disc ID (ULUS10064)")
        assertThat(state(patapon)).isEqualTo(SaveSyncState.NEEDS_FOLDER)

        ppssppSaves().choosePrefix(patapon.romId, PspDiscId("ULUS10065"))

        assertThat((ppssppSaves().folderChoice(lumines.romId) as FolderChoice.Options).options.map { it.key }).contains("ULUS10064")
        val report = engine().sync(SaveSyncRequest.PUSH)
        assertThat(report.unsupported).isEmpty()
        assertThat(report.uploaded).containsExactly(lumines.romId, patapon.romId)
        assertThat(romm.head(lumines.romId)!!.hash).isEqualTo(LUMINES_HASH)
        assertThat(saves.unit(patapon.romId)!!.unitPath).isEqualTo("${saveData.path}/ULUS10065*")
        // Lumines has the ID now, by its own save key: Patapon's choice doesn't offer it.
        assertThat((ppssppSaves().folderChoice(patapon.romId) as FolderChoice.Options).options.map { it.key }).containsExactly("ULUS10065")
    }

    /**
     * The owner's pick is the game's save from then on, and can be changed: Loom looks again at
     * once, and the game's details offer to choose again.
     */
    @Test fun thePickIsRememberedAndCanBeChanged() = runBlocking<Unit> {
        installPsp(lumines, file = "Lumines.chd", bytes = chd)
        writeLuminesSaves()
        writePrefix("NPUZ00001", minutesAgo = 5, "SAVE")
        engine().sync(SaveSyncRequest.PUSH)
        val repository = SaveRepository(engine(), saves, eden(), ppssppSaves(), scope)

        assertThat(repository.choosePrefix(lumines.romId, lumines.title, PspDiscId("NPUZ00001")))
            .isEqualTo("Lumines's saves are PPSSPP's folders starting with NPUZ00001")
        assertThat(repository.observeChosenFolders().first()).containsExactly(lumines.romId)
        assertThat((repository.saveFolderChoice(lumines.romId, "ppsspp") as FolderChoice.Options).current).isEqualTo("NPUZ00001")

        repository.choosePrefix(lumines.romId, lumines.title, PspDiscId("ULUS10064"))
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(lumines.romId)
        assertThat(romm.head(lumines.romId)!!.hash).isEqualTo(LUMINES_HASH)
        val key = saves.saveKey("ppsspp", lumines.romId)!!
        assertThat(key.saveKey).isEqualTo("ULUS10064")
        assertThat(key.source).isEqualTo(SaveKeySource.OWNER)
    }

    /**
     * A pick stands while RomM and the game's image can't say otherwise. Once RomM reads the disc
     * and names another ID, the game asks again rather than sync another game's folders as its own.
     */
    @Test fun aPickTheDiscContradictsAsksAgain() = runBlocking<Unit> {
        installPsp(lumines, file = "Lumines.chd", bytes = chd)
        writeLuminesSaves()
        writePrefix("NPUZ00001", minutesAgo = 5, "SAVE")
        ppssppSaves().choosePrefix(lumines.romId, PspDiscId("NPUZ00001"))
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(lumines)).isEqualTo(SaveSyncState.IN_SYNC)
        val uploads = romm.uploads().size

        installPsp(lumines, file = "Lumines.chd", bytes = chd, rommTitleId = "ULUS10064") // RomM rescanned the game
        engine().sync(SaveSyncRequest.PUSH)

        assertThat(state(lumines)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        assertThat(detail(lumines))
            .isEqualTo("You chose the save folders starting with NPUZ00001, but RomM says it is ULUS10064. Choose its save folders again")
        assertThat(romm.uploads()).hasSize(uploads)
        assertThat((ppssppSaves().folderChoice(lumines.romId) as FolderChoice.Options).current).isEqualTo("NPUZ00001")

        // RomM's own ID stands. What the wrong pick uploaded is RomM's save now: which to keep is the owner's to say.
        ppssppSaves().choosePrefix(lumines.romId, PspDiscId("ULUS10064"))
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(saves.unit(lumines.romId)!!.unitPath).isEqualTo("${saveData.path}/ULUS10064*")
        assertThat(saves.conflict(lumines.romId)!!.reason).isEqualTo(SyncDecision.FIRST_SYNC_DIFFERS.name)
    }

    private companion object {
        /**
         * RomM's hash of Lumines' two save folders, from Python:
         * md5("\n".join(f"{name}:{md5(bytes)}" for name in sorted(names))), with
         * `bytes((i * step + start) % 256 for i in range(size))` for each data file.
         */
        const val LUMINES_HASH = "d9760f10aea00f00a1c997fc294d1194"
    }
}
