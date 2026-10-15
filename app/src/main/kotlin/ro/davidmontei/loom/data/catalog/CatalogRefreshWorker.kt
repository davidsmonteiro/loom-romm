package ro.davidmontei.loom.data.catalog

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
 * With [KEY_UPGRADE] it is instead the one sync after an upgrade (see [CatalogRefreshScheduler.syncAfterUpgrade]),
 * queued by the same rules whether or not the refresh is on.
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
        val now = settings.snapshot()
        val wanted = if (inputData.getBoolean(KEY_UPGRADE, false)) {
            // Already filled in by another sync, or queued for any network before "Only on Wi-Fi" was turned on
            // (the next start queues it again, for Wi-Fi).
            now.upgradeSyncDue() && (!now.refreshOnWifiOnly || inputData.getBoolean(KEY_WIFI_ONLY, false))
        } else {
            now.refreshSchedule() != null
        }
        // Signed out, or setup not finished: nothing to refresh until the user is back.
        if (!wanted || credentials.current.value == null) return Result.success()
        // RomM out of reach (away from home, Tailscale off): the sync would fail and leave "Sync failed" in the
        // library until another one worked, for a sync nobody asked for. A VPN can come up a little after the
        // network, so try again over the next few minutes, then quietly wait for the next refresh (or, after an
        // upgrade, the next start).
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

        /** The one-time sync after an upgrade, as its own work: it doesn't touch the periodic refresh's schedule. */
        const val UPGRADE_NAME = "catalog-upgrade"
        const val KEY_UPGRADE = "upgrade"

        /** Whether the sync after an upgrade was queued to wait for an unmetered network. */
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}

/** How the periodic refresh should run. */
data class RefreshSchedule(val every: CatalogRefresh, val wifiOnly: Boolean)

/**
 * Whether the catalog holds less of each ROM than this Loom stores ([Settings.catalogFields] behind
 * [CatalogSyncer.CATALOG_FIELDS]), once setup is done: the next sync is a full one that fills it in.
 */
fun Settings.upgradeSyncDue(): Boolean = onboardingComplete && catalogFields < CatalogSyncer.CATALOG_FIELDS

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

    /**
     * The first start after an upgrade that stores more of each ROM than the catalog holds
     * ([upgradeSyncDue]): a sync, which the syncer makes a full one, so every game gets what's new
     * without the owner asking. Nobody asked for it either, so it goes by the refresh's rules, even with
     * the refresh off: with "Only on Wi-Fi" it waits for Wi-Fi, it waits while the battery is low, and
     * RomM out of reach puts it off quietly instead of leaving "Sync failed" in the library. It waits as
     * its own work, so the library doesn't say "Syncing…" meanwhile and a Sync pressed meanwhile isn't
     * held back with it. Until a sync has filled the catalog in, every start asks again; one already
     * queued is kept. Not while signed out ([paired] false), where it could only fail. True if it queued one.
     */
    fun syncAfterUpgrade(settings: Settings, paired: Boolean): Boolean {
        if (!settings.upgradeSyncDue() || !paired) return false
        val wifiOnly = settings.refreshOnWifiOnly
        val request = OneTimeWorkRequestBuilder<CatalogRefreshWorker>()
            .setInputData(workDataOf(CatalogRefreshWorker.KEY_UPGRADE to true, CatalogRefreshWorker.KEY_WIFI_ONLY to wifiOnly))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(CatalogRefreshWorker.UPGRADE_NAME, ExistingWorkPolicy.KEEP, request)
        return true
    }
}
