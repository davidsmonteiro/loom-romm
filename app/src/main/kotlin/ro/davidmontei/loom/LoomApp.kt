package ro.davidmontei.loom

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ro.davidmontei.loom.data.catalog.CoverFetcher
import ro.davidmontei.loom.data.catalog.CoverKeyer
import ro.davidmontei.loom.data.catalog.CoverStore
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.saves.SaveSyncTriggers
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import ro.davidmontei.loom.di.AppScope
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
