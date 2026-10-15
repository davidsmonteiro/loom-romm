package ro.davidmontei.loom.di

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.data.settings.WriteThroughDataStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.ServerInfo
import ro.davidmontei.loom.net.ServerReachability
import java.io.File

/** How the app's graph is put together, where that decides whether it works. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class) // not LoomApp, whose own graph would open the same settings file
class AppModuleTest {
    @get:Rule val tmp = TemporaryFolder()
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

    /**
     * The reachability check is a heartbeat Loom makes each time it comes to the front: what RomM says
     * in it is kept, so an upgraded server shows its version, and whether it can send games here, at once.
     */
    @Test fun theReachabilityHeartbeatRecordsWhatRommSays() = runBlocking<Unit> {
        MockWebServer().apply { start() }.use { romm ->
            fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "$name.preferences_pb") }
            val settings = SettingsStore(prefs("s"))
            settings.setServer(ServerConfig(romm.url("/")), "5.3.1")
            val credentials = CredentialStore(prefs("c"), object : TokenCipher {
                override fun encrypt(plaintext: String) = plaintext
                override fun decrypt(blob: String) = blob
            }, scope)
            val sendToDevice = SendToDeviceStore(prefs("d"))
            romm.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
                .body("""{"SYSTEM":{"VERSION":"5.4.0","SHOW_SETUP_WIZARD":false},"DEVICE_INSTALL":{"ENABLED":true,"EXCLUDED_PLATFORM_SLUGS":[]}}""")
                .build())
            val reachability = ServerReachability()
            val server = ActiveServer(settings, credentials, NetworkModule.bareClient(), scope)
            val watch = NetworkModule.reachabilityWatch(reachability, server, ServerInfo(settings, sendToDevice))
            val watching = scope.launch { watch.run() }
            try {
                watch.inFront(true)
                withTimeout(5_000) { while (sendToDevice.snapshot().installsEnabled != true) delay(10) }
                assertThat(settings.snapshot().serverVersion).isEqualTo("5.4.0")
                assertThat(romm.takeRequest().url.encodedPath).isEqualTo("/api/heartbeat")
            } finally {
                watching.cancel()
            }
        }
    }

    /** Every request to RomM, the API's (syncs, covers, saves) and the downloads', tells the library whether it can be reached. */
    @Test fun requestsToRommReportWhetherItAnswered() = runBlocking<Unit> {
        MockWebServer().apply { start() }.use { romm ->
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
            settings.setServer(ServerConfig(romm.url("/")), "5.3.1")
            val credentials = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") },
                object : TokenCipher {
                    override fun encrypt(plaintext: String) = plaintext
                    override fun decrypt(blob: String) = blob
                }, scope)
            val reachability = ServerReachability()
            lateinit var server: ActiveServer
            val lazyServer = dagger.Lazy { server }
            val api = NetworkModule.apiClient(NetworkModule.bareClient(), lazyServer, reachability)
            server = ActiveServer(settings, credentials, api, scope)
            withTimeout(5_000) { while (server.serverUrl() == null) delay(10) }
            for (client in listOf(api, NetworkModule.downloadClient(lazyServer, reachability))) {
                reachability.unreachable()
                romm.enqueue(MockResponse.Builder().code(200).build())
                client.newCall(Request.Builder().url(romm.url("/api/heartbeat")).build()).execute().close()
                assertThat(reachability.current.value).isEqualTo(ServerReachability.State.REACHABLE)
            }
        }
    }
}
