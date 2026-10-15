package ro.davidmontei.loom.storage.saves

import java.time.Duration
import java.time.Instant

/** The one slot Loom syncs per game; RomM's convention, shared with Argosy and the web player. */
const val SAVE_SLOT = "autosave"

/**
 * What Loom recorded the last time a game's slot was in sync on this device: the three-way base
 * RomM doesn't keep per slot (5.4.0 keeps one per save version, lost once another client uploads
 * a newer one). Written after every upload, download and adopt.
 */
data class SaveBaseline(
    /** Loom's hash of the local save unit right after that sync. */
    val localHash: String,
    /** The slot head then. */
    val serverSaveId: Int,
    /** Its `content_hash` then; null if RomM couldn't compute one. */
    val serverHash: String?,
    /** Its `updated_at` then (the server's clock). */
    val serverUpdatedAt: Instant,
)

/** The newest version in the slot on the server, from a negotiate operation or `GET /api/saves`. */
data class ServerHead(
    val saveId: Int,
    val contentHash: String?,
    val updatedAt: Instant,
    /** The emulator that uploaded it; null for the web UI and old uploads. */
    val emulator: String?,
)

/** What RomM's negotiate said for the slot, as far as the decision needs it. */
enum class NegotiatedAction {
    UPLOAD,
    DOWNLOAD,
    CONFLICT,
    NO_OP,

    /** RomM 5.4.0: the slot the device still holds a save of was emptied on the server. Loom never deletes for it. */
    DELETE,

    /** Anything a newer RomM answers: Loom never acts on it alone. */
    OTHER,
}

enum class SyncOutcome {
    NO_OP,
    UPLOAD,
    DOWNLOAD,

    /** Both sides already hold the same bytes: record the baseline and confirm it to RomM. */
    ADOPT,

    /** Ask the user: Keep Thor, Keep server, Keep both or Skip. */
    CONFLICT,
}

/**
 * The decision for one game's slot and why. A download is only ever chosen when the local save is
 * unchanged since the baseline or absent, and an upload only when the server is unchanged, so both
 * may run without asking (downloads still only after the emulator is closed).
 */
enum class SyncDecision(val outcome: SyncOutcome) {
    /** Neither side changed since the baseline. */
    IN_SYNC(SyncOutcome.NO_OP),

    /** No save on the device and none on the server. */
    NOTHING_TO_SYNC(SyncOutcome.NO_OP),

    /** The device's save is gone and the server's hasn't changed: the server copy stays, unapplied. */
    LOCAL_MISSING(SyncOutcome.NO_OP),

    /** Only this device has a save. */
    NEW_ON_DEVICE(SyncOutcome.UPLOAD),

    /** Only the device's save changed since the baseline. */
    LOCAL_CHANGED(SyncOutcome.UPLOAD),

    /** Only the server has a save, and it came from the same emulator. */
    NEW_ON_SERVER(SyncOutcome.DOWNLOAD),

    /** Only the server's save changed (to a newer one from the same emulator); the device's is unchanged or gone. */
    SERVER_CHANGED(SyncOutcome.DOWNLOAD),

    /** The device and the server hold the same bytes but no baseline says so yet. */
    SAME_CONTENT(SyncOutcome.ADOPT),

    /** (a) Negotiate itself answered `conflict`. */
    SERVER_SAYS_CONFLICT(SyncOutcome.CONFLICT),

    /** (b) Both sides changed since the baseline. */
    BOTH_CHANGED(SyncOutcome.CONFLICT),

    /** (b) Negotiate asked for something else than Loom's own rule: Loom never follows it alone. */
    DISAGREES_WITH_SERVER(SyncOutcome.CONFLICT),

    /** (c) RomM refused the upload with 409. Never returned by [SyncDecider.decide]; the upload records it. */
    UPLOAD_REJECTED(SyncOutcome.CONFLICT),

    /** (d) Both sides have a save, they differ, and there is no baseline (a reinstall, a new device). */
    FIRST_SYNC_DIFFERS(SyncOutcome.CONFLICT),

    /** (e) The server's head is not newer than the one in the baseline: its newest version was deleted. */
    SERVER_WENT_BACK(SyncOutcome.CONFLICT),

    /** (e) The slot is empty on the server although it held the baseline's version. */
    SERVER_SLOT_GONE(SyncOutcome.CONFLICT),

    /** (e) RomM 5.4.0 says the save the device holds was deleted there, and the slot is empty. */
    SERVER_DELETED(SyncOutcome.CONFLICT),

    /** (e) RomM 5.4.0 confirms [SERVER_WENT_BACK]: the device holds a version it deleted, so its head is an older one. */
    SERVER_ROLLED_BACK(SyncOutcome.CONFLICT),

    /** (f) The server's newer save was made by another emulator ([EmulatorIds]): it is never applied automatically. */
    FOREIGN_EMULATOR(SyncOutcome.CONFLICT),

    /**
     * (g) The server's save can't go in place of the device's: it isn't laid out as the emulator
     * keeps its saves. Never returned by [SyncDecider.decide]; a download that finds it records it.
     */
    SERVER_UNREADABLE(SyncOutcome.CONFLICT),
}

