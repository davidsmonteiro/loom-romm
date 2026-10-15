package ro.davidmontei.loom.data.saves

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import ro.davidmontei.loom.storage.saves.SaveUnit
import java.io.File

/** From an installed 3DS game to its Azahar save folder, or why not. */
@RunWith(AndroidJUnit4::class)
class SaveUnitResolverTest : SaveSyncTestBase() {
    private val resolver get() = SaveUnitResolver(settings, volumes, eden())

    private suspend fun resolve(game: Game): ResolvedSave {
        val dao = db.catalogDao()
        return resolver.resolve(dao.rom(game.romId)!!, dao.install(game.romId)!!)
    }

    private val ResolvedSave.Ready.roots get() = (unit as SaveUnit.Folders).roots

    @Test fun aCartridgeImageNamesItsSaveFolder() = runBlocking<Unit> {
        install(pokemonX)
        val ready = resolve(pokemonX) as ResolvedSave.Ready
        assertThat(ready.emulator).isEqualTo("azahar")
        assertThat(ready.roots.single().name).isEqualTo("data")
        assertThat(ready.roots.single().folder.toFile()).isEqualTo(saveFolder(pokemonX))
        assertThat(ready.fileName).isEqualTo("Pokemon X.zip")
    }

    /**
     * Loom 0.3 recorded a save's place as its folder's path, and a baseline recorded for another
     * place is of another save: an Azahar or Eden game's unit must keep that path to stay in sync.
     */
    @Test fun aFolderUnitsPathIsTheOneBaselinesRecorded() = runBlocking<Unit> {
        install(pokemonX)
        putProdKeys()
        installSwitch(wonder)
        val dao = db.catalogDao()
        val switch = resolver.resolve(dao.rom(wonder.romId)!!, dao.install(wonder.romId)!!) as ResolvedSave.Ready
        assertThat((resolve(pokemonX) as ResolvedSave.Ready).unitPath).isEqualTo(saveFolder(pokemonX).path)
        assertThat(switch.unitPath).isEqualTo(edenFolder(wonder).path)
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
        assertThat(SaveUnitResolver.unsupportedReason("psx")).isEqualTo("DuckStation keeps its memory cards in Android/data, where Loom can't reach them")
        assertThat(SaveUnitResolver.unsupportedReason("ngc")).startsWith("Dolphin")
        assertThat(SaveUnitResolver.unsupportedReason("wiiu")).startsWith("Cemu")
        assertThat(SaveUnitResolver.unsupportedReason("psp")).isEqualTo("Loom doesn't sync PPSSPP saves yet")
        assertThat(SaveUnitResolver.unsupportedReason("snes")).isEqualTo("Loom syncs 3DS (Azahar) and Switch (Eden) saves only, for now")
    }

    /** Eden's save folder moved to the card makes Switch games syncable, under Eden's emulator id (Argosy's too). */
    @Test fun switchGamesAreSyncedThroughEden() = runBlocking<Unit> {
        assertThat(SaveUnitResolver.supportsPlatform("switch")).isTrue()
        assertThat(SaveUnitResolver.emulatorForPlatform("switch")).isEqualTo("eden")
        assertThat(SaveUnitResolver.emulatorForPlatform("3ds")).isEqualTo("azahar")
        putProdKeys()
        installSwitch(wonder)
        val dao = db.catalogDao()
        val ready = resolver.resolve(dao.rom(wonder.romId)!!, dao.install(wonder.romId)!!) as ResolvedSave.Ready
        assertThat(ready.emulator).isEqualTo("eden")
        assertThat(ready.roots.single().name).isEqualTo(wonder.titleId)
        assertThat(ready.roots.single().folder.toFile()).isEqualTo(edenFolder(wonder))
        assertThat(ready.fileName).isEqualTo("Super Mario Bros Wonder.zip")
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
