package ro.davidmontei.loom.romm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Chooses which of the server's addresses to use: the primary (e.g. a Tailnet or LAN address)
 * when it answers the heartbeat, otherwise the fallback (e.g. the public URL). The choice is
 * cached for [ttlMs] and dropped on [invalidate] (network change, connection error).
 */
class ServerAddressSelector(
    probeClient: OkHttpClient,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = 60_000,
) {
    private val client = probeClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private var cached: Pair<List<HttpUrl>, HttpUrl>? = null
    private var cachedAt = 0L

    /** First reachable address in [addresses] order; throws the last failure if none answers. */
    suspend fun select(addresses: List<HttpUrl>): HttpUrl = mutex.withLock {
        require(addresses.isNotEmpty()) { "No server address configured" }
        cached?.let { (forList, url) ->
            if (forList == addresses && clockMs() - cachedAt < ttlMs) return url
        }
        if (addresses.size == 1) return remember(addresses, addresses.single())
        var last: Exception? = null
        for (url in addresses) {
            try {
                rommCall { RommServiceFactory.create(url, client).heartbeat() }
                return remember(addresses, url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RommException) {
                last = e
            }
        }
        throw last!!
    }

    fun invalidate() {
        cached = null
    }

    private fun remember(addresses: List<HttpUrl>, url: HttpUrl): HttpUrl {
        cached = addresses to url
        cachedAt = clockMs()
        return url
    }
}
