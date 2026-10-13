package ro.davidmontei.loom.romm.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ro.davidmontei.loom.romm.dto.ClientTokenCreateDto
import ro.davidmontei.loom.romm.dto.ClientTokenExchangeRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenResponse
import ro.davidmontei.loom.romm.dto.HeartbeatDto
import ro.davidmontei.loom.romm.dto.PlatformDto
import ro.davidmontei.loom.romm.dto.RomDto
import ro.davidmontei.loom.romm.dto.RomPageDto
import ro.davidmontei.loom.romm.dto.UserDto

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
}
