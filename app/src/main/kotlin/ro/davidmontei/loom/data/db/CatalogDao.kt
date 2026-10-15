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

/** One game row for the library: catalog data plus its install and any download job. */
data class GameRow(
    val id: Int,
    @ColumnInfo(name = "platform_id") val platformId: Int,
    val title: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "cover_small") val coverSmall: String?,
    @ColumnInfo(name = "cover_large") val coverLarge: String?,
    val regions: String,
    @ColumnInfo(name = "removed_on_server") val removedOnServer: Boolean,
    @ColumnInfo(name = "install_path") val installPath: String?,
    @ColumnInfo(name = "install_source") val installSource: String?,
    @ColumnInfo(name = "install_bytes") val installBytes: Long?,
    @ColumnInfo(name = "install_verified_by") val installVerifiedBy: String?,
    @ColumnInfo(name = "job_id") val jobId: Long?,
    @ColumnInfo(name = "job_phase") val jobPhase: String?,
    @ColumnInfo(name = "wait_reason") val waitReason: String?,
    @ColumnInfo(name = "failure_detail") val failureDetail: String?,
    @ColumnInfo(name = "failure_retryable") val failureRetryable: Boolean?,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long?,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long?,
)

@Dao
interface CatalogDao {

    /**
     * Games of one platform for the library grid. [filter]: 0 all, 1 on device, 2 not on device.
     * [query] is matched against the folded title + file name.
     */
    @Query(
        """SELECT r.id, r.platform_id, r.title, r.size_bytes, r.cover_small, r.cover_large, r.regions, r.removed_on_server,
             i.rel_path AS install_path, i.source AS install_source, i.bytes AS install_bytes, i.verified_by AS install_verified_by,
             j.id AS job_id, j.phase AS job_phase, j.wait_reason, j.failure_detail, j.failure_retryable, j.bytes_done, j.bytes_total
           FROM roms r
           LEFT JOIN installs i ON i.rom_id = r.id
           LEFT JOIN download_jobs j ON j.rom_id = r.id
           WHERE r.platform_id = :platformId
             AND (:query = '' OR r.search_key LIKE '%' || :query || '%')
             AND (:filter = 0 OR (:filter = 1 AND i.rom_id IS NOT NULL) OR (:filter = 2 AND i.rom_id IS NULL))
           ORDER BY r.sort_key, r.id""",
    )
    fun observeGames(platformId: Int, filter: Int, query: String): Flow<List<GameRow>>

    @Query(
        """SELECT r.id, r.platform_id, r.title, r.size_bytes, r.cover_small, r.cover_large, r.regions, r.removed_on_server,
             i.rel_path AS install_path, i.source AS install_source, i.bytes AS install_bytes, i.verified_by AS install_verified_by,
             j.id AS job_id, j.phase AS job_phase, j.wait_reason, j.failure_detail, j.failure_retryable, j.bytes_done, j.bytes_total
           FROM roms r LEFT JOIN installs i ON i.rom_id = r.id LEFT JOIN download_jobs j ON j.rom_id = r.id
           WHERE r.id = :romId""",
    )
    fun observeGame(romId: Int): Flow<GameRow?>

    @Query("SELECT cover_small FROM roms WHERE cover_small IS NOT NULL")
    suspend fun smallCovers(): List<String>


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

    @Query("DELETE FROM rom_details WHERE rom_id IN (:romIds)")
    suspend fun deleteDetailsOf(romIds: List<Int>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetails(details: List<RomDetailsEntity>)

    /**
     * Writes one page of the server's catalog: ROM rows replaced, their file lists and details
     * rewritten. A ROM of the page without [details] has none from then on: RomM no longer says any.
     */
    @Transaction
    suspend fun writePage(roms: List<RomEntity>, files: List<RomFileEntity>, details: List<RomDetailsEntity> = emptyList()) {
        if (roms.isEmpty()) return
        upsertRoms(roms)
        val ids = roms.map { it.id }
        deleteFilesOf(ids)
        insertFiles(files)
        deleteDetailsOf(ids)
        insertDetails(details)
    }

    /** What RomM says of ROM [romId] beyond its files, as it changes; null when it says nothing. */
    @Query("SELECT * FROM rom_details WHERE rom_id = :romId")
    fun observeDetails(romId: Int): Flow<RomDetailsEntity?>

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

    @Query("SELECT * FROM roms WHERE platform_id = :platformId") suspend fun romsOf(platformId: Int): List<RomEntity>

    @Query("SELECT * FROM rom_files WHERE rom_id = :romId ORDER BY file_path, file_name")
    suspend fun filesOf(romId: Int): List<RomFileEntity>

    @Query("SELECT COUNT(*) FROM roms") fun observeRomCount(): Flow<Int>

    // --- installs ---

    @Upsert suspend fun upsertInstall(install: InstallEntity)

    @Query("DELETE FROM installs WHERE rom_id = :romId") suspend fun deleteInstall(romId: Int)

    @Query("SELECT * FROM installs WHERE rom_id = :romId") suspend fun install(romId: Int): InstallEntity?

    @Query("SELECT * FROM installs") suspend fun installs(): List<InstallEntity>

    /** The installed games of the platforms with RomM's [slugs]: games one emulator keeps saves of side by side. */
    @Query("SELECT i.* FROM installs i JOIN roms r ON r.id = i.rom_id WHERE r.platform_slug IN (:slugs)")
    suspend fun installsOf(slugs: Collection<String>): List<InstallEntity>

    /** The ROMs of [installsOf], by title. */
    @Query("SELECT r.* FROM roms r JOIN installs i ON i.rom_id = r.id WHERE r.platform_slug IN (:slugs) ORDER BY r.sort_key")
    suspend fun installedRomsOf(slugs: Collection<String>): List<RomEntity>

    @Query("SELECT * FROM installs WHERE volume_uuid = :volumeUuid AND path_key = :pathKey")
    suspend fun installAtPath(volumeUuid: String, pathKey: String): InstallEntity?
}
