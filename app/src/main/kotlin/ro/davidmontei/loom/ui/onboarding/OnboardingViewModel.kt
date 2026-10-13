package ro.davidmontei.loom.ui.onboarding

import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import ro.davidmontei.loom.BuildConfig
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.MIN_SUPPORTED_ROMM
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommUrls
import ro.davidmontei.loom.romm.pairing.DeviceAuthResult
import ro.davidmontei.loom.romm.pairing.DeviceAuthSession
import ro.davidmontei.loom.romm.pairing.DeviceIdentity
import ro.davidmontei.loom.romm.pairing.PairedCredentials
import ro.davidmontei.loom.romm.pairing.RommConnector
import ro.davidmontei.loom.romm.pairing.UnsupportedServerException
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.ui.nav.OnboardingRoute
import java.io.File
import javax.inject.Inject

enum class OnboardingStep { WELCOME, SERVER, PAIR, STORAGE, ROMS_ROOT, BACKGROUND, SYNC }

enum class PairMode { DEVICE_CODE, PAIR_CODE, PASTE_TOKEN }

/**
 * The first step not done yet. Onboarding opens here, so a setup the process died in part-way
 * (Android may kill Loom while the user is in its settings) picks up where it was instead of
 * pairing a second time, and pairing moves on from here too.
 */
