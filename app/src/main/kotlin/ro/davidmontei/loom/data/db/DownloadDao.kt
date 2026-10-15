package ro.davidmontei.loom.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class RecentInstall(
    @androidx.room.ColumnInfo(name = "rom_id") val romId: Int,
    val title: String,
    @androidx.room.ColumnInfo(name = "rel_path") val relPath: String,
    val bytes: Long,
    @androidx.room.ColumnInfo(name = "installed_at") val installedAt: Long,
    @androidx.room.ColumnInfo(name = "verified_by") val verifiedBy: String,
)

@Dao
interface DownloadDao {

    @Insert suspend fun insertJob(job: DownloadJobEntity): Long

    @Insert suspend fun insertParts(parts: List<DownloadPartEntity>)

    @Transaction
    suspend fun insertJobWithParts(job: DownloadJobEntity, parts: (Long) -> List<DownloadPartEntity>): Long {
        val id = insertJob(job)
        insertParts(parts(id))
        return id
    }

    @Query("SELECT * FROM download_jobs WHERE id = :id") suspend fun job(id: Long): DownloadJobEntity?

    @Query("SELECT * FROM download_jobs WHERE rom_id = :romId") suspend fun jobForRom(romId: Int): DownloadJobEntity?

    /** The job RomM's install request [requestId] became, if it is still in the queue. */
    @Query("SELECT * FROM download_jobs WHERE install_request_id = :requestId")
    suspend fun jobForInstallRequest(requestId: String): DownloadJobEntity?

    /** Records that [jobId] is what RomM's install request [requestId] asked for. */
    @Query("UPDATE download_jobs SET install_request_id = :requestId WHERE id = :jobId")
    suspend fun setInstallRequest(jobId: Long, requestId: String)

    @Query("SELECT * FROM download_jobs WHERE id = :id") fun observeJob(id: Long): Flow<DownloadJobEntity?>

