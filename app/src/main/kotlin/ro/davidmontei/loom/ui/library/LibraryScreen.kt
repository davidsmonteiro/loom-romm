package ro.davidmontei.loom.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.saves.emulatorName
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.ButtonHint
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomChip
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.LoomProgressBar
import ro.davidmontei.loom.ui.components.LoomTextField
import ro.davidmontei.loom.ui.components.ScreenScaffold
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.ago
import ro.davidmontei.loom.ui.components.dateTime
import ro.davidmontei.loom.ui.folders.FolderQuestion
import ro.davidmontei.loom.ui.folders.FolderQuestionDialog
import ro.davidmontei.loom.ui.input.PadAction
import ro.davidmontei.loom.ui.input.onPadAction
import ro.davidmontei.loom.ui.layout.WidthClass
import ro.davidmontei.loom.ui.layout.rememberLoomLayout
import ro.davidmontei.loom.ui.saves.ReplaceSaveDialog
import ro.davidmontei.loom.ui.saves.SaveFolderDialog
import ro.davidmontei.loom.ui.saves.SaveFolderOption
import ro.davidmontei.loom.ui.saves.SaveFolderQuestion
import ro.davidmontei.loom.ui.second.CompanionContent
import ro.davidmontei.loom.ui.second.GamePanel
import ro.davidmontei.loom.ui.second.PublishCompanion
import ro.davidmontei.loom.ui.second.secondScreenShowing
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular

/** Everything the library draws, so it can be rendered and driven in tests without Hilt. */
data class LibraryUiState(
    val platforms: List<PlatformSummary>,
    val selected: PlatformSummary?,
    val games: List<GameItem>,
    val filter: LibraryFilter,
    val query: String,
    val gridMode: Boolean,
    val header: LibraryHeader,
    val coverBase: HttpUrl?,
    val message: String? = null,
    val folderQuestion: FolderQuestion? = null,
    val saveFolderQuestion: SaveFolderQuestion? = null,
)

interface LibraryActions {
    fun selectPlatform(id: Int)
    fun cyclePlatform(step: Int)
    fun setFilter(filter: LibraryFilter)
    fun setQuery(query: String)
    fun toggleGrid()
    fun sync()
    fun download(game: GameItem)
    fun offload(game: GameItem)
    fun retry(game: GameItem)
    fun cancel(game: GameItem)
    /** The game's details opened: looks at where its save stands now, moving nothing. */
    fun lookAtSave(game: GameItem)
    /** Uploads the game's save if it changed on the Thor only. */
    fun uploadSave(game: GameItem)
    /** Puts RomM's save in place of the Thor's; asked only once the user said the emulator is closed. */
    fun restoreSave(game: GameItem)
    /** Opens the queue, on [focusJobId]'s row, or the save conflict of game [focusConflictRomId], when given. */
    fun openQueue(focusJobId: Long? = null, focusConflictRomId: Int? = null)
    fun openSettings()
    /** Pairs Loom with RomM again, after RomM rejected its token. */
    fun repair()
    /** The answer to [LibraryUiState.folderQuestion]. */
    fun chooseFolder(folder: String)
    fun showAllFolders()
    fun showSuggestedFolders()
    fun dismissFolderQuestion()
    /** Asks which of the emulator's save folders is [game]'s ([LibraryUiState.saveFolderQuestion]). */
    fun askSaveFolder(game: GameItem)
    /** Asks which of the two folders the emulator keeps [game]'s save in Loom syncs ([LibraryUiState.saveFolderQuestion]). */
    fun askSyncedFolder(game: GameItem)
    /** The answer to [LibraryUiState.saveFolderQuestion]: a [SaveFolderOption.id]. */
    fun chooseSaveFolder(id: String)
    fun dismissSaveFolderQuestion()
}

