package ro.davidmontei.loom.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.data.settings.CatalogRefresh
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.mapping.ArchivePolicy
import ro.davidmontei.loom.storage.mapping.FolderNames
import ro.davidmontei.loom.storage.mapping.MappingSource
import ro.davidmontei.loom.storage.mapping.MultiDiscLayout
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import ro.davidmontei.loom.storage.mapping.ROM_FILE_CATEGORIES
import ro.davidmontei.loom.storage.mapping.SystemFolder
import ro.davidmontei.loom.storage.saves.EdenProfile
import ro.davidmontei.loom.ui.components.ButtonHint
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.components.FolderNote
import ro.davidmontei.loom.ui.components.FolderPicker
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomChip
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.LoomRow
import ro.davidmontei.loom.ui.components.LoomSwitchRow
import ro.davidmontei.loom.ui.components.LoomTextField
import ro.davidmontei.loom.ui.components.ScreenScaffold
import ro.davidmontei.loom.ui.components.SectionHeader
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.StatusBadge
import ro.davidmontei.loom.ui.components.ago
import ro.davidmontei.loom.ui.components.dateTime
import ro.davidmontei.loom.ui.components.gameCount
import ro.davidmontei.loom.ui.components.padScroll
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular

/** Everything the settings screen can do, so it can be driven in tests without Hilt. */
interface SettingsActions {
    fun updateAddress(address: String)
    fun repair()
    fun signOut()
    fun fullResync()
    fun setCatalogRefresh(refresh: CatalogRefresh)
    fun setRefreshOnWifiOnly(wifiOnly: Boolean)
    fun setRomsRoot(dir: java.io.File)
    fun volumes(): List<VolumeInfo>
    fun saveOverride(entry: PlatformMappingEntry)
    fun resetOverride(match: PlatformMatch)
    fun exportMapping()
    fun importMapping()
    val exportPath: String
    val cocoonFolders: List<String>
    /** Which of [names] are folders on the card, with their game counts; null when Loom can't look. Off the main thread. */
    suspend fun foldersOnCard(names: List<String>): List<SystemFolder>?
    fun setConcurrency(n: Int)
    /** The automatic upload switch; the periodic scan follows it. */
    fun setAutoUploadSaves(on: Boolean)
    /** Azahar's user directory; null goes back to the 3DS system folder. */
    fun setAzaharFolder(dir: java.io.File?)
    /** Eden's save folder; null goes back to the ROMs folder's saves/Eden. */
    fun setEdenFolder(dir: java.io.File?)
    /** Whose saves Loom syncs when Eden has several profiles. */
    fun setEdenProfile(profile: String)
    fun setSwap(swap: Boolean)
    fun openBatterySettings()
    fun licenseText(): String
    fun fontLicense(): String
}

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(onBack: () -> Unit, onRepair: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { message = it; delay(4000); message = null } }
    LifecycleResumeEffect(Unit) { vm.refreshDevice(); onPauseOrDispose { } }
    val actions = remember(vm) {
        object : SettingsActions {
            override fun updateAddress(address: String) { vm.updateAddress(address) }
            override fun repair() = onRepair()
            override fun signOut() { vm.signOut() }
            override fun fullResync() = vm.fullResync()
            override fun setCatalogRefresh(refresh: CatalogRefresh) { vm.setCatalogRefresh(refresh) }
            override fun setRefreshOnWifiOnly(wifiOnly: Boolean) { vm.setRefreshOnWifiOnly(wifiOnly) }
            override fun setRomsRoot(dir: java.io.File) { vm.setRomsRoot(dir) }
            override fun volumes() = vm.mountedVolumes()
            override fun saveOverride(entry: PlatformMappingEntry) { vm.saveOverride(entry) }
            override fun resetOverride(match: PlatformMatch) { vm.resetOverride(match) }
            override fun exportMapping() { vm.exportMapping() }
            override fun importMapping() { vm.importMapping() }
            override val exportPath get() = vm.exportPath
            override val cocoonFolders get() = vm.cocoonFolders
            override suspend fun foldersOnCard(names: List<String>) = vm.foldersOnCard(names)
            override fun setConcurrency(n: Int) { vm.setConcurrency(n) }
            override fun setAutoUploadSaves(on: Boolean) { vm.setAutoUploadSaves(on) }
            override fun setAzaharFolder(dir: java.io.File?) { vm.setAzaharFolder(dir) }
            override fun setEdenFolder(dir: java.io.File?) { vm.setEdenFolder(dir) }
            override fun setEdenProfile(profile: String) { vm.setEdenProfile(profile) }
            override fun setSwap(swap: Boolean) { vm.setSwap(swap) }
            override fun openBatterySettings() {
                runCatching {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
                }
            }
            override fun licenseText() = vm.licenseText()
            override fun fontLicense() = vm.interLicense()
        }
    }
    SettingsContent(state, actions, message)
}

private val settingsHints = listOf(ButtonHint("A", "Select"), ButtonHint("B", "Back"))

