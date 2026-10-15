package ro.davidmontei.loom.romm.socket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ro.davidmontei.loom.romm.RommJson

/** What one text frame from a Socket.IO server says, as far as a client that only listens needs. */
sealed interface SocketFrame {
    /** Engine.IO OPEN: the server pings every [pingIntervalMs] and waits [pingTimeoutMs] for the answer. */
    data class Open(val pingIntervalMs: Long, val pingTimeoutMs: Long) : SocketFrame

    /** Engine.IO PING, answered with [EngineIoFrames.pong]. */
    data class Ping(val payload: String) : SocketFrame

    /** Engine.IO CLOSE: the server is ending the connection. */
    data object Close : SocketFrame

    /** Socket.IO CONNECT: the namespace accepted the client. */
    data object Connected : SocketFrame

    /** Socket.IO CONNECT_ERROR: the namespace refused the client, saying why in [message]. */
    data class ConnectError(val message: String?) : SocketFrame

    /** Socket.IO EVENT [name], with its first argument when that is an object. */
    data class Event(val name: String, val data: JsonObject?) : SocketFrame

    /** Socket.IO DISCONNECT: the server left the namespace. */
    data object Disconnect : SocketFrame
}

/**
 * Engine.IO v4 text frames carrying Socket.IO v5 packets, one packet per WebSocket frame (as over the
 * websocket transport). Only what a client of one namespace that sends no events needs: the rest (noop,
 * pong, acks, binary packets, other namespaces) reads as nothing.
 *
 * A Socket.IO packet inside an Engine.IO MESSAGE (`4`) is `<type>[<namespace>,][<ack id>][<JSON>]`,
 * where the namespace is left out for `/`, and the comma when nothing follows it.
 */
object EngineIoFrames {

    /** The answer to [ping]: Engine.IO PONG with the same payload. */
    fun pong(ping: SocketFrame.Ping): String = "3${ping.payload}"

    /** Socket.IO CONNECT to [namespace], authenticating with [token] in the connect payload. */
    fun connect(namespace: String, token: String): String =
        "40$namespace," + buildJsonObject { put("token", token) }.toString()

    /** Socket.IO DISCONNECT from [namespace]: the client is leaving. */
    fun disconnect(namespace: String): String = "41$namespace,"

    /** What [text] says to a client of [namespace]; null when it says nothing it acts on, or isn't a frame at all. */
    fun read(text: String, namespace: String): SocketFrame? {
        if (text.isEmpty()) return null
        return when (text[0]) {
            '0' -> open(text.substring(1))
            '1' -> SocketFrame.Close
            '2' -> SocketFrame.Ping(text.substring(1))
            '4' -> packet(text.substring(1), namespace)
            else -> null // 3 pong, 5 upgrade, 6 noop, or not Engine.IO
        }
    }

    private fun open(json: String): SocketFrame? {
        val handshake = parseObject(json) ?: return null
        val interval = (handshake["pingInterval"] as? JsonPrimitive)?.longOrNull ?: return null
        val timeout = (handshake["pingTimeout"] as? JsonPrimitive)?.longOrNull ?: return null
        return SocketFrame.Open(interval, timeout)
    }

    private fun packet(packet: String, namespace: String): SocketFrame? {
        if (packet.isEmpty()) return null
        val type = packet[0]
        var rest = packet.substring(1)
        val packetNamespace = if (rest.startsWith("/")) {
            val end = rest.indexOf(',').let { if (it < 0) rest.length else it }
            rest.substring(0, end).also { rest = rest.substring((end + 1).coerceAtMost(rest.length)) }
        } else {
            "/"
        }
        if (packetNamespace != namespace) return null
        val payload = rest.dropWhile { it.isDigit() } // an ack id the server asks for; Loom never acks
        return when (type) {
            '0' -> SocketFrame.Connected
            '1' -> SocketFrame.Disconnect
            '2' -> event(payload)
            '4' -> SocketFrame.ConnectError(parseObject(payload)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull })
            else -> null // 3 ack, 5 and 6 binary
        }
    }

    private fun event(json: String): SocketFrame? {
        val args = parse(json) as? JsonArray ?: return null
        val name = (args.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return SocketFrame.Event(name, args.getOrNull(1) as? JsonObject)
    }

    private fun parse(json: String): JsonElement? = runCatching { RommJson.parseToJsonElement(json) }.getOrNull()

    private fun parseObject(json: String): JsonObject? = parse(json) as? JsonObject
}
