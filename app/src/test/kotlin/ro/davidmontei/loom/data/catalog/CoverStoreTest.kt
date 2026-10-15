package ro.davidmontei.loom.data.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Found on the Thor: RomM's "small" covers average ~100 KB, so 23,000 of them overflowed the old
 * 512 MB cache and every sync downloaded the evicted ones again. And then: they are 162 to 264 px
 * wide, narrower than the grid's cards (285 px, 297 focused), so covers were drawn enlarged and soft.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverStoreTest {
    @get:Rule val rule = MockWebServerRule()
    @get:Rule val tmp = TemporaryFolder()
    private val server get() = rule.server
    private val dir by lazy { tmp.newFolder("covers") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { CoverStore(dir, OkHttpClient(), scope) }
    private val small = "/assets/romm/resources/roms/18/8562/cover/small.png?ts=2026-07-30 13:55:01"
    private val large = "/assets/romm/resources/roms/18/8562/cover/big.png?ts=2026-07-30 13:55:01"

    /** The covers RomM serves, by path without the query; anything else is a 404. */
    private val covers = mutableMapOf<String, Pair<Int, Int>>()
    private val requested = CopyOnWriteArrayList<String>()

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(217, 164, 65)) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requested += path
                val (w, h) = covers[path] ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().setHeader("Content-Type", "image/png").body(Buffer().write(png(w, h))).build()
            }
        }
    }

    private fun cover(path: String, width: Int, height: Int) { covers[path.substringBefore('?')] = width to height; serve() }

    @After fun tearDown() = scope.cancel()

    private fun size(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(file.path, it) }.let { it.outWidth to it.outHeight }

    @Test fun coversAreMadeFromTheLargeArtAtTheSizeTheCardIsDrawn() = runBlocking<Unit> {
        cover(large, 600, 900) // SteamGridDB's 2:3, as RomM keeps it
        val file = store.get(server.url("/"), small, large)!!
        assertThat(size(file)).isEqualTo(CoverStore.WIDTH to CoverStore.HEIGHT) // shrunk to fill 297×396, then cropped to it
        assertThat(file.name).endsWith(".webp")
        assertThat(requested).containsExactly("/assets/romm/resources/roms/18/8562/cover/big.png")
    }

    @Test fun landscapeCoversAreCroppedToTheCard() = runBlocking<Unit> {
        cover(large, 800, 560)
        assertThat(size(store.get(server.url("/"), small, large)!!)).isEqualTo(CoverStore.WIDTH to CoverStore.HEIGHT)
    }

    @Test fun smallArtIsNotEnlarged() = runBlocking<Unit> {
        cover(large, 200, 280)
        assertThat(size(store.get(server.url("/"), small, large)!!)).isEqualTo(200 to 280)
    }

    @Test fun largeArtRommCantServeFallsBackToTheSmallCover() = runBlocking<Unit> {
        cover(small, 240, 360) // the large art is a 404
        assertThat(size(store.get(server.url("/"), small, large)!!)).isEqualTo(240 to 360)
        assertThat(requested).containsExactly("/assets/romm/resources/roms/18/8562/cover/big.png", "/assets/romm/resources/roms/18/8562/cover/small.png")
            .inOrder()
    }

    @Test fun aGameWithoutLargeArtGetsItsSmallCover() = runBlocking<Unit> {
        cover(small, 240, 360)
        assertThat(size(store.get(server.url("/"), small, large = null)!!)).isEqualTo(240 to 360)
        assertThat(requested).containsExactly("/assets/romm/resources/roms/18/8562/cover/small.png")
    }

    @Test fun aStoredCoverNeedsNoServerAndNoParticularAddress() = runBlocking<Unit> {
        cover(large, 600, 900)
        val first = store.get(server.url("/"), small, large)
        assertThat(store.get(base = null, small)).isEqualTo(first) // offline
        assertThat(store.get(server.url("/other/"), small, large)).isEqualTo(first) // another address for the same server
        assertThat(requested).hasSize(1)
    }

    @Test fun missingCoverIsNull() = runBlocking<Unit> {
        serve()
        assertThat(store.get(server.url("/"), small, large)).isNull()
        assertThat(store.get(base = null, "/never/fetched.png")).isNull()
    }

    /** A plain thumbnail, from the small cover, as the prefetch (or an earlier Loom, at 256 px) keeps it. */
    private fun plainThumbnail(path: String): File {
        val name = store.file(path).name.substringBefore('-') + ".webp"
        return File(dir, name).apply { writeBytes(png(240, 360)) }
    }

    /**
     * A cover that is drawn is made sharp in the background, from RomM's large art, while its plain
     * thumbnail is shown: the library never waits on RomM for a cover it has. The plain one goes once
     * the sharp one is stored.
     */
    @Test fun aDrawnCoverIsShownAtOnceAndSharpenedInTheBackground() = runBlocking<Unit> {
        cover(large, 600, 900)
        val plain = plainThumbnail(small)
        val sharpened = CountDownLatch(1)
        assertThat(store.forDrawing(CoverRef(small, server.url("/"), large)) { sharpened.countDown() }).isEqualTo(plain)
        assertThat(sharpened.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(size(store.file(small))).isEqualTo(CoverStore.WIDTH to CoverStore.HEIGHT)
        assertThat(plain.exists()).isFalse() // its space is given back
        assertThat(store.forDrawing(CoverRef(small, server.url("/"), large))).isEqualTo(store.file(small))
        assertThat(requested).containsExactly("/assets/romm/resources/roms/18/8562/cover/big.png")
    }

    /**
     * Scrolling through a long list of plain thumbnails sharpens them a couple at a time, with a few
     * dozen waiting at most: the rest wait to be drawn again.
     */
    @Test fun drawnCoversAreSharpenedACoupleAtATime() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.url.encodedPath
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                release.await(5, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
                return MockResponse.Builder().setHeader("Content-Type", "image/png").body(Buffer().write(png(600, 900))).build()
            }
        }
        val games = (1..40).map { smallOf(it) to largeOf(it) }
        games.forEach { plainThumbnail(it.first) }
        val sharpened = CountDownLatch(32)
        repeat(2) { games.forEach { (s, l) -> store.forDrawing(CoverRef(s, server.url("/"), l)) { sharpened.countDown() } } } // drawn twice: asked for once

        Thread.sleep(300)
        assertThat(requested).hasSize(2)
        release.countDown()
        assertThat(sharpened.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(most.get()).isEqualTo(2)
        assertThat(games.count { store.file(it.first).exists() }).isEqualTo(32)
        assertThat(requested).hasSize(32)
    }

    /** A cover drawn before any prefetch has made it is made sharp straight away: it's being looked at. */
    @Test fun aCoverWithNoThumbnailIsMadeSharpWhenDrawn() = runBlocking<Unit> {
        cover(small, 240, 360)
        cover(large, 600, 900)
        assertThat(size(store.forDrawing(CoverRef(small, server.url("/"), large))!!)).isEqualTo(CoverStore.WIDTH to CoverStore.HEIGHT)
        assertThat(requested).containsExactly("/assets/romm/resources/roms/18/8562/cover/big.png")
    }

    @Test fun offlineAPlainThumbnailIsDrawnAsItIs() = runBlocking<Unit> {
        val plain = plainThumbnail(small)
        assertThat(store.forDrawing(CoverRef(small, base = null, large))).isEqualTo(plain)
        assertThat(plain.exists()).isTrue()
    }

    private fun smallOf(id: Int) = "/assets/romm/resources/roms/18/$id/cover/small.png?ts=1"
    private fun largeOf(id: Int) = "/assets/romm/resources/roms/18/$id/cover/big.png?ts=1"

    /**
     * The prefetch keeps offline browsing complete cheaply: a cover with no thumbnail gets a plain
     * one, from RomM's small cover (about 0.1 MB to fetch, not the large art's 0.5 MB). Covers already
     * kept are left alone: only covers that are drawn are made sharp.
     */
    @Test fun thePrefetchMakesPlainThumbnailsOfMissingCoversOnly() = runBlocking<Unit> {
        val missing = 1
        val kept = (2..5).toList()
        (listOf(missing) + kept).forEach { cover(smallOf(it), 240, 360); cover(largeOf(it), 600, 900) }
        val plain = kept.map { plainThumbnail(smallOf(it)) }

        store.prefetch(server.url("/"), (listOf(missing) + kept).map(::smallOf))

        assertThat(requested).containsExactly("/assets/romm/resources/roms/18/1/cover/small.png")
        assertThat(store.file(smallOf(missing)).exists()).isFalse() // not sharp: nobody has looked at it
        assertThat(size(store.plain(smallOf(missing))!!)).isEqualTo(240 to 360)
        assertThat(plain.all { it.exists() }).isTrue()
        assertThat(kept.none { store.file(smallOf(it)).exists() }).isTrue()
    }

    @Test fun prefetchDropsCoversNoGameUses() = runBlocking<Unit> {
        cover(large, 600, 900)
        store.get(server.url("/"), small, large) // a sharp one, kept
        val gone = store.file("/assets/romm/resources/roms/1/1/cover/small.png").apply { writeText("old") }
        val gonePlain = plainThumbnail("/assets/romm/resources/roms/1/2/cover/small.png")
        val writing = File(dir, "abc.webp123.tmp").apply { writeText("") } // another fetch, mid-write

        cover(smallOf(8563), 240, 360)
        store.prefetch(server.url("/"), listOf(small, smallOf(8563)))

        assertThat(requested).hasSize(2) // one earlier, one for the missing cover
        assertThat(store.file(small).exists()).isTrue()
        assertThat(store.plain(smallOf(8563))).isNotNull()
        assertThat(gone.exists()).isFalse()
        assertThat(gonePlain.exists()).isFalse()
        assertThat(writing.exists()).isTrue()
    }

    @Test fun prefetchStopsWhenAsked() = runBlocking<Unit> {
        serve()
        store.prefetch(server.url("/"), listOf(small), stopped = { true })
        assertThat(requested).isEmpty()
    }

    /** "Only on Wi-Fi" on a metered network: [held] says covers can't be fetched now. */
    @Volatile private var held = false
    private val heldStore by lazy { CoverStore(dir, OkHttpClient(), scope) { !held } }

    @Test fun noCoverIsFetchedWhileFetchingIsHeld() = runBlocking<Unit> {
        cover(small, 240, 360)
        cover(large, 600, 900)
        held = true
        assertThat(heldStore.get(server.url("/"), small, large)).isNull()
        assertThat(heldStore.forDrawing(CoverRef(small, server.url("/"), large))).isNull()
        heldStore.prefetch(server.url("/"), listOf(small))
        assertThat(requested).isEmpty()
        assertThat(heldStore.plain(small)).isNull()
    }

    @Test fun aCoverAlreadyKeptStillShowsWhileFetchingIsHeld() = runBlocking<Unit> {
        cover(large, 600, 900)
        val sharp = heldStore.get(server.url("/"), small, large)!!
        val plain = plainThumbnail(smallOf(2))
        held = true
        assertThat(heldStore.forDrawing(CoverRef(small, server.url("/"), large))).isEqualTo(sharp)
        assertThat(heldStore.forDrawing(CoverRef(smallOf(2), server.url("/"), largeOf(2)))).isEqualTo(plain)
        assertThat(requested).hasSize(1) // only the one fetched before
    }

    /** It isn't given up on either: drawn again once covers can be fetched, it is sharpened then. */
    @Test fun aPlainCoverIsntSharpenedWhileFetchingIsHeld() = runBlocking<Unit> {
        cover(large, 600, 900)
        val plain = plainThumbnail(small)
        held = true
        val sharpenedWhileHeld = CountDownLatch(1)
        assertThat(heldStore.forDrawing(CoverRef(small, server.url("/"), large)) { sharpenedWhileHeld.countDown() }).isEqualTo(plain)
        assertThat(sharpenedWhileHeld.await(300, TimeUnit.MILLISECONDS)).isFalse()
        assertThat(requested).isEmpty()
        assertThat(heldStore.file(small).exists()).isFalse()

        held = false
        val sharpened = CountDownLatch(1)
        assertThat(heldStore.forDrawing(CoverRef(small, server.url("/"), large)) { sharpened.countDown() }).isEqualTo(plain)
        assertThat(sharpened.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(heldStore.file(small).exists()).isTrue()
    }

    /** Turned on mid-prefetch, on mobile data: the prefetch stops at the next batch. */
    @Test fun prefetchStopsOnceFetchingIsHeld() = runBlocking<Unit> {
        (1..4).forEach { cover(smallOf(it), 240, 360) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.url.encodedPath
                held = true
                return MockResponse.Builder().setHeader("Content-Type", "image/png").body(Buffer().write(png(240, 360))).build()
            }
        }
        heldStore.prefetch(server.url("/"), (1..4).map(::smallOf), parallelism = 1)
        assertThat(requested).containsExactly("/assets/romm/resources/roms/18/1/cover/small.png")
        assertThat(heldStore.plain(smallOf(1))).isNotNull()
        assertThat((2..4).none { heldStore.plain(smallOf(it)) != null }).isTrue()
    }
}
