package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.download.LiveProgress
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.ui.queue.queueItems
import ro.davidmontei.loom.ui.second.queueStrip

/** What the second screen says of the queue under the game: the download under way, and how much more there is. */
class QueueStripTest {
    private fun job(id: Long, title: String, phase: JobPhase, done: Long = 0, total: Long = 4_000_000) = DownloadJobEntity(
        id = id, romId = id.toInt(), platformId = 1, title = title, phase = phase.name, planJson = "{}", fingerprint = "f$id",
        volumeUuid = "9C33-6BBD", stagingRoot = "/storage/9C33-6BBD/Android/data/staging", romsRoot = "/storage/9C33-6BBD/ROMs",
        bytesDone = done, bytesTotal = total, queueOrder = id, updatedAt = 0,
    )

    @Test fun namesTheDownloadUnderWayAndCountsTheRest() {
        val jobs = listOf(
            job(1, "Metroid Prime (USA)", JobPhase.PAUSED), job(2, "Final Fantasy VII (USA)", JobPhase.DOWNLOADING, done = 1_000_000),
            job(3, "Mario Kart DS (USA)", JobPhase.QUEUED), job(4, "Chrono Cross (USA)", JobPhase.FAILED),
        )
        val strip = queueStrip(queueItems(jobs, mapOf(2L to LiveProgress(2_000_000, 11_800_000, 0))), conflicts = 0)!!
        assertThat(strip.current!!.title).isEqualTo("Final Fantasy VII (USA)")
        assertThat(strip.current!!.label).isEqualTo("Downloading")
        assertThat(strip.current!!.fraction).isEqualTo(0.5f) // as it is now, not as last stored
        assertThat(strip.counts).isEqualTo("2 more in the queue · 1 failed")
        // Nothing moving: what waits is counted, none named.
        val waiting = queueStrip(queueItems(jobs.filter { it.phase != JobPhase.DOWNLOADING.name }, emptyMap()), conflicts = 0)!!
        assertThat(waiting.current).isNull()
        assertThat(waiting.counts).isEqualTo("2 in the queue · 1 failed")
    }

    /** As in the library and the queue: a game moved into place is "Finishing…", its bar full. */
    @Test fun aMoveIntoPlaceSaysFinishing() {
        val strip = queueStrip(queueItems(listOf(job(1, "Chrono Trigger (USA)", JobPhase.PROMOTING, done = 4_000_000)), emptyMap()), 0)!!
        assertThat(strip.current!!.label).isEqualTo("Finishing…")
        assertThat(strip.current!!.fraction).isEqualTo(1f)
        assertThat(strip.counts).isEmpty()
    }

    @Test fun countsConflicts() {
        assertThat(queueStrip(emptyList(), conflicts = 1)!!.counts).isEqualTo("1 save conflict")
        val strip = queueStrip(queueItems(listOf(job(1, "Chrono Trigger (USA)", JobPhase.DOWNLOADING)), emptyMap()), conflicts = 2)!!
        assertThat(strip.counts).isEqualTo("2 save conflicts")
    }

    @Test fun nothingQueuedShowsNoStrip() {
        assertThat(queueStrip(emptyList(), conflicts = 0)).isNull()
        // A cancelled download on its way out of the queue isn't in it.
        assertThat(queueStrip(queueItems(listOf(job(1, "Chrono Trigger (USA)", JobPhase.CANCELLING)), emptyMap()), conflicts = 0)).isNull()
    }
}
