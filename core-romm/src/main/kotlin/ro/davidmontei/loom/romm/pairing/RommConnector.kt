package ro.davidmontei.loom.romm.pairing

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import ro.davidmontei.loom.romm.MIN_SUPPORTED_ROMM
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommServiceFactory
import ro.davidmontei.loom.romm.RommUrls
import ro.davidmontei.loom.romm.RommVersion
import ro.davidmontei.loom.romm.dto.ClientTokenExchangeRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitRequest
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse
import ro.davidmontei.loom.romm.dto.UserDto
import ro.davidmontei.loom.romm.rommCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Scopes Loom asks for. Assets and devices are for Phase 2 save sync, requested now so the user
 * doesn't have to pair again later.
 */
val LOOM_SCOPES = listOf(
    "me.read", "platforms.read", "roms.read",
    "assets.read", "assets.write", "devices.read", "devices.write",
)

/** What identifies this install to RomM when pairing. */
data class DeviceIdentity(
    /** Stable random id generated on first launch; re-pairing reuses RomM's Device record. */
    val clientDeviceIdentifier: String,
    /** Shown in RomM's device list, e.g. "AYN Thor (Loom)". */
    val name: String,
    val clientVersion: String,
)

/** A server that answered the heartbeat. */
data class ServerProbe(val baseUrl: HttpUrl, val version: String, val parsed: RommVersion?)

/** A device-authorization code to show the user, with absolute URLs for the QR code. */
data class DeviceAuthSession(
    val baseUrl: HttpUrl,
    val userCode: String,
    val verificationUrl: HttpUrl,
    val verificationUrlComplete: HttpUrl,
    val expiresInSeconds: Int,
    val raw: DeviceAuthInitResponse,
) {
    /** RomM displays codes as XXXX-XXXX; so does Loom. */
    val displayCode: String get() = if (userCode.length == 8) "${userCode.take(4)}-${userCode.drop(4)}" else userCode
}

/** Credentials that worked: validated against `GET /api/users/me` before being returned. */
data class PairedCredentials(
    val baseUrl: HttpUrl,
    val token: String,
    val deviceId: String?,
    val username: String,
    val expiresAt: String?,
)

class UnsupportedServerException(val version: String) :
    Exception("RomM $version is too old: Loom needs RomM $MIN_SUPPORTED_ROMM or newer")

/**
 * Finds a server and pairs with it. Stateless: the app stores what comes back.
 * [client] must not carry an auth interceptor or cookie jar; per-call auth is added here.
 */
class RommConnector(private val client: OkHttpClient) {

