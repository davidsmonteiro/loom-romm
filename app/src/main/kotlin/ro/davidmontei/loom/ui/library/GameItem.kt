package ro.davidmontei.loom.ui.library

import ro.davidmontei.loom.data.db.GameRow
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.db.SaveUnitEntity
import ro.davidmontei.loom.data.saves.SaveLook
import ro.davidmontei.loom.data.saves.SaveUnitResolver
import ro.davidmontei.loom.data.saves.emulatorName
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
    val platformId: Int = 0,
    /** Where the save stands, for an installed game; null otherwise. */
    val save: GameSave? = null,
    /** Adopted by its name alone: nothing said what size the game should be, so it may be another copy. */
    val matchedByName: Boolean = false,
)

/** Where an installed game's save stands, as its details show it. */
data class GameSave(
    /** Whether Loom syncs saves for the game's platform at all. */
    val supported: Boolean,
    /** Null until Loom has looked at this game's save. */
    val state: SaveSyncState?,
    /** Why it isn't synced or failed, in words. */
    val detail: String?,
    /** The emulator id the save is synced as, once known. */
    val emulator: String?,
    /** Whether Loom uploads a changed save by itself (automatic upload); off, only Upload save does. */
    val autoUpload: Boolean = true,
    /** The user chose the game's save folder (Loom couldn't read its title ID): they can choose another. */
    val folderChosen: Boolean = false,
    /** The look at the save its details asked for, while it runs or when it couldn't ask RomM. */
    val look: SaveLook? = null,
)

/**
 * Adds where the game's save stands, once it's installed: [unit] (null before Loom first looked)
 * on a platform Loom syncs saves for, else why it doesn't. [autoUpload]: the automatic upload
 * setting; [folderChosen]: the user chose the game's save folder; [look]: the details' look at it.
 */
fun GameItem.withSave(
    platformSlug: String, unit: SaveUnitEntity?, autoUpload: Boolean = true, folderChosen: Boolean = false, look: SaveLook? = null,
): GameItem = copy(
    save = when {
        state != InstallState.INSTALLED -> null
        !SaveUnitResolver.supportsPlatform(platformSlug) ->
            GameSave(supported = false, SaveSyncState.UNSUPPORTED, SaveUnitResolver.unsupportedReason(platformSlug), emulator = null)
        else -> GameSave(supported = true, unit?.state, unit?.detail, unit?.emulator ?: SaveUnitResolver.emulatorForPlatform(platformSlug),
            autoUpload, folderChosen, look)
    },
)

/** What the details say of their look at the save: running, or why it only looked at the Thor's side. Empty otherwise. */
fun lookLine(look: SaveLook?): String = when (look) {
    null -> ""
    SaveLook.Looking -> "Checking the save…"
    is SaveLook.ThorOnly -> "${look.reason}: checked the Thor's side only"
}

/** The details' line about a game's save. With automatic upload off, nothing uploads by itself: it names Upload save. */
fun saveLine(save: GameSave): String = "Save: " + when (save.state) {
    null -> "not checked yet"
    SaveSyncState.IN_SYNC -> "in sync with RomM"
    SaveSyncState.NO_SAVE -> "none yet"
    SaveSyncState.LOCAL_NEWER -> if (save.autoUpload) "newer on the Thor" else "newer on the Thor; Upload save sends it"
    SaveSyncState.WRITING -> "newer on the Thor; ${if (save.autoUpload) "Loom uploads" else "Upload save sends"} it once " +
        "${emulatorName(save.emulator)} has left it alone for a minute"
    SaveSyncState.SERVER_NEWER -> "newer on RomM"
    SaveSyncState.LOCAL_MISSING -> "missing on the Thor; RomM has a copy"
    SaveSyncState.CONFLICT -> "changed on the Thor and on RomM. Choose which to keep."
    SaveSyncState.NEEDS_FOLDER -> "choose its ${emulatorName(save.emulator)} save folder. " + save.detail.orEmpty()
    SaveSyncState.UNSUPPORTED -> "not synced. " + save.detail.orEmpty()
    SaveSyncState.ERROR -> "the last sync failed. " + save.detail.orEmpty()
}

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
        platformId = platformId,
        retryable = failureRetryable ?: true,
        matchedByName = installSource == "ADOPTED" && installVerifiedBy == "NAME",
    )
}