@Composable
fun LibraryScreen(
    openQueue: (focusJobId: Long?, focusConflictRomId: Int?) -> Unit,
    openSettings: () -> Unit,
    onRepair: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val platforms by vm.platforms.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val grid by vm.gridMode.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val coverBase by vm.coverBase.collectAsStateWithLifecycle()
    val folderQuestion by vm.folderQuestion.collectAsStateWithLifecycle()
    val saveFolderQuestion by vm.saveFolderQuestion.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { message = it; delay(3500); message = null } }

    val actions = remember(vm) {
        object : LibraryActions {
            override fun selectPlatform(id: Int) = vm.selectPlatform(id)
            override fun cyclePlatform(step: Int) = vm.cyclePlatform(step)
            override fun setFilter(filter: LibraryFilter) = vm.setFilter(filter)
            override fun setQuery(query: String) = vm.setQuery(query)
            override fun toggleGrid() = vm.toggleGrid()
            override fun sync() = vm.sync()
            override fun download(game: GameItem) { vm.download(game) }
            override fun offload(game: GameItem) { vm.offload(game) }
            override fun retry(game: GameItem) { vm.retry(game) }
            override fun cancel(game: GameItem) { vm.cancel(game) }
            override fun lookAtSave(game: GameItem) = vm.lookAtSave(game)
            override fun uploadSave(game: GameItem) { vm.uploadSave(game) }
            override fun restoreSave(game: GameItem) { vm.restoreSave(game) }
            override fun openQueue(focusJobId: Long?, focusConflictRomId: Int?) = openQueue(focusJobId, focusConflictRomId)
            override fun openSettings() = openSettings()
            override fun repair() = onRepair()
            override fun chooseFolder(folder: String) { vm.chooseFolder(folder) }
            override fun showAllFolders() { vm.showAllFolders() }
            override fun showSuggestedFolders() = vm.showSuggestedFolders()
            override fun dismissFolderQuestion() = vm.dismissFolderQuestion()
            override fun askSaveFolder(game: GameItem) { vm.askSaveFolder(game) }
            override fun askSyncedFolder(game: GameItem) { vm.askSyncedFolder(game) }
            override fun chooseSaveFolder(id: String) { vm.chooseSaveFolder(id) }
            override fun dismissSaveFolderQuestion() = vm.dismissSaveFolderQuestion()
        }
    }
    LibraryContent(
        LibraryUiState(platforms, selected, games, filter, query, grid, header, coverBase, message, folderQuestion, saveFolderQuestion), actions,
    )
}

private val libraryHints = listOf(
    ButtonHint("A", "Details"), ButtonHint("X", "Download / offload"), ButtonHint("Y", "Search"),
    ButtonHint("L1 R1", "Platform"), ButtonHint("Start", "Queue"), ButtonHint("Select", "Settings"),
)

