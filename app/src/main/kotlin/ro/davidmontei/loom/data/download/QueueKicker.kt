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
 * skips appending when a follow-up worker is already waiting (see [kickAction]).
 */
class QueueKicker(private val workManager: WorkManager, private val nowMs: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()

    suspend fun kick() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val infos = runCatching { workManager.getWorkInfosForUniqueWork(DrainWorker.UNIQUE_NAME).get(5, TimeUnit.SECONDS) }
                .getOrDefault(emptyList())
            val policy = when (kickAction(infos, nowMs())) {
                KickAction.NONE -> return@withContext
                KickAction.APPEND -> ExistingWorkPolicy.APPEND_OR_REPLACE
                KickAction.START_NOW -> ExistingWorkPolicy.REPLACE
            }
            val request = OneTimeWorkRequestBuilder<DrainWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(DrainWorker.UNIQUE_NAME)
                .build()
            workManager.enqueueUniqueWork(DrainWorker.UNIQUE_NAME, policy, request)
        }
    }
}

internal enum class KickAction { NONE, APPEND, START_NOW }

/**
 * What [QueueKicker.kick] does with the worker's current runs. A run waiting only for its retry
 * back-off to pass (Loom was killed mid-download, say: 30 s, doubling, counted from its start)
 * would make reopening Loom wait the rest of it out, so it's replaced by one that starts now. Never
 * while a worker runs, so two can't drain the queue at once.
 */
internal fun kickAction(runs: List<WorkInfo>, nowMs: Long): KickAction {
    val waiting = runs.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    if (waiting.isEmpty()) return KickAction.APPEND
    val running = runs.any { it.state == WorkInfo.State.RUNNING }
    val heldBack = waiting.all { it.state == WorkInfo.State.ENQUEUED } && waiting.any { it.nextScheduleTimeMillis > nowMs + BACKOFF_SLACK_MS }
    return if (!running && heldBack) KickAction.START_NOW else KickAction.NONE
}

/** A run due within this long is left alone: replacing it wouldn't start it any sooner. */
private const val BACKOFF_SLACK_MS = 1_000L
