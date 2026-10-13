package ro.davidmontei.loom.romm

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import java.io.IOException

/** Everything that can go wrong talking to RomM, in terms the UI can act on. */
sealed class RommException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The server couldn't be reached at all (DNS, refused, timeout, TLS). */
    class Unreachable(cause: Throwable) : RommException(cause.message ?: "Server unreachable", cause)

    /** 401: no token, a revoked or expired token. The user has to pair again. */
    class Unauthorized(detail: String?) : RommException(detail ?: "Not signed in")

    /** 403: signed in, but the token lacks a scope. */
    class Forbidden(detail: String?) : RommException(detail ?: "Forbidden")

    class NotFound(detail: String?) : RommException(detail ?: "Not found")

    /**
     * 409: the server refused because its state moved on. For a save upload this is "Slot has a
     * newer save since your last sync": a save conflict, never something to retry blindly.
     */
    class Conflict(val detail: String?) : RommException(detail ?: "Conflict")

    /** 429: RomM rate-limits the pairing endpoints. */
    class RateLimited(detail: String?) : RommException(detail ?: "Too many requests")

    /** Any other non-2xx answer; [detail] is FastAPI's `detail` string when present. */
    class Http(val code: Int, val detail: String?) : RommException("HTTP $code${detail?.let { ": $it" } ?: ""}")

    /** The body didn't match the schema Loom was built against, or wasn't RomM at all. */
    class Protocol(message: String, cause: Throwable? = null) : RommException(message, cause)
}

/** FastAPI's error body: `{"detail": "text"}` or `{"detail": [{"msg": …}, …]}` for 422. */
fun parseDetail(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val obj = runCatching { RommJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    return when (val detail = obj["detail"]) {
        is JsonPrimitive -> detail.contentOrNull
        is JsonArray -> detail.mapNotNull { (it as? JsonObject)?.get("msg")?.let { m -> (m as? JsonPrimitive)?.contentOrNull } }
            .joinToString("; ").ifEmpty { null }
        else -> null
    }
}

/** Runs a Retrofit call and maps failures to [RommException]. Cancellation passes through. */
suspend fun <T> rommCall(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: RommException) {
    throw e
} catch (e: HttpException) {
    throw e.toRommException()
} catch (e: SerializationException) {
    throw RommException.Protocol("Unexpected response from server: ${e.message}", e)
} catch (e: IllegalArgumentException) {
    throw RommException.Protocol("Unexpected response from server: ${e.message}", e)
} catch (e: IOException) {
    throw RommException.Unreachable(e)
}

fun HttpException.toRommException(): RommException {
    val detail = parseDetail(response()?.errorBody()?.string())
    return when (code()) {
        401 -> RommException.Unauthorized(detail)
        403 -> RommException.Forbidden(detail)
        404 -> RommException.NotFound(detail)
        409 -> RommException.Conflict(detail)
        429 -> RommException.RateLimited(detail)
        else -> RommException.Http(code(), detail)
    }
}
