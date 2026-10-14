package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.db.SwitchTitleSource
import ro.davidmontei.loom.storage.saves.SaveHasher
import ro.davidmontei.loom.storage.saves.SaveRestorer
import ro.davidmontei.loom.storage.saves.SwitchTitleId
import ro.davidmontei.loom.storage.saves.SyncDecision
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Switch games' saves in Eden, through the same sync as 3DS saves: Eden's save folder moved to
 * `ROMs/saves/Eden`, the title ID read from the game's image with the owner's header key, or the
 * folder the owner chose.
 */
@RunWith(AndroidJUnit4::class)
class EdenSaveSyncTest : SaveSyncTestBase() {
    private val saves get() = db.saveDao()
    private suspend fun state(game: SwitchGame) = saves.unit(game.romId)!!.state
    private suspend fun detail(game: SwitchGame) = saves.unit(game.romId)!!.detail

    private fun archiveEntries(bytes: ByteArray): List<String> =
        ZipInputStream(bytes.inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }

    @Test fun aSwitchSaveGoesUpUnderItsTitleFolder() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        val hash = writeEdenSave(wonder, bytes(1))

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).containsExactly(wonder.romId)
        val upload = romm.uploads().single().url
        assertThat(upload.queryParameter("emulator")).isEqualTo("eden")
        assertThat(upload.queryParameter("slot")).isEqualTo("autosave")
        val head = romm.head(wonder.romId)!!
        assertThat(head.fileName).startsWith("Super Mario Bros Wonder [")
        assertThat(head.hash).isEqualTo(hash)
        // The archive Argosy writes for a Switch save: the title folder under its own name.
        assertThat(archiveEntries(head.bytes)).containsExactly("0100A0B00C0D0000/game_data.sav", "0100A0B00C0D0000/option_data.sav")
        assertThat(saves.baseline(wonder.romId, "autosave")!!.unitPath).isEqualTo(edenFolder(wonder).path)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    /** A scan every 15 minutes mustn't read every XCI again: the ID is kept while the image is unchanged. */
    @Test fun theTitleIdIsReadOnceWhileTheImageIsUnchanged() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val recorded = saves.switchTitle(wonder.romId)!!
        assertThat(recorded.titleId).isEqualTo("0100A0B00C0D0000")
        assertThat(recorded.source).isEqualTo(SwitchTitleSource.IMAGE)

        File(volume, "ROMs/prod.keys").delete() // nothing to read the image with now
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.IN_SYNC)

        // A replaced image is read again (and without the key, Loom asks).
        val image = File(volume, "ROMs/switch/Super Mario Bros Wonder.xci")
        image.setLastModified(image.lastModified() + 60_000)
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
    }

    /** The key is the owner's: it is read for the scan only, and lands nowhere Loom writes, sends or logs. */
    @Test fun theKeyIsNeverStoredUploadedOrLogged() = runBlocking<Unit> {
        ShadowLog.clear()
        putProdKeys()
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.IN_SYNC)

        val key = testKey.lowercase()
        val raw = String(key.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.ISO_8859_1)
        fun holdsKey(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).let { it.contains(key, ignoreCase = true) || it.contains(raw) }
        fun holdsKey(text: String?) = text != null && holdsKey(text.toByteArray(Charsets.ISO_8859_1))
        // The database: what Loom recorded of the game.
        val title = saves.switchTitle(wonder.romId)!!
        assertThat(listOf(title.titleId, title.imageFingerprint).none(::holdsKey)).isTrue()
        val unit = saves.unit(wonder.romId)!!
        assertThat(listOf(unit.detail, unit.unitPath, unit.fingerprint, unit.localHash).none(::holdsKey)).isTrue()
        // Every file Loom wrote (settings, scratch space, the card's backups) and RomM's stored saves.
        val files = tmp.root.walkTopDown().filter { it.isFile && it.name != "prod.keys" }.toList()
        assertThat(files.filter { holdsKey(it.readBytes()) }).isEmpty()
        // Everything sent to RomM, and the log.
        assertThat(romm.requests.filter { holdsKey(it.body?.toByteArray() ?: ByteArray(0)) || holdsKey(it.url.toString()) }).isEmpty()
        assertThat(ShadowLog.getLogs().filter { holdsKey(it.msg) || holdsKey(it.throwable?.toString()) }).isEmpty()
    }

    @Test fun withoutProdKeysTheOwnerChoosesTheSaveFolder() = runBlocking<Unit> {
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        writeEdenSave(arceus, bytes(2), minutesAgo = 5) // another game's folder, changed later

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.unsupported.getValue(wonder.romId)).isEqualTo(EdenSaves.NO_KEYS)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        assertThat(romm.uploads()).isEmpty()

        val choice = eden().folderChoice(wonder.romId) as EdenFolderChoice.Options
        assertThat(choice.folders.map { it.titleId.toString() }).containsExactly(arceus.titleId, wonder.titleId).inOrder() // newest first
        assertThat(choice.current).isNull()

        eden().chooseFolder(wonder.romId, SwitchTitleId.parse(wonder.titleId)!!)
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(wonder.romId)
        assertThat(archiveEntries(romm.head(wonder.romId)!!.bytes)).contains("0100A0B00C0D0000/game_data.sav")
        assertThat(saves.switchTitle(wonder.romId)!!.source).isEqualTo(SwitchTitleSource.OWNER)
        assertThat((eden().folderChoice(wonder.romId) as EdenFolderChoice.Options).current.toString()).isEqualTo(wonder.titleId)
    }

    /** A prod.keys without a usable header_key, or whose key opens nothing, doesn't hide a good one in the Switch folder. */
    @Test fun aProdKeysWithoutAGoodKeyDoesntHideOneThatWorks() = runBlocking<Unit> {
        File(volume, "ROMs/prod.keys").writeText("key_area_key_application_00 = ${"cd".repeat(16)}\n")
        putProdKeys(at = File(volume, "ROMs/switch/prod.keys"))
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(wonder.romId)

        putProdKeys(headerKey = switchFixture("other-header-key.txt").readText().trim()) // ROMs/prod.keys now has the wrong key
        installSwitch(arceus)
        writeEdenSave(arceus, bytes(2))
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(arceus.romId)
    }

    /** An update beside a game file Loom can't read: the game may be that file, so the owner is asked, not told it has no save. */
    @Test fun anUpdateBesideAnUnreadableGameAsks() = runBlocking<Unit> {
        putProdKeys()
        val folder = File(volume, "ROMs/switch/Wonder").apply { mkdirs() }
        File(folder, "Wonder.xci").writeBytes(ByteArray(0x4000) { (it * 7).toByte() })
        switchFixture("update.nsp").copyTo(File(folder, "Wonder [Update].nsp"))
        val dao = db.catalogDao()
        installSwitch(wonder)
        dao.upsertInstall(dao.install(wonder.romId)!!.copy(relPath = "switch/Wonder", pathKey = "switch/wonder", kind = InstallKind.DIRECTORY))

        engine().sync(SaveSyncRequest.PUSH)

        assertThat(state(wonder)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        assertThat(detail(wonder)).isEqualTo("Loom can't read its title ID: not an XCI or NSP image, and the file it can read is an update")
    }

    /** Once another game has a folder, it isn't offered for this one. */
    @Test fun aFolderAnotherGameHasIsntOffered() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(arceus)
        writeEdenSave(arceus, bytes(2))
        writeEdenSave(wonder, bytes(1))
        engine().sync(SaveSyncRequest.PUSH) // arceus's ID is read from its image
        File(volume, "ROMs/prod.keys").delete()
        installSwitch(wonder)
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.NEEDS_FOLDER)

        val choice = eden().folderChoice(wonder.romId) as EdenFolderChoice.Options
        assertThat(choice.folders.map { it.titleId.toString() }).containsExactly(wonder.titleId)
    }

    @Test fun aKeyThatDoesntOpenTheImageAsksToo() = runBlocking<Unit> {
        putProdKeys(headerKey = switchFixture("other-header-key.txt").readText().trim())
        installSwitch(wonder)
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.NEEDS_FOLDER)
        assertThat(detail(wonder)).isEqualTo(EdenSaves.WRONG_KEY)
    }

    /** The owner may keep prod.keys in the Switch folder instead. */
    @Test fun prodKeysInTheSwitchFolderWorksToo() = runBlocking<Unit> {
        putProdKeys(at = File(volume, "ROMs/switch/prod.keys"))
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(wonder.romId)
    }

    @Test fun anUpdateKeepsNoSaveOfItsOwn() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder, file = "Wonder [Update].nsp", fixture = "update.nsp")
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.UNSUPPORTED)
        assertThat(detail(wonder)).isEqualTo("This is an update of 0100C0FFEE000000; the game itself keeps the save")
    }

    /** Two games syncing one folder would restore each other's saves over it: neither does. */
    @Test fun twoGamesWithOneSaveFolderSyncNeither() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        installSwitch(SwitchGame(32, "Super Mario Bros Wonder (copy)", wonder.titleId, "game.xci"))
        writeEdenSave(wonder, bytes(1))

        engine().sync(SaveSyncRequest.PUSH)
        engine().sync(SaveSyncRequest.PUSH)

        assertThat(state(wonder)).isEqualTo(SaveSyncState.UNSUPPORTED)
        assertThat(detail(wonder)).contains("has the same Eden save folder (0100A0B00C0D0000)")
        assertThat(saves.unit(32)!!.state).isEqualTo(SaveSyncState.UNSUPPORTED)
        assertThat(romm.uploads()).hasSize(1) // only the first run's, before Loom knew of the second game
    }

    @Test fun severalProfilesWaitForTheOwnersChoice() = runBlocking<Unit> {
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        File(eden, "user/save/0000000000000000/0123456789ABCDEF0123456789ABCDEF").mkdirs()

        // Whose saves comes first: a game's folder can only be picked from a profile's.
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(detail(wonder)).isEqualTo(EdenSaves.SEVERAL_PROFILES)
        assertThat(eden().folderChoice(wonder.romId)).isEqualTo(EdenFolderChoice.Unavailable(EdenSaves.SEVERAL_PROFILES))

        putProdKeys()
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.UNSUPPORTED)
        assertThat(detail(wonder)).isEqualTo(EdenSaves.SEVERAL_PROFILES)
        assertThat(romm.uploads()).isEmpty()

        settings.setEdenProfile(PROFILE)
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(wonder.romId)
    }

    @Test fun aGameEdenHasntSavedYetHasNoSave() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.NO_SAVE)
        assertThat(saves.unit(wonder.romId)!!.unitPath).isEqualTo(edenFolder(wonder).path)
    }

    @Test fun withoutEdensSaveFolderOnTheCardItSaysWhere() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        eden.deleteRecursively()
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(wonder)).isEqualTo(SaveSyncState.UNSUPPORTED)
        assertThat(detail(wonder)).startsWith("Eden's saves aren't in ${File(volume, "ROMs/saves/Eden").path}")
    }

    /** Eden's folder chosen in Settings, e.g. kept elsewhere on the card. */
    @Test fun aChosenSaveFolderIsUsed() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        val elsewhere = File(volume, "Emulation/Eden")
        File(eden, "user").renameTo(File(elsewhere, "user").apply { parentFile!!.mkdirs() })
        eden = elsewhere
        writeEdenSave(wonder, bytes(1))
        settings.setEdenSaveDir(elsewhere.path)
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(wonder.romId)
        assertThat(saves.unit(wonder.romId)!!.unitPath).startsWith(elsewhere.path)
    }

    // --- downloads: the same safety as 3DS saves ---

    @Test fun restoringReplacesTheTitleFolderAfterABackup() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        val old = writeEdenSave(wonder, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val newer = romm.addSave(wonder.romId, edenArchiveOf(wonder, bytes(2)), emulator = "eden")

        assertThat(engine().restoreFromServer(wonder.romId)).isEqualTo(ResolveResult.Done)

        assertThat(File(edenFolder(wonder), "game_data.sav").readBytes()).isEqualTo(bytes(2))
        val backups = File(volume, ".loom/save-backups/${wonder.romId}").listFiles().orEmpty().filter { it.name.endsWith(".zip") }
        assertThat(backups.map { SaveHasher.hashFile(it.toPath()) }).containsExactly(old)
        assertThat(romm.requests.single { it.url.encodedPath.endsWith("/content") }.url.queryParameter("optimistic")).isEqualTo("false")
        assertThat(romm.paths()).contains("POST /api/saves/${newer.id}/downloaded")
        assertThat(saves.baseline(wonder.romId, "autosave")!!.serverSaveId).isEqualTo(newer.id)
        // No work folder left in the profile, where Eden lists its title folders.
        assertThat(edenFolder(wonder).parentFile!!.list()!!.filter { it.startsWith(".") }).isEmpty()
    }

    @Test fun aRestoreThatFailsHalfwayLeavesEdensSaveAsItWas() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(wonder.romId, edenArchiveOf(wonder, bytes(2)), emulator = "eden")
        var moves = 0
        mover = SaveRestorer.FileMover { from, to ->
            if (++moves == 3) throw java.io.IOException("card removed")
            SaveRestorer.FileMover.Rename.move(from, to)
        }

        assertThat(engine().restoreFromServer(wonder.romId)).isInstanceOf(ResolveResult.Failed::class.java)
        assertThat(File(edenFolder(wonder), "game_data.sav").readBytes()).isEqualTo(bytes(1))
        assertThat(File(edenFolder(wonder), "option_data.sav").exists()).isTrue()
    }

    /** RomM's newer save came from another Switch emulator: never applied alone. */
    @Test fun aSaveFromAnotherEmulatorIsAConflict() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        romm.addSave(wonder.romId, edenArchiveOf(wonder, bytes(2)), emulator = "ryujinx")

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.conflicts).containsExactly(wonder.romId)
        assertThat(saves.conflict(wonder.romId)!!.reason).isEqualTo(SyncDecision.FOREIGN_EMULATOR.name)
        assertThat(edenFolder(wonder).exists()).isFalse()
    }

    /** A newer Eden save on RomM waits for the user, who is told to close Eden. */
    @Test fun aNewerSaveOnRommSaysToCloseEden() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(wonder.romId, edenArchiveOf(wonder, bytes(2)), emulator = "eden")

        assertThat(engine().sync(SaveSyncRequest.PUSH).serverNewer).containsExactly(wonder.romId)
        assertThat(detail(wonder)).isEqualTo("RomM has a newer save; close Eden, then restore it")
        assertThat(File(edenFolder(wonder), "game_data.sav").readBytes()).isEqualTo(bytes(1))
    }

    @Test fun aSaveEdenIsWritingWaits() = runBlocking<Unit> {
        putProdKeys()
        installSwitch(wonder)
        writeEdenSave(wonder, bytes(1), minutesAgo = 0)
        assertThat(engine().sync(SaveSyncRequest.PUSH).waiting).containsExactly(wonder.romId)
        assertThat(detail(wonder)).isEqualTo("Eden is writing this save; Loom uploads it once it has been quiet for a minute")
    }
}
