package ro.davidmontei.loom

import android.app.Application
import android.net.ConnectivityManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.catalog.CatalogRefreshScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CoverFetcher
import ro.davidmontei.loom.data.catalog.CoverKeyer
import ro.davidmontei.loom.data.catalog.CoverStore
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.saves.SaveSyncTriggers
import ro.davidmontei.loom.data.sent.SendToDevice
import ro.davidmontei.loom.data.settings.SettingsStore
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import ro.davidmontei.loom.di.AppScope
import ro.davidmontei.loom.net.ReachabilityWatch
import ro.davidmontei.loom.storage.engine.WaitReason
import javax.inject.Inject

@HiltAndroidApp
class LoomApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var downloads: DownloadRepository
    @Inject @AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var installs: InstallManager
    @Inject lateinit var covers: CoverStore
    @Inject lateinit var saveSync: SaveSyncTriggers
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var refresh: CatalogRefreshScheduler
    @Inject lateinit var reachability: ReachabilityWatch
    @Inject lateinit var sendToDevice: SendToDevice
    @Inject lateinit var catalogSync: CatalogSyncScheduler
    @Inject lateinit var credentials: CredentialStore

    /** Cover art comes only from the [CoverStore], which keeps its own files on disk. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(CoverKeyer)
            add(CoverFetcher.Factory(covers))
        }
        .diskCache { null }
        .build()

    // WorkManager's default initializer is removed in the manifest so workers get Hilt injection.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    @Volatile private var lastReconcile = 0L

    override fun onCreate() {
        super.onCreate()
        // The background catalog refresh follows its settings from here on: scheduled once setup is
        // done, changed or cancelled with them. A settings file that can't be read leaves it as it was.
        scope.launch { runCatching { refresh.follow(settings.settings) } }
        // An upgrade that stores more of each game (RomM's summary and genres, say) fills it in for every
        // game with one full sync, queued on the first open after it.
        scope.launch {
            runCatching {
                credentials.awaitLoaded()
                catalogSync.syncAfterUpgrade(settings.snapshot(), paired = credentials.current.value != null)
            }
        }
        // Whether RomM can be reached, for the library's offline note: looked at again when a network
        // comes or goes (Wi-Fi joined or left, a VPN up or down), and when Loom comes to the front.
        scope.launch { reachability.run() }
        reachability.follow(ProcessLifecycleOwner.get().lifecycle, getSystemService(ConnectivityManager::class.java))
        // Games sent from RomM's web UI: claimed at start and each time Loom comes to the front, and
        // periodically in the background while they can come.
        scope.launch { runCatching { sendToDevice.run() } }
        sendToDevice.follow(ProcessLifecycleOwner.get().lifecycle)
        // Coming to the foreground lets Android start the download service again: resume anything
        // that was parked for that, and make sure a worker is draining the queue.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    downloads.resumeWaiting(WaitReason.APP_OPEN)
                    // Files may have been added or deleted in a file manager, and saves changed by
                    // the emulators, while Loom was away. Listing every system folder isn't free,
                    // so at most once a minute.
                    val now = System.currentTimeMillis()
                    if (now - lastReconcile > 60_000) {
                        lastReconcile = now
                        runCatching { installs.emptyTrash(); installs.reconcile() }
                        runCatching { saveSync.appOpened() }
                    }
                }
            }
        })
    }
}
