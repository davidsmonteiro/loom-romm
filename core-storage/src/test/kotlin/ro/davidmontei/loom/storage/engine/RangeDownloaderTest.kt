package ro.davidmontei.loom.storage.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.BufferedSink
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
import java.util.concurrent.atomic.AtomicLong

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

    /** Holds a stalled response back; let go at the end, so the fake server can stop. */
    private val stalled = CountDownLatch(1)

    @After fun tearDown() {
        stalled.countDown()
        server.close()
    }

    /** Waits until [ready], as a slow card holding a read back would; gives up after 5 s. */
    private fun waitFor(ready: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready()) {
            check(System.nanoTime() < until) { "waited 5 s" }
            Thread.sleep(2)
        }
    }

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

    /**
     * Found by review: what came in while the prefix was hashed was read back in one go, with the
     * connection left unread meanwhile: about 12 s for a download paused at 3 GB, and past what RomM's
     * proxy waits for (a minute) at larger ones. It is caught up on a little for each read instead.
     */
    @Test fun catchingUpKeepsReadingTheConnection() {
        val big = random(6_000_000, 22)
        files.files[2] = big
        val staged = file.resolveSibling("2.part").also { Files.write(it, big.copyOf(have.toInt())) }
        val bigPart = PartRecord(
            jobId = 1, plan = PartPlan(2, "Big.iso", big.size.toLong(), PartCheck.Hash("sha1", sha1(big)), extract = false),
            relUrl = "api/roms/2/files/content/Big.iso", durableBytes = have, validator = "Wed, 01 Oct 2026 10:00:00 GMT",
            state = PartState.PARTIAL,
        )
        files.throttleBytesPer10Ms = 64_000
        val caughtUp = AtomicLong() // bytes read back after the prefix
        val readBack: (Path) -> InputStream = { path ->
            object : FilterInputStream(Files.newInputStream(path)) {
                var catchingUp = false // catching up skips the prefix; hashing the prefix doesn't

                override fun skip(n: Long): Long = super.skip(n).also { catchingUp = true }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    // The prefix is hashed only once 1.5 MB more has come: that is the catching up to do.
                    if (!catchingUp) waitFor { Files.size(staged) >= have + 1_500_000 }
                    return super.read(b, off, len).also { if (catchingUp && it > 0) caughtUp.addAndGet(it.toLong()) }
                }
            }
        }
        var seen = 0L
        var mostBetweenReads = 0L
        val result = runBlocking {
            RangeDownloader(OkHttpClient(), readBack = readBack).download(bigPart, server.url("/api/roms/2/files/content/Big.iso"), staged,
                onDurable = { _, _ -> },
                onProgress = { caughtUp.get().let { now -> mostBetweenReads = maxOf(mostBetweenReads, now - seen); seen = now } },
            )
        }
        assertThat(result.digest).isEqualTo(sha1(big))
        assertThat(caughtUp.get()).isAtLeast(1_500_000L)
        // At most twice what one read from the connection brings (256 KB) between two reads…
        assertThat(mostBetweenReads).isAtMost(RangeDownloader.CATCH_UP_PER_READ * 256 * 1024L)
        // …and once caught up, the rest is hashed as it comes, not read back again.
        assertThat(caughtUp.get()).isLessThan(big.size - have - 512 * 1024L)
    }

    /** Found by review: pausing a download whose connection had stalled waited for the next byte, or for the read to time out. */
    @Test fun stoppingADownloadOnAStalledConnectionIsPrompt() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(206)
                .setHeader("Content-Range", "bytes $have-${data.size - 1}/${data.size}")
                .body(object : MockResponseBody {
                    override val contentLength = data.size - have
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(data, have.toInt(), 64 * 1024)
                        sink.flush()
                        stalled.await(30, TimeUnit.SECONDS) // and then nothing more
                    }
                }).build()
        }
        val reading = CountDownLatch(1)
        runBlocking {
            val download = launch(Dispatchers.IO) {
                RangeDownloader(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build())
                    .download(part, server.url("/api/roms/1/files/content/Game.iso"), file, onDurable = { _, _ -> }, onProgress = { reading.countDown() })
            }
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue()
            withTimeout(3_000) { download.cancelAndJoin() }
        }
    }
}
