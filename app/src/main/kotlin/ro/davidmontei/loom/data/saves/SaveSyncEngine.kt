package ro.davidmontei.loom.data.saves

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.SaveBaselineEntity
import ro.davidmontei.loom.data.db.SaveConflictEntity
import ro.davidmontei.loom.data.db.SaveDao
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.db.SaveUnitEntity
import ro.davidmontei.loom.data.download.Volumes
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.NotPairedException
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommTime
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.api.saveFilePart
import ro.davidmontei.loom.romm.dto.ClientSaveStateDto
import ro.davidmontei.loom.romm.dto.MAX_NEGOTIATE_ROM_IDS
import ro.davidmontei.loom.romm.dto.SaveDeviceRequest
import ro.davidmontei.loom.romm.dto.SaveDto
import ro.davidmontei.loom.romm.dto.SyncAction
import ro.davidmontei.loom.romm.dto.SyncCompleteRequest
import ro.davidmontei.loom.romm.dto.SyncNegotiateRequest
import ro.davidmontei.loom.romm.dto.SyncOperationDto
import ro.davidmontei.loom.romm.rommCall
import ro.davidmontei.loom.storage.saves.NegotiatedAction
import ro.davidmontei.loom.storage.saves.SAVE_SLOT
import ro.davidmontei.loom.storage.saves.SaveArchiveException
import ro.davidmontei.loom.storage.saves.SaveBackups
import ro.davidmontei.loom.storage.saves.SaveBaseline
import ro.davidmontei.loom.storage.saves.SaveChangedException
import ro.davidmontei.loom.storage.saves.SaveHasher
import ro.davidmontei.loom.storage.saves.SaveRestorer
import ro.davidmontei.loom.storage.saves.SaveRoot
import ro.davidmontei.loom.storage.saves.SaveZipper
import ro.davidmontei.loom.storage.saves.ServerHead
import ro.davidmontei.loom.storage.saves.SyncDecider
import ro.davidmontei.loom.storage.saves.SyncDecision
import ro.davidmontei.loom.storage.saves.SyncOutcome
import ro.davidmontei.loom.storage.saves.UnitSnapshot
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** What one sync run may do. */
data class SaveSyncRequest(
    /** Upload saves that changed on the Thor while the server's didn't. */
    val uploads: Boolean,
    /**
     * Apply downloads Loom's rule allows on its own: the server's save is newer and from the same
     * emulator, and the Thor's is unchanged since the last sync or absent. Each one is backed up
     * first. Never anything more: conflicts always wait for the user.
     */
    val downloads: Boolean,
    /** Only these games; null for every installed game Loom syncs. */
    val romIds: Set<Int>? = null,
    /**
     * With [downloads], the last moment a download may start; null for no limit. Past it, a newer
     * server save is only counted: the user who asked may have started the emulator since.
     */
    val downloadsUntil: Instant? = null,
) {
    companion object {
        /** The periodic scan, opening Loom, a mounted card, PUSH_SAVES: uploads; newer server saves are only counted. */
        val PUSH = SaveSyncRequest(uploads = true, downloads = false)

        /** PULL_SAVES: only the safe downloads. Reachable by any app on the device, so it must never do more. */
        val PULL = SaveSyncRequest(uploads = false, downloads = true)

        /** Only looks: conflicts are recorded and newer saves counted, and no save moves either way. */
        val CHECK = SaveSyncRequest(uploads = false, downloads = false)

        /** A game's "Upload save": only that game, and never a download (those need the emulator closed). */
        fun upload(romId: Int) = SaveSyncRequest(uploads = true, downloads = false, romIds = setOf(romId))
    }
}

/** What a run did, per game (ROM ids). */
data class SaveSyncReport(
    val uploaded: Set<Int> = emptySet(),
    val downloaded: Set<Int> = emptySet(),
    /** Both sides already held the same save; Loom recorded it as in sync. */
    val adopted: Set<Int> = emptySet(),
    /** Games in conflict that the user hasn't chosen to skip. */
    val conflicts: Set<Int> = emptySet(),
    /** The server's save is newer and this run didn't apply it. */
    val serverNewer: Set<Int> = emptySet(),
    /** Changed on the Thor, not uploaded yet: still being written, or uploads weren't asked for. */
    val waiting: Set<Int> = emptySet(),
    val failed: Map<Int, String> = emptyMap(),
    val unsupported: Map<Int, String> = emptyMap(),
    /** Why the run couldn't start at all (not paired, no RomM device); null when it ran. */
    val unavailable: String? = null,
    /** The run couldn't start until Loom pairs again: RomM has no device for it ([SaveSyncEngine.NO_DEVICE]). */
    val needsPairing: Boolean = false,
) {
    /** What the run did, in a few words for Settings ("2 uploaded · 1 conflict"), or why it couldn't run. */
    fun summary(): String {
        unavailable?.let { return it }
        val parts = buildList {
            if (uploaded.isNotEmpty()) add("${uploaded.size} uploaded")
            if (downloaded.isNotEmpty()) add("${downloaded.size} restored from RomM")
            if (conflicts.isNotEmpty()) add(if (conflicts.size == 1) "1 conflict" else "${conflicts.size} conflicts")
            if (serverNewer.isNotEmpty()) add("${serverNewer.size} newer on RomM")
            if (waiting.isNotEmpty()) add("${waiting.size} waiting to upload")
            if (failed.isNotEmpty()) add("${failed.size} failed")
        }
        return parts.joinToString(" · ").ifEmpty { "no changes" }
    }
}

