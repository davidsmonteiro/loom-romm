package ro.davidmontei.loom.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.davidmontei.loom.data.db.RecentInstall
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.status
import ro.davidmontei.loom.data.saves.ConflictChoice
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.saves.ServerSaveInfo
import ro.davidmontei.loom.data.saves.emulatorName
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.WaitReason
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.ui.components.ButtonHint
import ro.davidmontei.loom.ui.components.InitialFocus
import ro.davidmontei.loom.ui.components.FocusRingIndication
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.LoomProgressBar
import ro.davidmontei.loom.ui.components.ScreenScaffold
import ro.davidmontei.loom.ui.components.SectionHeader
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.StatusBadge
import ro.davidmontei.loom.ui.input.PadAction
import ro.davidmontei.loom.ui.input.onPadAction
import ro.davidmontei.loom.ui.saves.ReplaceSaveDialog
import ro.davidmontei.loom.ui.saves.SaveFolderDialog
import ro.davidmontei.loom.ui.saves.SaveFolderOption
import ro.davidmontei.loom.ui.saves.SaveFolderQuestion
import ro.davidmontei.loom.ui.saves.answer
import ro.davidmontei.loom.ui.saves.toQuestion
import ro.davidmontei.loom.ui.theme.LoomColors
import ro.davidmontei.loom.ui.theme.tabular
import javax.inject.Inject

/** One job as the queue screen shows it. */
data class QueueItem(
    val jobId: Long,
    val title: String,
    val phase: JobPhase,
    val label: String,
    val detail: String?,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
    val retryable: Boolean,
) {
    val canPause get() = phase == JobPhase.QUEUED || phase == JobPhase.WAITING || phase == JobPhase.DOWNLOADING ||
        phase == JobPhase.VERIFYING || phase == JobPhase.EXTRACTING
    val failed get() = phase == JobPhase.FAILED
    /** Moving into place can't be stopped half way. */
    val canCancel get() = phase != JobPhase.PROMOTING
}

data class QueueUiState(
    val active: List<QueueItem>,
    val failed: List<QueueItem>,
    val finished: List<RecentInstall>,
    val conflicts: List<ConflictItem> = emptyList(),
    val restorable: List<RestorableSave> = emptyList(),
    val recentSaves: List<RecentSave> = emptyList(),
    /** Which save folder is a conflicting game's, asked from its conflict ([QueueActions.askSaveFolder]). */
    val saveFolderQuestion: SaveFolderQuestion? = null,
)

/** A row of the queue: a section header, a save to settle or restore, or a job. */
private sealed interface QueueRow {
    val key: Any

    data class Header(val text: String) : QueueRow { override val key get() = text }
    data class Conflict(val item: ConflictItem) : QueueRow { override val key get() = "conflict-${item.romId}" }
    data class Restorable(val item: RestorableSave) : QueueRow { override val key get() = "restore-${item.romId}" }
    data class Job(val item: QueueItem) : QueueRow { override val key get() = item.jobId }
}

interface QueueActions {
    fun pause(jobId: Long)
    fun resume(jobId: Long)
    fun retry(jobId: Long)
    fun cancel(jobId: Long)
    /** The user's answer to a game's save conflict. Keep server and Keep both are asked again first. */
    fun resolveConflict(romId: Int, title: String, choice: ConflictChoice)
    /** Puts RomM's save on the Thor, once the user said the emulator is closed. */
    fun restoreSave(romId: Int, title: String)
    /** Asks again which save folder is the conflicting game [romId]'s ([QueueUiState.saveFolderQuestion]), as its details do. */
    fun askSaveFolder(romId: Int, title: String, emulator: String?)
    /** The answer to [QueueUiState.saveFolderQuestion]: a [SaveFolderOption.id]. The game's save is looked at again. */
    fun chooseSaveFolder(id: String)
    fun dismissSaveFolderQuestion()
}

@HiltViewModel
class QueueViewModel @Inject constructor(private val downloads: DownloadRepository, private val saves: SaveRepository) : ViewModel(), QueueActions {
    /** What RomM said about each conflict's server save (Loom doesn't keep its size or origin), by save id. */
    private val serverSaves = MutableStateFlow<Map<Int, ServerSaveInfo>>(emptyMap())
    /** Games an answer or a restore is being carried out for. */
    private val busy = MutableStateFlow<Set<Int>>(emptySet())
    private val saveFolderQuestion = MutableStateFlow<SaveFolderQuestion?>(null)

