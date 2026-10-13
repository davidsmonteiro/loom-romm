package ro.davidmontei.loom.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ro.davidmontei.loom.data.db.DownloadDao
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.di.DownloadHttp
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.storage.engine.Failure
import ro.davidmontei.loom.storage.engine.FailureCode
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.JobRunner
import ro.davidmontei.loom.storage.engine.NioFileOps
import ro.davidmontei.loom.storage.engine.RangeDownloader
import ro.davidmontei.loom.storage.engine.RunOutcome
import ro.davidmontei.loom.storage.engine.SpaceProbe
import ro.davidmontei.loom.storage.engine.WaitReason
import ro.davidmontei.loom.storage.plan.DownloadPlan
import ro.davidmontei.loom.storage.plan.NothingToDownloadException
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Drains the download queue inside WorkManager's foreground service (type dataSync), so downloads
 * continue with the screen off and the app in the background, and are rescheduled after process
 * death. Room is the source of truth: this worker only claims QUEUED jobs, runs them, and exits
 * when nothing is left.
 */
@HiltWorker
class DrainWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DownloadDao,
    private val store: RoomJobStore,
    private val repository: DownloadRepository,
    private val server: ActiveServer,
    private val volumes: StorageVolumes,
    private val settings: SettingsStore,
    private val notifications: DownloadNotifications,
    @DownloadHttp private val http: OkHttpClient,
) : CoroutineWorker(context, params) {

    private val running = ConcurrentHashMap<Long, Running>()
    private class Running(val job: DownloadJobEntity, val lease: String, val coroutine: Job, @Volatile var remaining: Long)

    override suspend fun doWork(): Result {
        try {
            setForeground(notifications.foreground(applicationContext.getString(ro.davidmontei.loom.R.string.download_preparing), 0, 0, 0, null))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: started from the background (Android 12+)
            // or the dataSync time budget is spent (Android 15+). Park and ask the user to open Loom.
            repository.parkQueued(WaitReason.APP_OPEN)
            notifications.resumeHint()
            return Result.success()
        }
        if (!volumes.hasAllFilesAccess()) return Result.success()

        dao.requeueStale(System.currentTimeMillis()) // this worker is the only owner of leases
        try {
            coroutineScope {
                while (true) {
                    cleanUpCancelled()
                    val limit = settings.snapshot().maxConcurrentDownloads
                    while (running.size < limit) {
                        val (job, lease) = claimNext() ?: break
                        startJob(this, job, lease)
                    }
                    if (running.isEmpty() && dao.nextQueued() == null) break
                    updateForeground()
                    delay(750)
                }
            }
        } catch (e: CancellationException) {
            // Stopped by the system. Jobs go back to the queue (or wait for the app on a timeout).
            val timedOut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && stopReason == STOP_REASON_FGS_TIMEOUT
            dao.requeueStale(System.currentTimeMillis())
            if (timedOut) { repository.parkQueued(WaitReason.APP_OPEN); notifications.resumeHint() }
            throw e
        }
        return Result.success()
    }

    private fun startJob(scope: kotlinx.coroutines.CoroutineScope, job: DownloadJobEntity, lease: String) {
        val runnerJob = scope.launch {
            val reserved = { running.values.filter { it.job.id != job.id }.sumOf { it.remaining } }
            val runner = JobRunner(
                store = store,
                downloader = RangeDownloader(http),
                baseUrl = { server.baseUrl() },
                space = SpaceProbe { path -> volumes.freeBytes(path.toFile()) - reserved() },
                files = NioFileOps(),
                isMounted = { volumes.isMounted(it.volumeId) && File(it.romsRoot).isDirectory },
                onProgress = { id, bytes ->
                    repository.reportProgress(id, bytes)
                    running[id]?.remaining = (job.bytesTotal - bytes).coerceAtLeast(0)
                },
            )
            val self = coroutineContext[Job]!!
            // A pause or cancel from the UI clears the lease: stop this runner right away.
            val watcher = launch {
                dao.observeJob(job.id).first { it == null || it.leaseId != lease }
                self.cancel()
            }
            try {
                when (val outcome = runner.run(job.id, lease)) {
                    is RunOutcome.Installed -> notifications.installed(job.title, outcome.install.relPath)
                    is RunOutcome.Parked -> if (outcome.status.phase == JobPhase.FAILED) {
                        notifications.failed(job.title, outcome.status.failure?.detail)
                    }
                    RunOutcome.Released -> Unit
                }
            } finally {
                watcher.cancel()
                running.remove(job.id)
                repository.clearProgress(job.id)
            }
        }
        running[job.id] = Running(job, lease, runnerJob, job.bytesTotal - job.bytesDone)
    }

    /** Claims the oldest QUEUED job, re-planning it first if the server's files changed. */
    private suspend fun claimNext(): Pair<DownloadJobEntity, String>? {
        repeat(5) {
            val candidate = dao.nextQueued() ?: return null
            if (!refreshPlan(candidate)) return@repeat
            val fresh = dao.job(candidate.id) ?: return@repeat
            val record = fresh.toRecord()
            val runner = JobRunner(store, RangeDownloader(http), { server.baseUrl() }, { Long.MAX_VALUE })
            val phase = runner.resumePhase(record)
            val lease = UUID.randomUUID().toString()
            if (dao.claim(fresh.id, lease, phase.name, System.currentTimeMillis()) == 1) return fresh to lease
        }
        return null
    }

    /**
     * Fetches the ROM again; if its files changed since the job was planned, discards the staged
     * bytes and plans afresh. Returns false when the job was failed instead (gone from the server).
     * Offline or unreachable: keep the existing plan and let the download itself retry.
     */
    private suspend fun refreshPlan(job: DownloadJobEntity): Boolean {
        val rom = try {
            server.call { it.rom(job.romId) }
        } catch (e: RommException.NotFound) {
            fail(job, Failure(FailureCode.SERVER_FILE_MISSING, "This game is no longer on the RomM server"))
            return false
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return true
        }
        val plan = try {
            repository.planFor(rom)
        } catch (e: NothingToDownloadException) {
            fail(job, Failure(FailureCode.NOTHING_TO_DOWNLOAD, e.message, retryable = false))
            return false
        }
        if (plan.fingerprint == job.fingerprint && plan.systemFolder == job.plan().systemFolder) return true
        if (job.finalRelPath != null) return true // already assembling: finish what was verified
        File(job.stagingRoot, "jobs/${job.id}-${job.generation}").deleteRecursively()
        dao.replan(
            job.id, EngineJson.encodeToString(DownloadPlan.serializer(), plan), plan.fingerprint, plan.totalBytes,
            repository.partsFor(job.id, plan).map { it.toEntity() },
        )
        return true
    }

    private suspend fun fail(job: DownloadJobEntity, failure: Failure) {
        dao.setPhase(job.id, job.phase, JobPhase.FAILED.name, null, failure.code.name, failure.detail, failure.retryable, System.currentTimeMillis())
        notifications.failed(job.title, failure.detail)
    }

    private suspend fun cleanUpCancelled() {
        for (job in dao.jobsIn(JobPhase.CANCELLING.name)) {
            if (running.containsKey(job.id)) continue // its runner is still stopping
            File(job.stagingRoot, "jobs/${job.id}-${job.generation}").deleteRecursively()
            dao.deleteJob(job.id)
        }
    }

    private suspend fun updateForeground() {
        val first = running.values.minByOrNull { it.job.queueOrder } ?: return
        val done = repository.progress.value[first.job.id]?.bytesDone ?: first.job.bytesDone
        val queued = dao.jobsIn(JobPhase.QUEUED.name).size
        runCatching { setForeground(notifications.foreground(first.job.title, done, first.job.bytesTotal, queued, first.job.id)) }
    }

    companion object {
        const val UNIQUE_NAME = "download-queue"
        /** JobParameters.STOP_REASON_TIMEOUT: the foreground-service time limit (Android 15+). */
        private const val STOP_REASON_FGS_TIMEOUT = 3
        @Suppress("unused") private val FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
