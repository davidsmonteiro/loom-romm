package ro.davidmontei.loom.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A save conflict with its game's title; null if the ROM has left the catalog. */
data class TitledConflict(@Embedded val conflict: SaveConflictEntity, val title: String?)

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

    @Query("SELECT c.*, r.title AS title FROM save_conflicts c LEFT JOIN roms r ON r.id = c.rom_id ORDER BY c.detected_at")
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

    /** Games whose save is in one of [states] (names of [SaveSyncState]), by title. */
    @Query(
        """SELECT u.rom_id, r.title, u.emulator, u.state FROM save_units u JOIN roms r ON r.id = u.rom_id
           WHERE u.state IN (:states) ORDER BY r.sort_key, r.id""",
    )
    fun observeUnitsIn(states: List<String>): Flow<List<TitledSaveUnit>>

    @Upsert suspend fun upsertUnit(unit: SaveUnitEntity)

    /** Forgets games no longer installed; their baselines and conflicts stay with their saves. */
    @Query("DELETE FROM save_units WHERE rom_id NOT IN (SELECT rom_id FROM installs)")
    suspend fun deleteUninstalledUnits()
}
