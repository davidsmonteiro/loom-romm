package ro.davidmontei.loom.data.sent

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What decides whether games sent from RomM's web UI reach Loom, apart from the server's version and
 * the pairing (see [ro.davidmontei.loom.data.settings.Settings] and the credentials).
 */
data class SendToDeviceSettings(
    /** The owner's switch in Settings → Downloads: on until switched off. */
    val receive: Boolean = true,
    /** What RomM's last heartbeat said of device installs; null before one did (and from RomM before 5.4). */
    val installsEnabled: Boolean? = null,
    /** RomM refused the device or its token for installs: only pairing again can fix that. */
    val needsPairing: Boolean = false,
)

/**
 * Send-to-device state in its own small DataStore file, `send_to_device`, apart from the settings file
 * the rest of Loom writes. An edit that changes nothing leaves the file alone (DataStore compares), so
 * the heartbeat can record [SendToDeviceSettings.installsEnabled] each time it runs.
 */
class SendToDeviceStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<SendToDeviceSettings> = dataStore.data.map { p ->
        SendToDeviceSettings(
            receive = p[RECEIVE] ?: true,
            installsEnabled = p[INSTALLS_ENABLED],
            needsPairing = p[NEEDS_PAIRING] ?: false,
        )
    }

    suspend fun snapshot(): SendToDeviceSettings = settings.first()

    suspend fun setReceive(on: Boolean) = dataStore.edit { it[RECEIVE] = on }

    /** [enabled] null forgets it: the server doesn't say (RomM before 5.4). */
    suspend fun setInstallsEnabled(enabled: Boolean?) = dataStore.edit {
        if (enabled == null) it.remove(INSTALLS_ENABLED) else it[INSTALLS_ENABLED] = enabled
    }

    suspend fun setNeedsPairing(needs: Boolean) = dataStore.edit { it[NEEDS_PAIRING] = needs }

    private companion object {
        val RECEIVE = booleanPreferencesKey("receive")
        val INSTALLS_ENABLED = booleanPreferencesKey("installs_enabled")
        val NEEDS_PAIRING = booleanPreferencesKey("needs_pairing")
    }
}
