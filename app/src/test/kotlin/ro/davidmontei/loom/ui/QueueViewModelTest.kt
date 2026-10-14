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
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueKicker
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.saves.ConflictChoice
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.saves.SaveSyncRequest
import ro.davidmontei.loom.data.saves.SaveSyncTestBase
import ro.davidmontei.loom.net.ActiveServer
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
        vm = QueueViewModel(downloads, SaveRepository(engine(), db.saveDao(), scope))
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

    @Test fun anAnswerIsCarriedOutOnceAndSaysHowItWent() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.addSave(7, archiveOf(bytes(2)))
        engine().sync(SaveSyncRequest.PUSH)
        withTimeout(5_000) { vm.state.first { it.conflicts.isNotEmpty() } }
        val message = async { vm.messages.first() }

        vm.resolveConflict(7, "Pokemon X", ConflictChoice.KEEP_THOR)
        vm.resolveConflict(7, "Pokemon X", ConflictChoice.KEEP_THOR) // pressed again while the first is running

        assertThat(withTimeout(10_000) { message.await() })
            .isEqualTo("RomM now has the Thor's save of Pokemon X; its old one stays in RomM's history")
        val after = withTimeout(5_000) { vm.state.first { it.conflicts.isEmpty() && it.recentSaves.isNotEmpty() } }
        assertThat(after.recentSaves.map { it.title }).containsExactly("Pokemon X")
        assertThat(romm.uploads()).hasSize(1)
    }

    @Test fun aNewerSaveOnRommIsListedAndRestored() = runBlocking<Unit> {
        install(omegaRuby)
        romm.addSave(8, archiveOf(bytes(3)))
        engine().sync(SaveSyncRequest.PUSH)

        val listed = withTimeout(5_000) { vm.state.first { it.restorable.isNotEmpty() } }.restorable
        assertThat(listed).containsExactly(RestorableSave(8, "Pokemon Omega Ruby", missing = false, emulator = "azahar"))

        vm.restoreSave(8, "Pokemon Omega Ruby")
        withTimeout(10_000) { vm.state.first { it.restorable.isEmpty() } }
        assertThat(localMain(omegaRuby)).isEqualTo(bytes(3))
    }
}