@Composable
fun SettingsContent(state: SettingsUiState, actions: SettingsActions, message: String? = null) {
    // Saveable: "Pair again" opens pairing on top, and Settings comes back from saved state.
    var section by rememberSaveable { mutableStateOf(SettingsSection.SERVER) }
    var backFromPairing by rememberSaveable { mutableStateOf(false) }
    val sectionActions = remember(actions) {
        object : SettingsActions by actions {
            override fun repair() { backFromPairing = true; actions.repair() }
        }
    }
    val firstSection = remember { FocusRequester() }
    val sectionFocus = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    // Moving right from the section list lands on the section's first control, not the nearest one.
    val contentFirst = remember { FocusRequester() }
    val first = Modifier.focusRequester(contentFirst)
    InitialFocus(firstSection, unless = { backFromPairing })
    // A on a section opens it: focus moves to its first control once it is shown.
    var opened by remember { mutableIntStateOf(0) }
    LaunchedEffect(opened) { if (opened > 0) runCatching { contentFirst.requestFocus() } }
    // B inside a section undoes that A, back to the section in the list (as Left does); from there it leaves Settings.
    var contentHasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = contentHasFocus) { runCatching { sectionFocus.getValue(section).requestFocus() } }

    ScreenScaffold(title = "Settings", hints = settingsHints) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            LazyColumn(
                // Coming back from a section (Left) lands on that section, not whichever is level with the control left behind.
                Modifier.width(230.dp).fillMaxHeight()
                    .focusProperties { onEnter = { runCatching { sectionFocus.getValue(section).requestFocus() } } }
                    .testTag("settings-sections"),
                contentPadding = PaddingValues(8.dp),
                // Eight sections fit the Thor's screen only this close together.
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                items(SettingsSection.entries) { s ->
                    LoomRow(
                        title = s.title, selected = s == section, dense = true, onClick = { section = s; opened++ },
                        modifier = (if (s == SettingsSection.SERVER) Modifier.focusRequester(firstSection) else Modifier)
                            .focusRequester(sectionFocus.getValue(s)).testTag("section-${s.name}"),
                    )
                }
            }
            Column(
                // Only a move (Right) is redirected: focus given back to a control after a dialog closes stays on it.
                Modifier.weight(1f).fillMaxHeight()
                    .onFocusChanged { contentHasFocus = it.hasFocus }
                    .focusProperties { onEnter = { if (requestedFocusDirection != FocusDirection.Enter) runCatching { contentFirst.requestFocus() } } }
                    .verticalScroll(rememberScrollState()).padding(8.dp).testTag("settings-content"),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                message?.let { Text(it, color = LoomColors.Accent, style = MaterialTheme.typography.bodyMedium) }
                when (section) {
                    SettingsSection.SERVER -> ServerSection(state, sectionActions, first, backFromPairing) { backFromPairing = false }
                    SettingsSection.LIBRARY -> LibrarySyncSection(state, sectionActions, first, backFromPairing) { backFromPairing = false }
                    SettingsSection.STORAGE -> StorageSection(state, actions, first)
                    SettingsSection.MAPPING -> MappingSection(state, actions, first)
                    SettingsSection.DOWNLOADS -> DownloadsSection(state, actions, first)
                    SettingsSection.SAVES -> SaveSyncSection(state, sectionActions, first, backFromPairing) { backFromPairing = false }
                    SettingsSection.CONTROLS -> Box(first) {
                        LoomSwitchRow("Swap A and B", state.settings?.swapConfirmBack == true, actions::setSwap,
                            subtitle = "For Nintendo-style pads: confirm with the right face button and go back with the bottom one.")
                    }
                    SettingsSection.ABOUT -> AboutSection(state, actions, first)
                }
            }
        }
    }
}

/**
 * Puts focus back on what opened a dialog (or the folder browser) once it has closed. The focused
 * button leaves with it, and Compose would start again from the top-left: the section list.
 */
@Composable
private fun ReturnFocusOnClose(open: Boolean, opener: () -> FocusRequester?) {
    var wasOpen by remember { mutableStateOf(false) }
    val target by rememberUpdatedState(opener)
    LaunchedEffect(open) {
        if (open) wasOpen = true
        else if (wasOpen) {
            delay(50) // until the dialog has gone: its focus trap refuses requests made from inside it
            runCatching { target()?.requestFocus() }
        }
    }
}

/**
 * A section's heading and explanation together with its first controls. Focus arriving on those
 * controls shows the whole block: moving focus only scrolls as far as the control itself, and with
 * nothing focusable above it the text could never be brought back with the pad.
 */
