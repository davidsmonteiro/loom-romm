package ro.davidmontei.loom.data.sent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import ro.davidmontei.loom.net.PROXY_CANT_REACH
import ro.davidmontei.loom.romm.socket.DeviceEvent
import ro.davidmontei.loom.romm.socket.reconnecting

/**
 * While Loom is in front, RomM's device socket nudges it the moment a game is sent, so the game arrives
 * at once rather than at the next claim. The socket only nudges: what to install always comes from a
 * claim. It is open only while Loom is in front (nothing keeps a socket alive in the background, where
 * the periodic claim takes over), and connects again after a drop as [reconnecting] says.
 *
 * While it isn't connected, Loom claims every [pollEveryMs] instead, which also keeps the device shown
 * online in RomM's "Install on device" (for 90 s after each claim). A front that answers the upgrade
 * itself [GIVE_UP_AFTER] times in a row doesn't pass WebSockets: the socket isn't tried again until
 * Loom next comes to the front, and the claims carry on. A proxy saying it can't reach RomM
 * ([PROXY_CANT_REACH], as while RomM restarts behind Cloudflare) doesn't count: that passes, and the
 * socket is tried again as after a drop.
 */
class LiveNudges(
    private val connect: (baseUrl: HttpUrl, token: String) -> Flow<DeviceEvent>,
    private val pollEveryMs: Long = POLL_MS,
    private val waitBeforeReconnecting: suspend (Long) -> Unit = { delay(it) },
) {
    /** Where the socket connects, and as whom: the paired server, while games sent from it can come. */
    data class Target(val baseUrl: HttpUrl, val token: String) {
        override fun toString() = "Target(baseUrl=$baseUrl)"
    }

    /** What the socket says, for [SendToDevice] to act on. */
    interface Listener {
        /** Claim soon: the socket connected (nothing sent before is announced again), or a game was sent. */
        fun nudge()

        /** The owner cancelled request [requestId] on RomM. */
        suspend fun cancelled(requestId: String)

        /** RomM refused the device for [token]: [message] says why (see [DeviceEvent.Refused]). */
        suspend fun refused(message: String, token: String)
    }

    private val front = MutableStateFlow(false)

    fun inFront(inFront: Boolean) {
        front.value = inFront
    }

    /**
     * Keeps a socket open to [targets]' latest while Loom is in front: moved when the address or the
     * token changes, closed when Loom leaves the front or the target goes (null).
     */
    suspend fun run(targets: Flow<Target?>, listener: Listener) {
        combine(front, targets) { inFront, target -> target?.takeIf { inFront } }.distinctUntilChanged().collectLatest { target ->
            if (target != null) live(target, listener)
        }
    }

    private suspend fun live(target: Target, listener: Listener): Unit = coroutineScope {
        val connected = MutableStateFlow(false)
        launch {
            while (true) {
                connected.first { !it }
                delay(pollEveryMs)
                if (!connected.value) listener.nudge()
            }
        }
        var upgradesRefused = 0
        reconnecting({ connect(target.baseUrl, target.token) }, delay = waitBeforeReconnecting)
            .onEach {
                if (it is DeviceEvent.UpgradeRefused && it.code !in PROXY_CANT_REACH) upgradesRefused++
                if (it == DeviceEvent.Connected) upgradesRefused = 0
            }
            .takeWhile { upgradesRefused < GIVE_UP_AFTER }
            .collect { event ->
                when (event) {
                    DeviceEvent.Connected -> {
                        connected.value = true
                        listener.nudge()
                    }
                    is DeviceEvent.InstallQueued -> listener.nudge()
                    is DeviceEvent.InstallCancelled -> listener.cancelled(event.requestId)
                    is DeviceEvent.Refused -> {
                        connected.value = false
                        listener.refused(event.message, target.token)
                    }
                    is DeviceEvent.UpgradeRefused, is DeviceEvent.Dropped -> connected.value = false
                }
            }
        // No socket (refused, or a front that won't pass it) until Loom next comes to the front: claims carry on.
        awaitCancellation()
    }

    companion object {
        /** How often Loom claims while in front without a socket. */
        const val POLL_MS = 60_000L

        /** Upgrades answered by something other than RomM, in a row, before the socket is left alone. */
        const val GIVE_UP_AFTER = 3
    }
}
