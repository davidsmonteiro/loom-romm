package ro.davidmontei.loom.data.catalog

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.settings.CatalogRefresh
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.ServerInfo
import ro.davidmontei.loom.romm.RommException
import java.util.concurrent.TimeUnit

/**
 * The scheduled catalog refresh. It only queues the incremental sync the library's Sync button
 * starts, so a refresh never runs alongside another sync and the UI shows its progress as usual.
 */
@HiltWorker
class CatalogRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsStore,
    private val credentials: CredentialStore,
    private val server: ActiveServer,
    private val sync: CatalogSyncScheduler,
    private val serverInfo: ServerInfo,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        credentials.awaitLoaded()
        // Signed out, or setup not finished: nothing to refresh until the user is back.
        if (settings.snapshot().refreshSchedule() == null || credentials.current.value == null) return Result.success()
        // RomM out of reach (away from home, Tailscale off): the sync would fail and leave "Sync failed" in the
        // library until another one worked, for a sync nobody asked for. A VPN can come up a little after the
        // network, so try again over the next few minutes, then quietly wait for the next refresh.
        if (!rommAnswers()) return if (runAttemptCount < 3) Result.retry() else Result.success()
        // The sync itself only needs a network: it starts at once on the one this ran on, and a manual sync
        // kept in its place mustn't wait for Wi-Fi.
        sync.enqueue(full = false)
        return Result.success()
    }

    /** False only when RomM can't be reached: any other answer (a rejected token, say) is the sync's to report. */
    private suspend fun rommAnswers(): Boolean = try {
        server.call { api -> serverInfo.heartbeat(api) }
        true
    } catch (e: RommException.Unreachable) {
        false
    } catch (e: RommException) {
        true
    }

    companion object {
        const val UNIQUE_NAME = "catalog-refresh"
    }
}

/** How the periodic refresh should run. */
data class RefreshSchedule(val every: CatalogRefresh, val wifiOnly: Boolean)

/** The refresh these settings ask for: none while it's off or setup isn't finished. */
fun Settings.refreshSchedule(): RefreshSchedule? =
    if (!onboardingComplete || server == null || romsRoot == null || catalogRefresh == CatalogRefresh.OFF) null
    else RefreshSchedule(catalogRefresh, refreshOnWifiOnly)

class CatalogRefreshScheduler(private val workManager: WorkManager) {

    /** Keeps the periodic refresh in step with [settings] for as long as it is collected. */
    suspend fun follow(settings: Flow<Settings>) =
        settings.map { it.refreshSchedule() }.distinctUntilChanged().collect(::schedule)

    /** Schedules the refresh, changes it in place, or cancels it when [schedule] is null. */
    fun schedule(schedule: RefreshSchedule?) {
        if (schedule == null) {
            workManager.cancelUniqueWork(CatalogRefreshWorker.UNIQUE_NAME)
            return
        }
        val hours = schedule.every.hours.toLong()
        val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (schedule.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            // Not at once: setup has only just synced the library, and switching the refresh on isn't asking for a sync.
            .setInitialDelay(hours, TimeUnit.HOURS)
            .build()
        // UPDATE keeps when the schedule started, so applying it again at every app start doesn't put
        // the next refresh off, and a new interval counts from the last refresh (or from switching it on).
        workManager.enqueueUniquePeriodicWork(CatalogRefreshWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
