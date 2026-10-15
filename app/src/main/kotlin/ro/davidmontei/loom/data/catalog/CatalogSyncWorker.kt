package ro.davidmontei.loom.data.catalog

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.NotPairedException
import ro.davidmontei.loom.romm.RommException

/** Runs [CatalogSyncer] as unique background work so it survives leaving the app. */
@HiltWorker
class CatalogSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val server: ActiveServer,
    private val syncer: CatalogSyncer,
    private val afterSync: AfterCatalogSync,
    private val workManager: WorkManager,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val full = inputData.getBoolean(KEY_FULL, false)
        return try {
            val result = server.call { api ->
                syncer.sync(api, full) { setProgress(workDataOf(KEY_FETCHED to it.fetched, KEY_TOTAL to (it.total ?: -1))) }
            }
            afterSync.onCatalogSynced()
            // Separate work, so the sync is done as soon as the catalog is.
            CoverPrefetchWorker.enqueue(workManager, wifiOnly = settings.snapshot().refreshOnWifiOnly)
            Result.success(workDataOf(KEY_FETCHED to result.romsWritten, KEY_TOTAL to result.serverRomCount))
        } catch (e: NotPairedException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Not paired")))
        } catch (e: RommException.Unauthorized) {
            Result.failure(workDataOf(KEY_ERROR to "RomM rejected Loom's token. Pair again in Settings.", KEY_AUTH to true))
        } catch (e: RommException.Unreachable) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(KEY_ERROR to "Can't reach the RomM server"))
        } catch (e: RommException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Sync failed")))
        }
    }

    companion object {
        const val UNIQUE_NAME = "catalog-sync"
        const val KEY_FULL = "full"
        const val KEY_FETCHED = "fetched"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_AUTH = "auth"
    }
}

/** Hook for work that must follow a sync (reconciling installs with the file system). */
fun interface AfterCatalogSync {
    suspend fun onCatalogSynced()
}

/** UI-facing view of the sync worker. */
data class CatalogSyncStatus(
    val running: Boolean,
    val fetched: Int = 0,
    val total: Int? = null,
    val error: String? = null,
    val needsPairing: Boolean = false,
)

class CatalogSyncScheduler(private val workManager: WorkManager) {

    /** Starts a sync unless one is already running. A full sync replaces a pending incremental one. */
    fun enqueue(full: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<CatalogSyncWorker>()
            .setInputData(workDataOf(CatalogSyncWorker.KEY_FULL to full))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(CatalogSyncWorker.UNIQUE_NAME)
            .build()
        workManager.enqueueUniqueWork(
            CatalogSyncWorker.UNIQUE_NAME,
            if (full) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * The first time Loom opens after an upgrade that stores more of each ROM than the catalog holds
     * ([Settings.catalogFields] behind [CatalogSyncer.CATALOG_FIELDS]): a sync, which the syncer makes a
     * full one, so every game gets what's new without the owner asking. Until it has run, every start
     * asks again; one already queued or running is kept. Not before setup is done, nor while signed
     * out ([paired] false), where it could only fail. True if it queued one.
     */
    fun syncAfterUpgrade(settings: Settings, paired: Boolean): Boolean {
        if (!settings.onboardingComplete || !paired || settings.catalogFields >= CatalogSyncer.CATALOG_FIELDS) return false
        enqueue(full = false)
        return true
    }

    val status: Flow<CatalogSyncStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(CatalogSyncWorker.UNIQUE_NAME).map { infos ->
            val info = infos.lastOrNull() ?: return@map CatalogSyncStatus(running = false)
            when (info.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> CatalogSyncStatus(
                    running = true,
                    fetched = info.progress.getInt(CatalogSyncWorker.KEY_FETCHED, 0),
                    total = info.progress.getInt(CatalogSyncWorker.KEY_TOTAL, -1).takeIf { it >= 0 },
                )
                WorkInfo.State.FAILED -> CatalogSyncStatus(
                    running = false,
                    error = info.outputData.getString(CatalogSyncWorker.KEY_ERROR),
                    needsPairing = info.outputData.getBoolean(CatalogSyncWorker.KEY_AUTH, false),
                )
                else -> CatalogSyncStatus(running = false)
            }
        }
}
