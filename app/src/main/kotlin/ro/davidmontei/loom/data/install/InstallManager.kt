package ro.davidmontei.loom.data.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.davidmontei.loom.data.catalog.ARCHIVE_MEMBERS
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.InstallEntity
import ro.davidmontei.loom.data.db.InstallKind
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.RomFileEntity
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.data.download.Volumes
import ro.davidmontei.loom.data.mapping.MappingRepository
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.RommJson
import ro.davidmontei.loom.romm.dto.ArchiveMemberDto
import ro.davidmontei.loom.storage.STAGING_DIR_NAME
import ro.davidmontei.loom.storage.mapping.ResolvedPlatform
import ro.davidmontei.loom.storage.plan.Discs
import ro.davidmontei.loom.storage.plan.DownloadPlanner
import ro.davidmontei.loom.storage.plan.EntryKind
import ro.davidmontei.loom.storage.plan.LayoutDecider
import ro.davidmontei.loom.storage.plan.NameSanitizer
import ro.davidmontei.loom.storage.plan.PayloadFile
import java.io.File
import java.util.UUID

sealed interface OffloadResult {
    data class Removed(val bytes: Long) : OffloadResult
    data object NotInstalled : OffloadResult
    data class Failed(val message: String) : OffloadResult
}

data class ReconcileResult(val forgotten: Int, val adopted: Int)

/**
 * Keeps the `installs` table honest about the SD card: offloads games, forgets installs whose
 * files were deleted elsewhere, and adopts games that were already in place.
 */