    /** What the queue's saves need besides the rows themselves. */
    private class SaveExtras(val server: Map<Int, ServerSaveInfo>, val busy: Set<Int>, val chosen: Set<Int>, val question: SaveFolderQuestion?)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val jobRows = combine(downloads.jobs, downloads.progress, downloads.recentInstalls) { jobs, live, recent ->
        val items = jobs.filter { it.phase != JobPhase.CANCELLING.name }.map { job ->
            val status = job.status()
            val p = live[job.id]
            QueueItem(
                jobId = job.id,
                title = job.title,
                phase = status.phase,
                label = when (status.phase) {
                    JobPhase.QUEUED -> "Queued"
                    JobPhase.WAITING -> if (status.waitReason == WaitReason.STORAGE) "Waiting for the SD card" else "Waiting for Loom to open"
                    JobPhase.PAUSED -> "Paused"
                    JobPhase.DOWNLOADING -> "Downloading"
                    JobPhase.VERIFYING -> "Checking files"
                    JobPhase.EXTRACTING -> "Unpacking"
                    JobPhase.PROMOTING -> "Moving into place"
                    JobPhase.FAILED -> "Failed"
                    else -> status.phase.name
                },
                detail = status.failure?.detail,
                bytesDone = p?.bytesDone ?: job.bytesDone,
                bytesTotal = job.bytesTotal,
                bytesPerSecond = if (status.phase == JobPhase.DOWNLOADING) p?.bytesPerSecond ?: 0 else 0,
                retryable = status.failure?.retryable ?: true,
            )
        }
        QueueUiState(items.filterNot { it.failed }, items.filter { it.failed }, recent)
    }

    val state: StateFlow<QueueUiState> = combine(
        jobRows, saves.observeConflicts(), saves.observeRestorable(), saves.observeRecentSyncs(),
        combine(serverSaves, busy, saves.observeChosenFolders(), saveFolderQuestion, ::SaveExtras),
    ) { jobs, conflicts, restorable, recent, extras ->
        jobs.copy(
            conflicts = conflicts.map { it.toItem(extras.server, extras.busy, extras.chosen) },
            restorable = restorable.map {
                RestorableSave(it.romId, it.title, it.state == SaveSyncState.LOCAL_MISSING, it.emulator, it.romId in extras.busy)
            },
            recentSaves = recent.map { RecentSave(it.romId, it.title, it.syncedAtMs) },
            saveFolderQuestion = extras.question,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState(emptyList(), emptyList(), emptyList()))

    init {
        // Each conflict's server save is asked about once; a conflict that changes names a new one.
        viewModelScope.launch {
            val asked = mutableSetOf<Int>()
            saves.observeConflicts().collect { conflicts ->
                for (c in conflicts) {
                    val saveId = c.conflict.serverSaveId ?: continue
                    if (!asked.add(saveId)) continue
                    saves.serverSaveInfo(c.conflict.romId, saveId)?.let { info -> serverSaves.update { it + (saveId to info) } }
                }
            }
        }
    }

    override fun pause(jobId: Long) { viewModelScope.launch { downloads.pause(jobId) } }
    override fun resume(jobId: Long) { viewModelScope.launch { downloads.resume(jobId) } }
    override fun retry(jobId: Long) { viewModelScope.launch { downloads.retry(jobId) } }
    override fun cancel(jobId: Long) { viewModelScope.launch { downloads.cancel(jobId) } }

    override fun resolveConflict(romId: Int, title: String, choice: ConflictChoice) = carryOut(romId) { saves.resolve(romId, title, choice) }

    override fun restoreSave(romId: Int, title: String) = carryOut(romId) { saves.restore(romId, title) }

    override fun askSaveFolder(romId: Int, title: String, emulator: String?) {
        viewModelScope.launch { saveFolderQuestion.value = saves.saveFolderChoice(romId).toQuestion(romId, title, emulator) }
    }

    /** Remembered, then the game's save is looked at again: a right pick usually settles its conflict. */
    override fun chooseSaveFolder(id: String) {
        val q = saveFolderQuestion.value ?: return
        saveFolderQuestion.value = null
        carryOut(q.romId) { saves.answer(q, id) ?: "Nothing changed for ${q.title}" }
    }

    override fun dismissSaveFolderQuestion() { saveFolderQuestion.value = null }

    /** One request per game at a time: a second press while it runs does nothing. */
    private fun carryOut(romId: Int, request: suspend () -> String) {
        if (romId in busy.value) return
        busy.update { it + romId }
        viewModelScope.launch {
            try {
                _messages.send(request())
            } finally {
                busy.update { it - romId }
            }
        }
    }
}

@Composable
fun QueueScreen(focusJobId: Long?, focusConflictRomId: Int?, onBack: () -> Unit, vm: QueueViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { message = it; delay(4000); message = null } }
    QueueContent(state, vm, focusJobId, message, focusConflictRomId)
}