internal fun resumeStep(hasServer: Boolean, paired: Boolean, hasAllFilesAccess: Boolean, hasRomsRoot: Boolean): OnboardingStep = when {
    !hasServer -> OnboardingStep.WELCOME
    !paired -> OnboardingStep.PAIR
    !hasAllFilesAccess -> OnboardingStep.STORAGE
    !hasRomsRoot -> OnboardingStep.ROMS_ROOT
    else -> OnboardingStep.BACKGROUND
}

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val addressInput: String = "",
    val probing: Boolean = false,
    val serverError: String? = null,
    val serverSummary: String? = null,
    val insecureWarning: String? = null,
    val base: HttpUrl? = null,
    val pairMode: PairMode = PairMode.DEVICE_CODE,
    val session: DeviceAuthSession? = null,
    val sessionStartedAt: Long = 0,
    val pairInput: String = "",
    val pairing: Boolean = false,
    val pairError: String? = null,
    val pairedAs: String? = null,
    val hasAllFilesAccess: Boolean = false,
    val volumes: List<VolumeInfo> = emptyList(),
    val suggestedRoot: File? = null,
    val romsRoot: String? = null,
    val sync: CatalogSyncStatus? = null,
    /** Pairing again (from Settings): only the pairing step, and the current token stays until a new one works. */
    val pairAgain: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val connector: RommConnector,
    private val settings: SettingsStore,
    private val credentials: CredentialStore,
    private val volumes: StorageVolumes,
    private val sync: CatalogSyncScheduler,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val pairAgain = savedState.toRoute<OnboardingRoute>().pairAgain
    private val _state = MutableStateFlow(OnboardingState(step = if (pairAgain) OnboardingStep.PAIR else OnboardingStep.WELCOME, pairAgain = pairAgain))
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    private var pollJob: Job? = null

    /** Folder names Cocoon uses, to recognise a ROMs root in the folder picker. */
    val knownSystemFolders: Set<String> by lazy {
        MappingJson.shippedDefaults().platforms.mapNotNull { it.folder }.toSet()
    }

    init {
        viewModelScope.launch {
            val s = settings.snapshot()
            val creds = credentials.current.value
            _state.update {
                it.copy(addressInput = s.server?.url?.toString().orEmpty(), base = s.server?.url, romsRoot = s.romsRoot,
                    pairedAs = creds?.username.takeUnless { pairAgain })
            }
            go(if (pairAgain) OnboardingStep.PAIR else resumeStep(s.server != null, creds != null, volumes.hasAllFilesAccess(), s.romsRoot != null))
        }
        viewModelScope.launch { sync.status.collect { st -> _state.update { it.copy(sync = st) } } }
    }

    fun go(step: OnboardingStep) {
        _state.update { it.copy(step = step) }
        when (step) {
            OnboardingStep.PAIR -> if (_state.value.pairedAs == null && _state.value.session == null && _state.value.pairMode == PairMode.DEVICE_CODE) startDeviceAuth()
            OnboardingStep.STORAGE -> refreshStorage()
            OnboardingStep.ROMS_ROOT -> refreshStorage()
            OnboardingStep.SYNC -> sync.enqueue(full = true)
            else -> Unit
        }
    }

    fun setAddress(text: String) = _state.update { it.copy(addressInput = text, serverError = null, serverSummary = null) }

    /** Finds RomM at the typed address (and the optional second one), then moves on to pairing. */
    fun connect() {
        val s = _state.value
        if (s.probing) return
        _state.update { it.copy(probing = true, serverError = null, serverSummary = null, insecureWarning = null) }
        viewModelScope.launch {
            try {
                val probe = connector.probe(s.addressInput)
                settings.setServer(ServerConfig(probe.baseUrl), probe.version)
                val url = probe.baseUrl
                _state.update {
                    it.copy(
                        probing = false, base = url,
                        serverSummary = "RomM ${probe.version} at $url",
                        insecureWarning = if (url.scheme == "http" && !RommUrls.isPrivateHost(url.host)) {
                            "${url.host} is a public address over plain HTTP: your token would travel unencrypted. Use https:// if you can."
                        } else null,
                    )
                }
            } catch (e: UnsupportedServerException) {
                _state.update { it.copy(probing = false, serverError = "This server runs RomM ${e.version}. Loom needs RomM $MIN_SUPPORTED_ROMM or newer.") }
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(probing = false, serverError = e.message) }
            } catch (e: RommException.Protocol) {
                _state.update { it.copy(probing = false, serverError = "Something answered, but it isn't RomM. Check the address (and any login page in front of it).") }
            } catch (e: RommException) {
                _state.update { it.copy(probing = false, serverError = "Couldn't reach RomM: ${e.message}") }
            }
        }
    }

    /** Back on the pairing step after pairing: replace the token with a new one. */
    fun pairAgain() {
        _state.update { it.copy(pairedAs = null, pairMode = PairMode.DEVICE_CODE) }
        startDeviceAuth()
    }

    fun setPairMode(mode: PairMode) {
        _state.update { it.copy(pairMode = mode, pairError = null, pairInput = "") }
        if (mode == PairMode.DEVICE_CODE && _state.value.session == null) startDeviceAuth()
    }

    fun setPairInput(text: String) = _state.update { it.copy(pairInput = text, pairError = null) }

    /** Asks RomM for a code to show; then waits for the user to approve it on their phone. */
    fun startDeviceAuth() {
        val base = _state.value.base ?: return
        pollJob?.cancel()
        _state.update { it.copy(session = null, pairError = null, pairing = true) }
        pollJob = viewModelScope.launch {
            try {
                val device = DeviceIdentity(settings.deviceIdentifier(), deviceName(), BuildConfig.VERSION_NAME)
                val session = connector.startDeviceAuth(base, device)
                _state.update { it.copy(session = session, sessionStartedAt = System.currentTimeMillis(), pairing = false) }
                when (val result = connector.awaitDeviceAuth(session)) {
                    is DeviceAuthResult.Paired -> paired(result.credentials)
                    DeviceAuthResult.Denied -> _state.update { it.copy(session = null, pairError = "The request was denied in RomM. Start again to get a new code.") }
                    DeviceAuthResult.Expired -> _state.update { it.copy(session = null, pairError = "The code expired before it was approved. Start again for a new one.") }
                }
            } catch (e: RommException.RateLimited) {
                _state.update { it.copy(pairing = false, pairError = "RomM is rate-limiting pairing requests. Wait a minute and try again.") }
            } catch (e: RommException) {
                _state.update { it.copy(pairing = false, pairError = e.message) }
            }
        }
    }

    fun submitPairInput() {
        val s = _state.value
        if (s.pairing) return // already checking this one (the button stays enabled, and Enter can submit too)
        val base = s.base ?: return
        _state.update { it.copy(pairing = true, pairError = null) }
        viewModelScope.launch {
            try {
                val creds = when (s.pairMode) {
                    PairMode.PAIR_CODE -> connector.exchangePairCode(base, s.pairInput)
                    PairMode.PASTE_TOKEN -> connector.usePastedToken(base, s.pairInput)
                    PairMode.DEVICE_CODE -> return@launch
                }
                paired(creds)
            } catch (e: RommException.NotFound) {
                _state.update { it.copy(pairing = false, pairError = "That code is invalid or expired (codes last 60 seconds). Create a new one in RomM.") }
            } catch (e: RommException.Unauthorized) {
                _state.update { it.copy(pairing = false, pairError = "RomM didn't accept that token.") }
            } catch (e: Exception) {
                _state.update { it.copy(pairing = false, pairError = e.message ?: "Pairing failed") }
            }
        }
    }

    /** The new token replaces the old one only here, once RomM has accepted it. */
    private suspend fun paired(creds: PairedCredentials) {
        credentials.save(creds)
        _state.update { it.copy(pairing = false, pairedAs = creds.username, session = null) }
        // Pairing again ends here (the screen closes); a sync that needed the new token runs again.
        if (pairAgain) sync.enqueue()
        else go(resumeStep(hasServer = true, paired = true, volumes.hasAllFilesAccess(), _state.value.romsRoot != null))
    }

    fun refreshStorage() {
        val mounted = volumes.mounted()
        _state.update {
            it.copy(
                hasAllFilesAccess = volumes.hasAllFilesAccess(),
                volumes = mounted,
                suggestedRoot = it.romsRoot?.let(::File) ?: volumes.defaultRomsRoot(),
            )
        }
    }

    fun pickRoot(dir: File) {
        viewModelScope.launch {
            settings.setRomsRoot(dir.path)
            _state.update { it.copy(romsRoot = dir.path) }
            go(OnboardingStep.BACKGROUND)
        }
    }

    /** Creates `ROMs` on the suggested volume when it doesn't exist yet. */
    fun createSuggestedRoot() {
        val root = _state.value.suggestedRoot ?: return
        if (root.mkdirs() || root.isDirectory) pickRoot(root)
    }

    fun finish(onFinished: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            onFinished()
        }
    }

    private fun deviceName(): String {
        val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ")
            .replaceFirstChar { it.uppercase() }
        return "$model (Loom)"
    }

    override fun onCleared() {
        pollJob?.cancel()
    }
}
