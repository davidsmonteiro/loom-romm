package ro.davidmontei.loom.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A download job: one ROM on its way to becoming an install. Deleted when it finishes (the
 * install row takes over) or when a cancel is cleaned up. Phase/wait/failure mirror
 * [ro.davidmontei.loom.storage.engine.JobStatus]; [leaseId] is set while a worker owns it.
 */
@Entity(
    tableName = "download_jobs",
    indices = [Index(value = ["rom_id"], unique = true), Index(value = ["phase", "queue_order"])],
)
data class DownloadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "rom_id") val romId: Int,
    @ColumnInfo(name = "platform_id") val platformId: Int,
    val title: String,
    val phase: String,
    @ColumnInfo(name = "wait_reason") val waitReason: String? = null,
    @ColumnInfo(name = "failure_code") val failureCode: String? = null,
    @ColumnInfo(name = "failure_detail") val failureDetail: String? = null,
    @ColumnInfo(name = "failure_retryable") val failureRetryable: Boolean = true,
    @ColumnInfo(name = "lease_id") val leaseId: String? = null,
    val attempt: Int = 0,
    val generation: Int = 1,
    @ColumnInfo(name = "checksum_retries") val checksumRetries: Int = 0,
    @ColumnInfo(name = "plan_json") val planJson: String,
    val fingerprint: String,
    @ColumnInfo(name = "volume_uuid") val volumeUuid: String,
    @ColumnInfo(name = "staging_root") val stagingRoot: String,
    @ColumnInfo(name = "roms_root") val romsRoot: String,
    @ColumnInfo(name = "final_rel_path") val finalRelPath: String? = null,
    /** Case-folded [finalRelPath] for collision checks. */
    @ColumnInfo(name = "final_key") val finalKey: String? = null,
    @ColumnInfo(name = "final_kind") val finalKind: String? = null,
    @ColumnInfo(name = "bytes_done") val bytesDone: Long = 0,
    @ColumnInfo(name = "bytes_total") val bytesTotal: Long,
    @ColumnInfo(name = "queue_order") val queueOrder: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One ROM file of a job. The immutable plan is JSON; the rest tracks what's safely on disk. */
@Entity(
    tableName = "download_parts",
    primaryKeys = ["job_id", "rom_file_id"],
    foreignKeys = [ForeignKey(DownloadJobEntity::class, ["id"], ["job_id"], onDelete = ForeignKey.CASCADE)],
)
data class DownloadPartEntity(
    @ColumnInfo(name = "job_id") val jobId: Long,
    @ColumnInfo(name = "rom_file_id") val romFileId: Int,
    @ColumnInfo(name = "plan_json") val planJson: String,
    @ColumnInfo(name = "rel_url") val relUrl: String,
    @ColumnInfo(name = "durable_bytes") val durableBytes: Long = 0,
    val validator: String? = null,
    val digest: String? = null,
    val state: String,
)
