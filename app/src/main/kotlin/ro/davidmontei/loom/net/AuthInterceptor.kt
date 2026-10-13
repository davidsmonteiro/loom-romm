package ro.davidmontei.loom.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import ro.davidmontei.loom.romm.RommUrls

/** What the interceptor needs to know, read without blocking on every request. */
interface AuthSource {
    /** The configured RomM server; the token is only ever sent to its host and port. */
    fun serverUrl(): HttpUrl?
    fun token(): String?
}

/**
 * Adds `Authorization: Bearer …` to requests for the configured RomM server only, so the token
 * never leaks to a cover CDN (IGDB) or any other host, and leaves requests that already carry an
 * Authorization header (pairing validation) untouched.
 */
class AuthInterceptor(private val source: AuthSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val token = source.token() ?: return chain.proceed(request)
        val ours = source.serverUrl()?.let { RommUrls.sameServer(it, request.url) } ?: false
        if (!ours) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
