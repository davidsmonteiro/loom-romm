package ro.davidmontei.loom

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.catalog.CatalogRefreshWorker
import ro.davidmontei.loom.data.settings.ServerConfig

/**
 * What Loom does from its start. Robolectric creates [LoomApp] with its real Hilt graph before each test,
 * so these go through the app's own settings and WorkManager.
 */
@RunWith(AndroidJUnit4::class)
class LoomAppTest {
    private val app = ApplicationProvider.getApplicationContext<LoomApp>()

    /** Nothing else schedules the background refresh: without this, it would never run. */
    @Test fun finishingSetupStartsTheBackgroundRefresh() = runBlocking<Unit> {
        app.settings.setServer(ServerConfig("http://100.64.0.7:8080/".toHttpUrl()), "5.3.1")
        app.settings.setRomsRoot("/storage/9C33-6BBD/ROMs")
        app.settings.setOnboardingComplete(true)
        val refresh = withTimeout(5_000) {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(CatalogRefreshWorker.UNIQUE_NAME)
                .first { it.singleOrNull()?.state == WorkInfo.State.ENQUEUED }.single()
        }
        assertThat(refresh.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(refresh.constraints.requiresBatteryNotLow()).isTrue()
    }
}
