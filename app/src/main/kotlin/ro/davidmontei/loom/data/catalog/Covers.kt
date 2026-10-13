package ro.davidmontei.loom.data.catalog

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import ro.davidmontei.loom.data.db.CatalogDao
import ro.davidmontei.loom.romm.RommUrls

/**
 * Cache key for a cover: its path plus RomM's `ts`, without the host. The same cover fetched
 * over the Tailnet or the public address is one cache entry, so covers keep showing offline.
 */
fun coverCacheKey(path: String): String = path.substringBefore('?') + "#" + path.substringAfter("ts=", "")

/** Fills the disk cache with every small cover after a sync, so browsing works offline. */
class CoverPrefetcher(private val context: Context, private val catalog: CatalogDao) {

    suspend fun prefetch(base: HttpUrl, parallelism: Int = 4) {
        val loader = SingletonImageLoader.get(context)
        val disk = loader.diskCache ?: return
        val missing = catalog.smallCovers().distinct().filter { path ->
            disk.openSnapshot(coverCacheKey(path))?.use { false } ?: true
        }
        missing.chunked(parallelism).forEach { batch ->
            coroutineScope {
                batch.map { path ->
                    async {
                        val url = RommUrls.coverUrl(base, path) ?: return@async
                        val key = coverCacheKey(path)
                        loader.execute(ImageRequest.Builder(context).data(url.toString()).diskCacheKey(key).memoryCacheKey(key).build())
                    }
                }.awaitAll()
            }
        }
    }
}
