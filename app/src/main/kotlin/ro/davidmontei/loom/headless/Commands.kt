package ro.davidmontei.loom.headless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.auth.CredentialStore
import ro.davidmontei.loom.data.catalog.CatalogSyncScheduler
import ro.davidmontei.loom.data.saves.SaveSyncKind
import ro.davidmontei.loom.data.saves.SaveSyncScheduler
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.di.AppScope
import javax.inject.Inject

/**
 * Commands Loom accepts without its UI, for scripts (adb, Termux) and launcher shortcuts.
 *
 *     adb shell am broadcast -n ro.davidmontei.loom/.headless.CommandReceiver \
 *         -a ro.davidmontei.loom.action.SYNC_CATALOG --ez full true
 *
 * - SYNC_CATALOG (`--ez full true` for a full pass): syncs the library.
 * - PUSH_SAVES: uploads every save that changed on the Thor while RomM's didn't, once it has been
 *   left alone for a minute. Conflicts are only recorded.
 * - PULL_SAVES: downloads RomM's newer saves, but only where the Thor's save is unchanged since the
 *   last sync or absent, the newer save comes from the same emulator, and only after backing the
 *   Thor's save up. Close the emulator first. It never resolves a conflict.
 *
 * `am broadcast` prints the result code and a one-line message: [RESULT_OK] (queued),
 * [RESULT_NOT_PAIRED], [RESULT_UNAVAILABLE] (Loom isn't set up yet) or [RESULT_UNKNOWN]. A queued
 * save command reports what it couldn't do alone (conflicts, newer saves) as a notification.
 */
object Commands {
    const val SYNC_CATALOG = "ro.davidmontei.loom.action.SYNC_CATALOG"
    const val PUSH_SAVES = "ro.davidmontei.loom.action.PUSH_SAVES"
    const val PULL_SAVES = "ro.davidmontei.loom.action.PULL_SAVES"
    const val EXTRA_FULL = "full"

    /** Result codes, as `am broadcast` shows them. */
    const val RESULT_OK = 0
    const val RESULT_NOT_PAIRED = 1

    /** Loom can't run the command yet: setup isn't finished. */
    const val RESULT_UNAVAILABLE = 2
    const val RESULT_UNKNOWN = 3

    data class Result(val code: Int, val message: String)

    /** Runs a command. Every command only queues work, so this returns at once. */
    suspend fun run(intent: Intent, credentials: CredentialStore, settings: SettingsStore, sync: CatalogSyncScheduler, saves: SaveSyncScheduler): Result {
        val action = intent.action
        if (action != SYNC_CATALOG && action != PUSH_SAVES && action != PULL_SAVES) return Result(RESULT_UNKNOWN, "Unknown command $action")
        credentials.awaitLoaded()
        if (credentials.current.value == null) return Result(RESULT_NOT_PAIRED, "Loom isn't paired with RomM; open Loom to pair")
        return when (action) {
            SYNC_CATALOG -> {
                val full = intent.getBooleanExtra(EXTRA_FULL, false)
                sync.enqueue(full)
                Result(RESULT_OK, if (full) "Full library sync queued" else "Library sync queued")
            }
            else -> {
                if (!settings.snapshot().onboardingComplete) return Result(RESULT_UNAVAILABLE, "Finish setting up Loom first")
                if (action == PUSH_SAVES) {
                    saves.runNow(SaveSyncKind.PUSH)
                    Result(RESULT_OK, "Save upload queued")
                } else {
                    saves.runNow(SaveSyncKind.PULL)
                    Result(RESULT_OK, "Download of newer saves queued: only saves unchanged on the Thor are replaced, each backed up first")
                }
            }
        }
    }
}

/**
 * Exported so scripts can reach it with an explicit component name. No permission is required:
 * its commands only queue work that never destroys anything (a catalog sync, uploads, and
 * downloads that replace only unchanged saves, after a backup); see the README.
 */
@AndroidEntryPoint
class CommandReceiver : BroadcastReceiver() {
    @Inject lateinit var credentials: CredentialStore
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var sync: CatalogSyncScheduler
    @Inject lateinit var saves: SaveSyncScheduler
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val result = Commands.run(intent, credentials, settings, sync, saves)
                runCatching { pending.setResult(result.code, result.message, null) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Invisible activity behind the launcher shortcuts: runs the command, shows a toast, closes. */
@AndroidEntryPoint
class CommandActivity : ComponentActivity() {
    @Inject lateinit var credentials: CredentialStore
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var sync: CatalogSyncScheduler
    @Inject lateinit var saves: SaveSyncScheduler
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = Intent(intent)
        val app = applicationContext
        scope.launch {
            val result = Commands.run(command, credentials, settings, sync, saves)
            withContext(Dispatchers.Main) {
                Toast.makeText(app, result.message, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
