package ro.davidmontei.loom.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A RomM platform as last seen on the server. */
@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey val id: Int,
    val slug: String,
    @ColumnInfo(name = "fs_slug") val fsSlug: String,
    val name: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "rom_count") val romCount: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

/**
 * A RomM ROM in the offline catalog. Rows deleted on the server are kept, flagged
 * [removedOnServer], while an install still references them, so they can be offloaded.
 */
@Entity(
    tableName = "roms",
    indices = [Index("platform_id"), Index("sort_key")],
)
data class RomEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "platform_id") val platformId: Int,
    @ColumnInfo(name = "platform_slug") val platformSlug: String,
    @ColumnInfo(name = "platform_fs_slug") val platformFsSlug: String,
    /** Display title: RomM's matched name, else the file name without tags. */
    val title: String,
    /** Lower-cased, accent-free title + file name for search. */
    @ColumnInfo(name = "search_key") val searchKey: String,
    @ColumnInfo(name = "sort_key") val sortKey: String,
    @ColumnInfo(name = "fs_name") val fsName: String,
    @ColumnInfo(name = "fs_name_no_ext") val fsNameNoExt: String,
    @ColumnInfo(name = "fs_extension") val fsExtension: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "has_simple_single_file") val hasSimpleSingleFile: Boolean,
    @ColumnInfo(name = "has_nested_single_file") val hasNestedSingleFile: Boolean,
    @ColumnInfo(name = "has_multiple_files") val hasMultipleFiles: Boolean,
    @ColumnInfo(name = "cover_small") val coverSmall: String?,
    @ColumnInfo(name = "cover_large") val coverLarge: String?,
    /** Comma-separated region tags, e.g. "USA,Europe". */
    val regions: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "missing_from_fs") val missingFromFs: Boolean,
    @ColumnInfo(name = "removed_on_server") val removedOnServer: Boolean = false,
)

/** One file of a ROM, with the hashes RomM computed (null when it didn't). */
@Entity(
    tableName = "rom_files",
    foreignKeys = [ForeignKey(RomEntity::class, ["id"], ["rom_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("rom_id")],
)
data class RomFileEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "rom_id") val romId: Int,
    @ColumnInfo(name = "file_name") val fileName: String,
    /** Directory of the file relative to RomM's library root, e.g. "roms/psx/Game/Disc 1". */
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "is_top_level") val isTopLevel: Boolean,
    val crc: String?,
    val md5: String?,
    val sha1: String?,
    /** RomM file category (game, update, dlc, manual…); null for a plain top-level game file. */
    val category: String?,
    /** JSON array of {name,size,crc_hash,md5_hash,sha1_hash}, or null. */
    @ColumnInfo(name = "archive_members_json") val archiveMembersJson: String?,
)

/**
 * A ROM present on the device as one entry in its system folder. Paths are stored relative to
 * the ROMs root together with the volume, so an unmounted SD card never reads as "uninstalled".
 */
@Entity(
    tableName = "installs",
    indices = [Index(value = ["volume_uuid", "path_key"], unique = true)],
)
data class InstallEntity(
    @PrimaryKey @ColumnInfo(name = "rom_id") val romId: Int,
    @ColumnInfo(name = "volume_uuid") val volumeUuid: String,
    /** The ROMs root the install was made under (it can be changed in Settings later). */
    @ColumnInfo(name = "roms_root") val romsRoot: String,
    /** Relative to the ROMs root, e.g. "psx/Final Fantasy VII.m3u". */
    @ColumnInfo(name = "rel_path") val relPath: String,
    /** NFC + case-folded [relPath], for collision checks on case-insensitive file systems. */
    @ColumnInfo(name = "path_key") val pathKey: String,
    @ColumnInfo(name = "platform_id") val platformId: Int,
    val kind: InstallKind,
    val bytes: Long,
    @ColumnInfo(name = "verified_by") val verifiedBy: VerifiedBy,
    val source: InstallSource,
    /** Fingerprint of the server's files at install time; lets the UI flag "update available". */
    @ColumnInfo(name = "rom_fingerprint") val romFingerprint: String?,
    @ColumnInfo(name = "installed_at") val installedAtEpochMs: Long,
)

enum class InstallKind { FILE, DIRECTORY }

enum class VerifiedBy { SHA1, MD5, CRC32, ARCHIVE_MEMBERS, ARCHIVE_CRC, SIZE, NONE }

enum class InstallSource { LOOM, ADOPTED }
