package ro.davidmontei.loom.romm.socket

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Frames as python-socketio 5.17 / python-engineio 4.13 (RomM 5.4.0's) write them. */
class EngineIoFramesTest {

    private fun read(text: String) = EngineIoFrames.read(text, DeviceSocket.NAMESPACE)

    @Test fun readsTheOpenPacket() {
        val open = read("""0{"sid":"Lbo5JLzTotvW3g2LAAAA","upgrades":[],"pingInterval":25000,"pingTimeout":60000,"maxPayload":1000000}""")
        assertThat(open).isEqualTo(SocketFrame.Open(pingIntervalMs = 25_000, pingTimeoutMs = 60_000))
        assertThat(read("2")).isEqualTo(SocketFrame.Ping(""))
        assertThat(EngineIoFrames.pong(read("2probe") as SocketFrame.Ping)).isEqualTo("3probe")
        assertThat(read("1")).isEqualTo(SocketFrame.Close)
    }

    @Test fun readsAnInstallQueuedEventInTheDevicesNamespace() {
        assertThat(read("""40/devices,{"sid":"vYbRyQ0tYyVwOE1oAAAB"}""")).isEqualTo(SocketFrame.Connected)
        val event = read("""42/devices,["install:queued",{"id":"6f1c2b3a-0000-4000-8000-000000000001","rom_id":1234}]""")
        assertThat((event as SocketFrame.Event).name).isEqualTo("install:queued")
        assertThat(event.toDeviceEvent()).isEqualTo(DeviceEvent.InstallQueued("6f1c2b3a-0000-4000-8000-000000000001", 1234))
        val cancelled = read("""42/devices,["install:cancelled",{"id":"6f1c2b3a-0000-4000-8000-000000000001","rom_id":1234}]""")
        assertThat((cancelled as SocketFrame.Event).toDeviceEvent())
            .isEqualTo(DeviceEvent.InstallCancelled("6f1c2b3a-0000-4000-8000-000000000001", 1234))
    }

    /** The server can ask for an acknowledgement, with an id before the arguments; Loom reads the event anyway. */
    @Test fun readsAnEventCarryingAnAckId() {
        val event = read("""42/devices,17["install:queued",{"id":"r-1","rom_id":7}]""") as SocketFrame.Event
        assertThat(event.toDeviceEvent()).isEqualTo(DeviceEvent.InstallQueued("r-1", 7))
    }

    @Test fun readsAConnectError() {
        assertThat(read("""44/devices,{"message":"unauthorized"}""")).isEqualTo(SocketFrame.ConnectError(DeviceSocket.UNAUTHORIZED))
        assertThat(read("""44/devices,{"message":"disabled"}""")).isEqualTo(SocketFrame.ConnectError(DeviceSocket.DISABLED))
        assertThat(read("44/devices,")).isEqualTo(SocketFrame.ConnectError(null))
    }

    /** python-socketio leaves the comma out when nothing follows the namespace; Socket.IO's own clients put it in. */
    @Test fun readsADisconnectWithOrWithoutItsComma() {
        assertThat(read("41/devices")).isEqualTo(SocketFrame.Disconnect)
        assertThat(read("41/devices,")).isEqualTo(SocketFrame.Disconnect)
    }

    @Test fun ignoresOtherNamespacesAndUnknownEvents() {
        assertThat(read("""42["install:queued",{"id":"r-1","rom_id":7}]""")).isNull() // the default namespace
        assertThat(read("""42/devicesx,["install:queued",{"id":"r-1","rom_id":7}]""")).isNull()
        assertThat(read("41")).isNull()
        val updated = read("""42/devices,["install:updated",{"id":"r-1","rom_id":7,"status":"done"}]""") as SocketFrame.Event
        assertThat(updated.toDeviceEvent()).isNull() // for the owner's web UI, not the device
        val withoutItsRequest = read("""42/devices,["install:queued",{"rom_id":7}]""") as SocketFrame.Event
        assertThat(withoutItsRequest.toDeviceEvent()).isNull()
        assertThat(read("43/devices,3[]")).isNull() // an ack
        assertThat(read("""451-/devices,["install:queued",{"_placeholder":true,"num":0}]""")).isNull() // binary
        assertThat(read("3")).isNull()
        assertThat(read("6")).isNull()
    }

    @Test fun garbageIsIgnoredNotThrown() {
        for (text in listOf("", "0", "0{", "0[]", """0{"pingInterval":"soon"}""", "4", "42/devices,", "42/devices,{", "42/devices,[]",
            "42/devices,[7]", "9hello", "<html>Bad Gateway</html>", "40/devices,{\"sid\":")) {
            val frame = runCatching { read(text) }
            assertThat(frame.exceptionOrNull()).isNull()
        }
        assertThat(read("0{")).isNull()
        assertThat(read("42/devices,[7]")).isNull()
    }

    /** The token is the only thing that authenticates the socket, and must reach RomM exactly, whatever it holds. */
    @Test fun theConnectFrameCarriesTheTokenAsJson() {
        val frame = EngineIoFrames.connect(DeviceSocket.NAMESPACE, "rmm_a\"b\\c")
        assertThat(frame).startsWith("40/devices,{")
        val auth = Json.parseToJsonElement(frame.removePrefix("40/devices,")).jsonObject
        assertThat(auth.keys).containsExactly("token")
        assertThat(auth["token"]!!.jsonPrimitive.content).isEqualTo("rmm_a\"b\\c")
        assertThat(EngineIoFrames.disconnect(DeviceSocket.NAMESPACE)).isEqualTo("41/devices,")
    }
}
