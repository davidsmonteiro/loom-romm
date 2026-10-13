package ro.davidmontei.loom.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.storage.mapping.ArchivePolicy
import ro.davidmontei.loom.storage.mapping.InvalidMappingException
import ro.davidmontei.loom.storage.mapping.MappingSource
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import java.io.File

class MappingRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy {
        MappingRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "m.preferences_pb") })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun overridesApplyAndClear() = runBlocking<Unit> {
        repo.setOverride(PlatformMappingEntry(PlatformMatch(slug = "snes"), folder = "sfc"))
        assertThat(repo.currentMapper().resolve("snes", "snes").folder).isEqualTo("sfc")
        repo.clearOverride(PlatformMatch(slug = "snes"))
        assertThat(repo.currentMapper().resolve("snes", "snes").source).isEqualTo(MappingSource.DEFAULT)
    }

    @Test fun exportThenImportElsewhereKeepsOnlyDifferences() = runBlocking<Unit> {
        repo.setOverride(PlatformMappingEntry(PlatformMatch(slug = "gba"), archive = ArchivePolicy.EXTRACT))
        val exported = repo.exportJson()
        repo.resetAll()
        assertThat(repo.currentMapper().resolve("gba", "gba").archive).isEqualTo(ArchivePolicy.KEEP)

        repo.importJson(exported)
        assertThat(repo.currentMapper().resolve("gba", "gba").archive).isEqualTo(ArchivePolicy.EXTRACT)
        assertThat(repo.overrides.first().map { it.match.slug }).containsExactly("gba")
    }

    @Test fun choosingAFolderKeepsTheOtherSettings() = runBlocking<Unit> {
        repo.setOverride(PlatformMappingEntry(PlatformMatch(slug = "arcade"), archive = ArchivePolicy.EXTRACT))
        repo.chooseFolder("arcade", "arcade", "arcade")
        val arcade = repo.currentMapper().resolve("arcade", "arcade")
        assertThat(arcade.folder).isEqualTo("arcade")
        assertThat(arcade.folderChosen).isTrue()
        assertThat(arcade.archive).isEqualTo(ArchivePolicy.EXTRACT)
    }

    @Test fun aFolderIsChosenForThePlatformAsTheMappingSplitsIt() = runBlocking<Unit> {
        repo.chooseFolder("arcade", "fbneo", "fba") // RomM's arcade platform served from the fbneo folder
        assertThat(repo.currentMapper().resolve("arcade", "fbneo").folder).isEqualTo("fba")
        assertThat(repo.currentMapper().resolve("arcade", "arcade").folderChosen).isFalse() // other arcade folders untouched
    }

    @Test fun badImportLeavesOverridesAlone() = runBlocking<Unit> {
        repo.setOverride(PlatformMappingEntry(PlatformMatch(slug = "snes"), folder = "sfc"))
        assertThrows(InvalidMappingException::class.java) { runBlocking { repo.importJson("{nope") } }
        assertThat(repo.currentMapper().resolve("snes", "snes").folder).isEqualTo("sfc")
    }
}
