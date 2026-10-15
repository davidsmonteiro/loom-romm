package ro.davidmontei.loom.net

import ro.davidmontei.loom.data.sent.SendToDeviceStore
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.dto.HeartbeatDto
import ro.davidmontei.loom.romm.rommCall

/**
 * What RomM says of itself at each heartbeat: its version, which Settings → Server shows and which
 * features that need a newer RomM depend on, and whether it can send games to devices. Every heartbeat
 * Loom makes to the paired server goes through here (a library sync, the background refresh, the
 * reachability check), so an upgraded server is seen at once, not at the next library sync.
 */
class ServerInfo(private val settings: SettingsStore, private val sendToDevice: SendToDeviceStore) {

    /** Asks [api] for its heartbeat and records what it says. */
    suspend fun heartbeat(api: RommApi): HeartbeatDto = rommCall { api.heartbeat() }.also { record(it) }

    suspend fun record(heartbeat: HeartbeatDto) {
        val version = heartbeat.system.version
        if (settings.snapshot().serverVersion != version) settings.setServerVersion(version)
        // A heartbeat that says nothing of device installs comes from a RomM without them: before 5.4, or
        // built from source before then, whose version ("development") reads as the newest.
        sendToDevice.setInstallsEnabled(heartbeat.deviceInstall?.enabled ?: false)
    }
}
