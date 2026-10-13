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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.davidmontei.loom.data.download.VolumeInfo
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.mapping.ArchivePolicy
import ro.davidmontei.loom.storage.mapping.FolderNames
import ro.davidmontei.loom.storage.mapping.MappingSource
import ro.davidmontei.loom.storage.mapping.MultiDiscLayout
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch
import ro.davidmontei.loom.storage.mapping.ROM_FILE_CATEGORIES
import ro.davidmontei.loom.ui.components.ButtonHint
import ro.davidmontei.loom.ui.components.FocusRingIndication
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
    fun setRomsRoot(dir: java.io.File)
    fun volumes(): List<VolumeInfo>
    fun saveOverride(entry: PlatformMappingEntry)
    fun resetOverride(match: PlatformMatch)
    fun exportMapping()
    fun importMapping()
    val exportPath: String
    val cocoonFolders: List<String>
    fun setConcurrency(n: Int)
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
            override fun repair() { vm.repair(onRepair) }
            override fun signOut() { vm.signOut() }
            override fun fullResync() = vm.fullResync()
            override fun setRomsRoot(dir: java.io.File) { vm.setRomsRoot(dir) }
            override fun volumes() = vm.mountedVolumes()
            override fun saveOverride(entry: PlatformMappingEntry) { vm.saveOverride(entry) }
            override fun resetOverride(match: PlatformMatch) { vm.resetOverride(match) }
            override fun exportMapping() { vm.exportMapping() }
            override fun importMapping() { vm.importMapping() }
            override val exportPath get() = vm.exportPath
            override val cocoonFolders get() = vm.cocoonFolders
            override fun setConcurrency(n: Int) { vm.setConcurrency(n) }
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
    var section by remember { mutableStateOf(SettingsSection.SERVER) }
    val firstSection = remember { FocusRequester() }
    val sectionFocus = remember { SettingsSection.entries.associateWith { FocusRequester() } }
    // Moving right from the section list lands on the section's first control, not the nearest one.
    val contentFirst = remember { FocusRequester() }
    val first = Modifier.focusRequester(contentFirst)
    InitialFocus(firstSection)
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
                verticalArrangement = Arrangement.spacedBy(Space.s),
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
                    SettingsSection.SERVER -> ServerSection(state, actions, first)
                    SettingsSection.STORAGE -> StorageSection(state, actions, first)
                    SettingsSection.MAPPING -> MappingSection(state, actions, first)
                    SettingsSection.DOWNLOADS -> DownloadsSection(state, actions, first)
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

