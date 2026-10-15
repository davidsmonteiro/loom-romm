package ro.davidmontei.loom.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.RommServiceFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** What each heartbeat tells Loom about the server: its version, and whether it can send games to devices. */
class ServerInfoTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val rule = MockWebServerRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsFile by lazy { CountingDataStore(prefs("settings")) }
    private val settings by lazy { SettingsStore(settingsFile) }
    private val sendToDevice by lazy { SendToDeviceStore(prefs("send_to_device")) }
    private val info by lazy { ServerInfo(settings, sendToDevice) }
    private val api by lazy { RommServiceFactory.create(rule.server.url("/"), OkHttpClient()) }

    @After fun tearDown() = scope.cancel()

    private fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "$name.preferences_pb") }

    private fun heartbeat(body: String) = rule.server.enqueue(
        MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build(),
    )

    @Test fun a540HeartbeatRecordsTheVersionAndThatInstallsAreOn() = runBlocking<Unit> {
        settings.setServerVersion("5.3.1") // the server was upgraded since Loom last looked
        heartbeat(HEARTBEAT_5_4_0)
        assertThat(info.heartbeat(api).system.version).isEqualTo("5.4.0")
        assertThat(settings.snapshot().serverVersion).isEqualTo("5.4.0")
        assertThat(sendToDevice.snapshot().installsEnabled).isTrue()
    }

    /** RomM before 5.4 can't send games; what an earlier, newer server said doesn't hold for it. */
    @Test fun a531HeartbeatLeavesInstallsUnknown() = runBlocking<Unit> {
        sendToDevice.setInstallsEnabled(true)
        heartbeat(HEARTBEAT_5_3_1)
        info.heartbeat(api)
        assertThat(settings.snapshot().serverVersion).isEqualTo("5.3.1")
        assertThat(sendToDevice.snapshot().installsEnabled).isNull()
    }

    @Test fun installsSwitchedOffOnTheServerAreRecorded() = runBlocking<Unit> {
        heartbeat(HEARTBEAT_5_4_0)
        heartbeat(HEARTBEAT_5_4_0.replace(""""DEVICE_INSTALL":{"ENABLED":true""", """"DEVICE_INSTALL":{"ENABLED":false"""))
        info.heartbeat(api)
        info.heartbeat(api)
        assertThat(sendToDevice.snapshot().installsEnabled).isFalse()
    }

    /** Heartbeats come often (each time Loom comes to the front, each network change): the same version isn't written each time. */
    @Test fun anUnchangedVersionIsntWrittenAgain() = runBlocking<Unit> {
        repeat(3) { heartbeat(HEARTBEAT_5_4_0) }
        repeat(3) { info.heartbeat(api) }
        assertThat(settings.snapshot().serverVersion).isEqualTo("5.4.0")
        assertThat(settingsFile.writes.get()).isEqualTo(1)
    }

    /** Counts the writes made to a DataStore. */
    private class CountingDataStore(private val file: DataStore<Preferences>) : DataStore<Preferences> {
        val writes = AtomicInteger()
        override val data = file.data
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writes.incrementAndGet()
            return file.updateData(transform)
        }
    }

    private companion object {
        const val HEARTBEAT_5_3_1 = """{"SYSTEM":{"VERSION":"5.3.1","GIT_BRANCH":null,"SHOW_SETUP_WIZARD":false},
            "OIDC":{"ENABLED":true,"PROVIDER":"authentik"}}"""
        const val HEARTBEAT_5_4_0 = """{"SYSTEM":{"VERSION":"5.4.0","GIT_BRANCH":null,"SHOW_SETUP_WIZARD":false},
            "OIDC":{"ENABLED":true,"PROVIDER":"authentik"},
            "DEVICE_INSTALL":{"ENABLED":true,"EXCLUDED_PLATFORM_SLUGS":["win","win3x","win9x","windows-apps"]},
            "CONVERTO":{"ENABLED":false}}"""
    }
}
