package ro.davidmontei.loom.romm.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Hand-written mirrors of RomM 5.4.0 schemas (components/schemas in /openapi.json), declaring only
// the fields Loom reads. Unknown fields are ignored by the Json config. Nullability follows the
// schema exactly; RommSchemaConformanceTest checks every class here against the committed snapshot,
// and that a field older servers don't send is optional (5.3.1's snapshot is kept for that).

/** `HeartbeatResponse` (only the part Loom needs). */
@Serializable
data class HeartbeatDto(
    @SerialName("SYSTEM") val system: SystemDto,
)

/** `SystemDict`. */
@Serializable
data class SystemDto(
    @SerialName("VERSION") val version: String,
    @SerialName("SHOW_SETUP_WIZARD") val showSetupWizard: Boolean,
)

/** `UserSchema`, used to validate a token and show who Loom is paired as. */
@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    val enabled: Boolean,
    @SerialName("oauth_scopes") val oauthScopes: List<String>,
)

/** `PlatformSchema`. */
@Serializable
data class PlatformDto(
    val id: Int,
    val slug: String,
    @SerialName("fs_slug") val fsSlug: String,
    val name: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("custom_name") val customName: String? = null,
    @SerialName("rom_count") val romCount: Int,
    @SerialName("fs_size_bytes") val fsSizeBytes: Long,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("url_logo") val urlLogo: String? = null,
)

/**
 * `SimpleRomSchema` (list items) and, field for field, `DetailedRomSchema` (`GET /api/roms/{id}`),
 * which is a superset. `files` is only populated by the list endpoint when `with_files=true`.
 */
@Serializable
data class RomDto(
    val id: Int,
    @SerialName("platform_id") val platformId: Int,
    @SerialName("platform_slug") val platformSlug: String,
    @SerialName("platform_fs_slug") val platformFsSlug: String,
    val name: String?,
    @SerialName("name_sort_key") val nameSortKey: String?,
    @SerialName("fs_name") val fsName: String,
    @SerialName("fs_name_no_tags") val fsNameNoTags: String,
    @SerialName("fs_name_no_ext") val fsNameNoExt: String,
    @SerialName("fs_extension") val fsExtension: String,
    /** `fs_path/fs_name` relative to RomM's library root; folder ROMs' files live under it. */
    @SerialName("full_path") val fullPath: String,
    @SerialName("fs_size_bytes") val fsSizeBytes: Long,
    @SerialName("path_cover_small") val pathCoverSmall: String?,
    @SerialName("path_cover_large") val pathCoverLarge: String?,
    @SerialName("url_cover") val urlCover: String?,
    val regions: List<String>,
    val languages: List<String>,
    val tags: List<String>,
    val revision: String?,
    @SerialName("crc_hash") val crcHash: String?,
    @SerialName("md5_hash") val md5Hash: String?,
    @SerialName("sha1_hash") val sha1Hash: String?,
    /**
     * The game's own id where RomM could read one at scan time (3DS, Switch, PSP, …). Null for
     * compressed ROMs, and absent (read as null) on older servers that don't send these three.
     */
    @SerialName("title_id") val titleId: String?,
    /** Where the game keeps its save, keyed as [saveTargetLayout] says, e.g. "0004000e/0011c500". */
    @SerialName("save_target") val saveTarget: String?,
    /** `SaveTargetLayout`: folder-exact, folder-prefix, file-exact, file-prefix or folder-split. */
    @SerialName("save_target_layout") val saveTargetLayout: String?,
    @SerialName("has_simple_single_file") val hasSimpleSingleFile: Boolean,
    @SerialName("has_nested_single_file") val hasNestedSingleFile: Boolean,
    @SerialName("has_multiple_files") val hasMultipleFiles: Boolean,
    @SerialName("missing_from_fs") val missingFromFs: Boolean,
    @SerialName("is_physical") val isPhysical: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val files: List<RomFileDto>,
)

/** `RomFileSchema`. Hashes may be null *or empty*; RomM writes "" when it skipped hashing. */
@Serializable
data class RomFileDto(
    val id: Int,
    @SerialName("rom_id") val romId: Int,
    @SerialName("file_name") val fileName: String,
    @SerialName("file_path") val filePath: String,
    @SerialName("full_path") val fullPath: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
    @SerialName("is_top_level") val isTopLevel: Boolean,
    @SerialName("crc_hash") val crcHash: String?,
    @SerialName("md5_hash") val md5Hash: String?,
    @SerialName("sha1_hash") val sha1Hash: String?,
    @SerialName("archive_members") val archiveMembers: List<ArchiveMemberDto>?,
    val category: String?,
    @SerialName("updated_at") val updatedAt: String,
)

/** `RomArchiveMember`: per-member hashes of an archive ROM file (computed over member bytes). */
@Serializable
data class ArchiveMemberDto(
    val name: String,
    val size: Long,
    @SerialName("crc_hash") val crcHash: String,
    @SerialName("md5_hash") val md5Hash: String,
    @SerialName("sha1_hash") val sha1Hash: String,
)

/** `CustomLimitOffsetPage_SimpleRomSchema_` (minus the indexes Loom asks the server not to build). */
@Serializable
data class RomPageDto(
    val items: List<RomDto>,
    val total: Int?,
    val limit: Int,
    val offset: Int,
)
