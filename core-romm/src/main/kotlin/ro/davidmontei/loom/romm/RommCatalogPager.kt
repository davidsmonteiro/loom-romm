package ro.davidmontei.loom.romm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ro.davidmontei.loom.romm.api.RommApi
import ro.davidmontei.loom.romm.dto.RomPageDto

/** One fetched page plus what the pager knows about the whole result so far. */
data class RomPage(val page: RomPageDto, val fetched: Int, val total: Int?)

/**
 * Pages through `GET /api/roms` in id order. The first request asks for `total` (for progress);
 * later ones don't, which saves the server a count. Paging stops on a short page, which is
 * reliable because RomM always breaks ordering ties by id.
 *
 * Offsets can skip or repeat a ROM if the library changes mid-sync; the caller's identifiers diff
 * and the next incremental sync cover that.
 */
fun RommApi.pageRoms(updatedAfter: String? = null, pageSize: Int = DEFAULT_PAGE_SIZE): Flow<RomPage> = flow {
    require(pageSize in 1..10_000) { "RomM accepts limit 1..10000" }
    var offset = 0
    var total: Int? = null
    while (true) {
        val page = rommCall {
            roms(offset = offset, limit = pageSize, updatedAfter = updatedAfter, withTotal = offset == 0)
        }
        if (offset == 0) total = page.total
        offset += page.items.size
        emit(RomPage(page, offset, total))
        if (page.items.size < pageSize) break
    }
}

const val DEFAULT_PAGE_SIZE = 250
