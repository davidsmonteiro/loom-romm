package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.data.db.SaveConflictEntity
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.db.TitledConflict
import ro.davidmontei.loom.data.saves.SaveLook
import ro.davidmontei.loom.data.saves.ServerSaveInfo
import ro.davidmontei.loom.data.saves.conflictReason
import ro.davidmontei.loom.data.saves.emulatorName
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.saves.SyncDecision
import ro.davidmontei.loom.storage.saves.SyncOutcome
import ro.davidmontei.loom.ui.components.dateTime
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.lookLine
import ro.davidmontei.loom.ui.library.saveLine
import ro.davidmontei.loom.ui.queue.ConflictItem
import ro.davidmontei.loom.ui.queue.serverSide
import ro.davidmontei.loom.ui.queue.thorSide
import ro.davidmontei.loom.ui.queue.toItem
import java.time.Instant

/** What the screens say about a save: the details' line for each state, and both sides of a conflict. */
class SaveWordingTest {
    private fun line(state: SaveSyncState?, detail: String? = null) = saveLine(GameSave(supported = true, state, detail, "azahar"))

    @Test fun theDetailsSayWhereEachSaveStands() {
        assertThat(line(null)).isEqualTo("Save: not checked yet")
        assertThat(line(SaveSyncState.IN_SYNC)).isEqualTo("Save: in sync with RomM")
        assertThat(line(SaveSyncState.NO_SAVE)).isEqualTo("Save: none yet")
        assertThat(line(SaveSyncState.LOCAL_NEWER)).isEqualTo("Save: newer on the Thor")
        assertThat(line(SaveSyncState.WRITING))
            .isEqualTo("Save: newer on the Thor; Loom uploads it once Azahar has left it alone for a minute")
        assertThat(line(SaveSyncState.SERVER_NEWER)).isEqualTo("Save: newer on RomM")
        assertThat(line(SaveSyncState.LOCAL_MISSING)).isEqualTo("Save: missing on the Thor; RomM has a copy")
        assertThat(line(SaveSyncState.CONFLICT)).isEqualTo("Save: changed on the Thor and on RomM. Choose which to keep.")
        assertThat(saveLine(GameSave(supported = true, SaveSyncState.MOVED, "Eden now keeps this game's save in its profile folder", "eden")))
            .isEqualTo("Save: in two Eden folders, each holding a different save. Choose which one Loom syncs.")
        assertThat(line(SaveSyncState.UNSUPPORTED, "Loom can't read this game's title ID"))
            .isEqualTo("Save: not synced. Loom can't read this game's title ID")
        assertThat(line(SaveSyncState.ERROR, "Couldn't read the save")).isEqualTo("Save: the last sync failed. Couldn't read the save")
    }

    /** How the details' look at a save is going, beside their buttons: nothing once RomM answered. */
    @Test fun theDetailsSayHowTheirLookIsGoing() {
        assertThat(lookLine(null)).isEmpty()
        assertThat(lookLine(SaveLook.Looking)).isEqualTo("Checking the save…")
        assertThat(lookLine(SaveLook.ThorOnly("Couldn't reach RomM"))).isEqualTo("Couldn't reach RomM: checked the Thor's side only")
    }

    /** With automatic upload off nothing uploads by itself: the details don't promise it, and name what does. */
    @Test fun withAutomaticUploadOffTheDetailsNameUploadSave() {
        fun off(state: SaveSyncState) = saveLine(GameSave(supported = true, state, null, "azahar", autoUpload = false))
        assertThat(off(SaveSyncState.WRITING)).isEqualTo("Save: newer on the Thor; Upload save sends it once Azahar has left it alone for a minute")
        assertThat(off(SaveSyncState.LOCAL_NEWER)).isEqualTo("Save: newer on the Thor; Upload save sends it")
        assertThat(off(SaveSyncState.IN_SYNC)).isEqualTo("Save: in sync with RomM")
    }