@Composable
fun LibraryContent(state: LibraryUiState, actions: LibraryActions) {
    // The game X acts on: only while it has focus, and in its current state.
    var focusedId by remember { mutableStateOf<Int?>(null) }
    var detailsFor by remember { mutableStateOf<GameItem?>(null) }
    var confirmOffload by remember { mutableStateOf<GameItem?>(null) }
    var confirmRestore by remember { mutableStateOf<GameItem?>(null) }
    var searchEdits by remember { mutableIntStateOf(0) }
    var searchEditing by remember { mutableStateOf(false) }
    // The game last focused and its place, kept while the queue or settings is on top so B comes
    // back to it. If it has left the list meanwhile (its download finished under "Not on device"),
    // the game now in its place is on screen to take over. A new platform, filter, layout or search
    // starts from the first game again.
    val listing = arrayOf<Any?>(state.selected?.platform?.id, state.filter, state.gridMode, state.query)
    var lastGameId by rememberSaveable(*listing) { mutableStateOf<Int?>(null) }
    var lastGameIndex by rememberSaveable(*listing) { mutableIntStateOf(0) }
    // Where focus enters the games: the one last focused or the one now in its place, else the first.
    val entryGame = remember { FocusRequester() }
    val gamesFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    // Too narrow for the rail beside the games: the header names the platform, and A there lists them all.
    val layout = rememberLoomLayout()
    var pickingPlatform by remember { mutableStateOf(false) }
    val platformTitle = remember { FocusRequester() }
    // B in that list goes back to the name it was opened from; a new platform takes focus to its games.
    var backToPlatformTitle by remember { mutableIntStateOf(0) }
    LaunchedEffect(backToPlatformTitle) {
        if (backToPlatformTitle > 0) {
            delay(50) // until the list has gone: its focus trap refuses requests made from inside it
            runCatching { platformTitle.requestFocus() }
        }
    }

    // A wide window has room for the focused game's details beside the games: the focused one, or the one
    // last focused while focus is elsewhere (the search box, a dialog), as it is now: its download moves on
    // as it shows. They are to read only, as A still opens the details to act on the game.
    val shown = state.games.firstOrNull { it.id == (focusedId ?: lastGameId) }
    // A second screen, where there is one, shows the same game: the details beside then give way to it.
    PublishCompanion(shown?.let { CompanionContent.Game(it, state.selected?.platform?.displayName, state.coverBase) } ?: CompanionContent.Idle)
    val window = LocalWindowInfo.current.containerDpSize.width
    val besideWidth = if (layout.width == WidthClass.EXPANDED && !secondScreenShowing()) (window * 0.32f).coerceIn(280.dp, 420.dp) else 0.dp
    // The three filter chips need about the room the Thor's top screen gives the games, 558 dp, to leave
    // the search box room. With less (a phone on its side, with the details beside) they are one chip that
    // cycles, as on a narrow screen. The games get what the margins, the rail and the details leave.
    val gamesWidth = window - Space.xl * 2 - 210.dp - Space.l - if (besideWidth > 0.dp) besideWidth + Space.l else 0.dp
    val oneFilterChip = layout.compact || gamesWidth < 540.dp

    // X does what the details offer; where that's nothing to download or retry, the details say why.
    fun toggle(game: GameItem) = when (game.state) {
        InstallState.NOT_INSTALLED -> if (game.removedOnServer) detailsFor = game else actions.download(game)
        InstallState.INSTALLED -> confirmOffload = game
        InstallState.FAILED -> if (game.retryable) actions.retry(game) else detailsFor = game
        InstallState.QUEUED, InstallState.DOWNLOADING -> actions.openQueue(game.jobId)
    }

    // The library is the root: B never closes Loom (Home does). It clears a search, if there is one.
    BackHandler { if (state.query.isNotEmpty()) actions.setQuery("") }

    // Into the games when they first appear and whenever the platform, filter or layout changes,
    // but never while a search is being typed: results that vanish and come back as the query
    // changes must not pull focus out of the field. The box merely having focus doesn't count: at launch
    // it can take the window's first focus before the games arrive. On a narrow screen the filters are
    // one chip that A moves on: focus stays on it for the next press while the games change below.
    var filterFocused by remember { mutableStateOf(false) }
    InitialFocus(entryGame, state.selected?.platform?.id, state.filter, state.gridMode, state.games.isNotEmpty(),
        unless = { searchEditing || filterFocused })
    // A focused game that leaves the list takes focus with it before the game now in its place can
    // take over (Compose then picks the top-left card on screen), so that one asks once it can.
    val focusedLeft = focusedId != null && state.games.none { it.id == focusedId }
    LaunchedEffect(focusedLeft) { if (focusedLeft) runCatching { entryGame.requestFocus() } }

    // Focus coming into the screen from outside (a dialog's button that just went away) lands on
    // the games, and the grid restores the one last focused: the game the dialog was opened from.
    // Without this Compose enters the first group on screen, the platform rail.
    Box(Modifier.fillMaxSize().focusProperties { onEnter = { runCatching { gamesFocus.requestFocus() } } }.focusGroup().onPadAction { action ->
        when (action) {
            PadAction.X -> state.games.firstOrNull { it.id == focusedId }?.let(::toggle) != null
            PadAction.Y -> { searchEdits++; true }
            PadAction.L1 -> { actions.cyclePlatform(-1); true }
            PadAction.R1 -> { actions.cyclePlatform(+1); true }
            PadAction.START -> { actions.openQueue(); true }
            PadAction.SELECT -> { actions.openSettings(); true }
        }
    }) {
        ScreenScaffold(
            title = state.selected?.platform?.displayName ?: "Library",
            hints = libraryHints,
            actions = { HeaderActions(state, actions, layout.compact) },
            titleContent = if (!layout.compact) null else {
                { PlatformTitle(state, onClick = { pickingPlatform = true }, Modifier.focusRequester(platformTitle)) }
            },
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                if (!layout.compact) PlatformRail(state, actions, Modifier.width(210.dp).fillMaxHeight().focusRequester(railFocus))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        LoomTextField(
                            value = state.query, onValueChange = actions::setQuery, label = null,
                            placeholder = "Search", modifier = Modifier.weight(1f), tag = "search", editRequest = searchEdits,
                            onEditingChange = { searchEditing = it },
                            onImeAction = { runCatching { entryGame.requestFocus() } },
                        )
                        if (oneFilterChip) {
                            val next = LibraryFilter.entries[(state.filter.ordinal + 1) % LibraryFilter.entries.size]
                            LoomChip(state.filter.label, state.filter != LibraryFilter.ALL, { actions.setFilter(next) },
                                Modifier.onFocusChanged { filterFocused = it.isFocused }.testTag("filter"))
                        } else {
                            LibraryFilter.entries.forEach { f ->
                                LoomChip(f.label, state.filter == f, { actions.setFilter(f) })
                            }
                        }
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LoomColors.Accent) }
                    SyncError(state.header.sync, actions)
                    Games(state, entryGame, lastGameId, lastGameIndex, gamesFocus, onFocus = { game, focused ->
                        if (focused) {
                            focusedId = game.id
                            lastGameId = game.id
                            lastGameIndex = state.games.indexOfFirst { it.id == game.id }.coerceAtLeast(0)
                        } else if (focusedId == game.id) focusedId = null
                    }, onOpen = { detailsFor = it }, actions = actions)
                }
                if (besideWidth > 0.dp) {
                    Box(
                        Modifier.width(besideWidth).fillMaxHeight()
                            .background(LoomColors.Surface, RoundedCornerShape(16.dp)).padding(Space.l).testTag("details-beside"),
                    ) {
                        shown?.let { GamePanel(it, state.selected?.platform?.displayName, state.coverBase, Modifier.fillMaxSize()) }
                    }
                }
            }
        }

        detailsFor?.let { game ->
            GameDialog(
                game = state.games.firstOrNull { it.id == game.id } ?: game,
                platform = state.selected,
                onDismiss = { detailsFor = null },
                onDownload = { actions.download(it); detailsFor = null },
                onOffload = { confirmOffload = it; detailsFor = null },
                onRetry = { actions.retry(it); detailsFor = null },
                onCancel = { actions.cancel(it); detailsFor = null },
                onLookAtSave = actions::lookAtSave,
                onUploadSave = { actions.uploadSave(it); detailsFor = null },
                onRestoreSave = { confirmRestore = it; detailsFor = null },
                onChooseSave = { actions.openQueue(focusConflictRomId = game.id); detailsFor = null },
                onChooseSaveFolder = { actions.askSaveFolder(it); detailsFor = null },
                onChooseSyncedFolder = { actions.askSyncedFolder(it); detailsFor = null },
            )
        }
        confirmRestore?.let { game ->
            ReplaceSaveDialog(
                title = "Restore ${game.title}'s save from RomM?",
                emulator = emulatorName(game.save?.emulator),
                confirm = "Restore",
                onConfirm = { actions.restoreSave(game); confirmRestore = null },
                onDismiss = { confirmRestore = null },
            )
        }
        confirmOffload?.let { game ->
            LoomDialog(
                title = "Remove ${game.title} from this device?",
                onDismiss = { confirmOffload = null },
                body = {
                    Text(
                        buildString {
                            append("Frees ${formatBytes(game.sizeBytes)}. It stays in your library and can be downloaded again with one press.")
                            // Not "Loom didn't download it": after Loom's data is lost, games it installed are found again.
                            if (game.adopted) {
                                append("\n\nLoom found this copy already on the SD card, at ${game.installPath}")
                                append(if (game.matchedByName) ", and matched it to this game by its name only." else ".")
                            }
                            append("\nSaves stored by your emulator aren't touched.")
                        },
                        style = MaterialTheme.typography.bodyLarge, color = LoomColors.TextMuted,
                    )
                },
                buttons = { first ->
                    LoomButton("Keep it", onClick = { confirmOffload = null }, modifier = Modifier.focusRequester(first))
                    LoomButton("Remove", primary = true, destructive = true, onClick = { actions.offload(game); confirmOffload = null })
                },
            )
        }
        // The question can arrive after another dialog opened (the card scan takes a moment): it takes over.
        LaunchedEffect(state.folderQuestion != null) {
            if (state.folderQuestion != null) { detailsFor = null; confirmOffload = null; confirmRestore = null; pickingPlatform = false }
        }
        if (pickingPlatform) {
            PlatformPicker(
                state.platforms, state.selected?.platform?.id, layout.tallEnough,
                onPick = { id ->
                    pickingPlatform = false
                    // The platform already shown changes nothing, so focus goes back as from B.
                    if (id == state.selected?.platform?.id) backToPlatformTitle++ else actions.selectPlatform(id)
                },
                onDismiss = { pickingPlatform = false; backToPlatformTitle++ },
            )
        }
        state.folderQuestion?.let {
            FolderQuestionDialog(it, actions::chooseFolder, actions::showAllFolders, actions::showSuggestedFolders, actions::dismissFolderQuestion)
        }
        state.saveFolderQuestion?.let { SaveFolderDialog(it, actions::chooseSaveFolder, actions::dismissSaveFolderQuestion) }
    }
}

