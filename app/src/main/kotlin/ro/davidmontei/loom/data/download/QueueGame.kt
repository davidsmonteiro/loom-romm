package ro.davidmontei.loom.data.download

import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.net.NotPairedException
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.dto.RomDto
import ro.davidmontei.loom.storage.mapping.FolderDecision
import ro.davidmontei.loom.storage.mapping.SystemFolder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The card has several folders a platform's games could go in ([FolderDecision.Ask]): which one
 * Cocoon reads is asked once, and the answer is remembered as the platform's folder.
 */
data class FolderAsk(
    val romId: Int,
    val platformName: String,
    val slug: String,
    val fsSlug: String,
    val options: List<SystemFolder>,
    val suggested: String,
)

/**
 * Puts a game in the download queue: X in the library, or a game sent from RomM. A platform's first
 * download settles its system folder, from what is on the card when that's clear, else by asking
 * ([Outcome.AskFolder]); either way the choice is remembered.
 */
@Singleton
class QueueGame @Inject constructor(
    private val catalog: CatalogDao,
    private val downloads: DownloadRepository,
    private val folders: FolderChooser,
    private val syncScheduler: CatalogSyncScheduler,
) {
    sealed interface Outcome {
        /** [folderNote]: where the platform's games go, said the one time Loom found that on the card. */
        data class Queued(val jobId: Long, val folderNote: String? = null) : Outcome
        data class AskFolder(val ask: FolderAsk) : Outcome
        data object AlreadyQueued : Outcome
        data object AlreadyInstalled : Outcome

        /** [transient]: a passing problem (RomM out of reach or busy); asking again later can get past it. */
        data class Failed(val message: String, val transient: Boolean = false) : Outcome
    }

    private data class Platform(val name: String, val slug: String, val fsSlug: String)

    /** Queues [romId]; [installRequestId] is the RomM install request that sent it, when RomM did. */
    suspend fun queue(romId: Int, installRequestId: String? = null): Outcome {
        // Already in the queue or on the card: nothing to decide (a failed download is retried).
        if (downloads.hasJob(romId) || catalog.install(romId) != null) return downloads.enqueue(romId, installRequestId).outcome()
        val inCatalog = catalog.rom(romId)?.let { catalog.platform(it.platformId) }
        // Added on RomM after the last library sync: RomM says its platform, and a sync brings both to the library.
        val fetched: RomDto? = if (inCatalog != null) null else try {
            downloads.romFromRomm(romId).also { syncScheduler.enqueue() }
        } catch (e: NotPairedException) {
            return downloads.whyNot(e).outcome()
        } catch (e: RommException) {
            return downloads.whyNot(e).outcome()
        }
        val platform = inCatalog?.let { Platform(it.displayName, it.slug, it.fsSlug) }
            ?: fetched!!.let { Platform(it.platformSlug, it.platformSlug, it.platformFsSlug) }
        return when (val d = folders.decide(platform.slug, platform.fsSlug)) {
            null -> downloads.enqueue(romId, installRequestId, fetched).outcome() // the card can't be read: queueing says why
            is FolderDecision.Ask -> Outcome.AskFolder(FolderAsk(romId, platform.name, platform.slug, platform.fsSlug, d.options, d.suggested))
            is FolderDecision.Use -> {
                if (d.why != FolderDecision.Why.CHOSEN) folders.choose(platform.fsSlug, d.folder, detected = true)
                // Found on the card rather than the default: say where, the one time it's decided.
                val found = d.why == FolderDecision.Why.HAS_GAMES || d.why == FolderDecision.Why.ONLY_FOLDER
                downloads.enqueue(romId, installRequestId, fetched).outcome(note = if (found) "${platform.name} games go in ${d.folder}/" else null)
            }
        }
    }

    /** The answer to [Outcome.AskFolder]: remembered for the platform, then [thenQueue] is queued there (null when none is). */
    suspend fun folderChosen(fsSlug: String, folder: String, thenQueue: Int?): Outcome? {
        folders.choose(fsSlug, folder)
        return thenQueue?.let { downloads.enqueue(it).outcome() }
    }

    private fun EnqueueResult.outcome(note: String? = null): Outcome = when (this) {
        is EnqueueResult.Queued -> Outcome.Queued(jobId, note)
        EnqueueResult.AlreadyQueued -> Outcome.AlreadyQueued
        EnqueueResult.AlreadyInstalled -> Outcome.AlreadyInstalled
        is EnqueueResult.Failed -> Outcome.Failed(message, transient)
    }
}
