package ro.davidmontei.loom.storage.plan

import kotlinx.serialization.Serializable
import ro.davidmontei.loom.storage.mapping.ArchivePolicy
import ro.davidmontei.loom.storage.mapping.MultiDiscLayout
import ro.davidmontei.loom.storage.mapping.ResolvedPlatform
import java.security.MessageDigest

/** A ROM as the server describes it right before downloading (fresh `GET /api/roms/{id}`). */
data class RomSnapshot(
    val id: Int,
    val fsName: String,
    val fsNameNoExt: String,
    /** `fs_path/fs_name` relative to RomM's library root. */
    val fullPath: String,
    val hasMultipleFiles: Boolean,
    val hasNestedSingleFile: Boolean,
    val updatedAt: String,
    val files: List<RomFileSnapshot>,
) {
    /** True when the ROM is a folder on the server (its files live under [fullPath]). */
    val isFolder: Boolean get() = hasMultipleFiles || hasNestedSingleFile
}

@Serializable
data class ArchiveMember(val name: String, val size: Long, val crc: String?, val md5: String?, val sha1: String?)

data class RomFileSnapshot(
    val id: Int,
    val fileName: String,
    /** Directory of the file relative to RomM's library root. */
    val filePath: String,
    val size: Long,
    val crc: String?,
    val md5: String?,
    val sha1: String?,
    val category: String?,
    val archiveMembers: List<ArchiveMember>?,
)

/** How a downloaded part is checked before it can be installed (ADR 0003). */
@Serializable
sealed interface PartCheck {
    /** Hash of the raw bytes. [algo] is "sha1", "md5" or "crc32". */
    @Serializable data class Hash(val algo: String, val hex: String) : PartCheck

    /** RomM hashed the archive's members, not its bytes: check each member's CRC32 and size. */
    @Serializable data class ArchiveMembers(val members: List<ArchiveMember>) : PartCheck

    /** An archive without member hashes: check that every entry decompresses with a valid CRC. */
    @Serializable data object ArchiveIntegrity : PartCheck

    /** RomM didn't hash this file (some platforms never are): only the size is checked. */
    @Serializable data object SizeOnly : PartCheck
}

@Serializable
data class PartPlan(
    val romFileId: Int,
    /** Path of the file inside the ROM, e.g. "Disc 1/Game (Disc 1).cue" or "Game.sfc". */
    val relPath: String,
    val size: Long,
    val check: PartCheck,
    /** True when this part is an archive that will be unpacked before installing. */
    val extract: Boolean,
)

@Serializable
data class DownloadPlan(
    val romId: Int,
    /** Changes whenever the server's files for this ROM change; used to resume or re-plan. */
    val fingerprint: String,
    val systemFolder: String,
    /** Name for the install's folder when the layout needs one (sanitised `fs_name` stem). */
    val entryStem: String,
    val multiDisc: MultiDiscLayout,
    val parts: List<PartPlan>,
) {
    val totalBytes: Long get() = parts.sumOf { it.size }

    /** Space needed on top of the download for unpacking (from archive member sizes; 3× when unknown). */
    val extractionBytes: Long
        get() = parts.filter { it.extract }.sumOf { part ->
            (part.check as? PartCheck.ArchiveMembers)?.members?.sumOf { it.size } ?: (part.size * 3)
        }
}

class NothingToDownloadException(message: String) : Exception(message)

/** Decides which of a ROM's files to fetch and how each one is checked. */
object DownloadPlanner {
    /** Extensions RomM hashes by content rather than raw bytes (roms_handler.py @5.3.1). */
    val CONTENT_HASHED = setOf("zip", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "rar")

    /** Archives Loom can unpack. */
    val EXTRACTABLE = setOf("zip", "7z")

    fun plan(rom: RomSnapshot, platform: ResolvedPlatform): DownloadPlan {
        val chosen = rom.files.filter { platform.includes(it.category) }
        if (chosen.isEmpty()) {
            throw NothingToDownloadException(
                if (rom.files.isEmpty()) "RomM has no files for this game" else "None of this game's files are in the categories set to download",
            )
        }
        val parts = chosen.map { file ->
            val ext = Discs.ext(file.fileName)
            val contentHashed = ext in CONTENT_HASHED
            val extract = platform.archive == ArchivePolicy.EXTRACT && ext in EXTRACTABLE
            PartPlan(
                romFileId = file.id,
                relPath = relPath(rom, file),
                size = file.size,
                check = check(file, contentHashed, ext),
                extract = extract,
            )
        }
        val stem = if (rom.isFolder) rom.fsName else rom.fsNameNoExt.ifBlank { rom.fsName }
        return DownloadPlan(
            romId = rom.id,
            fingerprint = fingerprint(rom),
            systemFolder = platform.folder,
            entryStem = NameSanitizer.sanitize(stem, fallback = "rom-${rom.id}"),
            multiDisc = platform.multiDisc,
            parts = parts,
        )
    }

    /** Path of [file] inside its ROM folder; just the file name for single-file ROMs. */
    fun relPath(rom: RomSnapshot, file: RomFileSnapshot): String {
        val dir = file.filePath.trim('/')
        val root = rom.fullPath.trim('/')
        val inner = when {
            !rom.isFolder -> ""
            dir == root -> ""
            dir.startsWith("$root/") -> dir.removePrefix("$root/")
            else -> ""
        }
        return if (inner.isEmpty()) file.fileName else "$inner/${file.fileName}"
    }

    private fun check(file: RomFileSnapshot, contentHashed: Boolean, ext: String): PartCheck {
        if (contentHashed) {
            val members = file.archiveMembers?.takeIf { it.isNotEmpty() }
            return when {
                members != null && ext in EXTRACTABLE -> PartCheck.ArchiveMembers(members)
                ext in EXTRACTABLE -> PartCheck.ArchiveIntegrity
                else -> PartCheck.SizeOnly
            }
        }
        file.sha1.nonBlank()?.let { return PartCheck.Hash("sha1", it.lowercase()) }
        file.md5.nonBlank()?.let { return PartCheck.Hash("md5", it.lowercase()) }
        file.crc.nonBlank()?.let { return PartCheck.Hash("crc32", it.lowercase().padStart(8, '0')) }
        return PartCheck.SizeOnly
    }

    fun fingerprint(rom: RomSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        rom.files.sortedBy { it.id }.forEach { f ->
            digest.update("${f.id}|${f.fileName}|${f.filePath}|${f.size}|${f.sha1}|${f.md5}|${f.crc}|${f.category}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
