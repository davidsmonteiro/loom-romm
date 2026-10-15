package ro.davidmontei.loom.data.catalog

import kotlinx.serialization.builtins.ListSerializer
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.RomDetailsEntity
import ro.davidmontei.loom.data.db.RomEntity
import ro.davidmontei.loom.data.db.RomFileEntity
import ro.davidmontei.loom.data.settings.SettingsStore
import ro.davidmontei.loom.romm.RommJson
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.dto.ArchiveMemberDto
import ro.davidmontei.loom.romm.dto.HeartbeatDto
import ro.davidmontei.loom.romm.dto.PlatformDto
import ro.davidmontei.loom.romm.dto.RomDto
import ro.davidmontei.loom.romm.pageRoms
import ro.davidmontei.loom.romm.rommCall
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class SyncProgress(val fetched: Int, val total: Int?)

data class SyncResult(val full: Boolean, val romsWritten: Int, val serverRomCount: Int, val serverVersion: String)

/**
 * Brings the offline catalog in line with the server.
 *
 * - Platforms: always fetched whole (a few dozen rows).
 * - ROMs: a full pass the first time, on request, and once after Loom starts storing a new ROM
 *   field; afterwards only ROMs with `updated_at` newer than the watermark minus a one-hour
 *   overlap. The watermark is the newest `updated_at` seen, so it runs on the server's clock,
 *   not the Thor's.
 * - Deletions: the server's complete id list is diffed against the local one.
 */
class CatalogSyncer(
    private val dao: CatalogDao,
    private val settings: SettingsStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Records what the sync's heartbeat says of the server (see [ro.davidmontei.loom.net.ServerInfo]). */
    private val onHeartbeat: suspend (HeartbeatDto) -> Unit = { settings.setServerVersion(it.system.version) },
) {
    suspend fun sync(api: RommApi, full: Boolean, onProgress: suspend (SyncProgress) -> Unit = {}): SyncResult {
        val heartbeat = rommCall { api.heartbeat() }
        onHeartbeat(heartbeat)
        val version = heartbeat.system.version

        val platforms = rommCall { api.platforms() }
        dao.upsertPlatforms(platforms.map { it.toEntity() })
        val serverPlatformIds = platforms.map { it.id }.toSet()
        dao.deletePlatformsWithoutInstalls(dao.platformIds().filterNot { it in serverPlatformIds })

        val stored = settings.snapshot()
        val previous = stored.catalogWatermark
        // Rows written by an older Loom lack fields it didn't store (title ids for save sync): those
        // only arrive with a full pass, since an incremental one skips ROMs that didn't change.
        val incremental = !full && previous != null && stored.catalogFields >= CATALOG_FIELDS
        val updatedAfter = if (incremental) overlap(previous!!) else null

        var newest: OffsetDateTime? = previous?.let(::parseInstant)
        var written = 0
        api.pageRoms(updatedAfter = updatedAfter).collect { page ->
            val roms = page.page.items
            dao.writePage(roms.map { it.toEntity() }, roms.flatMap { it.toFileEntities() }, roms.mapNotNull { it.toDetails() })
            written += roms.size
            roms.forEach { rom -> parseInstant(rom.updatedAt)?.let { if (newest == null || it > newest) newest = it } }
            onProgress(SyncProgress(page.fetched, page.total))
        }

        val serverIds = rommCall { api.romIdentifiers() }.toSet()
        dao.applyServerIds(serverIds)

        val fields = if (incremental) stored.catalogFields else CATALOG_FIELDS
        settings.setCatalogSynced(newest?.let(::formatInstant), nowMs(), fields)
        return SyncResult(full = !incremental, romsWritten = written, serverRomCount = serverIds.size, serverVersion = version)
    }

    companion object {
        /**
         * The ROM fields a full sync writes. Raised whenever ROM rows gain a field (2: title id and
         * save target; 3: RomM's summary, release year, genres and companies), so the first sync after
         * the upgrade is a full one that fills it everywhere. LoomApp queues that sync when Loom first
         * opens after the upgrade.
         */
        const val CATALOG_FIELDS = 3

        /** The most of a summary kept: a panel shows a few lines of it, and 20,000 games' summaries add up. */
        const val SUMMARY_LENGTH = 1_000

        /** The most genres and companies kept: what a line in a panel has room for. */
        const val GENRES = 3
        const val COMPANIES = 2

        private val WIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")

        fun parseInstant(raw: String): OffsetDateTime? =
            runCatching { OffsetDateTime.parse(raw.replace(' ', 'T')) }.getOrNull()

        /** Always UTC with an explicit "+00:00" offset, which RomM's parser accepts. */
        fun formatInstant(t: OffsetDateTime): String = t.withOffsetSameInstant(ZoneOffset.UTC).format(WIRE)

        fun overlap(watermark: String): String? = parseInstant(watermark)?.minusHours(1)?.let(::formatInstant)

        fun searchKey(vararg parts: String?): String = parts.filterNotNull().joinToString(" ") { fold(it) }

        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
    }
}