    @Test fun eachConflictSaysWhy() {
        val reasons = SyncDecision.entries.filter { it.outcome == SyncOutcome.CONFLICT }
            .associateWith { conflictReason(it.name) }
        assertThat(reasons).containsExactly(
            SyncDecision.SERVER_SAYS_CONFLICT, "Both the Thor and RomM have changed this save since the last sync",
            SyncDecision.BOTH_CHANGED, "Both the Thor and RomM have changed this save since the last sync",
            SyncDecision.DISAGREES_WITH_SERVER, "RomM and Loom disagree about which save is newer",
            SyncDecision.UPLOAD_REJECTED, "RomM got a newer save from elsewhere before this one could upload",
            SyncDecision.FIRST_SYNC_DIFFERS, "The Thor and RomM each have a different save, and they were never synced",
            SyncDecision.SERVER_WENT_BACK, "RomM's newest save is older than the one last synced: it may have been deleted",
            SyncDecision.SERVER_SLOT_GONE, "RomM no longer has this game's save",
            SyncDecision.SERVER_DELETED, "This save was deleted on RomM: upload the Thor's again, or leave RomM without it",
            SyncDecision.SERVER_ROLLED_BACK, "RomM's newest save was deleted, so its save is an older one now",
            SyncDecision.FOREIGN_EMULATOR, "RomM's newer save was made by another emulator",
            SyncDecision.SERVER_UNREADABLE, "Loom can't put RomM's save in place of the Thor's",
        )
        assertThat(conflictReason("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW") // a reason recorded by a newer Loom
    }

    /** Each side as Loom found it; RomM's names the emulator and, when RomM says, the device it came from. */
    @Test fun aConflictsSidesSayWhatEachHolds() {
        val item = QueueScreenTest.pokemonX
        assertThat(thorSide(item)).containsExactly("Changed ${dateTime(item.thorModifiedMs!!)}", formatBytes(524_288)).inOrder()
        assertThat(serverSide(item))
            .containsExactly("Uploaded ${dateTime(item.serverUpdatedMs!!)}", "${formatBytes(530_000)} · Azahar", "From Steam Deck").inOrder()

        val elsewhere = item.copy(thorModifiedMs = null, thorBytes = null, serverEmulator = "citra", serverBytes = null, serverDevice = null)
        assertThat(thorSide(elsewhere)).containsExactly("No save")
        assertThat(serverSide(elsewhere)).containsExactly("Uploaded ${dateTime(item.serverUpdatedMs!!)}", "citra").inOrder()
        assertThat(serverSide(item.copy(serverEmulator = null, serverBytes = null))[1]).isEqualTo("emulator unknown")
        assertThat(serverSide(item.copy(serverUpdatedMs = null))).containsExactly("No save")
    }

    /** A conflict as Loom recorded it, with what RomM said of its save (by save id) and whether an answer is running. */
    @Test fun aConflictAsTheQueueListsIt() {
        val recorded = SaveConflictEntity(7, "autosave", SyncDecision.BOTH_CHANGED.name, "aa", 1_792_000_000_000, 524_288,
            serverSaveId = 501, serverHash = "bb", serverUpdatedAt = "2026-10-14T10:00:00Z", serverEmulator = "azahar", detectedAtMs = 0)
        val item = TitledConflict(recorded, "Pokemon X", "azahar")
            .toItem(mapOf(501 to ServerSaveInfo(530_000, "Steam Deck"), 502 to ServerSaveInfo(1, "elsewhere")), busy = setOf(7))
        assertThat(item).isEqualTo(
            ConflictItem(7, "Pokemon X", "Both the Thor and RomM have changed this save since the last sync", 1_792_000_000_000, 524_288,
                Instant.parse("2026-10-14T10:00:00Z").toEpochMilli(), "azahar", 530_000, "Steam Deck", "azahar", skipped = false, busy = true),
        )

        val gone = TitledConflict(recorded.copy(serverSaveId = null, serverUpdatedAt = null, skipped = true), title = null, localEmulator = null)
            .toItem(emptyMap(), busy = emptySet())
        assertThat(gone.title).isEqualTo("ROM 7") // the game has left the catalog
        assertThat(gone.serverHasSave).isFalse()
        assertThat(gone.skipped).isTrue()
        assertThat(gone.busy).isFalse()
        assertThat(gone.serverUnreadable).isFalse()

        // RomM's save Loom can't restore: why, in the words Loom found it in.
        val unreadable = TitledConflict(recorded.copy(reason = SyncDecision.SERVER_UNREADABLE.name, detail = "RomM's save holds ULUS10065DATA00"),
            "Pokemon X", "azahar").toItem(emptyMap(), busy = emptySet())
        assertThat(unreadable.reason).isEqualTo("RomM's save holds ULUS10065DATA00")
        assertThat(unreadable.serverUnreadable).isTrue()
    }

    @Test fun emulatorsAreNamedAsTheUserKnowsThem() {
        assertThat(emulatorName("azahar")).isEqualTo("Azahar")
        assertThat(emulatorName("melonds")).isEqualTo("melonDS")
        assertThat(emulatorName("ppsspp")).isEqualTo("PPSSPP")
        assertThat(emulatorName("ppsspp_gold")).isEqualTo("PPSSPP Gold")
        assertThat(emulatorName("citra")).isEqualTo("citra")
        assertThat(emulatorName(null)).isEqualTo("the emulator")
    }
}
