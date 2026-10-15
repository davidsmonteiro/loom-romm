package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.data.db.GameRow
import ro.davidmontei.loom.data.db.RomDetailsEntity
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.library.InstallState
import ro.davidmontei.loom.ui.library.companiesLine
import ro.davidmontei.loom.ui.library.factsLine
import ro.davidmontei.loom.ui.library.releaseLine
import ro.davidmontei.loom.ui.library.stateBadgeText
import ro.davidmontei.loom.ui.library.stateLines
import ro.davidmontei.loom.ui.library.toDetails
import ro.davidmontei.loom.ui.library.toItem

/** What a game's details say of it: the same words in the details, beside the games and on the second screen. */
class GameFactsTest {
    private fun row(phase: JobPhase? = null, installPath: String? = null, failure: String? = null, retryable: Boolean? = null,
                    done: Long? = null, total: Long? = null) = GameRow(
        id = 1, platformId = 2, title = "Final Fantasy VII (USA)", sizeBytes = 1_500_000, coverSmall = null, coverLarge = null,
        regions = "USA,Europe", removedOnServer = false, installPath = installPath, installSource = installPath?.let { "LOOM" },
        installBytes = null, installVerifiedBy = null, jobId = phase?.let { 9L }, jobPhase = phase?.name, waitReason = null,
        failureDetail = failure, failureRetryable = retryable, bytesDone = done, bytesTotal = total,
    )

    @Test fun theFactsNamePlatformSizeAndRegions() {
        val game = row().toItem()
        assertThat(factsLine(game, "PlayStation")).isEqualTo("PlayStation · ${formatBytes(1_500_000)} · USA, Europe")
        assertThat(factsLine(game.copy(regions = emptyList()), null)).isEqualTo(formatBytes(1_500_000))
        assertThat(stateLines(game)).containsExactly("Not on this device")
        assertThat(stateBadgeText(game)).isNull()
        val installed = row(installPath = "psx/Final Fantasy VII (USA).chd").toItem()
        assertThat(stateLines(installed)).containsExactly("On this device at psx/Final Fantasy VII (USA).chd")
        assertThat(stateBadgeText(installed)).isEqualTo("On device")
    }

    /**
     * What RomM says of the game, in the words the panels show it: the year and genres in a line, the
     * companies in another, a company's comma kept as its own. The details give only the year, after the platform.
     */
    @Test fun rommsDetailsReadAsTheirLines() {
        val details = RomDetailsEntity(1, "A summary.", 1997, "Role-playing (RPG)\nAdventure", "Square Co., Ltd.\nSony").toDetails()
        assertThat(details.genres).containsExactly("Role-playing (RPG)", "Adventure").inOrder()
        assertThat(details.companies).containsExactly("Square Co., Ltd.", "Sony").inOrder()
        assertThat(releaseLine(details)).isEqualTo("1997 · Role-playing (RPG), Adventure")
        assertThat(companiesLine(details)).isEqualTo("Square Co., Ltd., Sony")
        assertThat(factsLine(row().toItem(), "PlayStation", details.releaseYear))
            .isEqualTo("PlayStation · 1997 · ${formatBytes(1_500_000)} · USA, Europe")

        val yearOnly = RomDetailsEntity(1, null, 1997, "", "").toDetails()
        assertThat(yearOnly.genres).isEmpty()
        assertThat(releaseLine(yearOnly)).isEqualTo("1997")
        assertThat(companiesLine(yearOnly)).isNull()
        assertThat(releaseLine(yearOnly.copy(releaseYear = null))).isNull()
        assertThat(releaseLine(yearOnly.copy(releaseYear = null, genres = listOf("Puzzle")))).isEqualTo("Puzzle")
    }

    /** Moved into place, a game's download is complete: what it is doing then is "Finishing…", not "100%". */
    @Test fun aFinishingDownloadSaysFinishingEverywhere() {
        val game = row(JobPhase.PROMOTING, done = 4_000, total = 4_000).toItem()
        assertThat(game.progress).isEqualTo(1f)
        assertThat(stateBadgeText(game)).isEqualTo("Finishing…")
        assertThat(stateLines(game)).containsExactly("Moving it into your ROMs folder")
        val downloading = row(JobPhase.DOWNLOADING, done = 1_000, total = 4_000).toItem()
        assertThat(stateBadgeText(downloading)).isEqualTo("25%")
        assertThat(stateLines(downloading)).containsExactly("Downloading")
    }

    @Test fun aFailedDownloadSaysWhy() {
        val full = "Needs 1.4 GB free on the SD card; 900 MB available"
        val game = row(JobPhase.FAILED, failure = full, retryable = false).toItem()
        assertThat(game.state).isEqualTo(InstallState.FAILED)
        assertThat(stateBadgeText(game)).isEqualTo("Failed")
        assertThat(stateLines(game)).containsExactly(full, "Retrying won't help until this is fixed.").inOrder()
        assertThat(stateLines(row(JobPhase.FAILED, failure = "SHA1 doesn't match RomM's", retryable = true).toItem()))
            .containsExactly("SHA1 doesn't match RomM's")
    }
}