/** Where the library's sync stands, in a few words: the header has room for no more. */
private fun syncStatus(header: LibraryHeader): String {
    val sync = header.sync
    return when {
        sync.running && sync.total != null -> "Syncing ${sync.fetched} of ${sync.total}"
        sync.running -> "Syncing…"
        sync.needsPairing -> "Not paired"
        sync.error != null -> "Sync failed" // the reason has more room above the games
        header.lastSyncMs != null -> "Synced ${ago(header.lastSyncMs)}"
        else -> "Not synced yet"
    }
}

@Composable
private fun HeaderActions(state: LibraryUiState, actions: LibraryActions, compact: Boolean) {
    // A slot of its own width, in a row as tall as the buttons: the note that RomM can't be reached
    // appears above the status without moving the title, the buttons or the games. On a narrow
    // screen the status is under the platform's name instead.
    if (!compact) {
        Column(Modifier.padding(end = Space.s).width(220.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.header.offline) {
                Text(OFFLINE_NOTE, style = MaterialTheme.typography.labelSmall, color = LoomColors.Accent, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("offline"))
            }
            Text(syncStatus(state.header), style = MaterialTheme.typography.labelMedium.tabular(),
                color = if (state.header.sync.error != null) LoomColors.Error else LoomColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    IconAction(Icons.Outlined.Sync, "Sync library", actions::sync)
    IconAction(if (state.gridMode) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, "Grid or list", actions::toggleGrid)
    IconAction(Icons.Outlined.Download, "Queue (${state.header.activeDownloads})", actions::openQueue, badge = state.header.activeDownloads)
    IconAction(Icons.Outlined.Settings, "Settings", actions::openSettings)
}

/** The library's note while RomM can't be reached: what's shown is the copy kept on the Thor. */
const val OFFLINE_NOTE = "Offline · showing your saved library"

/**
 * The header on a screen too narrow for the platform rail: the platform's name, which A turns into
 * the list of platforms, over where the sync stands. Offline, that line starts with a sign saying so.
 */
@Composable
private fun RowScope.PlatformTitle(state: LibraryUiState, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier.weight(1f).padding(end = Space.s)
            .clickable(remember { MutableInteractionSource() }, FocusRingIndication(cornerRadius = 12.dp), enabled = state.platforms.isNotEmpty(),
                role = Role.Button, onClickLabel = "Choose a platform", onClick = onClick)
            .padding(horizontal = Space.s, vertical = Space.xs)
            .testTag("platform-title"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.selected?.platform?.displayName ?: "Library", style = MaterialTheme.typography.titleMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (state.platforms.isNotEmpty()) Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = LoomColors.TextMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            // A phone has no room for the words beside the status: a cloud struck through, the usual sign, says it.
            if (state.header.offline) {
                Icon(Icons.Outlined.CloudOff, contentDescription = OFFLINE_NOTE, tint = LoomColors.Accent,
                    modifier = Modifier.size(16.dp).testTag("offline"))
            }
            Text(syncStatus(state.header), style = MaterialTheme.typography.labelMedium.tabular(),
                color = if (state.header.sync.error != null) LoomColors.Error else LoomColors.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Why the last sync failed, in full, and the way out when RomM no longer accepts Loom's token. */
@Composable
private fun SyncError(sync: CatalogSyncStatus, actions: LibraryActions) {
    val error = sync.error ?: return
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        // The button comes first, so Up from the first game lands on it.
        if (sync.needsPairing) LoomButton("Pair again", onClick = actions::repair)
        Text(error, style = MaterialTheme.typography.bodyMedium, color = LoomColors.Error, modifier = Modifier.weight(1f).testTag("sync-error"))
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, badge: Int = 0) {
    Box(
        Modifier.padding(start = Space.xs).size(44.dp)
            .clickable(remember { MutableInteractionSource() }, FocusRingIndication(cornerRadius = 24.dp), role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = LoomColors.TextMuted)
        if (badge > 0) {
            Box(Modifier.align(Alignment.TopEnd).background(LoomColors.Accent, RoundedCornerShape(50)).padding(horizontal = 5.dp)) {
                Text("$badge", style = MaterialTheme.typography.labelSmall.tabular(), color = LoomColors.OnAccent)
            }
        }
    }
}

@Composable
private fun PlatformRail(state: LibraryUiState, actions: LibraryActions, modifier: Modifier) {
    val listState = rememberLazyListState()
    val selectedItem = remember { FocusRequester() }
    LazyColumn(
        // Coming into the rail from the grid lands on the selected platform, not the nearest one.
        modifier.focusProperties { onEnter = { runCatching { selectedItem.requestFocus() } } }.testTag("platform-rail"),
        state = listState,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        items(state.platforms, key = { it.platform.id }) { p ->
            val selected = p.platform.id == state.selected?.platform?.id
            PlatformItem(p, selected, { actions.selectPlatform(p.platform.id) }, if (selected) Modifier.focusRequester(selectedItem) else Modifier)
        }
    }
    // Keep the selected platform in view as L1/R1 move through the list.
    LaunchedEffect(state.selected?.platform?.id) {
        val index = state.platforms.indexOfFirst { it.platform.id == state.selected?.platform?.id }
        if (index >= 0) listState.animateScrollToItem(index)
    }
}

@Composable
private fun Games(
    state: LibraryUiState,
    entryGame: FocusRequester,
    lastGameId: Int?,
    lastGameIndex: Int,
    gamesFocus: FocusRequester,
    onFocus: (GameItem, Boolean) -> Unit,
    onOpen: (GameItem) -> Unit,
    actions: LibraryActions,
) {
    if (state.platforms.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("Your library is empty here.", style = MaterialTheme.typography.titleMedium)
            Text("Sync with RomM to see your games.", color = LoomColors.TextMuted)
            LoomButton("Sync now", onClick = actions::sync, primary = true, modifier = Modifier.focusRequester(entryGame))
        }
        InitialFocus(entryGame)
        return
    }
    if (state.games.isEmpty()) {
        Text(
            when {
                state.query.isNotBlank() -> "No games match “${state.query}”."
                state.filter == LibraryFilter.ON_DEVICE -> "No games from this platform are on the device yet. Press X on a game to download it."
                else -> "No games to show."
            },
            color = LoomColors.TextMuted,
        )
        return
    }
    val entryId = (state.games.firstOrNull { it.id == lastGameId } ?: state.games[lastGameIndex.coerceAtMost(state.games.lastIndex)]).id
    // Back to the top for a new platform, in either layout, but not when coming back from another
    // screen: the saved position still shows the game the user left.
    var scrolledFor by rememberSaveable { mutableStateOf<Int?>(null) }
    val platformId = state.selected?.platform?.id
    if (state.gridMode) {
        val gridState = rememberLazyGridState()
        LaunchedEffect(platformId) {
            if (platformId != scrolledFor) gridState.scrollToItem(0)
            scrolledFor = platformId
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().focusRequester(gamesFocus).focusRestorer(entryGame).testTag("game-grid"),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            items(state.games, key = { it.id }) { game ->
                GameCard(
                    game, state.coverBase,
                    modifier = if (game.id == entryId) Modifier.focusRequester(entryGame) else Modifier,
                    onFocus = onFocus, onClick = onOpen,
                )
            }
        }
    } else {
        val listState = rememberLazyListState()
        LaunchedEffect(platformId) {
            if (platformId != scrolledFor) listState.scrollToItem(0)
            scrolledFor = platformId
        }
        LazyColumn(
            Modifier.fillMaxSize().focusRequester(gamesFocus).focusRestorer(entryGame).testTag("game-list"),
            state = listState,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(state.games, key = { it.id }) { game ->
                GameRowItem(game, state.coverBase,
                    modifier = if (game.id == entryId) Modifier.focusRequester(entryGame) else Modifier,
                    onFocus = onFocus, onClick = onOpen)
            }
        }
    }
}

@Composable
private fun GameCard(game: GameItem, coverBase: HttpUrl?, modifier: Modifier, onFocus: (GameItem, Boolean) -> Unit, onClick: (GameItem) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocus(game, focused) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "card-scale")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interaction, FocusRingIndication(cornerRadius = 14.dp), role = Role.Button, onClickLabel = "Details") { onClick(game) }
            .testTag("game-${game.id}"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(LoomColors.SurfaceRaised).testTag("cover-${game.id}")) {
            Cover(game, coverBase, Modifier.fillMaxSize())
            StateOverlay(game, Modifier.align(Alignment.BottomCenter))
        }
        Text(game.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) LoomColors.Text else LoomColors.TextMuted)
    }
}

@Composable
private fun GameRowItem(game: GameItem, coverBase: HttpUrl?, modifier: Modifier, onFocus: (GameItem, Boolean) -> Unit, onClick: (GameItem) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocus(game, focused) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().background(if (focused) LoomColors.SurfaceHighest else LoomColors.Surface, shape)
            .clickable(interaction, FocusRingIndication(cornerRadius = 12.dp), role = Role.Button) { onClick(game) }
            .padding(Space.s).testTag("game-${game.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Box(Modifier.size(width = 42.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(LoomColors.SurfaceRaised).testTag("cover-${game.id}")) {
            Cover(game, coverBase, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(formatBytes(game.sizeBytes), game.regions.takeIf { it.isNotEmpty() }?.joinToString(), game.stateDetail).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            game.progress?.let { LoomProgressBar(it, Modifier.padding(top = Space.xs), height = 4.dp) }
        }
        StateBadge(game)
    }
}

@Composable
private fun StateOverlay(game: GameItem, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs), horizontalAlignment = Alignment.End) {
        StateBadge(game)
        if (game.progress != null) LoomProgressBar(game.progress, height = 5.dp)
    }
}

