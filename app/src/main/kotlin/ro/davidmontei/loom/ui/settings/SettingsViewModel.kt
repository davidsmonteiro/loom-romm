package ro.davidmontei.loom.ui.settings

import android.content.Context
import android.os.Environment
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.BuildConfig
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.auth.Credentials
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.data.download.StorageVolumes
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.CatalogRefresh
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.data.settings.Settings
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.romm.ROMM_CONTRACT_VERSION
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.pairing.RommConnector
import ro.davidmontei.loom.storage.mapping.InvalidMappingException
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import ro.davidmontei.loom.storage.mapping.ResolvedPlatform
import ro.davidmontei.loom.storage.mapping.SystemFolder
import java.io.File
import javax.inject.Inject

enum class SettingsSection(val title: String) {
    SERVER("Server & pairing"), LIBRARY("Library sync"), STORAGE("Storage"), MAPPING("Platform folders"),
    DOWNLOADS("Downloads"), CONTROLS("Controls"), ABOUT("About & licenses"),
}

/** A server platform with the folder and policies Loom will use for it. */
data class MappingRow(val platform: PlatformSummary, val resolved: ResolvedPlatform, val override: PlatformMappingEntry?)

data class StorageInfo(val romsRoot: String?, val volumeLabel: String?, val freeBytes: Long?, val totalBytes: Long?)

data class SettingsUiState(
    val settings: Settings? = null,
    val credentials: Credentials? = null,
    val sync: CatalogSyncStatus = CatalogSyncStatus(running = false),
    val mapping: List<MappingRow> = emptyList(),
    val storage: StorageInfo = StorageInfo(null, null, null, null),
    val batteryUnrestricted: Boolean = false,
    val allFilesAccess: Boolean = false,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val contractVersion: String = ROMM_CONTRACT_VERSION,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val credentials: CredentialStore,
    private val catalog: CatalogDao,
    private val mapping: MappingRepository,
    private val folders: FolderChooser,
    private val volumes: StorageVolumes,
    private val installs: InstallManager,
    private val sync: CatalogSyncScheduler,
    private val connector: RommConnector,
    private val server: ActiveServer,
) : ViewModel() {

    private val storageInfo = MutableStateFlow(StorageInfo(null, null, null, null))
    private val deviceState = MutableStateFlow(false to false)

    val state: StateFlow<SettingsUiState> = combine(
        store.settings, credentials.current, catalog.observePlatformSummaries(), mapping.overrides, storageInfo,
    ) { s, c, platforms, overrides, storage ->
        val mapper = mapping.currentMapper()
        SettingsUiState(
            settings = s,
            credentials = c,
            mapping = platforms.map { p ->
                MappingRow(p, mapper.resolve(p.platform.slug, p.platform.fsSlug),
                    overrides.find { it.match.fsSlug == p.platform.fsSlug } ?: overrides.find { it.match.slug == p.platform.slug && it.match.fsSlug == null })
            },
            storage = storage,
        )
    }.combine(deviceState) { ui, (battery, files) -> ui.copy(batteryUnrestricted = battery, allFilesAccess = files) }
        .combine(sync.status) { ui, status -> ui.copy(sync = status) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init { refreshDevice() }

    fun refreshDevice() = viewModelScope.launch {
        val power = context.getSystemService(PowerManager::class.java)
        deviceState.value = power.isIgnoringBatteryOptimizations(context.packageName) to volumes.hasAllFilesAccess()
        val root = store.snapshot().romsRoot
        storageInfo.value = withContext(Dispatchers.IO) {
            val volume = root?.let(volumes::forPath)
            StorageInfo(root, volume?.label, volume?.let { volumes.freeBytes(it.root) }, volume?.let { volumes.totalBytes(it.root) })
        }
    }

    // --- server ---

    /** Switches to another address of the same server (e.g. from the LAN address to the domain). */
    fun updateAddress(address: String) = viewModelScope.launch {
        try {
            val p = connector.probe(address)
            store.setServer(ServerConfig(p.baseUrl), p.version)
            _messages.send("Saved. RomM ${p.version} answers at ${p.baseUrl}")
        } catch (e: RommException) {
            _messages.send("Couldn't reach RomM there: ${e.message}")
        } catch (e: Exception) {
            _messages.send(e.message ?: "That address doesn't work")
        }
    }

    fun signOut() = viewModelScope.launch {
        credentials.clear()
        _messages.send("Signed out. Your library and installed games stay on the device.")
    }

    // --- library sync ---

    fun fullResync() {
        sync.enqueue(full = true)
        viewModelScope.launch { _messages.send("Full sync started") }
    }

    /** The periodic refresh follows these settings by itself (see LoomApp). */
    fun setCatalogRefresh(refresh: CatalogRefresh) = viewModelScope.launch { store.setCatalogRefresh(refresh) }
    fun setRefreshOnWifiOnly(wifiOnly: Boolean) = viewModelScope.launch { store.setRefreshOnWifiOnly(wifiOnly) }

    // --- storage ---

    fun setRomsRoot(dir: File) = viewModelScope.launch {
        store.setRomsRoot(dir.path)
        refreshDevice()
        val r = installs.reconcile()
        _messages.send("ROMs folder set. Found ${r.adopted} games already there.")
    }

    fun mountedVolumes() = volumes.mounted()

    // --- mapping ---

    fun saveOverride(entry: PlatformMappingEntry) = viewModelScope.launch {
        mapping.setOverride(entry)
        _messages.send("Saved. New downloads use this; games already on the device stay where they are.")
    }

    fun resetOverride(match: PlatformMatch) = viewModelScope.launch { mapping.clearOverride(match) }

    private val exportFile: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "loom-mapping.json")

    fun exportMapping() = viewModelScope.launch {
        val result = runCatching { withContext(Dispatchers.IO) { exportFile.apply { parentFile?.mkdirs(); writeText(mapping.exportJson()) } } }
        _messages.send(result.fold({ "Saved to ${it.path}" }, { "Couldn't write ${exportFile.path}: ${it.message}" }))
    }

    fun importMapping() = viewModelScope.launch {
        val text = runCatching { withContext(Dispatchers.IO) { exportFile.readText() } }.getOrElse {
            _messages.send("Put a mapping file at ${exportFile.path} first"); return@launch
        }
        try {
            mapping.importJson(text)
            _messages.send("Imported ${exportFile.name}")
        } catch (e: InvalidMappingException) {
            _messages.send(e.message ?: "That file isn't a Loom mapping")
        }
    }

    val exportPath: String get() = exportFile.path

    /** Folder names Cocoon knows, offered as suggestions in the editor. */
    val cocoonFolders: List<String> by lazy {
        MappingJson.shippedDefaults().platforms.mapNotNull { it.folder }.distinct().sorted()
    }

    /** Which of a platform's usual folders are on the card, and how many games each holds; null when Loom can't look. */
    suspend fun foldersOnCard(names: List<String>): List<SystemFolder>? = folders.onCard(names)

    // --- downloads & controls ---

    fun setConcurrency(n: Int) = viewModelScope.launch { store.setMaxConcurrentDownloads(n) }
    fun setSwap(swap: Boolean) = viewModelScope.launch { store.setSwapConfirmBack(swap) }

    fun licenseText(): String = context.assets.open("licenses/gpl-3.0.txt").bufferedReader().use { it.readText() }
    fun interLicense(): String = context.assets.open("licenses/inter-OFL.txt").bufferedReader().use { it.readText() }
}
