package ro.davidmontei.loom.data.download

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.storage.engine.JobPhase

class DrainWorkerTest {
    private fun job(phase: JobPhase) = DownloadJobEntity(
        id = 7, romId = 10, platformId = 1, title = "Gran Turismo 2", phase = phase.name, planJson = "{}", fingerprint = "f",
        volumeUuid = "9C33-6BBD", stagingRoot = "/staging", romsRoot = "/roms", bytesTotal = 1, queueOrder = 1, updatedAt = 1,
    )

    /** The notification's Cancel would do nothing once the game is being moved into place, so it isn't offered then. */
    @Test fun theNotificationOffersCancelUntilTheGameIsMovedIntoPlace() {
        assertThat(DrainWorker.cancelTarget(job(JobPhase.DOWNLOADING))).isEqualTo(7L)
        assertThat(DrainWorker.cancelTarget(job(JobPhase.EXTRACTING))).isEqualTo(7L)
        assertThat(DrainWorker.cancelTarget(job(JobPhase.PROMOTING))).isNull()
        assertThat(DrainWorker.cancelTarget(null)).isNull() // installed meanwhile
    }
}