@Composable
private fun GameDialog(
    game: GameItem,
    platform: PlatformSummary?,
    onDismiss: () -> Unit,
    onDownload: (GameItem) -> Unit,
    onOffload: (GameItem) -> Unit,
    onRetry: (GameItem) -> Unit,
    onCancel: (GameItem) -> Unit,
    onLookAtSave: (GameItem) -> Unit,
    onUploadSave: (GameItem) -> Unit,
    onRestoreSave: (GameItem) -> Unit,
    onChooseSave: () -> Unit,
    onChooseSaveFolder: (GameItem) -> Unit,
    onChooseSyncedFolder: (GameItem) -> Unit,
) {
    // Where the save stands now, not at the last background run: looked at once as the details open
    // (or once the game is installed while they're open), never on a mere recomposition.
    val looksAtSave = game.state == InstallState.INSTALLED && game.save?.supported == true
    LaunchedEffect(game.id, looksAtSave) { if (looksAtSave) onLookAtSave(game) }
    // A save button can go while it has focus (a background sync changing the save's state), which
    // would leave nothing focused in the dialog: focus then goes back to its first button.
    val firstButton = remember { FocusRequester() }
    var focusedSaveAction by remember { mutableStateOf<String?>(null) }
    val saveActions = game.save?.let { saveButtons(game, it, onUploadSave, onRestoreSave, onChooseSave, onChooseSaveFolder, onChooseSyncedFolder) }
        .orEmpty()
    val saveLabels = saveActions.map { it.label }
    LaunchedEffect(saveLabels) {
        if (focusedSaveAction != null && focusedSaveAction !in saveLabels) {
            focusedSaveAction = null
            runCatching { firstButton.requestFocus() }
        }
    }
    // On a narrow screen the buttons take the dialog's width: how the look goes is said under the save's line.
    val compact = rememberLoomLayout().compact
    LoomDialog(
        title = game.title,
        onDismiss = onDismiss,
        body = {
            // A line more for the save on a narrow screen, so the reason a folder is asked for is there in full.
            // Without save buttons below it, a reason has room in full: what to set in melonDS or PPSSPP.
            GameFacts(game, platform?.platform?.displayName, saveLines = if (saveActions.isEmpty()) 4 else if (compact) 3 else 2) {
                // A line kept for it while the save is looked at, so its words coming and going move nothing.
                if (compact && looksAtSave) {
                    Text(lookLine(game.save?.look), style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("save-look"))
                }
                if (saveActions.isNotEmpty()) {
                    // On one line where they fit, as on the Thor's top screen; wrapped on a narrower one.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        saveActions.forEach { action ->
                            LoomButton(action.label, action.onClick,
                                Modifier.onFocusChanged { if (it.isFocused) focusedSaveAction = action.label })
                        }
                    }
                }
            }
        },
        buttons = { first ->
            // Each button is drawn from one place whatever the state, so a download finishing or
            // failing while the details are open relabels the focused button instead of replacing
            // it: a new one would leave focus on the game behind the dialog. They are chosen outside
            // composition, because branches there (each lambda in a composable is remembered) can
            // still move the buttons to a new place in it.
            val (main, other) = detailsButtons(game, onDismiss, onDownload, onOffload, onRetry, onCancel)
            // How the look at the save is going, left of the buttons, which set the row's height:
            // its words coming and going move nothing.
            if (!compact) {
                Text(lookLine(game.save?.look), style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).align(Alignment.CenterVertically).testTag("save-look"))
            }
            val notASave = Modifier.onFocusChanged { if (it.isFocused) focusedSaveAction = null }
            LoomButton(main.label, main.onClick, Modifier.focusRequester(first).focusRequester(firstButton).then(notASave), primary = main.primary)
            other?.let { LoomButton(it.label, it.onClick, notASave, destructive = it.destructive) }
            // Where Cancel was: the move into the ROMs folder can't be stopped half way, so it says what's happening.
            if (game.finishing) {
                Text("Finishing…", style = MaterialTheme.typography.labelLarge, color = LoomColors.TextMuted,
                    modifier = Modifier.align(Alignment.CenterVertically).testTag("details-state"))
            }
            // The second button can go too (a failure retrying can't fix), taking focus with it: back to the first.
            InitialFocus(first, other == null, delayMs = 0)
        },
    )
}