@Composable
private fun SectionTop(content: @Composable ColumnScope.() -> Unit) {
    val top = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.bringIntoViewRequester(top).onFocusChanged { if (it.hasFocus) scope.launch { top.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(Space.m),
        content = content,
    )
}

@Composable
private fun ServerSection(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    backFromPairing: Boolean = false,
    onBackFromPairing: () -> Unit = {},
) {
    val first = modifier
    val server = state.settings?.server
    var address by remember(server) { mutableStateOf(server?.url?.toString().orEmpty()) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmPairAgain by remember { mutableStateOf(false) }
    var signedOut by remember { mutableStateOf(false) }
    val pairAgainFocus = remember { FocusRequester() }
    val signOutFocus = remember { FocusRequester() }
    // Signing out removes its own button: focus goes to Pair again, the next thing to do.
    ReturnFocusOnClose(confirmSignOut) { if (signedOut) pairAgainFocus else signOutFocus }
    ReturnFocusOnClose(confirmPairAgain) { pairAgainFocus }
    // Back from pairing again (B, or a new token that works), Settings is composed afresh: focus where
    // the user left, on Pair again. Read only on arrival, so setting the flag on the way out does nothing.
    LaunchedEffect(Unit) {
        if (backFromPairing) {
            delay(50) // as InitialFocus: the button has to be laid out first
            runCatching { pairAgainFocus.requestFocus() }
            onBackFromPairing()
        }
    }
    SectionHeader("Server")
    Text(
        state.settings?.serverVersion?.let { "RomM $it (Loom is built against RomM ${state.contractVersion})" } ?: "Not connected",
        color = LoomColors.TextMuted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Bottom) {
        LoomTextField(address, { address = it }, "Address", keyboardType = KeyboardType.Uri, modifier = first.weight(1f),
            onImeAction = { actions.updateAddress(address) })
        LoomButton("Save", onClick = { actions.updateAddress(address) })
    }
    Text("Local, Tailscale or public: Loom checks it answers as RomM before saving, and queued downloads continue from it.",
        style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted)

    SectionHeader("Pairing")
    val creds = state.credentials
    Text(
        if (creds != null) "Paired as ${creds.username}" + (creds.expiresAt?.let { " · token expires $it" } ?: " · token doesn't expire")
        else "Not paired",
        color = if (creds != null) LoomColors.Installed else LoomColors.Error,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        LoomButton("Pair again", onClick = { if (creds != null) confirmPairAgain = true else actions.repair() },
            modifier = Modifier.focusRequester(pairAgainFocus))
        if (creds != null) {
            LoomButton("Sign out", onClick = { confirmSignOut = true }, destructive = true, modifier = Modifier.focusRequester(signOutFocus))
        }
    }
    if (confirmPairAgain) {
        LoomDialog(title = "Pair with RomM again?", onDismiss = { confirmPairAgain = false },
            body = { Text("Loom keeps its current token until RomM accepts a new one. B on the pairing screen comes back here.", color = LoomColors.TextMuted) },
            buttons = { f ->
                LoomButton("Cancel", { confirmPairAgain = false }, Modifier.focusRequester(f))
                LoomButton("Pair again", { confirmPairAgain = false; actions.repair() }, primary = true)
            })
    }
    if (confirmSignOut) {
        LoomDialog(title = "Sign out of RomM?", onDismiss = { confirmSignOut = false },
            body = { Text("Loom forgets its token. Your library and installed games stay; downloads pause until you pair again.", color = LoomColors.TextMuted) },
            buttons = { f ->
                LoomButton("Stay signed in", { confirmSignOut = false }, Modifier.focusRequester(f))
                LoomButton("Sign out", { actions.signOut(); signedOut = true; confirmSignOut = false }, primary = true, destructive = true)
            })
    }
}

@Composable
private fun LibrarySyncSection(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    backFromPairing: Boolean = false,
    onBackFromPairing: () -> Unit = {},
) {
    val first = modifier
    val refresh = state.settings?.catalogRefresh ?: CatalogRefresh.DEFAULT
    val sync = state.sync
    val currentChip = remember { FocusRequester() }
    val pairAgainFocus = remember { FocusRequester() }
    // Back from pairing again, focus is where the user left, on Pair again. If the new token already works,
    // its sync has replaced the failure and the button has gone: the interval instead.
    LaunchedEffect(Unit) {
        if (backFromPairing) {
            delay(50) // as InitialFocus: the button has to be laid out first
            if (!runCatching { pairAgainFocus.requestFocus() }.getOrDefault(false)) runCatching { currentChip.requestFocus() }
            onBackFromPairing()
        }
    }
    // That sync can also start once Pair again has focus, taking the button away: focus stays in the section.
    // (Read while composing: by the time the effect runs, the button has gone and reported losing focus.)
    var pairAgainFocused by remember { mutableStateOf(false) }
    val pairAgainHadFocus = pairAgainFocused
    LaunchedEffect(sync.needsPairing) {
        if (!sync.needsPairing && pairAgainHadFocus) { delay(50); runCatching { currentChip.requestFocus() } }
    }
    SectionTop {
        SectionHeader("Automatic refresh")
        Text("Loom checks RomM for new, changed and removed games in the background, while online and the battery isn't low.",
            color = LoomColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            CatalogRefresh.entries.forEach { r ->
                // Focus arrives on the current choice, so a second A changes nothing.
                LoomChip(r.label, r == refresh, { actions.setCatalogRefresh(r) }, if (r == refresh) first.focusRequester(currentChip) else Modifier)
            }
        }
    }
    LoomSwitchRow("Only on Wi-Fi", state.settings?.refreshOnWifiOnly == true, actions::setRefreshOnWifiOnly,
        subtitle = "Refreshes and new covers wait for Wi-Fi, not mobile data or a phone's hotspot. Covers already on the Thor still show.")
    SectionHeader("Last sync")
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (sync.running) sync.total?.let { "Syncing ${sync.fetched} of $it" } ?: "Syncing…"
                else state.settings?.lastCatalogSyncEpochMs?.let { "Last synced ${ago(it)}" } ?: "Not synced yet",
            )
            if (!sync.running && sync.error != null) {
                // The sync's own text sends the user to Settings; here the way out is the button beside it.
                Text("The last sync failed: ${if (sync.needsPairing) "RomM rejected Loom's token." else sync.error}",
                    color = LoomColors.Error, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Nearest the line it answers, so Down from Only on Wi-Fi lands on it. The token is already
        // rejected, so unlike Server & pairing's Pair again there is nothing to lose and nothing to confirm.
        if (sync.needsPairing) {
            LoomButton("Pair again", onClick = actions::repair,
                modifier = Modifier.focusRequester(pairAgainFocus).onFocusChanged { pairAgainFocused = it.isFocused })
        }
        LoomButton("Full resync", onClick = actions::fullResync)
    }
}

private val CatalogRefresh.label get() = when (this) {
    CatalogRefresh.OFF -> "Off"
    CatalogRefresh.EVERY_6_HOURS -> "Every 6 h"
    CatalogRefresh.EVERY_12_HOURS -> "Every 12 h"
    CatalogRefresh.EVERY_24_HOURS -> "Every 24 h"
}

@Composable
private fun StorageSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val first = modifier
    var picking by remember { mutableStateOf(false) }
    val changeFolder = remember { FocusRequester() }
    ReturnFocusOnClose(picking) { changeFolder }
    // B on the browser (once it has no folder left to go back up from) or its toggle closes it.
    var toggleFocused by remember { mutableStateOf(false) }
    var pickerFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = picking && (toggleFocused || pickerFocused)) { picking = false }
    val s = state.storage
    SectionTop {
        SectionHeader("ROMs folder")
        Text(s.romsRoot ?: "Not set", style = MaterialTheme.typography.titleSmall)
        if (s.freeBytes != null && s.totalBytes != null) {
            Text("${s.volumeLabel ?: "Storage"}: ${formatBytes(s.freeBytes)} free of ${formatBytes(s.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium.tabular(), color = LoomColors.TextMuted)
        }
        if (!state.allFilesAccess) Text("All files access is off: Loom can't write games. Turn it on in Android settings.", color = LoomColors.Error)
        LoomButton(if (picking) "Close folder browser" else "Change folder", onClick = { picking = !picking },
            modifier = first.focusRequester(changeFolder).onFocusChanged { toggleFocused = it.isFocused })
    }
    if (picking) {
        Text("Games already installed stay where they are; Loom looks for games it knows in the new folder.", color = LoomColors.TextMuted,
            style = MaterialTheme.typography.bodySmall)
        val romsRoot = s.romsRoot?.let { java.io.File(it) }
        FolderPicker(actions.volumes(), romsRoot?.parentFile, actions.cocoonFolders.toSet(), focusOn = romsRoot,
            onPick = { actions.setRomsRoot(it); picking = false },
            modifier = Modifier.heightIn(max = 360.dp).onFocusChanged { pickerFocused = it.hasFocus })
    }
    SectionHeader("Used by Loom, per platform")
    val used = state.mapping.filter { it.platform.installedCount > 0 }
    if (used.isEmpty()) Text("No games on the device yet.", color = LoomColors.TextMuted)
    used.sortedByDescending { it.platform.installedBytes }.forEach { row ->
        // Focusable only so the pad can scroll a long list; there is nothing to press.
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier.fillMaxWidth().background(LoomColors.Surface, RoundedCornerShape(10.dp))
                .focusable(interactionSource = interaction).indication(interaction, FocusRingIndication(cornerRadius = 10.dp))
                .padding(horizontal = Space.m, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(row.platform.platform.displayName, modifier = Modifier.weight(1f))
            Text(gameCount(row.platform.installedCount), color = LoomColors.TextMuted, style = MaterialTheme.typography.bodyMedium.tabular())
            Text(formatBytes(row.platform.installedBytes), color = LoomColors.Installed, style = MaterialTheme.typography.bodyMedium.tabular())
        }
    }
}

@Composable
private fun MappingSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val first = modifier
    var editing by remember { mutableStateOf<MappingRow?>(null) }
    // Each platform's row, so closing the editor puts focus back on the row it was opened from.
    val ids = state.mapping.map { it.platform.platform.id }
    val rowFocus = remember(ids) { ids.associateWith { FocusRequester() } }
    var editedId by remember { mutableStateOf<Int?>(null) }
    ReturnFocusOnClose(editing != null) { editedId?.let(rowFocus::get) }
    SectionTop {
        SectionHeader("Where each platform's games go")
        Text("Folder names must match the system folders Cocoon scans. Platforms marked “check” use the server's folder name because Loom has no default for them.",
            color = LoomColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            LoomButton("Export", onClick = actions::exportMapping, modifier = first)
            LoomButton("Import", onClick = actions::importMapping)
        }
    }
    Text("File: ${actions.exportPath}", style = MaterialTheme.typography.labelMedium, color = LoomColors.TextFaint)
    if (state.mapping.isEmpty()) Text("Sync the library to see your platforms here.", color = LoomColors.TextMuted)
    state.mapping.forEach { row ->
        val r = row.resolved
        val id = row.platform.platform.id
        LoomRow(
            title = row.platform.platform.displayName,
            subtitle = "→ ${r.folder}/ · archives: ${if (r.archive == ArchivePolicy.EXTRACT) "unpack" else "keep"} · " +
                "files: ${r.include.sorted().joinToString()}",
            onClick = { editing = row; editedId = id },
            modifier = Modifier.focusRequester(rowFocus.getValue(id)),
            trailing = {
                when (r.source) {
                    MappingSource.USER -> StatusBadge("Changed", color = LoomColors.AccentMuted, onColor = LoomColors.Text)
                    MappingSource.SERVER_FOLDER -> StatusBadge("Check", color = LoomColors.Error, onColor = LoomColors.OnError)
                    MappingSource.DEFAULT -> Unit
                }
            },
        )
    }
    editing?.let { row -> MappingEditor(row, actions, onClose = { editing = null }) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MappingEditor(row: MappingRow, actions: SettingsActions, onClose: () -> Unit) {
    val r = row.resolved
    // Saved per server folder: RomM gives one slug ("arcade") to several (MAME, FBNeo, NAOMI…).
    val match = PlatformMatch(fsSlug = row.platform.platform.fsSlug)
    var folder by remember { mutableStateOf(r.folder) }
    var archive by remember { mutableStateOf(r.archive) }
    var multiDisc by remember { mutableStateOf(r.multiDisc) }
    var include by remember { mutableStateOf(r.include) }
    val folderProblem = FolderNames.validate(folder)
    // What the card holds of the platform's usual folders, looked up off the main thread: names alone until then.
    val onCard by produceState<List<SystemFolder>?>(null, r.usualFolders) { value = actions.foldersOnCard(r.usualFolders) }
    // The choices on the right reach lower than the folder field, so the pad's nearest-control rule
    // skipped the field: Left from "Game.m3u folder" went down to the file chips, Up from those under
    // the field went to the right column, and Down from the field went there too. Those moves are set here.
    val folderFocus = remember { FocusRequester() }
    val firstFile = remember { FocusRequester() }
    val layout = remember { EditorLayout() }
    // As wide as the screen, with the choices beside the folder field and RomM's names beside the
    // buttons: stacked, the form was taller than the screen and its top scrolled out of the pad's reach.
    LoomDialog(
        title = row.platform.platform.displayName,
        onDismiss = onClose,
        maxWidth = Dp.Unspecified,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    LoomTextField(folder, { folder = it.trim() }, "Folder in your ROMs folder", isError = folderProblem != null,
                        supporting = folderProblem ?: folderHint(row.platform.platform.displayName, folder, r.usualFolders, onCard, actions.cocoonFolders),
                        modifier = Modifier.weight(1f).focusRequester(folderFocus).focusProperties { down = firstFile }, tag = "folder-field")
                    Column(Modifier.onPlaced { layout.rightColumn = it }, verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text("Archives (.zip, .7z)", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                LoomChip("Keep as is", archive == ArchivePolicy.KEEP, { archive = ArchivePolicy.KEEP },
                                    Modifier.focusProperties { left = folderFocus })
                                LoomChip("Unpack", archive == ArchivePolicy.EXTRACT, { archive = ArchivePolicy.EXTRACT })
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text("Multi-disc games", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                LoomChip("Game.m3u folder", multiDisc == MultiDiscLayout.M3U_DIR, { multiDisc = MultiDiscLayout.M3U_DIR },
                                    Modifier.focusProperties { left = folderFocus })
                                LoomChip("Plain folder", multiDisc == MultiDiscLayout.FOLDER, { multiDisc = MultiDiscLayout.FOLDER })
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Files to download", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        ROM_FILE_CATEGORIES.forEach { c ->
                            LoomChip(c, c in include, { include = if (c in include) include - c else include + c },
                                Modifier.onPlaced { layout.chips[c] = it }.focusProperties { if (layout.underField(c)) up = folderFocus }
                                    .then(if (c == ROM_FILE_CATEGORIES.first()) Modifier.focusRequester(firstFile) else Modifier))
                        }
                    }
                }
            }
        },
        buttons = { f ->
            Text("RomM slug ${row.platform.platform.slug} · server folder ${row.platform.platform.fsSlug}",
                Modifier.weight(1f).align(Alignment.CenterVertically),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = LoomColors.TextFaint)
            row.override?.let { o -> LoomButton("Reset", { actions.resetOverride(o.match); onClose() }) }
            LoomButton("Cancel", onClose, Modifier.focusRequester(f))
            LoomButton("Save", enabled = folderProblem == null && include.isNotEmpty(), primary = true, onClick = {
                // The folder only when the user set it: saving other settings mustn't pin the default and
                // stop Loom looking on the card at the first download.
                actions.saveOverride(PlatformMappingEntry(match, folder = folder.takeIf { it != r.folder || r.folderChosen },
                    archive = archive, multiDisc = multiDisc, include = include.sorted()))
                onClose()
            })
        },
    )
}

