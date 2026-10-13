package ro.davidmontei.loom.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val catalogRefresh: CatalogRefresh = CatalogRefresh.DEFAULT,
    /** Background refreshes wait for an unmetered network. */
    val refreshOnWifiOnly: Boolean = false,
)

/** User settings in Preferences DataStore. Credentials live separately in CredentialStore. */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

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
            catalogRefresh = CatalogRefresh.entries.find { it.hours == p[REFRESH_HOURS] } ?: CatalogRefresh.DEFAULT,
            refreshOnWifiOnly = p[REFRESH_WIFI_ONLY] ?: false,
        )
    }

    suspend fun snapshot(): Settings = settings.first()

    /** Random per-install id sent to RomM when pairing; reused so re-pairing updates one Device. */
    suspend fun deviceIdentifier(): String {
        dataStore.data.first()[DEVICE_IDENTIFIER]?.let { return it }
        val id = "loom-" + UUID.randomUUID().toString()
        dataStore.edit { if (it[DEVICE_IDENTIFIER] == null) it[DEVICE_IDENTIFIER] = id }
        return dataStore.data.first()[DEVICE_IDENTIFIER]!!
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

    suspend fun setCatalogSynced(watermark: String?, atEpochMs: Long) = dataStore.edit {
        if (watermark != null) it[WATERMARK] = watermark else it.remove(WATERMARK)
        it[LAST_SYNC] = atEpochMs.toString()
    }

    suspend fun setCatalogRefresh(refresh: CatalogRefresh) = dataStore.edit { it[REFRESH_HOURS] = refresh.hours }

    suspend fun setRefreshOnWifiOnly(wifiOnly: Boolean) = dataStore.edit { it[REFRESH_WIFI_ONLY] = wifiOnly }

    /** Forget the sync watermark so the next sync is a full one. */
    suspend fun resetCatalogWatermark() = dataStore.edit { it.remove(WATERMARK) }

    private companion object {
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
    }
}
