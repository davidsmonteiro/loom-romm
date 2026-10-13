package ro.davidmontei.loom.data.mapping

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ro.davidmontei.loom.storage.mapping.FolderNames
import ro.davidmontei.loom.storage.mapping.InvalidMappingException
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
        prefs[OVERRIDES]?.let(MappingJson::parseOverrides).orEmpty()
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

    /**
     * Makes [folder] the system folder of the platform served from [fsSlug], keeping whatever else
     * the user set for it. Stored per server folder: RomM gives one slug ("arcade") to several of
     * them (MAME, FBNeo, NAOMI…), and a choice for one mustn't move the others. [detected]: Loom
     * found it on the card rather than the user picking it.
     */
    suspend fun chooseFolder(fsSlug: String, folder: String, detected: Boolean = false) {
        FolderNames.validate(folder)?.let { throw InvalidMappingException(it) }
        val match = PlatformMatch(fsSlug = fsSlug)
        edit { list ->
            val existing = list.find { it.match == match }
            val chosen = existing?.copy(folder = folder, detected = detected.takeIf { it })
                ?: PlatformMappingEntry(match, folder = folder, detected = detected.takeIf { it })
            list.filterNot { it.match == match } + chosen
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
        val diffs = imported.platforms.mapNotNull { entry ->
            val base = shipped.platforms.find { it.match == entry.match } ?: return@mapNotNull entry
            // Field by field: a value equal to the shipped one is left to the shipped row, so an older
            // export (without aliases) doesn't pin every folder as the user's choice.
            entry.copy(
                folder = entry.folder.takeIf { it != base.folder },
                aliases = entry.aliases.takeIf { it != null && it != base.aliases },
                archive = entry.archive.takeIf { it != base.archive },
                multiDisc = entry.multiDisc.takeIf { it != base.multiDisc },
                include = entry.include.takeIf { it != base.include },
                saveDir = entry.saveDir.takeIf { it != base.saveDir },
                note = null,
                detected = entry.detected.takeIf { entry.folder != base.folder },
            ).takeUnless { it.folder == null && it.aliases == null && it.archive == null && it.multiDisc == null && it.include == null && it.saveDir == null }
        }
        dataStore.edit { it[OVERRIDES] = MappingJson.encode(PlatformMappingFile(platforms = diffs)) }
    }

    private suspend fun edit(transform: (List<PlatformMappingEntry>) -> List<PlatformMappingEntry>) {
        dataStore.edit { prefs ->
            val current = prefs[OVERRIDES]?.let(MappingJson::parseOverrides).orEmpty()
            prefs[OVERRIDES] = MappingJson.encode(PlatformMappingFile(platforms = transform(current)))
        }
    }

    private companion object {
        val OVERRIDES = stringPreferencesKey("platform_mapping_overrides")
    }
}
