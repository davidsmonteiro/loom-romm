package ro.davidmontei.loom.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.Credentials
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.RommServiceFactory
import ro.davidmontei.loom.romm.api.RommApi
import java.util.concurrent.ConcurrentHashMap

class NotPairedException : Exception("Loom isn't paired with a RomM server yet")

/** The RomM server Loom is paired with: its address, the token, and a [RommApi] for it. */
class ActiveServer(
    settings: SettingsStore,
    private val credentials: CredentialStore,
    private val apiClient: OkHttpClient,
    scope: CoroutineScope,
) : AuthSource {

    private data class Snapshot(val server: ServerConfig?, val credentials: Credentials?, val loaded: Boolean)

    private val state: StateFlow<Snapshot> = combine(settings.settings, credentials.current) { s, c ->
        Snapshot(s.server, c, loaded = true)
    }.stateIn(scope, SharingStarted.Eagerly, Snapshot(null, null, loaded = false))

    private val apis = ConcurrentHashMap<HttpUrl, RommApi>()

    override fun serverUrl(): HttpUrl? = state.value.server?.url

    override fun token(): String? = state.value.credentials?.token

    val isPaired: Boolean get() = state.value.server != null && state.value.credentials != null

    /** The server's base URL. Throws [NotPairedException] when Loom isn't set up. */
    suspend fun baseUrl(): HttpUrl {
        credentials.awaitLoaded()
        // A worker started cold by WorkManager can get here before settings have been read once.
        val server = state.first { it.loaded && (it.credentials != null || credentials.current.value == null) }.server
            ?: throw NotPairedException()
        return server.url
    }

    suspend fun api(): RommApi = baseUrl().let { base -> apis.getOrPut(base) { RommServiceFactory.create(base, apiClient) } }

    suspend fun <T> call(block: suspend (RommApi) -> T): T = block(api())
}
