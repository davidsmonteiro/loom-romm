package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ro.davidmontei.loom.data.db.RecentSaveSync
import ro.davidmontei.loom.data.db.SaveDao
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.db.SaveUnitEntity
import ro.davidmontei.loom.data.db.TitledConflict
import ro.davidmontei.loom.data.db.TitledSaveUnit
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.storage.saves.AzaharSaveLocator
import ro.davidmontei.loom.storage.saves.EdenSaveLocator
import ro.davidmontei.loom.storage.saves.SwitchTitleId

/**
 * What the screens show of saves, and what the user asks for one game, each answer in words for
 * the screen's message line. Requests go through [SaveSyncEngine], so they wait for a run in
 * progress and never overlap one, and they run in [scope]: leaving the screen doesn't stop a
 * restore half way.
 */
class SaveRepository(
    private val engine: SaveSyncEngine,
    private val saves: SaveDao,
    private val eden: EdenSaves,
    private val scope: CoroutineScope,
) {

    /** Every installed game Loom syncs saves for, by ROM id. */
    fun observeGames(): Flow<Map<Int, SaveUnitEntity>> = engine.observeGames().map { units -> units.associateBy { it.romId } }

    fun observeConflicts(): Flow<List<TitledConflict>> = saves.observeTitledConflicts()

    /** Games whose save can come back from RomM: newer there, or gone from the Thor. */
    fun observeRestorable(): Flow<List<TitledSaveUnit>> =
        saves.observeUnitsIn(listOf(SaveSyncState.SERVER_NEWER.name, SaveSyncState.LOCAL_MISSING.name))

    fun observeRecentSyncs(): Flow<List<RecentSaveSync>> = saves.observeRecentSyncs(RECENT_SYNCS)

    suspend fun serverSaveInfo(romId: Int, saveId: Int): ServerSaveInfo? = engine.serverSaveInfo(romId, saveId)

    /** The Switch games whose Eden save folder the owner chose: they can choose another. */
    fun observeChosenFolders(): Flow<Set<Int>> = saves.observeOwnerChosenSwitchTitles().map { it.toSet() }

    /** The Eden save folders the owner can pick [romId]'s from. */
    suspend fun saveFolderChoice(romId: Int): EdenFolderChoice = eden.folderChoice(romId)

    /**
     * The owner's answer to "which Eden save folder is this game's?": remembered, then Loom looks
     * at the game's save again (moving nothing), so its details show where it stands.
     */
    suspend fun chooseSaveFolder(romId: Int, title: String, titleId: SwitchTitleId): String = request {
        eden.chooseFolder(romId, titleId)
        val report = try {
            engine.sync(SaveSyncRequest.CHECK.copy(romIds = setOf(romId)))
        } catch (e: RommException) {
            null
        }
        "$title's save is Eden's $titleId folder" + when {
            report == null || report.unavailable != null || romId in report.failed -> ". Upload save in its details looks at it again"
            romId in report.unsupported -> ", but it isn't synced: ${report.unsupported.getValue(romId)}"
            engine.observeGame(romId).first()?.state == SaveSyncState.NO_SAVE -> ". Neither the Thor nor RomM has a save of it yet"
            else -> ""
        }
    }

    /** "Upload save": uploads [romId]'s save if only the Thor's changed. Never downloads. */
    suspend fun upload(romId: Int, title: String): String = request {
        val report = engine.sync(SaveSyncRequest.upload(romId))
        val unit = engine.observeGame(romId).first()
        report.unavailable ?: when (romId) {
            in report.uploaded -> "Uploaded $title's save to RomM"
            in report.adopted -> "RomM already had this save of $title; it's in sync"
            in report.serverNewer -> "RomM has a newer save of $title. Close ${emulatorName(unit?.emulator)}, then restore it."
            in report.waiting -> "${emulatorName(unit?.emulator)} wrote $title's save less than a minute ago. " +
                "Loom uploads a save once it has been left alone for a minute; try again then."
            in report.failed -> "Couldn't sync $title's save: ${report.failed.getValue(romId)}"
            in report.unsupported -> "$title's save isn't synced: ${report.unsupported.getValue(romId)}"
            else -> when (unit?.state) {
                SaveSyncState.NO_SAVE -> "$title has no save yet, on the Thor or on RomM"
                SaveSyncState.LOCAL_MISSING -> "$title's save isn't on the Thor; RomM has one to restore"
                SaveSyncState.CONFLICT -> chooseText(title) // found now, or skipped before and nothing changed since
                else -> "$title's save is already in sync with RomM"
            }
        }
    }

    /** "Restore server save", once the user confirmed the emulator is closed: see [SaveSyncEngine.restoreFromServer]. */
    suspend fun restore(romId: Int, title: String): String = request {
        when (val result = engine.restoreFromServer(romId)) {
            ResolveResult.Done -> "Restored $title's save from RomM"
            is ResolveResult.Failed -> "Didn't restore $title's save: ${result.reason}" + lookAgain(romId, result)
        }
    }

    /** The user's answer to [romId]'s save conflict. Keep server and Keep both need the emulator closed. */
    suspend fun resolve(romId: Int, title: String, choice: ConflictChoice): String = request {
        val replacesASave = saves.conflict(romId)?.localHash != null
        when (val result = engine.resolveConflict(romId, choice)) {
            ResolveResult.Done -> when (choice) {
                ConflictChoice.KEEP_THOR -> "RomM now has the Thor's save of $title; its old one stays in RomM's history"
                ConflictChoice.KEEP_SERVER -> "The Thor now has RomM's save of $title" + backedUp(replacesASave)
                ConflictChoice.KEEP_BOTH -> "RomM keeps the Thor's save of $title as a separate copy, and the Thor now has RomM's"
                ConflictChoice.SKIP -> "Left both saves of $title as they are. Loom asks again if either one changes."
            }
            is ResolveResult.Failed -> "Nothing changed for $title: ${result.reason}" + lookAgain(romId, result)
        }
    }

    /**
     * After an answer refused because a side moved on, Loom looks at the game again, moving no
     * save either way, so the queue and the game's details show where it stands now; the message
     * says what to do from there. When RomM can't be asked, Upload save in the details looks again.
     */
    private suspend fun lookAgain(romId: Int, result: ResolveResult.Failed): String {
        if (!result.stale) return ""
        val report = try {
            engine.sync(SaveSyncRequest.CHECK.copy(romIds = setOf(romId)))
        } catch (e: RommException) {
            null
        }
        if (report == null || report.unavailable != null || romId in report.failed) return ". Upload save in its details looks again"
        return ". Loom looked again: " + when (engine.observeGame(romId).first()?.state) {
            SaveSyncState.CONFLICT -> "choose which to keep in the queue"
            SaveSyncState.SERVER_NEWER, SaveSyncState.LOCAL_MISSING -> "RomM's save can be restored from the queue"
            SaveSyncState.LOCAL_NEWER, SaveSyncState.WRITING -> "the Thor's save is the newer one, and Upload save sends it"
            SaveSyncState.IN_SYNC -> "both sides hold the same save now"
            else -> "see the game's details"
        }
    }

    private suspend fun request(block: suspend () -> String): String = try {
        scope.async { block() }.await()
    } catch (e: RommException.Unreachable) {
        "Can't reach the RomM server"
    } catch (e: RommException.Unauthorized) {
        "RomM rejected Loom's token. Pair again in Settings."
    } catch (e: RommException) {
        e.message ?: "Save sync failed"
    }

    private fun chooseText(title: String) = "$title's save changed on the Thor and on RomM: choose which to keep in the queue"

    private fun backedUp(replacesASave: Boolean) = if (replacesASave) "; the one it replaced is backed up on the card" else ""

    companion object {
        /** How many recently synced games the queue lists. */
        const val RECENT_SYNCS = 5
    }
}

/** An emulator id Loom gives RomM, as the user knows it. */
fun emulatorName(id: String?): String = when (id) {
    AzaharSaveLocator.EMULATOR_ID -> "Azahar"
    EdenSaveLocator.EMULATOR_ID -> "Eden"
    null -> "the emulator"
    else -> id
}
