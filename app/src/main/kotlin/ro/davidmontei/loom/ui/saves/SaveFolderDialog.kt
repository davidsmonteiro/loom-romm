package ro.davidmontei.loom.ui.saves

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ro.davidmontei.loom.data.saves.EdenFolderChoice
import ro.davidmontei.loom.data.saves.EdenSaves
import ro.davidmontei.loom.data.saves.SaveRepository
import ro.davidmontei.loom.data.saves.SyncedFolderChoice
import ro.davidmontei.loom.data.saves.emulatorName
import ro.davidmontei.loom.storage.engine.formatBytes
import ro.davidmontei.loom.storage.saves.SwitchTitleId
import ro.davidmontei.loom.ui.components.LoomButton
import ro.davidmontei.loom.ui.components.LoomDialog
import ro.davidmontei.loom.ui.components.LoomRow
import ro.davidmontei.loom.ui.components.Space
import ro.davidmontei.loom.ui.components.dateTime
import ro.davidmontei.loom.ui.theme.LoomColors
import java.nio.file.Paths

/** One folder a game's save can be synced from, as a question lists it. */
data class SaveFolderOption(
    /** What picking it answers: the title ID it's named after, or (for [SaveFolderQuestion.Kind.SYNCED]) its path. */
    val id: String,
    val fileCount: Int,
    val bytes: Long,
    val changedAtMs: Long?,
    /** What the list calls it: the title ID, or which of the game's two folders it is. */
    val name: String = id,
)

/**
 * A question about a game's save folder, from its details or its save conflict in the queue.
 * [unavailable] says why there's nothing to choose from.
 */
data class SaveFolderQuestion(
    val romId: Int,
    val title: String,
    /** The emulator id the game's saves sync as, e.g. "eden". */
    val emulator: String?,
    val options: List<SaveFolderOption>,
    /** The option chosen before ([Kind.TITLE]), or the folder Loom syncs, or synced until now ([Kind.SYNCED]). */
    val current: String?,
    val unavailable: String? = null,
    val kind: Kind = Kind.TITLE,
) {
    enum class Kind {
        /** Which of the emulator's save folders is the game's: Loom can't read its title ID. */
        TITLE,

        /** Which of the game's two folders (Eden's console-wide one and the profile's) Loom syncs. */
        SYNCED,
    }
}

/** Eden's title folders as the question lists them. */
fun EdenFolderChoice.toQuestion(romId: Int, title: String, emulator: String?): SaveFolderQuestion = when (this) {
    is EdenFolderChoice.Options -> SaveFolderQuestion(
        romId, title, emulator,
        folders.map { SaveFolderOption(it.titleId.toString(), it.fileCount, it.bytes, it.newestModified?.toEpochMilli()) },
        current?.toString(),
    )
    is EdenFolderChoice.Unavailable -> SaveFolderQuestion(romId, title, emulator, emptyList(), null, unavailable = reason)
}

/** A game's two Eden folders as the question lists them. */
fun SyncedFolderChoice.toQuestion(romId: Int, title: String, emulator: String?): SaveFolderQuestion = when (this) {
    is SyncedFolderChoice.Options -> SaveFolderQuestion(
        romId, title, emulator,
        folders.map {
            SaveFolderOption(it.folder.toString(), it.fileCount, it.bytes, it.newestModified?.toEpochMilli(), name = "${EdenSaves.kind(it.folder)} folder"
                .replaceFirstChar(Char::uppercase))
        },
        current?.toString(), kind = SaveFolderQuestion.Kind.SYNCED,
    )
    is SyncedFolderChoice.Unavailable -> SaveFolderQuestion(romId, title, emulator, emptyList(), null, reason, SaveFolderQuestion.Kind.SYNCED)
}

/**
 * Carries out the user's pick [id] among [q]'s options, then looks at the game's save again:
 * how it went, in words; null for an answer that names no option.
 */
suspend fun SaveRepository.answer(q: SaveFolderQuestion, id: String): String? = when (q.kind) {
    SaveFolderQuestion.Kind.TITLE -> SwitchTitleId.parse(id)?.let { chooseSaveFolder(q.romId, q.title, it) }
    SaveFolderQuestion.Kind.SYNCED -> q.options.firstOrNull { it.id == id }?.let { chooseSyncedFolder(q.romId, q.title, Paths.get(it.id)) }
}

/**
 * A question about a game's save folder. Which of the emulator's folders is the game's
 * ([SaveFolderQuestion.Kind.TITLE], when Loom can't read its title ID): the folders no other game
 * has, newest first with when each changed, so the one the game wrote when it last saved is easy
 * to spot. Or which of its two folders Loom syncs ([SaveFolderQuestion.Kind.SYNCED]), each with
 * when it changed and what it holds. Focus starts on the newest; B, or Cancel, changes nothing.
 */
@Composable
fun SaveFolderDialog(q: SaveFolderQuestion, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    val emulator = emulatorName(q.emulator)
    val synced = q.kind == SaveFolderQuestion.Kind.SYNCED
    LoomDialog(
        title = if (synced) "Which $emulator folder should Loom sync ${q.title}'s save from?" else "Which $emulator save folder is ${q.title}'s?",
        onDismiss = onDismiss,
        body = {
            Text(
                when {
                    q.unavailable != null -> q.unavailable
                    synced -> "$emulator keeps this game's save in two folders, its console-wide one and the profile's, and they hold " +
                        "different saves. Loom syncs the one you pick from now on, and leaves the other as it is."
                    q.options.isEmpty() -> "$emulator has no save folder that isn't another game's. Play the game until it saves, then choose."
                    else -> "Loom can't read this game's title ID, which names its save folder. Pick the one that changed when the game " +
                        "last saved. Its details can change this later."
                },
                style = MaterialTheme.typography.bodyMedium, color = LoomColors.TextMuted,
            )
            if (q.options.isNotEmpty()) {
                val newest = remember { FocusRequester() }
                LaunchedEffect(q) { delay(50); runCatching { newest.requestFocus() } } // after the dialog's own first focus
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 148.dp).testTag("save-folder-options"),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    itemsIndexed(q.options, key = { _, option -> option.id }) { i, option ->
                        val changed = option.changedAtMs?.let { "changed ${dateTime(it)}" }
                        val holds = listOfNotNull(
                            if (option.fileCount == 1) "1 file" else "${option.fileCount} files",
                            formatBytes(option.bytes),
                            (if (synced) "synced so far" else "chosen now").takeIf { option.id == q.current },
                        ).joinToString(" · ")
                        LoomRow(
                            title = option.name,
                            // Only two folders side by side: room for when each changed and what it holds, a line each.
                            subtitle = if (synced) listOfNotNull(changed, holds).joinToString("\n") else listOfNotNull(changed, holds).joinToString(" · "),
                            subtitleLines = if (synced) 2 else 1,
                            dense = true,
                            selected = option.id == q.current,
                            onClick = { onChoose(option.id) },
                            modifier = (if (i == 0) Modifier.focusRequester(newest) else Modifier).testTag("save-folder-${option.id}"),
                        )
                    }
                }
            }
        },
        buttons = { first -> LoomButton("Cancel", onDismiss, Modifier.focusRequester(first)) },
    )
}
