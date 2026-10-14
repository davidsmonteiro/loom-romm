package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.SaveSyncState
import ro.davidmontei.loom.data.settings.ServerConfig
import ro.davidmontei.loom.romm.RommException
import ro.davidmontei.loom.romm.RommJson
import ro.davidmontei.loom.storage.saves.SaveHasher
import ro.davidmontei.loom.storage.saves.SaveRestorer
import ro.davidmontei.loom.storage.saves.SyncDecision
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class SaveSyncEngineTest : SaveSyncTestBase() {

    private val saves get() = db.saveDao()
    private suspend fun state(game: Game) = saves.unit(game.romId)!!.state
    private fun negotiations() = romm.requests.filter { it.url.encodedPath == "/api/sync/negotiate" }
        .map { RommJson.parseToJsonElement(it.body!!.utf8()).jsonObject }
    private fun contentRequests() = romm.requests.filter { it.url.encodedPath.endsWith("/content") }

    // --- uploads ---

    @Test fun aNewSaveGoesUpAndIsInSyncAfterwards() = runBlocking<Unit> {
        install(pokemonX)
        val hash = writeSave(pokemonX, bytes(1))

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).containsExactly(7)
        val sent = negotiations().single()
        assertThat(sent["device_id"]!!.jsonPrimitive.content).isEqualTo("dev-thor")
        assertThat(sent["rom_ids"]!!.jsonArray.map { it.jsonPrimitive.int }).containsExactly(7)
        val state = sent["saves"]!!.jsonArray.single().jsonObject
        assertThat(state["slot"]!!.jsonPrimitive.content).isEqualTo("autosave")
        assertThat(state["content_hash"]!!.jsonPrimitive.content).isEqualTo(hash)
        assertThat(state["updated_at"]!!.jsonPrimitive.content).isEqualTo(clock.minus(Duration.ofMinutes(10)).toString())

        val url = romm.uploads().single().url
        val query = url.queryParameterNames.associateWith { url.queryParameter(it) }
        assertThat(query).containsExactly(
            "rom_id", "7", "emulator", "azahar", "slot", "autosave", "device_id", "dev-thor", "session_id", "40",
            "content_hash", hash, "overwrite", "false", "autocleanup", "true", "autocleanup_limit", "10",
        )
        val head = romm.head(7)!!
        assertThat(head.hash).isEqualTo(hash) // RomM hashes the archive exactly as Loom hashed the folder
        assertThat(saves.baseline(7, "autosave")!!.serverSaveId).isEqualTo(head.id)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)
        assertThat(romm.completed.single()).isEqualTo(Triple(40, 1, 0))

        val again = engine().sync(SaveSyncRequest.PUSH)
        assertThat(again.uploaded).isEmpty()
        assertThat(romm.uploads()).hasSize(1)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    @Test fun aChangedSaveGoesUpAsTheNewestVersion() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val changed = writeSave(pokemonX, bytes(2), minutesAgo = 5)

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(7)
        assertThat(romm.head(7)!!.hash).isEqualTo(changed)
        assertThat(saves.baseline(7, "autosave")!!.localHash).isEqualTo(changed)
    }

    /** RomM's 409: a newer save reached the slot first. Recorded as a conflict, and not hammered on. */
    @Test fun aRefusedUploadBecomesAConflictThatIsntRetried() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.uploadAnswer = {
            mockwebserver3.MockResponse.Builder().code(409).setHeader("Content-Type", "application/json")
                .body("""{"detail":"Slot has a newer save since your last sync"}""").build()
        }

        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).containsExactly(7)
        assertThat(saves.conflict(7)!!.reason).isEqualTo(SyncDecision.UPLOAD_REJECTED.name)
        assertThat(saves.baseline(7, "autosave")).isNull()
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.CONFLICT)

        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).containsExactly(7)
        assertThat(romm.uploads()).hasSize(1)

        assertThat(engine().resolveConflict(7, ConflictChoice.SKIP)).isEqualTo(ResolveResult.Done)
        assertThat(engine().sync(SaveSyncRequest.PUSH).conflicts).isEmpty()
        assertThat(saves.conflict(7)!!.skipped).isTrue()
        assertThat(romm.uploads()).hasSize(1)
    }

    /**
     * The Thor went back to an older save (restored by hand): RomM matches its bytes to an older
     * version and makes no new head. Loom uploads again with overwrite, on purpose.
     */
    @Test fun bytesRommHasInAnOlderVersionAreMadeTheNewest() = runBlocking<Unit> {
        install(pokemonX)
        val first = writeSave(pokemonX, bytes(1), minutesAgo = 30)
        engine().sync(SaveSyncRequest.PUSH)
        writeSave(pokemonX, bytes(2), minutesAgo = 20)
        engine().sync(SaveSyncRequest.PUSH)
        writeSave(pokemonX, bytes(1), minutesAgo = 10)

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(7)

        val uploads = romm.uploads()
        assertThat(uploads).hasSize(4)
        assertThat(uploads[2].url.queryParameter("overwrite")).isEqualTo("false")
        assertThat(uploads[3].url.queryParameter("overwrite")).isEqualTo("true")
        val head = romm.head(7)!!
        assertThat(head.hash).isEqualTo(first)
        assertThat(saves.baseline(7, "autosave")!!.serverSaveId).isEqualTo(head.id)
    }

    @Test fun aSaveWrittenMomentsAgoWaitsForAQuietMinute() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1), minutesAgo = 0)

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).isEmpty()
        assertThat(report.waiting).containsExactly(7)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.WRITING)
        assertThat(romm.uploads()).isEmpty()

        clock = clock.plus(Duration.ofMinutes(1))
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(7)
    }

    @Test fun aSaveThatChangesBetweenTheTwoHashesIsNotUploaded() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        onPause = { File(saveFolder(pokemonX), "00000001/main").appendBytes(byteArrayOf(9)) }

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).isEmpty()
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.WRITING)
        assertThat(romm.uploads()).isEmpty()
    }

    /** Touched while Loom waited, even with the same bytes: the emulator is at work, so Loom waits too. */
    @Test fun aSaveTouchedBetweenTheTwoHashesIsNotUploaded() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        onPause = { settle(pokemonX, minutesAgo = 2) }

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).isEmpty()
        assertThat(romm.uploads()).isEmpty()
    }

    /**
     * Both hashes are read fresh, five seconds apart. Here the scan reuses its cached hash (same
     * sizes and times), the save holds other bytes at the first read, and is back to the cached
     * bytes when zipped: it was changing, so nothing goes up.
     */
    @Test fun bothHashesAreFreshReadsOfTheSave() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1), minutesAgo = 0)
        assertThat(engine().sync(SaveSyncRequest.PUSH).waiting).containsExactly(7) // too fresh; its hash is cached
        clock = clock.plus(Duration.ofMinutes(2))
        writeSave(pokemonX, bytes(2), minutesAgo = 2) // same size and time as the cached scan
        onPause = { writeSave(pokemonX, bytes(1), minutesAgo = 2) }

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).isEmpty()
        assertThat(romm.uploads()).isEmpty()
    }

    // --- downloads ---

    @Test fun pullTakesTheServersNewerSaveAfterBackingUpTheThors() = runBlocking<Unit> {
        install(pokemonX)
        val old = writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val newer = romm.addSave(7, archiveOf(bytes(2))) // played on another device since

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.downloaded).containsExactly(7)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))
        assertThat(backups(pokemonX).map { SaveHasher.hashFile(it.toPath()) }).containsExactly(old)
        val content = contentRequests().single().url
        assertThat(content.encodedPath).isEqualTo("/api/saves/${newer.id}/content")
        assertThat(content.queryParameter("optimistic")).isEqualTo("false")
        assertThat(content.queryParameter("device_id")).isEqualTo("dev-thor")
        assertThat(romm.paths()).contains("POST /api/saves/${newer.id}/downloaded")
        assertThat(saves.baseline(7, "autosave")!!.serverSaveId).isEqualTo(newer.id)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)

        // RomM now agrees the Thor holds it: nothing to upload.
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).isEmpty()
        assertThat(romm.uploads()).hasSize(1)
    }

    @Test fun pushOnlyCountsANewerServerSave() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)))

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.serverNewer).containsExactly(7)
        assertThat(report.downloaded).isEmpty()
        assertThat(contentRequests()).isEmpty()
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.SERVER_NEWER)
    }

    /** Progress on the Thor and a newer save on RomM: a conflict, even when RomM asks for the download. */
    @Test fun pullNeverReplacesProgressMadeOnTheThor() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1), minutesAgo = 30)
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)))
        writeSave(pokemonX, bytes(3))
        romm.negotiateSays[7] = "download"

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.downloaded).isEmpty()
        assertThat(report.conflicts).containsExactly(7)
        assertThat(saves.conflict(7)!!.reason).isEqualTo(SyncDecision.BOTH_CHANGED.name)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(3))
        assertThat(contentRequests()).isEmpty()
        assertThat(backups(pokemonX)).isEmpty()
    }

    /**
     * The scan reused its last hash (same sizes and times), but the bytes changed. The download
     * hashes the save again before touching it, and leaves it alone.
     */
    @Test fun pullChecksTheSaveAgainBeforeReplacingIt() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)))
        writeSave(pokemonX, bytes(3)) // same size, same time: the scan can't tell

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.downloaded).isEmpty()
        assertThat(report.failed.getValue(7)).contains("changed")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(3))
        assertThat(backups(pokemonX)).isEmpty()
    }

    @Test fun aSaveFromAnotherEmulatorIsNeverAppliedAlone() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)), emulator = "citra")

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.conflicts).containsExactly(7)
        assertThat(saves.conflict(7)!!.reason).isEqualTo(SyncDecision.FOREIGN_EMULATOR.name)
        assertThat(saves.conflict(7)!!.serverEmulator).isEqualTo("citra")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
        assertThat(contentRequests()).isEmpty()
    }

    @Test fun aRestoreThatFailsHalfwayLeavesTheThorsSaveAsItWas() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val before = saves.baseline(7, "autosave")
        val newer = romm.addSave(7, archiveOf(bytes(2)))
        var moves = 0
        mover = SaveRestorer.FileMover { from, to ->
            if (++moves == 3) throw IOException("card removed")
            SaveRestorer.FileMover.Rename.move(from, to)
        }

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.failed.keys).containsExactly(7)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
        assertThat(File(saveFolder(pokemonX), "00000001.metadata").exists()).isTrue()
        assertThat(romm.paths()).doesNotContain("POST /api/saves/${newer.id}/downloaded")
        assertThat(saves.baseline(7, "autosave")).isEqualTo(before)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.ERROR)
        assertThat(romm.completed.last().third).isEqualTo(1)

        mover = SaveRestorer.FileMover.Rename
        assertThat(engine().sync(SaveSyncRequest.PULL).downloaded).containsExactly(7)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))
    }

    @Test fun aGameThatNeverSavedHereGetsTheServersSaveOnPull() = runBlocking<Unit> {
        install(pokemonX)
        romm.addSave(7, archiveOf(bytes(2)))

        assertThat(engine().sync(SaveSyncRequest.PULL).downloaded).containsExactly(7)
        assertThat(localMain(pokemonX)).isEqualTo(bytes(2))
        assertThat(backups(pokemonX)).isEmpty() // there was nothing to back up
    }

    /** A download that isn't the save RomM hashed (cut short, or altered on the way) replaces nothing. */
    @Test fun aDownloadThatDoesntMatchRommsHashReplacesNothing() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val newer = romm.addSave(7, archiveOf(bytes(2)), hash = "0".repeat(32))

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.failed.getValue(7)).contains("doesn't match")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
        assertThat(romm.paths()).doesNotContain("POST /api/saves/${newer.id}/downloaded")
    }

    @Test fun aServerSaveThatIsntAnArchiveIsNotRestored() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, bytes(2)) // a bare save file, as another client might upload

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.failed.getValue(7)).contains("isn't a save archive")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
    }

    @Test fun aDownloadLargerThanASaveCanBeIsRefused() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)))

        val report = engine(maxDownloadBytes = 100).sync(SaveSyncRequest.PULL)

        assertThat(report.failed.getValue(7)).contains("larger than a save can be")
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
    }

    /** Other slots (another app's "channel", manual saves) are none of Loom's business. */
    @Test fun savesInOtherSlotsAreLeftAlone() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)), slot = "channel-1")

        val report = engine().sync(SaveSyncRequest.PULL)

        assertThat(report.downloaded).isEmpty()
        assertThat(report.conflicts).isEmpty()
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    /** Loom died while swapping a restored save in: the next run puts the old save back before reading it. */
    @Test fun aRestoreLoomDiedInIsUndoneBeforeTheSaveIsRead() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val title = saveFolder(pokemonX).parentFile!!
        val work = File(title, ".loom-restore-1")
        File(work, "old/data/00000001").mkdirs()
        File(work, "journal").writeText("data/00000001/main")
        File(saveFolder(pokemonX), "00000001/main").renameTo(File(work, "old/data/00000001/main"))
        File(saveFolder(pokemonX), "00000001/main").writeBytes(bytes(9)) // the new file was in place

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
        assertThat(work.exists()).isFalse()
        assertThat(report.uploaded).isEmpty()
        assertThat(romm.uploads()).hasSize(1)
    }

    @Test fun theSameBytesOnBothSidesAreAdopted() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        val there = romm.addSave(7, archiveOf(bytes(1)))

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.adopted).containsExactly(7)
        assertThat(romm.uploads()).isEmpty()
        assertThat(romm.paths()).contains("POST /api/saves/${there.id}/downloaded")
        assertThat(saves.baseline(7, "autosave")!!.serverSaveId).isEqualTo(there.id)
    }

    /**
     * After a sync, the save is deleted on the Thor. RomM leaves the slot out of negotiate ("the
     * client deleted it"); Loom reads the head from the save list instead of taking the slot as gone.
     */
    @Test fun aSaveDeletedOnTheThorIsNotAConflict() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        saveFolder(pokemonX).deleteRecursively()

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.conflicts).isEmpty()
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.LOCAL_MISSING)
        assertThat(romm.paths()).contains("GET /api/saves")
    }

    // --- without RomM ---

    private suspend fun rommOutOfReach() = settings.setServer(ServerConfig("http://127.0.0.1:1/".toHttpUrl()), "5.3.1")

    /** A look that can't reach RomM (offline, the server down). */
    private suspend fun checkWithoutRomm(romId: Int) {
        val thrown = runCatching { engine().sync(SaveSyncRequest.CHECK.copy(romIds = setOf(romId))) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(RommException.Unreachable::class.java)
    }

    /**
     * Found on the Thor: a save folder moved away by hand still read "in sync" until RomM could be
     * reached again. Without RomM, a look still says what changed on the Thor since the last sync.
     */
    @Test fun withoutRommALookStillSaysWhatChangedOnTheThor() = runBlocking<Unit> {
        install(pokemonX)
        val synced = writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)
        rommOutOfReach()

        val aside = File(tmp.root, "aside").apply { saveFolder(pokemonX).renameTo(this) } // moved away by hand
        checkWithoutRomm(7)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.LOCAL_MISSING)

        aside.renameTo(saveFolder(pokemonX)) // put back
        checkWithoutRomm(7)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)
        assertThat(saves.unit(7)!!.localHash).isEqualTo(synced)

        writeSave(pokemonX, bytes(2), minutesAgo = 5) // played since
        checkWithoutRomm(7)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.LOCAL_NEWER)
        assertThat(romm.uploads()).hasSize(1) // looking, and RomM out of reach anyway
    }

    /** What only RomM can settle stays as the last look with RomM left it: a conflict, a newer save there. */
    @Test fun withoutRommAConflictOrANewerSaveOnRommStays() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        writeSave(omegaRuby, bytes(2))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(3)))
        romm.addSave(8, archiveOf(bytes(4)))
        writeSave(pokemonX, bytes(5), minutesAgo = 5) // and played on the Thor too: a conflict
        engine().sync(SaveSyncRequest.PUSH)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.CONFLICT)
        assertThat(state(omegaRuby)).isEqualTo(SaveSyncState.SERVER_NEWER)
        rommOutOfReach()

        saveFolder(pokemonX).deleteRecursively()
        saveFolder(omegaRuby).deleteRecursively()
        val thrown = runCatching { engine().sync(SaveSyncRequest.CHECK) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RommException.Unreachable::class.java)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.CONFLICT)
        assertThat(state(omegaRuby)).isEqualTo(SaveSyncState.SERVER_NEWER)
    }

    /** A game never synced: without RomM, a look says whether the Thor has a save, RomM's side as last seen (none). */
    @Test fun withoutRommANeverSyncedGameSaysWhatTheThorHas() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.CHECK)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.LOCAL_NEWER)
        rommOutOfReach()

        saveFolder(pokemonX).deleteRecursively()
        checkWithoutRomm(7)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.NO_SAVE)

        install(omegaRuby) // never looked at with RomM
        writeSave(omegaRuby, bytes(2))
        checkWithoutRomm(8)
        assertThat(state(omegaRuby)).isEqualTo(SaveSyncState.LOCAL_NEWER)
    }

    /** RomM stopping half way through a run: the games it answered for keep that answer. */
    @Test fun gamesRommAnsweredForKeepTheAnswerWhenItStopsHalfWay() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        writeSave(omegaRuby, bytes(2))
        var negotiated = 0
        romm.onRequest = { request ->
            if (request.url.encodedPath == "/api/sync/negotiate" && ++negotiated == 2) {
                romm.negotiateAnswer = { MockResponse.Builder().code(400).setHeader("Content-Type", "application/json").body("""{"detail":"Busy"}""").build() }
            }
        }

        val report = engine(chunkSize = 1).sync(SaveSyncRequest.PUSH)

        assertThat(report.unavailable).isEqualTo("RomM won't sync saves with this device: Busy")
        assertThat(report.uploaded).containsExactly(7)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)
        assertThat(state(omegaRuby)).isEqualTo(SaveSyncState.LOCAL_NEWER) // the Thor's side, as found
    }

    /** Without a RomM device for Loom, a look still says what changed on the Thor. */
    @Test fun withoutARommDeviceALookStillSaysWhatChangedOnTheThor() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        pair(deviceId = null)
        saveFolder(pokemonX).deleteRecursively()

        assertThat(engine().sync(SaveSyncRequest.CHECK).unavailable).isEqualTo(SaveSyncEngine.NO_DEVICE)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.LOCAL_MISSING)
    }

    // --- runs ---

    /** Negotiate cancels the device's other sessions: two runs must never be open at once. */
    @Test fun runsNeverOverlap() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        romm.negotiateDelayMs = 300
        val engine = engine()

        coroutineScope {
            listOf(SaveSyncRequest.PUSH, SaveSyncRequest.PULL, SaveSyncRequest.PUSH).map { async(Dispatchers.IO) { engine.sync(it) } }.awaitAll()
        }

        assertThat(negotiations()).hasSize(3)
        assertThat(romm.mostOpenSessions).isEqualTo(1)
    }

    @Test fun negotiateIsChunkedAndEachSessionEndsBeforeTheNext() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        writeSave(omegaRuby, bytes(2))

        engine(chunkSize = 1).sync(SaveSyncRequest.PUSH)

        assertThat(negotiations().map { n -> n["rom_ids"]!!.jsonArray.map { it.jsonPrimitive.int } }).containsExactly(listOf(7), listOf(8)).inOrder()
        assertThat(romm.paths().filter { "negotiate" in it || "complete" in it }).containsExactly(
            "POST /api/sync/negotiate", "POST /api/sync/sessions/40/complete",
            "POST /api/sync/negotiate", "POST /api/sync/sessions/41/complete",
        ).inOrder()
        assertThat(romm.uploads()).hasSize(2)
    }

    /** A save Loom can't read is never taken for "no save": that would let a download through. */
    @Test fun aSaveLoomCantReadSitsTheRunOut() = runBlocking<Unit> {
        install(pokemonX)
        install(omegaRuby)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        romm.addSave(7, archiveOf(bytes(2)))
        val locked = File(saveFolder(pokemonX), "00000001").toPath()
        val before = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"))
        try {
            saves.upsertUnit(saves.unit(7)!!.copy(fingerprint = null)) // no cached hash to fall back on
            val report = engine().sync(SaveSyncRequest.PULL)

            assertThat(report.failed.keys).containsExactly(7)
            assertThat(negotiations().last()["rom_ids"]!!.jsonArray.map { it.jsonPrimitive.int }).containsExactly(8)
            assertThat(contentRequests()).isEmpty()
            assertThat(state(pokemonX)).isEqualTo(SaveSyncState.ERROR)
        } finally {
            Files.setPosixFilePermissions(locked, before)
        }
        assertThat(localMain(pokemonX)).isEqualTo(bytes(1))
    }

    @Test fun gamesLoomCantPlaceAreLeftOut() = runBlocking<Unit> {
        install(pokemonX, cartridgeId = null, file = "Pokemon X.cia")
        install(omegaRuby, rommSaveTarget = "00040000/00055d00") // RomM and the cartridge disagree

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.unsupported.keys).containsExactly(7, 8)
        assertThat(report.unsupported.getValue(8)).contains("0004000000055D00")
        assertThat(negotiations()).isEmpty()
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.UNSUPPORTED)
    }

    /** RomM's title id names the save when Loom can't read one from the file (a CIA). */
    @Test fun rommsTitleIdIsUsedWhenTheFileHasNone() = runBlocking<Unit> {
        install(pokemonX, cartridgeId = null, file = "Pokemon X.cia", rommTitleId = "0004000000055D00")
        writeSave(pokemonX, bytes(1))

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(7)
    }

    @Test fun azaharsFolderCanBeSetInSettings() = runBlocking<Unit> {
        install(pokemonX)
        val elsewhere = File(volume, "Azahar")
        File(elsewhere, "sdmc/Nintendo 3DS/$ZEROS/$ZEROS/title/00040000/00055d00/data/00000001").mkdirs()
        File(elsewhere, "sdmc/Nintendo 3DS/$ZEROS/$ZEROS/title/00040000/00055d00/data/00000001/main").writeBytes(bytes(5))
        File(elsewhere, "sdmc").walkTopDown().forEach { it.setLastModified(clock.minus(Duration.ofMinutes(10)).toEpochMilli()) }
        settings.setAzaharUserDir(elsewhere.path)

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(7)
        assertThat(saves.unit(7)!!.unitPath).startsWith(elsewhere.path)
    }

    /** Azahar's data in [folder], holding Pokémon X's save as [copyOf] if given, else this `main`; last written long ago. */
    private fun azaharIn(folder: File, main: ByteArray? = null, copyOf: File? = null) {
        val data = File(folder, "sdmc/Nintendo 3DS/$ZEROS/$ZEROS/title/00040000/00055d00/data")
        if (copyOf != null) copyOf.copyRecursively(data) else {
            File(data, "00000001").mkdirs()
            File(data, "00000001/main").writeBytes(main!!)
        }
        File(folder, "sdmc").walkTopDown().forEach { it.setLastModified(clock.minus(Duration.ofDays(300)).toEpochMilli()) }
    }

    /**
     * The baseline belongs to the folder the save was synced from. Azahar's folder set to another
     * one holding an old save of the same game (or the game reinstalled on another card with its
     * own Azahar data) isn't "changed on the Thor": Loom asks rather than uploading it over the
     * synced save.
     */
    @Test fun aSaveInAnotherAzaharFolderIsAConflictNotAnUpload() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(7)
        val head = romm.head(7)!!.id
        val elsewhere = File(volume, "Azahar").also { azaharIn(it, main = bytes(5)) }
        settings.setAzaharUserDir(elsewhere.path)

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.uploaded).isEmpty()
        assertThat(report.conflicts).containsExactly(7)
        assertThat(saves.conflict(7)!!.reason).isEqualTo(SyncDecision.FIRST_SYNC_DIFFERS.name)
        assertThat(romm.head(7)!!.id).isEqualTo(head)
    }

    /** The same save in the other folder is simply recorded as in sync there. */
    @Test fun theSameSaveInAnotherAzaharFolderIsAdopted() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        engine().sync(SaveSyncRequest.PUSH)
        val elsewhere = File(volume, "Azahar").also { azaharIn(it, copyOf = saveFolder(pokemonX)) }
        settings.setAzaharUserDir(elsewhere.path)

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.adopted).containsExactly(7)
        assertThat(romm.uploads()).hasSize(1)
        assertThat(saves.baseline(7, "autosave")!!.unitPath).startsWith(elsewhere.path)
        assertThat(state(pokemonX)).isEqualTo(SaveSyncState.IN_SYNC)
    }

    // --- the device ---

    @Test fun withoutARommDeviceSavesWaitForPairing() = runBlocking<Unit> {
        pair(deviceId = null)
        install(pokemonX)
        writeSave(pokemonX, bytes(1))

        val report = engine().sync(SaveSyncRequest.PUSH)

        assertThat(report.unavailable).isEqualTo(SaveSyncEngine.NO_DEVICE)
        assertThat(report.needsPairing).isTrue()
        assertThat(negotiations()).isEmpty()
    }

    /** What only pairing again fixes says so, for Settings to offer it: no RomM device for Loom (any more), or signed out. */
    @Test fun aRunThatOnlyPairingFixesSaysSo() = runBlocking<Unit> {
        install(pokemonX)
        writeSave(pokemonX, bytes(1))
        fun answer(code: Int, detail: String) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json")
            .body("""{"detail":"$detail"}""").build()

        romm.negotiateAnswer = { answer(404, "Device not found") } // deleted in RomM's web UI
        engine().sync(SaveSyncRequest.PUSH).let {
            assertThat(it.unavailable).isEqualTo(SaveSyncEngine.NO_DEVICE)
            assertThat(it.needsPairing).isTrue()
        }
        romm.negotiateAnswer = { answer(400, "Invalid slot") }
        engine().sync(SaveSyncRequest.PUSH).let {
            assertThat(it.unavailable).isEqualTo("RomM won't sync saves with this device: Invalid slot")
            assertThat(it.needsPairing).isFalse()
        }
        romm.negotiateAnswer = null
        credentials.clear() // signed out in Settings
        engine().sync(SaveSyncRequest.PUSH).let {
            assertThat(it.unavailable).isEqualTo(SaveSyncEngine.NO_DEVICE)
            assertThat(it.needsPairing).isTrue()
        }
        assertThat(romm.uploads()).isEmpty()
    }

    /** A pasted token isn't bound to a device; the device Loom paired as is found by its identifier. */
    @Test fun aTokenWithoutADeviceUsesTheDeviceLoomPairedAs() = runBlocking<Unit> {
        pair(deviceId = null)
        romm.devices += romm.device("dev-deck", "argosy-1")
        romm.devices += romm.device("dev-thor-2", settings.deviceIdentifier())
        install(pokemonX)
        writeSave(pokemonX, bytes(1))

        assertThat(engine().sync(SaveSyncRequest.PUSH).uploaded).containsExactly(7)
        assertThat(romm.uploads().single().url.queryParameter("device_id")).isEqualTo("dev-thor-2")
    }
}
