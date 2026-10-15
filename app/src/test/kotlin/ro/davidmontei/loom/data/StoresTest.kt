package ro.davidmontei.loom.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.sent.SendToDeviceSettings
import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.settings.CatalogRefresh
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.data.settings.WriteThroughDataStore
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Reversible stand-in for the Keystore cipher (Robolectric has no AndroidKeyStore). */
private class FakeCipher : TokenCipher {
    var broken = false
    override fun encrypt(plaintext: String) = "sealed:" + plaintext.reversed()
    override fun decrypt(blob: String) = if (broken) null else blob.removePrefix("sealed:").reversed()
}

/**
 * DataStore as CI caught it (DataStore 1.2.1): a collector that subscribed as the first write landed
 * got the value from before the write and then nothing more. With [lateFirstValue], that stale value
 * only arrives a moment after the write has returned. Later subscribers follow the writes.
 */
private class UpdateLosingDataStore(private val lateFirstValue: Boolean) : DataStore<Preferences> {
    private val value = MutableStateFlow(emptyPreferences())
    private val subscriptions = AtomicInteger()
    private val firstWrite = CompletableDeferred<Unit>()

    /** Completes once the first collector has subscribed: a write after this is one its value is older than. */
    val firstSubscribed = CompletableDeferred<Unit>()

