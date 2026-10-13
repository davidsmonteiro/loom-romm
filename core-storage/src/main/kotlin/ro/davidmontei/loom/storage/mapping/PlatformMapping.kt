package ro.davidmontei.loom.storage.mapping

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What to do with a ROM file that is an archive (.zip, .7z). */
@Serializable
enum class ArchivePolicy {
    /** Leave it zipped: arcade sets and emulators that read archives directly. */
    @SerialName("keep") KEEP,

    /** Unpack it before installing: disc images and emulators that can't read archives. */
    @SerialName("extract") EXTRACT,
}

/** How a multi-disc set is laid out in its system folder. */
@Serializable
enum class MultiDiscLayout {
    /** `Name.m3u/` folder holding the discs and `Name.m3u`; Cocoon and ES-DE show one game. */
    @SerialName("m3u_dir") M3U_DIR,

    /** `Name/` folder holding the discs and `Name.m3u`; needs the frontend to scan subfolders. */
    @SerialName("folder") FOLDER,
}

/** Which RomM platform an entry applies to. `fsSlug` (the server's folder name) wins over `slug`. */
@Serializable
data class PlatformMatch(
    val slug: String? = null,
    val fsSlug: String? = null,
) {
    init {
        require(slug != null || fsSlug != null) { "A mapping entry needs a slug or an fsSlug to match" }
    }
}

/** One row of the mapping file. Null fields inherit from the defaults. */
@Serializable
data class PlatformMappingEntry(
    val match: PlatformMatch,
    val folder: String? = null,
    val archive: ArchivePolicy? = null,
    val multiDisc: MultiDiscLayout? = null,
    /** RomM file categories to download; "game" covers files with no category. */
    val include: List<String>? = null,
    /** Phase 2: the emulator's save directory for this platform. */
    val saveDir: String? = null,
    val note: String? = null,
)

@Serializable
data class MappingDefaults(
    val archive: ArchivePolicy = ArchivePolicy.KEEP,
    val multiDisc: MultiDiscLayout = MultiDiscLayout.M3U_DIR,
    val include: List<String> = listOf(CATEGORY_GAME),
)

/** The mapping file: the shipped defaults and the import/export format are the same document. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PlatformMappingFile(
    @EncodeDefault val schemaVersion: Int = SCHEMA_VERSION,
    val about: String? = null,
    val defaults: MappingDefaults = MappingDefaults(),
    val platforms: List<PlatformMappingEntry> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Where a resolved setting came from; shown in the mapping editor. */
enum class MappingSource { USER, DEFAULT, SERVER_FOLDER }

/** The effective settings for one platform. */
data class ResolvedPlatform(
    val folder: String,
    val archive: ArchivePolicy,
    val multiDisc: MultiDiscLayout,
    val include: Set<String>,
    val saveDir: String?,
    val source: MappingSource,
) {
    /** True when a RomM file with [category] should be downloaded. */
    fun includes(category: String?): Boolean =
        (category ?: CATEGORY_GAME) in include
}

const val CATEGORY_GAME = "game"

/** Every RomM 5.3.1 `RomFileCategory`. */
val ROM_FILE_CATEGORIES = listOf(
    "game", "dlc", "hack", "manual", "walkthrough", "patch", "update", "mod",
    "demo", "translation", "prototype", "cheat", "soundtrack", "screenshot",
)

class InvalidMappingException(message: String) : Exception(message)

/**
 * Resolves a RomM platform to its system folder and install policies.
 *
 * Order: the user's override for the platform's server folder (`fsSlug`), then for its `slug`;
 * then the shipped default for `fsSlug`, then for `slug`; finally the server's own folder name,
 * flagged [MappingSource.SERVER_FOLDER] so the UI can ask the user to confirm it. Each field an
 * entry leaves out falls back to the next matching entry, then to the defaults.
 */
