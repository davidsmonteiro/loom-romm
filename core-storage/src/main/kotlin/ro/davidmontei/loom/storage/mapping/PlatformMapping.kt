package ro.davidmontei.loom.storage.mapping

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.davidmontei.loom.storage.plan.NameSanitizer

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
    /**
     * Other names the same system folder goes by (ES-DE's, other frontends'). Loom looks for these
     * on the card before settling on [folder], and asks when more than one could be the one.
     */
    val aliases: List<String>? = null,
    val archive: ArchivePolicy? = null,
    val multiDisc: MultiDiscLayout? = null,
    /** RomM file categories to download; "game" covers files with no category. */
    val include: List<String>? = null,
    /** Phase 2: the emulator's save directory for this platform. */
    val saveDir: String? = null,
    val note: String? = null,
    /**
     * True when [folder] is one Loom found on the card at the platform's first download, rather
     * than the user's choice: settled (Loom doesn't look again), but not a change the user made.
     */
    val detected: Boolean? = null,
) {
    /** Just a folder Loom found on the card: nothing the user set. */
    val isDetectedFolderOnly: Boolean
        get() = detected == true && aliases == null && archive == null && multiDisc == null && include == null && saveDir == null
}

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
    /** True when the user picked [folder] (not just other settings), so nothing should second-guess it. */
    val folderChosen: Boolean = false,
    /** Folder names this platform's games may already live under on the card: [folder] first, then its aliases. */
    val candidates: List<String> = listOf(folder),
    /**
     * Every folder name this platform's games usually go in, whatever the user chose: [candidates],
     * then the shipped mapping's folder and aliases. Offered when the user picks a folder.
     */
    val usualFolders: List<String> = candidates,
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
        val folder = folderEntry?.folder ?: FolderNames.fromServer(fsSlug)
        // A folder Loom found on the card settles it, but isn't the user's change ("Changed" in Settings).
        val userTouched = chain.any { it in overrides && !it.isDetectedFolderOnly }
        val source = when {
            userTouched -> MappingSource.USER
            chain.any { it !in overrides && it.folder != null } -> MappingSource.DEFAULT
            else -> MappingSource.SERVER_FOLDER // a folder Loom found doesn't make it a known one: still "Check"
        }
        val d = defaults.defaults
        val candidates = candidates(chain, fsSlug)
        return ResolvedPlatform(
            folder = folder,
            archive = chain.firstNotNullOfOrNull { it.archive } ?: d.archive,
            multiDisc = chain.firstNotNullOfOrNull { it.multiDisc } ?: d.multiDisc,
            include = (chain.firstNotNullOfOrNull { it.include } ?: d.include).toSet(),
            saveDir = chain.firstNotNullOfOrNull { it.saveDir },
            source = source,
            folderChosen = folderEntry != null && folderEntry in overrides,
            candidates = candidates,
            // A folder the user picked has no aliases of its own: the shipped ones still say where else the games go.
            usualFolders = (candidates + candidates(chain.filterNot { it in overrides }, fsSlug)).distinctBy { FolderNames.key(it) },
        )
    }

    /**
     * The folder the first row in [chain] naming one gives, then its aliases. Aliases belong to the row
     * that names the folder (or rows above it): the MAME, NAOMI and FBNeo server folders mustn't
     * inherit the generic arcade row's arcade/fbneo/fba.
     */
    private fun candidates(chain: List<PlatformMappingEntry>, fsSlug: String): List<String> {
        val folderEntry = chain.firstOrNull { it.folder != null }
        val folder = folderEntry?.folder ?: FolderNames.fromServer(fsSlug)
        return (listOf(folder) + chain.take(chain.indexOf(folderEntry) + 1).firstNotNullOfOrNull { it.aliases }.orEmpty())
            .distinctBy { FolderNames.key(it) }
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
        aliases = o.aliases ?: base.aliases,
        archive = o.archive ?: base.archive,
        multiDisc = o.multiDisc ?: base.multiDisc,
        include = o.include ?: base.include,
        saveDir = o.saveDir ?: base.saveDir,
        note = o.note ?: base.note,
        detected = o.detected ?: base.detected,
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
        file.platforms.forEach { entry -> problem(entry)?.let { throw InvalidMappingException("${entry.match}: $it") } }
        return file
    }

    /** Why [entry] can't be used, or null when it's fine. */
    fun problem(entry: PlatformMappingEntry): String? =
        (listOfNotNull(entry.folder) + entry.aliases.orEmpty()).firstNotNullOfOrNull { FolderNames.validate(it) }
            ?: entry.include?.firstOrNull { it !in ROM_FILE_CATEGORIES }?.let { "unknown file category '$it'" }

    /**
     * The user's stored overrides: unlike [parse], an entry that can't be used is dropped on its own,
     * so one bad entry never loses the rest.
     */
    fun parseOverrides(text: String): List<PlatformMappingEntry> =
        runCatching { json.decodeFromString(PlatformMappingFile.serializer(), text).platforms }.getOrDefault(emptyList())
            .filter { problem(it) == null }

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
        EmulatorData.isSavesFolder(name) -> "${EmulatorData.SAVES_FOLDER} holds emulators' saves, not games"
        else -> null
    }

    /** Comparison key: SD cards treat "MAME" and "mame" as one folder. */
    fun key(name: String): String = NameSanitizer.key(name)

    /**
     * Server folder names are already single components; this only guards against odd ones, and
     * against the ROMs root's saves folder, which is never a system folder.
     */
    fun fromServer(fsSlug: String): String =
        fsSlug.trim().trimStart('.').replace(Regex("[\\\\/*?\"<>|:]"), "_").ifBlank { "unknown" }
            .let { if (EmulatorData.isSavesFolder(it)) "${it}_games" else it }
}
