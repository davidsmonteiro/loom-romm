package ro.davidmontei.loom.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.data.settings.WriteThroughDataStore

/** How the app's graph is put together, where that decides whether it works. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class) // not LoomApp, whose own graph would open the same settings file
class AppModuleTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    /**
     * SettingsStore and MappingRepository both get the settings file from here: read once and kept in
     * memory, so a long-lived reader (the catalog refresh schedule, a screen's state) can't miss a change.
     */
    @Test fun theSettingsFileIsKeptInMemory() {
        val prefs = AppModule.settingsPrefs(ApplicationProvider.getApplicationContext(), scope)
        assertThat(prefs).isInstanceOf(WriteThroughDataStore::class.java)
    }
}
