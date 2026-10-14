package ro.davidmontei.loom.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A DataStore [file] that only Loom writes, and only through this one instance: it is read once,
 * then kept in memory from what each write returns, so [data] shows a write as soon as it has
 * returned, to every collector however long it has been collecting.
 *
 * Following DataStore's own flow isn't safe: DataStore 1.2.1 can leave a collector that subscribed
 * as a write landed on the value from before it, for good (see CredentialStore), and the settings
 * have long-lived readers (the catalog refresh schedule, each screen's state). Writes take a lock,
 * so they follow each other, and a first read older than a write doesn't undo it.
 */
class WriteThroughDataStore<T : Any>(private val file: DataStore<T>, scope: CoroutineScope) : DataStore<T> {
    private val state = MutableStateFlow<T?>(null)
    private val loaded = CompletableDeferred<Unit>()
    private val lock = Mutex()
    private var written = false

    init {
        scope.launch {
            try {
                val stored = file.data.first()
                lock.withLock { if (!written) state.value = stored } // a read older than a write doesn't undo it
                loaded.complete(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loaded.completeExceptionally(e) // readers get the error, as they would from the file's own flow
            }
        }
    }

    override val data: Flow<T> = flow {
        loaded.await()
        emitAll(state.filterNotNull())
    }

    override suspend fun updateData(transform: suspend (t: T) -> T): T = lock.withLock {
        file.updateData(transform).also {
            state.value = it
            written = true
            loaded.complete(Unit)
        }
    }
}
