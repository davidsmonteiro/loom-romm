package ro.davidmontei.loom.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowEnvironment
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.saves.ConflictChoice
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.saves.SaveSyncRequest
import ro.davidmontei.loom.data.saves.SaveSyncTestBase
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.storage.saves.SwitchTitleId
import ro.davidmontei.loom.ui.queue.QueueViewModel
import ro.davidmontei.loom.ui.queue.RestorableSave
import java.io.File

/** The queue's saves: conflicts as RomM and the Thor hold them, and the user's answers carried out. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class QueueViewModelTest : SaveSyncTestBase() {
    private lateinit var vm: QueueViewModel

    @Before fun setUpQueue() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val server = ActiveServer(settings, credentials, OkHttpClient(), scope)
        val mapping = MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "m.preferences_pb") })
        val downloads = DownloadRepository(db.downloadDao(), db.catalogDao(), server, mapping, settings, StorageVolumes(context),
            QueueKicker(WorkManager.getInstance(context)))
        vm = QueueViewModel(downloads, SaveRepository(engine(), db.saveDao(), eden(), scope))
    }

    @After fun tearDownQueue() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    /** Loom doesn't keep the server save's size or where it came from: the queue asks RomM. */
    @Test fun aConflictShowsBothSidesWithWhatRommSaysOfItsSave() = runBlocking<Unit> {
        romm.devices += romm.device("dev-deck", "argosy-deck", name = "Steam Deck")
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        val server = romm.addSave(7, archiveOf(bytes(2)), device = "dev-deck")
        engine().sync(SaveSyncRequest.PUSH)

        val item = withTimeout(5_000) { vm.state.first { s -> s.conflicts.any { it.serverDevice != null } } }.conflicts.single()

        assertThat(item.title).isEqualTo("Pokemon X")
        assertThat(item.reason).isEqualTo("The Thor and RomM each have a different save, and they were never synced")
        assertThat(item.thorBytes).isEqualTo(db.saveDao().conflict(7)!!.localBytes)
        assertThat(item.thorHasSave).isTrue()
        assertThat(item.serverUpdatedMs).isEqualTo(server.updatedAt.toEpochMilli())
        assertThat(item.serverBytes).isEqualTo(server.bytes.size.toLong())
        assertThat(item.serverDevice).isEqualTo("Steam Deck")
        assertThat(item.emulator).isEqualTo("azahar")
    }

    /** RomM is asked once per server save, not each time the conflicts change (a Skip, another game's conflict). */
    @Test fun rommIsAskedAboutAServerSaveOnce() = runBlocking<Unit> {
        romm.devices += romm.device("dev-deck", "argosy-deck", name = "Steam Deck")
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2)), device = "dev-deck")
        engine().sync(SaveSyncRequest.PUSH)
        withTimeout(5_000) { vm.state.first { s -> s.conflicts.any { it.serverDevice != null } } }

        db.saveDao().upsertConflict(db.saveDao().conflict(7)!!.copy(skipped = true))
        withTimeout(5_000) { vm.state.first { s -> s.conflicts.any { it.skipped } } }
        delay(500) // a second question, had it been asked, would have reached RomM by now

        assertThat(romm.paths().count { it == "GET /api/devices/dev-deck" }).isEqualTo(1)
    }

    @Test fun anAnswerIsCarriedOutOnceAndSaysHowItWent() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2)))
        engine().sync(SaveSyncRequest.PUSH)
        withTimeout(5_000) { vm.state.first { it.conflicts.isNotEmpty() } }
        val message = async { vm.messages.first() }

        vm.resolveConflict(7, "Pokemon X", ConflictChoice.KEEP_THOR)

        assertThat(withTimeout(10_000) { message.await() })
            .isEqualTo("RomM now has the Thor's save of Pokemon X; its old one stays in RomM's history")
        val after = withTimeout(5_000) { vm.state.first { it.conflicts.isEmpty() && it.recentSaves.isNotEmpty() } }
        assertThat(after.recentSaves.map { it.title }).containsExactly("Pokemon X")
        assertThat(romm.uploads()).hasSize(1)
    }

    /** An answer that changed nothing (the Thor's save moved on since) leaves the conflict to answer again. */
    @Test fun anAnswerThatChangedNothingCanBeGivenAgain() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2)))
        engine().sync(SaveSyncRequest.PUSH)
        writeSave(pokemonX, bytes(3), minutesAgo = 5)
        withTimeout(5_000) { vm.state.first { it.conflicts.isNotEmpty() } }
        val message = async { vm.messages.first() }

        vm.resolveConflict(7, "Pokemon X", ConflictChoice.KEEP_SERVER)

        assertThat(withTimeout(10_000) { message.await() }).startsWith("Nothing changed for Pokemon X: ")
        val after = withTimeout(5_000) { vm.state.first { s -> s.conflicts.none { it.busy } } }
        assertThat(after.conflicts.map { it.romId }).containsExactly(7)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(3))
    }

    /**
     * A wrong pick of a Switch game's save folder ends in a conflict: the queue asks for the folder
     * again, as the game's details do, and the right pick settles it.
     */
    @Test fun aConflictsSaveFolderCanBeChosenAgainFromTheQueue() = runBlocking<Unit> {
        installSwitch(wonder) // no prod.keys: the owner picks its folder
        writeEdenSave(wonder, bytes(1))
        writeEdenSave(arceus, bytes(2), minutesAgo = 5) // another game's folder, changed later
        romm.addSave(wonder.romId, edenArchiveOf(wonder, bytes(1)), emulator = "eden")
        eden().chooseFolder(wonder.romId, SwitchTitleId.parse(arceus.titleId)!!) // the wrong one
        engine().sync(SaveSyncRequest.PUSH)
        val conflict = withTimeout(5_000) { vm.state.first { s -> s.conflicts.any { it.folderChosen } } }.conflicts.single()
        assertThat(conflict.romId).isEqualTo(wonder.romId)

        vm.askSaveFolder(wonder.romId, wonder.title, conflict.emulator)
        val question = withTimeout(5_000) { vm.state.first { it.saveFolderQuestion != null } }.saveFolderQuestion!!
        assertThat(question.options.map { it.id }).containsExactly(arceus.titleId, wonder.titleId).inOrder()
        assertThat(question.current).isEqualTo(arceus.titleId)

        val message = async { vm.messages.first() }
        vm.chooseSaveFolder(wonder.titleId)

        assertThat(withTimeout(10_000) { message.await() }).isEqualTo("Super Mario Bros Wonder's save is Eden's ${wonder.titleId} folder")
        val after = withTimeout(5_000) { vm.state.first { it.conflicts.isEmpty() } }
        assertThat(after.saveFolderQuestion).isNull()
        assertThat(db.saveDao().switchTitle(wonder.romId)!!.titleId).isEqualTo(wonder.titleId)
        assertThat(db.saveDao().unit(wonder.romId)!!.state).isEqualTo(SaveSyncState.IN_SYNC) // the same save as RomM's
        assertThat(romm.uploads()).isEmpty()
    }

    @Test fun aNewerSaveOnRommIsListedAndRestored() = runBlocking<Unit> {
        install(omegaRuby)
        romm.addSave(8, archiveOf(bytes(3)))
        engine().sync(SaveSyncRequest.PUSH)

        val listed = withTimeout(5_000) { vm.state.first { it.restorable.isNotEmpty() } }.restorable
        assertThat(listed).containsExactly(RestorableSave(8, "Pokemon Omega Ruby", missing = false, emulator = "azahar"))

        val messages = mutableListOf<String>()
        val collecting = launch(Dispatchers.IO) { vm.messages.collect { messages += it } }
        vm.restoreSave(8, "Pokemon Omega Ruby")
        vm.restoreSave(8, "Pokemon Omega Ruby") // pressed again while the first is running
        withTimeout(10_000) { vm.state.first { it.restorable.isEmpty() } }
        withTimeout(10_000) { while (messages.isEmpty()) delay(20) }
        delay(1_000) // a second request, had it gone through, would have answered by now
        collecting.cancel()

        assertThat(localMain(omegaRuby)).isEqualTo(bytes(3))
        assertThat(messages).containsExactly("Restored Pokemon Omega Ruby's save from RomM")
        assertThat(romm.requests.count { it.url.encodedPath.endsWith("/content") }).isEqualTo(1)
    }
}
