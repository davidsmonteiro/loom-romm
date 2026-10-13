package ro.davidmontei.loom.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A platform row for the library rail, with what's on the device. */
data class PlatformSummary(
    @Embedded val platform: PlatformEntity,
    @ColumnInfo(name = "local_rom_count") val localRomCount: Int,
    @ColumnInfo(name = "installed_count") val installedCount: Int,
    @ColumnInfo(name = "installed_bytes") val installedBytes: Long,
)

@Dao
interface CatalogDao {

    // --- platforms ---

    @Upsert suspend fun upsertPlatforms(platforms: List<PlatformEntity>)

    @Query("SELECT id FROM platforms") suspend fun platformIds(): List<Int>

    /** Deletes platforms gone from the server unless one of their ROMs is still installed. */
    @Query(
        """DELETE FROM platforms WHERE id IN (:ids)
           AND id NOT IN (SELECT platform_id FROM installs)""",
    )
    suspend fun deletePlatformsWithoutInstalls(ids: List<Int>)

    @Query(
        """SELECT p.*,
             (SELECT COUNT(*) FROM roms r WHERE r.platform_id = p.id) AS local_rom_count,
             (SELECT COUNT(*) FROM installs i WHERE i.platform_id = p.id) AS installed_count,
             (SELECT COALESCE(SUM(i.bytes), 0) FROM installs i WHERE i.platform_id = p.id) AS installed_bytes
           FROM platforms p
           WHERE local_rom_count > 0 OR installed_count > 0
           ORDER BY p.display_name COLLATE NOCASE""",
    )
    fun observePlatformSummaries(): Flow<List<PlatformSummary>>

    @Query("SELECT * FROM platforms WHERE id = :id") suspend fun platform(id: Int): PlatformEntity?

    @Query("SELECT * FROM platforms") suspend fun platforms(): List<PlatformEntity>

    // --- roms ---

    @Upsert suspend fun upsertRoms(roms: List<RomEntity>)

    @Query("DELETE FROM rom_files WHERE rom_id IN (:romIds)")
    suspend fun deleteFilesOf(romIds: List<Int>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<RomFileEntity>)

    /** Writes one page of the server's catalog: ROM rows replaced, their file lists rewritten. */
    @Transaction
    suspend fun writePage(roms: List<RomEntity>, files: List<RomFileEntity>) {
        if (roms.isEmpty()) return
        upsertRoms(roms)
        deleteFilesOf(roms.map { it.id })
        insertFiles(files)
    }

    @Query("SELECT id FROM roms") suspend fun romIds(): List<Int>

    @Query("SELECT rom_id FROM installs") suspend fun installedRomIds(): List<Int>

    @Query("DELETE FROM roms WHERE id IN (:ids)") suspend fun deleteRoms(ids: List<Int>)

    @Query("UPDATE roms SET removed_on_server = 1 WHERE id IN (:ids)")
    suspend fun markRemovedOnServer(ids: List<Int>)

    /**
     * Applies the server's full id list: ROMs it no longer has are deleted, or kept and flagged
     * when they're installed. Chunked to stay under SQLite's variable limit.
     */
    @Transaction
    suspend fun applyServerIds(serverIds: Set<Int>) {
        val gone = romIds().filterNot { it in serverIds }
        if (gone.isEmpty()) return
        val installed = installedRomIds().toSet()
        val (keep, drop) = gone.partition { it in installed }
        keep.chunked(500).forEach { markRemovedOnServer(it) }
        drop.chunked(500).forEach { deleteRoms(it) }
    }

    @Query("SELECT * FROM roms WHERE id = :id") suspend fun rom(id: Int): RomEntity?

    @Query("SELECT * FROM rom_files WHERE rom_id = :romId ORDER BY file_path, file_name")
    suspend fun filesOf(romId: Int): List<RomFileEntity>

    @Query("SELECT COUNT(*) FROM roms") fun observeRomCount(): Flow<Int>

    // --- installs ---

    @Upsert suspend fun upsertInstall(install: InstallEntity)

    @Query("DELETE FROM installs WHERE rom_id = :romId") suspend fun deleteInstall(romId: Int)

    @Query("SELECT * FROM installs WHERE rom_id = :romId") suspend fun install(romId: Int): InstallEntity?

    @Query("SELECT * FROM installs") suspend fun installs(): List<InstallEntity>

    @Query("SELECT * FROM installs WHERE volume_uuid = :volumeUuid AND path_key = :pathKey")
    suspend fun installAtPath(volumeUuid: String, pathKey: String): InstallEntity?
}