/**
 * Loom's own sync rule for one game's slot (design §3). RomM's negotiate (5.4.0's too) pairs on
 * timestamps when the device has no sync row for the slot head, which happens whenever another
 * device or the web player uploaded last; it can then answer `download` over unsynced local
 * progress. So Loom decides from its baseline and content hashes, and when negotiate asks for
 * anything else, the game becomes a conflict for the user to settle.
 */
object SyncDecider {
    /**
     * @param localHash Loom's hash of the local save unit now; null when there is no save.
     * @param localEmulator the emulator id Loom uploads this game's saves as, e.g. "azahar".
     * @param baseline the slot's baseline; null if this device never synced it.
     * @param server the slot head; null when the slot is empty on the server.
     * @param negotiated negotiate's action for the slot; null when Loom didn't negotiate or
     *   negotiate returned nothing for it (a slot it considers deleted on the device).
     */
    fun decide(
        localHash: String?,
        localEmulator: String,
        baseline: SaveBaseline?,
        server: ServerHead?,
        negotiated: NegotiatedAction? = null,
    ): SyncDecision {
        val own = ownRule(localHash, localEmulator, baseline, server)
        return when {
            negotiated == null -> own
            // RomM 5.4.0 knows what was deleted there, which Loom's own rule can't tell: it asks in those words.
            // Even a save Loom would upload as new on the device: it was on RomM once.
            negotiated == NegotiatedAction.DELETE && server == null && localHash != null -> SyncDecision.SERVER_DELETED
            negotiated == NegotiatedAction.DOWNLOAD && own == SyncDecision.SERVER_WENT_BACK -> SyncDecision.SERVER_ROLLED_BACK
            // Loom's conflicts stand, and identical bytes can't be in conflict.
            own.outcome == SyncOutcome.CONFLICT || own.outcome == SyncOutcome.ADOPT -> own
            negotiated == NegotiatedAction.CONFLICT -> SyncDecision.SERVER_SAYS_CONFLICT
            negotiated == expected(own.outcome) -> own
            else -> SyncDecision.DISAGREES_WITH_SERVER
        }
    }

    private fun expected(outcome: SyncOutcome): NegotiatedAction? = when (outcome) {
        SyncOutcome.NO_OP -> NegotiatedAction.NO_OP
        SyncOutcome.UPLOAD -> NegotiatedAction.UPLOAD
        SyncOutcome.DOWNLOAD -> NegotiatedAction.DOWNLOAD
        SyncOutcome.ADOPT, SyncOutcome.CONFLICT -> null
    }

    private fun ownRule(local: String?, emulator: String, base: SaveBaseline?, server: ServerHead?): SyncDecision {
        if (local == null && server == null) return SyncDecision.NOTHING_TO_SYNC
        if (local != null && server?.contentHash != null && local == server.contentHash) {
            val recorded = base != null && base.localHash == local && base.serverSaveId == server.saveId &&
                base.serverHash == server.contentHash
            return if (recorded) SyncDecision.IN_SYNC else SyncDecision.SAME_CONTENT
        }
        if (base == null) {
            return when {
                server == null -> SyncDecision.NEW_ON_DEVICE
                local != null -> SyncDecision.FIRST_SYNC_DIFFERS
                !EmulatorIds.same(server.emulator, emulator) -> SyncDecision.FOREIGN_EMULATOR
                else -> SyncDecision.NEW_ON_SERVER
            }
        }
        // Someone emptied the slot on RomM: uploading would quietly re-create what they deleted.
        if (server == null) return SyncDecision.SERVER_SLOT_GONE
        val localChanged = local != base.localHash
        // The web player rewrites its row in place, so the hash counts as much as the id.
        val serverChanged = server.saveId != base.serverSaveId || server.contentHash != base.serverHash
        return when {
            !serverChanged && !localChanged -> SyncDecision.IN_SYNC
            !serverChanged -> if (local == null) SyncDecision.LOCAL_MISSING else SyncDecision.LOCAL_CHANGED
            !server.updatedAt.isAfter(base.serverUpdatedAt) -> SyncDecision.SERVER_WENT_BACK
            localChanged && local != null -> SyncDecision.BOTH_CHANGED
            !EmulatorIds.same(server.emulator, emulator) -> SyncDecision.FOREIGN_EMULATOR
            else -> SyncDecision.SERVER_CHANGED
        }
    }

    /**
     * The `updated_at` to report to negotiate, chosen so its timestamp rule agrees with Loom's
     * whenever the device has a sync row: the baseline's server time when the save is unchanged
     * (not newer than the sync row), a second past it at least when it changed (newer), and the
     * save's own newest modification time when there is no baseline.
     */
    fun reportedUpdatedAt(localHash: String, newestModified: Instant, baseline: SaveBaseline?): Instant = when {
        baseline == null -> newestModified
        localHash == baseline.localHash -> baseline.serverUpdatedAt
        else -> maxOf(newestModified, baseline.serverUpdatedAt.plus(Duration.ofSeconds(1)))
    }
}
