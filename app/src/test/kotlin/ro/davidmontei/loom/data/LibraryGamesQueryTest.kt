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
import ro.davidmontei.loom.data.db.CoverPaths
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

    private suspend fun download(phase: JobPhase, failureCode: String? = null, failureDetail: String? = null, retryable: Boolean = true) {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(1, "psx", "psx", "PlayStation", "PlayStation", 1, "t")))
        db.catalogDao().upsertRoms(listOf(RomEntity(10, 1, "psx", "psx", "Gran Turismo 2", "gran turismo 2", "gran turismo 2",
            "GT2.chd", "GT2", "chd", 5_000_000_000, true, false, false, null, null, "USA", "t", false)))
        db.downloadDao().insertJobWithParts(
            DownloadJobEntity(romId = 10, platformId = 1, title = "Gran Turismo 2", phase = phase.name,
                failureCode = failureCode, failureDetail = failureDetail,
                failureRetryable = retryable, planJson = "{}", fingerprint = "f", volumeUuid = "9C33-6BBD",
                stagingRoot = "/staging", romsRoot = "/roms", bytesTotal = 5_000_000_000, queueOrder = 1, updatedAt = 1),
        ) { emptyList() }
    }

    private suspend fun failedDownload(retryable: Boolean) =
        download(JobPhase.FAILED, "FILE_TOO_LARGE_FOR_SD", "This card can't hold files over 4 GB (FAT32).", retryable)

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

    /** Found by review: the details offered Cancel while the game was moved into place, which can't be cancelled. */
    @Test fun aGameBeingMovedIntoPlaceIsFinishing() = runBlocking<Unit> {
        download(JobPhase.PROMOTING)
        val game = db.catalogDao().observeGames(1, 0, "").first().single().toItem()
        assertThat(game.state).isEqualTo(InstallState.DOWNLOADING)
        assertThat(game.finishing).isTrue()
        assertThat(game.stateDetail).isEqualTo("Finishing…")
    }

    /** Covers are made from RomM's large art, so the library and the prefetch get it with the small cover that names it. */
    @Test fun aGameCarriesItsLargeCoverArt() = runBlocking<Unit> {
        db.catalogDao().upsertPlatforms(listOf(PlatformEntity(1, "psx", "psx", "PlayStation", "PlayStation", 2, "t")))
        fun rom(id: Int, small: String?, large: String?) = RomEntity(id, 1, "psx", "psx", "Game $id", "game $id", "game $id",
            "G$id.chd", "G$id", "chd", 1, true, false, false, small, large, "USA", "t", false)
        db.catalogDao().upsertRoms(listOf(rom(10, "/c/10/small.png", "/c/10/big.png"), rom(11, null, null)))
        assertThat(db.catalogDao().observeGames(1, 0, "").first().first { it.id == 10 }.toItem().coverLarge).isEqualTo("/c/10/big.png")
        assertThat(db.catalogDao().covers()).containsExactly(CoverPaths("/c/10/small.png", "/c/10/big.png"))
    }

    @Test fun aGameStillDownloadingIsNotFinishing() = runBlocking<Unit> {
        download(JobPhase.DOWNLOADING)
        assertThat(db.catalogDao().observeGames(1, 0, "").first().single().toItem().finishing).isFalse()
    }
}
