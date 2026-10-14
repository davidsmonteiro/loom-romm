package ro.davidmontei.loom.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.davidmontei.loom.romm.pairing.PairedCredentials

/** The token Loom sends to RomM, plus who it belongs to. Never logged. */
data class Credentials(
    val token: String,
    val username: String,
    val deviceId: String?,
    val expiresAt: String?,
) {
    override fun toString() = "Credentials(username=$username, deviceId=$deviceId, token=***)"
}

/**
 * Holds the RomM credentials: encrypted at rest with [TokenCipher], mirrored in memory so the
 * OkHttp interceptor can read them without blocking. [current] is null until [awaitLoaded]
 * completes and whenever Loom is not paired.
 */
class CredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    scope: CoroutineScope,
) {
    private val state = MutableStateFlow<Credentials?>(null)
    private val loaded = CompletableDeferred<Unit>()
    private val lock = Mutex()
    private var written = false

    val current: StateFlow<Credentials?> = state.asStateFlow()

    init {
        // Only this store writes this file, so it reads it once and then shows its own writes as
        // they're made. Following DataStore's flow instead isn't safe: DataStore 1.2.1 can leave a
        // collector that subscribed as a write landed on the value from before it, for good.
        scope.launch {
            val stored = read(dataStore.data.first())
            lock.withLock { if (!written) state.value = stored } // a read older than a save doesn't undo it
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    suspend fun save(paired: PairedCredentials) = write {
        it[TOKEN] = cipher.encrypt(paired.token)
        it[USERNAME] = paired.username
        if (paired.deviceId != null) it[DEVICE_ID] = paired.deviceId!! else it.remove(DEVICE_ID)
        if (paired.expiresAt != null) it[EXPIRES_AT] = paired.expiresAt!! else it.remove(EXPIRES_AT)
    }

    suspend fun clear() = write { it.remove(TOKEN); it.remove(USERNAME); it.remove(DEVICE_ID); it.remove(EXPIRES_AT) }

    private suspend fun write(change: (MutablePreferences) -> Unit) = lock.withLock {
        state.value = read(dataStore.edit(change))
        written = true
    }

    private fun read(prefs: Preferences): Credentials? {
        val blob = prefs[TOKEN] ?: return null
        val token = cipher.decrypt(blob) ?: return null // key gone: behave as unpaired
        return Credentials(token, prefs[USERNAME].orEmpty(), prefs[DEVICE_ID], prefs[EXPIRES_AT])
    }

    private companion object {
        val TOKEN = stringPreferencesKey("romm_token_sealed")
        val USERNAME = stringPreferencesKey("romm_username")
        val DEVICE_ID = stringPreferencesKey("romm_device_id")
        val EXPIRES_AT = stringPreferencesKey("romm_token_expires_at")
    }
}
