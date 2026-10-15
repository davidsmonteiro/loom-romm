package ro.davidmontei.loom.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.catalog.CatalogSyncer
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.QueueGame
import ro.davidmontei.loom.data.install.InstallManager
import ro.davidmontei.loom.data.install.OffloadResult
import ro.davidmontei.loom.data.mapping.FolderChooser
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.ServerReachability
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.folders.FolderQuestion
import ro.davidmontei.loom.ui.folders.toQuestion
import ro.davidmontei.loom.ui.saves.SaveFolderQuestion
import ro.davidmontei.loom.ui.saves.answer
import ro.davidmontei.loom.ui.saves.toQuestion

enum class LibraryFilter(val label: String) { ALL("All"), ON_DEVICE("On device"), NOT_ON_DEVICE("Not on device") }

/**
 * The platform the library shows: the one [picked] with the rail or L1/R1, else the first listed.
 * That default is pinned into [picked] as soon as there is one: a first sync adds platforms as their
 * ROMs arrive, not by name, and one sorting earlier would otherwise take over the screen (title,
 * games and focus) while the user is browsing.
 */
internal fun selectedPlatform(platforms: Flow<List<PlatformSummary>>, picked: MutableStateFlow<Int?>): Flow<PlatformSummary?> =
    combine(platforms, picked) { list, id -> list.firstOrNull { it.platform.id == id } ?: list.firstOrNull() }
        .onEach { if (it != null && picked.value == null) picked.value = it.platform.id }

