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
import ro.davidmontei.loom.di.AppScope
import javax.inject.Inject

/**
 * Commands Loom accepts without its UI, for scripts (adb, Termux) and launcher shortcuts.
 *
 *     adb shell am broadcast -n ro.davidmontei.loom/.headless.CommandReceiver \
 *         -a ro.davidmontei.loom.action.SYNC_CATALOG --ez full true
 *
 * `am broadcast` prints the result code and a one-line message.
 */
object Commands {
    const val SYNC_CATALOG = "ro.davidmontei.loom.action.SYNC_CATALOG"
    const val PUSH_SAVES = "ro.davidmontei.loom.action.PUSH_SAVES"
    const val PULL_SAVES = "ro.davidmontei.loom.action.PULL_SAVES"
    const val EXTRA_FULL = "full"

    /** Result codes, as `am broadcast` shows them. */
    const val RESULT_OK = 0
    const val RESULT_NOT_PAIRED = 1
    const val RESULT_UNAVAILABLE = 2
    const val RESULT_UNKNOWN = 3

    data class Result(val code: Int, val message: String)

    /** Runs a command. Every command only queues work, so this returns at once. */
    suspend fun run(intent: Intent, credentials: CredentialStore, sync: CatalogSyncScheduler): Result {
        when (intent.action) {
            SYNC_CATALOG -> {
                credentials.awaitLoaded()
                if (credentials.current.value == null) return Result(RESULT_NOT_PAIRED, "Loom isn't paired with RomM; open Loom to pair")
                val full = intent.getBooleanExtra(EXTRA_FULL, false)
                sync.enqueue(full)
                return Result(RESULT_OK, if (full) "Full library sync queued" else "Library sync queued")
            }
            PUSH_SAVES, PULL_SAVES ->
                return Result(RESULT_UNAVAILABLE, "Save sync isn't available yet (planned for Loom's Phase 2)")
            else -> return Result(RESULT_UNKNOWN, "Unknown command ${intent.action}")
        }
    }
}

/**
 * Exported so scripts can reach it with an explicit component name. Its commands only queue
 * work (a catalog sync), so no permission is required; see the README.
 */
@AndroidEntryPoint
class CommandReceiver : BroadcastReceiver() {
    @Inject lateinit var credentials: CredentialStore
    @Inject lateinit var sync: CatalogSyncScheduler
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                val result = Commands.run(intent, credentials, sync)
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
    @Inject lateinit var sync: CatalogSyncScheduler
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = Intent(intent)
        val app = applicationContext
        scope.launch {
            val result = Commands.run(command, credentials, sync)
            withContext(Dispatchers.Main) {
                Toast.makeText(app, result.message, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