    override val data: Flow<Preferences> = flow {
        if (subscriptions.incrementAndGet() > 1) return@flow emitAll(value)
        val before = value.value
        firstSubscribed.complete(Unit)
        if (lateFirstValue) {
            firstWrite.await()
            delay(200) // lands after the write has returned
        }
        emit(before)
        awaitCancellation()
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(value.value).also { value.value = it; firstWrite.complete(Unit) }
}

class StoresTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) {
        File(tmp.root, "$name.preferences_pb")
    }

    @Test fun credentialsRoundTripEncryptedAndClear() = runBlocking<Unit> {
        val prefsStore = prefs("credentials")
        val cipher = FakeCipher()
        val store = CredentialStore(prefsStore, cipher, scope)
        store.awaitLoaded()
        assertThat(store.current.value).isNull()

        store.save(PairedCredentials("https://romm.example.org/".toHttpUrl(), "rmm_abc", "dev-1", "david", null))
        val saved = withTimeout(5_000) { store.current.filterNotNull().first() }
        assertThat(saved.token).isEqualTo("rmm_abc")
        assertThat(saved.deviceId).isEqualTo("dev-1")
        assertThat(saved.toString()).doesNotContain("rmm_abc")

        // Nothing on disk holds the plain token.
        val raw = File(tmp.root, "credentials.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertThat(raw).doesNotContain("rmm_abc")

        store.clear()
        assertThat(withTimeout(5_000) { store.current.filter { it == null }.first() }).isNull()
    }

    /** Found on CI: the save was on disk, but [CredentialStore.current] stayed null for good. */
    @Test fun aSaveShowsAtOnceEvenWhenDataStoreLosesTheUpdate() = runBlocking<Unit> {
        val store = CredentialStore(UpdateLosingDataStore(lateFirstValue = false), FakeCipher(), scope)
        store.awaitLoaded()
        // Writes right after each other: each shows as soon as it returns (a re-read once let a save come back after a clear).
        store.save(PairedCredentials("https://romm.example.org/".toHttpUrl(), "rmm_abc", "dev-1", "david", null))
        assertThat(store.current.value?.deviceId).isEqualTo("dev-1")
        store.save(PairedCredentials("https://romm.example.org/".toHttpUrl(), "rmm_def", "dev-2", "david", null))
        assertThat(store.current.value?.deviceId).isEqualTo("dev-2")
        store.clear()
        assertThat(store.current.value).isNull()
        delay(500)
        assertThat(store.current.value).isNull()
    }

    /** Pairing right as Loom starts: DataStore's first read, older than the save, arrives after it and doesn't undo it. */
    @Test fun aReadOlderThanASaveDoesntUndoIt() = runBlocking<Unit> {
        val store = CredentialStore(UpdateLosingDataStore(lateFirstValue = true), FakeCipher(), scope)
        store.save(PairedCredentials("https://romm.example.org/".toHttpUrl(), "rmm_abc", "dev-1", "david", null))
        assertThat(store.current.value?.deviceId).isEqualTo("dev-1")
        assertThat(withTimeoutOrNull(2_000) { store.awaitLoaded(); true }).isTrue() // the late read has arrived
        assertThat(store.current.value?.deviceId).isEqualTo("dev-1")
    }

    /**
     * CatalogRefreshScheduler follows the settings for as long as Loom runs, and each screen's state
     * for as long as it's open: such a reader sees every change, not just the value it started with.
     */
    @Test fun aLongLivedSettingsReaderSeesEveryChange() = runBlocking<Unit> {
        val store = SettingsStore(WriteThroughDataStore(UpdateLosingDataStore(lateFirstValue = false), scope))
        val seen = MutableStateFlow<CatalogRefresh?>(null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { store.settings.collect { seen.value = it.catalogRefresh } }
        assertThat(withTimeoutOrNull(2_000) { seen.first { it == CatalogRefresh.EVERY_12_HOURS } }).isNotNull()
        store.setCatalogRefresh(CatalogRefresh.EVERY_6_HOURS)
        assertThat(withTimeoutOrNull(2_000) { seen.first { it == CatalogRefresh.EVERY_6_HOURS } }).isNotNull()
        store.setCatalogRefresh(CatalogRefresh.OFF)
        assertThat(withTimeoutOrNull(2_000) { seen.first { it == CatalogRefresh.OFF } }).isNotNull()
    }

    /** A setting changed right as Loom starts: the settings file's first read, older than the change, arrives after it and doesn't undo it. */
    @Test fun aSettingsReadOlderThanAChangeDoesntUndoIt() = runBlocking<Unit> {
        val file = UpdateLosingDataStore(lateFirstValue = true)
        val store = SettingsStore(WriteThroughDataStore(file, scope))
        withTimeout(2_000) { file.firstSubscribed.await() } // Loom has started reading the file
        store.setAutoUploadSaves(false)
        assertThat(store.snapshot().autoUploadSaves).isFalse()
        delay(500) // the read Loom started with arrives 200 ms after the change
        assertThat(store.snapshot().autoUploadSaves).isFalse()
    }

    /**
     * SettingsStore and MappingRepository write the same file (AppModule gives them one): a reader of
     * the platform folders sees a choice made after it started, and neither store's writes undo the other's.
     */
    @Test fun theStoresSharingTheSettingsFileKeepEachOthersChanges() = runBlocking<Unit> {
        val file = WriteThroughDataStore(UpdateLosingDataStore(lateFirstValue = false), scope)
        val settings = SettingsStore(file)
        val mapping = MappingRepository(file)
        val overrides = MutableStateFlow<List<PlatformMappingEntry>?>(null)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { mapping.overrides.collect { overrides.value = it } }

        mapping.chooseFolder("arcade", "fbneo")
        settings.setRomsRoot("/storage/9C33-6BBD/ROMs")
        mapping.chooseFolder("snes", "sfc")

        assertThat(withTimeoutOrNull(2_000) { overrides.first { it?.size == 2 } }).isNotNull()
        assertThat(mapping.currentMapper().resolve("arcade", "arcade").folder).isEqualTo("fbneo")
        assertThat(settings.snapshot().romsRoot).isEqualTo("/storage/9C33-6BBD/ROMs")
    }

    @Test fun undecryptableTokenReadsAsUnpaired() = runBlocking<Unit> {
        val prefsStore = prefs("credentials")
        val cipher = FakeCipher()
        CredentialStore(prefsStore, cipher, scope).apply {
            awaitLoaded()
            save(PairedCredentials("https://r.example.org/".toHttpUrl(), "rmm_abc", null, "david", null))
            withTimeout(5_000) { current.filterNotNull().first() }
        }
        cipher.broken = true
        val reopened = CredentialStore(prefsStore, cipher, scope)
        reopened.awaitLoaded()
        assertThat(reopened.current.value).isNull()
    }

    @Test fun settingsDefaultsAndUpdates() = runBlocking<Unit> {
        val store = SettingsStore(prefs("settings"))
        val initial = store.snapshot()
        assertThat(initial.server).isNull()
        assertThat(initial.maxConcurrentDownloads).isEqualTo(1)
        assertThat(initial.onboardingComplete).isFalse()

        store.setServer(ServerConfig("http://100.64.0.5:8080/".toHttpUrl()), version = "5.3.1")
        store.setMaxConcurrentDownloads(9)
        store.setRomsRoot("/storage/9C33-6BBD/ROMs")
        val after = store.snapshot()
        assertThat(after.server!!.url.toString()).isEqualTo("http://100.64.0.5:8080/")
        assertThat(after.maxConcurrentDownloads).isEqualTo(3)
        assertThat(after.romsRoot).isEqualTo("/storage/9C33-6BBD/ROMs")
        assertThat(after.serverVersion).isEqualTo("5.3.1")
    }

    @Test fun catalogRefreshDefaultsAndUpdates() = runBlocking<Unit> {
        val prefsStore = prefs("settings")
        val store = SettingsStore(prefsStore)
        val initial = store.snapshot()
        assertThat(initial.catalogRefresh).isEqualTo(CatalogRefresh.EVERY_12_HOURS)
        assertThat(initial.refreshOnWifiOnly).isFalse()

        store.setCatalogRefresh(CatalogRefresh.EVERY_6_HOURS)
        store.setRefreshOnWifiOnly(true)
        val after = store.snapshot()
        assertThat(after.catalogRefresh).isEqualTo(CatalogRefresh.EVERY_6_HOURS)
        assertThat(after.refreshOnWifiOnly).isTrue()

        store.setCatalogRefresh(CatalogRefresh.OFF)
        assertThat(store.snapshot().catalogRefresh).isEqualTo(CatalogRefresh.OFF)
        // An interval Loom doesn't offer (from a future or past version) reads as the default.
        prefsStore.edit { it[intPreferencesKey("catalog_refresh_hours")] = 7 }
        assertThat(store.snapshot().catalogRefresh).isEqualTo(CatalogRefresh.EVERY_12_HOURS)
    }

    @Test fun deviceIdentifierIsStable() = runBlocking<Unit> {
        val store = SettingsStore(prefs("settings"))
        val first = store.deviceIdentifier()
        assertThat(first).startsWith("loom-")
        assertThat(store.deviceIdentifier()).isEqualTo(first)
    }

    /** A reinstall wipes Loom's data but not ANDROID_ID: pairing again reuses the same RomM device. */
    @Test fun aNewInstallDerivesItsIdentifierFromAndroidId() = runBlocking<Unit> {
        val first = SettingsStore(prefs("install1")) { "A1B2C3D4E5F60718" }.deviceIdentifier()
        val reinstalled = SettingsStore(prefs("install2")) { "a1b2c3d4e5f60718" }.deviceIdentifier()
        assertThat(first).isEqualTo("loom-a1b2c3d4e5f60718")
        assertThat(reinstalled).isEqualTo(first)
    }

    /** An install that already paired keeps the device it paired as, whatever ANDROID_ID says. */
    @Test fun anExistingIdentifierIsKept() = runBlocking<Unit> {
        val store = prefs("settings")
        val paired = SettingsStore(store).deviceIdentifier()
        assertThat(SettingsStore(store) { "a1b2c3d4e5f60718" }.deviceIdentifier()).isEqualTo(paired)
    }

    @Test fun anUnusableAndroidIdFallsBackToARandomIdentifier() = runBlocking<Unit> {
        for ((i, bad) in listOf(null, "", "0000000000000000", "9774d56d682e549c", "not-hex!").withIndex()) {
            val id = SettingsStore(prefs("bad$i")) { bad }.deviceIdentifier()
            assertThat(id).startsWith("loom-")
            assertThat(id.removePrefix("loom-")).hasLength(36) // a UUID
        }
    }

    @Test fun azaharsFolderIsFoundUnlessSetInSettings() = runBlocking<Unit> {
        val store = SettingsStore(prefs("settings"))
        assertThat(store.snapshot().azaharUserDir).isNull()
        store.setAzaharUserDir("/storage/6365-3230/Azahar")
        assertThat(store.snapshot().azaharUserDir).isEqualTo("/storage/6365-3230/Azahar")
        store.setAzaharUserDir(null)
        assertThat(store.snapshot().azaharUserDir).isNull()
    }

    @Test fun theSecondScreenIsOnUntilTurnedOff() = runBlocking<Unit> {
        val store = SettingsStore(prefs("settings"))
        assertThat(store.snapshot().secondScreen).isTrue()
        store.setSecondScreen(false)
        assertThat(store.snapshot().secondScreen).isFalse()
        store.setSecondScreen(true)
        assertThat(store.snapshot().secondScreen).isTrue()
    }

    @Test fun automaticSaveUploadIsOnUntilTurnedOff() = runBlocking<Unit> {
        val store = SettingsStore(prefs("settings"))
        assertThat(store.snapshot().autoUploadSaves).isTrue()
        store.setAutoUploadSaves(false)
        assertThat(store.snapshot().autoUploadSaves).isFalse()
    }

    /** Games sent from RomM come unless switched off; nothing is known of the server or the pairing until it says. */
    @Test fun receivingGamesFromRommIsOnUntilTurnedOff() = runBlocking<Unit> {
        val store = SendToDeviceStore(prefs("send_to_device"))
        val fresh = store.snapshot()
        assertThat(fresh.receive).isTrue()
        assertThat(fresh.installsEnabled).isNull()
        assertThat(fresh.needsPairing).isFalse()
        store.setReceive(false)
        store.setInstallsEnabled(false)
        store.setNeedsPairing(true)
        assertThat(store.snapshot()).isEqualTo(SendToDeviceSettings(receive = false, installsEnabled = false, needsPairing = true))
        store.setInstallsEnabled(null)
        assertThat(store.snapshot().installsEnabled).isNull()
    }
}
