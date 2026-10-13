package ro.davidmontei.loom.romm.pairing

import kotlinx.coroutines.delay
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.dto.DeviceAuthInitResponse
import ro.davidmontei.loom.romm.dto.DeviceAuthTokenRequest
import ro.davidmontei.loom.romm.rommCall

/** How a device-authorization attempt ended. */
sealed interface DeviceAuthOutcome {
    data class Approved(
        val token: String,
        val deviceId: String,
        val scopes: List<String>,
        val expiresAt: String?,
    ) : DeviceAuthOutcome

    /** The user pressed "Deny" in RomM's web UI. */
    data object Denied : DeviceAuthOutcome

    /** Nobody approved before the code expired (RomM: 10 minutes). Start over with a new code. */
    data object Expired : DeviceAuthOutcome
}

/**
 * Polls `POST /api/auth/device/token` until the user approves or denies the code, following
 * RFC 8628 semantics as RomM implements them: errors arrive as HTTP 400 with `detail` set to
 * `authorization_pending`, `slow_down`, `access_denied` or `expired_token`.
 *
 * - waits the server's `interval` between polls; `slow_down` adds 5 s (capped at 30 s);
 * - gives up at `expires_in` measured on [clockMs] (monotonic in production);
 * - rides out up to [maxTransientFailures] consecutive network/5xx errors with doubling backoff.
 */
class DeviceAuthPoller(
    private val api: RommApi,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxTransientFailures: Int = 5,
) {
    suspend fun await(session: DeviceAuthInitResponse): DeviceAuthOutcome {
        val deadline = clockMs() + session.expiresIn * 1000L
        var intervalMs = session.interval.coerceAtLeast(1) * 1000L
        var failures = 0
        while (true) {
            delay(intervalMs)
            if (clockMs() >= deadline) return DeviceAuthOutcome.Expired
            try {
                val token = rommCall { api.deviceAuthToken(DeviceAuthTokenRequest(session.deviceCode)) }
                return DeviceAuthOutcome.Approved(token.accessToken, token.deviceId, token.scopes, token.expiresAt)
            } catch (e: RommException.Http) {
                when (e.detail) {
                    "authorization_pending" -> failures = 0
                    "slow_down" -> { failures = 0; intervalMs = (intervalMs + 5_000).coerceAtMost(30_000) }
                    "access_denied" -> return DeviceAuthOutcome.Denied
                    "expired_token" -> return DeviceAuthOutcome.Expired
                    else -> if (e.code >= 500 && ++failures <= maxTransientFailures) {
                        delay(backoff(failures))
                    } else {
                        throw e
                    }
                }
            } catch (e: RommException.RateLimited) {
                intervalMs = (intervalMs + 5_000).coerceAtMost(30_000)
            } catch (e: RommException.Unreachable) {
                if (++failures > maxTransientFailures) throw e
                delay(backoff(failures))
            }
        }
    }

    private fun backoff(failures: Int): Long = (1_000L shl (failures - 1)).coerceAtMost(16_000)
}
