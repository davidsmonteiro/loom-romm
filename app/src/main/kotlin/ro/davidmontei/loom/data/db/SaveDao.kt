package ro.davidmontei.loom.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A save conflict with its game's title (null if the ROM has left the catalog) and the emulator
 * the Thor's save belongs to (null until Loom has looked at the save since the game came back).
 */
data class TitledConflict(
    @Embedded val conflict: SaveConflictEntity,
    val title: String?,
    @ColumnInfo(name = "local_emulator") val localEmulator: String?,
)

/** A game's save state with its title. */
data class TitledSaveUnit(
    @ColumnInfo(name = "rom_id") val romId: Int,
    val title: String,
    val emulator: String,
    val state: SaveSyncState,
)

/** When a game's save was last in sync. */
data class RecentSaveSync(
    @ColumnInfo(name = "rom_id") val romId: Int,
    val title: String,
    @ColumnInfo(name = "synced_at") val syncedAtMs: Long,
)

@Dao
interface SaveDao {
    // --- baselines ---

    @Query("SELECT * FROM save_baselines WHERE rom_id = :romId AND slot = :slot")
    suspend fun baseline(romId: Int, slot: String): SaveBaselineEntity?

    @Upsert suspend fun upsertBaseline(baseline: SaveBaselineEntity)

    /** The games whose saves were synced most recently, newest first. */
    @Query(
        """SELECT b.rom_id, r.title, b.synced_at FROM save_baselines b JOIN roms r ON r.id = b.rom_id
           ORDER BY b.synced_at DESC LIMIT :limit""",
    )
    fun observeRecentSyncs(limit: Int): Flow<List<RecentSaveSync>>

    // --- conflicts ---

    @Query("SELECT * FROM save_conflicts ORDER BY detected_at")
    fun observeConflicts(): Flow<List<SaveConflictEntity>>

    @Query("SELECT * FROM save_conflicts WHERE rom_id = :romId")
    suspend fun conflict(romId: Int): SaveConflictEntity?

    /**
     * The conflicts of installed games, oldest first. An offloaded game's conflict stays recorded
     * (its save is still in the emulator's folder) but can't be answered, so it isn't listed.
     */
    @Query(
        """SELECT c.*, r.title AS title, u.emulator AS local_emulator FROM save_conflicts c
           JOIN installs i ON i.rom_id = c.rom_id
           LEFT JOIN roms r ON r.id = c.rom_id LEFT JOIN save_units u ON u.rom_id = c.rom_id ORDER BY c.detected_at""",
    )
    fun observeTitledConflicts(): Flow<List<TitledConflict>>

    @Query("SELECT * FROM save_conflicts ORDER BY detected_at")
    suspend fun conflicts(): List<SaveConflictEntity>

    @Upsert suspend fun upsertConflict(conflict: SaveConflictEntity)

    @Query("DELETE FROM save_conflicts WHERE rom_id = :romId")
    suspend fun deleteConflict(romId: Int)

    @Query("UPDATE save_conflicts SET skipped = 1 WHERE rom_id = :romId")
    suspend fun skipConflict(romId: Int)

    // --- units ---

    @Query("SELECT * FROM save_units WHERE rom_id = :romId")
    suspend fun unit(romId: Int): SaveUnitEntity?

    @Query("SELECT * FROM save_units WHERE rom_id = :romId")
    fun observeUnit(romId: Int): Flow<SaveUnitEntity?>

    @Query("SELECT * FROM save_units ORDER BY rom_id")
    fun observeUnits(): Flow<List<SaveUnitEntity>>

    /** Installed games whose save is in one of [states] (names of [SaveSyncState]), by title. */
    @Query(
        """SELECT u.rom_id, r.title, u.emulator, u.state FROM save_units u JOIN roms r ON r.id = u.rom_id
           JOIN installs i ON i.rom_id = u.rom_id WHERE u.state IN (:states) ORDER BY r.sort_key, r.id""",
    )
    fun observeUnitsIn(states: List<String>): Flow<List<TitledSaveUnit>>

    @Upsert suspend fun upsertUnit(unit: SaveUnitEntity)

    // --- Switch title IDs ---

    @Query("SELECT * FROM switch_titles WHERE rom_id = :romId")
    suspend fun switchTitle(romId: Int): SwitchTitleEntity?

    @Upsert suspend fun upsertSwitchTitle(title: SwitchTitleEntity)

    /** Forgets a title ID read from a game's image (never the owner's pick). */
    @Query("DELETE FROM switch_titles WHERE rom_id = :romId AND source = 'IMAGE'")
    suspend fun forgetReadSwitchTitle(romId: Int)

    /** The titles of the installed games other than [romId] whose save folder is [titleId]'s. */
    @Query(
        """SELECT r.title FROM switch_titles t JOIN installs i ON i.rom_id = t.rom_id JOIN roms r ON r.id = t.rom_id
           WHERE t.title_id = :titleId AND t.rom_id != :romId ORDER BY r.sort_key""",
    )
    suspend fun otherGamesWithSwitchTitle(titleId: String, romId: Int): List<String>

    /** The title IDs of the installed games other than [romId]: those save folders are taken. */
    @Query("SELECT t.title_id FROM switch_titles t JOIN installs i ON i.rom_id = t.rom_id WHERE t.rom_id != :romId")
    suspend fun switchTitlesTakenBesides(romId: Int): List<String>

    /** The games whose save folder the owner chose, so they can choose again. */
    @Query("SELECT rom_id FROM switch_titles WHERE source = 'OWNER'")
    fun observeOwnerChosenSwitchTitles(): Flow<List<Int>>

    // --- the folder a Switch game's save is synced from, of the two Eden kept it in ---

    @Query("SELECT * FROM synced_folders WHERE rom_id = :romId")
    suspend fun syncedFolder(romId: Int): SyncedFolderEntity?

    @Upsert suspend fun upsertSyncedFolder(folder: SyncedFolderEntity)

    @Query("DELETE FROM synced_folders WHERE rom_id = :romId")
    suspend fun forgetSyncedFolder(romId: Int)

    /** The games whose synced folder the owner chose, so they can choose again. */
    @Query("SELECT rom_id FROM synced_folders")
    fun observeSyncedFolders(): Flow<List<Int>>

    /** Forgets games no longer installed; their baselines and conflicts stay with their saves. */
    @Query("DELETE FROM save_units WHERE rom_id NOT IN (SELECT rom_id FROM installs)")
    suspend fun deleteUninstalledUnits()
}
