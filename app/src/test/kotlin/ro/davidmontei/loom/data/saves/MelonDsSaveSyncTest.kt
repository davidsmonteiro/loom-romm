package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.romm.RommJson
import ro.davidmontei.loom.storage.saves.SaveHasher
import ro.davidmontei.loom.storage.saves.SyncDecision
import java.io.File

/**
 * DS games' saves in melonDS, through the same sync as 3DS and Switch saves: one raw `.sav` per
 * game in `ROMs/saves/melonDS`, uploaded as itself, as Argosy and RomM's web player keep it.
 */
@RunWith(AndroidJUnit4::class)
class MelonDsSaveSyncTest : SaveSyncTestBase() {
    private val saves get() = db.saveDao()
    private suspend fun state(game: DsGame) = saves.unit(game.romId)!!.state
    private suspend fun detail(game: DsGame) = saves.unit(game.romId)!!.detail

    /** What Loom told RomM when confirming it holds [saveId]. */
    private fun downloadedBody(saveId: Int) = romm.requests.single { it.url.encodedPath == "/api/saves/$saveId/downloaded" }
        .let { RommJson.parseToJsonElement(it.body!!.utf8()).jsonObject }

    private val save1 = bytes(1, 8192)

    @Test fun aDsSaveGoesUpRawAsItsStemDotSav() = runBlocking<Unit> {
        installDs(marioDs)
        writeDsSave(marioDs, save1)

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).containsExactly(marioDs.romId)
        val upload = romm.uploads().single().url
        assertThat(upload.queryParameter("emulator")).isEqualTo("melonds")
        assertThat(upload.queryParameter("slot")).isEqualTo("autosave")
        assertThat(upload.queryParameter("content_hash")).isEqualTo(SAVE1_MD5)
        val head = romm.head(marioDs.romId)!!
        assertThat(head.fileName).startsWith("New Super Mario Bros [") // RomM adds the time to the name Loom sends
        assertThat(head.fileName).endsWith("].sav")
        assertThat(head.bytes).isEqualTo(save1) // the file itself, not a zip of it
        assertThat(head.hash).isEqualTo(SAVE1_MD5)
        assertThat(saves.baseline(marioDs.romId, "autosave")!!.unitPath).isEqualTo(dsSave(marioDs).path)
        assertThat(state(marioDs)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    /** melonDS makes an empty .sav when a game first runs: that is no save, and never goes up. */
    @Test fun melonDsEmptyPlaceholderIsNoSave() = runBlocking<Unit> {
        installDs(marioDs)
        writeDsSave(marioDs, ByteArray(0))

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).isEmpty()
        assertThat(romm.uploads()).isEmpty()
        assertThat(state(marioDs)).isEqualTo(SaveSyncState.NO_SAVE)
    }

