package ro.davidmontei.loom.data.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The prefetch after a sync waits for the network the background refresh does. */
@RunWith(AndroidJUnit4::class)
class CoverPrefetchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    private fun prefetch(): WorkInfo = workManager.getWorkInfosForUniqueWork(CoverPrefetchWorker.UNIQUE_NAME).get().single()

    @Test fun withWifiOnlyThePrefetchWaitsForAnUnmeteredNetwork() {
        CoverPrefetchWorker.enqueue(workManager, wifiOnly = true)
        assertThat(prefetch().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(prefetch().constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        assertThat(prefetch().constraints.requiresStorageNotLow()).isTrue()
    }

    @Test fun withoutItThePrefetchNeedsOnlyANetwork() {
        CoverPrefetchWorker.enqueue(workManager, wifiOnly = false)
        assertThat(prefetch().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(prefetch().constraints.requiresStorageNotLow()).isTrue()
    }
}