/** The user's answer to a save conflict. */
enum class ConflictChoice {
    /** Upload the Thor's save as the newest version; the server's stays in its history. */
    KEEP_THOR,

    /** Back up the Thor's save, then put the server's in its place. */
    KEEP_SERVER,

    /** Upload the Thor's save as an archival copy outside the slot, then take the server's. */
    KEEP_BOTH,

    /** Leave both alone; don't raise this conflict again until either side changes. */
    SKIP,
}

sealed interface ResolveResult {
    data object Done : ResolveResult

    /**
     * Nothing changed, for [reason]. [stale]: a side moved on since Loom last looked at the game,
     * so where it stands, and what can be asked of it, needs a fresh look.
     */
    data class Failed(val reason: String, val stale: Boolean = false) : ResolveResult
}

/** RomM's record of one save version: its size, and the device that uploaded it when RomM knows. */
data class ServerSaveInfo(val bytes: Long, val deviceName: String?)

/** Why a game is in conflict, in words, from the `SyncDecision` name a conflict records. */
fun conflictReason(decision: String): String = when (decision) {
    SyncDecision.SERVER_SAYS_CONFLICT.name, SyncDecision.BOTH_CHANGED.name -> "Both the Thor and RomM have changed this save since the last sync"
    SyncDecision.DISAGREES_WITH_SERVER.name -> "RomM and Loom disagree about which save is newer"
    SyncDecision.UPLOAD_REJECTED.name -> "RomM got a newer save from elsewhere before this one could upload"
    SyncDecision.FIRST_SYNC_DIFFERS.name -> "The Thor and RomM each have a different save, and they were never synced"
    SyncDecision.SERVER_WENT_BACK.name -> "RomM's newest save is older than the one last synced: it may have been deleted"
    SyncDecision.SERVER_SLOT_GONE.name -> "RomM no longer has this game's save"
    SyncDecision.FOREIGN_EMULATOR.name -> "RomM's newer save was made by another emulator"
    else -> decision
}

/**
 * Syncs saves between the Thor and RomM (design in docs/adr/0010). Loom decides each game from its
 * own baseline with [SyncDecider]; RomM's negotiate opens the session and is a cross-check, and
 * any disagreement becomes a save conflict.
 *
 * Safety rules, all enforced here:
 * - Runs never overlap: one process-wide lock, because negotiate cancels the device's other
 *   sessions (and two runs could act on one save).
 * - A save is uploaded only after a quiet minute and two equal hashes a few seconds apart.
 * - A local save is replaced only after a checked backup, and only atomically ([SaveRestorer]).
 *   Downloads happen only when asked for ([SaveSyncRequest.downloads], a resolution).
 * - A save Loom can't read is left out of the run entirely, never treated as "no save".
 * - A baseline is written only once the whole operation succeeded (for a download, after RomM
 *   recorded it with `/downloaded`).
 */
