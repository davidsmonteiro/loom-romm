package ro.davidmontei.loom.romm.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Save sync: RomM 5.4.0's endpoints/sync, endpoints/saves.py and endpoints/device, declaring only
// what Loom reads, like CatalogDtos. A response field 5.0 to 5.3 don't send is optional with a
// default (RommSchemaConformanceTest checks them against 5.3.1); a request field they don't know
// is dropped unread, so Loom sends 5.4.0's without asking the server's version first.

/** Most ROM ids one negotiate call may scope to (`utils/validation.py`). */
const val MAX_NEGOTIATE_ROM_IDS = 500

/** `ClientSaveState`: one local save as Loom reports it to negotiate. */
@Serializable
data class ClientSaveStateDto(
    @SerialName("rom_id") val romId: Int,
    /** Only echoed back in `upload` operations. */
    @SerialName("file_name") val fileName: String,
    /** Never null: RomM never pairs a null-slot save, so it would always come back as `upload`. */
    val slot: String,
    val emulator: String?,
    @SerialName("content_hash") val contentHash: String?,
    /** ISO-8601; RomM reads a value without an offset as UTC. */
    @SerialName("updated_at") val updatedAt: String,
    /** Required by the schema, unused by the algorithm. */
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
)

/** `SyncNegotiatePayload`. Negotiating cancels this device's other open sync sessions. */
@Serializable
data class SyncNegotiateRequest(
    /** Falls back to the device the token is bound to. */
    @SerialName("device_id") val deviceId: String?,
    val saves: List<ClientSaveStateDto>,
    /**
     * Always sent: without it the user's whole save library is in scope and every slot head
     * Loom didn't mention comes back as a download. Empty means "only the ROMs in [saves]".
     */
    @SerialName("rom_ids") val romIds: List<Int>,
) {
    init {
        require(romIds.size <= MAX_NEGOTIATE_ROM_IDS) { "RomM takes at most $MAX_NEGOTIATE_ROM_IDS rom_ids per call" }
    }
}

/** `SyncNegotiateResponse`. */
@Serializable
data class SyncNegotiateResponseDto(
    @SerialName("session_id") val sessionId: Int,
    val operations: List<SyncOperationDto>,
    @SerialName("total_upload") val totalUpload: Int,
    @SerialName("total_download") val totalDownload: Int,
    @SerialName("total_conflict") val totalConflict: Int,
    @SerialName("total_no_op") val totalNoOp: Int,
    /** RomM 5.4.0 (required there); absent from 5.3.1. */
    @SerialName("total_delete") val totalDelete: Int = 0,
)

/** `SyncOperationSchema`. [action] stays a string so a value from a newer server still parses. */
@Serializable
data class SyncOperationDto(
    val action: String,
    @SerialName("rom_id") val romId: Int,
    /** The slot head's id; null for uploads (and 5.4.0 deletes). */
    @SerialName("save_id") val saveId: Int?,
    /** The server's tagged name ("Game [2026-10-13_17-49-45].zip") except for uploads. */
    @SerialName("file_name") val fileName: String,
    val slot: String?,
    /** The server save's emulator, except for uploads where it echoes Loom's. */
    val emulator: String?,
    val reason: String,
    /** Null for uploads and for a save this device untracked. */
    @SerialName("server_updated_at") val serverUpdatedAt: String?,
    @SerialName("server_content_hash") val serverContentHash: String?,
) {
    val syncAction: SyncAction get() = SyncAction.of(action)
}

/** What negotiate asks of the device. */
enum class SyncAction(val wire: String?) {
    UPLOAD("upload"),
    DOWNLOAD("download"),
    CONFLICT("conflict"),
    NO_OP("no_op"),

    /** RomM 5.4.0: the slot was emptied on the server while the device still holds a copy. */
    DELETE("delete"),

    /** Anything a newer server sends that Loom doesn't know. */
    UNKNOWN(null),
    ;

    companion object {
        fun of(wire: String): SyncAction = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/** `SyncCompletePayload`. RomM overwrites the session's counters with these. */
@Serializable
data class SyncCompleteRequest(
    @SerialName("operations_completed") val operationsCompleted: Int,
    @SerialName("operations_failed") val operationsFailed: Int,
)

/** `SyncCompleteResponse`. */
@Serializable
data class SyncCompleteResponseDto(
    val session: SyncSessionDto,
)

/** `SyncSessionSchema`. [status] is PENDING, IN_PROGRESS, COMPLETED, FAILED or CANCELLED. */
@Serializable
data class SyncSessionDto(
    val id: Int,
    val status: String,
    @SerialName("operations_planned") val operationsPlanned: Int,
    @SerialName("operations_completed") val operationsCompleted: Int,
    @SerialName("operations_failed") val operationsFailed: Int,
)

/** `SaveSchema`: one version in a slot's history. */
@Serializable
data class SaveDto(
    val id: Int,
    @SerialName("rom_id") val romId: Int,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
    @SerialName("missing_from_fs") val missingFromFs: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val emulator: String?,
    val slot: String?,
    /** RomM's hash of the stored file; null when it couldn't compute one. */
    @SerialName("content_hash") val contentHash: String?,
    /** The device that uploaded it; null for the web UI and older rows. */
    @SerialName("origin_device_id") val originDeviceId: String?,
    /** With `device_id` in the request, the caller's own entry comes first. */
    @SerialName("device_syncs") val deviceSyncs: List<DeviceSyncDto> = emptyList(),
)

/** `DeviceSyncSchema`: a device's sync row for one save. */
@Serializable
data class DeviceSyncDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String?,
    @SerialName("last_synced_at") val lastSyncedAt: String,
    @SerialName("is_untracked") val isUntracked: Boolean,
    @SerialName("is_current") val isCurrent: Boolean,
)

/** Body of `/api/saves/{id}/track` and `/untrack`. */
@Serializable
data class SaveDeviceRequest(
    @SerialName("device_id") val deviceId: String,
)

/** Body of `/api/saves/{id}/downloaded`. */
@Serializable
data class SaveDownloadedRequest(
    @SerialName("device_id") val deviceId: String,
    /**
     * RomM's own hash of the save, which Loom checked the bytes against; left out when RomM has
     * none. RomM 5.4.0 keeps it as this device's baseline only when it equals the save's hash, and
     * clears the baseline when it is missing or differs; older RomMs ignore it.
     */
    @SerialName("content_hash") val contentHash: String? = null,
)

/** `DeviceSchema`: named in the conflict view ("uploaded from Steam Deck"). */
@Serializable
data class DeviceDto(
    val id: String,
    val name: String?,
    val platform: String?,
    val client: String?,
    @SerialName("client_version") val clientVersion: String?,
    @SerialName("client_device_identifier") val clientDeviceIdentifier: String?,
    @SerialName("sync_enabled") val syncEnabled: Boolean,
    @SerialName("last_seen") val lastSeen: String?,
)
