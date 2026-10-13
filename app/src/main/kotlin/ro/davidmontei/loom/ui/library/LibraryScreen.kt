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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import ro.davidmontei.loom.data.catalog.CatalogSyncStatus
import ro.davidmontei.loom.data.catalog.CoverRef
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.ButtonHint
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomChip
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.LoomProgressBar
import ro.davidmontei.loom.ui.components.LoomRow
import ro.davidmontei.loom.ui.components.LoomTextField
import ro.davidmontei.loom.ui.components.ScreenScaffold
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.StatusBadge
import ro.davidmontei.loom.ui.components.gameCount
import ro.davidmontei.loom.ui.input.PadAction
import ro.davidmontei.loom.ui.input.onPadAction
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
    /** Opens the queue, on [focusJobId]'s row when given. */
    fun openQueue(focusJobId: Long? = null)
    fun openSettings()
    /** Pairs Loom with RomM again, after RomM rejected its token. */
    fun repair()
    /** The answer to [LibraryUiState.folderQuestion]. */
    fun chooseFolder(folder: String)
    fun showAllFolders()
    fun showSuggestedFolders()
    fun dismissFolderQuestion()
}

@Composable
fun LibraryScreen(
    openQueue: (focusJobId: Long?) -> Unit,
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
            override fun openQueue(focusJobId: Long?) = openQueue(focusJobId)
            override fun openSettings() = openSettings()
            override fun repair() = onRepair()
            override fun chooseFolder(folder: String) { vm.chooseFolder(folder) }
            override fun showAllFolders() { vm.showAllFolders() }
            override fun showSuggestedFolders() = vm.showSuggestedFolders()
            override fun dismissFolderQuestion() = vm.dismissFolderQuestion()
        }
    }
    LibraryContent(LibraryUiState(platforms, selected, games, filter, query, grid, header, coverBase, message, folderQuestion), actions)
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
    var searchEdits by remember { mutableIntStateOf(0) }
    var searchFocused by remember { mutableStateOf(false) }
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
    // changes must not pull focus out of the field.
    InitialFocus(entryGame, state.selected?.platform?.id, state.filter, state.gridMode, state.games.isNotEmpty(), unless = { searchFocused })
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
            actions = { HeaderActions(state, actions) },
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                PlatformRail(state, actions, Modifier.width(210.dp).fillMaxHeight().focusRequester(railFocus))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        LoomTextField(
                            value = state.query, onValueChange = actions::setQuery, label = null,
                            placeholder = "Search", modifier = Modifier.weight(1f).onFocusChanged { searchFocused = it.hasFocus }, tag = "search", editRequest = searchEdits,
                            onImeAction = { runCatching { entryGame.requestFocus() } },
                        )
                        LibraryFilter.entries.forEach { f ->
                            LoomChip(f.label, state.filter == f, { actions.setFilter(f) })
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
            if (state.folderQuestion != null) { detailsFor = null; confirmOffload = null }
        }
        state.folderQuestion?.let { FolderQuestionDialog(it, actions) }
    }
}

/**
 * Where a platform's games go, when the card has several folders that could be the one Cocoon reads.
 * Focus starts on the likeliest, so A takes it; "Another folder…" lists every folder in the ROMs folder.
 */
@Composable
private fun FolderQuestionDialog(q: FolderQuestion, actions: LibraryActions) {
    LoomDialog(
        title = "Where does Cocoon look for ${q.platformName} games?",
        // B from every folder goes back to the likely ones; from those it drops the download.
        onDismiss = if (q.allFolders != null) actions::showSuggestedFolders else actions::dismissFolderQuestion,
        body = {
            Text(
                if (q.allFolders == null) "Your card has more than one folder these games could go in. Loom remembers your " +
                    "answer; Settings › Platform folders can change it."
                else "Every folder in your ROMs folder.",
                style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted,
            )
            val folders = q.allFolders ?: q.options
            val grid = rememberLazyGridState()
            val suggested = remember { FocusRequester() }
            // After the dialog's own first focus: scroll the likeliest into view, then focus it. Again when
            // the pad is pressed after a tap (touch mode focuses nothing), before that press is replayed.
            val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
            LaunchedEffect(folders, keyboard) {
                val index = folders.indexOfFirst { it.name == q.suggested }.coerceAtLeast(0)
                grid.scrollToItem(index)
                delay(50)
                runCatching { suggested.requestFocus() }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = grid,
                modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp).testTag("folder-options"),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                items(folders, key = { it.name }) { folder ->
                    LoomRow(
                        title = "${folder.name}/",
                        subtitle = folder.systemName,
                        subtitleLines = 1,
                        dense = true,
                        trailing = {
                            Text(if (folder.games == 0) "empty" else gameCount(folder.games),
                                style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted)
                        },
                        onClick = { actions.chooseFolder(folder.name) },
                        modifier = (if (folder.name == q.suggested) Modifier.focusRequester(suggested) else Modifier).testTag("folder-${folder.name}"),
                    )
                }
            }
        },
        buttons = { first ->
            if (q.allFolders == null) LoomButton("Another folder…", actions::showAllFolders, Modifier.focusRequester(first))
            LoomButton("Cancel", actions::dismissFolderQuestion, if (q.allFolders == null) Modifier else Modifier.focusRequester(first))
        },
    )
}

