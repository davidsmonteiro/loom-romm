package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.settings.ServerConfig
import java.time.Duration

/** What the screens ask of one game's save, and the answer the user reads. */
@RunWith(AndroidJUnit4::class)
class SaveRepositoryTest : SaveSyncTestBase() {
    private val screen = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = SaveRepository(engine(), db.saveDao(), scope)

    @After fun tearDownScreen() = screen.cancel()

    @Test fun uploadSendsOnlyThatGamesSave() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        writeSave(omegaRuby, bytes(2))

        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("Uploaded Pokemon X's save to RomM")

        assertThat(romm.uploads().map { it.url.queryParameter("rom_id") }).containsExactly("7")
    }

    /** Replacing a save needs the emulator closed, which only the restore question asks: Upload never downloads. */
    @Test fun uploadNeverReplacesTheThorsSave() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2))) // played on elsewhere

        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("RomM has a newer save of Pokemon X. Close Azahar, then restore it.")

        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
        assertThat(romm.requests.none { it.url.encodedPath.endsWith("/content") }).isTrue()
    }

    @Test fun aSaveStillBeingWrittenWaitsForAQuietMinute() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1), minutesAgo = 0)

        assertThat(repository.upload(7, "Pokemon X")).isEqualTo(
            "Azahar wrote Pokemon X's save less than a minute ago. Loom uploads a save once it has been left alone for a minute; try again then.",
        )
        assertThat(romm.uploads()).isEmpty()
    }

    @Test fun uploadSaysWhenThereIsNothingToSend() = runBlocking<Unit> {
        install(pokemonX)
        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("Pokemon X has no save yet, on the Thor or on RomM")
        writeSave(pokemonX, bytes(1))
        repository.upload(7, "Pokemon X")
        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("Pokemon X's save is already in sync with RomM")
        assertThat(romm.uploads()).hasSize(1)
    }

    @Test fun aSaveRommAlreadyHasIsSaidToBeInSync() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(1))) // the same bytes, uploaded from elsewhere
        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("RomM already had this save of Pokemon X; it's in sync")
        assertThat(romm.uploads()).isEmpty()
    }

    /** A game Loom can't place, and an upload RomM fails, each say why. */
    @Test fun uploadSaysWhyItCouldnt() = runBlocking<Unit> {
        install(pokemonX, cartridgeId = null, file = "Pokemon X.cia") // no title ID to find its save by
        assertThat(repository.upload(7, "Pokemon X")).startsWith("Pokemon X's save isn't synced: ")

        install(omegaRuby)
        writeSave(omegaRuby, bytes(1))
        romm.uploadAnswer = { json(500, "Database is locked") }
        assertThat(repository.upload(8, "Pokemon Omega Ruby")).isEqualTo("Couldn't sync Pokemon Omega Ruby's save: HTTP 500: Database is locked")
    }

    /** RomM turning Loom away is said with what to do, or in RomM's words. */
    @Test fun aServerThatRefusesLoomIsSaidPlainly() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.uploadAnswer = { json(401, "Invalid token") }
        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("RomM rejected Loom's token. Pair again in Settings.")
        romm.uploadAnswer = { json(403, "Forbidden: missing scope assets.write") }
        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("Forbidden: missing scope assets.write")
    }

    private fun json(code: Int, detail: String) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body("""{"detail":"$detail"}""").build()

    @Test fun aConflictIsSettledInTheQueueEvenOnceSkipped() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2)))
        val choose = "Pokemon X's save changed on the Thor and on RomM: choose which to keep in the queue"

        assertThat(repository.upload(7, "Pokemon X")).isEqualTo(choose)
        assertThat(repository.resolve(7, "Pokemon X", ConflictChoice.SKIP))
            .isEqualTo("Left both saves of Pokemon X as they are. Loom asks again if either one changes.")
        assertThat(repository.upload(7, "Pokemon X")).isEqualTo(choose)
        assertThat(romm.uploads()).isEmpty()
    }

    @Test fun restoringAnswersWhatHappened() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)))

        assertThat(repository.restore(7, "Pokemon X")).isEqualTo("Restored Pokemon X's save from RomM")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))

        writeSave(pokemonX, bytes(3)) // played on
        romm.addSave(7, archiveOf(bytes(4)))
        assertThat(repository.restore(7, "Pokemon X"))
            .isEqualTo("Didn't restore Pokemon X's save: The save on the Thor changed since the last sync: sync this game to choose which to keep")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(3))
    }

    @Test fun conflictAnswersSayWhatEachSideNowHolds() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2)))
        engine().sync(SaveSyncRequest.PUSH)
        writeSave(pokemonX, bytes(3), minutesAgo = 5) // changed since the conflict was found

        assertThat(repository.resolve(7, "Pokemon X", ConflictChoice.KEEP_SERVER))
            .isEqualTo("Nothing changed for Pokemon X: The save on the Thor changed since; sync again to see where it stands")

        engine().sync(SaveSyncRequest.PUSH) // the conflict as it is now
        assertThat(repository.resolve(7, "Pokemon X", ConflictChoice.KEEP_SERVER))
            .isEqualTo("The Thor now has RomM's save of Pokemon X; the one it replaced is backed up on the card")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))
    }

    @Test fun keepingBothSaysWhereEachSaveWent() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2)))
        engine().sync(SaveSyncRequest.PUSH)

        assertThat(repository.resolve(7, "Pokemon X", ConflictChoice.KEEP_BOTH))
            .isEqualTo("RomM keeps the Thor's save of Pokemon X as a separate copy, and the Thor now has RomM's")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))
    }

    @Test fun keepingRommsSaveWhenTheThorHasNoneMentionsNoBackup() = runBlocking<Unit> {
        install(pokemonX)
        romm.addSave(7, archiveOf(bytes(2)), emulator = "citra") // another emulator's: never taken without asking
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(db.saveDao().conflict(7)!!.localHash).isNull()

        assertThat(repository.resolve(7, "Pokemon X", ConflictChoice.KEEP_SERVER)).isEqualTo("The Thor now has RomM's save of Pokemon X")
    }

    @Test fun anUnreachableServerIsSaidPlainly() = runBlocking<Unit> {
        settings.setServer(ServerConfig("http://127.0.0.1:1/".toHttpUrl()), "5.3.1")
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        assertThat(repository.upload(7, "Pokemon X")).isEqualTo("Can't reach the RomM server")
        assertThat(repository.restore(8, "Pokemon Omega Ruby")).isEqualTo("Can't reach the RomM server")
    }

    /** Leaving the screen (its scope cancelled) mid-request mustn't stop the request half way. */
    @Test fun aRequestCarriesOnWhenItsScreenGoes() = runBlocking<Unit> {
        install(pokemonX)
        val local = writeSave(pokemonX, bytes(1))
        val repository = repository
        lateinit var caller: Job
        onPause = { caller.cancel() } // between the two hashes, just before the upload
        caller = screen.launch(start = CoroutineStart.LAZY) { repository.upload(7, "Pokemon X") }
        caller.start()
        caller.join()

        withTimeout(10_000) { while (romm.uploads().isEmpty() || db.saveDao().baseline(7, "autosave") == null) delay(20) }
        assertThat(romm.head(7)!!.hash).isEqualTo(local)
    }

    @Test fun theServerSideOfAConflictNamesItsSizeAndDevice() = runBlocking<Unit> {
        romm.devices += romm.device("dev-deck", "argosy-deck", name = "Steam Deck")
        val fromDeck = romm.addSave(7, archiveOf(bytes(2)), device = "dev-deck")
        val fromWeb = romm.addSave(7, archiveOf(bytes(3)), device = null)
        val fromGone = romm.addSave(7, archiveOf(bytes(4)), device = "dev-gone")

        assertThat(repository.serverSaveInfo(7, fromDeck.id)).isEqualTo(ServerSaveInfo(fromDeck.bytes.size.toLong(), "Steam Deck"))
        assertThat(repository.serverSaveInfo(7, fromWeb.id)).isEqualTo(ServerSaveInfo(fromWeb.bytes.size.toLong(), null))
        assertThat(repository.serverSaveInfo(7, fromGone.id)).isEqualTo(ServerSaveInfo(fromGone.bytes.size.toLong(), null))
        assertThat(repository.serverSaveInfo(7, 9_999)).isNull()
    }

    /**
     * An offloaded game's conflict, or its save to restore, can't be answered (the game isn't
     * installed): the queue leaves it out, keeping the record, and lists it again once the game is
     * back. Its save still sits in Azahar's folder.
     */
    @Test fun anOffloadedGamesSavesLeaveTheQueueUntilItsBack() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2))) // a conflict
        romm.addSave(8, archiveOf(bytes(3))) // RomM's can come back
        engine().sync(SaveSyncRequest.PUSH)
        val repository = repository

        db.catalogDao().deleteInstall(7)
        db.catalogDao().deleteInstall(8)

        assertThat(repository.observeConflicts().first()).isEmpty()
        assertThat(repository.observeRestorable().first()).isEmpty()
        assertThat(db.saveDao().conflict(7)).isNotNull()

        install(pokemonX)
        install(omegaRuby)
        assertThat(repository.observeConflicts().first().map { it.conflict.romId }).containsExactly(7)
        assertThat(repository.observeRestorable().first().map { it.romId }).containsExactly(8)
    }

    @Test fun theQueueListsConflictsRestorableSavesAndRecentSyncsByTitle() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2))) // a different save, never synced: a conflict
        romm.addSave(8, archiveOf(bytes(3))) // Omega Ruby has none on the Thor: RomM's can come back
        engine().sync(SaveSyncRequest.PUSH)
        val repository = repository

        assertThat(repository.observeConflicts().first().map { it.title to it.conflict.romId }).containsExactly("Pokemon X" to 7)
        assertThat(repository.observeRestorable().first().map { it.title to it.state })
            .containsExactly("Pokemon Omega Ruby" to SaveSyncState.SERVER_NEWER)
        assertThat(repository.observeRecentSyncs().first()).isEmpty()

        repository.restore(8, "Pokemon Omega Ruby")
        clock = clock.plus(Duration.ofMinutes(5))
        repository.resolve(7, "Pokemon X", ConflictChoice.KEEP_THOR)

        assertThat(repository.observeRestorable().first()).isEmpty()
        assertThat(repository.observeConflicts().first()).isEmpty()
        assertThat(repository.observeRecentSyncs().first().map { it.title }).containsExactly("Pokemon X", "Pokemon Omega Ruby").inOrder()
    }
}
