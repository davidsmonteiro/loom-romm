package ro.davidmontei.loom.data.catalog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import coil3.request.Options
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cache key for a cover: its path plus RomM's `ts`, without the host. The same cover fetched
 * over the Tailnet or the public address is one entry, so covers keep showing offline.
 */
fun coverCacheKey(path: String): String = path.substringBefore('?') + "#" + path.substringAfter("ts=", "")

/**
 * Every game's cover, kept on the device at the size the library shows it.
 *
 * RomM's "small" covers are full scans of about 100 KB, so a 23,000-game library's would need
 * over 2 GB. Shrunk to cover a 256×341 px card (the grid's cards on the Thor) and stored as WebP
 * they are about 15 KB, and every platform can be browsed offline. They live in the cache dir:
 * Android may trim them when storage runs low, and the next sync fetches them again.
 */
class CoverStore(private val dir: File, private val http: OkHttpClient) {

    fun file(path: String): File = File(dir, fileName(path))

    /** The stored cover for [path], fetched and shrunk first if needed; null if it can't be had. */
    suspend fun get(base: HttpUrl?, path: String): File? {
        val file = file(path)
        if (file.exists()) return file
        val url = base?.let { RommUrls.coverUrl(it, path) } ?: return null
        return withContext(Dispatchers.IO) {
            val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body.bytes()
            }
            val bitmap = shrink(bytes) ?: return@withContext null
            dir.mkdirs()
            // The library and the prefetch can fetch one cover at once: each writes its own file.
            val tmp = File.createTempFile(file.name, ".tmp", dir)
            try {
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it) }
                if (!tmp.renameTo(file)) throw IOException("Couldn't store ${file.name}")
            } finally {
                bitmap.recycle()
                tmp.delete()
            }
            file
        }
    }

    /** Fetches every cover not stored yet, stopping early if [stopped]; then drops covers no game uses. */
    suspend fun prefetch(base: HttpUrl, paths: Collection<String>, parallelism: Int = 4, stopped: () -> Boolean = { false }) {
        val wanted = paths.associateBy(::fileName)
        val missing = wanted.filterKeys { !File(dir, it).exists() }.values
        for (batch in missing.chunked(parallelism)) {
            if (stopped()) return
            coroutineScope {
                // Best effort: a cover that fails to load only costs a placeholder.
                batch.map { path -> async { runCatching { get(base, path) } } }.awaitAll()
            }
        }
        // Covers of games gone from RomM, and temp files a crash left behind; not ones being written now.
        val stale = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.forEach {
            if (if (it.name.endsWith(".tmp")) it.lastModified() < stale else it.name !in wanted) it.delete()
        }
    }

    companion object {
        const val CARD_WIDTH = 256
        const val CARD_HEIGHT = 341
        private const val QUALITY = 80

        private fun fileName(path: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(coverCacheKey(path).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(32) + ".webp"
        }

        /** Decodes [bytes] scaled so it still fills a card when cropped, never enlarged. */
        internal fun shrink(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= CARD_WIDTH && bounds.outHeight / (sample * 2) >= CARD_HEIGHT) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = max(CARD_WIDTH / decoded.width.toFloat(), CARD_HEIGHT / decoded.height.toFloat())
            if (scale >= 1f) return decoded
            val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
            if (scaled !== decoded) decoded.recycle()
            return scaled
        }
    }
}

/** What the library asks Coil for: a game's cover by its RomM path, and where to fetch it if it isn't stored. */
data class CoverRef(val path: String, val base: HttpUrl?)

/** Serves [CoverRef]s from the [CoverStore], fetching on first sight. */
class CoverFetcher(private val ref: CoverRef, private val store: CoverStore) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = store.get(ref.base, ref.path) ?: throw IOException("No cover for ${ref.path}")
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), "image/webp", DataSource.DISK)
    }

    class Factory(private val store: CoverStore) : Fetcher.Factory<CoverRef> {
        override fun create(data: CoverRef, options: Options, imageLoader: ImageLoader): Fetcher = CoverFetcher(data, store)
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
        store.prefetch(server.baseUrl(), catalog.smallCovers().distinct(), stopped = { isStopped })
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
