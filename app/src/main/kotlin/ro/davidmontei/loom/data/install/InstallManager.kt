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
import ro.davidmontei.loom.storage.mapping.EmulatorData
import ro.davidmontei.loom.storage.mapping.ArchivePolicy
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
        // An install is one entry inside a system folder. Never a system folder itself, never an
        // emulator's data that lives among the games (Azahar's user directory is ROMs/n3ds), and
        // never anything in the ROMs root's saves folder (Eden's saves are in ROMs/saves/Eden).
        val segments = install.relPath.split('/')
        val inSystemFolder = segments.drop(1)
        if (entry.parentFile == root || EmulatorData.isSavesFolder(segments.first()) || inSystemFolder.any(EmulatorData::isEmulatorData) ||
            EmulatorData.holdsEmulatorData(entry.toPath())
        ) {
            return@withContext OffloadResult.Failed("Refusing to delete ${install.relPath}: it holds an emulator's data, not a game")
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
     * An emulator's folders among the games (Azahar's `sdmc`, `nand`… in `n3ds`) are never adopted.
     *
     * An archive Loom unpacks but RomM lists nothing inside can't be predicted by name and size:
     * it's looked for by name alone, after every other ROM of every platform (platforms can share a
     * folder), and adopted only when one entry alone fits.
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
        val byNameOnly = mutableListOf<Triple<RomEntity, RomFileEntity, String>>()
        var adopted = 0
        for (platform in catalog.platforms()) {
            val resolved = mapper.resolve(platform.slug, platform.fsSlug)
            val folder = resolved.folder
            val listing = listings.getOrPut(folder) {
                File(root, folder).listFiles().orEmpty().filterNot { EmulatorData.isEmulatorData(it.name) }.associateBy { NameSanitizer.key(it.name) }
            }
            if (listing.isEmpty()) continue
            for (rom in catalog.romsOf(platform.id)) {
                if (rom.id in installed) continue
                val files = catalog.filesOf(rom.id)
                val match = expectedEntries(rom, files, resolved).firstNotNullOfOrNull { (name, size) ->
                    listing[NameSanitizer.key(name)]?.takeIf { f -> size == null || (f.isFile && f.length() == size) }?.let { it to size }
                }
                if (match != null) {
                    val (entry, size) = match
                    if (adopt(rom, entry, folder, volume.id, romsRoot, if (size == null) VerifiedBy.NAME else VerifiedBy.SIZE)) adopted++
                } else {
                    archiveWithoutMembers(files, resolved)?.let { byNameOnly += Triple(rom, it, folder) }
                }
            }
        }

        val stems = mutableMapOf<String, Map<String, List<File>>>()
        for ((rom, archive, folder) in byNameOnly) {
            val byStem = stems.getOrPut(folder) { byStem(listings.getValue(folder).values) }
            val archiveKey = NameSanitizer.key(NameSanitizer.sanitize(archive.fileName))
            val candidates = byStem[NameSanitizer.key(entryStem(rom))].orEmpty().filter { entry ->
                NameSanitizer.key(entry.name) != archiveKey && couldBeUnpackedFrom(entry, archive) &&
                    catalog.installAtPath(volume.id, NameSanitizer.key("$folder/${entry.name}")) == null
            }
            // A file another game already has isn't this one's. Two or more left ("Game.3ds" and
            // "Game.cia") and Loom can't tell which it made: neither.
            val entry = candidates.singleOrNull()
            if (entry != null && adopt(rom, entry, folder, volume.id, romsRoot, VerifiedBy.NAME)) adopted++
        }
        ReconcileResult(forgotten, adopted)
    }

    /** Records [entry] as [rom]'s install, unless another install already claims it. */
    private suspend fun adopt(rom: RomEntity, entry: File, folder: String, volumeId: String, romsRoot: String, verifiedBy: VerifiedBy): Boolean {
        val relPath = "$folder/${entry.name}"
        if (catalog.installAtPath(volumeId, NameSanitizer.key(relPath)) != null) return false
        catalog.upsertInstall(
            InstallEntity(
                romId = rom.id, volumeUuid = volumeId, romsRoot = romsRoot, relPath = relPath,
                pathKey = NameSanitizer.key(relPath), platformId = rom.platformId,
                kind = if (entry.isDirectory) InstallKind.DIRECTORY else InstallKind.FILE,
                bytes = if (entry.isDirectory) entry.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else entry.length(),
                verifiedBy = verifiedBy, source = InstallSource.ADOPTED, romFingerprint = null,
                installedAtEpochMs = nowMs(),
            ),
        )
        return true
    }

    /**
     * The ROM's archive when Loom would unpack it but RomM lists nothing inside it ("Pokemon X.zip"
     * on a platform set to unpack), so neither the name nor the size of what it became is known.
     */
    private fun archiveWithoutMembers(files: List<RomFileEntity>, platform: ResolvedPlatform): RomFileEntity? {
        if (platform.archive != ArchivePolicy.EXTRACT) return null
        val archive = gameFiles(files).singleOrNull() ?: return null
        return archive.takeIf { Discs.ext(it.fileName) in DownloadPlanner.EXTRACTABLE && archiveMembers(it).isEmpty() }
    }

    /**
     * Whether [entry] could be what Loom unpacked from [archive]. Unpacking never leaves a game much
     * smaller than its archive, so a save, a patch or a cover named after the game doesn't count;
     * nor do documents, or saves, save states and cheats, whatever their size.
     */
    private fun couldBeUnpackedFrom(entry: File, archive: RomFileEntity): Boolean {
        if (entry.isFile && (Discs.isDocument(entry.name) || EMULATOR_FILE.matches(Discs.ext(entry.name)))) return false
        val size = if (entry.isDirectory) entry.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else entry.length()
        return size * 2 >= archive.sizeBytes
    }

    /**
     * A system folder's entries by name without the extension (what an unpacked file is named
     * after), and folders also by their own name: a set unpacks to `Name.m3u/` or `Name/`.
     */
    private fun byStem(entries: Collection<File>): Map<String, List<File>> {
        val out = mutableMapOf<String, MutableSet<File>>()
        for (entry in entries) {
            if (entry.name.startsWith('.')) continue
            val stems = when {
                entry.isDirectory -> setOf(entry.name, if (entry.name.endsWith(".m3u", ignoreCase = true)) entry.name.dropLast(4) else entry.name)
                entry.isFile -> setOf(entry.name.substringBeforeLast('.'))
                else -> emptySet()
            }
            stems.forEach { out.getOrPut(NameSanitizer.key(it)) { mutableSetOf() } += entry }
        }
        return out.mapValues { it.value.toList() }
    }

    /**
     * Names (and sizes, for files) a ROM could be found under in its system folder: the file
     * itself, the single file inside an archive, what Loom made of an archive it unpacked, or the
     * `Name.m3u` / `Name` folder of a set.
     */
    private fun expectedEntries(rom: RomEntity, files: List<RomFileEntity>, platform: ResolvedPlatform): List<Pair<String, Long?>> {
        val out = mutableListOf<Pair<String, Long?>>()
        val games = gameFiles(files)
        val stem = entryStem(rom)
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

    /** The name Loom gives the install when its layout needs one, as the download planner does. */
    private fun entryStem(rom: RomEntity): String =
        DownloadPlanner.entryStem(rom.id, rom.fsName, rom.fsNameNoExt, rom.hasMultipleFiles || rom.hasNestedSingleFile)

    private fun gameFiles(files: List<RomFileEntity>) = files.filter { (it.category ?: "game") == "game" }

    private fun archiveMembers(file: RomFileEntity): List<ArchiveMemberDto> =
        file.archiveMembersJson?.let { runCatching { RommJson.decodeFromString(ARCHIVE_MEMBERS, it) }.getOrNull() }.orEmpty()

    private companion object {
        /** Extensions of saves, save states and cheats an emulator may keep next to a game, named after it. */
        val EMULATOR_FILE = Regex("""sav|srm|dsv|cht|state\d*|ml\d|ss\d""")
    }
}