/**
 * What the details offer for an installed game's save: nothing where Loom doesn't sync the
 * platform's saves, the question of its save folder while Loom can't tell it, or of which of its
 * two folders to sync, the queue for a conflict, else an upload when it could send something, a
 * restore when RomM's copy is newer or the Thor's is gone, and a new answer when the user chose
 * the folder ([chooseFolder] for the title folder, which also asks the other question again,
 * else [chooseSynced]).
 */
private fun saveButtons(
    game: GameItem,
    save: GameSave,
    upload: (GameItem) -> Unit,
    restore: (GameItem) -> Unit,
    choose: () -> Unit,
    chooseFolder: (GameItem) -> Unit,
    chooseSynced: (GameItem) -> Unit,
): List<DialogAction> {
    val change = when {
        save.folderChosen -> DialogAction("Change save folder") { chooseFolder(game) }
        save.syncedFolderChosen -> DialogAction("Change save folder") { chooseSynced(game) }
        else -> null
    }
    return when {
        !save.supported -> emptyList()
        save.state == SaveSyncState.NEEDS_FOLDER -> listOf(DialogAction("Choose save folder") { chooseFolder(game) })
        save.state == SaveSyncState.MOVED -> listOf(DialogAction("Choose save folder") { chooseSynced(game) })
        // A wrong pick of the save folder often shows up as a conflict: the pick can be changed from there too.
        save.state == SaveSyncState.CONFLICT -> listOfNotNull(DialogAction("Choose which to keep") { choose() }, change)
        else -> listOfNotNull(
            DialogAction("Upload save") { upload(game) }.takeIf { uploadCouldSend(save) },
            DialogAction("Restore server save") { restore(game) }
                .takeIf { save.state == SaveSyncState.SERVER_NEWER || save.state == SaveSyncState.LOCAL_MISSING },
            change,
        )
    }
}

