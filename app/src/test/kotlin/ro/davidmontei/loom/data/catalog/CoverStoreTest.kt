package ro.davidmontei.loom.data.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Found on the Thor: RomM's "small" covers average ~100 KB, so 23,000 of them overflowed the old
 * 512 MB cache and every sync downloaded the evicted ones again.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverStoreTest {
    @get:Rule val rule = MockWebServerRule()
    @get:Rule val tmp = TemporaryFolder()
    private val server get() = rule.server
    private val dir by lazy { tmp.newFolder("covers") }
    private val store by lazy { CoverStore(dir, OkHttpClient()) }
    private val path = "/assets/romm/resources/roms/18/8562/cover/small.png?ts=2026-07-30 13:55:01"

    private fun png(width: Int, height: Int): MockResponse {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(217, 164, 65)) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return MockResponse.Builder().setHeader("Content-Type", "image/png").body(Buffer().write(bytes)).build()
    }

    private fun size(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(file.path, it) }.let { it.outWidth to it.outHeight }

    @Test fun storesCoversShrunkToFillACard() = runBlocking<Unit> {
        server.enqueue(png(600, 850))
        val file = store.get(server.url("/"), path)!!
        assertThat(size(file)).isEqualTo(256 to 363) // fills 256×341 when cropped
        assertThat(file.name).endsWith(".webp")
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/assets/romm/resources/roms/18/8562/cover/small.png")
    }

    @Test fun landscapeCoversKeepEnoughHeightToFillTheCard() = runBlocking<Unit> {
        server.enqueue(png(800, 560))
        assertThat(size(store.get(server.url("/"), path)!!)).isEqualTo(487 to 341)
    }

    @Test fun smallCoversAreNotEnlarged() = runBlocking<Unit> {
        server.enqueue(png(200, 280))
        assertThat(size(store.get(server.url("/"), path)!!)).isEqualTo(200 to 280)
    }

    @Test fun aStoredCoverNeedsNoServerAndNoParticularAddress() = runBlocking<Unit> {
        server.enqueue(png(600, 850))
        val first = store.get(server.url("/"), path)
        assertThat(store.get(base = null, path)).isEqualTo(first) // offline
        assertThat(store.get(server.url("/other/"), path)).isEqualTo(first) // another address for the same server
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun missingCoverIsNull() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThat(store.get(server.url("/"), path)).isNull()
        assertThat(store.get(base = null, "/never/fetched.png")).isNull()
    }

    @Test fun prefetchFetchesOnlyWhatIsMissingAndDropsCoversNoGameUses() = runBlocking<Unit> {
        server.enqueue(png(600, 850))
        store.get(server.url("/"), path)
        val gone = store.file("/assets/romm/resources/roms/1/1/cover/small.png").apply { writeText("old") }
        val writing = File(dir, "abc.webp123.tmp").apply { writeText("") } // another fetch, mid-write

        server.enqueue(png(600, 850))
        val other = "/assets/romm/resources/roms/18/8563/cover/small.png?ts=2026-07-30 13:55:06"
        store.prefetch(server.url("/"), listOf(path, other))

        assertThat(server.requestCount).isEqualTo(2) // one earlier, one for the missing cover
        assertThat(store.file(other).exists()).isTrue()
        assertThat(gone.exists()).isFalse()
        assertThat(writing.exists()).isTrue()
    }

    @Test fun prefetchStopsWhenAsked() = runBlocking<Unit> {
        store.prefetch(server.url("/"), listOf(path), stopped = { true })
        assertThat(server.requestCount).isEqualTo(0)
    }
}