@Composable
private fun ServerSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val first = modifier
    val server = state.settings?.server
    var address by remember(server) { mutableStateOf(server?.url?.toString().orEmpty()) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var signedOut by remember { mutableStateOf(false) }
    val pairAgainFocus = remember { FocusRequester() }
    val signOutFocus = remember { FocusRequester() }
    // Signing out removes its own button: focus goes to Pair again, the next thing to do.
    ReturnFocusOnClose(confirmSignOut) { if (signedOut) pairAgainFocus else signOutFocus }
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
        LoomButton("Pair again", onClick = actions::repair, modifier = Modifier.focusRequester(pairAgainFocus))
        LoomButton("Full resync", onClick = actions::fullResync)
        if (creds != null) {
            LoomButton("Sign out", onClick = { confirmSignOut = true }, destructive = true, modifier = Modifier.focusRequester(signOutFocus))
        }
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
private fun StorageSection(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val first = modifier
    var picking by remember { mutableStateOf(false) }
    val changeFolder = remember { FocusRequester() }
    ReturnFocusOnClose(picking) { changeFolder }
    val s = state.storage
    SectionHeader("ROMs folder")
    Text(s.romsRoot ?: "Not set", style = MaterialTheme.typography.titleSmall)
    if (s.freeBytes != null && s.totalBytes != null) {
        Text("${s.volumeLabel ?: "Storage"}: ${formatBytes(s.freeBytes)} free of ${formatBytes(s.totalBytes)}",
            style = MaterialTheme.typography.bodyMedium.tabular(), color = LoomColors.TextMuted)
    }
    if (!state.allFilesAccess) Text("All files access is off: Loom can't write games. Turn it on in Android settings.", color = LoomColors.Error)
    LoomButton(if (picking) "Close folder browser" else "Change folder", onClick = { picking = !picking },
        modifier = first.focusRequester(changeFolder))
    if (picking) {
        Text("Games already installed stay where they are; Loom looks for games it knows in the new folder.", color = LoomColors.TextMuted,
            style = MaterialTheme.typography.bodySmall)
        FolderPicker(actions.volumes(), s.romsRoot?.let { java.io.File(it) }?.parentFile, actions.cocoonFolders.toSet(),
            onPick = { actions.setRomsRoot(it); picking = false }, modifier = Modifier.heightIn(max = 360.dp))
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
    val rowFocus = remember { mutableMapOf<Int, FocusRequester>() }
    var editedId by remember { mutableStateOf<Int?>(null) }
    ReturnFocusOnClose(editing != null) { editedId?.let(rowFocus::get) }
    SectionHeader("Where each platform's games go")
    Text("Folder names must match the system folders Cocoon scans. Platforms marked “check” use the server's folder name because Loom has no default for them.",
        color = LoomColors.TextMuted, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        LoomButton("Export", onClick = actions::exportMapping, modifier = first)
        LoomButton("Import", onClick = actions::importMapping)
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
            modifier = Modifier.focusRequester(rowFocus.getOrPut(id) { FocusRequester() }),
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
    val match = row.override?.match ?: PlatformMatch(slug = row.platform.platform.slug)
    var folder by remember { mutableStateOf(r.folder) }
    var archive by remember { mutableStateOf(r.archive) }
    var multiDisc by remember { mutableStateOf(r.multiDisc) }
    var include by remember { mutableStateOf(r.include) }
    val folderProblem = FolderNames.validate(folder)
    LoomDialog(
        title = row.platform.platform.displayName,
        onDismiss = onClose,
        body = {
            Text("RomM slug ${row.platform.platform.slug} · server folder ${row.platform.platform.fsSlug}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = LoomColors.TextFaint)
            LoomTextField(folder, { folder = it.trim() }, "Folder in your ROMs folder", isError = folderProblem != null,
                supporting = folderProblem ?: actions.cocoonFolders.filter { it.startsWith(folder.take(1)) && it != folder }.take(8)
                    .takeIf { it.isNotEmpty() && folder.isNotEmpty() }?.joinToString(prefix = "Other Cocoon folders: "))
            Text("Archives (.zip, .7z)", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LoomChip("Keep as is", archive == ArchivePolicy.KEEP, { archive = ArchivePolicy.KEEP })
                LoomChip("Unpack", archive == ArchivePolicy.EXTRACT, { archive = ArchivePolicy.EXTRACT })
            }
            Text("Multi-disc games", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LoomChip("Game.m3u folder", multiDisc == MultiDiscLayout.M3U_DIR, { multiDisc = MultiDiscLayout.M3U_DIR })
                LoomChip("Plain folder", multiDisc == MultiDiscLayout.FOLDER, { multiDisc = MultiDiscLayout.FOLDER })
            }
            Text("Files to download", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                ROM_FILE_CATEGORIES.forEach { c ->
                    LoomChip(c, c in include, { include = if (c in include) include - c else include + c })
                }
            }
        },
        buttons = { f ->
            if (row.override != null) LoomButton("Reset", { actions.resetOverride(match); onClose() })
            LoomButton("Cancel", onClose, Modifier.focusRequester(f))
            LoomButton("Save", enabled = folderProblem == null && include.isNotEmpty(), primary = true, onClick = {
                actions.saveOverride(PlatformMappingEntry(match, folder = folder, archive = archive, multiDisc = multiDisc, include = include.sorted()))
                onClose()
            })
        },
    )
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
