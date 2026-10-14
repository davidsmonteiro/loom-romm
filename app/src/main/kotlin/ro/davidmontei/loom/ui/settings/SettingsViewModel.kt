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
import ro.davidmontei.loom.data.saves.EdenSaves
import ro.davidmontei.loom.data.saves.SaveSyncTriggers
import ro.davidmontei.loom.data.saves.SaveUnitResolver
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
import ro.davidmontei.loom.storage.saves.EdenSaveLocator
import java.io.File
import javax.inject.Inject

/**
 * Settings' sections. Save sync shares Downloads' section: a seventh would make the section list
 * taller than the Thor's screen (FitsTheScreenTest.theSettingsSectionListFits).
 */
enum class SettingsSection(val title: String) {
    SERVER("Server & pairing"), LIBRARY("Library sync"), STORAGE("Storage"), MAPPING("Platform folders"),
    DOWNLOADS("Downloads"), SAVES("Saves"), CONTROLS("Controls"), ABOUT("About & licenses"),
}

/** A server platform with the folder and policies Loom will use for it. */
data class MappingRow(val platform: PlatformSummary, val resolved: ResolvedPlatform, val override: PlatformMappingEntry?)

data class StorageInfo(
    val romsRoot: String?,
    val volumeLabel: String?,
    val freeBytes: Long?,
    val totalBytes: Long?,
    /** Where Azahar's data was found when Settings doesn't name its folder: the 3DS system folder, as on the Thor. */
    val azaharFound: String? = null,
    /** Eden's save folder when Settings doesn't name one: the ROMs folder's saves/Eden, when it holds user/save. */
    val edenFound: String? = null,
    /** The profiles in the Eden save folder in use: Settings asks whose saves to sync when there are several. */
    val edenProfiles: List<String> = emptyList(),
    /** The owner's prod.keys Loom would read Switch title IDs with; null when there's none. Only its place, never its keys. */
    val prodKeys: String? = null,
)

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
    private val saveSync: SaveSyncTriggers,
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
        val mapper = mapping.currentMapper()
        val n3dsFolders = catalog.platforms().filter { it.slug in SaveUnitResolver.AZAHAR_PLATFORMS }.map { mapper.resolve(it.slug, it.fsSlug).folder }
        val switchFolders = catalog.platforms().filter { it.slug in SaveUnitResolver.EDEN_PLATFORMS }.map { mapper.resolve(it.slug, it.fsSlug).folder }
        val settings = store.snapshot()
        storageInfo.value = withContext(Dispatchers.IO) {
            val volume = root?.let(volumes::forPath)
            val edenFound = root?.let(EdenSaves::defaultSaveDir)?.takeIf { EdenSaveLocator.holdsEdenData(it.toPath()) }
            val edenDir = settings.edenSaveDir?.let(::File) ?: edenFound
            StorageInfo(root, volume?.label, volume?.let { volumes.freeBytes(it.root) }, volume?.let { volumes.totalBytes(it.root) },
                findAzaharFolder(root, n3dsFolders)?.path, edenFound?.path,
                edenDir?.let { runCatching { EdenSaveLocator(it.toPath()).profiles() }.getOrNull() }.orEmpty(),
                root?.let { r -> (switchFolders.ifEmpty { listOf("switch") }).flatMap { EdenSaves.keyFiles(r, it) }.distinct().firstOrNull(File::isFile)?.path },
            )
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

    // --- downloads, saves & controls ---

    fun setConcurrency(n: Int) = viewModelScope.launch { store.setMaxConcurrentDownloads(n) }

    fun setAutoUploadSaves(on: Boolean) = viewModelScope.launch { saveSync.setAutomaticUpload(on) }

    /** [dir] null goes back to finding Azahar's data in the 3DS system folder. */
    fun setAzaharFolder(dir: File?) = viewModelScope.launch {
        store.setAzaharUserDir(dir?.path)
        refreshDevice()
        _messages.send(withContext(Dispatchers.IO) { azaharFolderSaved(dir) })
    }
    fun setSwap(swap: Boolean) = viewModelScope.launch { store.setSwapConfirmBack(swap) }

    /** [dir] null goes back to looking for Eden's saves in the ROMs folder's saves/Eden. */
    fun setEdenFolder(dir: File?) = viewModelScope.launch {
        store.setEdenSaveDir(dir?.path)
        refreshDevice()
        _messages.send(withContext(Dispatchers.IO) { edenFolderSaved(dir) })
    }

    /** Whose saves Loom syncs when Eden has several profiles. */
    fun setEdenProfile(profile: String) = viewModelScope.launch {
        store.setEdenProfile(profile)
        _messages.send("Loom syncs the Switch saves of Eden's profile ${shortProfile(profile)}")
    }

    fun licenseText(): String = context.assets.open("licenses/gpl-3.0.txt").bufferedReader().use { it.readText() }
    fun interLicense(): String = context.assets.open("licenses/inter-OFL.txt").bufferedReader().use { it.readText() }
}

/**
 * Where Azahar keeps its data when Settings doesn't say: the first of the 3DS system folders
 * ([systemFolders], in [romsRoot]) holding its `sdmc/Nintendo 3DS`, as on the Thor.
 */
fun findAzaharFolder(romsRoot: String?, systemFolders: List<String>): File? =
    romsRoot?.let { root -> systemFolders.distinct().map { File(root, it) }.firstOrNull(SaveUnitResolver::holdsAzaharData) }

/** What Settings says once Azahar's folder is chosen ([dir]), or set back to the 3DS folder (null). */
fun azaharFolderSaved(dir: File?): String = when {
    dir == null -> "Loom looks for Azahar's data in the 3DS folder again"
    SaveUnitResolver.holdsAzaharData(dir) -> "Saved. Loom finds 3DS saves in ${dir.path}"
    else -> "Saved, but Azahar's sdmc/Nintendo 3DS isn't in ${dir.path} yet. Run a game in Azahar once, or choose its folder."
}

/** What Settings says once Eden's save folder is chosen ([dir]), or set back to the ROMs folder's saves/Eden (null). */
fun edenFolderSaved(dir: File?): String = when {
    dir == null -> "Loom looks for Eden's saves in the ROMs folder's ${EdenSaves.DEFAULT_FOLDER} again"
    EdenSaveLocator.holdsEdenData(dir.toPath()) -> "Saved. Loom finds Switch saves in ${dir.path}"
    else -> "Saved, but there's no user/save in ${dir.path} yet. Choose the folder Eden saves in."
}

/** An Eden profile (32 hex digits) short enough for a chip: its first and last four. */
fun shortProfile(profile: String): String = if (profile.length > 12) "${profile.take(4)}…${profile.takeLast(4)}" else profile
