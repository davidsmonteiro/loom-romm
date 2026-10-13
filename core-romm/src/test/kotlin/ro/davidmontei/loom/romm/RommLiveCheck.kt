package ro.davidmontei.loom.romm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only checks against a real server. Excluded from `test`; run explicitly:
 *   ./gradlew :core-romm:liveCheck -Promm.url=https://romm.example.org
 * With a LOOM_TOKEN environment variable it also lists platforms and the first page of ROMs,
 * which exercises every DTO against live data. It never writes anything.
 */
class RommLiveCheck {
    private val url = System.getProperty("romm.url").orEmpty()
    private val token = System.getenv("LOOM_TOKEN").orEmpty()

    private fun api(withToken: Boolean) = RommServiceFactory.create(
        RommUrls.candidates(url).first(),
        OkHttpClient.Builder().apply {
            if (withToken) addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            }
        }.build(),
    )

    @Test fun heartbeat() = runBlocking {
        assumeTrue("set -Promm.url", url.isNotBlank())
        val version = rommCall { api(false).heartbeat() }.system.version
        println("RomM at $url reports version $version (contract $ROMM_CONTRACT_VERSION)")
        assertThat(RommVersion.parse(version)!!).isAtLeast(MIN_SUPPORTED_ROMM)
    }

    @Test fun catalogWithToken() = runBlocking {
        assumeTrue("set -Promm.url and LOOM_TOKEN", url.isNotBlank() && token.isNotBlank())
        val api = api(true)
        val me = rommCall { api.me() }
        val platforms = rommCall { api.platforms() }
        val firstPage = api.pageRoms(pageSize = 50).first()
        println("Signed in as ${me.username}; ${platforms.size} platforms; ${firstPage.total} ROMs; " +
            "first page ${firstPage.page.items.size} items, ${firstPage.page.items.sumOf { it.files.size }} files")
        assertThat(platforms).isNotEmpty()
    }
}
