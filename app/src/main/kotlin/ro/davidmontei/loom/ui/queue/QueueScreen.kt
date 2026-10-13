package ro.davidmontei.loom.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
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
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.davidmontei.loom.data.db.RecentInstall
import ro.davidmontei.loom.data.download.DownloadRepository
import ro.davidmontei.loom.data.download.status
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
}

data class QueueUiState(val active: List<QueueItem>, val failed: List<QueueItem>, val finished: List<RecentInstall>)

interface QueueActions {
    fun pause(jobId: Long)
    fun resume(jobId: Long)
    fun retry(jobId: Long)
    fun cancel(jobId: Long)
}

@HiltViewModel
class QueueViewModel @Inject constructor(private val downloads: DownloadRepository) : ViewModel(), QueueActions {
    val state: StateFlow<QueueUiState> = combine(downloads.jobs, downloads.progress, downloads.recentInstalls) { jobs, live, recent ->
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState(emptyList(), emptyList(), emptyList()))

    override fun pause(jobId: Long) { viewModelScope.launch { downloads.pause(jobId) } }
    override fun resume(jobId: Long) { viewModelScope.launch { downloads.resume(jobId) } }
    override fun retry(jobId: Long) { viewModelScope.launch { downloads.retry(jobId) } }
    override fun cancel(jobId: Long) { viewModelScope.launch { downloads.cancel(jobId) } }
}

@Composable
fun QueueScreen(onBack: () -> Unit, vm: QueueViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    QueueContent(state, vm)
}

private val queueHints = listOf(ButtonHint("A", "Pause / resume / retry"), ButtonHint("X", "Cancel"), ButtonHint("B", "Back"))

@Composable
fun QueueContent(state: QueueUiState, actions: QueueActions) {
    // The job X acts on: only while it has focus, and as it is now.
    var focusedId by remember { mutableStateOf<Long?>(null) }
    var confirmCancel by remember { mutableStateOf<QueueItem?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val firstItem = state.active.firstOrNull() ?: state.failed.firstOrNull()

    fun trackFocus(item: QueueItem, focused: Boolean) {
        if (focused) focusedId = item.jobId else if (focusedId == item.jobId) focusedId = null
    }

    fun primary(item: QueueItem) = when {
        item.failed -> if (item.retryable) actions.retry(item.jobId) else Unit
        item.phase == JobPhase.PAUSED -> actions.resume(item.jobId)
        item.canPause -> actions.pause(item.jobId)
        else -> Unit
    }

    Box(Modifier.fillMaxSize().onPadAction { action ->
        when (action) {
            PadAction.X -> (state.active + state.failed).firstOrNull { it.jobId == focusedId }?.let { confirmCancel = it } != null
            else -> false
        }
    }) {
        ScreenScaffold(title = "Downloads", hints = queueHints) {
            if (firstItem == null && state.finished.isEmpty()) {
                Text("Nothing downloading. Press X on a game in the library to add it here.", color = LoomColors.TextMuted)
                return@ScreenScaffold
            }
            // The first job, or the newest finished game when nothing is queued, so the pad can scroll the list.
            // Only while the list has no focus: the first job finishing mustn't pull it off the row the user is on.
            InitialFocus(first, firstItem?.jobId, state.finished.isEmpty(), unless = { listHasFocus })
            LazyColumn(Modifier.fillMaxSize().onFocusChanged { listHasFocus = it.hasFocus }.testTag("queue-list"), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (state.active.isNotEmpty()) item { SectionHeader("In progress") }
                items(state.active, key = { it.jobId }) { item ->
                    JobRow(item, if (item == firstItem) Modifier.focusRequester(first) else Modifier, onFocus = ::trackFocus) { primary(item) }
                }
                if (state.failed.isNotEmpty()) item { SectionHeader("Needs attention") }
                items(state.failed, key = { it.jobId }) { item ->
                    JobRow(item, if (item == firstItem) Modifier.focusRequester(first) else Modifier, onFocus = ::trackFocus) { primary(item) }
                }
                if (state.finished.isNotEmpty()) item { SectionHeader("Finished") }
                items(state.finished, key = { "done-${it.romId}-${it.installedAt}" }) { done ->
                    // Focusable only so the pad can scroll through them; there is nothing to press.
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier.fillMaxWidth()
                            .then(if (firstItem == null && done == state.finished.first()) Modifier.focusRequester(first) else Modifier)
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