class InstallManager(
    private val catalog: CatalogDao,
    private val settings: SettingsStore,
    private val mapping: MappingRepository,
    private val volumes: Volumes,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Removes an install from the device but keeps its catalog entry. The entry is first renamed
     * into the staging trash, so Cocoon sees it vanish in one step even if deleting a big folder
     * takes a while; then it's deleted.
     */
    suspend fun offload(romId: Int): OffloadResult = withContext(Dispatchers.IO) {
        val install = catalog.install(romId) ?: return@withContext OffloadResult.NotInstalled
        val root = File(install.romsRoot).canonicalFile
        val entry = File(root, install.relPath).canonicalFile
        if (!entry.path.startsWith(root.path + File.separator)) {
            return@withContext OffloadResult.Failed("Refusing to delete ${entry.path}: it's outside the ROMs folder")
        }
        if (!volumes.isMounted(install.volumeUuid)) {
            return@withContext OffloadResult.Failed("The storage holding this game isn't mounted")
        }
        if (!entry.exists()) {
            catalog.deleteInstall(romId)
            return@withContext OffloadResult.Removed(0)
        }
        val volume = volumes.forPath(root.path)
        val trash = volume?.let { File(it.stagingRoot, "trash/${UUID.randomUUID()}") }
        val target = if (trash != null && trash.parentFile!!.let { it.exists() || it.mkdirs() } && entry.renameTo(trash)) trash else entry
        val deleted = target.deleteRecursively()
        if (!deleted && entry.exists()) return@withContext OffloadResult.Failed("Couldn't delete ${install.relPath}")
        catalog.deleteInstall(romId)
        OffloadResult.Removed(install.bytes)
    }

    /** Empties staging trash left by an offload that was interrupted. */
    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val romsRoot = settings.snapshot().romsRoot ?: return@withContext
        volumes.forPath(romsRoot)?.let { File(it.stagingRoot, "trash").deleteRecursively() }
    }

    /**
     * Forgets installs whose files are gone and adopts catalog ROMs already present at the path
     * Loom would install them to. Only volumes that are mounted are looked at, so pulling the SD
     * card never "uninstalls" anything. Each system folder is listed once (not one stat per ROM).
     */
    suspend fun reconcile(): ReconcileResult = withContext(Dispatchers.IO) {
        var forgotten = 0
        for (install in catalog.installs()) {
            if (!volumes.isMounted(install.volumeUuid) || !File(install.romsRoot).isDirectory) continue
            if (!File(install.romsRoot, install.relPath).exists()) {
                catalog.deleteInstall(install.romId)
                forgotten++
            }
        }

        val romsRoot = settings.snapshot().romsRoot ?: return@withContext ReconcileResult(forgotten, 0)
        val volume = volumes.forPath(romsRoot) ?: return@withContext ReconcileResult(forgotten, 0)
        val root = File(romsRoot)
        if (!root.isDirectory) return@withContext ReconcileResult(forgotten, 0)

        val mapper = mapping.currentMapper()
        val installed = catalog.installedRomIds().toSet()
        val listings = mutableMapOf<String, Map<String, File>>()
        var adopted = 0
        for (platform in catalog.platforms()) {
            val resolved = mapper.resolve(platform.slug, platform.fsSlug)
            val folder = resolved.folder
            val listing = listings.getOrPut(folder) {
                File(root, folder).listFiles().orEmpty().associateBy { NameSanitizer.key(it.name) }
            }
            if (listing.isEmpty()) continue
            for (rom in catalog.romsOf(platform.id)) {
                if (rom.id in installed) continue
                val match = expectedEntries(rom, catalog.filesOf(rom.id), resolved).firstNotNullOfOrNull { (name, size) ->
                    listing[NameSanitizer.key(name)]?.takeIf { f -> size == null || (f.isFile && f.length() == size) }
                } ?: continue
                val relPath = "$folder/${match.name}"
                if (catalog.installAtPath(volume.id, NameSanitizer.key(relPath)) != null) continue
                catalog.upsertInstall(
                    InstallEntity(
                        romId = rom.id, volumeUuid = volume.id, romsRoot = romsRoot, relPath = relPath,
                        pathKey = NameSanitizer.key(relPath), platformId = rom.platformId,
                        kind = if (match.isDirectory) InstallKind.DIRECTORY else InstallKind.FILE,
                        bytes = if (match.isDirectory) match.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else match.length(),
                        verifiedBy = VerifiedBy.SIZE, source = InstallSource.ADOPTED, romFingerprint = null,
                        installedAtEpochMs = nowMs(),
                    ),
                )
                adopted++
            }
        }
        ReconcileResult(forgotten, adopted)
    }

    /**
     * Names (and sizes, for files) a ROM could be found under in its system folder: the file
     * itself, the single file inside an archive, what Loom made of an archive it unpacked, or the
     * `Name.m3u` / `Name` folder of a set.
     */
    private fun expectedEntries(rom: RomEntity, files: List<RomFileEntity>, platform: ResolvedPlatform): List<Pair<String, Long?>> {
        val out = mutableListOf<Pair<String, Long?>>()
        val games = files.filter { (it.category ?: "game") == "game" }
        val stem = DownloadPlanner.entryStem(rom.id, rom.fsName, rom.fsNameNoExt, rom.hasMultipleFiles || rom.hasNestedSingleFile)
        if (games.size == 1) {
            val f = games.single()
            out += NameSanitizer.sanitize(f.fileName) to f.sizeBytes
            if (Discs.ext(f.fileName) in DownloadPlanner.EXTRACTABLE) {
                val members = archiveMembers(f)
                // Unzipped by hand, the file keeps the name it has inside the archive.
                members.singleOrNull()?.let { m -> out += NameSanitizer.sanitize(m.name.substringAfterLast('/')) to m.size }
                unpacked(stem, members, platform)?.let { out += it }
                out += NameSanitizer.sanitize(f.fileName.substringBeforeLast('.')) + ".m3u" to null
            }
        }
        if (rom.hasMultipleFiles || rom.hasNestedSingleFile || games.size > 1) {
            out += "$stem.m3u" to null
            out += stem to null
        }
        return out.filterNot { (name, _) -> name.startsWith(STAGING_DIR_NAME) }
    }

    /**
     * The entry Loom's engine makes of an archive holding [members] when it unpacks it, laid out by
     * the engine itself: a lone file goes by the game's name ("Super Bust-A-Move (USA).bin", not
     * "SLUS-20115 (1.00).bin"). With the file's size when the entry is one file.
     */
    private fun unpacked(stem: String, members: List<ArchiveMemberDto>, platform: ResolvedPlatform): Pair<String, Long?>? {
        val payload = members.filterNot { it.name.endsWith('/') }.map { PayloadFile(it.name, it.size, unpacked = true) }
        if (payload.isEmpty()) return null
        val layout = runCatching { LayoutDecider.decide(stem, payload, platform.multiDisc, platform.folder) }.getOrNull() ?: return null
        if (layout.kind == EntryKind.DIRECTORY) return layout.entryName to null
        val file = payload.firstOrNull { it.relPath == layout.placements.singleOrNull()?.from } ?: return null
        return layout.entryName to file.size
    }

    private fun archiveMembers(file: RomFileEntity): List<ArchiveMemberDto> =
        file.archiveMembersJson?.let { runCatching { RommJson.decodeFromString(ARCHIVE_MEMBERS, it) }.getOrNull() }.orEmpty()
}
