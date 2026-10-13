package ro.davidmontei.loom.data.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import ro.davidmontei.loom.data.db.DownloadJobEntity
import ro.davidmontei.loom.data.db.InstallSource
import ro.davidmontei.loom.data.db.LoomDatabase
import ro.davidmontei.loom.data.db.VerifiedBy
import ro.davidmontei.loom.storage.engine.JobPhase
import ro.davidmontei.loom.storage.engine.JobRunner
import ro.davidmontei.loom.storage.engine.JobStatus
import ro.davidmontei.loom.storage.engine.RangeDownloader
import ro.davidmontei.loom.storage.engine.RunOutcome
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMapper
import ro.davidmontei.loom.storage.plan.DownloadPlan
import ro.davidmontei.loom.storage.plan.DownloadPlanner
import ro.davidmontei.loom.storage.plan.NameSanitizer
import ro.davidmontei.loom.storage.plan.RomFileSnapshot
import ro.davidmontei.loom.storage.plan.RomSnapshot
import ro.davidmontei.loom.romm.RommUrls
import ro.davidmontei.loom.storage.engine.PartRecord
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RoomJobStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: LoomDatabase
    private lateinit var store: RoomJobStore
    private val server = MockWebServer()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LoomDatabase::class.java)
            .allowMainThreadQueries().build()
        store = RoomJobStore(db.downloadDao(), nowMs = { 42 })
        server.start()
    }

    @After fun tearDown() { db.close(); server.close() }

    private val bytes = ByteArray(70_000) { (it * 31).toByte() }
    private val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun plan(romId: Int = 7): DownloadPlan = DownloadPlanner.plan(
        RomSnapshot(romId, "Kirby.gba", "Kirby", "roms/gba/Kirby.gba", false, false, "t",
            listOf(RomFileSnapshot(70, "Kirby.gba", "roms/gba", bytes.size.toLong(), null, null, sha1, null, null))),
        PlatformMapper(MappingJson.shippedDefaults()).resolve("gba", "gba"),
    )

    private suspend fun insert(plan: DownloadPlan, romsRoot: File, staging: File): Long = db.downloadDao().insertJobWithParts(
        DownloadJobEntity(romId = plan.romId, platformId = 3, title = "Kirby", phase = JobPhase.QUEUED.name,
            planJson = EngineJson.encodeToString(DownloadPlan.serializer(), plan), fingerprint = plan.fingerprint,
            volumeUuid = "9C33-6BBD", stagingRoot = staging.path, romsRoot = romsRoot.path,
            bytesTotal = plan.totalBytes, queueOrder = 1, updatedAt = 1),
    ) { id -> plan.parts.map { PartRecord(id, it, RommUrls.romFileContentPath(it.romFileId)).toEntity() } }

    @Test fun planAndPartsRoundTripThroughRoom() = runBlocking<Unit> {
        val id = insert(plan(), tmp.newFolder("roms"), tmp.newFolder("staging"))
        val record = store.load(id)!!
        assertThat(record.plan).isEqualTo(plan())
        assertThat(record.status).isEqualTo(JobStatus.Queued)
        assertThat(store.parts(id).single().relUrl).isEqualTo("api/roms/70/files/content/file")
    }

    @Test fun transitionsAreCompareAndSetOnLeaseAndPhase() = runBlocking<Unit> {
        val dao = db.downloadDao()
        val id = insert(plan(), tmp.newFolder("roms"), tmp.newFolder("staging"))
        assertThat(dao.claim(id, "lease-1", JobPhase.DOWNLOADING.name, 1)).isEqualTo(1)
        assertThat(dao.claim(id, "lease-2", JobPhase.DOWNLOADING.name, 1)).isEqualTo(0) // already claimed

        assertThat(store.transition(id, "wrong", JobPhase.DOWNLOADING, JobStatus(JobPhase.VERIFYING))).isFalse()
        assertThat(store.transition(id, "lease-1", JobPhase.VERIFYING, JobStatus(JobPhase.PROMOTING))).isFalse()
        assertThat(store.transition(id, "lease-1", JobPhase.DOWNLOADING, JobStatus(JobPhase.VERIFYING))).isTrue()
        assertThat(store.load(id)!!.lease).isEqualTo("lease-1") // still active → lease kept

        // The UI pauses: lease dropped, the runner's next write fails.
        assertThat(dao.setPhase(id, JobPhase.VERIFYING.name, JobPhase.PAUSED.name, null, null, null, true, 2)).isEqualTo(1)
        assertThat(store.transition(id, "lease-1", JobPhase.VERIFYING, JobStatus(JobPhase.PROMOTING))).isFalse()
        assertThat(store.load(id)!!.lease).isNull()
    }

    @Test fun staleActiveJobsAreRequeued() = runBlocking<Unit> {
        val dao = db.downloadDao()
        val id = insert(plan(), tmp.newFolder("roms"), tmp.newFolder("staging"))
        dao.claim(id, "dead-worker", JobPhase.PROMOTING.name, 1)
        assertThat(dao.requeueStale(5)).isEqualTo(1)
        val job = store.load(id)!!
        assertThat(job.status.phase).isEqualTo(JobPhase.QUEUED)
        assertThat(job.lease).isNull()
    }

    @Test fun fullRunOverRoomInstallsAndDeletesTheJob() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/octet-stream").body(Buffer().write(bytes)).build())
        val romsRoot = tmp.newFolder("ROMs")
        val staging = tmp.newFolder(".loom-staging")
        val id = insert(plan(), romsRoot, staging)
        val runner = JobRunner(store, RangeDownloader(OkHttpClient()), { server.url("/") }, { Long.MAX_VALUE },
            isMounted = { true })
        val lease = "lease"
        db.downloadDao().claim(id, lease, runner.resumePhase(store.load(id)!!).name, 1)

        val outcome = runner.run(id, lease)

        assertThat(outcome).isInstanceOf(RunOutcome.Installed::class.java)
        assertThat(File(romsRoot, "gba/Kirby.gba").readBytes()).isEqualTo(bytes)
        assertThat(db.downloadDao().job(id)).isNull()
        assertThat(db.downloadDao().parts(id)).isEmpty()
        val install = db.catalogDao().install(7)!!
        assertThat(install.relPath).isEqualTo("gba/Kirby.gba")
        assertThat(install.pathKey).isEqualTo(NameSanitizer.key("gba/Kirby.gba"))
        assertThat(install.verifiedBy).isEqualTo(VerifiedBy.SHA1)
        assertThat(install.source).isEqualTo(InstallSource.LOOM)
        assertThat(install.bytes).isEqualTo(bytes.size.toLong())

        // A second ROM with the same file name is told the path is taken.
        assertThat(store.isPathClaimed("9C33-6BBD", NameSanitizer.key("gba/kirby.GBA"), exceptRomId = 8)).isTrue()
        assertThat(store.isPathClaimed("9C33-6BBD", NameSanitizer.key("gba/Kirby.gba"), exceptRomId = 7)).isFalse()
    }
}
