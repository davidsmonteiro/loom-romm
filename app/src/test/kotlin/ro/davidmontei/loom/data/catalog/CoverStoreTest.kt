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
import ro.davidmontei.loom.data.db.CoverPaths
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

    /** A thumbnail from before (256 px wide, from the small cover) as the previous Loom kept it. */
    private fun olderThumbnail(path: String): File {
        val name = store.file(path).name.substringBefore('-') + ".webp"
        return File(dir, name).apply { writeBytes(png(256, 384)) }
    }

    /**
     * Thumbnails from before stay drawn until replaced, and a drawn one is replaced in the background:
     * the library never waits on RomM for a cover it has, and covers aren't all fetched again at once.
     */
    @Test fun anOlderThumbnailIsDrawnAtOnceAndReplacedInTheBackground() = runBlocking<Unit> {
        cover(large, 600, 900)
        val older = olderThumbnail(small)
        val replaced = CountDownLatch(1)
        assertThat(store.forDrawing(CoverRef(small, server.url("/"), large)) { replaced.countDown() }).isEqualTo(older)
        assertThat(replaced.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(size(store.file(small))).isEqualTo(CoverStore.WIDTH to CoverStore.HEIGHT)
        assertThat(older.exists()).isFalse() // its space is given back
        assertThat(store.forDrawing(CoverRef(small, server.url("/"), large))).isEqualTo(store.file(small))
        assertThat(requested).hasSize(1)
    }

    /**
     * Scrolling through a long list of older thumbnails replaces them a couple at a time, with a few
     * dozen waiting at most: the rest wait to be drawn again, or for the next prefetch.
     */
    @Test fun olderThumbnailsAreReplacedACoupleAtATime() = runBlocking<Unit> {
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
        val games = (1..40).map(::paths)
        games.forEach { olderThumbnail(it.small) }
        val replaced = CountDownLatch(32)
        repeat(2) { games.forEach { store.forDrawing(CoverRef(it.small, server.url("/"), it.large)) { replaced.countDown() } } } // drawn twice: asked for once

        Thread.sleep(300)
        assertThat(requested).hasSize(2)
        release.countDown()
        assertThat(replaced.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(most.get()).isEqualTo(2)
        assertThat(games.count { store.file(it.small).exists() }).isEqualTo(32)
        assertThat(requested).hasSize(32)
    }

    @Test fun offlineAnOlderThumbnailIsDrawnAsItIs() = runBlocking<Unit> {
        val older = olderThumbnail(small)
        assertThat(store.forDrawing(CoverRef(small, base = null, large))).isEqualTo(older)
        assertThat(older.exists()).isTrue()
    }

    private fun paths(id: Int) = CoverPaths("/assets/romm/resources/roms/18/$id/cover/small.png?ts=1", "/assets/romm/resources/roms/18/$id/cover/big.png?ts=1")

    /**
     * A prefetch makes the covers not stored yet, and replaces only so many from before: 20,000
     * covers don't all come down again in one go.
     */
    @Test fun aPrefetchMakesMissingCoversAndReplacesOnlySomeFromBefore() = runBlocking<Unit> {
        val missing = paths(1)
        val before = (2..5).map(::paths)
        (listOf(missing) + before).forEach { cover(it.large!!, 600, 900) }
        before.forEach { olderThumbnail(it.small) }

        store.prefetch(server.url("/"), listOf(missing) + before, upgrades = 2)

        assertThat(store.file(missing.small).exists()).isTrue()
        assertThat(before.count { store.file(it.small).exists() }).isEqualTo(2)
        assertThat(before.count { store.older(it.small) != null }).isEqualTo(2) // the rest are still drawn, until the next run
        assertThat(requested).hasSize(3)
    }

    @Test fun prefetchDropsCoversNoGameUses() = runBlocking<Unit> {
        cover(large, 600, 900)
        store.get(server.url("/"), small, large)
        val gone = store.file("/assets/romm/resources/roms/1/1/cover/small.png").apply { writeText("old") }
        val goneBefore = olderThumbnail("/assets/romm/resources/roms/1/2/cover/small.png")
        val writing = File(dir, "abc.webp123.tmp").apply { writeText("") } // another fetch, mid-write

        val other = paths(8563)
        cover(other.large!!, 600, 900)
        store.prefetch(server.url("/"), listOf(CoverPaths(small, large), other))

        assertThat(requested).hasSize(2) // one earlier, one for the missing cover
        assertThat(store.file(other.small).exists()).isTrue()
        assertThat(gone.exists()).isFalse()
        assertThat(goneBefore.exists()).isFalse()
        assertThat(writing.exists()).isTrue()
    }

    @Test fun prefetchStopsWhenAsked() = runBlocking<Unit> {
        serve()
        store.prefetch(server.url("/"), listOf(CoverPaths(small, large)), stopped = { true })
        assertThat(requested).isEmpty()
    }
}
