package ro.davidmontei.loom.ui.library

import ro.davidmontei.loom.data.db.GameRow
import ro.davidmontei.loom.storage.engine.JobPhase

/** What the catalog shows per ROM (the brief's five states). */
enum class InstallState { NOT_INSTALLED, QUEUED, DOWNLOADING, INSTALLED, FAILED }

/** A game as the library displays it. */
data class GameItem(
    val id: Int,
    val title: String,
    val sizeBytes: Long,
    val coverPath: String?,
    val regions: List<String>,
    val state: InstallState,
    /** Why a queued job isn't moving: "Paused", "Waiting for the SD card"… */
    val stateDetail: String?,
    val progress: Float?,
    val jobId: Long?,
    val adopted: Boolean,
    val removedOnServer: Boolean,
    val installPath: String?,
    /** False for a failed download that retrying can't fix (a card that can't hold the file). */
    val retryable: Boolean = true,
)

/**
 * Derives the install state from the install row and the download job (stored separately, so the
 * state is never stored twice). An active job wins over an existing install: that's a re-download.
 */
fun installState(installPath: String?, jobPhase: String?): InstallState {
    val phase = jobPhase?.let { runCatching { JobPhase.valueOf(it) }.getOrNull() }
    return when {
        phase == null || phase == JobPhase.CANCELLING || phase == JobPhase.DONE ->
            if (installPath != null) InstallState.INSTALLED else InstallState.NOT_INSTALLED
        phase == JobPhase.FAILED -> InstallState.FAILED
        phase.isActive -> InstallState.DOWNLOADING
        else -> InstallState.QUEUED // QUEUED, WAITING, PAUSED
    }
}

fun GameRow.toItem(liveBytes: Long? = null): GameItem {
    val state = installState(installPath, jobPhase)
    val done = liveBytes ?: bytesDone
    val detail = when (jobPhase) {
        JobPhase.PAUSED.name -> "Paused"
        JobPhase.WAITING.name -> if (waitReason == "STORAGE") "Waiting for the SD card" else "Waiting for Loom to open"
        JobPhase.QUEUED.name -> "Queued"
        JobPhase.VERIFYING.name -> "Verifying"
        JobPhase.EXTRACTING.name -> "Unpacking"
        JobPhase.PROMOTING.name -> "Finishing"
        JobPhase.FAILED.name -> failureDetail ?: "Download failed"
        else -> null
    }
    return GameItem(
        id = id,
        title = title,
        sizeBytes = installBytes ?: sizeBytes,
        coverPath = coverSmall,
        regions = regions.split(',').filter { it.isNotBlank() },
        state = state,
        stateDetail = detail,
        progress = if (state == InstallState.DOWNLOADING || state == InstallState.QUEUED) {
            bytesTotal?.takeIf { it > 0 }?.let { total -> ((done ?: 0).toFloat() / total).coerceIn(0f, 1f) }
        } else null,
        jobId = jobId,
        adopted = installSource == "ADOPTED",
        removedOnServer = removedOnServer,
        installPath = installPath,
        retryable = failureRetryable ?: true,
    )
}
