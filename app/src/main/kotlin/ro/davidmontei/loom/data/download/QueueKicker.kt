package ro.davidmontei.loom.data.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Starts the download worker. Uses APPEND_OR_REPLACE so a job enqueued while a worker is just
 * deciding the queue is empty still gets a fresh worker after it (a lost wake-up otherwise), but
 * skips appending when a follow-up worker is already waiting.
 */
class QueueKicker(private val workManager: WorkManager) {
    private val mutex = Mutex()

    suspend fun kick() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(DrainWorker.UNIQUE_NAME).get(5, TimeUnit.SECONDS) }
                .getOrDefault(emptyList())
            if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return@withContext
            val request = OneTimeWorkRequestBuilder<DrainWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(DrainWorker.UNIQUE_NAME)
                .build()
            workManager.enqueueUniqueWork(DrainWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
