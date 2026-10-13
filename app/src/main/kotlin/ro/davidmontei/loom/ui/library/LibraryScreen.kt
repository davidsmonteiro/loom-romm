package ro.davidmontei.loom.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import ro.davidmontei.loom.data.catalog.CoverRef
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.ButtonHint
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomChip
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.LoomProgressBar
import ro.davidmontei.loom.ui.components.LoomTextField
import ro.davidmontei.loom.ui.components.ScreenScaffold
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.StatusBadge
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
    fun openQueue()
    fun openSettings()
}

@Composable
fun LibraryScreen(openQueue: () -> Unit, openSettings: () -> Unit, vm: LibraryViewModel = hiltViewModel()) {
    val platforms by vm.platforms.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val grid by vm.gridMode.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val coverBase by vm.coverBase.collectAsStateWithLifecycle()
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
            override fun openQueue() = openQueue()
            override fun openSettings() = openSettings()
        }
    }
    LibraryContent(LibraryUiState(platforms, selected, games, filter, query, grid, header, coverBase, message), actions)
}

private val libraryHints = listOf(
    ButtonHint("A", "Details"), ButtonHint("X", "Download / offload"), ButtonHint("Y", "Search"),
    ButtonHint("L1 R1", "Platform"), ButtonHint("Start", "Queue"), ButtonHint("Select", "Settings"),
)

@Composable
fun LibraryContent(state: LibraryUiState, actions: LibraryActions) {
    var focusedGame by remember { mutableStateOf<GameItem?>(null) }
    var detailsFor by remember { mutableStateOf<GameItem?>(null) }
    var confirmOffload by remember { mutableStateOf<GameItem?>(null) }
    val searchFocus = remember { FocusRequester() }
    val firstGame = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }

    fun toggle(game: GameItem) = when (game.state) {
        InstallState.NOT_INSTALLED -> actions.download(game)
        InstallState.INSTALLED -> confirmOffload = game
        InstallState.FAILED -> actions.retry(game)
        InstallState.QUEUED, InstallState.DOWNLOADING -> actions.openQueue()
    }

    Box(Modifier.fillMaxSize().onPadAction { action ->
        when (action) {
            PadAction.X -> focusedGame?.let(::toggle) != null
            PadAction.Y -> { runCatching { searchFocus.requestFocus() }; true }
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
                            placeholder = "Search", modifier = Modifier.weight(1f).focusRequester(searchFocus), tag = "search",
                            onImeAction = { runCatching { firstGame.requestFocus() } },
                        )
                        LibraryFilter.entries.forEach { f ->
                            LoomChip(f.label, state.filter == f, { actions.setFilter(f) })
                        }
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LoomColors.Accent) }
                    Games(state, firstGame, onFocus = { focusedGame = it }, onOpen = { detailsFor = it }, actions = actions)
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
                            if (game.adopted) append("\n\nLoom didn't download this copy; it was already on the SD card at ${game.installPath}.")
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
    }
}

@Composable
private fun HeaderActions(state: LibraryUiState, actions: LibraryActions) {
    val sync = state.header.sync
    val status = when {
        sync.running && sync.total != null -> "Syncing ${sync.fetched} of ${sync.total}"
        sync.running -> "Syncing…"
        sync.error != null -> sync.error
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
                    else "${p.localRomCount} games",
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
    firstGame: FocusRequester,
    onFocus: (GameItem) -> Unit,
    onOpen: (GameItem) -> Unit,
    actions: LibraryActions,
) {
    if (state.platforms.isEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text("Your library is empty here.", style = MaterialTheme.typography.titleMedium)
            Text("Sync with RomM to see your games.", color = LoomColors.TextMuted)
            LoomButton("Sync now", onClick = actions::sync, primary = true, modifier = Modifier.focusRequester(firstGame))
        }
        InitialFocus(firstGame)
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
    // Back into the grid with the first game focused whenever the platform or filter changes.
    InitialFocus(firstGame, state.selected?.platform?.id, state.filter, state.gridMode)

    if (state.gridMode) {
        val gridState = rememberLazyGridState()
        LaunchedEffect(state.selected?.platform?.id) { gridState.scrollToItem(0) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().focusRestorer(firstGame).testTag("game-grid"),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            items(state.games, key = { it.id }) { game ->
                GameCard(
                    game, state.coverBase,
                    modifier = if (game.id == state.games.first().id) Modifier.focusRequester(firstGame) else Modifier,
                    onFocus = onFocus, onClick = onOpen,
                )
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().focusRestorer(firstGame).testTag("game-list"),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            items(state.games, key = { it.id }) { game ->
                GameRowItem(game, state.coverBase,
                    modifier = if (game.id == state.games.first().id) Modifier.focusRequester(firstGame) else Modifier,
                    onFocus = onFocus, onClick = onOpen)
            }
        }
    }
}

@Composable
private fun GameCard(game: GameItem, coverBase: HttpUrl?, modifier: Modifier, onFocus: (GameItem) -> Unit, onClick: (GameItem) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus(game) }
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
private fun GameRowItem(game: GameItem, coverBase: HttpUrl?, modifier: Modifier, onFocus: (GameItem) -> Unit, onClick: (GameItem) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus(game) }
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
                    InstallState.INSTALLED -> add("On this device at ${game.installPath}" + if (game.adopted) " (already there before Loom)" else "")
                    InstallState.QUEUED, InstallState.DOWNLOADING -> add(game.stateDetail ?: "Downloading")
                    InstallState.FAILED -> add(game.stateDetail ?: "The last download failed")
                    InstallState.NOT_INSTALLED -> add(if (game.removedOnServer) "No longer on the RomM server" else "Not on this device")
                }
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = LoomColors.TextMuted) }
            game.progress?.let { LoomProgressBar(it) }
        },
        buttons = { first ->
            val f = Modifier.focusRequester(first)
            when (game.state) {
                InstallState.NOT_INSTALLED -> if (!game.removedOnServer) LoomButton("Download", { onDownload(game) }, f, primary = true)
                    else LoomButton("Close", onDismiss, f)
                InstallState.INSTALLED -> {
                    LoomButton("Close", onDismiss, f)
                    LoomButton("Remove from device", { onOffload(game) }, destructive = true)
                }
                InstallState.FAILED -> {
                    LoomButton("Retry", { onRetry(game) }, f, primary = true)
                    LoomButton("Remove from queue", { onCancel(game) })
                }
                InstallState.QUEUED, InstallState.DOWNLOADING -> {
                    LoomButton("Close", onDismiss, f)
                    LoomButton("Cancel download", { onCancel(game) }, destructive = true)
                }
            }
        },
    )
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
