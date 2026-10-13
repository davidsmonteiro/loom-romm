package ro.davidmontei.loom.romm.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import ro.davidmontei.loom.romm.dto.ClientTokenCreateDto
import ro.davidmontei.loom.romm.dto.ClientTokenExchangeRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenResponse
import ro.davidmontei.loom.romm.dto.DeviceDto
import ro.davidmontei.loom.romm.dto.HeartbeatDto
import ro.davidmontei.loom.romm.dto.PlatformDto
import ro.davidmontei.loom.romm.dto.RomDto
import ro.davidmontei.loom.romm.dto.RomPageDto
import ro.davidmontei.loom.romm.dto.SaveDeviceRequest
import ro.davidmontei.loom.romm.dto.SaveDto
import ro.davidmontei.loom.romm.dto.SyncCompleteRequest
import ro.davidmontei.loom.romm.dto.SyncCompleteResponseDto
import ro.davidmontei.loom.romm.dto.SyncNegotiateRequest
import ro.davidmontei.loom.romm.dto.SyncNegotiateResponseDto
import ro.davidmontei.loom.romm.dto.UserDto
import java.io.File

/**
 * The slice of RomM's API Loom uses. Paths are relative ("api/…") so a RomM served under a
 * sub-path keeps working; the base URL always ends with "/". ROM file bytes are not fetched
 * through Retrofit: the download engine streams them with Range support (see [RommUrls]).
 */
interface RommApi {
    @GET("api/heartbeat")
    suspend fun heartbeat(): HeartbeatDto

    @GET("api/users/me")
    suspend fun me(): UserDto

    @POST("api/auth/device/init")
    suspend fun deviceAuthInit(@Body body: DeviceAuthInitRequest): DeviceAuthInitResponse

    @POST("api/auth/device/token")
    suspend fun deviceAuthToken(@Body body: DeviceAuthTokenRequest): DeviceAuthTokenResponse

    @POST("api/client-tokens/exchange")
    suspend fun exchangePairCode(@Body body: ClientTokenExchangeRequest): ClientTokenCreateDto

    @GET("api/platforms")
    suspend fun platforms(@Query("updated_after") updatedAfter: String? = null): List<PlatformDto>

    /**
     * One page of ROMs. The defaults switch off the server-side indexes a sync client doesn't
     * need (char index, filter values, full id index) and ask for each ROM's files.
     */
    @GET("api/roms")
    suspend fun roms(
        @Query("offset") offset: Int,
        @Query("limit") limit: Int,
        @Query("updated_after") updatedAfter: String? = null,
        @Query("platform_ids") platformIds: List<Int>? = null,
        @Query("order_by") orderBy: String = "id",
        @Query("order_dir") orderDir: String = "asc",
        @Query("with_files") withFiles: Boolean = true,
        @Query("with_total") withTotal: Boolean = true,
        @Query("with_char_index") withCharIndex: Boolean = false,
        @Query("with_filter_values") withFilterValues: Boolean = false,
        @Query("with_rom_id_index") withRomIdIndex: Boolean = false,
    ): RomPageDto

    /** Every ROM id visible to the caller; diffed locally to detect deletions. */
    @GET("api/roms/identifiers")
    suspend fun romIdentifiers(): List<Int>

    /** Fresh detail (always includes `files`), fetched right before a download is planned. */
    @GET("api/roms/{id}")
    suspend fun rom(@Path("id") id: Int): RomDto

    // --- Save sync. RomM pairs saves on (rom_id, slot); see SaveSyncDtos. ---

    /**
     * Plans a sync. It also cancels this device's other open sessions, so sync runs must never
     * overlap.
     */
    @POST("api/sync/negotiate")
    suspend fun negotiateSync(@Body body: SyncNegotiateRequest): SyncNegotiateResponseDto

    /** Closes a session; 400 once a later negotiate has cancelled it. */
    @POST("api/sync/sessions/{session_id}/complete")
    suspend fun completeSyncSession(
        @Path("session_id") sessionId: Int,
        @Body body: SyncCompleteRequest,
    ): SyncCompleteResponseDto