/**
 * The hint under the folder field: the platform's other usual folders (arcade's mame, fbneo, fba…),
 * with what the card holds of each once that's known. Only a platform without any gets Cocoon's
 * folders that start like the one typed.
 */
private fun folderHint(platform: String, typed: String, usual: List<String>, onCard: List<SystemFolder>?, cocoonFolders: List<String>): String? {
    val others = usual.filter { FolderNames.key(it) != FolderNames.key(typed) }
    if (others.isEmpty()) {
        return cocoonFolders.filter { it.startsWith(typed.take(1)) && it != typed }.take(8)
            .takeIf { it.isNotEmpty() && typed.isNotEmpty() }?.joinToString(prefix = "Other Cocoon folders: ")
    }
    val found = onCard?.associateBy { FolderNames.key(it.name) }
    return others.joinToString(prefix = "Other folders for $platform: ") { name ->
        val folder = found?.get(FolderNames.key(name))
        when {
            found == null -> name
            folder == null -> "$name (not on the card)"
            folder.games == 0 -> "${folder.name} (no games)"
            else -> "${folder.name} (${gameCount(folder.games)})"
        }
    }
}

/** Where the folder editor's right column and file chips were placed; read when the pad moves, not while drawing. */
private class EditorLayout {
    var rightColumn: LayoutCoordinates? = null
    val chips = mutableMapOf<String, LayoutCoordinates>()

