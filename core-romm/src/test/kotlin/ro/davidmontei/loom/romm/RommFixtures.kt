package ro.davidmontei.loom.romm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON bodies shaped like RomM 5.3.1 responses, including fields Loom doesn't model (metadata
 * blobs, ids from other providers) to prove they're ignored.
 */
object RommFixtures {
    const val HEARTBEAT_5_3_1 = """{"SYSTEM":{"VERSION":"5.3.1","GIT_BRANCH":null,"SHOW_SETUP_WIZARD":false},
        "METADATA_SOURCES":{"ANY_SOURCE_ENABLED":true},"FILESYSTEM":{"FS_PLATFORMS":[]},
        "OIDC":{"ENABLED":true,"PROVIDER":"authentik"}}"""

    fun platform(id: Int, slug: String, fsSlug: String = slug, name: String = slug.uppercase(), roms: Int = 1) =
        buildJsonObject {
            put("id", id); put("slug", slug); put("fs_slug", fsSlug); put("name", name)
            put("display_name", name); put("custom_name", JsonNull); put("rom_count", roms)
            put("fs_size_bytes", 1_000_000L * roms); put("updated_at", "2026-10-01T10:00:00+00:00")
            put("url_logo", JsonNull); put("igdb_id", 19); put("category", "Console")
            putJsonArray("firmware") {}
        }

    fun romFile(
        id: Int,
        romId: Int,
        name: String,
        size: Long,
        sha1: String? = null,
        md5: String? = null,
        crc: String? = null,
        category: String? = null,
        filePath: String = "roms/psx/Game",
        topLevel: Boolean = true,
        members: JsonArray? = null,
    ) = buildJsonObject {
        put("id", id); put("rom_id", romId); put("file_name", name); put("file_path", filePath)
        put("full_path", "$filePath/$name"); put("file_size_bytes", size); put("is_top_level", topLevel)
        put("crc_hash", crc.json()); put("md5_hash", md5.json()); put("sha1_hash", sha1.json())
        put("ra_hash", ""); put("chd_sha1_hash", JsonNull)
        put("archive_members", members ?: JsonNull)
        put("category", category.json())
        put("created_at", "2026-10-01T10:00:00+00:00"); put("updated_at", "2026-10-01T10:00:00+00:00")
        put("last_modified", JsonNull)
    }

    fun member(name: String, size: Long, crc: String, md5: String = "0".repeat(32), sha1: String = "0".repeat(40)) =
        buildJsonObject { put("name", name); put("size", size); put("crc_hash", crc); put("md5_hash", md5); put("sha1_hash", sha1) }

    fun rom(
        id: Int,
        platformId: Int = 1,
        platformSlug: String = "snes",
        fsName: String = "Game $id.sfc",
        name: String? = "Game $id",
        size: Long = 1024,
        files: List<JsonObject> = emptyList(),
        multiple: Boolean = files.size > 1,
        updatedAt: String = "2026-10-01T10:00:00+00:00",
        cover: String = "/assets/romm/resources/roms/1/$id/cover/small.png?ts=2026-10-01 10:00:00.123+00:00",
    ) = buildJsonObject {
        put("id", id); put("platform_id", platformId); put("platform_slug", platformSlug)
        put("platform_fs_slug", platformSlug); put("platform_custom_name", JsonNull)
        put("platform_display_name", platformSlug.uppercase())
        put("name", name.json()); put("name_sort_key", name?.lowercase().json()); put("slug", JsonNull)
        put("fs_name", fsName); put("fs_name_no_tags", fsName.substringBeforeLast('.'))
        put("fs_name_no_ext", fsName.substringBeforeLast('.'))
        put("fs_extension", if (fsName.contains('.')) fsName.substringAfterLast('.') else "")
        put("fs_path", "roms/$platformSlug"); put("full_path", "roms/$platformSlug/$fsName")
        put("fs_size_bytes", size)
        put("path_cover_small", cover); put("path_cover_large", cover.replace("small", "big"))
        put("url_cover", "https://images.igdb.com/igdb/image/upload/t_cover_big/co1.jpg")
        putJsonArray("regions") { add(JsonPrimitive("USA")) }
        putJsonArray("languages") {}
        putJsonArray("tags") {}
        put("revision", JsonNull)
        put("crc_hash", "1234abcd"); put("md5_hash", "0".repeat(32)); put("sha1_hash", "1".repeat(40))
        put("has_simple_single_file", !multiple); put("has_nested_single_file", false)
        put("has_multiple_files", multiple)
        put("missing_from_fs", false); put("is_physical", false); put("has_file_on_disk", true)
        put("created_at", "2026-09-01T10:00:00+00:00"); put("updated_at", updatedAt)
        put("files", JsonArray(files))
        putJsonObject("igdb_metadata") { put("total_rating", "91.2"); putJsonArray("genres") { add(JsonPrimitive("RPG")) } }
        putJsonObject("rom_user") { put("is_main_sibling", true) }
        putJsonArray("sibling_roms") {}
        put("summary", "A fixture ROM.")
    }

    fun page(items: List<JsonObject>, total: Int?, limit: Int, offset: Int) = buildJsonObject {
        put("items", JsonArray(items)); put("total", total.json()); put("limit", limit); put("offset", offset)
        putJsonObject("char_index") {}; putJsonArray("rom_id_index") {}; putJsonObject("filter_values") {}
    }

    fun ids(vararg ids: Int): String = buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Int?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
}