    @Test fun aRestoreReplacesTheEmptyPlaceholder() = runBlocking<Unit> {
        installDs(marioDs)
        writeDsSave(marioDs, ByteArray(0))
        romm.addSave(marioDs.romId, save1, emulator = "melonds", fileName = "New Super Mario Bros.sav")

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.downloaded).containsExactly(marioDs.romId)
        assertThat(dsSave(marioDs).readBytes()).isEqualTo(save1)
        assertThat(melonDs.list()).asList().containsExactly("New Super Mario Bros.sav") // no work folder left behind
        assertThat(state(marioDs)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    /**
     * Argosy appends 28 bytes to a save from a game played in hardcore mode. They come off before
     * melonDS gets the save, yet the game stays in sync: RomM is told the hash it holds, and the
     * Thor's save is compared with what Loom put there.
     */
    @Test fun argosysHardcoreTrailerIsStrippedAndTheGameStaysInSync() = runBlocking<Unit> {
        romm.rommVersion = "5.4.0"
        installDs(marioDs)
        val save = bytes(2, 8192)
        val argosys = romm.addSave(marioDs.romId, save + ARGOSY_TRAILER, emulator = "melonds", device = "dev-argosy",
            fileName = "New Super Mario Bros.sav")
        assertThat(argosys.hash).isEqualTo(SAVE2_WITH_TRAILER_MD5)

        assertThat(engine().sync(SaveSyncRequest.PULL).downloaded).containsExactly(marioDs.romId)

        assertThat(dsSave(marioDs).readBytes()).isEqualTo(save)
        assertThat(SaveHasher.md5(dsSave(marioDs).toPath())).isEqualTo(SAVE2_MD5)
        assertThat(downloadedBody(argosys.id)["content_hash"]!!.jsonPrimitive.content).isEqualTo(SAVE2_WITH_TRAILER_MD5)
        val baseline = saves.baseline(marioDs.romId, "autosave")!!
        assertThat(baseline.localHash).isEqualTo(SAVE2_MD5)
        assertThat(baseline.serverHash).isEqualTo(SAVE2_WITH_TRAILER_MD5)

        val again = engine().sync(SaveSyncRequest.CHECK)

        assertThat(again.conflicts).isEmpty()
        assertThat(romm.lastAnswer(marioDs.romId)).isEqualTo("no_op")
        assertThat(state(marioDs)).isEqualTo(SaveSyncState.IN_SYNC)
        assertThat(romm.uploads()).isEmpty()
    }

    /** RomM's web player keeps a DS save as `<ROM>.srm`, raw, under melonDS's id: it is melonDS's save. */
    @Test fun theWebPlayersSrmRestoresAsMelonDs() = runBlocking<Unit> {
        installDs(marioDs)
        romm.addSave(marioDs.romId, save1, emulator = "melonds", device = null, fileName = "New Super Mario Bros.srm")

        assertThat(engine().sync(SaveSyncRequest.PULL).downloaded).containsExactly(marioDs.romId)

        assertThat(dsSave(marioDs).readBytes()).isEqualTo(save1)
        assertThat(state(marioDs)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    /**
     * DeSmuME's save carries a footer melonDS would read as part of the save: it is another
     * emulator's, and not even Keep server puts it in melonDS's place.
     */
    @Test fun aDesmumeSaveIsAnotherEmulatorsAndNeverRestored() = runBlocking<Unit> {
        installDs(marioDs)
        val dsv = save1 + ByteArray(106) + "|-DESMUME SAVE-|".toByteArray()
        romm.addSave(marioDs.romId, dsv, emulator = "desmume", fileName = "New Super Mario Bros.dsv")

        engine().sync(SaveSyncRequest.PULL)
        assertThat(saves.conflict(marioDs.romId)!!.reason).isEqualTo(SyncDecision.FOREIGN_EMULATOR.name)

        val result = engine().resolveConflict(marioDs.romId, ConflictChoice.KEEP_SERVER) as ResolveResult.Failed

        assertThat(result.reason).isEqualTo("RomM's save is DeSmuME's, whose footer melonDS would read as part of the save; nothing was replaced")
        assertThat(dsSave(marioDs).exists()).isFalse()
        assertThat(saves.conflict(marioDs.romId)!!.reason).isEqualTo(SyncDecision.SERVER_UNREADABLE.name)
        assertThat(detail(marioDs)).isEqualTo(result.reason)
    }

    @Test fun anArchiveOnRommIsAConflictNotARestore() = runBlocking<Unit> {
        installDs(marioDs)
        romm.addSave(marioDs.romId, archiveOf(save1), emulator = "melonds")

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.downloaded).isEmpty()
        assertThat(report.conflicts).containsExactly(marioDs.romId)
        assertThat(saves.conflict(marioDs.romId)!!.reason).isEqualTo(SyncDecision.SERVER_UNREADABLE.name)
        assertThat(detail(marioDs)).isEqualTo("RomM's save for this game is an archive, and melonDS keeps one file; nothing was replaced")
        assertThat(dsSave(marioDs).exists()).isFalse()
    }

    /** Keep both parks the Thor's save outside the slot as what it is, a .sav. */
    @Test fun keepBothNamesTheThorsCopyDotSav() = runBlocking<Unit> {
        installDs(marioDs)
        writeDsSave(marioDs, save1)
        romm.addSave(marioDs.romId, bytes(2, 8192), emulator = "melonds", fileName = "New Super Mario Bros.sav")
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(saves.conflict(marioDs.romId)!!.reason).isEqualTo(SyncDecision.FIRST_SYNC_DIFFERS.name)

        assertThat(engine().resolveConflict(marioDs.romId, ConflictChoice.KEEP_BOTH)).isEqualTo(ResolveResult.Done)

        val archived = romm.saves.single { it.slot == null }
        assertThat(archived.fileName).isEqualTo("New Super Mario Bros (Thor 2026-10-14 12-00-00 UTC).sav")
        assertThat(archived.bytes).isEqualTo(save1)
        assertThat(dsSave(marioDs).readBytes()).isEqualTo(bytes(2, 8192))
    }

    /** A DS save is backed up as itself before RomM's replaces it, so it can be put back by hand. */
    @Test fun aDsBackupIsTheSavItself() = runBlocking<Unit> {
        installDs(marioDs)
        writeDsSave(marioDs, save1)
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(marioDs.romId, bytes(2, 8192), emulator = "melonds", device = "dev-argosy", fileName = "New Super Mario Bros.sav")

        assertThat(engine().sync(SaveSyncRequest.PULL).downloaded).containsExactly(marioDs.romId)

        val backup = File(volume, ".loom/save-backups/${marioDs.romId}").listFiles()!!.single()
        assertThat(backup.name).endsWith(" New Super Mario Bros.sav")
        assertThat(backup.readBytes()).isEqualTo(save1)
        assertThat(dsSave(marioDs).readBytes()).isEqualTo(bytes(2, 8192))
    }

    /**
     * A DS game and a DSi one installed under one name, but for case: melonDS keeps one save for
     * both, as the SD card can't tell the names apart, so neither syncs.
     */
    @Test fun twoInstallsWithOneSaveNameSyncNeither() = runBlocking<Unit> {
        installDs(marioDs, file = "Mario.nds")
        installDs(zeldaDs, file = "MARIO.dsi", platform = "nintendo-dsi")
        File(melonDs, "Mario.sav").apply { writeBytes(save1); setLastModified(clock.minusSeconds(600).toEpochMilli()) }

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(romm.uploads()).isEmpty()
        assertThat(report.unsupported.getValue(marioDs.romId))
            .isEqualTo("The Legend of Zelda Phantom Hourglass has the same melonDS save file (Mario.sav), and Loom syncs a save for one game only")
        assertThat(report.unsupported.getValue(zeldaDs.romId))
            .isEqualTo("New Super Mario Bros has the same melonDS save file (MARIO.sav), and Loom syncs a save for one game only")
    }

    /** DSiWare keeps its save in melonDS's NAND, never in a .sav: one named as a cartridge is doesn't stop it. */
    @Test fun aDsiWareTitleNamedAsACartridgeDoesntStopIt() = runBlocking<Unit> {
        installDs(marioDs, file = "Foo (USA).nds")
        installDs(zeldaDs, file = "Foo (USA).nds", platform = "nintendo-dsi", dsiWare = true)
        File(melonDs, "Foo (USA).sav").apply { writeBytes(save1); setLastModified(clock.minusSeconds(600).toEpochMilli()) }

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).containsExactly(marioDs.romId)
        assertThat(romm.head(marioDs.romId)!!.bytes).isEqualTo(save1)
        assertThat(report.unsupported.keys).containsExactly(zeldaDs.romId)
        assertThat(report.unsupported.getValue(zeldaDs.romId)).isEqualTo(MelonDsSaves.DSIWARE)
    }

    @Test fun dsiWareSaysWhyItIsntSynced() = runBlocking<Unit> {
        installDs(marioDs, platform = "nintendo-dsi", dsiWare = true)

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.unsupported.getValue(marioDs.romId)).isEqualTo(MelonDsSaves.DSIWARE)
        assertThat(state(marioDs)).isEqualTo(SaveSyncState.UNSUPPORTED)
    }