    /** True for a file chip in the first row that sits under the folder field rather than under the right column. */
    fun underField(category: String): Boolean {
        val column = rightColumn?.takeIf { it.isAttached } ?: return false
        val chip = chips[category]?.takeIf { it.isAttached } ?: return false
        return chip.positionInParent().y == 0f && chip.boundsInRoot().center.x < column.positionInRoot().x
    }
}

@Composable
private fun DownloadsSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val first = modifier
    val firstChip = remember { FocusRequester() }
    // Allowing background downloads replaces the focused button with a line of text; focus stays in the section.
    // (Read while composing: by the time the effect runs, the button has gone and reported losing focus.)
    var allowFocused by remember { mutableStateOf(false) }
    val allowHadFocus = allowFocused
    LaunchedEffect(state.batteryUnrestricted) {
        if (state.batteryUnrestricted && allowHadFocus) { delay(50); runCatching { firstChip.requestFocus() } }
    }
    SectionHeader("At the same time")
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        (1..3).forEach { n ->
            LoomChip("$n", state.settings?.maxConcurrentDownloads == n, { actions.setConcurrency(n) },
                if (n == 1) first.focusRequester(firstChip) else Modifier)
        }
    }
    Text("One at a time is usually fastest over one connection; two or three can help with many small games.",
        color = LoomColors.TextMuted, style = MaterialTheme.typography.bodySmall)
    SectionHeader("Background")
    if (state.batteryUnrestricted) {
        Text("Loom may run in the background: downloads continue and restart after Android kills the app.", color = LoomColors.Installed)
    } else {
        Text("Loom is battery-optimised. Android may stop downloads in the background and not restart them until you open Loom.",
            color = LoomColors.TextMuted)
        LoomButton("Allow background downloads", onClick = actions::openBatterySettings,
            modifier = Modifier.onFocusChanged { allowFocused = it.isFocused })
    }
}

/**
 * Save sync: automatic upload, how the last background sync went (with Pair again beside it when
 * that sync needs Loom to pair again), and a card per emulator saying where its saves are, two to
 * a row. A on a card opens that emulator's details: its folder in full, how to set it up, and the
 * buttons to change it. Change folder shows a folder browser in place of everything else, so it
 * has the section's whole height; B goes up through its folders, then back to the card, as picking
 * one does. No heading of its own above the switch: the section list names it, and the section
 * must fit the screen.
 */
