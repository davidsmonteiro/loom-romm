package ro.davidmontei.loom.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * Where the RomM server is: one base URL, whichever kind fits the user's setup (a LAN address,
 * a Tailscale IP or MagicDNS name, or a public domain). Changed in Settings when that changes.
 */
data class ServerConfig(val url: HttpUrl)

/**
 * How the last background save sync ended: when, and what it did or why it couldn't run, in words.
 * [needsPairing]: it failed until Loom pairs again (RomM rejected the token, or has no device for Loom).
 */
data class SaveSyncOutcome(val atEpochMs: Long, val summary: String, val failed: Boolean, val needsPairing: Boolean = false)

/** How often Loom refreshes the catalog in the background. */
enum class CatalogRefresh(val hours: Int) {
    OFF(0), EVERY_6_HOURS(6), EVERY_12_HOURS(12), EVERY_24_HOURS(24);

    companion object {
        val DEFAULT = EVERY_12_HOURS
    }
}

data class Settings(
    val server: ServerConfig?,
    val serverVersion: String?,
    /** Absolute path of the ROMs root Cocoon scans, e.g. /storage/9C33-6BBD/ROMs. */
    val romsRoot: String?,
    val onboardingComplete: Boolean,
    /** Swap A/B for Nintendo-layout pads: confirm on the right face button. */
    val swapConfirmBack: Boolean,
    val maxConcurrentDownloads: Int,
    /** Server-clock watermark for incremental catalog sync (ISO-8601), null before the first sync. */
    val catalogWatermark: String?,
    val lastCatalogSyncEpochMs: Long?,
    /**
     * Which ROM fields the catalog rows were last fully written with (`CatalogSyncer.CATALOG_FIELDS`);
     * rows written by an older Loom lack the newer ones until a full sync.
     */
    val catalogFields: Int = 1,
    /** Upload changed saves in the background (a periodic scan, and when Loom opens or the card mounts). */
    val autoUploadSaves: Boolean = true,
    /**
     * Azahar's user directory when the user set one; null means the n3ds system folder, when it
     * holds Azahar's `sdmc/Nintendo 3DS` (as on the Thor).
     */
    val azaharUserDir: String? = null,
    /**
     * Eden's save folder (the one holding `user/save`) when the user set one; null means
     * `<ROMs root>/saves/Eden`, when it holds `user/save` (as on the Thor).
     */
    val edenSaveDir: String? = null,
    /** The Eden profile (user ID folder) whose saves Loom syncs, when Eden has several; null until chosen. */
    val edenProfile: String? = null,
    /** Null until a background save sync has run. */
    val lastSaveSync: SaveSyncOutcome? = null,
    val catalogRefresh: CatalogRefresh = CatalogRefresh.DEFAULT,
    /** Background refreshes wait for an unmetered network. */
    val refreshOnWifiOnly: Boolean = false,
)

