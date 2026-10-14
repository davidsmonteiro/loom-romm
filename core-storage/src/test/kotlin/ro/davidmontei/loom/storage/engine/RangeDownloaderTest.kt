package ro.davidmontei.loom.storage.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.engine.TestBytes.random
import ro.davidmontei.loom.storage.engine.TestBytes.sha1
import ro.davidmontei.loom.storage.plan.PartCheck
import ro.davidmontei.loom.storage.plan.PartPlan
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Resuming a part: what is already on the card is hashed alongside the request for the rest, not
 * before it. Found by review: resuming re-read the whole downloaded prefix first, at the card's
 * speed (87 MB/s measured on the Thor), so a download paused at a few hundred MB took seconds to
 * start again, and one paused at a few GB, half a minute.
 */
class RangeDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val files = FakeRommFiles()
    /** Opens once RomM has been asked for the rest of the file. */
    private val asked = CountDownLatch(1)
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                files.dispatch(request).also { if (request.headers["Range"] != null) asked.countDown() }
        }
    }
    private val data = random(3_000_000, 21)
    private val have = 1_000_000L
    private lateinit var file: Path

    @Before fun setUp() {
        server.start()
        files.files[1] = data
        file = tmp.newFolder("dl").toPath().resolve("1.part")
        Files.write(file, data.copyOf(have.toInt())) // a download paused a third of the way in
    }

    @After fun tearDown() = server.close()

    private val part = PartRecord(
        jobId = 1, plan = PartPlan(1, "Game.iso", data.size.toLong(), PartCheck.Hash("sha1", sha1(data)), extract = false),
        relUrl = "api/roms/1/files/content/Game.iso", durableBytes = have, validator = "Wed, 01 Oct 2026 10:00:00 GMT",
        state = PartState.PARTIAL,
    )

    /** Reads the staged file only once [ready] says so, as a slow card would; each wait gives up after 5 s. */
    private fun readBackWhen(ready: () -> Boolean): (Path) -> InputStream = { path ->
        object : FilterInputStream(Files.newInputStream(path)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!ready()) {
                    check(System.nanoTime() < until) { "the bytes already downloaded were read back before RomM was asked for the rest" }
                    Thread.sleep(2)
                }
                return super.read(b, off, len)
            }
        }
    }

    private fun download(readBack: (Path) -> InputStream) = runBlocking {
        RangeDownloader(OkHttpClient(), readBack = readBack).download(part, server.url("/api/roms/1/files/content/Game.iso"), file, { _, _ -> })
    }

    @Test fun aResumedPartAsksForTheRestBeforeReadingBackWhatItHas() {
        val result = download(readBackWhen { asked.count == 0L })
        assertThat(files.rangeHeaders(1)).containsExactly("bytes=$have-")
        assertThat(result.bytes).isEqualTo(data.size.toLong())
        assertThat(result.digest).isEqualTo(sha1(data)) // still the whole file's, in order
    }

    /** The prefix finishes hashing only after the rest has arrived: the rest is read back and hashed after it. */
    @Test fun bytesThatArriveBeforeThePrefixIsHashedAreHashedAfterIt() {
        val result = download(readBackWhen { Files.size(file) == data.size.toLong() })
        assertThat(result.digest).isEqualTo(sha1(data))
    }

    /** The prefix finishes hashing part way through the rest: what came meanwhile is caught up, then the rest streams in. */
    @Test fun bytesThatArriveWhileThePrefixIsHashedAreCaughtUp() {
        files.throttleBytesPer10Ms = 40_000 // about half a second for the rest
        val result = download(readBackWhen { Files.size(file) >= have + 300_000 })
        assertThat(result.digest).isEqualTo(sha1(data))
    }
}