/**
 * [focusJobId] or [focusConflictRomId]: the job, or the game's save conflict, focus starts on (the
 * game the library opened the queue for), else the first row to act on: a save conflict, a save to
 * restore, then the jobs.
 */
@Composable
fun QueueContent(
    state: QueueUiState, actions: QueueActions, focusJobId: Long? = null, message: String? = null, focusConflictRomId: Int? = null,
) {
    // The row X acts on: only while it has focus, and as it is now.
    var focusedKey by remember { mutableStateOf<Any?>(null) }
    var confirmCancel by remember { mutableStateOf<QueueItem?>(null) }
    var choosing by remember { mutableStateOf<Int?>(null) }
    var replacing by remember { mutableStateOf<Pair<ConflictItem, ConflictChoice>?>(null) }
    var restoring by remember { mutableStateOf<RestorableSave?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val jobs = state.active + state.failed
    val rows = buildList {
        if (state.conflicts.isNotEmpty()) add(QueueRow.Header("Save conflicts"))
        state.conflicts.forEach { add(QueueRow.Conflict(it)) }
        if (state.restorable.isNotEmpty()) add(QueueRow.Header("Saves to restore from RomM"))
        state.restorable.forEach { add(QueueRow.Restorable(it)) }
        if (state.active.isNotEmpty()) add(QueueRow.Header("In progress"))
        state.active.forEach { add(QueueRow.Job(it)) }
        if (state.failed.isNotEmpty()) add(QueueRow.Header("Needs attention"))
        state.failed.forEach { add(QueueRow.Job(it)) }
    }
    // The rows A acts on, in screen order.
    val keys = rows.filterNot { it is QueueRow.Header }.map { it.key }
    val startKey = jobs.firstOrNull { it.jobId == focusJobId }?.jobId
        ?: state.conflicts.firstOrNull { it.romId == focusConflictRomId }?.let { QueueRow.Conflict(it).key }
        ?: keys.firstOrNull()
    // Where focus goes back into the list: the row last focused, or the one now in its place once that
    // one has left (a job finished or cancelled, a conflict settled), rather than the top of the queue.
    var lastIndex by remember { mutableStateOf<Int?>(null) }
    val entryKey = lastIndex?.let { keys.getOrNull(it.coerceAtMost(keys.lastIndex)) } ?: startKey
    val entry = remember { FocusRequester() }
    // With nothing to act on, the first row the pad can scroll from: the newest finished game, else the newest synced save.
    val readOnlyFirst: Any? = state.finished.firstOrNull()?.let { "done-${it.romId}-${it.installedAt}" }
        ?: state.recentSaves.firstOrNull()?.let { "synced-${it.romId}" }

    fun trackFocus(key: Any, focused: Boolean) {
        if (focused) {
            focusedKey = key
            lastIndex = keys.indexOf(key).takeIf { it >= 0 }
        } else if (focusedKey == key) focusedKey = null
    }
    // A focused row that leaves the list takes focus with it; the row now in its place takes over.
    val focusedLeft = focusedKey != null && focusedKey !in keys
    LaunchedEffect(focusedLeft) { if (focusedLeft) runCatching { entry.requestFocus() } }

    fun primary(item: QueueItem) = when {
        item.failed -> if (item.retryable) actions.retry(item.jobId) else Unit
        item.phase == JobPhase.PAUSED -> actions.resume(item.jobId)
        item.canPause -> actions.pause(item.jobId)
        else -> Unit
    }

    fun Modifier.focusTargets(key: Any) = this
        .then(if (key == startKey) Modifier.focusRequester(first) else Modifier)
        .then(if (key == entryKey) Modifier.focusRequester(entry) else Modifier)

    val hasSaves = state.conflicts.isNotEmpty() || state.restorable.isNotEmpty()
    val hints = listOf(ButtonHint("A", if (hasSaves) "Choose / pause / resume / retry" else "Pause / resume / retry"),
        ButtonHint("X", "Cancel"), ButtonHint("B", "Back"))

    // Focus coming into the screen from outside (a dialog's button that just went away) lands on the
    // list, which restores the row last focused: the one A or X was pressed on.
    Box(Modifier.fillMaxSize().focusProperties { onEnter = { runCatching { listFocus.requestFocus() } } }.focusGroup().onPadAction { action ->
        when (action) {
            PadAction.X -> jobs.firstOrNull { it.jobId == focusedKey && it.canCancel }?.let { confirmCancel = it } != null
            else -> false
        }
    }) {
        ScreenScaffold(title = "Downloads", hints = hints) {
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LoomColors.Accent, modifier = Modifier.padding(horizontal = 8.dp)) }
            if (startKey == null && readOnlyFirst == null) {
                Text("Nothing downloading. Press X on a game in the library to add it here.", color = LoomColors.TextMuted)
                return@ScreenScaffold
            }
            // The start row, or the first read-only one when there's nothing to act on, so the pad can scroll the list.
            // Only while the list has no focus: the first job finishing mustn't pull it off the row the user is on.
            InitialFocus(first, startKey, readOnlyFirst, unless = { listHasFocus })
            // A row far down a long queue isn't composed until it is scrolled to, and can't take focus before.
            LaunchedEffect(focusJobId, focusConflictRomId) {
                val index = rows.indexOfFirst {
                    (it is QueueRow.Job && it.item.jobId == focusJobId) || (it is QueueRow.Conflict && it.item.romId == focusConflictRomId)
                }
                if (index >= 0) listState.scrollToItem(index)
            }
            LazyColumn(
                Modifier.fillMaxSize().onFocusChanged { listHasFocus = it.hasFocus }.focusRequester(listFocus).focusRestorer(entry)
                    .testTag("queue-list"),
                state = listState,
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                // Headers, saves and jobs in one items(), so a job that fails or is retried keeps its row, and
                // focus, as it moves between sections.
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is QueueRow.Header -> SectionHeader(row.text)
                        is QueueRow.Conflict -> ConflictRow(row.item, Modifier.focusTargets(row.key), onFocus = { trackFocus(row.key, it) }) {
                            if (!row.item.busy) choosing = row.item.romId
                        }
                        is QueueRow.Restorable -> RestorableRow(row.item, Modifier.focusTargets(row.key), onFocus = { trackFocus(row.key, it) }) {
                            if (!row.item.busy) restoring = row.item
                        }
                        is QueueRow.Job -> JobRow(row.item, Modifier.focusTargets(row.key), onFocus = { item, focused -> trackFocus(item.jobId, focused) }) {
                            primary(row.item)
                        }
                    }
                }
                if (state.finished.isNotEmpty()) item { SectionHeader("Finished") }
                items(state.finished, key = { "done-${it.romId}-${it.installedAt}" }) { done ->
                    // Focusable only so the pad can scroll through them; there is nothing to press.
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (startKey == null && done == state.finished.first()) Modifier.focusRequester(first).focusRequester(entry) else Modifier)
                            .background(LoomColors.Surface, RoundedCornerShape(12.dp))
                            .focusable(interactionSource = interaction)
                            .indication(interaction, FocusRingIndication(cornerRadius = 12.dp))
                            .padding(Space.m)
                            .testTag("done-${done.romId}"),
                    ) {
                        Text(done.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${done.relPath} · ${formatBytes(done.bytes)} · checked by ${done.verifiedBy.lowercase().replace('_', ' ')}",
                            style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                if (state.recentSaves.isNotEmpty()) item { SectionHeader("Saves synced lately") }
                items(state.recentSaves, key = { "synced-${it.romId}" }) { saved ->
                    val takesFocus = startKey == null && state.finished.isEmpty() && saved == state.recentSaves.first()
                    RecentSaveRow(saved, if (takesFocus) Modifier.focusRequester(first).focusRequester(entry) else Modifier)
                }
            }
        }
        confirmCancel?.let { item ->
            LoomDialog(
                title = if (item.failed) "Remove ${item.title} from the queue?" else "Cancel downloading ${item.title}?",
                onDismiss = { confirmCancel = null },
                body = { Text("Partly downloaded data is deleted. Nothing in your ROMs folder changes.", color = LoomColors.TextMuted) },
                buttons = { f ->
                    LoomButton("Keep", onClick = { confirmCancel = null }, modifier = Modifier.focusRequester(f))
                    LoomButton(if (item.failed) "Remove" else "Cancel download", primary = true, destructive = true,
                        onClick = { actions.cancel(item.jobId); confirmCancel = null })
                },
            )
        }
        choosing?.let { romId ->
            // Settled meanwhile (in the background, or from the library): nothing left to choose.
            val item = state.conflicts.firstOrNull { it.romId == romId }
            LaunchedEffect(item == null) { if (item == null) choosing = null }
            if (item != null) {
                ConflictDialog(
                    item,
                    onAnswer = { choice ->
                        choosing = null
                        when (choice) {
                            // These replace the Thor's save: asked again, with the emulator to close first.
                            ConflictChoice.KEEP_SERVER, ConflictChoice.KEEP_BOTH -> replacing = item to choice
                            else -> actions.resolveConflict(item.romId, item.title, choice)
                        }
                    },
                    // Not an answer: the conflict stays as it is (not skipped) until the folder question is.
                    onChangeFolder = { choosing = null; actions.askSaveFolder(item.romId, item.title, item.emulator) }.takeIf { item.folderChosen },
                )
            }
        }
        replacing?.let { (item, choice) ->
            ReplaceSaveDialog(
                title = if (choice == ConflictChoice.KEEP_SERVER) "Put RomM's save of ${item.title} on the Thor?"
                    else "Upload the Thor's save of ${item.title} as a copy, then take RomM's?",
                emulator = emulatorName(item.emulator),
                confirm = if (choice == ConflictChoice.KEEP_SERVER) "Keep server" else "Keep both",
                onConfirm = { actions.resolveConflict(item.romId, item.title, choice); replacing = null },
                // Back to the choice, not past it: nothing has been decided yet.
                onDismiss = { replacing = null; choosing = item.romId },
            )
        }
        restoring?.let { item ->
            ReplaceSaveDialog(
                title = "Restore ${item.title}'s save from RomM?",
                emulator = emulatorName(item.emulator),
                confirm = "Restore",
                onConfirm = { actions.restoreSave(item.romId, item.title); restoring = null },
                onDismiss = { restoring = null },
            )
        }
        // B or Cancel closes it, back on the conflict's row.
        state.saveFolderQuestion?.let { SaveFolderDialog(it, actions::chooseSaveFolder, actions::dismissSaveFolderQuestion) }
    }
}

