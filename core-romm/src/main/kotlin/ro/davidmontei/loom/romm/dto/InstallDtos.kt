package ro.davidmontei.loom.romm.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Send to device: RomM 5.4.0's endpoints/device/install.py and the device update in
// endpoints/device/__init__.py, declaring only what Loom reads, like CatalogDtos. RomM asks a device
// to install a game with an install request; the device claims it, queues the game and reports how
// that went, which ends the request.

/** The capability a device declares so RomM's web UI offers it under "Install on device". */
const val REMOTE_INSTALL = "remote_install"

/** Reported once the game is in Loom's queue; RomM shows "Added to downloads". */
const val INSTALL_DONE = "done"

/** Reported when the game is already on the card; RomM shows "Already installed". */
const val INSTALL_ALREADY_INSTALLED = "already_installed"

/** Reported, with a reason, when Loom can't queue the game; RomM shows "Failed" and the reason. */
const val INSTALL_FAILED = "failed"

/** Longest reason RomM takes (`InstallRequestUpdatePayload.reason`); a longer one is a 422. */
const val MAX_INSTALL_REASON = 500

/**
 * [text] cut to what RomM takes as a report's reason. A surrogate pair is never split, so the reason
 * stays valid text (RomM counts code points, never more than the UTF-16 units kept here).
 */
fun installReason(text: String): String {
    if (text.length <= MAX_INSTALL_REASON) return text
    val end = if (Character.isHighSurrogate(text[MAX_INSTALL_REASON - 1])) MAX_INSTALL_REASON - 1 else MAX_INSTALL_REASON
    return text.substring(0, end)
}

/**
 * `InstallRequestSchema`: a game RomM's owner sent to this device. [id] is a UUID. `file_ids` is left
 * out on purpose: Loom installs a game by its own per-platform file rules, as it does from the library.
 */
@Serializable
data class InstallRequestDto(
    val id: String,
    @SerialName("rom_id") val romId: Int,
    /** `InstallStatus`: pending, taken, done, already_installed, failed or cancelled. */
    val status: String,
)

/**
 * `InstallRequestUpdatePayload`: how the install went. [reason] is left out when null; cut it with
 * [installReason].
 */
@Serializable
data class InstallReportRequest(
    val status: String,
    val reason: String? = null,
)

/**
 * `DeviceUpdatePayload`, carrying only the capabilities. RomM replaces the device's whole capability
 * dict with [capabilities], so Loom sends every key it owns each time.
 */
@Serializable
data class DeviceUpdateRequest(
    val capabilities: Map<String, Boolean>,
)

/**
 * `DeviceSchema` as `PUT /api/devices/{id}` returns it, read only for what Loom declared. Older RomMs
 * don't send [capabilities].
 */
@Serializable
data class DeviceCapabilitiesDto(
    val id: String,
    val capabilities: Map<String, Boolean>? = null,
)

/** `DeviceInstallDict` in the heartbeat (RomM 5.4.0 on). `EXCLUDED_PLATFORM_SLUGS` isn't read: RomM refuses those itself. */
@Serializable
data class DeviceInstallDto(
    @SerialName("ENABLED") val enabled: Boolean,
)
