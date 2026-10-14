package ro.davidmontei.loom.net

import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okhttp3.Response
import ro.davidmontei.loom.romm.RommUrls
import java.io.IOException

/**
 * Whether RomM answered the last time Loom tried to reach it. Fed by the requests Loom makes anyway
 * (syncs, covers, downloads, save sync: see [ReachabilityInterceptor]) and by [ReachabilityWatch]'s
 * heartbeats; nothing else polls.
 */
class ServerReachability {
    enum class State {
        /** Loom hasn't tried yet. */
        UNKNOWN,
        /** RomM answered, whatever it said. */
        REACHABLE,
        /** No answer: no network, RomM's address out of reach (away from home, Tailscale off), or RomM down. */
        UNREACHABLE,
    }

    private val state = MutableStateFlow(State.UNKNOWN)
    val current: StateFlow<State> = state.asStateFlow()

    fun answered() { state.value = State.REACHABLE }
    fun unreachable() { state.value = State.UNREACHABLE }
}

/**
 * Reports each request to RomM ([server]'s address) to [reachability]: any answer from RomM means it
 * can be reached; no answer at all, or a proxy saying it can't reach RomM (a 502, 503, 504, or
 * Cloudflare's 52x), means it can't. A request Loom cancelled, or one to another host (a cover RomM
 * links to elsewhere), says nothing either way.
 */
class ReachabilityInterceptor(private val reachability: ServerReachability, private val server: AuthSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (server.serverUrl()?.let { RommUrls.sameServer(it, request.url) } != true) return chain.proceed(request)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!chain.call().isCanceled()) reachability.unreachable()
            throw e
        }
        if (response.code in PROXY_CANT_REACH) reachability.unreachable() else reachability.answered()
        return response
    }

    private companion object {
        val PROXY_CANT_REACH = setOf(502, 503, 504, 520, 521, 522, 523, 524, 530)
    }
}

/**
 * Looks again whether RomM can be reached when there's reason to, with one heartbeat ([probe]): when
 * Loom comes to the front, when the network changes, and, while RomM can't be reached and Loom is in
 * front, now and then, from [FIRST_RETRY_MS] apart up to [MAX_RETRY_MS]. In the background it never
 * asks. Run it with [run], and [follow] the app.
 */
class ReachabilityWatch(private val reachability: ServerReachability, private val probe: suspend () -> Unit) {
    private val front = MutableStateFlow(false)
    private val networkGeneration = MutableStateFlow(0)

    fun inFront(inFront: Boolean) { front.value = inFront }

    /** A network came or went: what was true of RomM before may not be now. */
    fun networkChanged() { networkGeneration.value++ }

    /**
     * Follows the app: in front from [lifecycle]'s start to its stop (the process's), and the default
     * network coming and going (Wi-Fi joined or left, a VPN up or down).
     */
    fun follow(lifecycle: Lifecycle, connectivity: ConnectivityManager?) {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = inFront(true)
            override fun onStop(owner: LifecycleOwner) = inFront(false)
        })
        connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()
            override fun onLost(network: Network) = networkChanged()
        })
    }

    suspend fun run() {
        var lastGeneration = networkGeneration.value
        combine(front, networkGeneration, ::Pair).collectLatest { (inFront, generation) ->
            val newNetwork = generation != lastGeneration
            lastGeneration = generation
            if (!inFront) return@collectLatest
            // A new network (Wi-Fi joined, a VPN up) needs a moment before RomM's address works through
            // it. One that came while Loom was away has had it: coming to the front looks at once.
            if (newNetwork) delay(NETWORK_SETTLE_MS)
            look()
            var wait = FIRST_RETRY_MS
            while (true) {
                if (reachability.current.value != ServerReachability.State.UNREACHABLE) {
                    reachability.current.first { it == ServerReachability.State.UNREACHABLE }
                    wait = FIRST_RETRY_MS
                }
                delay(wait)
                look()
                wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
    }

    private suspend fun look() {
        try {
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Its outcome has reached [reachability] through the interceptor; not paired yet says nothing.
        }
    }

    companion object {
        const val NETWORK_SETTLE_MS = 2_000L
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 5 * 60_000L
    }
}