@Composable
private fun SaveSyncSection(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    backFromPairing: Boolean = false,
    onBackFromPairing: () -> Unit = {},
) {
    val first = modifier
    val settings = state.settings
    val last = settings?.lastSaveSync
    val needsPairing = last?.failed == true && last.needsPairing
    val switchFocus = remember { FocusRequester() }
    val pairAgainFocus = remember { FocusRequester() }
    // Back from pairing again, focus is where the user left, on Pair again. If the new pairing already
    // works, the save sync it starts has replaced the failure and the button has gone: the switch instead.
    LaunchedEffect(Unit) {
        if (backFromPairing) {
            delay(50) // as InitialFocus: the button has to be laid out first
            if (!runCatching { pairAgainFocus.requestFocus() }.getOrDefault(false)) runCatching { switchFocus.requestFocus() }
            onBackFromPairing()
        }
    }
    // That sync can also end once Pair again has focus, taking the button away: focus stays in the section.
    // (Read while composing: by the time the effect runs, the button has gone and reported losing focus.)
    var pairAgainFocused by remember { mutableStateOf(false) }
    val pairAgainHadFocus = pairAgainFocused
    LaunchedEffect(needsPairing) {
        if (!needsPairing && pairAgainHadFocus) { delay(50); runCatching { switchFocus.requestFocus() } }
    }
    // What a card opened, if anything; once it is all closed, focus goes back to that card.
    var open by remember { mutableStateOf<SavesOpen?>(null) }
    var opener by remember { mutableStateOf(SaveFolderFor.AZAHAR) }
    val cardFocus = remember { SaveFolderFor.entries.associateWith { FocusRequester() } }
    ReturnFocusOnClose(open != null) { cardFocus.getValue(opener) }
    val folders = SaveFolderFor.entries.associateWith { emulatorFolder(it, state) }
    val browsing = (open as? SavesOpen.Browser)?.which
    // B on the browser, once it has no folder left to go back up from, closes it; so does Back by
    // touch, with no focus to go by. From the section list, B still leaves Settings.
    var browserFocused by remember { mutableStateOf(false) }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    BackHandler(enabled = browsing != null && (browserFocused || !keyboardMode)) { open = null }
    if (browsing != null) {
        SaveFolderBrowser(folders.getValue(browsing), state, actions, onFocus = { browserFocused = it }, onClose = { open = null })
    } else {
        SectionTop {
            Box(first.focusRequester(switchFocus)) {
                LoomSwitchRow("Upload saves automatically", settings?.autoUploadSaves ?: true, actions::setAutoUploadSaves,
                    subtitle = "Every 15 minutes and when Loom opens: uploads saves untouched for a minute, tells you of conflicts " +
                        "and newer RomM saves. Off, Loom only looks when you press Upload save. Saves from RomM always wait for you.",
                    subtitleLines = 4)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        last == null -> "No save sync has run yet."
                        // The sync's own text sends the user to Settings; here the way out is the button beside it.
                        needsPairing -> "Last sync ${ago(last.atEpochMs)}: ${last.summary.substringBefore(" Pair again in Settings")}"
                        else -> "Last sync ${ago(last.atEpochMs)}: ${last.summary}"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = if (last?.failed == true) LoomColors.Error else LoomColors.TextMuted,
                    modifier = Modifier.weight(1f).testTag("last-save-sync"),
                )
                // RomM already rejects the token or has no device for Loom, so as in Library sync there is
                // nothing to lose and nothing to confirm.
                if (needsPairing) {
                    LoomButton("Pair again", onClick = actions::repair,
                        modifier = Modifier.focusRequester(pairAgainFocus).onFocusChanged { pairAgainFocused = it.isFocused })
                }
            }
        }
        // Cards, not rows: four emulators' rows wouldn't fit under the switch. Chunked, so the
        // number of columns can follow the screen's width.
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SaveFolderFor.entries.chunked(SAVE_CARD_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { which ->
                        val folder = folders.getValue(which)
                        LoomRow(
                            title = "${which.emulator} · ${which.system}", subtitle = folder.card, subtitleLines = 1, dense = true,
                            onClick = { opener = which; open = SavesOpen.Details(which) },
                            modifier = Modifier.weight(1f).focusRequester(cardFocus.getValue(which)).testTag("${which.tag}-card"),
                        )
                    }
                    repeat(SAVE_CARD_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
    when (val o = open) {
        is SavesOpen.Details -> EmulatorFolderDialog(
            folders.getValue(o.which), state, actions, focusProfile = o.fromProfiles,
            onChangeFolder = { open = SavesOpen.Browser(o.which) },
            onChooseProfile = { open = SavesOpen.Profiles },
            onClose = { open = null },
        )
        SavesOpen.Profiles -> EdenProfilesDialog(
            state.storage.edenProfiles, settings?.edenProfile,
            onChoose = { actions.setEdenProfile(it); open = SavesOpen.Details(SaveFolderFor.EDEN, fromProfiles = true) },
            onCancel = { open = SavesOpen.Details(SaveFolderFor.EDEN, fromProfiles = true) },
        )
        is SavesOpen.Browser, null -> Unit
    }
}

/** Emulator cards per row in Saves. */
private const val SAVE_CARD_COLUMNS = 2

/** What an emulator's card in Saves opened. */
private sealed interface SavesOpen {
    /** The emulator's details; [fromProfiles] when back from Eden's profiles, to put focus where it was. */
    data class Details(val which: SaveFolderFor, val fromProfiles: Boolean = false) : SavesOpen

    /** Eden's profiles, to choose whose saves Loom syncs; opened from Eden's details, and back to them. */
    data object Profiles : SavesOpen

    /** The folder browser, in place of the section's controls. */
    data class Browser(val which: SaveFolderFor) : SavesOpen
}

/**
 * The emulators whose save folders Settings → Saves shows: as the owner knows them, the system
 * they run, and what their folder browser looks for.
 */
private enum class SaveFolderFor(
    val emulator: String,
    val system: String,
    val tag: String,
    val highlighted: Set<String>,
    val note: (List<java.io.File>) -> FolderNote,
) {
    AZAHAR("Azahar", "3DS", "azahar", AZAHAR_FOLDERS, ::azaharNote),
    EDEN("Eden", "Switch", "eden", setOf("user"), ::edenNote),
}

/** One emulator's save folder as Saves shows it: on its card in a few words, and in full in its details. */
private class EmulatorFolder(
    val which: SaveFolderFor,
    /** The folder in use: the one chosen, else the one Loom found; null when there is neither. */
    val folder: java.io.File?,
    /** The card's line: where the folder is, or what's missing. */
    val card: String,
    /** How Loom found the folder, or that it didn't. */
    val status: String,
    /** How to set the emulator up for Loom to find its saves. */
    val steps: String,
    /** The button back to the folder Loom looks in by itself, once one is chosen. */
    val useDefault: String?,
    /** The way back to that folder. */
    val onUseDefault: (SettingsActions) -> Unit,
    /** Makes the folder picked in the browser the emulator's from now on. */
    val onPick: (SettingsActions, java.io.File) -> Unit,
)

private fun emulatorFolder(which: SaveFolderFor, state: SettingsUiState): EmulatorFolder {
    val settings = state.settings
    val storage = state.storage
    return when (which) {
        SaveFolderFor.AZAHAR -> {
            val chosen = settings?.azaharUserDir
            val folder = (chosen ?: storage.azaharFound)?.let { java.io.File(it) }
            EmulatorFolder(
                which, folder,
                card = when {
                    chosen != null -> "Your chosen folder"
                    folder != null -> "Found in the 3DS folder"
                    else -> "Not found yet"
                },
                status = when {
                    chosen != null -> "You chose this folder."
                    folder != null -> "Found in the 3DS folder."
                    else -> "Not found: run a game in Azahar once, or choose the folder holding its sdmc and nand."
                },
                steps = "Loom reads 3DS saves in Azahar's folder, the one holding sdmc and nand. On the Thor that is the 3DS " +
                    "system folder, where Loom looks by itself.",
                useDefault = "Use 3DS folder".takeIf { chosen != null },
                onUseDefault = { it.setAzaharFolder(null) },
                onPick = { actions, dir -> actions.setAzaharFolder(dir) },
            )
        }
        SaveFolderFor.EDEN -> {
            val chosen = settings?.edenSaveDir
            val folder = (chosen ?: storage.edenFound)?.let { java.io.File(it) }
            val noSaves = chosen != null && folder != null && !java.io.File(folder, "user/save").isDirectory
            val profiles = storage.edenProfiles
            val unpicked = profiles.size > 1 && profiles.none { it.id.equals(settings?.edenProfile, ignoreCase = true) }
            EmulatorFolder(
                which, folder,
                card = when {
                    noSaves -> "Chosen, but it has no user/save"
                    folder == null -> "Not found yet"
                    unpicked -> "Several profiles: choose one"
                    chosen != null -> "Your chosen folder"
                    else -> "Found in ROMs/saves/Eden"
                },
                status = when {
                    noSaves -> "You chose this folder, but it has no user/save."
                    chosen != null -> "You chose this folder."
                    folder != null -> "Found in ROMs/saves/Eden."
                    else -> "Not found in ROMs/saves/Eden."
                },
                steps = "Eden keeps its saves in Android/data, where Loom can't read them: move Eden's save folder to " +
                    "ROMs/saves/Eden, where Loom looks by itself, or choose the folder you moved it to.",
                useDefault = "Use saves/Eden".takeIf { chosen != null },
                onUseDefault = { it.setEdenFolder(null) },
                onPick = { actions, dir -> actions.setEdenFolder(dir) },
            )
        }
    }
}

/**
 * An emulator's details: its save folder in full, how Loom found it, how to set the emulator up,
 * and the buttons to change the folder or go back to the one Loom finds by itself. Eden's also
 * say whose saves Loom syncs, and whether it can read Switch title IDs.
 */
@Composable
private fun EmulatorFolderDialog(
    f: EmulatorFolder,
    state: SettingsUiState,
    actions: SettingsActions,
    focusProfile: Boolean,
    onChangeFolder: () -> Unit,
    onChooseProfile: () -> Unit,
    onClose: () -> Unit,
) {
    val tag = f.which.tag
    val profiles = if (f.which == SaveFolderFor.EDEN) state.storage.edenProfiles else emptyList()
    val picked = profiles.firstOrNull { it.id.equals(state.settings?.edenProfile, ignoreCase = true) }
    // Focus starts on what's needed: whose saves to sync while Eden has several profiles and none is chosen.
    val profileFirst = profiles.size > 1 && (focusProfile || picked == null)
    LoomDialog(
        title = "${f.which.emulator}'s saves",
        onDismiss = onClose,
        maxWidth = Dp.Unspecified, // four buttons for Eden's
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(f.folder?.path ?: "No folder", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("$tag-folder-path"))
                Text(f.status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("$tag-folder-status"))
                Text(f.steps, style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted)
                if (profiles.size > 1) {
                    Text(
                        if (picked == null) "Eden has ${profiles.size} profiles. Whose saves does Loom sync? Choose profile picks one."
                        else "Loom syncs the saves of Eden's profile ${shortProfile(picked.id)} (${saveCount(picked.saveCount)}).",
                        style = MaterialTheme.typography.bodySmall, color = if (picked == null) LoomColors.Error else LoomColors.TextMuted,
                        modifier = Modifier.testTag("eden-profile-line"),
                    )
                }
                if (f.which == SaveFolderFor.EDEN) {
                    Text(
                        when {
                            state.storage.prodKeys != null -> "Switch title IDs are read with the header_key in your prod.keys, which never leaves the Thor."
                            state.storage.prodKeysWithoutKey -> "Your prod.keys has no header_key: each Switch game asks which save folder is its."
                            else -> "No prod.keys in the ROMs folder: each Switch game asks which save folder is its."
                        },
                        style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted, modifier = Modifier.testTag("prod-keys"),
                    )
                }
            }
        },
        buttons = { firstFocus ->
            f.useDefault?.let {
                LoomButton(it, onClick = { f.onUseDefault(actions); onClose() }, modifier = Modifier.testTag("$tag-use-default"))
            }
            if (profiles.size > 1) {
                LoomButton("Choose profile", onClick = onChooseProfile,
                    modifier = (if (profileFirst) Modifier.focusRequester(firstFocus) else Modifier).testTag("eden-profile"))
            }
            LoomButton("Change folder", onClick = onChangeFolder,
                modifier = (if (profileFirst) Modifier else Modifier.focusRequester(firstFocus)).testTag("$tag-change-folder"))
            LoomButton("Close", onClick = onClose, modifier = Modifier.testTag("$tag-close"))
        },
    )
}

