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
import ro.davidmontei.loom.di.AppScope
import ro.davidmontei.loom.storage.engine.WaitReason
import javax.inject.Inject

@HiltAndroidApp
class LoomApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var downloads: DownloadRepository
    @Inject @AppScope lateinit var scope: CoroutineScope
    @Inject lateinit var installs: InstallManager

    // WorkManager's default initializer is removed in the manifest so workers get Hilt injection.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Coming to the foreground lets Android start the download service again: resume anything
        // that was parked for that, and make sure a worker is draining the queue.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    downloads.resumeWaiting(WaitReason.APP_OPEN)
                    // Files may have been added or deleted in a file manager while Loom was away.
                    runCatching { installs.emptyTrash(); installs.reconcile() }
                }
            }
        })
    }
}