@Composable
private fun HeaderActions(state: LibraryUiState, actions: LibraryActions) {
    val sync = state.header.sync
    val status = when {
        sync.running && sync.total != null -> "Syncing ${sync.fetched} of ${sync.total}"
        sync.running -> "Syncing…"
        sync.needsPairing -> "Not paired"
        sync.error != null -> "Sync failed" // the reason has more room above the games
        state.header.lastSyncMs != null -> "Synced ${ago(state.header.lastSyncMs)}"
        else -> "Not synced yet"
    }
    Text(status, style = MaterialTheme.typography.labelMedium.tabular(), color = if (sync.error != null) LoomColors.Error else LoomColors.TextFaint,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = Space.s).width(220.dp))
    IconAction(Icons.Outlined.Sync, "Sync library", actions::sync)
    IconAction(if (state.gridMode) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, "Grid or list", actions::toggleGrid)
    IconAction(Icons.Outlined.Download, "Queue (${state.header.activeDownloads})", actions::openQueue, badge = state.header.activeDownloads)
    IconAction(Icons.Outlined.Settings, "Settings", actions::openSettings)
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
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier.fillMaxWidth()
                    .then(if (selected) Modifier.focusRequester(selectedItem) else Modifier)
                    .background(if (selected) LoomColors.SurfaceHighest else LoomColors.Surface, shape)
                    .clickable(remember { MutableInteractionSource() }, FocusRingIndication(cornerRadius = 12.dp), role = Role.Tab) {
                        actions.selectPlatform(p.platform.id)
                    }
                    .padding(horizontal = Space.m, vertical = Space.s)
                    .testTag("platform-${p.platform.id}"),
            ) {
                Text(p.platform.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) LoomColors.Text else LoomColors.TextMuted)
                Text(
                    if (p.installedCount > 0) "${p.installedCount} of ${p.localRomCount} · ${formatBytes(p.installedBytes)}"
                    else gameCount(p.localRomCount),
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = if (p.installedCount > 0) LoomColors.Installed else LoomColors.TextFaint,
                )
            }
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
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp)).background(LoomColors.SurfaceRaised)) {
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
        Box(Modifier.size(width = 42.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(LoomColors.SurfaceRaised)) {
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
private fun Cover(game: GameItem, coverBase: HttpUrl?, modifier: Modifier) {
    val path = game.coverPath
    if (path == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(game.title.take(2).uppercase(), style = MaterialTheme.typography.headlineSmall, color = LoomColors.TextFaint)
        }
        return
    }
    AsyncImage(
        // Stored covers show offline too; the address is only for ones not fetched yet.
        model = ImageRequest.Builder(LocalContext.current).data(CoverRef(path, coverBase)).crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

@Composable
private fun StateOverlay(game: GameItem, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs), horizontalAlignment = Alignment.End) {
        StateBadge(game)
        if (game.progress != null) LoomProgressBar(game.progress, height = 5.dp)
    }
}

@Composable
private fun StateBadge(game: GameItem) {
    when (game.state) {
        InstallState.INSTALLED -> StatusBadge("On device")
        InstallState.DOWNLOADING -> StatusBadge(game.progress?.let { "${(it * 100).toInt()}%" } ?: (game.stateDetail ?: "Downloading"),
            color = LoomColors.Accent, onColor = LoomColors.OnAccent)
        InstallState.QUEUED -> StatusBadge(game.stateDetail ?: "Queued", color = LoomColors.SurfaceHighest, onColor = LoomColors.Text)
        InstallState.FAILED -> StatusBadge("Failed", color = LoomColors.Error, onColor = LoomColors.OnError)
        InstallState.NOT_INSTALLED -> if (game.removedOnServer) StatusBadge("Removed from RomM", color = LoomColors.SurfaceHighest, onColor = LoomColors.TextMuted)
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
) {
    LoomDialog(
        title = game.title,
        onDismiss = onDismiss,
        body = {
            val lines = buildList {
                platform?.let { add(it.platform.displayName) }
                add(formatBytes(game.sizeBytes) + (game.regions.takeIf { it.isNotEmpty() }?.let { " · ${it.joinToString()}" } ?: ""))
                when (game.state) {
                    InstallState.INSTALLED -> add("On this device at ${game.installPath}" + when {
                        game.matchedByName -> " (Loom found it there, by its name only)"
                        game.adopted -> " (Loom found it there)"
                        else -> ""
                    })
                    InstallState.QUEUED, InstallState.DOWNLOADING -> add(game.stateDetail ?: "Downloading")
                    InstallState.FAILED -> {
                        add(game.stateDetail ?: "The last download failed")
                        if (!game.retryable) add("Retrying won't help until this is fixed.")
                    }
                    InstallState.NOT_INSTALLED -> add(if (game.removedOnServer) "No longer on the RomM server" else "Not on this device")
                }
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = LoomColors.TextMuted) }
            game.progress?.let { LoomProgressBar(it) }
        },
        buttons = { first ->
            // Each button is drawn from one place whatever the state, so a download finishing or
            // failing while the details are open relabels the focused button instead of replacing
            // it: a new one would leave focus on the game behind the dialog. They are chosen outside
            // composition, because branches there (each lambda in a composable is remembered) can
            // still move the buttons to a new place in it.
            val (main, other) = detailsButtons(game, onDismiss, onDownload, onOffload, onRetry, onCancel)
            LoomButton(main.label, main.onClick, Modifier.focusRequester(first), primary = main.primary)
            other?.let { LoomButton(it.label, it.onClick, destructive = it.destructive) }
            // The second button can go too (a failure retrying can't fix), taking focus with it: back to the first.
            InitialFocus(first, other == null, delayMs = 0)
        },
    )
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
        InstallState.QUEUED, InstallState.DOWNLOADING -> Pair(closeButton, DialogAction("Cancel download", destructive = true) { cancel(game) })
    }
}

private fun ago(epochMs: Long): String {
    val minutes = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
