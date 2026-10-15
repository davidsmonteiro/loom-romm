package ro.davidmontei.loom.data.download

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * A game RomM holds in one file, [fileId] of [data], as `GET api/roms/{id}` answers it, on the platform
 * RomM calls [platformName].
 */
fun romJson(
    id: Int, fileId: Int, data: ByteArray, platformId: Int = 1, slug: String = "snes", name: String = "Chrono Trigger",
    fileName: String = "$name.sfc", platformName: String = "Super Nintendo Entertainment System",
): JsonObject = buildJsonObject {
    val stem = fileName.substringBeforeLast('.')
    put("id", id); put("platform_id", platformId); put("platform_slug", slug); put("platform_fs_slug", slug)
    put("platform_display_name", platformName); put("platform_custom_name", null as String?)
    put("name", name); put("name_sort_key", null as String?); put("fs_name", fileName)
    put("fs_name_no_tags", stem); put("fs_name_no_ext", stem); put("fs_extension", fileName.substringAfterLast('.'))
    put("full_path", "roms/$slug/$fileName")
    put("fs_size_bytes", data.size); put("path_cover_small", ""); put("path_cover_large", "")
    put("url_cover", null as String?); put("regions", buildJsonArray { add(JsonPrimitive("USA")) })
    put("languages", JsonArray(emptyList())); put("tags", JsonArray(emptyList())); put("revision", null as String?)
    put("crc_hash", ""); put("md5_hash", null as String?); put("sha1_hash", null as String?)
    put("has_simple_single_file", true); put("has_nested_single_file", false); put("has_multiple_files", false)
    put("missing_from_fs", false); put("is_physical", false)
    put("created_at", "2026-09-01T00:00:00+00:00"); put("updated_at", "2026-10-01T10:00:00+00:00")
    put("files", JsonArray(listOf(buildJsonObject {
        put("id", fileId); put("rom_id", id); put("file_name", fileName); put("file_path", "roms/$slug")
        put("full_path", "roms/$slug/$fileName"); put("file_size_bytes", data.size); put("is_top_level", true)
        put("crc_hash", ""); put("md5_hash", null as String?); put("sha1_hash", sha1(data))
        put("archive_members", null as String?); put("category", null as String?)
        put("updated_at", "2026-10-01T10:00:00+00:00")
    })))
}