internal val ARCHIVE_MEMBERS = ListSerializer(ArchiveMemberDto.serializer())

internal fun PlatformDto.toEntity() = PlatformEntity(
    id = id, slug = slug, fsSlug = fsSlug, name = name,
    displayName = customName?.takeIf { it.isNotBlank() } ?: displayName,
    romCount = romCount, updatedAt = updatedAt,
)

internal fun RomDto.toEntity(): RomEntity {
    val title = name?.takeIf { it.isNotBlank() } ?: fsNameNoTags.ifBlank { fsNameNoExt }
    return RomEntity(
        id = id,
        platformId = platformId,
        platformSlug = platformSlug,
        platformFsSlug = platformFsSlug,
        title = title,
        searchKey = CatalogSyncer.searchKey(title, fsName),
        sortKey = CatalogSyncer.fold(nameSortKey?.takeIf { it.isNotBlank() } ?: title),
        fsName = fsName,
        fsNameNoExt = fsNameNoExt,
        fsExtension = fsExtension,
        sizeBytes = fsSizeBytes,
        hasSimpleSingleFile = hasSimpleSingleFile,
        hasNestedSingleFile = hasNestedSingleFile,
        hasMultipleFiles = hasMultipleFiles,
        coverSmall = pathCoverSmall?.takeIf { it.isNotBlank() },
        coverLarge = pathCoverLarge?.takeIf { it.isNotBlank() },
        regions = regions.joinToString(","),
        updatedAt = updatedAt,
        missingFromFs = missingFromFs,
        titleId = titleId?.takeIf { it.isNotBlank() },
        saveTarget = saveTarget?.takeIf { it.isNotBlank() },
        saveTargetLayout = saveTargetLayout?.takeIf { it.isNotBlank() },
    )
}

/**
 * What RomM says of the ROM beyond its files, as the panels show it: its summary on one line (they
 * have no room for paragraphs) and cut at a word near [CatalogSyncer.SUMMARY_LENGTH] characters, the
 * year of its first release in UTC, and its first few genres and companies. Null when it says none of it.
 */
internal fun RomDto.toDetails(): RomDetailsEntity? {
    val about = summary?.let(::shortSummary)
    val year = metadatum?.firstReleaseDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }
    val genres = names(metadatum?.genres, CatalogSyncer.GENRES)
    val companies = names(metadatum?.companies, CatalogSyncer.COMPANIES)
    if (about == null && year == null && genres.isEmpty() && companies.isEmpty()) return null
    return RomDetailsEntity(id, about, year, genres.joinToString("\n"), companies.joinToString("\n"))
}

private val SPACES = Regex("\\s+")

/** [names] on one line each, blanks and repeats left out, the first [most] of them. */
private fun names(names: List<String>?, most: Int): List<String> =
    names.orEmpty().map { it.replace(SPACES, " ").trim() }.filter { it.isNotEmpty() }.distinct().take(most)

/** [text] on one line, cut at the last word that fits [CatalogSyncer.SUMMARY_LENGTH] characters with an ellipsis; null when blank. */
internal fun shortSummary(text: String): String? {
    val plain = text.replace(SPACES, " ").trim()
    if (plain.isEmpty()) return null
    if (plain.length <= CatalogSyncer.SUMMARY_LENGTH) return plain
    val room = plain.substring(0, CatalogSyncer.SUMMARY_LENGTH) // the ellipsis takes the place of the space before the next word
    // At a space, unless the summary has none in its second half (a long URL): then mid-word, never mid-character.
    val cut = room.substringBeforeLast(' ', "").takeIf { it.length >= CatalogSyncer.SUMMARY_LENGTH / 2 }
        ?: room.dropLast(1).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return cut.trimEnd { it.isWhitespace() || it in ",;:.-–—" } + "…"
}

internal fun RomDto.toFileEntities(): List<RomFileEntity> = files.map { f ->
    RomFileEntity(
        id = f.id,
        romId = id,
        fileName = f.fileName,
        filePath = f.filePath,
        sizeBytes = f.fileSizeBytes,
        isTopLevel = f.isTopLevel,
        crc = f.crcHash?.takeIf { it.isNotBlank() },
        md5 = f.md5Hash?.takeIf { it.isNotBlank() },
        sha1 = f.sha1Hash?.takeIf { it.isNotBlank() },
        category = f.category,
        archiveMembersJson = f.archiveMembers?.let { RommJson.encodeToString(ARCHIVE_MEMBERS, it) },
    )
}
