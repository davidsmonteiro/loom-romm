package ro.davidmontei.loom.romm.socket

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ro.davidmontei.loom.romm.RommUrls
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What RomM's device socket says, in order. A connection's last event is [Refused], [UpgradeRefused] or
 * [Dropped]; nothing comes after it.
 */
sealed interface DeviceEvent {
    /** RomM took the device in. Nothing sent meanwhile is announced again, so this is the time to claim. */
    data object Connected : DeviceEvent

    /** The owner sent a game to this device: claim it. */
    data class InstallQueued(val requestId: String, val romId: Int) : DeviceEvent

    /** The owner cancelled a request, waiting or already taken: drop the game if it's still to come. */
    data class InstallCancelled(val requestId: String, val romId: Int) : DeviceEvent

    /**
     * RomM refused the device, for good until something changes: [DeviceSocket.UNAUTHORIZED] (the token
     * isn't a live one bound to a device) or [DeviceSocket.DISABLED] (device installs are off on the server).
     */
    data class Refused(val message: String) : DeviceEvent

    /** The server, or a proxy in front of it, answered the WebSocket upgrade with [code], not 101. */
    data class UpgradeRefused(val code: Int) : DeviceEvent

    /** The connection ended: a network error, silence past the ping timeout, or the server closing it. */
    data class Dropped(val cause: Throwable?) : DeviceEvent
}

/**
 * One connection to RomM's `/devices` Socket.IO namespace (RomM 5.4), which nudges a device when a game
 * is sent to it or a request for it is cancelled. The socket only nudges: what the device must install
 * comes from claiming, which works without it.
 *
 * Engine.IO v4 over a WebSocket (Socket.IO v5): RomM pings and Loom answers; the device sends no events.
 * The token goes only in the namespace's connect payload, never in the URL, so [client] should add no
 * credentials of its own. [events] never reconnects: see [reconnecting].
 */
class DeviceSocket(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val token: String,
) {
    /**
     * Opens the socket when collected, and closes it when the collector stops: leaving the namespace
     * first, then closing normally (1000). Ends after the connection's last event (see [DeviceEvent]).
     */
    fun events(): Flow<DeviceEvent> = callbackFlow {
        // Each frame heard, with how long the socket may then stay silent before it counts as dead.
        val heard = Channel<Long>(Channel.CONFLATED)
        val silenceLimit = AtomicLong(HANDSHAKE_TIMEOUT_MS)
        val joined = AtomicBoolean(false)

        fun end(last: DeviceEvent) {
            joined.set(false)
            trySend(last)
            channel.close()
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                heard.trySend(silenceLimit.get())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = EngineIoFrames.read(text, NAMESPACE)
                if (frame is SocketFrame.Open) silenceLimit.set(frame.pingIntervalMs + frame.pingTimeoutMs)
                heard.trySend(silenceLimit.get())
                when (frame) {
                    is SocketFrame.Open -> webSocket.send(EngineIoFrames.connect(NAMESPACE, token))
                    is SocketFrame.Ping -> webSocket.send(EngineIoFrames.pong(frame))
                    SocketFrame.Connected -> {
                        joined.set(true)
                        trySend(DeviceEvent.Connected)
                    }
                    is SocketFrame.Event -> frame.toDeviceEvent()?.let { trySend(it) }
                    is SocketFrame.ConnectError -> end(DeviceEvent.Refused(frame.message.orEmpty()))
                    SocketFrame.Disconnect, SocketFrame.Close -> end(DeviceEvent.Dropped(null))
                    null -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = end(DeviceEvent.Dropped(null))

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(DeviceEvent.Dropped(null))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end(
                if (response != null && response.code != SWITCHING_PROTOCOLS) DeviceEvent.UpgradeRefused(response.code)
                else DeviceEvent.Dropped(t),
            )
        }
        val socket = client.newWebSocket(Request.Builder().url(RommUrls.deviceSocketUrl(baseUrl)).build(), listener)

        val watchdog = launch {
            heard.receiveAsFlow().collectLatest { limit ->
                delay(limit)
                end(DeviceEvent.Dropped(SocketTimeoutException("RomM's device socket was silent for $limit ms")))
                socket.cancel() // a dead connection doesn't answer a close; said after, so its failure isn't the reason given
            }
        }

        awaitClose {
            watchdog.cancel()
            if (joined.getAndSet(false)) socket.send(EngineIoFrames.disconnect(NAMESPACE))
            socket.close(NORMAL_CLOSURE, null)
        }
    }

    companion object {
        /** The Socket.IO namespace RomM nudges devices in. */
        const val NAMESPACE = "/devices"

        /** [DeviceEvent.Refused]: the token isn't a live token bound to a device (pair again). */
        const val UNAUTHORIZED = "unauthorized"

        /** [DeviceEvent.Refused]: sending games to devices is switched off on the server. */
        const val DISABLED = "disabled"

        const val INSTALL_QUEUED = "install:queued"
        const val INSTALL_CANCELLED = "install:cancelled"

        /** How long the socket may stay silent once open, before RomM's handshake says how long it pings. */
        const val HANDSHAKE_TIMEOUT_MS = 30_000L

        private const val SWITCHING_PROTOCOLS = 101
        private const val NORMAL_CLOSURE = 1000
    }
}

/** The install nudge an event carries, or null for any other event, or one missing its request. */
internal fun SocketFrame.Event.toDeviceEvent(): DeviceEvent? {
    val request = data ?: return null
    val requestId = (request["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val romId = (request["rom_id"] as? JsonPrimitive)?.intOrNull ?: return null
    return when (name) {
        DeviceSocket.INSTALL_QUEUED -> DeviceEvent.InstallQueued(requestId, romId)
        DeviceSocket.INSTALL_CANCELLED -> DeviceEvent.InstallCancelled(requestId, romId)
        else -> null
    }
}
