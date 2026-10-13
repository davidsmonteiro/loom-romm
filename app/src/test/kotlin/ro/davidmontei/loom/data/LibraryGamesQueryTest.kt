package ro.davidmontei.loom.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.library.toItem

/** What the library's game query tells the screen about a game's download. */
@RunWith(AndroidJUnit4::class)
class LibraryGamesQueryTest {
    private lateinit var db: LoomDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LoomDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private suspend fun failedDownload(retryable: Boolean) {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(1, "psx", "psx", "PlayStation", "PlayStation", 1, "t")))
        db.catalogDao().upsertRoms(listOf(RomEntity(10, 1, "psx", "psx", "Gran Turismo 2", "gran turismo 2", "gran turismo 2",
            "GT2.chd", "GT2", "chd", 5_000_000_000, true, false, false, null, null, "USA", "t", false)))
        db.downloadDao().insertJobWithParts(
            DownloadJobEntity(romId = 10, platformId = 1, title = "Gran Turismo 2", phase = JobPhase.FAILED.name,
                failureCode = "FILE_TOO_LARGE_FOR_SD", failureDetail = "This card can't hold files over 4 GB (FAT32).",
                failureRetryable = retryable, planJson = "{}", fingerprint = "f", volumeUuid = "9C33-6BBD",
                stagingRoot = "/staging", romsRoot = "/roms", bytesTotal = 5_000_000_000, queueOrder = 1, updatedAt = 1),
        ) { emptyList() }
    }

    /** Found by audit: the library offered Retry for failures the queue refuses to retry. */
    @Test fun aFailureRetryingCantFixReachesTheLibrary() = runBlocking<Unit> {
        failedDownload(retryable = false)
        val game = db.catalogDao().observeGames(1, 0, "").first().single().toItem()
        assertThat(game.state).isEqualTo(InstallState.FAILED)
        assertThat(game.retryable).isFalse()
        assertThat(db.catalogDao().observeGame(10).first()!!.toItem().retryable).isFalse()
    }

    @Test fun otherFailuresCanBeRetried() = runBlocking<Unit> {
        failedDownload(retryable = true)
        assertThat(db.catalogDao().observeGames(1, 0, "").first().single().toItem().retryable).isTrue()
    }
}
