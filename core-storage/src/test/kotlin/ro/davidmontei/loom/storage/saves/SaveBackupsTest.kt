package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_MAIN
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_METADATA
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_UNIT_HASH
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class SaveBackupsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun path(name: String): Path = tmp.root.toPath().resolve(name)

    private var clock = Instant.parse("2026-10-13T22:15:07.123Z")
    private fun backups(keep: Int = 10) = SaveBackups(path(".loom/save-backups"), keep) { clock }
    private fun unit() = listOf(SaveRoot("data", folderOf(path("data"), "00000001/main" to AZAHAR_MAIN, "00000001.metadata" to AZAHAR_METADATA)))

    @Test fun aBackupIsTheUnitsArchiveNamedByTime() {
        val backup = backups().backUp("42", "Pokémon Omega Ruby (USA)", unit(), AZAHAR_UNIT_HASH)
        assertThat(backup.file).isEqualTo(path(".loom/save-backups/42/20261013-221507-123 Pokémon Omega Ruby (USA).zip"))
        assertThat(backup.hash).isEqualTo(AZAHAR_UNIT_HASH)
        assertThat(SaveHasher.hashFile(backup.file)).isEqualTo(AZAHAR_UNIT_HASH)
        assertThat(Files.list(backup.file.parent).use { it.count() }).isEqualTo(1) // no partial file left
    }

    /** The emulator wrote between Loom's look and the backup: nothing may be overwritten on that basis. */
    @Test fun aSaveThatChangedIsNotBackedUpAsTheOneLoomSaw() {
        assertThrows(SaveChangedException::class.java) { backups().backUp("42", "Game", unit(), "0".repeat(32)) }
        assertThat(backups().list("42")).isEmpty()
        assertThat(Files.list(path(".loom/save-backups/42")).use { it.count() }).isEqualTo(0)
    }

    /** A card that loses writes: the copy is read back before Loom relies on it. */
    @Test fun aCopyThatDoesntReadBackIsNoBackup() {
        val lossy = SaveBackups(path(".loom/save-backups"), archive = { unit, target ->
            unit.pack(target).also { Files.write(target, byteArrayOf(0x50, 0x4b)) }
        }) { clock }
        assertThrows(java.io.IOException::class.java) { lossy.backUp("42", "Game", unit(), AZAHAR_UNIT_HASH) }
        assertThat(backups().list("42")).isEmpty()
    }

    @Test fun keepsTheNewestBackupsOfEachGame() {
        val other = path(".loom/save-backups/42/notes.txt").also { Files.createDirectories(it.parent); Files.write(it, byteArrayOf(1)) }
        val made = (1..5).map {
            clock = clock.plus(Duration.ofMinutes(1))
            backups(keep = 3).backUp("42", "Game", unit(), AZAHAR_UNIT_HASH).file
        }
        backups(keep = 3).backUp("7", "Other", unit(), AZAHAR_UNIT_HASH)
        assertThat(backups().list("42")).containsExactly(made[4], made[3], made[2]).inOrder()
        assertThat(backups().list("7")).hasSize(1)
        assertThat(Files.exists(other)).isTrue() // only Loom's own files are pruned
    }

    /**
     * The Thor's clock went back (a reset clock, automatic time turned off) behind the stamps of
     * ten earlier backups: each new backup is still kept as the newest, and so is the one before.
     */
    @Test fun aClockThatWentBackNeverPrunesTheNewBackups() {
        clock = Instant.parse("2027-10-10T00:00:00Z")
        repeat(10) {
            clock = clock.plus(Duration.ofDays(1))
            backups().backUp("42", "Game", unit(), AZAHAR_UNIT_HASH)
        }
        clock = Instant.parse("2026-10-14T12:00:00Z")

        val first = backups().backUp("42", "Game", unit(), AZAHAR_UNIT_HASH).file
        val second = backups().backUp("42", "Game", unit(), AZAHAR_UNIT_HASH).file

        assertThat(Files.exists(first)).isTrue()
        assertThat(Files.exists(second)).isTrue()
        assertThat(backups().list("42").take(2)).containsExactly(second, first).inOrder()
        assertThat(backups().list("42")).hasSize(10)
    }

    /** A file named like a backup but with no real time, sorting after every backup, never pushes out the one just made. */
    @Test fun theBackupJustMadeIsNeverPruned() {
        path(".loom/save-backups/42/99999999-999999-999 Game.zip").also { Files.createDirectories(it.parent); Files.write(it, byteArrayOf(1)) }
        val made = backups(keep = 1).backUp("42", "Game", unit(), AZAHAR_UNIT_HASH).file
        assertThat(Files.exists(made)).isTrue()
        assertThat(backups().list("42")).containsExactly(made)
    }

    @Test fun twoBackupsInTheSameMillisecondBothSurvive() {
        val first = backups().backUp("42", "Game", unit(), AZAHAR_UNIT_HASH).file
        val second = backups().backUp("42", "Game", unit(), AZAHAR_UNIT_HASH).file
        assertThat(second).isNotEqualTo(first)
        assertThat(backups().list("42")).hasSize(2)
    }
}
