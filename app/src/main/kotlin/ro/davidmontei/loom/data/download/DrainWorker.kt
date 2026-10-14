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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ro.davidmontei.loom.data.db.DownloadDao
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.di.DownloadHttp
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.rommCall
import ro.davidmontei.loom.storage.engine.Failure
import ro.davidmontei.loom.storage.engine.FailureCode
import ro.davidmontei.loom.storage.engine.JobEvent
import ro.davidmontei.loom.storage.engine.JobMachine
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

    /** Jobs whose plan has been compared with RomM's in this run (see [planVerdict]). */
    private val planChecked = ConcurrentHashMap.newKeySet<Long>()

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
        if (!volumes.hasAllFilesAccess()) {
            // Queued jobs stay queued; they continue once access is granted again and Loom opens.
            notifications.failed("Downloads", applicationContext.getString(ro.davidmontei.loom.R.string.download_needs_files_access))
            return Result.success()
        }

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
        lateinit var entry: Running
        // Registered before it starts, so a job that finishes at once can't leave a stale entry.
        val runnerJob = scope.launch(start = CoroutineStart.LAZY) {
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
            // A pause or cancel from the UI clears the lease: stop this runner right away. (The row
            // disappearing is the normal end of a successful run, not a reason to cancel.)
            val watcher = launch {
                dao.observeJob(job.id).first { it != null && it.leaseId != lease }
                self.cancel()
            }
            try {
                // The job's plan is compared with RomM's while it downloads, not before: RomM's answer
                // (the ROM's full details) is a round trip a resumed download needn't wait for.
                workWhileChecking(
                    work = {
                        when (val outcome = runner.run(job.id, lease)) {
                            is RunOutcome.Installed -> notifications.installed(job.title, outcome.install.relPath)
                            is RunOutcome.Parked -> if (outcome.status.phase == JobPhase.FAILED) {
                                notifications.failed(job.title, outcome.status.failure?.detail)
                            }
                            RunOutcome.Released -> Unit
                        }
                    },
                    check = { if (planChecked.add(job.id)) planVerdict(job) else null },
                    act = { verdict -> applyVerdict(job, lease, verdict) },
                )
            } finally {
                watcher.cancel()
                // Not the entry of the same job claimed again meanwhile (see applyVerdict).
                if (running.remove(job.id, entry)) repository.clearProgress(job.id)
            }
        }
        entry = Running(job, lease, runnerJob, job.bytesTotal - job.bytesDone)
        running[job.id] = entry
        runnerJob.start()
    }

    /** Claims the oldest QUEUED job. Its plan is compared with RomM's once it runs (see [planVerdict]). */
    private suspend fun claimNext(): Pair<DownloadJobEntity, String>? {
        repeat(5) {
            val candidate = dao.nextQueued() ?: return null
            val runner = JobRunner(store, RangeDownloader(http), { server.baseUrl() }, { Long.MAX_VALUE })
            val phase = runner.resumePhase(candidate.toRecord())
            val lease = UUID.randomUUID().toString()
            if (dao.claim(candidate.id, lease, phase.name, System.currentTimeMillis()) == 1) return candidate to lease
        }
        return null
    }

    /** Why a job's plan no longer holds: RomM no longer has the game, or its files changed. */
    private sealed interface PlanVerdict {
        data class Gone(val failure: Failure) : PlanVerdict
        data class Changed(val plan: DownloadPlan) : PlanVerdict
    }

    /**
     * Fetches the ROM again and says whether the job's plan still holds: null when it does, and when
     * RomM can't be asked (offline, unreachable), as the download then retries by itself.
     */
    private suspend fun planVerdict(job: DownloadJobEntity): PlanVerdict? {
        val rom = try {
            server.call { api -> rommCall { api.rom(job.romId) } }
        } catch (e: RommException.NotFound) {
            return PlanVerdict.Gone(Failure(FailureCode.SERVER_FILE_MISSING, "This game is no longer on the RomM server"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        val plan = try {
            repository.planFor(rom)
        } catch (e: NothingToDownloadException) {
            return PlanVerdict.Gone(Failure(FailureCode.NOTHING_TO_DOWNLOAD, e.message, retryable = false))
        }
        if (plan.fingerprint == job.fingerprint && plan.systemFolder == job.plan().systemFolder) return null
        if (dao.job(job.id)?.finalRelPath != null) return null // already assembling: finish what was verified
        return PlanVerdict.Changed(plan)
    }

    /**
     * Acts on a plan that no longer holds, once the job's runner has stopped: the job fails, or its
     * staged bytes are discarded and it goes back to the queue planned afresh. A job that got as far
     * as assembling its install finishes as planned.
     */
    private suspend fun applyVerdict(job: DownloadJobEntity, lease: String, verdict: PlanVerdict) {
        val now = dao.job(job.id) ?: return // installed meanwhile
        if (now.leaseId != lease) { planChecked.remove(job.id); return } // paused, cancelled or parked: checked when it runs again
        when (verdict) {
            is PlanVerdict.Gone -> fail(now, verdict.failure)
            is PlanVerdict.Changed -> {
                if (now.finalRelPath == null) {
                    File(now.stagingRoot, "jobs/${now.id}-${now.generation}").deleteRecursively()
                    dao.replan(
                        now.id, EngineJson.encodeToString(DownloadPlan.serializer(), verdict.plan), verdict.plan.fingerprint,
                        verdict.plan.totalBytes, repository.partsFor(now.id, verdict.plan).map { it.toEntity() },
                    )
                }
                dao.setPhase(now.id, now.phase, JobPhase.QUEUED.name, null, null, null, true, System.currentTimeMillis())
            }
        }
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
        val cancel = cancelTarget(dao.job(first.job.id))
        runCatching { setForeground(notifications.foreground(first.job.title, done, first.job.bytesTotal, queued, cancel)) }
    }

    companion object {
        /**
         * Runs [work] straight away and [check] alongside it, so the work never waits for the check's
         * answer. A null answer (or a check that fails) leaves [work] to finish; any other stops it, and
         * once it has stopped [act] gets the answer. [work] ending first drops the check.
         */
        internal suspend fun <T : Any> workWhileChecking(work: suspend () -> Unit, check: suspend () -> T?, act: suspend (T) -> Unit) =
            coroutineScope {
                val checking = async {
                    try {
                        check()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                val working = launch {
                    try { work() } finally { checking.cancel() }
                }
                val answer = try {
                    checking.await()
                } catch (e: CancellationException) {
                    ensureActive() // cancelled from outside, not by the work ending
                    null
                }
                if (answer == null) {
                    working.join()
                } else {
                    working.cancelAndJoin()
                    act(answer)
                }
            }

        /**
         * The job the notification's Cancel acts on: none once it is being moved into place, which
         * can't be stopped half way, so Cancel would do nothing.
         */
        internal fun cancelTarget(job: DownloadJobEntity?): Long? =
            job?.takeIf { JobMachine.canApply(it.status(), JobEvent.Cancel) }?.id

        const val UNIQUE_NAME = "download-queue"
        /** JobParameters.STOP_REASON_TIMEOUT: the foreground-service time limit (Android 15+). */
        private const val STOP_REASON_FGS_TIMEOUT = 3
        @Suppress("unused") private val FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}