/**
 * An emulator's folder browser, in place of the section's controls: it opens beside the folder in
 * use (or in the ROMs folder), and says whether the open folder holds the emulator's saves.
 */
@Composable
private fun SaveFolderBrowser(
    f: EmulatorFolder, state: SettingsUiState, actions: SettingsActions, onFocus: (Boolean) -> Unit, onClose: () -> Unit,
) {
    SectionHeader("${f.which.emulator}'s folder")
    val start = f.folder ?: state.storage.romsRoot?.let { java.io.File(it) }
    // One browser per emulator: what one remembers (the folder open in it) mustn't carry over to another.
    key(f.which) {
        FolderPicker(actions.volumes(), start?.parentFile, f.which.highlighted, focusOn = start, note = f.which.note,
            onPick = { f.onPick(actions, it); onClose() },
            modifier = Modifier.heightIn(max = 300.dp).onFocusChanged { onFocus(it.hasFocus) }.testTag("save-folder-browser"))
    }
}

/**
 * Whose saves Loom syncs, when Eden has several profiles: all of them, each with its saves' count
 * and newest change, since a 32-digit ID alone tells the owner nothing. Until one is chosen,
 * Switch saves wait.
 */
@Composable
private fun EdenProfilesDialog(profiles: List<EdenProfile>, chosen: String?, onChoose: (String) -> Unit, onCancel: () -> Unit) {
    val picked = profiles.firstOrNull { it.id.equals(chosen, ignoreCase = true) }
    LoomDialog(
        title = "Whose Switch saves does Loom sync?",
        onDismiss = onCancel,
        body = {
            Text("Eden keeps each user's saves in a profile folder named by a long ID. Yours is likely the one whose saves changed last.",
                style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted)
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } } // after the dialog's own first focus
            val focused = picked ?: profiles.first()
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 196.dp).testTag("eden-profiles"), contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s)) {
                items(profiles, key = { it.id }) { profile ->
                    LoomRow(
                        title = profile.id,
                        subtitle = listOfNotNull(saveCount(profile.saveCount), profile.newestModified?.let { "changed ${dateTime(it.toEpochMilli())}" })
                            .joinToString(" · "),
                        subtitleLines = 1, dense = true, selected = profile == picked,
                        onClick = { onChoose(profile.id) },
                        modifier = (if (profile == focused) Modifier.focusRequester(focus) else Modifier).testTag("profile-${profile.id}"),
                    )
                }
            }
        },
        buttons = { first -> LoomButton("Cancel", onCancel, Modifier.focusRequester(first)) },
    )
}