class SaveSyncEngine(
    private val catalog: CatalogDao,
    private val saves: SaveDao,
    private val server: ActiveServer,
    private val credentials: CredentialStore,
    private val settings: SettingsStore,
    private val units: SaveUnitResolver,
    private val volumes: Volumes,
    /** Private scratch space for archives on their way up or down (the app's cache). */
    private val workDir: File,
    private val now: () -> Instant = Instant::now,
    /** Waits between the two hashes of the quiet check; a seam for tests. */
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val restorer: SaveRestorer = SaveRestorer(),
    private val chunkSize: Int = MAX_NEGOTIATE_ROM_IDS,
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES,
    private val lock: Mutex = SAVE_SYNC_LOCK,
) {
    /** Every installed game Loom syncs, with where its sync stands. */
    fun observeGames(): Flow<List<SaveUnitEntity>> = saves.observeUnits()

    fun observeGame(romId: Int): Flow<SaveUnitEntity?> = saves.observeUnit(romId)

    fun observeConflicts(): Flow<List<SaveConflictEntity>> = saves.observeConflicts()

    /**
     * One sync run. Per-game problems end up in the report (and the game's state); the run itself
     * throws only [RommException.Unreachable], [RommException.Unauthorized] and
     * [RommException.Forbidden], which no game can get past.
     */
    suspend fun sync(request: SaveSyncRequest): SaveSyncReport = lock.withLock {
        withContext(Dispatchers.IO) { Run(request).go() }
    }

    /** Acts on the user's answer to [romId]'s conflict, if both sides still hold what it showed. */
    suspend fun resolveConflict(romId: Int, choice: ConflictChoice): ResolveResult = lock.withLock {
        withContext(Dispatchers.IO) { resolve(romId, choice) }
    }

    /**
     * Puts RomM's save for [romId] on the Thor, when the user asks (with the emulator closed): for
     * a game whose save on the Thor is unchanged since the last sync or missing, such as one
     * deleted by mistake. The Thor's save is backed up first. A save that changed on the Thor, or
     * one from another emulator on RomM, is a conflict to settle instead.
     */
    suspend fun restoreFromServer(romId: Int): ResolveResult = lock.withLock {
        withContext(Dispatchers.IO) { restore(romId) }
    }

    /**
     * What RomM says about save [saveId] of [romId], for the user choosing a side of a conflict
     * (Loom keeps neither its size nor where it came from). Null when RomM can't be asked or no
     * longer has it; the device's name is null when RomM doesn't know or won't say.
     */
    suspend fun serverSaveInfo(romId: Int, saveId: Int): ServerSaveInfo? = withContext(Dispatchers.IO) {
        try {
            val api = server.api()
            val save = rommCall { api.saves(romId = romId) }.firstOrNull { it.id == saveId } ?: return@withContext null
            val device = save.originDeviceId?.let { id ->
                try {
                    rommCall { api.device(id) }.name
                } catch (e: RommException) {
                    null
                }
            }
            ServerSaveInfo(save.fileSizeBytes, device)
        } catch (e: RommException) {
            null
        } catch (e: NotPairedException) {
            null
        }
    }

    // --- one run ---

    private class Prepared(
        val save: ResolvedSave.Ready,
        val snapshot: UnitSnapshot,
        val localHash: String?,
        val baseline: SaveBaseline?,
    ) {
        val romId get() = save.romId
    }

    private inner class Run(private val request: SaveSyncRequest) {
        private val uploaded = mutableSetOf<Int>()
        private val downloaded = mutableSetOf<Int>()
        private val adopted = mutableSetOf<Int>()
        private val conflicts = mutableSetOf<Int>()
        private val serverNewer = mutableSetOf<Int>()
        private val waiting = mutableSetOf<Int>()
        private val failed = mutableMapOf<Int, String>()
        private val unsupported = mutableMapOf<Int, String>()

        private fun report(unavailable: String? = null, needsPairing: Boolean = false) =
            SaveSyncReport(uploaded, downloaded, adopted, conflicts, serverNewer, waiting, failed, unsupported, unavailable, needsPairing)

        suspend fun go(): SaveSyncReport {
            val api = try {
                server.api()
            } catch (e: NotPairedException) {
                return report(unavailable = e.message)
            }
            val deviceId = deviceId(api) ?: return report(unavailable = NO_DEVICE, needsPairing = true)
            saves.deleteUninstalledUnits()

            val prepared = mutableListOf<Prepared>()
            for ((rom, install) in gamesInScope()) {
                when (val save = units.resolve(rom, install)) {
                    is ResolvedSave.Ready -> prepare(save)?.let { prepared += it }
                    is ResolvedSave.Unsupported -> {
                        unsupported[save.romId] = save.reason
                        setState(save.romId, SaveSyncState.UNSUPPORTED, save.reason, emulator = units.emulatorFor(rom))
                    }
                    is ResolvedSave.NeedsFolder -> {
                        unsupported[save.romId] = save.reason
                        setState(save.romId, SaveSyncState.NEEDS_FOLDER, save.reason, emulator = units.emulatorFor(rom))
                    }
                    is ResolvedSave.Unavailable -> {
                        failed[save.romId] = save.reason
                        setState(save.romId, SaveSyncState.ERROR, save.reason, emulator = units.emulatorFor(rom))
                    }
                }
            }
            for (chunk in prepared.chunked(chunkSize)) {
                val stop = syncChunk(api, deviceId, chunk)
                if (stop != null) return report(unavailable = stop, needsPairing = stop == NO_DEVICE)
            }
            return report()
        }

        private suspend fun gamesInScope() = catalog.installs()
            .filter { request.romIds == null || it.romId in request.romIds }
            .mapNotNull { install -> catalog.rom(install.romId)?.takeIf(units::supports)?.let { it to install } }

        /**
         * Reads the local save: finishes any restore Loom died in, then hashes the unit unless its
         * files look exactly as at the last scan. Null (left out of the run) when it can't be read.
         * A baseline recorded for another folder doesn't count: that save was never synced from here.
         */
        private suspend fun prepare(save: ResolvedSave.Ready): Prepared? {
            try {
                restorer.recover(save.roots)
                val before = UnitSnapshot.of(save.roots)
                val cached = saves.unit(save.romId)
                val path = save.unitPath
                val hash = when {
                    before.isEmpty -> null
                    cached != null && cached.unitPath == path && cached.fingerprint == before.fingerprint && cached.localHash != null -> cached.localHash
                    else -> SaveHasher.hashUnit(save.roots)
                }
                val baseline = saves.baseline(save.romId, SAVE_SLOT)?.takeIf { it.unitPath == path }
                val prepared = Prepared(save, before, hash, baseline?.toBaseline())
                setState(save.romId, cached?.state ?: SaveSyncState.NO_SAVE, cached?.detail, save, before, hash)
                return prepared
            } catch (e: IOException) {
                fail(save.romId, "Couldn't read the save: ${e.message}")
                return null
            }
        }

        /** Negotiates one chunk, acts on each game, completes the session. Returns why to stop, if it must. */
        private suspend fun syncChunk(api: RommApi, deviceId: String, chunk: List<Prepared>): String? {
            val negotiated = try {
                rommCall { api.negotiateSync(SyncNegotiateRequest(deviceId, chunk.mapNotNull { it.clientState() }, chunk.map { it.romId })) }
            } catch (e: RommException.Http) {
                if (e.code == 400) return "RomM won't sync saves with this device: ${e.detail ?: "refused"}"
                throw e
            } catch (e: RommException.NotFound) {
                return NO_DEVICE
            }
            val ops = negotiated.operations.filter { it.slot == SAVE_SLOT }.associateBy { it.romId }
            // Negotiate leaves out heads the device "already has" and gives no details for an
            // untracked version: those heads come from the save list.
            val unknown = chunk.filter { p -> ops[p.romId].let { it == null || (it.saveId != null && it.serverUpdatedAt == null) } }
            val listed = if (unknown.isEmpty()) emptyMap() else heads(api, unknown.map { it.romId })

            var completed = 0
            var failures = 0
            for (game in chunk) {
                try {
                    val op = ops[game.romId]
                    val fromList = op == null || (op.saveId != null && op.serverUpdatedAt == null)
                    val head = if (fromList) listed[game.romId] else op!!.head()
                    val action = if (fromList) null else op!!.syncAction.toNegotiated()
                    if (act(api, deviceId, negotiated.sessionId, game, head, action) == Acted.DONE) completed++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isFatal()) throw e
                    fail(game.romId, e.message ?: e.javaClass.simpleName)
                    failures++
                }
            }
            try {
                rommCall { api.completeSyncSession(negotiated.sessionId, SyncCompleteRequest(completed, failures)) }
            } catch (e: RommException) {
                // Bookkeeping only: what was done stays done.
            }
            return null
        }

        private suspend fun act(api: RommApi, deviceId: String, sessionId: Int, game: Prepared, head: ServerHead?, action: NegotiatedAction?): Acted {
            val decided = SyncDecider.decide(game.localHash, game.save.emulator, game.baseline, head, action)
            // RomM refused this very upload before (409) and nothing changed since: trying again
            // would only be refused again, and would forget that the user chose Skip.
            val existing = saves.conflict(game.romId)
            val refusedBefore = decided.outcome == SyncOutcome.UPLOAD && existing?.reason == SyncDecision.UPLOAD_REJECTED.name &&
                existing.localHash == game.localHash && existing.serverSaveId == head?.saveId && existing.serverHash == head?.contentHash
            val decision = if (refusedBefore) SyncDecision.UPLOAD_REJECTED else decided
            if (decision.outcome != SyncOutcome.CONFLICT) saves.deleteConflict(game.romId)
            return when (decision.outcome) {
                SyncOutcome.NO_OP -> {
                    val state = when (decision) {
                        SyncDecision.NOTHING_TO_SYNC -> SaveSyncState.NO_SAVE
                        SyncDecision.LOCAL_MISSING -> SaveSyncState.LOCAL_MISSING
                        else -> SaveSyncState.IN_SYNC
                    }
                    setState(game.romId, state, null)
                    Acted.NOTHING
                }
                SyncOutcome.ADOPT -> {
                    adopt(api, deviceId, game.save, game.localHash!!, head!!)
                    adopted += game.romId
                    Acted.DONE
                }
                SyncOutcome.UPLOAD -> if (request.uploads) upload(api, deviceId, sessionId, game, head) else {
                    waiting += game.romId
                    setState(game.romId, SaveSyncState.LOCAL_NEWER, "Changed on the Thor; it uploads with the next sync")
                    Acted.NOTHING
                }
                SyncOutcome.DOWNLOAD -> if (request.downloads && request.downloadsUntil.let { it == null || !now().isAfter(it) }) {
                    download(api, deviceId, sessionId, game.romId, game.save, head!!, game.localHash)
                    downloaded += game.romId
                    Acted.DONE
                } else {
                    serverNewer += game.romId
                    setState(game.romId, SaveSyncState.SERVER_NEWER, "RomM has a newer save; close ${emulatorName(game.save.emulator)}, then restore it")
                    Acted.NOTHING
                }
                SyncOutcome.CONFLICT -> {
                    conflict(game, head, decision)
                    Acted.NOTHING
                }
            }
        }

        private suspend fun upload(api: RommApi, deviceId: String, sessionId: Int, game: Prepared, head: ServerHead?): Acted {
            val hash = game.localHash!!
            val sent = try {
                whenSettled(game.save, hash) { file -> uploadSlotted(api, deviceId, sessionId, game.save, file, hash, head, overwrite = false) }
            } catch (e: RommException.Conflict) {
                conflict(game, head, SyncDecision.UPLOAD_REJECTED)
                return Acted.NOTHING
            }
            if (sent == null) {
                waiting += game.romId
                setState(game.romId, SaveSyncState.WRITING, "${emulatorName(game.save.emulator)} is writing this save; Loom uploads it once it has been quiet for a minute")
                return Acted.NOTHING
            }
            uploaded += game.romId
            return Acted.DONE
        }

        private suspend fun conflict(game: Prepared, head: ServerHead?, decision: SyncDecision) {
            val existing = saves.conflict(game.romId)
            val same = existing != null && existing.reason == decision.name && existing.localHash == game.localHash &&
                existing.serverSaveId == head?.saveId && existing.serverHash == head?.contentHash
            val record = SaveConflictEntity(
                romId = game.romId, slot = SAVE_SLOT, reason = decision.name,
                localHash = game.localHash, localModifiedMs = game.snapshot.newestModified?.toEpochMilli(),
                localBytes = game.snapshot.totalBytes.takeIf { game.localHash != null },
                serverSaveId = head?.saveId, serverHash = head?.contentHash, serverUpdatedAt = head?.updatedAt?.toString(),
                serverEmulator = head?.emulator,
                detectedAtMs = if (same) existing!!.detectedAtMs else now().toEpochMilli(),
                skipped = same && existing!!.skipped,
            )
            saves.upsertConflict(record)
            if (!record.skipped) conflicts += game.romId
            setState(game.romId, SaveSyncState.CONFLICT, conflictReason(decision.name))
        }

        private suspend fun fail(romId: Int, message: String) {
            failed[romId] = message
            setState(romId, SaveSyncState.ERROR, message)
        }

        private fun Prepared.clientState(): ClientSaveStateDto? {
            val hash = localHash ?: return null
            return ClientSaveStateDto(
                romId = romId, fileName = save.fileName, slot = SAVE_SLOT, emulator = save.emulator, contentHash = hash,
                updatedAt = SyncDecider.reportedUpdatedAt(hash, snapshot.newestModified!!, baseline).toString(),
                fileSizeBytes = snapshot.totalBytes,
            )
        }
    }

    /** Whether an operation was carried out, for the session's counters. */
    private enum class Acted { DONE, NOTHING }

    // --- conflicts ---

    /** The server, this device and a game's save, for something the user asked for; or why not. */
    private sealed interface Opened {
        class Ready(val api: RommApi, val deviceId: String, val save: ResolvedSave.Ready) : Opened
        class Refused(val reason: String) : Opened
    }

    private suspend fun open(romId: Int): Opened {
        val api = try {
            server.api()
        } catch (e: NotPairedException) {
            return Opened.Refused(e.message ?: "Not paired")
        }
        val deviceId = deviceId(api) ?: return Opened.Refused(NO_DEVICE)
        val install = catalog.install(romId) ?: return Opened.Refused("The game isn't installed any more")
        val rom = catalog.rom(romId) ?: return Opened.Refused("The game isn't in the catalog any more")
        return when (val resolved = units.resolve(rom, install)) {
            is ResolvedSave.Ready -> {
                // A restore Loom died in is undone first, so nothing acts on a half-restored save.
                restorer.recover(resolved.roots)
                Opened.Ready(api, deviceId, resolved)
            }
            is ResolvedSave.Unsupported -> Opened.Refused(resolved.reason)
            is ResolvedSave.NeedsFolder -> Opened.Refused(resolved.reason)
            is ResolvedSave.Unavailable -> Opened.Refused(resolved.reason)
        }
    }

    private suspend fun restore(romId: Int): ResolveResult {
        val opened = try {
            open(romId)
        } catch (e: IOException) {
            return ResolveResult.Failed(e.message ?: "Couldn't read the save")
        }
        if (opened is Opened.Refused) return ResolveResult.Failed(opened.reason)
        val (api, deviceId, save) = (opened as Opened.Ready).let { Triple(it.api, it.deviceId, it.save) }
        return try {
            val local = SaveHasher.hashUnit(save.roots)
            if (local != null && local != saves.baseline(romId, SAVE_SLOT)?.localHash) {
                return ResolveResult.Failed("The save on the Thor changed since the last sync", stale = true)
            }
            val head = heads(api, listOf(romId))[romId] ?: return ResolveResult.Failed("RomM has no save for this game")
            if (head.emulator != save.emulator) {
                return ResolveResult.Failed("RomM's save was made by ${head.emulator ?: "another app"}", stale = true)
            }
            download(api, deviceId, null, romId, save, head, local)
            saves.deleteConflict(romId)
            ResolveResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: SaveChangedException) {
            ResolveResult.Failed(e.message ?: "The save on the Thor changed since Loom looked", stale = true)
        } catch (e: Exception) {
            if (e is RommException && e.isFatal()) throw e
            ResolveResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun resolve(romId: Int, choice: ConflictChoice): ResolveResult {
        val conflict = saves.conflict(romId) ?: return ResolveResult.Failed("This game has no save conflict any more")
        if (choice == ConflictChoice.SKIP) {
            saves.skipConflict(romId)
            return ResolveResult.Done
        }
        val opened = try {
            open(romId)
        } catch (e: IOException) {
            return ResolveResult.Failed(e.message ?: "Couldn't read the save")
        }
        if (opened is Opened.Refused) return ResolveResult.Failed(opened.reason)
        val (api, deviceId, save) = (opened as Opened.Ready).let { Triple(it.api, it.deviceId, it.save) }
        return try {
            // The user chose between what the conflict showed: both sides must still be exactly that.
            val local = SaveHasher.hashUnit(save.roots)
            if (local != conflict.localHash) return ResolveResult.Failed(STALE_LOCAL, stale = true)
            val head = heads(api, listOf(romId))[romId]
            if (head?.saveId != conflict.serverSaveId || head?.contentHash != conflict.serverHash) return ResolveResult.Failed(STALE_SERVER, stale = true)
            when (choice) {
                ConflictChoice.KEEP_THOR -> {
                    if (local == null) return ResolveResult.Failed("There's no save on the Thor to keep")
                    whenSettled(save, local) { file -> uploadSlotted(api, deviceId, null, save, file, local, head, overwrite = true) }
                        ?: return ResolveResult.Failed(stillWriting(save))
                }
                ConflictChoice.KEEP_SERVER, ConflictChoice.KEEP_BOTH -> {
                    if (head == null) return ResolveResult.Failed("RomM has no save for this game to take")
                    if (choice == ConflictChoice.KEEP_BOTH && local != null) {
                        whenSettled(save, local) { file -> uploadArchival(api, deviceId, save, file, local) }
                            ?: return ResolveResult.Failed(stillWriting(save))
                    }
                    download(api, deviceId, null, romId, save, head, local)
                }
                ConflictChoice.SKIP -> Unit
            }
            saves.deleteConflict(romId)
            ResolveResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: SaveChangedException) {
            ResolveResult.Failed(STALE_LOCAL, stale = true)
        } catch (e: Exception) {
            if (e is RommException && e.isFatal()) throw e
            ResolveResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // --- the operations ---

    /** Both sides hold the same bytes: tell RomM this device has that version, and record it. */
    private suspend fun adopt(api: RommApi, deviceId: String, save: ResolvedSave.Ready, localHash: String, head: ServerHead) {
        rommCall { api.confirmSaveDownloaded(head.saveId, SaveDeviceRequest(deviceId)) }
        writeBaseline(save, localHash, head.saveId, head.contentHash, head.updatedAt)
        setState(save.romId, SaveSyncState.IN_SYNC, null)
    }

    /**
     * Uploads [file], the unit's archive hashing as [hash], to the slot. Without [overwrite], RomM
     * may answer with an older version holding the same bytes (it then makes no new head): Loom
     * uploads again with `overwrite`, deliberately making those bytes the newest, since the Thor
     * went back to them.
     */
    private suspend fun uploadSlotted(
        api: RommApi, deviceId: String, sessionId: Int?, save: ResolvedSave.Ready, file: File, hash: String, head: ServerHead?, overwrite: Boolean,
    ) {
        suspend fun send(overwrite: Boolean) = rommCall {
            api.uploadSave(
                romId = save.romId, emulator = save.emulator, slot = SAVE_SLOT, deviceId = deviceId, sessionId = sessionId,
                contentHash = hash, overwrite = overwrite, saveFile = saveFilePart(file, save.fileName),
            )
        }
        var saved = send(overwrite)
        if (!overwrite && head != null && saved.id == head.saveId) {
            // The head itself holds these bytes; RomM just has no record that this device has it.
            rommCall { api.confirmSaveDownloaded(saved.id, SaveDeviceRequest(deviceId)) }
        } else if (!overwrite && head != null && !isNewHead(saved, head)) {
            saved = send(overwrite = true)
        }
        writeBaseline(save, hash, saved.id, saved.contentHash, saved.updatedAtInstant())
        setState(save.romId, SaveSyncState.IN_SYNC, null)
    }

    /** "Keep both": the Thor's copy goes up outside the slot, under a name no other save has. */
    private suspend fun uploadArchival(api: RommApi, deviceId: String, save: ResolvedSave.Ready, file: File, hash: String) {
        val taken = rommCall { api.saves(romId = save.romId) }.map { it.fileName }.toSet()
        val stem = "${save.fileName.substringBeforeLast('.')} (Thor ${ARCHIVE_STAMP.format(now())})"
        var name = "$stem.zip"
        var n = 2
        while (name in taken) name = "$stem ${n++}.zip"
        rommCall {
            api.uploadSave(
                romId = save.romId, emulator = save.emulator, slot = null, deviceId = deviceId, sessionId = null,
                contentHash = hash, saveFile = saveFilePart(file, name),
            )
        }
    }

    /**
     * Replaces the Thor's save with the server's [head]: the save must still hash as [expectedLocal]
     * (what the decision was made on), is backed up, and the download must match RomM's hash
     * before [SaveRestorer] swaps it in. RomM hears about it (`/downloaded`) only once the save is
     * in place, and only then is the baseline written.
     */
    private suspend fun download(
        api: RommApi, deviceId: String, sessionId: Int?, romId: Int, save: ResolvedSave.Ready, head: ServerHead, expectedLocal: String?,
    ) {
        val current = SaveHasher.hashUnit(save.roots)
        if (current != expectedLocal) throw SaveChangedException("The save on the Thor changed since Loom looked; nothing was replaced")
        if (current != null) backups(save.roots).backUp(romId.toString(), save.title, save.roots, current)
        val file = File(workDir, "download-$romId-${UUID.randomUUID()}.zip")
        try {
            rommCall { api.saveContent(head.saveId, deviceId, sessionId) }.use { body ->
                body.byteStream().use { input -> copyCapped(input, file) }
            }
            val expected = head.contentHash
            if (expected != null && SaveHasher.hashFile(file.toPath()) != expected && SaveHasher.md5(file.toPath()) != expected) {
                throw SaveArchiveException("The downloaded save doesn't match RomM's hash; nothing was replaced")
            }
            if (!SaveHasher.isZip(file.toPath())) throw SaveArchiveException("RomM's save for this game isn't a save archive Loom can restore")
            val restored = restorer.restore(file.toPath(), save.roots, current)
            rommCall { api.confirmSaveDownloaded(head.saveId, SaveDeviceRequest(deviceId)) }
            writeBaseline(save, restored, head.saveId, head.contentHash, head.updatedAt)
            setState(romId, SaveSyncState.IN_SYNC, null)
        } finally {
            file.delete()
        }
    }

    /**
     * Packs the unit for upload once it has settled, and runs [block] with the archive. Settled:
     * left alone for [QUIET_PERIOD], hashing as [expected] (what the decision was made on), and
     * hashing the same again when it is zipped [SECOND_HASH_DELAY_MS] later (the archive's hash,
     * computed from the bytes as they went in, is the second hash), with no file touched in
     * between. Null, with nothing sent, while the emulator may still be writing.
     */
    private suspend fun <T> whenSettled(save: ResolvedSave.Ready, expected: String, block: suspend (File) -> T): T? {
        val before = UnitSnapshot.of(save.roots)
        val newest = before.newestModified ?: return null
        if (Duration.between(newest, now()) < QUIET_PERIOD) return null
        if (SaveHasher.hashUnit(save.roots) != expected) return null
        pause(SECOND_HASH_DELAY_MS)
        workDir.mkdirs()
        val file = File(workDir, "upload-${save.romId}-${UUID.randomUUID()}.zip")
        try {
            if (SaveZipper.zip(save.roots, file.toPath()) != expected || UnitSnapshot.of(save.roots) != before) return null
            return block(file)
        } finally {
            file.delete()
        }
    }

    private fun copyCapped(input: java.io.InputStream, file: File) {
        workDir.mkdirs()
        Files.newOutputStream(file.toPath()).use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxDownloadBytes) throw SaveArchiveException("RomM's save is larger than a save can be")
                out.write(buffer, 0, n)
            }
        }
    }

    private fun backups(roots: List<SaveRoot>): SaveBackups {
        val volume = volumes.forPath(roots.first().folder.toString())
            ?: throw IOException("Can't find the card holding this save to back it up; nothing was replaced")
        return SaveBackups(volume.root.toPath().resolve(SaveBackups.BACKUP_DIR), now = now)
    }

    /** The slot head of each game, from RomM's list of versions (newest by time, then by id). */
    private suspend fun heads(api: RommApi, romIds: List<Int>): Map<Int, ServerHead> =
        rommCall { api.saves(romIds = romIds, slot = SAVE_SLOT) }
            .filter { it.slot == SAVE_SLOT }
            .groupBy { it.romId }
            .mapValues { (_, versions) ->
                versions.maxWith(compareBy<SaveDto>({ it.updatedAtInstant() }, { it.id }))
                    .let { ServerHead(it.id, it.contentHash, it.updatedAtInstant(), it.emulator) }
            }

    /**
     * The RomM device Loom syncs as: the one its token is bound to, or the one it paired as before
     * (found by its identifier). Null when there is none: then saves can't sync until Loom pairs
     * with a code again.
     */
    private suspend fun deviceId(api: RommApi): String? {
        credentials.awaitLoaded()
        credentials.current.value?.deviceId?.let { return it }
        val mine = settings.deviceIdentifier()
        return try {
            rommCall { api.devices() }.firstOrNull { it.clientDeviceIdentifier == mine }?.id
        } catch (e: RommException.Forbidden) {
            null
        }
    }

    private suspend fun writeBaseline(save: ResolvedSave.Ready, localHash: String, saveId: Int, serverHash: String?, serverUpdatedAt: Instant) =
        saves.upsertBaseline(
            SaveBaselineEntity(
                save.romId, SAVE_SLOT, localHash, saveId, serverHash, serverUpdatedAt.toString(), now().toEpochMilli(), save.unitPath,
            ),
        )

    /** Records a game's state; with [save], also what its files looked like for the next scan. */
    private suspend fun setState(
        romId: Int, state: SaveSyncState, detail: String?,
        save: ResolvedSave.Ready? = null, snapshot: UnitSnapshot? = null, hash: String? = null, emulator: String? = null,
    ) {
        val old = saves.unit(romId)
        val emulator = save?.emulator ?: emulator ?: old?.emulator ?: EMULATOR_UNKNOWN
        saves.upsertUnit(
            if (save != null && snapshot != null) {
                SaveUnitEntity(
                    romId, emulator, save.unitPath, snapshot.fingerprint, snapshot.fileCount,
                    snapshot.totalBytes, snapshot.newestModified?.toEpochMilli(), hash, state, detail, now().toEpochMilli(),
                )
            } else {
                (old ?: SaveUnitEntity(romId, emulator, null, null, 0, 0, null, null, state, detail, 0))
                    .copy(state = state, detail = detail, checkedAtMs = now().toEpochMilli())
            },
        )
    }

    private fun SaveBaselineEntity.toBaseline() =
        SaveBaseline(localHash, serverSaveId, serverHash, Instant.parse(serverUpdatedAt))

    private fun SyncOperationDto.head(): ServerHead? {
        val id = saveId ?: return null
        val at = serverUpdatedAt?.let(RommTime::parse) ?: throw RommException.Protocol("RomM sent an unreadable time for save $id")
        return ServerHead(id, serverContentHash, at, emulator)
    }

    private fun SyncAction.toNegotiated(): NegotiatedAction = when (this) {
        SyncAction.UPLOAD -> NegotiatedAction.UPLOAD
        SyncAction.DOWNLOAD -> NegotiatedAction.DOWNLOAD
        SyncAction.CONFLICT -> NegotiatedAction.CONFLICT
        SyncAction.NO_OP -> NegotiatedAction.NO_OP
        SyncAction.DELETE, SyncAction.UNKNOWN -> NegotiatedAction.OTHER
    }

    private fun SaveDto.updatedAtInstant(): Instant =
        RommTime.parse(updatedAt) ?: throw RommException.Protocol("RomM sent an unreadable time for save $id")

    /** Whether an upload's answer is a new slot head, not an older version RomM matched by hash. */
    private fun isNewHead(saved: SaveDto, head: ServerHead): Boolean =
        saved.id > head.saveId && !saved.updatedAtInstant().isBefore(head.updatedAt)

    private fun stillWriting(save: ResolvedSave.Ready) = "${emulatorName(save.emulator)} is still writing this save. Close it and try again in a minute."

    /** Errors no game in the run can get past: the server is gone, or Loom's token isn't accepted. */
    private fun Exception.isFatal() =
        this is RommException.Unreachable || this is RommException.Unauthorized || this is RommException.Forbidden

    companion object {
        /** A save must be left alone this long before it's uploaded. */
        val QUIET_PERIOD: Duration = Duration.ofSeconds(60)

        /** Between the two hashes that must agree before an upload. */
        const val SECOND_HASH_DELAY_MS = 5_000L

        /** Most bytes Loom downloads for one save (RomM itself takes up to 512 MiB). */
        const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024

        const val NO_DEVICE = "RomM doesn't have Loom as a device, so saves can't sync. Pair again in Settings with the code Loom shows."
        private const val STALE_LOCAL = "The save on the Thor changed since this choice was shown"
        private const val STALE_SERVER = "RomM's save changed since this choice was shown"
        private const val EMULATOR_UNKNOWN = "unknown"

        private val ARCHIVE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss 'UTC'").withZone(ZoneOffset.UTC)
    }
}

/** Every sync run in the process takes this lock: negotiate cancels the device's other sessions. */
private val SAVE_SYNC_LOCK = Mutex()
