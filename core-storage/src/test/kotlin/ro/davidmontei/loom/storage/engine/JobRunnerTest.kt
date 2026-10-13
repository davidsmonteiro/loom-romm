package ro.davidmontei.loom.storage.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.engine.TestBytes.crc
import ro.davidmontei.loom.storage.engine.TestBytes.random
import ro.davidmontei.loom.storage.engine.TestBytes.sha1
import ro.davidmontei.loom.storage.engine.TestBytes.zip
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMapper
import ro.davidmontei.loom.storage.plan.ArchiveMember
import ro.davidmontei.loom.storage.plan.DownloadPlan
import ro.davidmontei.loom.storage.plan.DownloadPlanner
import ro.davidmontei.loom.storage.plan.RomFileSnapshot
import ro.davidmontei.loom.storage.plan.RomSnapshot
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end engine tests: a fake RomM file server (MockWebServer honouring Range), a temp
 * "SD card" with a ROMs root and staging area, crash injection at every phase, and the invariant
 * that the ROMs root never shows Cocoon a partial or hidden file.
 */
class JobRunnerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val files = FakeRommFiles()
    private val server = MockWebServer().apply { dispatcher = files }
    private val store = InMemoryJobStore()
    private val mapper = PlatformMapper(MappingJson.shippedDefaults())
    private lateinit var romsRoot: Path
    private lateinit var staging: Path

    private var freeBytes = Long.MAX_VALUE
    private var mounted = true
    private var crashAt: CrashPoint? = null
    private var exdev = false
    private val crashes = CrashPoints { point -> if (point == crashAt) { crashAt = null; throw SimulatedCrash(point) } }

    /** Behaves like an SD card whose staging area is on another mount when [exdev] is set. */
    private val fileOps = object : NioFileOps() {
        override fun atomicMove(source: Path, target: Path) {
            if (exdev && source.startsWith(staging) && target.startsWith(romsRoot)) {
                throw CrossDeviceException(java.io.IOException("EXDEV"))
            }
            super.atomicMove(source, target)
        }
    }

    @Before fun setUp() {
        server.start()
        val volume = tmp.newFolder("9C33-6BBD").toPath()
        romsRoot = Files.createDirectories(volume.resolve("ROMs"))
        staging = volume.resolve(".loom-staging")
    }

    @After fun tearDown() = server.close()

    private fun runner() = JobRunner(
        store = store,
        downloader = RangeDownloader(OkHttpClient(), syncEveryBytes = 16 * 1024, crash = crashes),
        baseUrl = { server.url("/") },
        space = { freeBytes },
        files = fileOps,
        archives = Archives(crashes),
        crash = crashes,
        isMounted = { mounted },
        maxTransientRetries = 2,
        backoffMs = { 0 },
    )

    // --- fixtures ---

    private fun file(id: Int, name: String, data: ByteArray, dir: String, sha1: Boolean = true, members: List<ArchiveMember>? = null) =
        RomFileSnapshot(id, name, dir, data.size.toLong(), crc = null, md5 = null,
            sha1 = if (sha1 && members == null) sha1(data) else if (members != null) "content-hash-not-of-raw-bytes" else null,
            category = null, archiveMembers = members).also { files.files[id] = data }

    private fun singleFile(romId: Int, slug: String, fsSlug: String, name: String, f: RomFileSnapshot): DownloadPlan =
        DownloadPlanner.plan(
            RomSnapshot(romId, name, name.substringBeforeLast('.'), "roms/$fsSlug/$name", false, false, "t", listOf(f)),
            mapper.resolve(slug, fsSlug),
        )

    private fun enqueue(plan: DownloadPlan, romId: Int = plan.romId): Long = store.add(
        { id -> JobRecord(id, romId, 1, "Game $romId", JobStatus.Queued, null, 0, 1, 0, plan, "9C33-6BBD", staging.toString(), romsRoot.toString()) },
        { id -> plan.toParts(id) },
    )

    private suspend fun runOnce(jobId: Long): RunOutcome {
        val r = runner()
        val lease = store.claim(jobId, r.resumePhase(store.load(jobId)!!))
        return r.run(jobId, lease)
    }

    /** Like the real queue: after a crash, the job is recovered to QUEUED and claimed again. */
    private suspend fun runSurvivingCrashes(jobId: Long): RunOutcome {
        repeat(5) {
            try {
                return runOnce(jobId)
            } catch (e: SimulatedCrash) {
                assertRomsRootClean(romsRoot) // the moment of death: nothing partial is visible
                store.recoverStale(jobId)
            }
        }
        error("did not settle")
    }

    private fun installed(o: RunOutcome) = (o as RunOutcome.Installed).install
    private fun failure(o: RunOutcome) = (o as RunOutcome.Parked).status.failure!!

    // --- happy paths: the three shapes from the acceptance criteria ---

    @Test fun singleFileGameLandsInItsSystemFolder() = runBlocking {
        val data = random(300_000, 1)
        val plan = singleFile(10, "snes", "snes", "Chrono Trigger (USA).sfc", file(100, "Chrono Trigger (USA).sfc", data, "roms/snes"))
        val install = installed(runOnce(enqueue(plan)))

        assertThat(install.relPath).isEqualTo("snes/Chrono Trigger (USA).sfc")
        assertThat(install.verifiedBy).isEqualTo("SHA1")
        assertThat(install.bytes).isEqualTo(300_000)
        assertThat(Files.readAllBytes(romsRoot.resolve("snes/Chrono Trigger (USA).sfc"))).isEqualTo(data)
        assertRomsRootClean(romsRoot, setOf("snes/Chrono Trigger (USA).sfc"))
        assertThat(Files.list(staging.resolve("jobs")).use { it.count() }).isEqualTo(0) // staging cleaned up
        assertThat(files.requests.single().headers["Accept-Encoding"]).isEqualTo("identity")
    }

    @Test fun multiDiscGameBecomesOneM3uFolder() = runBlocking {
        val bin1 = random(50_000, 2); val bin2 = random(60_000, 3)
        val cue1 = "FILE \"FF7 (Disc 1).bin\" BINARY\n  TRACK 01 MODE2/2352\n".toByteArray()
        val cue2 = "FILE \"FF7 (Disc 2).bin\" BINARY\n  TRACK 01 MODE2/2352\n".toByteArray()
        val dir = "roms/psx/Final Fantasy VII (USA)"
        val rom = RomSnapshot(20, "Final Fantasy VII (USA)", "Final Fantasy VII (USA)", dir, true, false, "t", listOf(
            file(201, "FF7 (Disc 2).cue", cue2, dir), file(202, "FF7 (Disc 2).bin", bin2, dir),
            file(203, "FF7 (Disc 1).cue", cue1, dir), file(204, "FF7 (Disc 1).bin", bin1, dir),
        ))
        val install = installed(runOnce(enqueue(DownloadPlanner.plan(rom, mapper.resolve("psx", "psx")))))

        assertThat(install.relPath).isEqualTo("psx/Final Fantasy VII (USA).m3u")
        val entry = romsRoot.resolve("psx/Final Fantasy VII (USA).m3u")
        assertThat(Files.isDirectory(entry)).isTrue()
        assertThat(String(Files.readAllBytes(entry.resolve("Final Fantasy VII (USA).m3u"))))
            .isEqualTo("FF7 (Disc 1).cue\nFF7 (Disc 2).cue\n")
        assertThat(Files.readAllBytes(entry.resolve("FF7 (Disc 2).bin"))).isEqualTo(bin2)
        assertThat(Files.list(entry).use { it.count() }).isEqualTo(5)
        assertRomsRootClean(romsRoot, setOf("psx/Final Fantasy VII (USA).m3u"))
    }

    @Test fun archiveIsExtractedAndCheckedAgainstRommMembers() = runBlocking {
        val nds = random(80_000, 4)
        // Named differently inside the archive: the install takes the game's name.
        val archive = zip("mkds.nds" to nds, "__MACOSX/._junk" to ByteArray(10))
        val members = listOf(ArchiveMember("mkds.nds", nds.size.toLong(), crc(nds), null, null))
        val plan = singleFile(30, "nds", "nds", "Mario Kart DS (USA).zip", file(300, "Mario Kart DS (USA).zip", archive, "roms/nds", members = members))
        assertThat(plan.parts.single().extract).isTrue()

        val install = installed(runOnce(enqueue(plan)))
        assertThat(install.relPath).isEqualTo("nds/Mario Kart DS (USA).nds")
        assertThat(install.verifiedBy).isEqualTo("ARCHIVE_MEMBERS")
        assertThat(Files.readAllBytes(romsRoot.resolve("nds/Mario Kart DS (USA).nds"))).isEqualTo(nds)
        assertRomsRootClean(romsRoot, setOf("nds/Mario Kart DS (USA).nds"))
    }

    @Test fun arcadeArchiveIsKeptZippedButStillVerified() = runBlocking {
        val rom = random(20_000, 5)
        val archive = zip("sf2.bin" to rom)
        val members = listOf(ArchiveMember("sf2.bin", rom.size.toLong(), crc(rom), null, null))
        val plan = singleFile(31, "arcade", "mame", "sf2.zip", file(310, "sf2.zip", archive, "roms/mame", members = members))
        val install = installed(runOnce(enqueue(plan)))
        assertThat(install.relPath).isEqualTo("mame/sf2.zip")
        assertThat(install.verifiedBy).isEqualTo("ARCHIVE_MEMBERS")
        assertThat(Files.readAllBytes(romsRoot.resolve("mame/sf2.zip"))).isEqualTo(archive)
    }

    // --- interruptions: resume, crashes at every phase ---

    @Test fun droppedConnectionResumesWithRange() = runBlocking {
        val data = random(200_000, 6)
        files.dropAfter[400] = 100_000
        val plan = singleFile(40, "gba", "gba", "Game.gba", file(400, "Game.gba", data, "roms/gba"))
        installed(runOnce(enqueue(plan)))
        val ranges = files.rangeHeaders(400)
        assertThat(ranges).hasSize(2)
        assertThat(ranges[0]).isNull()
        val resumedAt = ranges[1]!!.removePrefix("bytes=").removeSuffix("-").toLong()
        assertThat(resumedAt).isGreaterThan(0)
        assertThat(resumedAt).isAtMost(100_000)
        assertThat(Files.readAllBytes(romsRoot.resolve("gba/Game.gba"))).isEqualTo(data)
    }

    @Test fun processDeathMidDownloadLeavesNothingVisibleAndResumes() = runBlocking {
        val data = random(150_000, 7)
        crashAt = CrashPoint.AFTER_PART_BYTES
        val id = enqueue(singleFile(50, "gba", "gba", "Game.gba", file(500, "Game.gba", data, "roms/gba")))
        val outcome = runSurvivingCrashes(id)
        assertThat(installed(outcome).relPath).isEqualTo("gba/Game.gba")
        assertThat(files.rangeHeaders(500).last()).startsWith("bytes=")
        assertThat(Files.readAllBytes(romsRoot.resolve("gba/Game.gba"))).isEqualTo(data)
    }

    @Test fun deathJustBeforeThePromoteRenameIsRecovered() = runBlocking {
        crashAt = CrashPoint.BEFORE_PROMOTE_RENAME
        val id = enqueue(singleFile(60, "gb", "gb", "Tetris.gb", file(600, "Tetris.gb", random(1000, 8), "roms/gb")))
        assertThat(installed(runSurvivingCrashes(id)).relPath).isEqualTo("gb/Tetris.gb")
        assertThat(files.requests).hasSize(1) // nothing downloaded twice
        assertRomsRootClean(romsRoot, setOf("gb/Tetris.gb"))
    }

    @Test fun deathJustAfterThePromoteRenameIsRecovered() = runBlocking {
        crashAt = CrashPoint.AFTER_PROMOTE_RENAME
        val id = enqueue(singleFile(61, "gb", "gb", "Tetris.gb", file(610, "Tetris.gb", random(1000, 9), "roms/gb")))
        assertThat(installed(runSurvivingCrashes(id)).relPath).isEqualTo("gb/Tetris.gb")
        assertRomsRootClean(romsRoot, setOf("gb/Tetris.gb"))
    }

    @Test fun deathMidAssemblyReplaysTheManifest() = runBlocking {
        crashAt = CrashPoint.MID_ASSEMBLY
        val dir = "roms/psx/Riven"
        val rom = RomSnapshot(70, "Riven", "Riven", dir, true, false, "t", listOf(
            file(701, "Riven (Disc 1).chd", random(5000, 10), dir), file(702, "Riven (Disc 2).chd", random(5000, 11), dir),
        ))
        val outcome = runSurvivingCrashes(enqueue(DownloadPlanner.plan(rom, mapper.resolve("psx", "psx"))))
        assertThat(installed(outcome).relPath).isEqualTo("psx/Riven.m3u")
        assertThat(Files.list(romsRoot.resolve("psx/Riven.m3u")).use { it.count() }).isEqualTo(3)
    }

    @Test fun deathMidExtractionStartsTheExtractionOver() = runBlocking {
        crashAt = CrashPoint.MID_EXTRACT
        val a = random(3000, 12); val b = random(4000, 13)
        val archive = zip("Game/Game (Disc 1).chd" to a, "Game/Game (Disc 2).chd" to b)
        val members = listOf(ArchiveMember("Game/Game (Disc 1).chd", 3000, crc(a), null, null),
            ArchiveMember("Game/Game (Disc 2).chd", 4000, crc(b), null, null))
        val plan = singleFile(80, "psx", "psx", "Game.zip", file(800, "Game.zip", archive, "roms/psx", members = members))
        val install = installed(runSurvivingCrashes(enqueue(plan)))
        assertThat(install.relPath).isEqualTo("psx/Game.m3u")
        assertThat(String(Files.readAllBytes(romsRoot.resolve("psx/Game.m3u/Game.m3u")))).isEqualTo("Game (Disc 1).chd\nGame (Disc 2).chd\n")
    }

    @Test fun pausingMidDownloadKeepsProgressAndResumes() = runBlocking {
        val data = random(400_000, 14)
        files.throttleBytesPer10Ms = 8_000
        val id = enqueue(singleFile(90, "gba", "gba", "Slow.gba", file(900, "Slow.gba", data, "roms/gba")))
        val r = runner()
        val lease = store.claim(id, r.resumePhase(store.load(id)!!))
        val running = async { r.run(id, lease) }
        while (store.parts(id).single().durableBytes == 0L) delay(5)
        store.apply(id, JobEvent.Pause) // what the UI does: state change + lease cleared…
        running.cancelAndJoin()         // …and the queue cancels the runner
        val kept = store.parts(id).single().durableBytes
        assertThat(kept).isGreaterThan(0)
        assertRomsRootClean(romsRoot, emptySet())

        files.throttleBytesPer10Ms = null
        store.apply(id, JobEvent.Resume)
        installed(runOnce(id))
        assertThat(files.rangeHeaders(900).last()).isEqualTo("bytes=$kept-")
        assertThat(Files.readAllBytes(romsRoot.resolve("gba/Slow.gba"))).isEqualTo(data)
    }

    // --- verification failures ---

    @Test fun oneCorruptDownloadIsRetriedTransparently() = runBlocking {
        files.corruptTimes[1000] = 1
        val data = random(10_000, 15)
        val out = runOnce(enqueue(singleFile(100, "nes", "nes", "Zelda.nes", file(1000, "Zelda.nes", data, "roms/nes"))))
        assertThat(installed(out).verifiedBy).isEqualTo("SHA1")
        assertThat(files.rangeHeaders(1000)).hasSize(2)
        assertThat(Files.readAllBytes(romsRoot.resolve("nes/Zelda.nes"))).isEqualTo(data)
    }

    @Test fun repeatedCorruptionFailsWithoutInstalling() = runBlocking {
        files.corruptTimes[1100] = 2
        val out = runOnce(enqueue(singleFile(110, "nes", "nes", "Zelda.nes", file(1100, "Zelda.nes", random(10_000, 16), "roms/nes"))))
        assertThat(failure(out).code).isEqualTo(FailureCode.CHECKSUM)
        assertRomsRootClean(romsRoot, emptySet())
    }

    @Test fun serverIgnoringRangeRestartsFromZero() = runBlocking {
        files.ignoreRange = true
        files.dropAfter[1200] = 30_000
        val data = random(90_000, 17)
        installed(runOnce(enqueue(singleFile(120, "gba", "gba", "G.gba", file(1200, "G.gba", data, "roms/gba")))))
        assertThat(Files.readAllBytes(romsRoot.resolve("gba/G.gba"))).isEqualTo(data)
    }

    // --- errors the user has to see ---

    @Test fun missingFileOnServer() = runBlocking {
        val plan = singleFile(130, "nes", "nes", "Gone.nes", file(1300, "Gone.nes", random(10, 18), "roms/nes"))
        files.files.remove(1300)
        assertThat(failure(runOnce(enqueue(plan))).code).isEqualTo(FailureCode.SERVER_FILE_MISSING)
    }

    @Test fun rejectedToken() = runBlocking {
        files.forcedStatus = 401
        val plan = singleFile(131, "nes", "nes", "A.nes", file(1310, "A.nes", random(10, 19), "roms/nes"))
        assertThat(failure(runOnce(enqueue(plan))).code).isEqualTo(FailureCode.AUTH)
    }

    @Test fun loginPageInsteadOfTheGame() = runBlocking {
        files.contentType = "text/html; charset=utf-8"
        val plan = singleFile(132, "nes", "nes", "A.nes", file(1320, "A.nes", random(10, 20), "roms/nes"))
        assertThat(failure(runOnce(enqueue(plan))).code).isEqualTo(FailureCode.PROXY_PAGE)
        assertRomsRootClean(romsRoot, emptySet())
    }

    @Test fun compressedResponseIsRefused() = runBlocking {
        files.contentEncoding = "gzip"
        val plan = singleFile(133, "nes", "nes", "A.nes", file(1330, "A.nes", random(10, 21), "roms/nes"))
        assertThat(failure(runOnce(enqueue(plan))).code).isEqualTo(FailureCode.PROXY_PAGE)
    }

    @Test fun notEnoughSpaceFailsBeforeDownloading() = runBlocking {
        freeBytes = 100L * 1024 * 1024
        val plan = singleFile(140, "ps2", "ps2", "Big.iso", file(1400, "Big.iso", random(1000, 22), "roms/ps2"))
            .let { it.copy(parts = it.parts.map { p -> p.copy(size = 4L * 1024 * 1024 * 1024) }) }
        val f = failure(runOnce(enqueue(plan)))
        assertThat(f.code).isEqualTo(FailureCode.INSUFFICIENT_SPACE)
        assertThat(f.detail).contains("GB")
        assertThat(files.requests).isEmpty()
    }

    @Test fun unmountedCardParksTheJob() = runBlocking {
        files.dropAfter[1500] = 10
        mounted = false
        val plan = singleFile(150, "gba", "gba", "A.gba", file(1500, "A.gba", random(50_000, 23), "roms/gba"))
        val out = runOnce(enqueue(plan)) as RunOutcome.Parked
        assertThat(out.status.phase).isEqualTo(JobPhase.WAITING)
        assertThat(out.status.waitReason).isEqualTo(WaitReason.STORAGE)
    }

    // --- the destination ---

    @Test fun existingUserFileIsNeverOverwritten() = runBlocking {
        val mine = romsRoot.resolve("snes/Mine.sfc")
        Files.createDirectories(mine.parent); Files.write(mine, byteArrayOf(1, 2, 3))
        val plan = singleFile(160, "snes", "snes", "Mine.sfc", file(1600, "Mine.sfc", random(5000, 24), "roms/snes"))
        assertThat(failure(runOnce(enqueue(plan))).code).isEqualTo(FailureCode.DEST_EXISTS)
        assertThat(Files.readAllBytes(mine)).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun twoRomsWithTheSameNameBothInstall() = runBlocking {
        installed(runOnce(enqueue(singleFile(170, "snes", "snes", "Game.sfc", file(1700, "Game.sfc", random(100, 25), "roms/snes")))))
        val second = installed(runOnce(enqueue(singleFile(171, "snes", "snes", "Game.sfc", file(1710, "Game.sfc", random(200, 26), "roms/snes/hacks")))))
        assertThat(second.relPath).isEqualTo("snes/Game (romm-171).sfc")
        assertRomsRootClean(romsRoot, setOf("snes/Game.sfc", "snes/Game (romm-171).sfc"))
    }

    @Test fun crossMountRenameFallsBackToHiddenCopy() = runBlocking {
        exdev = true
        val data = random(10_000, 27)
        val dir = "roms/psx/G"
        val rom = RomSnapshot(180, "G", "G", dir, true, false, "t", listOf(
            file(1801, "G (Disc 1).chd", data, dir), file(1802, "G (Disc 2).chd", data, dir),
        ))
        // Assembly happens inside staging (same mount); only the promote crosses mounts.
        val out = installed(runOnce(enqueue(DownloadPlanner.plan(rom, mapper.resolve("psx", "psx")))))
        assertThat(out.relPath).isEqualTo("psx/G.m3u")
        assertRomsRootClean(romsRoot, setOf("psx/G.m3u"))
        assertThat(Files.list(romsRoot.resolve("psx/G.m3u")).use { it.count() }).isEqualTo(3)
    }

    @Test fun releasedLeaseStopsTheRunner() = runBlocking {
        val id = enqueue(singleFile(190, "nes", "nes", "A.nes", file(1900, "A.nes", random(10, 28), "roms/nes")))
        val lease = store.claim(id, JobPhase.DOWNLOADING)
        store.apply(id, JobEvent.Pause)
        assertThat(runner().run(id, lease)).isEqualTo(RunOutcome.Released)
        assertThat(files.requests).isEmpty()
    }

    @Test fun simulatedCrashIsNotSwallowed() {
        crashAt = CrashPoint.AFTER_PART_BYTES
        val id = enqueue(singleFile(195, "gba", "gba", "A.gba", file(1950, "A.gba", random(100_000, 29), "roms/gba")))
        assertThrows(SimulatedCrash::class.java) { runBlocking { runOnce(id) } }
    }
}
