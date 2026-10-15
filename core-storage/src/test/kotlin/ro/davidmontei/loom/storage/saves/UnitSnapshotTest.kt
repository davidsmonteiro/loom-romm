package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant

class UnitSnapshotTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun describesTheUnitAndChangesWithAnyFile() {
        val data = folderOf(tmp.root.toPath().resolve("data"), "00000001/main" to ByteArray(100), "00000001.metadata" to ByteArray(32))
        Files.setLastModifiedTime(data.resolve("00000001/main"), FileTime.from(Instant.parse("2026-10-13T17:49:45Z")))
        Files.setLastModifiedTime(data.resolve("00000001.metadata"), FileTime.from(Instant.parse("2026-10-13T17:49:40Z")))
        val unit = listOf(SaveRoot("data", data))
        val first = UnitSnapshot.of(unit)
        assertThat(first.fileCount).isEqualTo(2)
        assertThat(first.totalBytes).isEqualTo(132)
        assertThat(first.newestModified).isEqualTo(Instant.parse("2026-10-13T17:49:45Z"))
        assertThat(UnitSnapshot.of(unit)).isEqualTo(first)

        // Same size, new time: what an emulator rewriting its save in place looks like.
        Files.setLastModifiedTime(data.resolve("00000001.metadata"), FileTime.from(Instant.parse("2026-10-13T17:49:41Z")))
        assertThat(UnitSnapshot.of(unit).fingerprint).isNotEqualTo(first.fingerprint)
        assertThat(UnitSnapshot.of(unit).newestModified).isEqualTo(first.newestModified)
    }

    /** melonDS's empty placeholder is no save, yet a scan still sees it appear, and the game write to it. */
    @Test fun anEmptyPlaceholdersSnapshotIsNoSave() {
        val file = tmp.root.toPath().resolve("Mario Kart DS (USA).sav")
        val missing = UnitSnapshot.ofFile(file)
        Files.write(file, ByteArray(0))
        val placeholder = UnitSnapshot.ofFile(file)
        assertThat(placeholder.isEmpty).isTrue()
        assertThat(placeholder.totalBytes).isEqualTo(0)
        assertThat(placeholder.newestModified).isNull()
        assertThat(placeholder.fingerprint).isNotEqualTo(missing.fingerprint)

        Files.write(file, ByteArray(512))
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-10-13T17:49:45Z")))
        val saved = UnitSnapshot.ofFile(file)
        assertThat(saved.fileCount).isEqualTo(1)
        assertThat(saved.totalBytes).isEqualTo(512)
        assertThat(saved.newestModified).isEqualTo(Instant.parse("2026-10-13T17:49:45Z"))
        assertThat(saved.fingerprint).isNotEqualTo(placeholder.fingerprint)
    }

    @Test fun aMissingUnitIsEmpty() {
        val snapshot = UnitSnapshot.of(listOf(SaveRoot("data", tmp.root.toPath().resolve("nothing/data"))))
        assertThat(snapshot.isEmpty).isTrue()
        assertThat(snapshot.newestModified).isNull()
    }
}
