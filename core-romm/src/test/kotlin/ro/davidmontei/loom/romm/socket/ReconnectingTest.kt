package ro.davidmontei.loom.romm.socket

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/** How the device socket comes back after a connection ends, in virtual time. */
class ReconnectingTest {

    /** A [Random] whose doubles are [values], in turn. */
    private class Doubles(vararg values: Double) : Random() {
        private val next = ArrayDeque(values.toList())
        override fun nextBits(bitCount: Int) = error("only doubles are drawn")
        override fun nextDouble() = next.removeFirst()
    }

    private val dropped = DeviceEvent.Dropped(IOException("Connection reset"))

    /** Connections that each give [script]'s next list of events, then end. */
    private fun connections(vararg script: List<DeviceEvent>): Pair<() -> Flow<DeviceEvent>, () -> Int> {
        var made = 0
        val connect = { flowOf(*script[made++].toTypedArray()) }
        return connect to { made }
    }

    @Test fun aDropReconnectsAfterAGrowingJitteredWait() = runTest {
        val waits = mutableListOf<Long>()
        val (connect, made) = connections(*Array(9) { listOf(dropped) })
        // Half the wait, the wait itself, then one and a half times it; then the plain wait, up to the cap.
        val random = Doubles(0.0, 0.5, 1.0, 0.5, 0.5, 0.5, 0.5, 0.5, 1.0)
        val events = reconnecting(connect, random, delay = { waits += it; delay(it) }).take(9).toList()
        assertThat(events).containsExactlyElementsIn(List(9) { dropped })
        assertThat(made()).isEqualTo(9)
        assertThat(waits).containsExactly(500L, 2_000L, 6_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L).inOrder()
        assertThat(currentTime).isEqualTo(waits.sum())
    }

    /** The longest wait is a minute, jitter included. */
    @Test fun theWaitNeverPassesAMinute() {
        assertThat(jittered(RECONNECT_MAX_WAIT_MS, Doubles(1.0))).isEqualTo(RECONNECT_MAX_WAIT_MS)
        assertThat(jittered(RECONNECT_MAX_WAIT_MS, Doubles(0.0))).isEqualTo(RECONNECT_MAX_WAIT_MS / 2)
    }

    @Test fun aRefusalStopsForGood() = runTest {
        val (connect, made) = connections(
            listOf(dropped),
            listOf(DeviceEvent.Refused(DeviceSocket.UNAUTHORIZED)),
            listOf(DeviceEvent.Connected),
        )
        val events = reconnecting(connect, Doubles(0.5)).toList()
        assertThat(events).containsExactly(dropped, DeviceEvent.Refused(DeviceSocket.UNAUTHORIZED)).inOrder()
        assertThat(made()).isEqualTo(2)
    }

    /** A connection RomM accepted shows the way is clear again: the next drop waits a second, not as long as before. */
    @Test fun connectingAgainStartsTheWaitsOver() = runTest {
        val waits = mutableListOf<Long>()
        val (connect, _) = connections(
            listOf(DeviceEvent.UpgradeRefused(502)),
            listOf(dropped),
            listOf(DeviceEvent.Connected, DeviceEvent.InstallQueued("r-1", 7), dropped),
            listOf(dropped),
        )
        val events = reconnecting(connect, Doubles(0.5, 0.5, 0.5, 0.5), delay = { waits += it }).take(6).toList()
        assertThat(events.last()).isEqualTo(dropped)
        assertThat(waits).containsExactly(1_000L, 2_000L, 1_000L).inOrder()
    }

    /** A connection that fails before it starts counts as a drop, not as the end of all of them. */
    @Test fun aConnectionThatCantStartIsADrop() = runTest {
        val failure = IllegalStateException("no socket")
        var made = 0
        val connect = { made++; flow<DeviceEvent> { throw failure } }
        val events = reconnecting(connect, Doubles(0.5, 0.5)).take(2).toList()
        assertThat(events).containsExactly(DeviceEvent.Dropped(failure), DeviceEvent.Dropped(failure))
        assertThat(made).isEqualTo(2)
    }
}
