package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_MAIN
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_METADATA
import ro.davidmontei.loom.storage.saves.SaveFixtures.AZAHAR_UNIT_HASH
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE
import ro.davidmontei.loom.storage.saves.SaveFixtures.RAW_SAVE_MD5
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

    /** The unit holding a save of its own, [n], and that save's hash. */
    private fun unit(n: Int): Pair<List<SaveRoot>, String> {
        val roots = listOf(SaveRoot("data", folderOf(path("data"), "00000001/main" to byteArrayOf(n.toByte()), "00000001.metadata" to AZAHAR_METADATA)))
        return roots to SaveHasher.hashUnit(roots)!!
    }
    private fun rawUnit() = SaveUnit.SingleFile(folderOf(path("melonDS"), "Mario Kart DS.sav" to RAW_SAVE).resolve("Mario Kart DS.sav"))

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

    /** A DS save is one raw file: its backup is that file as it was, named `.sav`, not a zip of it. */
    @Test fun aRawSaveIsBackedUpAsItself() {
        val backup = backups().backUp("42", "Mario Kart DS", rawUnit(), RAW_SAVE_MD5)
        assertThat(backup.file).isEqualTo(path(".loom/save-backups/42/20261013-221507-123 Mario Kart DS.sav"))
        assertThat(backup.hash).isEqualTo(RAW_SAVE_MD5)
        assertThat(Files.readAllBytes(backup.file)).isEqualTo(RAW_SAVE)
        assertThat(Files.list(backup.file.parent).use { it.count() }).isEqualTo(1) // no partial file left
    }

    @Test fun aRawCopyThatDoesntReadBackIsNoBackup() {
        val lossy = SaveBackups(path(".loom/save-backups"), archive = { unit, target ->
            unit.pack(target).also { Files.write(target, byteArrayOf(1)) }
        }) { clock }
        assertThrows(java.io.IOException::class.java) { lossy.backUp("42", "Game", rawUnit(), RAW_SAVE_MD5) }
        assertThat(backups().list("42")).isEmpty()
    }

    /** Raw and zipped backups of one game count as one set: only the newest are kept, whatever their kind. */
    @Test fun rawAndZipBackupsArePrunedTogether() {
        val made = (1..5).map {
            clock = clock.plus(Duration.ofMinutes(1))
            if (it % 2 == 1) backups(keep = 3).backUp("42", "Game", rawUnit(), RAW_SAVE_MD5).file
            else backups(keep = 3).backUp("42", "Game", unit(), AZAHAR_UNIT_HASH).file
        }
        assertThat(made.map { it.fileName.toString().substringAfterLast('.') }).containsExactly("sav", "zip", "sav", "zip", "sav").inOrder()
        assertThat(backups().list("42")).containsExactly(made[4], made[3], made[2]).inOrder()
        assertThat(Files.list(path(".loom/save-backups/42")).use { it.count() }).isEqualTo(3)
    }

    @Test fun keepsTheNewestBackupsOfEachGame() {
        val other = path(".loom/save-backups/42/notes.txt").also { Files.createDirectories(it.parent); Files.write(it, byteArrayOf(1)) }
        val made = (1..5).map {
            clock = clock.plus(Duration.ofMinutes(1))
            val (unit, hash) = unit(it)
            backups(keep = 3).backUp("42", "Game", unit, hash).file
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
            val (unit, hash) = unit(it)
            backups().backUp("42", "Game", unit, hash)
        }
        clock = Instant.parse("2026-10-14T12:00:00Z")

        val first = unit(20).let { (unit, hash) -> backups().backUp("42", "Game", unit, hash).file }
        val second = unit(21).let { (unit, hash) -> backups().backUp("42", "Game", unit, hash).file }

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
        val first = unit(1).let { (unit, hash) -> backups().backUp("42", "Game", unit, hash).file }
        val second = unit(2).let { (unit, hash) -> backups().backUp("42", "Game", unit, hash).file }
        assertThat(second).isNotEqualTo(first)
        assertThat(backups().list("42")).hasSize(2)
    }

    /**
     * A restore that fails the same way every time backs the same save up before each attempt.
     * Those copies would push out the older backups, one of them maybe the only copy of a save the
     * owner replaced: a save the newest backup already holds isn't backed up again.
     */
    @Test fun theSameSaveAsTheNewestBackupIsntBackedUpAgain() {
        val older = (1..3).map {
            clock = clock.plus(Duration.ofMinutes(1))
            val (unit, hash) = unit(it)
            backups(keep = 4).backUp("42", "Game", unit, hash).file
        }
        val (unit, hash) = unit(9)
        val made = (1..12).map {
            clock = clock.plus(Duration.ofMinutes(1))
            backups(keep = 4).backUp("42", "Game", unit, hash)
        }

        assertThat(made.map { it.file }.toSet()).hasSize(1)
        assertThat(made.first().hash).isEqualTo(hash)
        assertThat(SaveHasher.hashFile(made.first().file)).isEqualTo(hash)
        assertThat(backups().list("42")).containsExactly(made.first().file, older[2], older[1], older[0]).inOrder()

        // Raw saves too, and a save that changed since is still refused rather than taken as backed up.
        val raw = backups().backUp("7", "Mario Kart DS", rawUnit(), RAW_SAVE_MD5).file
        clock = clock.plus(Duration.ofMinutes(1))
        assertThat(backups().backUp("7", "Mario Kart DS", rawUnit(), RAW_SAVE_MD5).file).isEqualTo(raw)
        Files.write(rawUnit().file, byteArrayOf(1))
        assertThrows(SaveChangedException::class.java) { backups().backUp("7", "Mario Kart DS", SaveUnit.SingleFile(path("melonDS/Mario Kart DS.sav")), RAW_SAVE_MD5) }
        assertThat(backups().list("7")).containsExactly(raw)
    }
}
