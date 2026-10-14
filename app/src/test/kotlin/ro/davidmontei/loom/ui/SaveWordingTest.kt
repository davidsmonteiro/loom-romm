package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.saves.conflictReason
import ro.davidmontei.loom.data.saves.emulatorName
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.saves.SyncDecision
import ro.davidmontei.loom.storage.saves.SyncOutcome
import ro.davidmontei.loom.ui.components.dateTime
import ro.davidmontei.loom.ui.library.GameSave
import ro.davidmontei.loom.ui.library.saveLine
import ro.davidmontei.loom.ui.queue.serverSide
import ro.davidmontei.loom.ui.queue.thorSide

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
        assertThat(line(SaveSyncState.UNSUPPORTED, "Loom can't read this game's title ID"))
            .isEqualTo("Save: not synced. Loom can't read this game's title ID")
        assertThat(line(SaveSyncState.ERROR, "Couldn't read the save")).isEqualTo("Save: the last sync failed. Couldn't read the save")
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
            SyncDecision.FOREIGN_EMULATOR, "RomM's newer save was made by another emulator",
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

    @Test fun emulatorsAreNamedAsTheUserKnowsThem() {
        assertThat(emulatorName("azahar")).isEqualTo("Azahar")
        assertThat(emulatorName("citra")).isEqualTo("citra")
        assertThat(emulatorName(null)).isEqualTo("the emulator")
    }
}
