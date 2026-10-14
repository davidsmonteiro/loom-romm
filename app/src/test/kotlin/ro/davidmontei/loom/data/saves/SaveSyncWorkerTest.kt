package ro.davidmontei.loom.data.saves

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import ro.davidmontei.loom.data.settings.ServerConfig

/** What each kind of background run may do, and what it tells the user. */
@RunWith(AndroidJUnit4::class)
class SaveSyncWorkerTest : SaveSyncTestBase() {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun run(kind: SaveSyncKind): ListenableWorker.Result =
        TestListenableWorkerBuilder<SaveSyncWorker>(context)
            .setInputData(workDataOf(SaveSyncWorker.KEY_KIND to kind.name))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SaveSyncWorker(appContext, workerParameters, engine(), settings, SaveSyncNotifications(appContext))
            })
            .build()
            .doWork()

    @Test fun theAutomaticRunUploadsChangedSaves() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        val result = run(SaveSyncKind.AUTO)
        assertThat(result).isEqualTo(ListenableWorker.Result.success(workDataOf(
            SaveSyncWorker.KEY_UPLOADED to 1, SaveSyncWorker.KEY_DOWNLOADED to 0,
            SaveSyncWorker.KEY_CONFLICTS to 0, SaveSyncWorker.KEY_SERVER_NEWER to 0,
        )))
        assertThat(romm.uploads()).hasSize(1)
    }

    @Test fun withAutomaticUploadOffTheAutomaticRunDoesNothing() = runBlocking<Unit> {
        settings.setAutoUploadSaves(false)
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        assertThat(run(SaveSyncKind.AUTO)).isEqualTo(ListenableWorker.Result.success())
        assertThat(romm.requests).isEmpty()

        // PUSH_SAVES is the user asking: it uploads anyway.
        run(SaveSyncKind.PUSH)
        assertThat(romm.uploads()).hasSize(1)
    }

    /** PULL_SAVES can come from any app on the device: it only ever does the safe downloads. */
    @Test fun pullNeverUploads() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        run(SaveSyncKind.PULL)
        assertThat(romm.uploads()).isEmpty()
        assertThat(db.saveDao().unit(7)!!.state).isEqualTo(ro.davidmontei.loom.data.db.SaveSyncState.LOCAL_NEWER)
    }

    @Test fun conflictsAndNewerSavesAreNotified() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2))) // a different save, never synced: a conflict
        romm.addSave(8, archiveOf(bytes(3))) // Omega Ruby has no save here: newer on the server

        run(SaveSyncKind.AUTO)

        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        val titles = posted.map { it.extras.getString("android.title") }
        assertThat(titles).containsExactly("A game's save needs you", "RomM has a newer save for a game")
    }

    @Test fun withoutARommDeviceTheRunFailsSayingWhy() = runBlocking<Unit> {
        pair(deviceId = null)
        val result = run(SaveSyncKind.PUSH)
        assertThat(result).isEqualTo(ListenableWorker.Result.failure(workDataOf(SaveSyncWorker.KEY_ERROR to SaveSyncEngine.NO_DEVICE)))
    }

    @Test fun anUnreachableServerIsTriedAgain() = runBlocking<Unit> {
        settings.setServer(ServerConfig("http://127.0.0.1:1/".toHttpUrl()), "5.3.1")
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        assertThat(run(SaveSyncKind.AUTO)).isEqualTo(ListenableWorker.Result.retry())
    }
}