    /** melonDS saves next to the ROM by default: without its folder in ROMs/saves, Loom says what to set in melonDS. */
    @Test fun withoutMelonDsFolderItSaysWhatToSetInMelonDs() = runBlocking<Unit> {
        installDs(marioDs)
        melonDs.deleteRecursively()

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.unsupported.getValue(marioDs.romId)).isEqualTo(
            "melonDS's saves aren't in ${melonDs.path}: in melonDS's Save Files settings, turn off Save next to ROM file and " +
                "set the Save file directory there, or choose its folder in Settings",
        )

        // A folder chosen in Settings is used wherever it is.
        val elsewhere = File(volume, "melonDS saves").apply { mkdirs() }
        File(elsewhere, "New Super Mario Bros.sav").writeBytes(save1)
        settings.setMelonDsSaveDir(elsewhere.path)
        File(elsewhere, "New Super Mario Bros.sav").setLastModified(clock.minusSeconds(600).toEpochMilli())
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(marioDs.romId)
        assertThat(romm.head(marioDs.romId)!!.bytes).isEqualTo(save1)
    }

    private companion object {
        /** Python's `hashlib.md5` of `bytes(1, 8192)`, `bytes(2, 8192)`, and of the latter with Argosy's trailer. */
        const val SAVE1_MD5 = "8c509d4800314b1ce7e0390d76a8972d"
        const val SAVE2_MD5 = "d43c084561356ff7937913053e744beb"
        const val SAVE2_WITH_TRAILER_MD5 = "9f47fcee60217ccde7019bcbdfd7a5c6"

        /** `{"h":true,"v":1}`, its length and `ARGOSY 01 00` (Argosy's `appendHardcoreTrailer`). */
        val ARGOSY_TRAILER = """{"h":true,"v":1}""".toByteArray() + byteArrayOf(16, 0, 0, 0) + "ARGOSY".toByteArray() + byteArrayOf(1, 0)
    }
}
