package ro.davidmontei.loom.ui

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ro.davidmontei.loom.data.db.PlatformEntity
import ro.davidmontei.loom.data.db.PlatformSummary
import ro.davidmontei.loom.ui.library.selectedPlatform

class PlatformSelectionTest {
    private fun platform(id: Int, name: String) =
        PlatformSummary(PlatformEntity(id, name.lowercase(), name.lowercase(), name, name, 10, "t"), 10, 0, 0)

    private val psx = platform(2, "PlayStation")
    private val gba = platform(3, "Game Boy Advance")
    private val platforms = MutableStateFlow(listOf(psx))
    private val picked = MutableStateFlow<Int?>(null)
    private fun shown() = runBlocking { selectedPlatform(platforms, picked).first()?.platform?.id }

    /** Found by audit: during the first sync the library jumped to each new platform that sorted first. */
    @Test fun theDefaultStaysPutAsASyncAddsPlatformsThatSortFirst() {
        assertThat(shown()).isEqualTo(psx.platform.id)
        platforms.value = listOf(gba, psx)
        assertThat(shown()).isEqualTo(psx.platform.id)
    }

    @Test fun aPickedPlatformIsShown() {
        platforms.value = listOf(gba, psx)
        picked.value = psx.platform.id
        assertThat(shown()).isEqualTo(psx.platform.id)
    }

    @Test fun aPlatformThatIsGoneFallsBackToTheFirst() {
        platforms.value = listOf(gba, psx)
        picked.value = 99
        assertThat(shown()).isEqualTo(gba.platform.id)
    }

    @Test fun nothingIsShownUntilThereArePlatforms() {
        platforms.value = emptyList()
        assertThat(shown()).isNull()
        assertThat(picked.value).isNull()
    }
}