    @Query("SELECT * FROM download_jobs ORDER BY queue_order") fun observeJobs(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE phase = :phase ORDER BY queue_order") suspend fun jobsIn(phase: String): List<DownloadJobEntity>

    @Query("SELECT * FROM download_jobs WHERE phase = 'QUEUED' AND lease_id IS NULL ORDER BY queue_order LIMIT 1")
    suspend fun nextQueued(): DownloadJobEntity?

    @Query("SELECT COUNT(*) FROM download_jobs WHERE phase IN ('QUEUED','DOWNLOADING','VERIFYING','EXTRACTING','PROMOTING','CANCELLING')")
    suspend fun pendingWorkCount(): Int

    @Query("SELECT * FROM download_parts WHERE job_id = :jobId ORDER BY rom_file_id") suspend fun parts(jobId: Long): List<DownloadPartEntity>

    @Upsert suspend fun upsertPart(part: DownloadPartEntity)

    @Query("DELETE FROM download_parts WHERE job_id = :jobId") suspend fun deleteParts(jobId: Long)

    @Query("DELETE FROM download_jobs WHERE id = :id") suspend fun deleteJob(id: Long)

    /** Compare-and-set on (lease, phase): the heart of "the UI's pause/cancel always wins". */
    @Query(
        """UPDATE download_jobs SET phase = :toPhase, wait_reason = :waitReason, failure_code = :failureCode,
             failure_detail = :failureDetail, failure_retryable = :retryable,
             lease_id = CASE WHEN :keepLease THEN lease_id ELSE NULL END,
             checksum_retries = COALESCE(:checksumRetries, checksum_retries), updated_at = :now
           WHERE id = :id AND lease_id = :lease AND phase = :fromPhase""",
    )
    suspend fun casPhase(
        id: Long, lease: String, fromPhase: String, toPhase: String, waitReason: String?, failureCode: String?,
        failureDetail: String?, retryable: Boolean, keepLease: Boolean, checksumRetries: Int?, now: Long,
    ): Int

    /** Claim: QUEUED and unleased → [toPhase] under [lease]. */
    @Query(
        """UPDATE download_jobs SET phase = :toPhase, lease_id = :lease, attempt = attempt + 1, updated_at = :now
           WHERE id = :id AND phase = 'QUEUED' AND lease_id IS NULL""",
    )
    suspend fun claim(id: Long, lease: String, toPhase: String, now: Long): Int

    /** A UI action: unconditional apart from the expected current phase; always drops the lease. */
    @Query(
        """UPDATE download_jobs SET phase = :toPhase, wait_reason = :waitReason, failure_code = :failureCode,
             failure_detail = :failureDetail, failure_retryable = :retryable, lease_id = NULL, updated_at = :now
           WHERE id = :id AND phase = :fromPhase""",
    )
    suspend fun setPhase(
        id: Long, fromPhase: String, toPhase: String, waitReason: String?, failureCode: String?,
        failureDetail: String?, retryable: Boolean, now: Long,
    ): Int

    /** After a worker death: every leased, active job goes back to the queue. */
    @Query(
        """UPDATE download_jobs SET phase = 'QUEUED', lease_id = NULL, updated_at = :now
           WHERE phase IN ('DOWNLOADING','VERIFYING','EXTRACTING','PROMOTING')""",
    )
    suspend fun requeueStale(now: Long): Int

    @Query("UPDATE download_jobs SET bytes_done = :bytes WHERE id = :id") suspend fun saveProgress(id: Long, bytes: Long)

    @Query("UPDATE download_jobs SET final_rel_path = :relPath, final_key = :key, final_kind = :kind WHERE id = :id")
    suspend fun setFinalEntry(id: Long, relPath: String, key: String, kind: String)

    @Query(
        """UPDATE download_jobs SET plan_json = :planJson, fingerprint = :fingerprint, generation = generation + 1,
             bytes_total = :bytesTotal, bytes_done = 0, final_rel_path = NULL, final_key = NULL, final_kind = NULL,
             checksum_retries = 0 WHERE id = :id""",
    )
    suspend fun replacePlan(id: Long, planJson: String, fingerprint: String, bytesTotal: Long)

    /** The same plan with one field changed (its folder): its parts and what's on disk still hold. */
    @Query("UPDATE download_jobs SET plan_json = :planJson WHERE id = :id")
    suspend fun setPlanJson(id: Long, planJson: String)

    @Transaction
    suspend fun replan(id: Long, planJson: String, fingerprint: String, bytesTotal: Long, parts: List<DownloadPartEntity>) {
        replacePlan(id, planJson, fingerprint, bytesTotal)
        deleteParts(id)
        insertParts(parts)
    }

    @Query(
        """SELECT COUNT(*) FROM download_jobs WHERE volume_uuid = :volumeUuid AND final_key = :key AND rom_id != :exceptRomId""",
    )
    suspend fun jobsClaimingPath(volumeUuid: String, key: String, exceptRomId: Int): Int

    @Query("SELECT COUNT(*) FROM installs WHERE volume_uuid = :volumeUuid AND path_key = :key AND rom_id != :exceptRomId")
    suspend fun installsClaimingPath(volumeUuid: String, key: String, exceptRomId: Int): Int

    @Upsert suspend fun upsertInstall(install: InstallEntity)

    @Query("SELECT lease_id FROM download_jobs WHERE id = :id") suspend fun leaseOf(id: Long): String?

    /** Install row in, job (and its parts, by cascade) out — iff the caller still holds the lease. */
    @Transaction
    suspend fun completeJob(id: Long, lease: String, install: InstallEntity): Boolean {
        if (leaseOf(id) != lease) return false
        upsertInstall(install)
        deleteJob(id)
        return true
    }

    @Query(
        """SELECT i.rom_id, COALESCE(r.title, i.rel_path) AS title, i.rel_path, i.bytes, i.installed_at, i.verified_by
           FROM installs i LEFT JOIN roms r ON r.id = i.rom_id
           WHERE i.source = 'LOOM' ORDER BY i.installed_at DESC LIMIT :limit""",
    )
    fun observeRecentInstalls(limit: Int = 30): Flow<List<RecentInstall>>
}