    /**
     * Adds a version to a slot. With [deviceId] and `overwrite=false`, RomM answers 409
     * ([ro.davidmontei.loom.romm.RommException.Conflict]) when the slot has a version this
     * device never synced. If any version in the slot already has the same bytes, RomM returns
     * that row instead of a new one. [contentHash] is read by RomM 5.4.0 as this device's
     * baseline; 5.3.1 ignores it. [saveFile] comes from [saveFilePart].
     */
    @Multipart
    @POST("api/saves")
    suspend fun uploadSave(
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String,
        @Query("slot") slot: String,
        @Query("device_id") deviceId: String,
        @Query("session_id") sessionId: Int?,
        @Query("content_hash") contentHash: String?,
        @Query("overwrite") overwrite: Boolean = false,
        @Query("autocleanup") autocleanup: Boolean = true,
        @Query("autocleanup_limit") autocleanupLimit: Int = SAVE_VERSIONS_KEPT,
        @Part saveFile: MultipartBody.Part,
    ): SaveDto

    /** Every version of the matching saves, with their sync rows, in no particular order. */
    @GET("api/saves")
    suspend fun saves(
        @Query("rom_id") romId: Int? = null,
        @Query("rom_ids") romIds: List<Int>? = null,
        @Query("slot") slot: String? = null,
        @Query("device_id") deviceId: String? = null,
    ): List<SaveDto>

    /**
     * A save's bytes, streamed. `optimistic=false` is fixed in the path: the default would
     * record this device as synced before Loom has stored anything. Confirm with
     * [confirmSaveDownloaded] once the save is safely in place.
     */
    @Streaming
    @GET("api/saves/{id}/content?optimistic=false")
    suspend fun saveContent(
        @Path("id") saveId: Int,
        @Query("device_id") deviceId: String,
        @Query("session_id") sessionId: Int?,
    ): ResponseBody

    /** Records that this device now holds [saveId]: its sync row, which uploads are checked against. */
    @POST("api/saves/{id}/downloaded")
    suspend fun confirmSaveDownloaded(@Path("id") saveId: Int, @Body body: SaveDeviceRequest): SaveDto

    /**
     * Marks this one save version untracked on the device: negotiate answers `no_op` for it, with
     * no server time or hash. It is not a per-game switch. In 5.3.1 the mark is per save id, so
     * once any client uploads a newer head, negotiate offers that head again. Untracking a version
     * the device never synced also records a sync row stamped "now", and [trackSave] leaves that
     * time, so RomM then treats the device as holding a version it never downloaded. Loom keeps
     * any "don't sync this game here" choice in its own state and leaves those games out before
     * deciding.
     */
    @POST("api/saves/{id}/untrack")
    suspend fun untrackSave(@Path("id") saveId: Int, @Body body: SaveDeviceRequest): SaveDto

    /** Clears [untrackSave]'s mark on this save version; the sync row's time stays as it was. */
    @POST("api/saves/{id}/track")
    suspend fun trackSave(@Path("id") saveId: Int, @Body body: SaveDeviceRequest): SaveDto

    @GET("api/devices/{device_id}")
    suspend fun device(@Path("device_id") deviceId: String): DeviceDto
}

/** Versions RomM keeps per slot when Loom uploads (`autocleanup_limit`). */
const val SAVE_VERSIONS_KEPT = 10

/** The multipart field RomM reads an uploaded save from. */
const val SAVE_FILE_FIELD = "saveFile"

/** [file] as the `saveFile` part of [RommApi.uploadSave]; RomM keeps [fileName] (plus a timestamp). */
fun saveFilePart(file: File, fileName: String): MultipartBody.Part =
    MultipartBody.Part.createFormData(SAVE_FILE_FIELD, fileName, file.asRequestBody(OCTET_STREAM))

private val OCTET_STREAM = "application/octet-stream".toMediaType()