class PlatformMapper(
    private val defaults: PlatformMappingFile,
    private val overrides: List<PlatformMappingEntry> = emptyList(),
) {
    fun resolve(slug: String, fsSlug: String): ResolvedPlatform {
        val chain = listOfNotNull(
            overrides.find { it.match.fsSlug == fsSlug },
            overrides.find { it.match.slug == slug && it.match.fsSlug == null },
            defaults.platforms.find { it.match.fsSlug == fsSlug },
            defaults.platforms.find { it.match.slug == slug && it.match.fsSlug == null },
        )
        val folderEntry = chain.firstOrNull { it.folder != null }
        val userTouched = chain.any { it in overrides }
        val source = when {
            userTouched -> MappingSource.USER
            folderEntry != null -> MappingSource.DEFAULT
            else -> MappingSource.SERVER_FOLDER
        }
        val d = defaults.defaults
        return ResolvedPlatform(
            folder = folderEntry?.folder ?: FolderNames.fromServer(fsSlug),
            archive = chain.firstNotNullOfOrNull { it.archive } ?: d.archive,
            multiDisc = chain.firstNotNullOfOrNull { it.multiDisc } ?: d.multiDisc,
            include = (chain.firstNotNullOfOrNull { it.include } ?: d.include).toSet(),
            saveDir = chain.firstNotNullOfOrNull { it.saveDir },
            source = source,
        )
    }

    /** The file a user would export: defaults with their overrides folded in. */
    fun export(): PlatformMappingFile {
        val merged = defaults.platforms.map { base ->
            overrides.find { it.match == base.match }?.let { o -> merge(base, o) } ?: base
        } + overrides.filter { o -> defaults.platforms.none { it.match == o.match } }
        return defaults.copy(platforms = merged)
    }

    private fun merge(base: PlatformMappingEntry, o: PlatformMappingEntry) = base.copy(
        folder = o.folder ?: base.folder,
        archive = o.archive ?: base.archive,
        multiDisc = o.multiDisc ?: base.multiDisc,
        include = o.include ?: base.include,
        saveDir = o.saveDir ?: base.saveDir,
        note = o.note ?: base.note,
    )
}

object MappingJson {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** Parses and validates a mapping file (the shipped default, or one the user imports). */
    fun parse(text: String): PlatformMappingFile {
        val file = try {
            json.decodeFromString(PlatformMappingFile.serializer(), text)
        } catch (e: Exception) {
            throw InvalidMappingException("Not a Loom mapping file: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        if (file.schemaVersion > PlatformMappingFile.SCHEMA_VERSION) {
            throw InvalidMappingException("Mapping file version ${file.schemaVersion} is newer than this Loom understands")
        }
        file.platforms.forEach { entry ->
            entry.folder?.let { FolderNames.validate(it)?.let { problem -> throw InvalidMappingException("${entry.match}: $problem") } }
            entry.include?.forEach { c ->
                if (c !in ROM_FILE_CATEGORIES) throw InvalidMappingException("${entry.match}: unknown file category '$c'")
            }
        }
        return file
    }

    fun encode(file: PlatformMappingFile): String = json.encodeToString(PlatformMappingFile.serializer(), file)

    /** The mapping shipped with Loom (`platform-mapping.default.json` on the classpath). */
    fun shippedDefaults(): PlatformMappingFile {
        val stream = MappingJson::class.java.getResourceAsStream("/platform-mapping.default.json")
            ?: error("platform-mapping.default.json missing from the classpath")
        return parse(stream.bufferedReader().use { it.readText() })
    }
}

/** Rules for system folder names: one path component Cocoon will see. */
object FolderNames {
    /** A reason the name can't be used, or null when it's fine. */
    fun validate(name: String): String? = when {
        name.isBlank() -> "folder name is empty"
        name.length > 64 -> "folder name is longer than 64 characters"
        name.startsWith(".") -> "folder name starts with a dot, so Cocoon would ignore it"
        name.any { it == '/' || it == '\\' || it.code < 0x20 } -> "folder name must be a single folder, without slashes"
        name.any { it in "*?\"<>|:" } -> "folder name contains a character SD cards don't allow"
        else -> null
    }

    /** Server folder names are already single components; this only guards against odd ones. */
    fun fromServer(fsSlug: String): String =
        fsSlug.trim().trimStart('.').replace(Regex("[\\\\/*?\"<>|:]"), "_").ifBlank { "unknown" }
}
