package ro.davidmontei.loom.data.saves

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.MultipartReader
import okio.Buffer
import ro.davidmontei.loom.romm.RommJson
import ro.davidmontei.loom.storage.saves.SaveHasher
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * RomM 5.3.1's save sync as far as Loom meets it, on a MockWebServer: negotiate pairs on (ROM,
 * slot) and compares through this device's sync rows (`compare_save_state`); an upload is refused
 * with 409 when the device never synced the slot head, deduplicated by hash, and writes a sync
 * row; `/content` with optimistic=false writes none; `/downloaded` does. Tests can also make
 * negotiate answer something else, or the upload fail.
 */
class FakeRomm(private val scratch: Path) : Dispatcher() {
    class Save(
        val id: Int,
        val romId: Int,
        val fileName: String,
        val slot: String?,
        val emulator: String?,
        val bytes: ByteArray,
        val hash: String?,
        var updatedAt: Instant,
        val originDevice: String?,
    )

    /** The server's clock; every write moves it on by a second. */
    var clock: Instant = Instant.parse("2026-10-14T11:00:00Z")
    val saves = CopyOnWriteArrayList<Save>()
    /** (device, save id) → last_synced_at. */
    val syncRows = mutableMapOf<Pair<String, Int>, Instant>()
    val devices = mutableListOf<JsonObject>()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val completed = CopyOnWriteArrayList<Triple<Int, Int, Int>>()

    /** Replaces negotiate's operations for a ROM with this action (the server and slot stay as they are). */
    val negotiateSays = mutableMapOf<Int, String>()
    /** Answers uploads with this instead (e.g. a 409). */
    var uploadAnswer: (() -> MockResponse)? = null
    /** How long negotiate takes, to catch two runs overlapping. */
    var negotiateDelayMs = 0L

    private val openSessions = AtomicInteger()
    @Volatile var mostOpenSessions = 0
    private var nextId = 500
    private var nextSession = 40

    fun addSave(
        romId: Int, bytes: ByteArray, emulator: String? = "azahar", slot: String? = "autosave", device: String? = "dev-deck",
        fileName: String = "Save [${clock.plusSeconds(1)}].zip", hash: String? = hashOf(bytes),
    ): Save {
        clock = clock.plusSeconds(1)
        val save = Save(nextId++, romId, fileName, slot, emulator, bytes, hash, clock, device)
        saves += save
        return save
    }

    fun head(romId: Int): Save? = saves.filter { it.romId == romId && it.slot == "autosave" }.maxByOrNull { it.updatedAt }

    fun paths(): List<String> = requests.map { "${it.method} ${it.url.encodedPath}" }

