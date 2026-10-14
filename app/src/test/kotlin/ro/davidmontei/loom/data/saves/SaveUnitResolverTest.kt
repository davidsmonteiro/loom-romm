package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** From an installed 3DS game to its Azahar save folder, or why not. */
@RunWith(AndroidJUnit4::class)
class SaveUnitResolverTest : SaveSyncTestBase() {
    private val resolver get() = SaveUnitResolver(settings, volumes)

    private suspend fun resolve(game: Game): ResolvedSave {
        val dao = db.catalogDao()
        return resolver.resolve(dao.rom(game.romId)!!, dao.install(game.romId)!!)
    }

    @Test fun aCartridgeImageNamesItsSaveFolder() = runBlocking<Unit> {
        install(pokemonX)
        val ready = resolve(pokemonX) as ResolvedSave.Ready
        assertThat(ready.emulator).isEqualTo("azahar")
        assertThat(ready.roots.single().name).isEqualTo("data")
        assertThat(ready.roots.single().folder.toFile()).isEqualTo(saveFolder(pokemonX))
        assertThat(ready.fileName).isEqualTo("Pokemon X.zip")
    }

    /** RomM's sigil can name an update's title (0004000e); only a game's own title has the save. */
    @Test fun aRommTitleThatIsntTheGamesIsPassedOver() = runBlocking<Unit> {
        install(pokemonX, rommSaveTarget = "0004000e/00055d00")
        assertThat((resolve(pokemonX) as ResolvedSave.Ready).roots.single().folder.toFile()).isEqualTo(saveFolder(pokemonX))
    }

    @Test fun aGameInstalledAsAFolderIsReadFromTheImageInside() = runBlocking<Unit> {
        install(pokemonX, file = "Pokemon X/Pokemon X.3ds")
        db.catalogDao().upsertInstall(db.catalogDao().install(7)!!.copy(relPath = "n3ds/Pokemon X", pathKey = "n3ds/pokemon x"))
        assertThat(resolve(pokemonX)).isInstanceOf(ResolvedSave.Ready::class.java)
    }

    @Test fun withoutAzaharsFolderInTheSystemFolderItSaysWhere() = runBlocking<Unit> {
        install(pokemonX)
        File(n3ds, "sdmc").deleteRecursively()
        val unsupported = resolve(pokemonX) as ResolvedSave.Unsupported
        assertThat(unsupported.reason).contains("Azahar's folder")
    }

    /** The library says why a game's saves aren't synced, naming the emulator Cocoon runs it in on the Thor. */
    @Test fun otherPlatformsSayWhyTheirSavesArentSynced() {
        assertThat(SaveUnitResolver.supportsPlatform("3ds")).isTrue()
        assertThat(SaveUnitResolver.supportsPlatform("new-nintendo-3ds")).isTrue()
        assertThat(SaveUnitResolver.supportsPlatform("nds")).isFalse()
        assertThat(SaveUnitResolver.unsupportedReason("switch")).isEqualTo("Eden keeps its saves in Android/data, where Loom can't reach them")
        assertThat(SaveUnitResolver.unsupportedReason("psx")).isEqualTo("DuckStation keeps its memory cards in Android/data, where Loom can't reach them")
        assertThat(SaveUnitResolver.unsupportedReason("ngc")).startsWith("Dolphin")
        assertThat(SaveUnitResolver.unsupportedReason("wiiu")).startsWith("Cemu")
        assertThat(SaveUnitResolver.unsupportedReason("psp")).isEqualTo("Loom doesn't sync PPSSPP saves yet")
        assertThat(SaveUnitResolver.unsupportedReason("snes")).isEqualTo("Loom syncs 3DS saves (Azahar) only, for now")
    }

    @Test fun azaharsFolderIsTheOneHoldingItsSdCard() {
        assertThat(SaveUnitResolver.holdsAzaharData(n3ds)).isTrue()
        assertThat(SaveUnitResolver.holdsAzaharData(File(volume, "ROMs"))).isFalse()
        File(n3ds, "sdmc/Nintendo 3DS").deleteRecursively()
        assertThat(SaveUnitResolver.holdsAzaharData(n3ds)).isFalse() // sdmc alone isn't Azahar's yet
    }

    /** The card being out is "can't look now", never "no save". */
    @Test fun anUnmountedCardIsUnavailableNotEmpty() = runBlocking<Unit> {
        install(pokemonX)
        mounted = false
        assertThat(resolve(pokemonX)).isInstanceOf(ResolvedSave.Unavailable::class.java)
    }
}
