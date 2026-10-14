package ro.davidmontei.loom.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SaveDao {
    // --- baselines ---

    @Query("SELECT * FROM save_baselines WHERE rom_id = :romId AND slot = :slot")
    suspend fun baseline(romId: Int, slot: String): SaveBaselineEntity?

    @Upsert suspend fun upsertBaseline(baseline: SaveBaselineEntity)

    // --- conflicts ---

    @Query("SELECT * FROM save_conflicts ORDER BY detected_at")
    fun observeConflicts(): Flow<List<SaveConflictEntity>>

    @Query("SELECT * FROM save_conflicts WHERE rom_id = :romId")
    suspend fun conflict(romId: Int): SaveConflictEntity?

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

    @Upsert suspend fun upsertUnit(unit: SaveUnitEntity)

    /** Forgets games no longer installed; their baselines and conflicts stay with their saves. */
    @Query("DELETE FROM save_units WHERE rom_id NOT IN (SELECT rom_id FROM installs)")
    suspend fun deleteUninstalledUnits()
}
