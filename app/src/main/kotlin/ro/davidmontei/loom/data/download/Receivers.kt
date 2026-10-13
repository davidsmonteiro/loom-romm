package ro.davidmontei.loom.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ro.davidmontei.loom.di.AppScope
import ro.davidmontei.loom.storage.engine.WaitReason
import javax.inject.Inject

/** Pause/cancel buttons on the downloads notification. Not exported. */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: DownloadRepository
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE_ALL -> repository.pauseAll()
                    ACTION_CANCEL -> intent.getLongExtra(EXTRA_JOB_ID, -1).takeIf { it > 0 }?.let { repository.cancel(it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "ro.davidmontei.loom.action.PAUSE_ALL_DOWNLOADS"
        const val ACTION_CANCEL = "ro.davidmontei.loom.action.CANCEL_DOWNLOAD"
        const val EXTRA_JOB_ID = "job_id"
    }
}

/** The SD card came back: downloads waiting for storage rejoin the queue. */
@AndroidEntryPoint
class MediaMountReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: DownloadRepository
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        val pending = goAsync()
        scope.launch {
            try { repository.resumeWaiting(WaitReason.STORAGE) } finally { pending.finish() }
        }
    }
}