    fun uploads(): List<RecordedRequest> = requests.filter { it.method == "POST" && it.url.encodedPath == "/api/saves" }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        return when {
            request.method == "POST" && path == "/api/sync/negotiate" -> negotiate(json(request))
            request.method == "POST" && path.matches(Regex("/api/sync/sessions/\\d+/complete")) -> complete(path, json(request))
            request.method == "POST" && path == "/api/saves" -> uploadAnswer?.invoke() ?: upload(request)
            request.method == "GET" && path == "/api/saves" -> list(request)
            request.method == "GET" && path.matches(Regex("/api/saves/\\d+/content")) -> content(path)
            request.method == "POST" && path.matches(Regex("/api/saves/\\d+/downloaded")) -> downloaded(path, json(request))
            request.method == "GET" && path == "/api/devices" -> ok(JsonArray(devices))
            else -> MockResponse.Builder().code(404).body("""{"detail":"Not found: $path"}""").build()
        }
    }

    // --- negotiate (endpoints/sync.py) ---

    private fun negotiate(body: JsonObject): MockResponse {
        val open = openSessions.incrementAndGet()
        mostOpenSessions = maxOf(mostOpenSessions, open)
        if (negotiateDelayMs > 0) Thread.sleep(negotiateDelayMs)
        val device = body["device_id"]!!.jsonPrimitive.content
        val clientSaves = body["saves"]!!.jsonArray.map { it.jsonObject }
        val scope = body["rom_ids"]!!.jsonArray.map { it.jsonPrimitive.int }.toSet() + clientSaves.map { it.int("rom_id") }
        val heads = saves.filter { it.slot != null && it.romId in scope }.groupBy { it.romId to it.slot!! }
            .mapValues { (_, versions) -> versions.maxBy { it.updatedAt } }
        val ops = mutableListOf<JsonObject>()
        val matched = mutableSetOf<Int>()
        for (cs in clientSaves) {
            val head = heads[cs.int("rom_id") to cs.string("slot")!!]
            if (head == null) {
                ops += op("upload", cs.int("rom_id"), null, cs.string("file_name")!!, cs.string("emulator"), "Save exists on client but not on server")
                continue
            }
            matched += head.id
            val action = compare(cs.string("content_hash"), Instant.parse(cs.string("updated_at")), head, syncRows[device to head.id])
            ops += op(negotiateSays[head.romId] ?: action, head.romId, head, reason = "compared")
        }
        for (head in heads.values) {
            if (head.id in matched) continue
            val synced = syncRows[device to head.id]
            if (synced != null && !head.updatedAt.isAfter(synced)) continue // "the client deleted it"
            ops += op(negotiateSays[head.romId] ?: "download", head.romId, head, reason = "Save exists on server but not on client")
        }
        val session = nextSession++
        return ok(buildJsonObject {
            put("session_id", session); put("operations", JsonArray(ops))
            for (a in listOf("upload", "download", "conflict", "no_op")) put("total_$a", ops.count { it.string("action") == a })
        })
    }

    /** `handler/sync/comparison.py`. */
    private fun compare(clientHash: String?, clientAt: Instant, head: Save, synced: Instant?): String {
        if (clientHash != null && head.hash != null && clientHash == head.hash) return "no_op"
        if (synced != null) {
            val client = clientAt.isAfter(synced)
            val server = head.updatedAt.isAfter(synced)
            return when {
                client && server -> "conflict"
                client -> "upload"
                server -> "download"
                else -> "no_op"
            }
        }
        return when {
            clientAt.isAfter(head.updatedAt) -> "upload"
            clientAt.isBefore(head.updatedAt) -> "download"
            else -> "conflict"
        }
    }

    private fun complete(path: String, body: JsonObject): MockResponse {
        openSessions.decrementAndGet()
        val session = path.split('/')[4].toInt()
        val done = body.int("operations_completed")
        val failed = body.int("operations_failed")
        completed += Triple(session, done, failed)
        return ok(buildJsonObject {
            put("session", buildJsonObject {
                put("id", session); put("status", "COMPLETED"); put("operations_planned", done + failed)
                put("operations_completed", done); put("operations_failed", failed)
            })
        })
    }

    // --- saves (endpoints/saves.py) ---

    private fun upload(request: RecordedRequest): MockResponse {
        val url = request.url
        val romId = url.queryParameter("rom_id")!!.toInt()
        val slot = url.queryParameter("slot")
        val device = url.queryParameter("device_id")
        val overwrite = url.queryParameter("overwrite") == "true"
        val (fileName, bytes) = savePart(request)
        if (device != null && slot != null && !overwrite) {
            val latest = saves.filter { it.romId == romId && it.slot == slot }.maxByOrNull { it.updatedAt }
            val synced = latest?.let { syncRows[device to it.id] }
            if (latest != null && (synced == null || synced.isBefore(latest.updatedAt))) {
                return MockResponse.Builder().code(409).setHeader("Content-Type", "application/json")
                    .body("""{"detail":"Slot has a newer save since your last sync"}""").build()
            }
        }
        val hash = hashOf(bytes)
        if (slot != null && !overwrite) {
            saves.firstOrNull { it.romId == romId && it.slot == slot && it.hash == hash }?.let { return ok(json(it)) }
        }
        clock = clock.plusSeconds(1)
        val stored = if (slot != null) "${fileName.substringBeforeLast('.')} [$clock].${fileName.substringAfterLast('.')}" else fileName
        val save = Save(nextId++, romId, stored, slot, url.queryParameter("emulator"), bytes, hash, clock, device)
        saves += save
        if (device != null) syncRows[device to save.id] = save.updatedAt
        if (slot != null && url.queryParameter("autocleanup") == "true") {
            val keep = url.queryParameter("autocleanup_limit")!!.toInt()
            saves.filter { it.romId == romId && it.slot == slot }.sortedByDescending { it.updatedAt }.drop(keep).forEach { saves.remove(it) }
        }
        return ok(json(save))
    }

    private fun list(request: RecordedRequest): MockResponse {
        val url = request.url
        val romIds = (url.queryParameterValues("rom_ids") + url.queryParameterValues("rom_id")).mapNotNull { it?.toInt() }.toSet()
        val slot = url.queryParameter("slot")
        val found = saves.filter { (romIds.isEmpty() || it.romId in romIds) && (slot == null || it.slot == slot) }
        return ok(JsonArray(found.map(::json)))
    }

    private fun content(path: String): MockResponse {
        val save = saves.firstOrNull { it.id == path.split('/')[3].toInt() } ?: return MockResponse.Builder().code(404).build()
        return MockResponse.Builder().code(200).setHeader("Content-Type", "application/zip").body(Buffer().write(save.bytes)).build()
    }

    private fun downloaded(path: String, body: JsonObject): MockResponse {
        val save = saves.first { it.id == path.split('/')[3].toInt() }
        syncRows[body.string("device_id")!! to save.id] = save.updatedAt
        return ok(json(save))
    }

    // --- JSON ---

    fun json(save: Save): JsonObject = buildJsonObject {
        put("id", save.id); put("rom_id", save.romId); put("file_name", save.fileName); put("file_size_bytes", save.bytes.size)
        put("missing_from_fs", false); put("created_at", save.updatedAt.toString()); put("updated_at", save.updatedAt.toString())
        put("emulator", save.emulator.json()); put("slot", save.slot.json()); put("content_hash", save.hash.json())
        put("origin_device_id", save.originDevice.json()); put("device_syncs", buildJsonArray { })
    }

    private fun op(action: String, romId: Int, head: Save?, fileName: String? = null, emulator: String? = null, reason: String) = buildJsonObject {
        put("action", action); put("rom_id", romId); put("save_id", (head?.id).json()); put("file_name", fileName ?: head!!.fileName)
        put("slot", head?.slot ?: "autosave"); put("emulator", (head?.emulator ?: emulator).json()); put("reason", reason)
        put("server_updated_at", (head?.updatedAt?.toString()).json()); put("server_content_hash", head?.hash.json())
    }

    fun device(id: String, identifier: String) = buildJsonObject {
        put("id", id); put("name", "AYN Thor (Loom)"); put("platform", "android"); put("client", "loom"); put("client_version", "0.2.0")
        put("client_device_identifier", identifier); put("sync_enabled", true); put("last_seen", "2026-10-14T10:00:00+00:00")
    }

    private fun savePart(request: RecordedRequest): Pair<String, ByteArray> {
        val boundary = request.headers["Content-Type"]!!.substringAfter("boundary=")
        MultipartReader(Buffer().write(request.body!!), boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val disposition = part.headers["Content-Disposition"]!!
                if ("name=\"saveFile\"" in disposition) {
                    return disposition.substringAfter("filename=\"").substringBefore('"') to part.body.readByteArray()
                }
            }
        }
        error("No saveFile part")
    }

    /** RomM's content hash of an uploaded file. */
    fun hashOf(bytes: ByteArray): String {
        val file = Files.createTempFile(scratch, "save", ".bin")
        try {
            Files.write(file, bytes)
            return SaveHasher.hashFile(file)
        } finally {
            Files.delete(file)
        }
    }

    private fun json(request: RecordedRequest): JsonObject = RommJson.parseToJsonElement(request.body!!.utf8()).jsonObject
    private fun ok(body: JsonElement) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body.toString()).build()
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun String?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun Int?.json(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
}
