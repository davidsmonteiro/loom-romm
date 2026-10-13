package ro.davidmontei.loom.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.catalog.CatalogSyncer
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.EnqueueResult
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.install.OffloadResult
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.storage.engine.formatBytes
import javax.inject.Inject

enum class LibraryFilter(val label: String) { ALL("All"), ON_DEVICE("On device"), NOT_ON_DEVICE("Not on device") }

data class LibraryHeader(
    val sync: CatalogSyncStatus,
    val lastSyncMs: Long?,
    val activeDownloads: Int,
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val catalog: CatalogDao,
    private val downloads: DownloadRepository,
    private val installs: InstallManager,
    private val syncScheduler: CatalogSyncScheduler,
    private val server: ActiveServer,
    settings: SettingsStore,
) : ViewModel() {

    val platforms: StateFlow<List<PlatformSummary>> = catalog.observePlatformSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedId = MutableStateFlow<Int?>(null)
    val filter = MutableStateFlow(LibraryFilter.ALL)
    val query = MutableStateFlow("")
    val gridMode = MutableStateFlow(true)

    /** The selected platform, defaulting to the first one with games. */
    val selected: StateFlow<PlatformSummary?> = combine(platforms, selectedId) { list, id ->
        list.firstOrNull { it.platform.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val games: StateFlow<List<GameItem>> = combine(selected, filter, query.debounce(150)) { p, f, q -> Triple(p, f, q) }
        .flatMapLatest { (platform, f, q) ->
            if (platform == null) flowOf(emptyList())
            else combine(catalog.observeGames(platform.platform.id, f.ordinal, CatalogSyncer.fold(q)), downloads.progress) { rows, live ->
                rows.map { row -> row.toItem(row.jobId?.let { live[it]?.bytesDone }) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val header: StateFlow<LibraryHeader> = combine(syncScheduler.status, settings.settings, downloads.jobs) { sync, s, jobs ->
        LibraryHeader(sync, s.lastCatalogSyncEpochMs, jobs.count { it.phase != "FAILED" })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryHeader(CatalogSyncStatus(false), null, 0))

    /** Base URL for cover art; covers are cached by path, so any reachable address will do. */
    val coverBase: StateFlow<HttpUrl?> = settings.settings.map { it.server?.primary }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { runCatching { server.baseUrl() } }
    }

    fun selectPlatform(id: Int) { selectedId.value = id }

    /** L1/R1: previous/next platform, wrapping around. */
    fun cyclePlatform(step: Int) {
        val list = platforms.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.platform.id == selected.value?.platform?.id }.coerceAtLeast(0)
        selectedId.value = list[(index + step).mod(list.size)].platform.id
    }

    fun setFilter(f: LibraryFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun toggleGrid() { gridMode.value = !gridMode.value }
    fun sync(full: Boolean = false) = syncScheduler.enqueue(full)

    fun download(game: GameItem) = viewModelScope.launch {
        when (val r = downloads.enqueue(game.id)) {
            is EnqueueResult.Queued -> _messages.send("${game.title} added to the queue")
            EnqueueResult.AlreadyQueued -> _messages.send("${game.title} is already in the queue")
            EnqueueResult.AlreadyInstalled -> _messages.send("${game.title} is already on your device")
            is EnqueueResult.Failed -> _messages.send(r.message)
        }
    }

    fun offload(game: GameItem) = viewModelScope.launch {
        when (val r = installs.offload(game.id)) {
            is OffloadResult.Removed -> _messages.send("Removed ${game.title}, freeing ${formatBytes(r.bytes)}")
            OffloadResult.NotInstalled -> Unit
            is OffloadResult.Failed -> _messages.send(r.message)
        }
    }

    fun retry(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.retry(it) } }
    fun cancel(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.cancel(it) } }
    fun pause(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.pause(it) } }
    fun resume(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.resume(it) } }

    suspend fun files(romId: Int) = catalog.filesOf(romId)
}
