package ro.davidmontei.loom.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.Credentials
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommServiceFactory
import ro.davidmontei.loom.romm.ServerAddressSelector
import ro.davidmontei.loom.romm.api.RommApi
import java.util.concurrent.ConcurrentHashMap

class NotPairedException : Exception("Loom isn't paired with a RomM server yet")

/**
 * The RomM server Loom is paired with: its addresses, the token, and a [RommApi] bound to
 * whichever address currently answers (see [ServerAddressSelector]).
 */
class ActiveServer(
    settings: SettingsStore,
    private val credentials: CredentialStore,
    private val apiClient: OkHttpClient,
    private val selector: ServerAddressSelector,
    scope: CoroutineScope,
) : AuthSource {

    private data class Snapshot(val server: ServerConfig?, val credentials: Credentials?)

    private val state: StateFlow<Snapshot> = combine(settings.settings, credentials.current) { s, c ->
        Snapshot(s.server, c)
    }.stateIn(scope, SharingStarted.Eagerly, Snapshot(null, null))

    private val apis = ConcurrentHashMap<HttpUrl, RommApi>()

    override fun serverAddresses(): List<HttpUrl> = state.value.server?.addresses.orEmpty()

    override fun token(): String? = state.value.credentials?.token

    val isPaired: Boolean get() = state.value.server != null && state.value.credentials != null

    /** Base URL to use right now. Throws [NotPairedException] or the selector's last failure. */
    suspend fun baseUrl(): HttpUrl {
        credentials.awaitLoaded()
        val server = state.value.server ?: throw NotPairedException()
        return selector.select(server.addresses)
    }

    suspend fun api(): RommApi = baseUrl().let { base -> apis.getOrPut(base) { RommServiceFactory.create(base, apiClient) } }

    /**
     * Runs [block] against the active address. If that address stops answering, the cached choice
     * is dropped and the call is retried once on whichever address answers next.
     */
    suspend fun <T> call(block: suspend (RommApi) -> T): T = try {
        block(api())
    } catch (e: RommException.Unreachable) {
        if (serverAddresses().size < 2) throw e
        selector.invalidate()
        block(api())
    }

    fun onNetworkChanged() = selector.invalidate()
}
