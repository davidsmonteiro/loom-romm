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
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.install.InstallManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import ro.davidmontei.loom.di.ApiHttp
import ro.davidmontei.loom.di.AppScope
import ro.davidmontei.loom.storage.engine.WaitReason
import javax.inject.Inject

@HiltAndroidApp
class LoomApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var downloads: DownloadRepository
    @Inject @AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var installs: InstallManager
    @Inject @ApiHttp lateinit var apiClient: OkHttpClient

    /**
     * Cover art: through the authenticated client (the token only ever goes to the RomM host), with
     * a large disk cache so the library still has its covers offline.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { apiClient })) }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers").toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
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
                    // Files may have been added or deleted in a file manager while Loom was away.
                    // Listing every system folder isn't free, so at most once a minute.
                    val now = System.currentTimeMillis()
                    if (now - lastReconcile > 60_000) {
                        lastReconcile = now
                        runCatching { installs.emptyTrash(); installs.reconcile() }
                    }
                }
            }
        })
    }
}
