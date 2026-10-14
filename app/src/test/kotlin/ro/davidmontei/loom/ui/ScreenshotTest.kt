package ro.davidmontei.loom.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ro.davidmontei.loom.ui.library.LibraryContent

/**
 * Renders each screen at the Thor's top-screen size to app/build/screenshots/ for review.
 * Not assertions about pixels: a quick way to see layout, type and colour without a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_TOP_SCREEN)
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val library = LibraryScreenTest()

    private fun settle() { rule.mainClock.advanceTimeBy(300); rule.waitForIdle() }

    @Test fun libraryGrid() {
        rule.setContent { LoomTestTheme { LibraryContent(library.state, NoopLibraryActions) } }
        settle()
        rule.saveScreenshot("library-grid")
    }

    @Test fun libraryList() {
        rule.setContent { LoomTestTheme { LibraryContent(library.state.copy(gridMode = false), NoopLibraryActions) } }
        settle()
        rule.saveScreenshot("library-list")
    }

    @Test fun libraryDetailsDialog() {
        rule.setContent { LoomTestTheme { LibraryContent(library.state, NoopLibraryActions) } }
        settle()
        rule.onNodeWithTag("game-5").performClick()
        settle()
        rule.saveScreenshot("library-details-failed")
    }

    @Test fun queue() {
        rule.setContent { LoomTestTheme { ro.davidmontei.loom.ui.queue.QueueContent(QueueScreenTest.sample, NoopQueueActions) } }
        settle()
        rule.saveScreenshot("queue")
    }

    @Test fun queueWithSaves() {
        rule.setContent { LoomTestTheme { ro.davidmontei.loom.ui.queue.QueueContent(QueueScreenTest.withSaves, NoopQueueActions) } }
        settle()
        rule.saveScreenshot("queue-saves")
    }

    @Test fun settingsMapping() {
        rule.setContent { LoomTestTheme { ro.davidmontei.loom.ui.settings.SettingsContent(SettingsScreenTest.sample, SettingsScreenTest.noop) } }
        settle()
        rule.onNodeWithTag("section-MAPPING").performClick()
        settle()
        rule.saveScreenshot("settings-mapping")
        rule.onNodeWithText("PlayStation").performClick()
        settle()
        rule.saveScreenshot("settings-mapping-editor")
    }

    @Test fun settingsDownloadsAndSaves() {
        val state = SettingsScreenTest.sample.copy(
            settings = SettingsScreenTest.sample.settings!!.copy(
                lastSaveSync = ro.davidmontei.loom.data.settings.SaveSyncOutcome(System.currentTimeMillis() - 12 * 60_000, "1 uploaded · 1 newer on RomM", false),
            ),
            storage = SettingsScreenTest.sample.storage.copy(azaharFound = "/storage/6365-3230/ROMs/n3ds"),
        )
        rule.setContent { LoomTestTheme { ro.davidmontei.loom.ui.settings.SettingsContent(state, SettingsScreenTest.noop) } }
        settle()
        rule.onNodeWithTag("section-DOWNLOADS").performClick()
        settle()
        rule.saveScreenshot("settings-downloads")
        rule.onNodeWithTag("section-SAVES").performClick()
        settle()
        rule.saveScreenshot("settings-saves")
    }

    @Test fun settingsServer() {
        rule.setContent { LoomTestTheme { ro.davidmontei.loom.ui.settings.SettingsContent(SettingsScreenTest.sample, SettingsScreenTest.noop) } }
        settle()
        rule.saveScreenshot("settings-server")
    }

    @Test fun libraryOffloadConfirm() {
        rule.setContent { LoomTestTheme { LibraryContent(library.state, NoopLibraryActions) } }
        settle()
        rule.onNodeWithTag("game-1").performClick()
        settle()
        rule.onNodeWithText("Remove from device").performClick()
        settle()
        rule.saveScreenshot("library-offload-confirm")
    }
}

object NoopLibraryActions : ro.davidmontei.loom.ui.library.LibraryActions {
    override fun selectPlatform(id: Int) = Unit
    override fun cyclePlatform(step: Int) = Unit
    override fun setFilter(filter: ro.davidmontei.loom.ui.library.LibraryFilter) = Unit
    override fun setQuery(query: String) = Unit
    override fun toggleGrid() = Unit
    override fun sync() = Unit
    override fun download(game: ro.davidmontei.loom.ui.library.GameItem) = Unit
    override fun offload(game: ro.davidmontei.loom.ui.library.GameItem) = Unit
    override fun retry(game: ro.davidmontei.loom.ui.library.GameItem) = Unit
    override fun cancel(game: ro.davidmontei.loom.ui.library.GameItem) = Unit
    override fun uploadSave(game: ro.davidmontei.loom.ui.library.GameItem) = Unit
    override fun restoreSave(game: ro.davidmontei.loom.ui.library.GameItem) = Unit
    override fun openQueue(focusJobId: Long?, focusConflictRomId: Int?) = Unit
    override fun openSettings() = Unit
    override fun repair() = Unit
    override fun chooseFolder(folder: String) = Unit
    override fun showAllFolders() = Unit
    override fun showSuggestedFolders() = Unit
    override fun dismissFolderQuestion() = Unit
}

object NoopQueueActions : ro.davidmontei.loom.ui.queue.QueueActions {
    override fun pause(jobId: Long) = Unit
    override fun resume(jobId: Long) = Unit
    override fun retry(jobId: Long) = Unit
    override fun cancel(jobId: Long) = Unit
    override fun resolveConflict(romId: Int, title: String, choice: ro.davidmontei.loom.data.saves.ConflictChoice) = Unit
    override fun restoreSave(romId: Int, title: String) = Unit
}
