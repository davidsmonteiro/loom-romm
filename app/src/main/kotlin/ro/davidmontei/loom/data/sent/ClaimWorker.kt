package ro.davidmontei.loom.data.sent

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
import java.util.concurrent.TimeUnit

/**
 * Claims the games sent from RomM while Loom is in the background, so one sent then still arrives
 * without opening Loom (within [PERIOD_MINUTES], WorkManager's shortest period).
 */
@HiltWorker
class ClaimWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sendToDevice: SendToDevice,
) : CoroutineWorker(context, params) {

    /** Whatever goes wrong is left for the next claim, which RomM answers with every request not reported yet. */
    override suspend fun doWork(): Result {
        sendToDevice.drain()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "send-to-device-claim"
        const val PERIOD_MINUTES = 15L
    }
}

class ClaimScheduler(private val workManager: WorkManager) {

    /** The periodic claim exists exactly while games sent from RomM can come ([ready]). */
    fun schedule(ready: Boolean) {
        if (!ready) {
            workManager.cancelUniqueWork(ClaimWorker.UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ClaimWorker>(ClaimWorker.PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE keeps when the schedule started, so applying it again at every start doesn't put the next claim off.
        workManager.enqueueUniquePeriodicWork(ClaimWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
