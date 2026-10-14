package ro.davidmontei.loom.data.saves

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ro.davidmontei.loom.MainActivity
import ro.davidmontei.loom.R

/**
 * Tells the user what a background run couldn't do alone: conflicts to settle, and newer saves on
 * RomM (downloads wait for the user, who has to close the emulator first). Both open the queue
 * screen, where conflicts are listed.
 */
class SaveSyncNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_saves), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun after(report: SaveSyncReport) {
        if (report.conflicts.isNotEmpty()) {
            post(CONFLICTS_ID, R.plurals.saves_conflicts_title, report.conflicts.size, R.string.saves_conflicts_text)
        }
        if (report.serverNewer.isNotEmpty()) {
            post(SERVER_NEWER_ID, R.plurals.saves_server_newer_title, report.serverNewer.size, R.string.saves_server_newer_text)
        }
    }

    private fun post(id: Int, title: Int, count: Int, text: Int) {
        if (!manager.areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_loom)
            .setContentTitle(context.resources.getQuantityString(title, count, count))
            .setContentText(context.getString(text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text)))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, id,
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_QUEUE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        @Suppress("MissingPermission") // areNotificationsEnabled covers POST_NOTIFICATIONS
        manager.notify(id, builder.build())
    }

    companion object {
        const val CHANNEL = "saves"
        const val CONFLICTS_ID = 2001
        const val SERVER_NEWER_ID = 2002
    }
}
