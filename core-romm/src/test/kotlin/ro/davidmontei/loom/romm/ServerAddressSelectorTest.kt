package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

class ServerAddressSelectorTest {
    @get:Rule val rule = MockWebServerRule()
    private val server get() = rule.server
    private val deadPrimary = "http://127.0.0.1:1/".toHttpUrl()
    private var now = 0L
    private val selector = ServerAddressSelector(OkHttpClient(), clockMs = { now }, ttlMs = 60_000)

    private fun heartbeat() = MockResponse.Builder().code(200)
        .setHeader("Content-Type", "application/json").body(RommFixtures.HEARTBEAT_5_3_1).build()

    @Test fun fallsBackWhenPrimaryIsDown() = runTest {
        server.enqueue(heartbeat())
        assertThat(selector.select(listOf(deadPrimary, server.url("/")))).isEqualTo(server.url("/"))
    }

    @Test fun prefersPrimaryWhenItAnswers() = runTest {
        server.enqueue(heartbeat())
        val fallback = "https://romm.example.org/".toHttpUrl()
        assertThat(selector.select(listOf(server.url("/"), fallback))).isEqualTo(server.url("/"))
    }

    @Test fun cachesChoiceUntilTtlOrInvalidate() = runTest {
        val addresses = listOf(deadPrimary, server.url("/"))
        server.enqueue(heartbeat())
        selector.select(addresses)
        now += 30_000
        selector.select(addresses)
        assertThat(server.requestCount).isEqualTo(1)

        selector.invalidate()
        server.enqueue(heartbeat())
        selector.select(addresses)
        assertThat(server.requestCount).isEqualTo(2)

        now += 61_000
        server.enqueue(heartbeat())
        selector.select(addresses)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun singleAddressIsUsedWithoutProbing() = runTest {
        assertThat(selector.select(listOf(server.url("/")))).isEqualTo(server.url("/"))
        assertThat(server.requestCount).isEqualTo(0)
    }
}
