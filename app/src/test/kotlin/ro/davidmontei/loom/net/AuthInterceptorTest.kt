package ro.davidmontei.loom.net

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {
    private val ours = MockWebServer()
    private val other = MockWebServer()
    private var token: String? = "rmm_secret"
    private lateinit var addresses: List<HttpUrl>

    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(AuthInterceptor(object : AuthSource {
            override fun serverAddresses() = addresses
            override fun token() = token
        })).build()
    }

    @Before fun start() {
        ours.start(); other.start()
        addresses = listOf("http://192.0.2.1:9/".toHttpUrl(), ours.url("/romm/"))
    }

    @After fun stop() { ours.close(); other.close() }

    private fun get(url: HttpUrl, auth: String? = null) = client.newCall(
        Request.Builder().url(url).apply { if (auth != null) header("Authorization", auth) }.build(),
    ).execute().close()

    @Test fun addsBearerForConfiguredServerOnAnyPath() {
        ours.enqueue(MockResponse.Builder().build())
        get(ours.url("/assets/romm/resources/cover.png"))
        assertThat(ours.takeRequest().headers["Authorization"]).isEqualTo("Bearer rmm_secret")
    }

    @Test fun neverSendsTokenToOtherHosts() {
        other.enqueue(MockResponse.Builder().build())
        get(other.url("/igdb/cover.jpg"))
        assertThat(other.takeRequest().headers["Authorization"]).isNull()
    }

    @Test fun leavesExplicitAuthorizationAlone() {
        ours.enqueue(MockResponse.Builder().build())
        get(ours.url("/api/users/me"), auth = "Bearer rmm_candidate")
        assertThat(ours.takeRequest().headers["Authorization"]).isEqualTo("Bearer rmm_candidate")
    }

    @Test fun noTokenNoHeader() {
        token = null
        ours.enqueue(MockResponse.Builder().build())
        get(ours.url("/api/heartbeat"))
        assertThat(ours.takeRequest().headers["Authorization"]).isNull()
    }
}
