package ro.davidmontei.loom.net

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import java.io.File

class ActiveServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cipher = object : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(blob: String) = blob
    }

    @After fun tearDown() = scope.cancel()

    private fun stores(): Pair<SettingsStore, CredentialStore> {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        val creds = CredentialStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "c.preferences_pb") }, cipher, scope)
        return settings to creds
    }

    /** What a WorkManager worker sees right after a cold start: freshly built objects over saved prefs. */
    @Test fun coldStartWaitsForSettingsInsteadOfReportingUnpaired() = runBlocking<Unit> {
        val (settings, creds) = stores()
        settings.setServer(ServerConfig("http://100.64.0.5:8080/".toHttpUrl()), "5.3.1")
        creds.save(PairedCredentials("http://100.64.0.5:8080/".toHttpUrl(), "rmm_x", null, "david", null))
        creds.current.filterNotNull().first()

        val fresh = ActiveServer(settings, creds, OkHttpClient(), scope)
        assertThat(fresh.baseUrl().toString()).isEqualTo("http://100.64.0.5:8080/")
        assertThat(fresh.token()).isEqualTo("rmm_x")
    }

    @Test fun reallyUnpairedIsReported() {
        val (settings, creds) = stores()
        val server = ActiveServer(settings, creds, OkHttpClient(), scope)
        assertThrows(NotPairedException::class.java) { runBlocking { server.baseUrl() } }
    }
}