@Composable
private fun JobRow(item: QueueItem, modifier: Modifier, onFocus: (QueueItem, Boolean) -> Unit, onPrimary: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { onFocus(item, isFocused) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth()
            .background(if (isFocused) LoomColors.SurfaceHighest else LoomColors.SurfaceRaised, shape)
            .clickable(interaction, FocusRingIndication(cornerRadius = 12.dp), role = Role.Button, onClick = onPrimary)
            .padding(Space.m)
            .testTag("job-${item.jobId}"),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                item.failed -> StatusBadge("Failed", color = LoomColors.Error, onColor = LoomColors.OnError)
                item.phase == JobPhase.PAUSED -> StatusBadge("Paused", color = LoomColors.SurfaceHighest, onColor = LoomColors.Text)
                else -> StatusBadge(item.label, color = if (item.phase.isActive) LoomColors.Accent else LoomColors.SurfaceHighest,
                    onColor = if (item.phase.isActive) LoomColors.OnAccent else LoomColors.Text)
            }
        }
        if (!item.failed) {
            LoomProgressBar(
                if (item.bytesTotal > 0) item.bytesDone.toFloat() / item.bytesTotal else null,
                color = if (item.phase.isActive) LoomColors.Accent else LoomColors.TextFaint,
            )
            Text(transferLine(item), style = MaterialTheme.typography.labelMedium.tabular(), color = LoomColors.TextMuted)
        } else {
            Text(item.detail ?: "The download failed.", style = MaterialTheme.typography.bodySmall, color = LoomColors.TextMuted)
            if (!item.retryable) Text("Retrying won't help until this is fixed.", style = MaterialTheme.typography.labelMedium, color = LoomColors.Error)
        }
    }
}

/** "1.2 GB of 4.3 GB · 11.4 MB/s · 4 min left" */
fun transferLine(item: QueueItem): String = buildList {
    add("${formatBytes(item.bytesDone)} of ${formatBytes(item.bytesTotal)}")
    if (item.bytesPerSecond > 0) {
        add("${formatBytes(item.bytesPerSecond)}/s")
        val left = (item.bytesTotal - item.bytesDone).coerceAtLeast(0) / item.bytesPerSecond
        add(if (left < 60) "${left}s left" else if (left < 3600) "${left / 60} min left" else "${left / 3600} h ${(left % 3600) / 60} min left")
    }
}.joinToString(" · ")
