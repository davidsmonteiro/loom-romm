package ro.davidmontei.loom.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import ro.davidmontei.loom.MainActivity
import ro.davidmontei.loom.R
import ro.davidmontei.loom.storage.engine.formatBytes

/** The downloads notification (foreground) and short result notifications. */
class DownloadNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_download_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun foreground(title: String, done: Long, total: Long, queued: Int, jobId: Long?): ForegroundInfo {
        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
        val text = if (total > 0) "${formatBytes(done)} of ${formatBytes(total)}" else context.getString(R.string.download_preparing)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_loom)
            .setContentTitle(title)
            .setContentText(if (queued > 0) "$text · ${context.resources.getQuantityString(R.plurals.download_more_queued, queued, queued)}" else text)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openQueue())
            .addAction(0, context.getString(R.string.action_pause_all), action(DownloadActionReceiver.ACTION_PAUSE_ALL, null))
        if (jobId != null) builder.addAction(0, context.getString(R.string.action_cancel), action(DownloadActionReceiver.ACTION_CANCEL, jobId))
        return ForegroundInfo(FOREGROUND_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    fun installed(title: String, where: String) = post(
        NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_loom)
            .setContentTitle(context.getString(R.string.download_installed, title))
            .setContentText(where)
            .setAutoCancel(true)
            .setContentIntent(openQueue()),
        title.hashCode(),
    )

    fun failed(title: String, reason: String?) = post(
        NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_loom)
            .setContentTitle(context.getString(R.string.download_failed, title))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setContentIntent(openQueue()),
        title.hashCode(),
    )

    /** Android wouldn't let downloads start in the background; opening Loom resumes them. */
    fun resumeHint() = post(
        NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_loom)
            .setContentTitle(context.getString(R.string.download_waiting_title))
            .setContentText(context.getString(R.string.download_waiting_text))
            .setAutoCancel(true)
            .setContentIntent(openQueue()),
        RESUME_HINT_ID,
    )

    private fun post(builder: NotificationCompat.Builder, id: Int) {
        if (manager.areNotificationsEnabled()) {
            @Suppress("MissingPermission") // areNotificationsEnabled covers POST_NOTIFICATIONS
            manager.notify(id, builder.build())
        }
    }

    private fun openQueue(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_QUEUE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(action: String, jobId: Long?): PendingIntent = PendingIntent.getBroadcast(
        context, (action.hashCode() * 31 + (jobId ?: 0).toInt()),
        Intent(context, DownloadActionReceiver::class.java).setAction(action).apply { jobId?.let { putExtra(DownloadActionReceiver.EXTRA_JOB_ID, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_RESULTS = "download_results"
        const val FOREGROUND_ID = 1001
        const val RESUME_HINT_ID = 1002
    }
}
