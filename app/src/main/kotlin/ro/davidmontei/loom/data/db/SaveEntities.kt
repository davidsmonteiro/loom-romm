package ro.davidmontei.loom.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What Loom recorded the last time a game's slot was in sync on the Thor (see CONTEXT.md,
 * "Baseline"). Written only after an upload, download or adopt fully succeeded. It holds only for
 * the save folder it was recorded from.
 */
@Entity(tableName = "save_baselines", primaryKeys = ["rom_id", "slot"])
data class SaveBaselineEntity(
    @ColumnInfo(name = "rom_id") val romId: Int,
    val slot: String,
    /** Loom's hash of the save unit right after that sync. */
    @ColumnInfo(name = "local_hash") val localHash: String,
    /** The slot head then. */
    @ColumnInfo(name = "server_save_id") val serverSaveId: Int,
    @ColumnInfo(name = "server_hash") val serverHash: String?,
    /** The head's `updated_at`, as an ISO-8601 instant, to the precision RomM sent. */
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: String,
    @ColumnInfo(name = "synced_at") val syncedAtMs: Long,
    /**
     * The save unit's folder then. A save found in another folder (Azahar's folder changed, the
     * game reinstalled on another card) is compared as if never synced. Null for a baseline
     * recorded before Loom kept it, which counts as another folder.
     */
    @ColumnInfo(name = "unit_path") val unitPath: String? = null,
)

/**
 * A game whose save Loom won't sync on its own (CONTEXT.md, "Save conflict"): what each side held
 * when Loom found it. A resolution only acts if both sides still hold exactly this.
 */
@Entity(tableName = "save_conflicts")
data class SaveConflictEntity(
    @PrimaryKey @ColumnInfo(name = "rom_id") val romId: Int,
    val slot: String,
    /** The decision that made it a conflict: a `SyncDecision` name such as BOTH_CHANGED. */
    val reason: String,
    /** The Thor's save; null when it has none. */
    @ColumnInfo(name = "local_hash") val localHash: String?,
    @ColumnInfo(name = "local_modified_ms") val localModifiedMs: Long?,
    @ColumnInfo(name = "local_bytes") val localBytes: Long?,
    /** The slot head; null when the server has none. */
    @ColumnInfo(name = "server_save_id") val serverSaveId: Int?,
    @ColumnInfo(name = "server_hash") val serverHash: String?,
    @ColumnInfo(name = "server_updated_at") val serverUpdatedAt: String?,
    @ColumnInfo(name = "server_emulator") val serverEmulator: String?,
    @ColumnInfo(name = "detected_at") val detectedAtMs: Long,
    /** The user chose Skip: it stays listed, but isn't raised again until either side changes. */
    val skipped: Boolean = false,
)

/**
 * One installed game Loom syncs saves for: where its save is, what it looked like at the last
 * scan (so an unchanged save isn't hashed again), and where its sync stands.
 */
@Entity(tableName = "save_units")
data class SaveUnitEntity(
    @PrimaryKey @ColumnInfo(name = "rom_id") val romId: Int,
    /** The emulator id Loom gives RomM, e.g. "azahar". */
    val emulator: String,
    /** The save's folder; null when Loom couldn't place it. */
    @ColumnInfo(name = "unit_path") val unitPath: String?,
    /** The files' names, sizes and times when [localHash] was computed. */
    val fingerprint: String?,
    @ColumnInfo(name = "file_count") val fileCount: Int,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "newest_modified_ms") val newestModifiedMs: Long?,
    /** Loom's hash of the save; null when it holds no files. */
    @ColumnInfo(name = "local_hash") val localHash: String?,
    val state: SaveSyncState,
    /** Why it's unsupported or failed, or what's pending, in words for the UI. */
    val detail: String?,
    @ColumnInfo(name = "checked_at") val checkedAtMs: Long,
)

/** Where a game's save sync stands after the last look. */
enum class SaveSyncState {
    /** Both sides hold the save of the baseline. */
    IN_SYNC,

    /** No save on the Thor nor on the server. */
    NO_SAVE,

    /** The Thor's save is newer; it goes up with the next upload. */
    LOCAL_NEWER,

    /** The server's save is newer and can replace the Thor's safely (after a backup). */
    SERVER_NEWER,

    /** The Thor's save is gone; the server's copy stays and can be restored. */
    LOCAL_MISSING,

    /** Needs the user: see `save_conflicts`. */
    CONFLICT,

    /**
     * Loom can't tell which of the emulator's save folders is the game's (it can't read a Switch
     * game's title ID): the user picks the folder. The detail says why.
     */
    NEEDS_FOLDER,

    /** The emulator wrote to it moments ago: Loom waits until it has been quiet for a minute. */
    WRITING,

    /** Loom can't sync this game; the detail says why. */
    UNSUPPORTED,

    /** The last attempt failed; the detail says why. Tried again next time. */
    ERROR,
}

/**
 * A Switch game's title ID, which names its save folder in Eden: read from the game's image with
 * the owner's header key, or the folder the owner chose when Loom couldn't read it. Never the key.
 */
@Entity(tableName = "switch_titles")
data class SwitchTitleEntity(
    @PrimaryKey @ColumnInfo(name = "rom_id") val romId: Int,
    /** 16 uppercase hex digits, e.g. 010015100B514000. */
    @ColumnInfo(name = "title_id") val titleId: String,
    val source: SwitchTitleSource,
    /**
     * For [SwitchTitleSource.IMAGE], the image files' paths, sizes and times when they were read:
     * while they match, a scan doesn't read the images again. Null for the owner's choice.
     */
    @ColumnInfo(name = "image_fingerprint") val imageFingerprint: String?,
    @ColumnInfo(name = "recorded_at") val recordedAtMs: Long,
)

enum class SwitchTitleSource {
    /** Read from the game's XCI or NSP. */
    IMAGE,

    /** The save folder the owner chose for the game. It stands until they choose another. */
    OWNER,
}