private fun saveCount(n: Int) = if (n == 1) "1 save" else "$n saves"

/** The folders Azahar keeps in its user directory, highlighted in its folder browser. */
private val AZAHAR_FOLDERS = setOf("sdmc", "nand")

private fun azaharNote(children: List<java.io.File>): FolderNote =
    if (children.any { it.name == "sdmc" }) FolderNote("Azahar's data is here (${children.filter { it.name in AZAHAR_FOLDERS }.joinToString { it.name }}).", true)
    else FolderNote("Azahar's folder holds sdmc and nand; they aren't here.", false)

private fun edenNote(children: List<java.io.File>): FolderNote =
    if (children.any { it.name == "user" && java.io.File(it, "save").isDirectory }) FolderNote("Eden's saves are here (user/save).", true)
    else FolderNote("Eden's save folder holds user/save; it isn't here.", false)

@Composable
private fun AboutSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val first = modifier
    var showing by remember { mutableStateOf<Pair<String, String>?>(null) }
    val gplFocus = remember { FocusRequester() }
    val fontFocus = remember { FocusRequester() }
    var opener by remember { mutableStateOf(gplFocus) }
    ReturnFocusOnClose(showing != null) { opener }
    SectionHeader("Loom ${state.appVersion}")
    Text("Puts games from your RomM server into the folders Cocoon scans. Free software under the GNU GPL v3.0.",
        color = LoomColors.TextMuted)
    Text("Built against RomM ${state.contractVersion}'s API.", color = LoomColors.TextMuted)
    SectionHeader("Credits")
    Text("RomM (rommapp/romm, AGPL-3.0): the server Loom talks to.\n" +
        "Argosy Launcher (rommapp/argosy-launcher, GPL-3.0): reference for RomM pairing and API use.\n" +
        "Inter by Rasmus Andersson (SIL Open Font License 1.1).\n" +
        "AndroidX, Jetpack Compose, Kotlin, OkHttp, Retrofit, Coil, Dagger/Hilt, Apache Commons Compress, XZ for Java, ZXing (Apache 2.0).",
        style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        LoomButton("GNU GPL v3.0", onClick = { showing = "GNU General Public License v3.0" to actions.licenseText(); opener = gplFocus },
            modifier = first.focusRequester(gplFocus))
        LoomButton("Inter font licence", onClick = { showing = "SIL Open Font License 1.1" to actions.fontLicense(); opener = fontFocus },
            modifier = Modifier.focusRequester(fontFocus))
    }
    showing?.let { (title, text) ->
        LoomDialog(title = title, onDismiss = { showing = null },
            body = {
                Box(Modifier.heightIn(max = 260.dp).padScroll(rememberScrollState()).testTag("license-text")) {
                    // The licence files are wrapped at ~72 columns; rejoined, each paragraph flows to the dialog's width.
                    Text(text.replace(Regex("(?<!\n)\n(?!\n)"), " "), style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted)
                }
            },
            buttons = { f -> LoomButton("Close", { showing = null }, Modifier.focusRequester(f)) })
    }
}
