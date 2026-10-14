package ro.davidmontei.loom.data.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.net.ActiveServer
import ro.davidmontei.loom.net.NotPairedException
import ro.davidmontei.loom.romm.RommUrls
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cache key for a cover: its path plus RomM's `ts`, without the host. The same cover fetched
 * over the Tailnet or the public address is one entry, so covers keep showing offline.
 */
fun coverCacheKey(path: String): String = path.substringBefore('?') + "#" + path.substringAfter("ts=", "")

/**
 * Every game's cover, kept on the device so every platform can be browsed offline.
 *
 * Two kinds of thumbnail, both WebP at quality 75 and never enlarged:
 * - A plain one, from RomM's "small" cover, for every game: the prefetch after each sync makes the
 *   ones missing. RomM makes small covers 0.4 the size of its large art (0.2 for art 1,000 px tall
 *   or more): 162 to 264 px wide, narrower than a grid card on the Thor (285 px), so they are drawn
 *   a little soft. About 0.1 MB to fetch and 15 KB to keep.
 * - A sharp one, from RomM's large art, shrunk and cropped to [WIDTH]×[HEIGHT] (the size a focused
 *   card draws its cover), only for the covers that are drawn: made in the background, a couple at a
 *   time, while the plain one is shown, which is deleted once the sharp one is stored. About 0.5 MB
 *   to fetch and 20 KB to keep.
 *
 * They live in the cache dir: Android may trim them when storage runs low, and the next sync fetches
 * them again.
 */
class CoverStore(private val dir: File, private val http: OkHttpClient, private val scope: CoroutineScope) {

    /** Where [path]'s sharp thumbnail is kept. */
    fun file(path: String): File = File(dir, name(path))

    /** [path]'s plain thumbnail (or one made at an earlier size), if it has one. */
    fun plain(path: String): File? = PLAIN_SUFFIXES.map { File(dir, "${hash(path)}$it.webp") }.firstOrNull { it.exists() }

    /** [path]'s sharp thumbnail, made first if needed (from [large] when it has one); null if it can't be had. */
    suspend fun get(base: HttpUrl?, path: String, large: String? = null): File? {
        val file = file(path)
        if (file.exists()) return file
        if (base == null) return null
        return withContext(Dispatchers.IO) {
            // A cover without large art, or whose large art can't be read, makes do with the small one.
            val made = store(file, large?.let { fetch(base, it) } ?: fetch(base, path) ?: return@withContext null)
            plain(path)?.delete()
            made
        }
    }

    /** Makes [path]'s plain thumbnail from RomM's small cover; null if it can't be had. */
    private suspend fun makePlain(base: HttpUrl, path: String): File? =
        withContext(Dispatchers.IO) { store(File(dir, plainName(path)), fetch(base, path) ?: return@withContext null) }

