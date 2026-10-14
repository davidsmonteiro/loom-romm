package ro.davidmontei.loom.net

import android.content.Context
import android.net.ConnectivityManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import ro.davidmontei.loom.net.ServerReachability.State
import java.io.IOException

/**
 * Found by review: with RomM out of reach (away from home, Tailscale off) the library showed the
 * copy kept on the Thor and said nothing. Loom now knows, from the requests it makes anyway and a
 * heartbeat when there's reason to look again, without polling RomM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ServerReachabilityTest {
    private val reachability = ServerReachability()

    // --- what the requests Loom makes anyway say ---

    /** A request to [server], with RomM at [romm] (the same server unless said otherwise). */
    private fun get(server: MockWebServer, cancel: Boolean = false, romm: MockWebServer = server) {
        val address = object : AuthSource {
            override fun serverUrl() = romm.url("/")
            override fun token(): String? = null
        }
        val call = OkHttpClient.Builder().addInterceptor(ReachabilityInterceptor(reachability, address)).build()
            .newCall(Request.Builder().url(server.url("/api/heartbeat")).build())
        if (cancel) call.cancel()
        call.execute().close()
    }

    /** A cover RomM links to on another host, say: whether that host answers says nothing about RomM. */
    @Test fun requestsToOtherHostsSayNothing() {
        MockWebServer().apply { start() }.use { romm ->
            MockWebServer().apply { start() }.use { elsewhere ->
                elsewhere.enqueue(MockResponse.Builder().code(200).build())
                get(elsewhere, romm = romm)
                assertThat(reachability.current.value).isEqualTo(State.UNKNOWN)
            }
        }
    }

    @Test fun anyAnswerFromRommMeansItCanBeReached() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(401).build()) // a rejected token is still RomM answering
            get(server)
            assertThat(reachability.current.value).isEqualTo(State.REACHABLE)
        }
    }

    @Test fun noAnswerMeansItCant() {
        val server = MockWebServer().apply { start() }
        server.close() // nothing listens there any more
        assertThrows(IOException::class.java) { get(server) }
        assertThat(reachability.current.value).isEqualTo(State.UNREACHABLE)
    }

    /** Cloudflare (or another proxy) answering that it can't reach RomM. */
    @Test fun aProxyThatCantReachRommMeansItCant() {
        MockWebServer().apply { start() }.use { server ->
            server.enqueue(MockResponse.Builder().code(502).build())
            get(server)
            assertThat(reachability.current.value).isEqualTo(State.UNREACHABLE)
        }
    }

    /** Leaving a screen cancels its requests: that says nothing about RomM. */
    @Test fun aRequestLoomCancelledSaysNothing() {
        MockWebServer().apply { start() }.use { server ->
            assertThrows(IOException::class.java) { get(server, cancel = true) }
            assertThat(reachability.current.value).isEqualTo(State.UNKNOWN)
        }
    }

    // --- when Loom looks again, in virtual time ---

    /** The times RomM was asked, in ms; [answers] says what each heartbeat finds. */
    private class Probes(private val reachability: ServerReachability, private val scope: TestScope) {
        val at = mutableListOf<Long>()
        var answers = true
        val probe: suspend () -> Unit = {
            at += scope.currentTime
            if (answers) reachability.answered() else { reachability.unreachable(); throw IOException("no answer") }
        }
    }

    private fun TestScope.watching(): Pair<ReachabilityWatch, Probes> {
        val probes = Probes(reachability, this)
        val watch = ReachabilityWatch(reachability, probes.probe)
        backgroundScope.launch { watch.run() }
        runCurrent()
        return watch to probes
    }

    @Test fun comingToTheFrontLooksOnce() = runTest {
        val (watch, probes) = watching()
        watch.inFront(true)
        advanceTimeBy(3_600_000)
        assertThat(probes.at).containsExactly(0L)
        assertThat(reachability.current.value).isEqualTo(State.REACHABLE)
    }

    @Test fun inTheBackgroundLoomNeverLooks() = runTest {
        val (watch, probes) = watching()
        reachability.unreachable()
        watch.networkChanged()
        advanceTimeBy(3_600_000)
        assertThat(probes.at).isEmpty()
    }

    /** RomM out of reach: looked at again now and then, ever less often, never more than every 5 minutes. */
    @Test fun whileRommCantBeReachedLoomLooksAgainLessAndLessOften() = runTest {
        val (watch, probes) = watching()
        probes.answers = false
        watch.inFront(true)
        advanceTimeBy(1_200_001)
        assertThat(probes.at).containsExactly(0L, 30_000L, 90_000L, 210_000L, 450_000L, 750_000L, 1_050_000L).inOrder()
    }

    @Test fun onceRommAnswersLoomStopsLooking() = runTest {
        val (watch, probes) = watching()
        probes.answers = false
        watch.inFront(true)
        advanceTimeBy(30_001)
        probes.answers = true
        advanceTimeBy(3_600_000)
        assertThat(probes.at).containsExactly(0L, 30_000L, 90_000L).inOrder()
        assertThat(reachability.current.value).isEqualTo(State.REACHABLE)
    }

    /** A sync or a cover failing while Loom is in front starts the looking again. */
    @Test fun aRequestThatFindsRommGoneStartsTheLooking() = runTest {
        val (watch, probes) = watching()
        watch.inFront(true)
        advanceTimeBy(60_000)
        reachability.unreachable()
        advanceTimeBy(30_001)
        assertThat(probes.at).containsExactly(0L, 90_000L).inOrder()
    }

    /** Wi-Fi joined or a VPN up: RomM is asked again once the network has settled, and the waits start over. */
    @Test fun aNewNetworkIsLookedThroughOnceItSettles() = runTest {
        val (watch, probes) = watching()
        probes.answers = false
        watch.inFront(true)
        advanceTimeBy(100_000) // asked at 0, 30 s and 90 s; the next wait is 2 min
        probes.answers = true
        watch.networkChanged()
        advanceTimeBy(3_600_000)
        assertThat(probes.at).containsExactly(0L, 30_000L, 90_000L, 100_000L + ReachabilityWatch.NETWORK_SETTLE_MS).inOrder()
    }

    /** A network that came while Loom was away has settled by the time Loom is opened: RomM is asked at once. */
    @Test fun comingToTheFrontAfterANetworkChangeLooksAtOnce() = runTest {
        val (watch, probes) = watching()
        watch.networkChanged() // as Android reports the network the moment Loom starts
        advanceTimeBy(60_000)
        watch.inFront(true)
        runCurrent()
        assertThat(probes.at).containsExactly(60_000L)
    }

    @Test fun goingToTheBackgroundStopsTheLooking() = runTest {
        val (watch, probes) = watching()
        probes.answers = false
        watch.inFront(true)
        advanceTimeBy(40_000)
        watch.inFront(false)
        advanceTimeBy(3_600_000)
        assertThat(probes.at).containsExactly(0L, 30_000L).inOrder()
    }

    /** What the app feeds it: its process coming to the front and going, and the default network coming and going. */
    @Test fun theAppAndItsNetworksAreFollowed() = runTest {
        val (watch, probes) = watching()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val connectivity = ApplicationProvider.getApplicationContext<Context>().getSystemService(ConnectivityManager::class.java)
        val others = shadowOf(connectivity).networkCallbacks.toSet()
        watch.follow(owner.lifecycle, connectivity)
        val callbacks = shadowOf(connectivity).networkCallbacks - others

        owner.lifecycle.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertThat(probes.at).containsExactly(0L)
        callbacks.forEach { it.onAvailable(ShadowNetwork.newInstance(7)) } // Tailscale up, say
        advanceTimeBy(ReachabilityWatch.NETWORK_SETTLE_MS + 1)
        assertThat(probes.at).containsExactly(0L, ReachabilityWatch.NETWORK_SETTLE_MS).inOrder()

        owner.lifecycle.currentState = Lifecycle.State.CREATED // in the background
        probes.answers = false
        callbacks.forEach { it.onLost(ShadowNetwork.newInstance(7)) }
        advanceTimeBy(3_600_000)
        assertThat(probes.at).hasSize(2)
    }
}
