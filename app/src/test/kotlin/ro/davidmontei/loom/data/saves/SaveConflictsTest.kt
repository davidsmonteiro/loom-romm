package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.storage.saves.SaveHasher
import ro.davidmontei.loom.storage.saves.SyncDecision

/** The user's answers to a save conflict: Keep Thor, Keep server, Keep both, Skip. */
@RunWith(AndroidJUnit4::class)
class SaveConflictsTest : SaveSyncTestBase() {

    private val saves get() = db.saveDao()

    /** A Thor that never synced Pokémon X, and a different save from another device on RomM. */
    private suspend fun firstSyncConflict(): Pair<String, FakeRomm.Save> {
        install(pokemonX)
        val local = writeSave(pokemonX, bytes(1))
        val server = romm.addSave(7, archiveOf(bytes(2)))
        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).containsExactly(7)
        assertThat(saves.conflict(7)!!.reason).isEqualTo(SyncDecision.FIRST_SYNC_DIFFERS.name)
        assertThat(romm.uploads()).isEmpty()
        return local to server
    }

    @Test fun keepThorMakesTheThorsSaveTheNewest() = runBlocking<Unit> {
        val (local, server) = firstSyncConflict()

        assertThat(engine().resolveConflict(7, ConflictChoice.KEEP_THOR)).isEqualTo(ResolveResult.Done)

        val upload = romm.uploads().single().url
        assertThat(upload.queryParameter("overwrite")).isEqualTo("true")
        assertThat(upload.queryParameter("slot")).isEqualTo("autosave")
        assertThat(romm.head(7)!!.hash).isEqualTo(local)
        assertThat(romm.saves.map { it.id }).contains(server.id) // the server's copy stays in its history
        assertThat(saves.conflict(7)).isNull()
        assertThat(saves.baseline(7, "autosave")!!.serverSaveId).isEqualTo(romm.head(7)!!.id)
        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).isEmpty()
        assertThat(saves.unit(7)!!.state).isEqualTo(SaveSyncState.IN_SYNC)
    }

    @Test fun keepServerBacksUpTheThorsSaveThenTakesTheServers() = runBlocking<Unit> {
        val (local, server) = firstSyncConflict()

        assertThat(engine().resolveConflict(7, ConflictChoice.KEEP_SERVER)).isEqualTo(ResolveResult.Done)

        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))
        assertThat(backups(pokemonX).map { SaveHasher.hashFile(it.toPath()) }).containsExactly(local)
        assertThat(romm.paths()).contains("POST /api/saves/${server.id}/downloaded")
        assertThat(romm.uploads()).isEmpty()
        assertThat(saves.conflict(7)).isNull()
        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).isEmpty()
        assertThat(romm.uploads()).isEmpty()
    }

    @Test fun keepBothParksTheThorsSaveOutsideTheSlotThenTakesTheServers() = runBlocking<Unit> {
        val (local, _) = firstSyncConflict()
        // An archival save of that name already exists: RomM would overwrite it.
        romm.addSave(7, bytes(5), slot = null, fileName = "Pokemon X (Thor 2026-10-14 12-00-00 UTC).zip")

        assertThat(engine().resolveConflict(7, ConflictChoice.KEEP_BOTH)).isEqualTo(ResolveResult.Done)

        val upload = romm.uploads().single()
        assertThat(upload.url.queryParameterNames).doesNotContain("slot")
        val archived = romm.saves.single { it.slot == null && it.hash == local }
        assertThat(archived.fileName).isEqualTo("Pokemon X (Thor 2026-10-14 12-00-00 UTC) 2.zip")
        assertThat(archived.hash).isEqualTo(local)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))
        assertThat(backups(pokemonX)).hasSize(1)
        assertThat(saves.conflict(7)).isNull()
    }

    /** The user chose on what the conflict showed; if either side moved on, nothing happens. */
    @Test fun aChoiceOnSidesThatChangedSinceIsRefused() = runBlocking<Unit> {
        firstSyncConflict()
        writeSave(pokemonX, bytes(3)) // played on

        val keepServer = engine().resolveConflict(7, ConflictChoice.KEEP_SERVER)

        assertThat(keepServer).isInstanceOf(ResolveResult.Failed::class.java)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(3))
        assertThat(romm.requests.none { it.url.encodedPath.endsWith("/content") }).isTrue()

        writeSave(pokemonX, bytes(1)) // back to what the conflict showed
        romm.addSave(7, archiveOf(bytes(4))) // but the server moved on
        assertThat(engine().resolveConflict(7, ConflictChoice.KEEP_THOR)).isInstanceOf(ResolveResult.Failed::class.java)
        assertThat(romm.uploads()).isEmpty()
        assertThat(saves.conflict(7)).isNotNull()
    }

    @Test fun keepThorWaitsWhileTheEmulatorIsStillWriting() = runBlocking<Unit> {
        firstSyncConflict()
        settle(pokemonX, minutesAgo = 0)

        val result = engine().resolveConflict(7, ConflictChoice.KEEP_THOR)

        assertThat(result).isInstanceOf(ResolveResult.Failed::class.java)
        assertThat((result as ResolveResult.Failed).reason).contains("still writing")
        assertThat(romm.uploads()).isEmpty()
    }

    @Test fun skipKeepsBothSidesAndStopsAsking() = runBlocking<Unit> {
        firstSyncConflict()

        assertThat(engine().resolveConflict(7, ConflictChoice.SKIP)).isEqualTo(ResolveResult.Done)

        assertThat(saves.conflict(7)!!.skipped).isTrue()
        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).isEmpty()
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))

        // Either side changing raises it again.
        writeSave(pokemonX, bytes(3), minutesAgo = 5)
        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).containsExactly(7)
        assertThat(saves.conflict(7)!!.skipped).isFalse()
    }
}