/**
 * User settings in Preferences DataStore. Credentials live separately in CredentialStore.
 * [androidId] is the device's `Settings.Secure.ANDROID_ID`, which stays the same for Loom across
 * reinstalls (same signing key, device and user); null where there is none.
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
    private val androidId: () -> String? = { null },
) {

    val settings: Flow<Settings> = dataStore.data.map { p ->
        Settings(
            server = p[SERVER_URL]?.toHttpUrlOrNull()?.let(::ServerConfig),
            serverVersion = p[SERVER_VERSION],
            romsRoot = p[ROMS_ROOT],
            onboardingComplete = p[ONBOARDED] ?: false,
            swapConfirmBack = p[SWAP_AB] ?: false,
            maxConcurrentDownloads = (p[CONCURRENCY] ?: 1).coerceIn(1, 3),
            catalogWatermark = p[WATERMARK],
            lastCatalogSyncEpochMs = p[LAST_SYNC]?.toLongOrNull(),
            catalogFields = p[CATALOG_FIELDS] ?: 1,
            autoUploadSaves = p[AUTO_UPLOAD_SAVES] ?: true,
            azaharUserDir = p[AZAHAR_USER_DIR],
            edenSaveDir = p[EDEN_SAVE_DIR],
            edenProfile = p[EDEN_PROFILE],
            lastSaveSync = p[SAVE_SYNC_AT]?.let { at ->
                SaveSyncOutcome(at, p[SAVE_SYNC_SUMMARY].orEmpty(), p[SAVE_SYNC_FAILED] ?: false, p[SAVE_SYNC_NEEDS_PAIRING] ?: false)
            },
            catalogRefresh = CatalogRefresh.entries.find { it.hours == p[REFRESH_HOURS] } ?: CatalogRefresh.DEFAULT,
            refreshOnWifiOnly = p[REFRESH_WIFI_ONLY] ?: false,
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    /**
     * The id Loom pairs with, so RomM keeps one Device for this Thor: re-pairing with the same id
     * reuses it, with its save sync history. Derived from ANDROID_ID, which survives reinstalling
     * Loom, so a reinstall pairs as the same device; random where there is no usable ANDROID_ID.
     * Once stored it never changes, so an install that already paired keeps its device.
     */
    suspend fun deviceIdentifier(): String {
        dataStore.data.first()[DEVICE_IDENTIFIER]?.let { return it }
        val id = "loom-" + (androidId()?.trim()?.lowercase()?.takeIf(::usableAndroidId) ?: UUID.randomUUID().toString())
        dataStore.edit { if (it[DEVICE_IDENTIFIER] == null) it[DEVICE_IDENTIFIER] = id }
        return dataStore.data.first()[DEVICE_IDENTIFIER]!!
    }

    suspend fun setAutoUploadSaves(on: Boolean) = dataStore.edit { it[AUTO_UPLOAD_SAVES] = on }

    /** [path] null goes back to finding Azahar's folder in the n3ds system folder. */
    suspend fun setAzaharUserDir(path: String?) = dataStore.edit {
        if (path.isNullOrBlank()) it.remove(AZAHAR_USER_DIR) else it[AZAHAR_USER_DIR] = path
    }

    /** [path] null goes back to looking for Eden's saves in `<ROMs root>/saves/Eden`. */
    suspend fun setEdenSaveDir(path: String?) = dataStore.edit {
        if (path.isNullOrBlank()) it.remove(EDEN_SAVE_DIR) else it[EDEN_SAVE_DIR] = path
    }

    suspend fun setEdenProfile(profile: String?) = dataStore.edit {
        if (profile.isNullOrBlank()) it.remove(EDEN_PROFILE) else it[EDEN_PROFILE] = profile
    }

    suspend fun setLastSaveSync(outcome: SaveSyncOutcome) = dataStore.edit {
        it[SAVE_SYNC_AT] = outcome.atEpochMs
        it[SAVE_SYNC_SUMMARY] = outcome.summary
        it[SAVE_SYNC_FAILED] = outcome.failed
        it[SAVE_SYNC_NEEDS_PAIRING] = outcome.needsPairing
    }

    suspend fun setServer(config: ServerConfig, version: String?) = dataStore.edit {
        it[SERVER_URL] = config.url.toString()
        if (version != null) it[SERVER_VERSION] = version
    }

    suspend fun setServerVersion(version: String) = dataStore.edit { it[SERVER_VERSION] = version }

    suspend fun setRomsRoot(path: String) = dataStore.edit { it[ROMS_ROOT] = path }

    suspend fun setOnboardingComplete(done: Boolean) = dataStore.edit { it[ONBOARDED] = done }

    suspend fun setSwapConfirmBack(swap: Boolean) = dataStore.edit { it[SWAP_AB] = swap }

    suspend fun setMaxConcurrentDownloads(n: Int) = dataStore.edit { it[CONCURRENCY] = n.coerceIn(1, 3) }

    /** Records a finished sync; [fields] is the catalog row version a full sync writes (see [Settings.catalogFields]). */
    suspend fun setCatalogSynced(watermark: String?, atEpochMs: Long, fields: Int) = dataStore.edit {
        if (watermark != null) it[WATERMARK] = watermark else it.remove(WATERMARK)
        it[LAST_SYNC] = atEpochMs.toString()
        it[CATALOG_FIELDS] = fields
    }

    suspend fun setCatalogRefresh(refresh: CatalogRefresh) = dataStore.edit { it[REFRESH_HOURS] = refresh.hours }

    suspend fun setRefreshOnWifiOnly(wifiOnly: Boolean) = dataStore.edit { it[REFRESH_WIFI_ONLY] = wifiOnly }

    /** Forget the sync watermark so the next sync is a full one. */
    suspend fun resetCatalogWatermark() = dataStore.edit { it.remove(WATERMARK) }

    private companion object {
        /** A hex id that isn't the emulator's well-known shared value or all zeros. */
        fun usableAndroidId(id: String): Boolean =
            id.length in 8..64 && id.all { it in '0'..'9' || it in 'a'..'f' } && id.any { it != '0' } && id != BROKEN_ANDROID_ID

        /** The ANDROID_ID many old emulators and builds share. */
        const val BROKEN_ANDROID_ID = "9774d56d682e549c"

        val SERVER_URL = stringPreferencesKey("server_url")
        val SERVER_VERSION = stringPreferencesKey("server_version")
        val ROMS_ROOT = stringPreferencesKey("roms_root")
        val ONBOARDED = booleanPreferencesKey("onboarding_complete")
        val SWAP_AB = booleanPreferencesKey("swap_confirm_back")
        val CONCURRENCY = intPreferencesKey("max_concurrent_downloads")
        val WATERMARK = stringPreferencesKey("catalog_watermark")
        val LAST_SYNC = stringPreferencesKey("catalog_last_sync_ms")
        val REFRESH_HOURS = intPreferencesKey("catalog_refresh_hours")
        val REFRESH_WIFI_ONLY = booleanPreferencesKey("catalog_refresh_wifi_only")
        val DEVICE_IDENTIFIER = stringPreferencesKey("device_identifier")
        val CATALOG_FIELDS = intPreferencesKey("catalog_fields")
        val AUTO_UPLOAD_SAVES = booleanPreferencesKey("auto_upload_saves")
        val AZAHAR_USER_DIR = stringPreferencesKey("azahar_user_dir")
        val EDEN_SAVE_DIR = stringPreferencesKey("eden_save_dir")
        val EDEN_PROFILE = stringPreferencesKey("eden_profile")
        val SAVE_SYNC_AT = longPreferencesKey("save_sync_last_ms")
        val SAVE_SYNC_SUMMARY = stringPreferencesKey("save_sync_last_summary")
        val SAVE_SYNC_FAILED = booleanPreferencesKey("save_sync_last_failed")
        val SAVE_SYNC_NEEDS_PAIRING = booleanPreferencesKey("save_sync_last_needs_pairing")
    }
}