/**
 * Whether Upload save could send the Thor's save (Loom uploads only a save that changed while RomM's
 * didn't): it is newer, or Loom doesn't know, not having looked yet or having failed to. In sync,
 * none yet, newer on RomM, missing on the Thor or not synced, an upload sends nothing. With automatic
 * upload off, though, Loom only knows how a save stood when last asked, so Upload save, which looks
 * again, stays; but not where RomM's save is newer, as a change on the Thor since makes a conflict.
 */
private fun uploadCouldSend(save: GameSave): Boolean = when (save.state) {
    null, SaveSyncState.LOCAL_NEWER, SaveSyncState.WRITING, SaveSyncState.ERROR -> true
    SaveSyncState.SERVER_NEWER, SaveSyncState.CONFLICT, SaveSyncState.NEEDS_FOLDER, SaveSyncState.MOVED -> false
    SaveSyncState.IN_SYNC, SaveSyncState.NO_SAVE, SaveSyncState.LOCAL_MISSING, SaveSyncState.UNSUPPORTED -> !save.autoUpload
}

private class DialogAction(val label: String, val primary: Boolean = false, val destructive: Boolean = false, val onClick: () -> Unit)

/** The details' first button (which has focus) and optional second one, for the game's state. */
private fun detailsButtons(
    game: GameItem,
    close: () -> Unit,
    download: (GameItem) -> Unit,
    offload: (GameItem) -> Unit,
    retry: (GameItem) -> Unit,
    cancel: (GameItem) -> Unit,
): Pair<DialogAction, DialogAction?> {
    val closeButton = DialogAction("Close", onClick = close)
    val removeFromQueue = DialogAction("Remove from queue") { cancel(game) }
    return when (game.state) {
        InstallState.NOT_INSTALLED -> Pair(if (game.removedOnServer) closeButton else DialogAction("Download", primary = true) { download(game) }, null)
        InstallState.INSTALLED -> Pair(closeButton, DialogAction("Remove from device", destructive = true) { offload(game) })
        // Retrying a failure that retrying can't fix is refused, so it isn't offered.
        InstallState.FAILED -> if (game.retryable) Pair(DialogAction("Retry", primary = true) { retry(game) }, removeFromQueue)
            else Pair(removeFromQueue, null)
        // Not while it's being moved into place: the engine can't stop that half way, so Cancel would do nothing.
        InstallState.QUEUED, InstallState.DOWNLOADING ->
            Pair(closeButton, DialogAction("Cancel download", destructive = true) { cancel(game) }.takeUnless { game.finishing })
    }
}