data class LibraryHeader(
    val sync: CatalogSyncStatus,
    val lastSyncMs: Long?,
    val activeDownloads: Int,
    /** RomM couldn't be reached the last time Loom tried: the library is the copy kept on the Thor. */
    val offline: Boolean = false,
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
    private val folders: FolderChooser,
    private val queueGame: QueueGame,
    private val saves: SaveRepository,
    reachability: ServerReachability,
) : ViewModel() {

    val platforms: StateFlow<List<PlatformSummary>> = catalog.observePlatformSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedId = MutableStateFlow<Int?>(null)
    val filter = MutableStateFlow(LibraryFilter.ALL)
    val query = MutableStateFlow("")
    val gridMode = MutableStateFlow(true)

    /** The selected platform, defaulting to the first one with games (see [selectedPlatform]). */
    val selected: StateFlow<PlatformSummary?> = selectedPlatform(platforms, selectedId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val games: StateFlow<List<GameItem>> = combine(selected, filter, query.debounce(150)) { p, f, q -> Triple(p, f, q) }
        .flatMapLatest { (platform, f, q) ->
            if (platform == null) flowOf(emptyList())
            else combine(
                catalog.observeGames(platform.platform.id, f.ordinal, CatalogSyncer.fold(q)), downloads.progress, saves.observeGames(),
                settings.settings.map { it.autoUploadSaves }.distinctUntilChanged(),
                combine(saves.observeChosenFolders(), saves.observeSyncedFolders(), saves.looks, ::Triple),
            ) { rows, live, units, autoUpload, (chosen, synced, looks) ->
                rows.map { row ->
                    row.toItem(row.jobId?.let { live[it]?.bytesDone })
                        .withSave(platform.platform.slug, units[row.id], autoUpload, row.id in chosen, looks[row.id], row.id in synced)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val header: StateFlow<LibraryHeader> = combine(syncScheduler.status, settings.settings, downloads.jobs, reachability.current) { sync, s, jobs, reach ->
        LibraryHeader(sync, s.lastCatalogSyncEpochMs, jobs.count { it.phase != "FAILED" }, offline = reach == ServerReachability.State.UNREACHABLE)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryHeader(CatalogSyncStatus(false), null, 0))

    private val focusedGame = MutableStateFlow<Int?>(null)

    /**
     * What RomM says of the game last [focus]ed, as the catalog has it: null until one is, and while
     * RomM says nothing of it. Never another game's: focus moving on clears it until the new game's is read.
     */
    val focusedDetails: StateFlow<RomDetails?> = focusedGame
        .flatMapLatest { id -> if (id == null) flowOf(null) else catalog.observeDetails(id).map { it?.toDetails() } }
        .combine(focusedGame) { details, id -> details?.takeIf { it.romId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Base URL for cover art. Covers are cached by path, so changing the address keeps them. */
    val coverBase: StateFlow<HttpUrl?> = settings.settings.map { it.server?.url }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _folderQuestion = MutableStateFlow<FolderQuestion?>(null)
    val folderQuestion: StateFlow<FolderQuestion?> = _folderQuestion

    private val _saveFolderQuestion = MutableStateFlow<SaveFolderQuestion?>(null)
    val saveFolderQuestion: StateFlow<SaveFolderQuestion?> = _saveFolderQuestion

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { runCatching { server.baseUrl() } }
    }

    fun selectPlatform(id: Int) { selectedId.value = id }

    /** The panels show game [id] now (see [focusedDetails]). */
    fun focus(id: Int) { focusedGame.value = id }

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

    /**
     * X on a game. A platform's first download settles its system folder: from what is on the card
     * when that's clear, else by asking ([folderQuestion]). Either way the choice is remembered.
     */
    fun download(game: GameItem) = viewModelScope.launch { say(game.id, game.title, queueGame.queue(game.id, QueueGame.WhenUnsure.ASK)) }

    /** The answer to [folderQuestion]: remembered for the platform, then the game is queued. */
    fun chooseFolder(folder: String) = viewModelScope.launch {
        val q = _folderQuestion.value ?: return@launch
        _folderQuestion.value = null
        queueGame.folderChosen(q.fsSlug, folder, thenQueue = q.romId, platformId = q.platformId)?.let { say(q.romId, q.title.orEmpty(), it) }
    }

    fun showAllFolders() = viewModelScope.launch {
        val all = folders.allFolders()
        _folderQuestion.update { it?.copy(allFolders = all) }
    }

    /** B in the list of every folder: back to the likely ones. */
    fun showSuggestedFolders() = _folderQuestion.update { it?.copy(allFolders = null) }

    fun dismissFolderQuestion() { _folderQuestion.value = null }

    /** How queueing game [romId] went, in a message, or the folder question to answer first. */
    private suspend fun say(romId: Int?, title: String, outcome: QueueGame.Outcome) = when (outcome) {
        is QueueGame.Outcome.AskFolder -> _folderQuestion.value = outcome.ask.toQuestion(romId, title)
        is QueueGame.Outcome.Queued -> _messages.send("$title added to the queue" + outcome.folderNote?.let { ". $it" }.orEmpty())
        // Not from the library, which asks; listed to be complete.
        is QueueGame.Outcome.WaitingForFolder -> _messages.send("$title is in the queue, waiting for its folder")
        QueueGame.Outcome.AlreadyQueued -> _messages.send("$title is already in the queue")
        QueueGame.Outcome.AlreadyInstalled -> _messages.send("$title is already on your device")
        is QueueGame.Outcome.Failed -> _messages.send(outcome.message)
    }

    fun offload(game: GameItem) = viewModelScope.launch {
        when (val r = installs.offload(game.id)) {
            is OffloadResult.Removed -> _messages.send("Removed ${game.title}, freeing ${formatBytes(r.bytes)}")
            OffloadResult.NotInstalled -> Unit
            is OffloadResult.Failed -> _messages.send(r.message)
        }
    }

    /**
     * A game's details opened: Loom looks at its save (moving nothing), so they say where it stands
     * now. The repository skips a look already running, or one made moments ago.
     */
    fun lookAtSave(game: GameItem) {
        if (game.save?.supported == true) saves.look(game.id)
    }

    /** "Upload save" in the details. The answer arrives once the save has been checked twice, a few seconds apart. */
    fun uploadSave(game: GameItem) = viewModelScope.launch {
        _messages.send("Checking ${game.title}'s save…")
        _messages.send(saves.upload(game.id, game.title))
    }

    /** "Restore server save", once the user confirmed the emulator is closed. */
    fun restoreSave(game: GameItem) = viewModelScope.launch {
        _messages.send("Restoring ${game.title}'s save from RomM…")
        _messages.send(saves.restore(game.id, game.title))
    }

    /** "Choose save folder" in a Switch or PSP game's details: the Eden save folders, or PPSSPP's disc IDs, it can be. */
    fun askSaveFolder(game: GameItem) = viewModelScope.launch {
        val emulator = game.save?.emulator
        _saveFolderQuestion.value = saves.saveFolderChoice(game.id, emulator).toQuestion(game.id, game.title, emulator)
    }

    /** "Choose save folder" for a Switch game whose save Eden keeps in two folders: which one Loom syncs. */
    fun askSyncedFolder(game: GameItem) = viewModelScope.launch {
        _saveFolderQuestion.value = saves.syncedFolderChoice(game.id).toQuestion(game.id, game.title, game.save?.emulator)
    }

    /** The answer to [saveFolderQuestion]: remembered, and the game's save looked at again. */
    fun chooseSaveFolder(id: String) = viewModelScope.launch {
        val q = _saveFolderQuestion.value ?: return@launch
        _saveFolderQuestion.value = null
        saves.answer(q, id)?.let { _messages.send(it) }
    }

    fun dismissSaveFolderQuestion() { _saveFolderQuestion.value = null }

    fun retry(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.retry(it) } }
    fun cancel(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.cancel(it) } }
    fun pause(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.pause(it) } }
    fun resume(game: GameItem) = viewModelScope.launch { game.jobId?.let { downloads.resume(it) } }

    suspend fun files(romId: Int) = catalog.filesOf(romId)
}
