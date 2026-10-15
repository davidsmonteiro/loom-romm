package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE_MD5
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A save unit of one raw file: a DS game's `.sav` in melonDS's save folder. */
class SaveUnitTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    private val file get() = path("saves/melonDS/Mario Kart DS (USA).sav")

    private fun rawSave(bytes: ByteArray = RAW_SAVE): SaveUnit.SingleFile {
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
        return SaveUnit.SingleFile(file)
    }

    /** RomM hashes a save that isn't a zip as the MD5 of its bytes. */
    @Test fun aRawSaveHashesAsRommHashesIt() {
        val unit = rawSave()
        assertThat(unit.hash()).isEqualTo(RAW_SAVE_MD5)
        assertThat(unit.path).isEqualTo(file.toString())
        assertThat(unit.anchor).isEqualTo(path("saves/melonDS").toAbsolutePath())
    }

    /** melonDS creates an empty `.sav` when a game first runs: that is no save, as no file is. */
    @Test fun anEmptyFileIsNoSave() {
        val empty = rawSave(ByteArray(0))
        assertThat(empty.hash()).isNull()
        assertThat(empty.snapshot().isEmpty).isTrue()
        assertThrows(SaveArchiveException::class.java) { empty.pack(path("upload.sav")) }

        val missing = SaveUnit.SingleFile(path("saves/melonDS/Tetris DS (USA).sav"))
        assertThat(missing.hash()).isNull()
        assertThat(missing.snapshot().isEmpty).isTrue()
    }

    @Test fun packingSendsTheBytesItHashed() {
        val target = path("upload.sav")
        assertThat(rawSave().pack(target)).isEqualTo(RAW_SAVE_MD5)
        assertThat(Files.readAllBytes(target)).isEqualTo(RAW_SAVE)
        assertThat(SaveHasher.hashFile(target)).isEqualTo(RAW_SAVE_MD5)
    }

    /** A folder where the save should be: Loom can't tell what the save is, which is never "no save". */
    @Test fun somethingElseInTheSavesPlaceCantBeRead() {
        val unit = SaveUnit.SingleFile(Files.createDirectories(file))
        assertThrows(IOException::class.java) { unit.hash() }
        assertThrows(IOException::class.java) { unit.snapshot() }
    }
}
