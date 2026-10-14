package ro.davidmontei.loom.data.saves

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ro.davidmontei.loom.data.settings.SaveSyncOutcome
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.RommException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Why a sync run was started; decides what it may do. */
enum class SaveSyncKind {
    /** The periodic scan, opening Loom, a mounted card: uploads, only while automatic upload is on. */
    AUTO,

    /** PUSH_SAVES: uploads, whatever the automatic setting says. */
    PUSH,

    /**
     * PULL_SAVES: only the safe downloads, each backed up first, and only within [SaveSyncWorker.PULL_WINDOW]
     * of the command. Later, or run a second time, it only counts newer saves: whoever asked may
     * have started the emulator since.
     */
    PULL,
}

/**
 * Runs [SaveSyncEngine] in the background. Every run takes the engine's lock, so the periodic
 * work, a one-off run and a sync started from the UI never overlap.
 */
@HiltWorker
class SaveSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SaveSyncEngine,
    private val settings: SettingsStore,
    private val notifications: SaveSyncNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND)?.let { runCatching { SaveSyncKind.valueOf(it) }.getOrNull() } ?: SaveSyncKind.AUTO
        if (kind == SaveSyncKind.AUTO && !settings.snapshot().autoUploadSaves) return Result.success()
        val request = if (kind == SaveSyncKind.PULL) pullRequest() else SaveSyncRequest.PUSH
        return try {
            val report = engine.sync(request)
            notifications.after(report)
            record(report.summary(), failed = report.unavailable != null, needsPairing = report.needsPairing)
            if (report.unavailable != null) {
                Result.failure(workDataOf(KEY_ERROR to report.unavailable))
            } else {
                Result.success(
                    workDataOf(
                        KEY_UPLOADED to report.uploaded.size, KEY_DOWNLOADED to report.downloaded.size,
                        KEY_CONFLICTS to report.conflicts.size, KEY_SERVER_NEWER to report.serverNewer.size,
                    ),
                )
            }
        } catch (e: RommException.Unreachable) {
            // A PULL tried again later would only check, so it says at once that RomM is out of reach.
            if (kind != SaveSyncKind.PULL && runAttemptCount < 3) {
                record("Can't reach the RomM server; Loom tries again soon", failed = true)
                Result.retry()
            } else {
                failure("Can't reach the RomM server")
            }
        } catch (e: RommException.Unauthorized) {
            failure("RomM rejected Loom's token. Pair again in Settings.", needsPairing = true)
        } catch (e: RommException) {
            failure(e.message ?: "Save sync failed")
        }
    }

    /**
     * A PULL restores only while it is fresh: its downloads must start within [PULL_WINDOW] of the
     * command. One that ran before (WorkManager stopped it) or doesn't say when it was asked only checks.
     */
    private fun pullRequest(): SaveSyncRequest {
        val queuedAt = inputData.getLong(KEY_QUEUED_AT, 0L).takeIf { it > 0 } ?: return SaveSyncRequest.CHECK
        if (runAttemptCount > 0) return SaveSyncRequest.CHECK
        return SaveSyncRequest.PULL.copy(downloadsUntil = Instant.ofEpochMilli(queuedAt).plus(PULL_WINDOW))
    }

    /**
     * Settings shows how the last run ended, so a sync that keeps failing doesn't go unnoticed;
     * with [needsPairing], beside a way to pair again.
     */
    private suspend fun record(summary: String, failed: Boolean, needsPairing: Boolean = false) =
        settings.setLastSaveSync(SaveSyncOutcome(System.currentTimeMillis(), summary, failed, needsPairing))

    private suspend fun failure(error: String, needsPairing: Boolean = false): Result {
        record(error, failed = true, needsPairing = needsPairing)
        return Result.failure(workDataOf(KEY_ERROR to error))
    }

    companion object {
        const val PERIODIC_NAME = "save-sync"
        const val NOW_NAME = "save-sync-now"
        const val KEY_KIND = "kind"

        /** When the run was asked for, in epoch milliseconds. */
        const val KEY_QUEUED_AT = "queued_at"
        const val KEY_ERROR = "error"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_CONFLICTS = "conflicts"
        const val KEY_SERVER_NEWER = "server_newer"

        /** WorkManager's shortest period. */
        const val PERIOD_MINUTES = 15L

        /** How long after PULL_SAVES its downloads may still start. */
        val PULL_WINDOW: Duration = Duration.ofSeconds(30)
    }
}

class SaveSyncScheduler(private val workManager: WorkManager, private val now: () -> Long = System::currentTimeMillis) {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** The periodic scan, every [SaveSyncWorker.PERIOD_MINUTES] with a network: on while automatic upload is. */
    fun setPeriodic(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(SaveSyncWorker.PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SaveSyncWorker>(SaveSyncWorker.PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(network)
            .setInputData(workDataOf(SaveSyncWorker.KEY_KIND to SaveSyncKind.AUTO.name))
            .addTag(SaveSyncWorker.PERIODIC_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(SaveSyncWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * One run as soon as there's a network. An automatic one is dropped if a run is already
     * waiting; PUSH and PULL always run, after whatever is queued. A PULL doesn't wait for a
     * network: it restores only within [SaveSyncWorker.PULL_WINDOW], so without one it fails at once.
     */
    fun runNow(kind: SaveSyncKind) {
        val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
            .apply { if (kind != SaveSyncKind.PULL) setConstraints(network) }
            .setInputData(workDataOf(SaveSyncWorker.KEY_KIND to kind.name, SaveSyncWorker.KEY_QUEUED_AT to now()))
            .addTag(SaveSyncWorker.NOW_NAME)
            .addTag("kind:${kind.name}")
            .build()
        val policy = if (kind == SaveSyncKind.AUTO) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE
        workManager.enqueueUniqueWork(SaveSyncWorker.NOW_NAME, policy, request)
    }
}

/** The moments Loom looks for changed saves without being asked. */
class SaveSyncTriggers(private val settings: SettingsStore, private val scheduler: SaveSyncScheduler) {
    /**
     * Loom came to the foreground (at most once a minute): saves may have changed while it was
     * away. Also keeps the periodic scan in line with the automatic upload setting.
     */
    suspend fun appOpened() {
        val s = settings.snapshot()
        if (!s.onboardingComplete) return
        scheduler.setPeriodic(s.autoUploadSaves)
        if (s.autoUploadSaves) scheduler.runNow(SaveSyncKind.AUTO)
    }

    /** A card came back: the saves on it may have changed elsewhere. The worker honours the setting. */
    fun cardMounted() = scheduler.runNow(SaveSyncKind.AUTO)

    /** The Settings switch: the periodic scan runs exactly while automatic upload is on. */
    suspend fun setAutomaticUpload(on: Boolean) {
        settings.setAutoUploadSaves(on)
        scheduler.setPeriodic(on)
    }

    /**
     * Loom paired again: a background sync that failed for want of pairing runs again, so Settings
     * shows how it goes now rather than the old failure. The worker honours the setting.
     */
    suspend fun pairedAgain() {
        if (settings.snapshot().lastSaveSync?.needsPairing == true) scheduler.runNow(SaveSyncKind.AUTO)
    }
}
