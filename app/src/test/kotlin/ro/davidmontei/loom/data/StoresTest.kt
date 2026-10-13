package ro.davidmontei.loom.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import java.io.File

/** Reversible stand-in for the Keystore cipher (Robolectric has no AndroidKeyStore). */
private class FakeCipher : TokenCipher {
    var broken = false
    override fun encrypt(plaintext: String) = "sealed:" + plaintext.reversed()
    override fun decrypt(blob: String) = if (broken) null else blob.removePrefix("sealed:").reversed()
}

class StoresTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) {
        File(tmp.root, "$name.preferences_pb")
    }

    @Test fun credentialsRoundTripEncryptedAndClear() = runBlocking {
        val prefsStore = prefs("credentials")
        val cipher = FakeCipher()
        val store = CredentialStore(prefsStore, cipher, scope)
        store.awaitLoaded()
        assertThat(store.current.value).isNull()

        store.save(PairedCredentials("https://romm.example.org/".toHttpUrl(), "rmm_abc", "dev-1", "david", null))
        val saved = store.current.filterNotNull().first()
        assertThat(saved.token).isEqualTo("rmm_abc")
        assertThat(saved.deviceId).isEqualTo("dev-1")
        assertThat(saved.toString()).doesNotContain("rmm_abc")

        // Nothing on disk holds the plain token.
        val raw = File(tmp.root, "credentials.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertThat(raw).doesNotContain("rmm_abc")

        store.clear()
        assertThat(store.current.filter { it == null }.first()).isNull()
    }

    @Test fun undecryptableTokenReadsAsUnpaired() = runBlocking {
        val prefsStore = prefs("credentials")
        val cipher = FakeCipher()
        CredentialStore(prefsStore, cipher, scope).apply {
            awaitLoaded()
            save(PairedCredentials("https://r.example.org/".toHttpUrl(), "rmm_abc", null, "david", null))
            current.filterNotNull().first()
        }
        cipher.broken = true
        val reopened = CredentialStore(prefsStore, cipher, scope)
        reopened.awaitLoaded()
        assertThat(reopened.current.value).isNull()
    }

    @Test fun settingsDefaultsAndUpdates() = runBlocking {
        val store = SettingsStore(prefs("settings"))
        val initial = store.snapshot()
        assertThat(initial.server).isNull()
        assertThat(initial.maxConcurrentDownloads).isEqualTo(1)
        assertThat(initial.onboardingComplete).isFalse()

        store.setServer(
            ServerConfig("http://100.64.0.5:8080/".toHttpUrl(), "https://romm.example.org/".toHttpUrl()),
            version = "5.3.1",
        )
        store.setMaxConcurrentDownloads(9)
        store.setRomsRoot("/storage/9C33-6BBD/ROMs")
        val after = store.snapshot()
        assertThat(after.server!!.addresses.map { it.host }).containsExactly("100.64.0.5", "romm.example.org").inOrder()
        assertThat(after.maxConcurrentDownloads).isEqualTo(3)
        assertThat(after.romsRoot).isEqualTo("/storage/9C33-6BBD/ROMs")
        assertThat(after.serverVersion).isEqualTo("5.3.1")
    }

    @Test fun deviceIdentifierIsStable() = runBlocking {
        val store = SettingsStore(prefs("settings"))
        val first = store.deviceIdentifier()
        assertThat(first).startsWith("loom-")
        assertThat(store.deviceIdentifier()).isEqualTo(first)
    }
}
