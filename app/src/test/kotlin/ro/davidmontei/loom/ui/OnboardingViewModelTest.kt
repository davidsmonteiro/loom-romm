package ro.davidmontei.loom.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowEnvironment
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.TokenCipher
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.romm.pairing.RommConnector
import ro.davidmontei.loom.ui.onboarding.OnboardingState
import ro.davidmontei.loom.ui.onboarding.OnboardingStep
import ro.davidmontei.loom.ui.onboarding.OnboardingViewModel
import ro.davidmontei.loom.ui.onboarding.PairMode
import java.io.File

/** The onboarding ViewModel against a fake RomM: what it saves, and when. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val romm = MockWebServerRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = ViewModelStore()
    private lateinit var settings: SettingsStore
    private lateinit var credentials: CredentialStore

    private object PlainCipher : TokenCipher {
        override fun encrypt(plaintext: String) = plaintext
        override fun decrypt(blob: String) = blob
    }

    private val me = """{"id":1,"username":"david","enabled":true,"oauth_scopes":["roms.read"]}"""

    @Volatile private var meRequests = 0

    private val heartbeat = """{"SYSTEM":{"VERSION":"5.3.1","SHOW_SETUP_WIZARD":false}}"""

    /** RomM accepts only [goodToken]; it has no device pairing to offer, so the QR step fails quietly. */
    private fun serve(goodToken: String = "rmm_new", meDelayMs: Long = 0, heartbeatDelayMs: Long = 0) {
        romm.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/heartbeat" -> { Thread.sleep(heartbeatDelayMs); json(heartbeat) }
                "/api/users/me" -> {
                    meRequests++
                    Thread.sleep(meDelayMs)
                    if (request.headers["Authorization"] == "Bearer $goodToken") json(me) else json("""{"detail":"bad token"}""", 401)
                }
                else -> json("""{"detail":"not here"}""", 404)
            }
        }
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Before fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ShadowEnvironment.addExternalDir(tmp.newFolder("sdcard").path) // Android's all-files check needs a volume
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        fun prefs(name: String) = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "$name.preferences_pb") }
        settings = SettingsStore(prefs("settings"))
        credentials = CredentialStore(prefs("credentials"), PlainCipher, scope)
        credentials.awaitLoaded()
        settings.setServer(ServerConfig(romm.server.url("/")), "5.3.1")
        settings.setOnboardingComplete(true)
        credentials.save(PairedCredentials(romm.server.url("/"), "rmm_old", "dev-1", "david", null))
        credentials.current.first { it?.token == "rmm_old" }
        Unit
    }

    @After fun tearDown() {
        viewModels.clear() // stops its coroutines before the next test
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(pairAgain: Boolean): OnboardingViewModel = ViewModelProvider.create(viewModels, viewModelFactory {
        initializer {
            OnboardingViewModel(
                RommConnector(OkHttpClient()), settings, credentials, StorageVolumes(context),
                CatalogSyncScheduler(WorkManager.getInstance(context)), SavedStateHandle(mapOf("pairAgain" to pairAgain)),
            )
        }
    })[OnboardingViewModel::class]

    private fun OnboardingViewModel.await(what: (OnboardingState) -> Boolean) = runBlocking { withTimeout(5_000) { state.first(what) } }

    private fun OnboardingViewModel.pasteToken(token: String) {
        setPairMode(PairMode.PASTE_TOKEN)
        setPairInput(token)
        submitPairInput()
    }

    /** Found by audit: Pair again threw the working token away before a new one existed, and B couldn't undo it. */
    @Test fun pairingAgainKeepsTheTokenUntilANewOneWorks() {
        serve(goodToken = "rmm_new")
        val vm = viewModel(pairAgain = true)
        vm.await { it.pairError != null } // asked RomM for a QR code, which this one doesn't offer
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.PAIR)
        assertThat(vm.state.value.pairedAs).isNull() // the pairing step itself, not "Paired as david"
        assertThat(credentials.current.value?.token).isEqualTo("rmm_old")

        vm.pasteToken("rmm_wrong")
        vm.await { it.pairError != null && !it.pairing }
        assertThat(credentials.current.value?.token).isEqualTo("rmm_old")

        vm.pasteToken("rmm_new")
        vm.await { it.pairedAs == "david" }
        runBlocking { withTimeout(5_000) { credentials.current.first { it?.token == "rmm_new" } } }
        assertThat(vm.state.value.step).isEqualTo(OnboardingStep.PAIR) // the screen closes; no walk through setup
    }

    /** Found by audit: with Pair kept enabled while checking, a second press (or Enter) must not send the code twice. */
    @Test fun aSecondSubmitWhilePairingIsIgnored() {
        serve(goodToken = "rmm_new", meDelayMs = 500)
        val vm = viewModel(pairAgain = true)
        vm.await { it.pairError != null }
        vm.setPairMode(PairMode.PASTE_TOKEN)
        vm.setPairInput("rmm_new")
        vm.submitPairInput()
        vm.submitPairInput()
        vm.await { it.pairedAs == "david" }
        assertThat(meRequests).isEqualTo(1)
    }

    /** Found by audit: a slow check of the old address finished after the user had typed a new one, and took over. */
    @Test fun aLateCheckOfAnEarlierAddressIsDropped() {
        serve(heartbeatDelayMs = 1_000)
        val vm = viewModel(pairAgain = false)
        vm.await { it.addressInput.isNotEmpty() }
        vm.go(OnboardingStep.SERVER)
        vm.connect()
        assertThat(vm.state.value.probing).isTrue()
        vm.setAddress("romm.example.org") // retyping while it hangs
        assertThat(vm.state.value.probing).isFalse() // so the new address can be checked straight away
        Thread.sleep(1_500)
        assertThat(vm.state.value.serverSummary).isNull()
        assertThat(vm.state.value.serverError).isNull()
    }

    /** Found by audit: B back to the Server step forgot the checked address, so Continue was disabled again. */
    @Test fun theSameAddressStaysChecked() {
        serve()
        val vm = viewModel(pairAgain = false)
        vm.await { it.addressInput.isNotEmpty() }
        vm.go(OnboardingStep.SERVER)
        vm.connect()
        vm.await { it.serverSummary != null }
        vm.setAddress(vm.state.value.addressInput) // the field reports its text again when the step reappears
        assertThat(vm.state.value.serverSummary).isNotNull()
    }
}
