package ro.davidmontei.loom.romm.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `DeviceAuthInitPayload`: starts RomM's device-authorization pairing (RomM ≥ 5.0). */
@Serializable
data class DeviceAuthInitRequest(
    @SerialName("client_device_identifier") val clientDeviceIdentifier: String,
    val name: String,
    val client: String,
    val platform: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    @SerialName("requested_scopes") val requestedScopes: List<String>,
)

/** `DeviceAuthInitResponse`. The verification paths are relative to the server origin. */
@Serializable
data class DeviceAuthInitResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_path") val verificationPath: String,
    @SerialName("verification_path_complete") val verificationPathComplete: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

/** `DeviceAuthTokenPayload`. */
@Serializable
data class DeviceAuthTokenRequest(
    @SerialName("device_code") val deviceCode: String,
)

/** `DeviceAuthTokenResponse`: a device-bound client token, returned exactly once. */
@Serializable
data class DeviceAuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("device_id") val deviceId: String,
    val scopes: List<String>,
    @SerialName("expires_at") val expiresAt: String?,
)

/** `ClientTokenExchangePayload`: the 8-character code shown by RomM's web UI ("Pair device"). */
@Serializable
data class ClientTokenExchangeRequest(
    val code: String,
)

/** `ClientTokenCreateSchema`: what the pair-code exchange returns. */
@Serializable
data class ClientTokenCreateDto(
    val id: Int,
    val name: String,
    val scopes: List<String>,
    @SerialName("expires_at") val expiresAt: String?,
    @SerialName("user_id") val userId: Int,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("raw_token") val rawToken: String,
)
