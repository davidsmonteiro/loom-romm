package ro.davidmontei.loom.data.download

import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.PlatformEntity
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
    /** The game it's asked for. */
    val romId: Int,
    val platformId: Int,
    val platformName: String,
    val slug: String,
    val fsSlug: String,
    val options: List<SystemFolder>,
    val suggested: String,
)

/**
 * Puts a game in the download queue: X in the library, or a game sent from RomM. A platform's first
 * download settles its system folder, from what is on the card when that's clear, else by asking
 * ([Outcome.AskFolder]), or by the game waiting in the queue to be asked about there
 * ([Outcome.WaitingForFolder]); either way the choice is remembered.
 */
@Singleton
class QueueGame @Inject constructor(
    private val catalog: CatalogDao,
    private val downloads: DownloadRepository,
    private val folders: FolderChooser,
    private val syncScheduler: CatalogSyncScheduler,
) {
    /** What queueing does when the card doesn't settle where the platform's games go. */
    enum class WhenUnsure {
        /** Asks now ([Outcome.AskFolder]): someone pressed X in the library, there to answer. */
        ASK,

        /** Queues the game waiting for its folder ([Outcome.WaitingForFolder]), asked about in the queue: a game sent from RomM. */
        WAIT_IN_QUEUE,
    }

    sealed interface Outcome {
        /** [folderNote]: where the platform's games go, said the one time Loom found that on the card. */
        data class Queued(val jobId: Long, val folderNote: String? = null) : Outcome
        data class AskFolder(val ask: FolderAsk) : Outcome

        /** In the queue, waiting there for [ask] to be answered ([askFolder]). */
        data class WaitingForFolder(val jobId: Long, val ask: FolderAsk) : Outcome
        data object AlreadyQueued : Outcome
        data object AlreadyInstalled : Outcome

        /** [transient]: a passing problem (RomM out of reach or busy); asking again later can get past it. */
        data class Failed(val message: String, val transient: Boolean = false) : Outcome
    }

    private data class Platform(val id: Int, val name: String, val slug: String, val fsSlug: String) {
        constructor(p: PlatformEntity) : this(p.id, p.displayName, p.slug, p.fsSlug)

        fun ask(romId: Int, d: FolderDecision.Ask) = FolderAsk(romId, id, name, slug, fsSlug, d.options, d.suggested)
    }

    /** Queues [romId]; [installRequestId] is the RomM install request that sent it, when RomM did. */
    suspend fun queue(romId: Int, whenUnsure: WhenUnsure, installRequestId: String? = null): Outcome {
        // Already in the queue or on the card: nothing to decide (a failed download is retried).
        if (downloads.hasJob(romId) || catalog.install(romId) != null) return downloads.enqueue(romId, installRequestId).outcome()
        val (platform, fetched) = try {
            platformOf(romId)
        } catch (e: NotPairedException) {
            return downloads.whyNot(e).outcome()
        } catch (e: RommException) {
            return downloads.whyNot(e).outcome()
        }
        return when (val d = folders.decide(platform.slug, platform.fsSlug)) {
            null -> downloads.enqueue(romId, installRequestId, known = fetched).outcome() // the card can't be read: queueing says why
            is FolderDecision.Ask -> when (whenUnsure) {
                WhenUnsure.ASK -> Outcome.AskFolder(platform.ask(romId, d))
                WhenUnsure.WAIT_IN_QUEUE -> when (val r = downloads.enqueue(romId, installRequestId, waitForFolder = true, known = fetched)) {
                    is EnqueueResult.Queued -> Outcome.WaitingForFolder(r.jobId, platform.ask(romId, d))
                    else -> r.outcome()
                }
            }
            is FolderDecision.Use -> {
                settle(platform, d)
                // Found on the card rather than the default: say where, the one time it's decided.
                val found = d.why == FolderDecision.Why.HAS_GAMES || d.why == FolderDecision.Why.ONLY_FOLDER
                val note = if (found) "${platform.name} games go in ${d.folder}/" else null
                downloads.enqueue(romId, installRequestId, known = fetched).outcome(note)
            }
        }
    }

    /**
     * A in the queue on game [jobId], waiting for its folder: the question to answer. If the folder was
     * settled meanwhile, the game is released instead ([Outcome.Queued]). Null once it no longer waits.
     */
    suspend fun askFolder(jobId: Long): Outcome? {
        val job = downloads.waitingForFolder().firstOrNull { it.id == jobId } ?: return null
        val platform = try {
            platformOf(job.romId, job.platformId).first
        } catch (e: NotPairedException) {
            return downloads.whyNot(e).outcome()
        } catch (e: RommException) {
            return downloads.whyNot(e).outcome()
        }
        return when (val d = folders.decide(platform.slug, platform.fsSlug)) {
            null -> Outcome.Failed("Loom can't look in your ROMs folder now; check the SD card is in")
            is FolderDecision.Ask -> Outcome.AskFolder(platform.ask(job.romId, d))
            is FolderDecision.Use -> {
                settle(platform, d)
                downloads.releaseFolderWaits(listOf(platform.id), d.folder)
                Outcome.Queued(job.id, "${platform.name} games go in ${d.folder}/")
            }
        }
    }

    /**
     * The answer to the folder question for [fsSlug] (and platform [platformId]): remembered, every game
     * of it waiting in the queue goes there, then [thenQueue] is queued (null when none is, as when
     * the question was asked in the queue).
     */
    suspend fun folderChosen(fsSlug: String, folder: String, thenQueue: Int?, platformId: Int? = null): Outcome? {
        folders.choose(fsSlug, folder)
        val platforms = catalog.platforms().filter { it.fsSlug == fsSlug }.map { it.id } + listOfNotNull(platformId)
        downloads.releaseFolderWaits(platforms, folder)
        return thenQueue?.let { downloads.enqueue(it).outcome() }
    }

    /**
     * Releases the games waiting for their folder whose platform has one now: chosen meanwhile (in
     * Settings › Platform folders, say), or made clear by the card. A platform the library doesn't
     * have yet waits for its sync.
     */
    suspend fun releaseDecided() {
        for (platformId in downloads.waitingForFolder().map { it.platformId }.distinct()) {
            val platform = catalog.platform(platformId)?.let(::Platform) ?: continue
            val d = folders.decide(platform.slug, platform.fsSlug) as? FolderDecision.Use ?: continue
            settle(platform, d)
            downloads.releaseFolderWaits(listOf(platformId), d.folder)
        }
    }

    /**
     * [romId]'s platform as the library has it, else as RomM says, with the game RomM gave: one added
     * there since the last library sync, which this starts. Throws as [DownloadRepository.romFromRomm].
     */
    private suspend fun platformOf(romId: Int, platformId: Int? = null): Pair<Platform, RomDto?> {
        (platformId ?: catalog.rom(romId)?.platformId)?.let { catalog.platform(it) }?.let { return Platform(it) to null }
        val rom = downloads.romFromRomm(romId)
        syncScheduler.enqueue()
        return Platform(rom.platformId, rom.platformSlug, rom.platformSlug, rom.platformFsSlug) to rom
    }

    /** A folder Loom found on the card is remembered as if chosen, so the platform's games keep one home. */
    private suspend fun settle(platform: Platform, d: FolderDecision.Use) {
        if (d.why != FolderDecision.Why.CHOSEN) folders.choose(platform.fsSlug, d.folder, detected = true)
    }

    private fun EnqueueResult.outcome(note: String? = null): Outcome = when (this) {
        is EnqueueResult.Queued -> Outcome.Queued(jobId, note)
        EnqueueResult.AlreadyQueued -> Outcome.AlreadyQueued
        EnqueueResult.AlreadyInstalled -> Outcome.AlreadyInstalled
        is EnqueueResult.Failed -> Outcome.Failed(message, transient)
    }
}
