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
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.RommException
import java.util.concurrent.TimeUnit

/** Why a sync run was started; decides what it may do. */
enum class SaveSyncKind {
    /** The periodic scan, opening Loom, a mounted card: uploads, only while automatic upload is on. */
    AUTO,

    /** PUSH_SAVES: uploads, whatever the automatic setting says. */
    PUSH,

    /** PULL_SAVES: only the safe downloads, each backed up first. */
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
        val request = if (kind == SaveSyncKind.PULL) SaveSyncRequest.PULL else SaveSyncRequest.PUSH
        return try {
            val report = engine.sync(request)
            notifications.after(report)
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
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "Can't reach the RomM server"))
        } catch (e: RommException.Unauthorized) {
            Result.failure(workDataOf(KEY_ERROR to "RomM rejected Loom's token. Pair again in Settings."))
        } catch (e: RommException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Save sync failed")))
        }
    }

    companion object {
        const val PERIODIC_NAME = "save-sync"
        const val NOW_NAME = "save-sync-now"
        const val KEY_KIND = "kind"
        const val KEY_ERROR = "error"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_CONFLICTS = "conflicts"
        const val KEY_SERVER_NEWER = "server_newer"

        /** WorkManager's shortest period. */
        const val PERIOD_MINUTES = 15L
    }
}

class SaveSyncScheduler(private val workManager: WorkManager) {
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
     * waiting; PUSH and PULL always run, after whatever is queued.
     */
    fun runNow(kind: SaveSyncKind) {
        val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(SaveSyncWorker.KEY_KIND to kind.name))
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
}