    /** Stores [bitmap] as [file], without ever leaving a part-written one there. */
    private fun store(file: File, bitmap: Bitmap): File {
        dir.mkdirs()
        // The library and the prefetch can make one cover at once: each writes its own file.
        val tmp = File.createTempFile(file.name, ".tmp", dir)
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it) }
            if (!tmp.renameTo(file)) throw IOException("Couldn't store ${file.name}")
        } finally {
            bitmap.recycle()
            tmp.delete()
        }
        return file
    }

    /**
     * The thumbnail to draw for [ref] right away: the sharp one; else the plain one, while the sharp
     * one is made in the background ([onSharpened] once it is); else the sharp one, made now.
     */
    suspend fun forDrawing(ref: CoverRef, onSharpened: () -> Unit = {}): File? {
        file(ref.path).takeIf { it.exists() }?.let { return it }
        plain(ref.path)?.let { plainFile ->
            sharpenLater(ref, onSharpened)
            return plainFile
        }
        return get(ref.base, ref.path, ref.large)
    }

    private val sharpening: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val sharpenSlots = Semaphore(SHARPENING_AT_ONCE)

    /**
     * Makes [ref]'s sharp thumbnail in the background, a couple at a time. At most [SHARPENING_WAITING]
     * wait: scrolling through a long list doesn't queue them all, and the ones left out are asked for
     * again when drawn again.
     */
    private fun sharpenLater(ref: CoverRef, onSharpened: () -> Unit) {
        if (ref.base == null || sharpening.size >= SHARPENING_WAITING || !sharpening.add(ref.path)) return
        scope.launch {
            try {
                sharpenSlots.withPermit { if (get(ref.base, ref.path, ref.large) != null) onSharpened() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort: the plain thumbnail stays until the next time it's drawn.
            } finally {
                sharpening.remove(ref.path)
            }
        }
    }

    /**
     * Makes a plain thumbnail for every cover that has no thumbnail yet, stopping early if [stopped];
     * then drops thumbnails no game uses. Covers already kept are left as they are: only covers that
     * are drawn are made sharp.
     */
    suspend fun prefetch(base: HttpUrl, paths: Collection<String>, parallelism: Int = 4, stopped: () -> Boolean = { false }) {
        val missing = paths.distinct().filter { !file(it).exists() && plain(it) == null }
        for (batch in missing.chunked(parallelism)) {
            if (stopped()) return
            coroutineScope {
                // Best effort: a cover that fails to load only costs a placeholder.
                batch.map { path -> async { runCatching { makePlain(base, path) } } }.awaitAll()
            }
        }
        // Thumbnails of games gone from RomM, plain ones whose sharp one has been made, and temp files a
        // crash left behind; not ones being written now.
        val keep = HashSet<String>()
        for (path in paths) {
            keep += name(path)
            if (!file(path).exists()) plain(path)?.let { keep += it.name }
        }
        val stale = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach {
            if (if (it.name.endsWith(".tmp")) it.lastModified() < stale else it.name !in keep) it.delete()
        }
    }

    private fun fetch(base: HttpUrl, path: String): Bitmap? {
        val url = RommUrls.coverUrl(base, path) ?: return null
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.bytes()
        }
        return shrink(bytes)
    }

    companion object {
        /**
         * The size a focused grid card draws its cover on the Thor's top screen (1920×1080 at 369 dpi):
         * four columns of 285×380 px, grown 4% by the focus. The list draws covers at 97×129 px.
         * CoverSizeTest measures it. When it changes, add today's suffix ("-297x396") to
         * [PLAIN_SUFFIXES], so the sharp thumbnails made until then are drawn until sharpened again.
         */
        const val WIDTH = 297
        const val HEIGHT = 396
        private const val QUALITY = 75

        /** How thumbnails that aren't sharp are named after their hash: "" is a plain one (and what earlier Loom kept). */
        private val PLAIN_SUFFIXES = listOf("")

        private const val SHARPENING_AT_ONCE = 2
        private const val SHARPENING_WAITING = 32

        private fun hash(path: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(coverCacheKey(path).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }

        private fun name(path: String) = "${hash(path)}-${WIDTH}x$HEIGHT.webp"

        private fun plainName(path: String) = "${hash(path)}.webp"

        /**
         * Decodes [bytes] shrunk to fill a [WIDTH]×[HEIGHT] card and cropped to it, centred as the
         * library crops it; never enlarged.
         */
        internal fun shrink(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= WIDTH && bounds.outHeight / (sample * 2) >= HEIGHT) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = max(WIDTH / decoded.width.toFloat(), HEIGHT / decoded.height.toFloat())
            val scaled = if (scale >= 1f) decoded else decoded.scale((decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt())
            if (scaled !== decoded) decoded.recycle()
            val width = min(WIDTH, scaled.width)
            val height = min(HEIGHT, scaled.height)
            if (width == scaled.width && height == scaled.height) return scaled
            val cropped = Bitmap.createBitmap(scaled, (scaled.width - width) / 2, (scaled.height - height) / 2, width, height)
            if (cropped !== scaled) scaled.recycle()
            return cropped
        }
    }
}

/** What the library asks Coil for: a game's cover by its RomM path, its large art, and where to fetch them if needed. */
data class CoverRef(val path: String, val base: HttpUrl?, val large: String? = null)

/** Serves [CoverRef]s from the [CoverStore], fetching on first sight. */
class CoverFetcher(private val ref: CoverRef, private val store: CoverStore, private val imageLoader: ImageLoader) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // A plain thumbnail is drawn now; once the sharp one is made, the next time the cover is drawn shows it.
        val file = store.forDrawing(ref) { imageLoader.memoryCache?.remove(MemoryCache.Key(coverCacheKey(ref.path))) }
            ?: throw IOException("No cover for ${ref.path}")
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), "image/webp", DataSource.DISK)
    }

    class Factory(private val store: CoverStore) : Fetcher.Factory<CoverRef> {
        override fun create(data: CoverRef, options: Options, imageLoader: ImageLoader): Fetcher = CoverFetcher(data, store, imageLoader)
    }
}

/** One memory-cache entry per cover, whichever address it came from. */
object CoverKeyer : Keyer<CoverRef> {
    override fun key(data: CoverRef, options: Options): String = coverCacheKey(data.path)
}

/** Fills the [CoverStore] after a sync, in the background and at its own pace. */
@HiltWorker
class CoverPrefetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val server: ActiveServer,
    private val catalog: CatalogDao,
    private val store: CoverStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        store.prefetch(server.baseUrl(), catalog.smallCovers(), stopped = { isStopped })
        Result.success()
    } catch (e: NotPairedException) {
        Result.failure()
    }

    companion object {
        private const val UNIQUE_NAME = "cover-prefetch"

        /** Restarts the prefetch, so covers of games added since the last run are included. */
        fun enqueue(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<CoverPrefetchWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build(),
                )
                .build()
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
