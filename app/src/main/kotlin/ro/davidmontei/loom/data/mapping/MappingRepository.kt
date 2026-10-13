package ro.davidmontei.loom.data.mapping

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMapper
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMappingFile
import ro.davidmontei.loom.storage.mapping.PlatformMatch

/**
 * The shipped mapping plus the user's overrides. Overrides are stored as a mapping file (the same
 * JSON the user imports and exports), so a corrupt or newer file simply falls back to defaults.
 */
class MappingRepository(private val dataStore: DataStore<Preferences>) {
    private val shipped: PlatformMappingFile by lazy { MappingJson.shippedDefaults() }

    val overrides: Flow<List<PlatformMappingEntry>> = dataStore.data.map { prefs ->
        prefs[OVERRIDES]?.let { runCatching { MappingJson.parse(it).platforms }.getOrNull() }.orEmpty()
    }

    val mapper: Flow<PlatformMapper> = overrides.map { PlatformMapper(shipped, it) }

    suspend fun currentMapper(): PlatformMapper = mapper.first()

    /** Replaces (or adds) the override for [match]. Passing an entry with only `match` clears it. */
    suspend fun setOverride(entry: PlatformMappingEntry) = edit { list ->
        val rest = list.filterNot { it.match == entry.match }
        val empty = entry.folder == null && entry.aliases == null && entry.archive == null && entry.multiDisc == null &&
            entry.include == null && entry.saveDir == null
        if (empty) rest else rest + entry
    }

    /** Makes [folder] the platform's system folder, keeping whatever else the user set for it. */
    suspend fun chooseFolder(slug: String, fsSlug: String, folder: String) {
        val match = currentMapper().matchFor(slug, fsSlug)
        edit { list ->
            val existing = list.find { it.match == match }
            list.filterNot { it.match == match } + (existing?.copy(folder = folder) ?: PlatformMappingEntry(match, folder = folder))
        }
    }

    suspend fun clearOverride(match: PlatformMatch) = edit { list -> list.filterNot { it.match == match } }

    suspend fun resetAll() = dataStore.edit { it.remove(OVERRIDES) }

    /** The effective mapping as a file the user can keep, edit and import elsewhere. */
    suspend fun exportJson(): String = MappingJson.encode(currentMapper().export())

    /**
     * Imports a mapping file: every entry that differs from the shipped default becomes an override.
     * Throws [ro.davidmontei.loom.storage.mapping.InvalidMappingException] with a readable reason.
     */
    suspend fun importJson(text: String) {
        val imported = MappingJson.parse(text)
        val diffs = imported.platforms.filterNot { entry ->
            shipped.platforms.any { it.match == entry.match && it.copy(note = null) == entry.copy(note = null) }
        }
        dataStore.edit { it[OVERRIDES] = MappingJson.encode(PlatformMappingFile(platforms = diffs)) }
    }

    private suspend fun edit(transform: (List<PlatformMappingEntry>) -> List<PlatformMappingEntry>) {
        dataStore.edit { prefs ->
            val current = prefs[OVERRIDES]?.let { runCatching { MappingJson.parse(it).platforms }.getOrNull() }.orEmpty()
            prefs[OVERRIDES] = MappingJson.encode(PlatformMappingFile(platforms = transform(current)))
        }
    }

    private companion object {
        val OVERRIDES = stringPreferencesKey("platform_mapping_overrides")
    }
}