    /** Probing an address the user typed should fail fast, not sit on a 60 s read timeout. */
    private val probeClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Tries each candidate from [input] (see [RommUrls.candidates]) and returns the first that
     * answers the heartbeat as RomM. Throws the last failure if none do, or
     * [UnsupportedServerException] for RomM older than [MIN_SUPPORTED_ROMM].
     */
    suspend fun probe(input: String): ServerProbe {
        val candidates = RommUrls.candidates(input)
        if (candidates.isEmpty()) throw IllegalArgumentException("Enter the address of your RomM server")
        var last: Exception? = null
        for (base in candidates) {
            try {
                // OkHttp's timeouts can't interrupt a blocked system DNS lookup (seen on the Thor
                // when Tailscale's DNS couldn't resolve the public name), so cap it at this level.
                val version = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                        rommCall { RommServiceFactory.create(base, probeClient).heartbeat() }.system.version
                    }
                } ?: throw RommException.Unreachable(java.io.IOException("No answer from ${base.host} within ${PROBE_TIMEOUT_MS / 1000} s"))
                val parsed = RommVersion.parse(version)
                if (parsed != null && parsed < MIN_SUPPORTED_ROMM) throw UnsupportedServerException(version)
                return ServerProbe(base, version, parsed)
            } catch (e: RommException) {
                last = e
            }
        }
        throw last!!
    }

    suspend fun startDeviceAuth(baseUrl: HttpUrl, device: DeviceIdentity): DeviceAuthSession {
        val response = rommCall {
            RommServiceFactory.create(baseUrl, client).deviceAuthInit(
                DeviceAuthInitRequest(
                    clientDeviceIdentifier = device.clientDeviceIdentifier,
                    name = device.name,
                    client = CLIENT_NAME,
                    platform = "android",
                    clientVersion = device.clientVersion,
                    requestedScopes = LOOM_SCOPES,
                ),
            )
        }
        return DeviceAuthSession(
            baseUrl = baseUrl,
            userCode = response.userCode,
            verificationUrl = RommUrls.verificationUrl(baseUrl, response.verificationPath),
            verificationUrlComplete = RommUrls.verificationUrl(baseUrl, response.verificationPathComplete),
            expiresInSeconds = response.expiresIn,
            raw = response,
        )
    }

    /** Waits for approval in the web UI, then validates the token. */
    suspend fun awaitDeviceAuth(session: DeviceAuthSession, poller: DeviceAuthPoller? = null): DeviceAuthResult {
        val p = poller ?: DeviceAuthPoller(RommServiceFactory.create(session.baseUrl, client))
        return when (val outcome = p.await(session.raw)) {
            is DeviceAuthOutcome.Approved -> {
                val user = validateToken(session.baseUrl, outcome.token)
                DeviceAuthResult.Paired(
                    PairedCredentials(session.baseUrl, outcome.token, outcome.deviceId, user.username, outcome.expiresAt),
                )
            }
            DeviceAuthOutcome.Denied -> DeviceAuthResult.Denied
            DeviceAuthOutcome.Expired -> DeviceAuthResult.Expired
        }
    }

    /**
     * Exchanges the 8-character code from RomM's web UI ("API tokens → Pair device") for a
     * token. Note RomM rotates that token's secret on exchange.
     */
    suspend fun exchangePairCode(baseUrl: HttpUrl, code: String): PairedCredentials {
        val normalized = normalizePairCode(code)
            ?: throw IllegalArgumentException("A pairing code has 8 letters and digits, like ABCD-2345")
        val created = rommCall {
            RommServiceFactory.create(baseUrl, client).exchangePairCode(ClientTokenExchangeRequest(normalized))
        }
        val user = validateToken(baseUrl, created.rawToken)
        return PairedCredentials(baseUrl, created.rawToken, created.deviceId, user.username, created.expiresAt)
    }

    /** Accepts a pasted token after checking it against the server. */
    suspend fun usePastedToken(baseUrl: HttpUrl, token: String): PairedCredentials {
        val trimmed = token.trim().removePrefix("Bearer ").trim()
        require(trimmed.isNotEmpty()) { "Paste a RomM API token" }
        val user = validateToken(baseUrl, trimmed)
        return PairedCredentials(baseUrl, trimmed, null, user.username, null)
    }

    suspend fun validateToken(baseUrl: HttpUrl, token: String): UserDto {
        val authed = client.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            }
            .build()
        val user = rommCall { RommServiceFactory.create(baseUrl, authed).me() }
        if (!user.enabled) throw RommException.Forbidden("This RomM user is disabled")
        return user
    }

    companion object {
        const val CLIENT_NAME = "loom"
        const val PROBE_TIMEOUT_MS = 8_000L

        /** Strips spaces and dashes, uppercases, and checks for 8 letters/digits. */
        fun normalizePairCode(input: String): String? {
            val code = input.filterNot { it == '-' || it.isWhitespace() }.uppercase()
            return code.takeIf { it.length == 8 && it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }
        }
    }
}

sealed interface DeviceAuthResult {
    data class Paired(val credentials: PairedCredentials) : DeviceAuthResult
    data object Denied : DeviceAuthResult
    data object Expired : DeviceAuthResult
}
