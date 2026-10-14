package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.davidmontei.loom.storage.saves.SaveFixtures.folderOf
import ro.davidmontei.loom.storage.saves.SaveFixtures.zipOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class EdenSaveLocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val profile = "4E431822D6DEFD527268BA35B5C09533"
    private val wonder = SwitchTitleId(0x010015100B514000L)
    private val arceus = SwitchTitleId(0x01001F5010DFA000L)
    private val animalCrossing = SwitchTitleId(0x01006F8002326000L)

    /** `ROMs/saves/Eden` on the Thor: one profile, holding Wonder's save. */
    private fun thorEden(): Path {
        val eden = tmp.newFolder("ROMs", "saves", "Eden").toPath()
        folderOf(
            users(eden).resolve("$profile/$wonder"),
            "game_data.sav" to ByteArray(512) { 1 }, "game_sub_data.sav" to ByteArray(64) { 2 },
            "game_account.sav" to ByteArray(32) { 3 }, "option_data.sav" to ByteArray(16) { 4 },
        )
        Files.createDirectories(eden.resolve("system/save/8000000000000010"))
        return eden
    }

    private fun users(eden: Path) = eden.resolve("user/save/0000000000000000")

    @Test fun findsAGamesSaveInTheOnlyProfile() {
        val eden = thorEden()
        val found = EdenSaveLocator(eden).locate(wonder, chosenProfile = null) as EdenSaveLocation.Found
        assertThat(found.unit.name).isEqualTo("010015100B514000")
        assertThat(found.unit.folder).isEqualTo(users(eden).resolve("$profile/010015100B514000"))
        assertThat(SaveZipper.entries(listOf(found.unit)).map { it.name }).containsExactly(
            "010015100B514000/game_account.sav", "010015100B514000/game_data.sav",
            "010015100B514000/game_sub_data.sav", "010015100B514000/option_data.sav",
        ).inOrder()
    }

    /** No save yet on this device: the folder a restore would create, where Eden looks. */
    @Test fun pointsWhereANewSaveGoes() {
        val eden = thorEden()
        val found = EdenSaveLocator(eden).locate(arceus, chosenProfile = null) as EdenSaveLocation.Found
        assertThat(found.unit.folder).isEqualTo(users(eden).resolve("$profile/01001F5010DFA000"))
        assertThat(Files.exists(found.unit.folder)).isFalse()
    }

    /**
     * Argosy zips a Switch save under the title folder's own name, with folder entries; Loom's
     * archive has the same root, so either one's upload restores in the other.
     */
    @Test fun anArgosyArchiveRestoresIntoTheTitleFolder() {
        val eden = thorEden()
        val unit = (EdenSaveLocator(eden).locate(wonder, null) as EdenSaveLocation.Found).unit
        val argosy = zipOf(tmp.root.toPath().resolve("argosy.zip"),
            "010015100B514000/" to null, "010015100B514000/game_data.sav" to ByteArray(512) { 9 },
            "010015100B514000/option_data.sav" to ByteArray(16) { 8 })
        val restored = SaveRestorer().restore(argosy, listOf(unit), SaveHasher.hashUnit(listOf(unit)))
        assertThat(restored).isEqualTo(SaveHasher.hashZip(argosy))
        assertThat(Files.readAllBytes(unit.folder.resolve("game_data.sav"))).isEqualTo(ByteArray(512) { 9 })
        assertThat(Files.exists(unit.folder.resolve("game_sub_data.sav"))).isFalse()
    }

    @Test fun ofSeveralProfilesOnlyTheChosenOneCounts() {
        val eden = thorEden()
        val other = "0123456789ABCDEF0123456789ABCDEF"
        Files.createDirectories(users(eden).resolve(other))
        val locator = EdenSaveLocator(eden)
        assertThat(locator.profiles()).containsExactly(other, profile).inOrder()
        assertThat(locator.locate(wonder, chosenProfile = null)).isEqualTo(EdenSaveLocation.ChooseProfile(listOf(other, profile)))
        assertThat(locator.locate(wonder, chosenProfile = "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")).isInstanceOf(EdenSaveLocation.ChooseProfile::class.java)
        val chosen = locator.locate(wonder, chosenProfile = other) as EdenSaveLocation.Found
        assertThat(chosen.unit.folder).isEqualTo(users(eden).resolve("$other/010015100B514000"))
        val mine = locator.locate(wonder, chosenProfile = profile.lowercase()) as EdenSaveLocation.Found
        assertThat(mine.unit.folder).isEqualTo(users(eden).resolve("$profile/010015100B514000"))
    }

    /** Profiles as the owner can tell them apart: how many saves each holds and when one last changed, the latest first. */
    @Test fun profilesAreSummarisedLatestFirst() {
        val eden = thorEden()
        val other = "0123456789ABCDEF0123456789ABCDEF"
        folderOf(users(eden).resolve("$other/$arceus"), "main" to ByteArray(10))
        folderOf(users(eden).resolve("$other/0100AAAA0000B000"), "main" to ByteArray(10))
        Files.createDirectories(users(eden).resolve("${"0".repeat(32)}/0100AAAA0000C000"))
        fun touch(dir: Path, at: String) = Files.walk(dir).use { w ->
            w.filter(Files::isRegularFile).forEach { Files.setLastModifiedTime(it, FileTime.from(Instant.parse(at))) }
        }
        touch(users(eden).resolve(profile), "2026-10-14T12:00:00Z")
        touch(users(eden).resolve(other), "2026-09-01T12:00:00Z")
        assertThat(EdenSaveLocator(eden).profileSummaries()).containsExactly(
            EdenProfile(profile, 1, Instant.parse("2026-10-14T12:00:00Z")),
            EdenProfile(other, 2, Instant.parse("2026-09-01T12:00:00Z")),
        ).inOrder()
    }

    /** Eden keeps saves made per console under an all-zeros user: that's no profile to choose. */
    @Test fun deviceSavesAreNotAProfile() {
        val eden = thorEden()
        val zeros = "0".repeat(32)
        folderOf(users(eden).resolve("$zeros/0100AAAA0000B000"), "save.bin" to byteArrayOf(1))
        val locator = EdenSaveLocator(eden)
        assertThat(locator.profiles()).containsExactly(profile)
        val device = locator.locate(SwitchTitleId.parse("0100AAAA0000B000")!!, null) as EdenSaveLocation.Found
        assertThat(device.unit.folder).isEqualTo(users(eden).resolve("$zeros/0100AAAA0000B000"))
        // A game known to save per console goes there before its first save.
        val acnh = locator.locate(animalCrossing, null) as EdenSaveLocation.Found
        assertThat(acnh.unit.folder).isEqualTo(users(eden).resolve("$zeros/01006F8002326000"))
        // A save already in the profile stays the one.
        Files.createDirectories(users(eden).resolve("$profile/01006F8002326000"))
        assertThat((locator.locate(animalCrossing, null) as EdenSaveLocation.Found).unit.folder)
            .isEqualTo(users(eden).resolve("$profile/01006F8002326000"))
    }

    /**
     * A game synced from the console-wide folder that then writes a profile save isn't switched to
     * it (nor back): the two hold different saves. Once the synced folder is gone, the other is it.
     */
    @Test fun aGameSyncedFromOneFolderIsNeverSwitchedToTheOther() {
        val eden = thorEden()
        val zeros = "0".repeat(32)
        val device = users(eden).resolve("$zeros/$arceus")
        val own = users(eden).resolve("$profile/$arceus")
        folderOf(device, "device.bin" to byteArrayOf(1))
        val locator = EdenSaveLocator(eden)
        assertThat((locator.locate(arceus, null, synced = device) as EdenSaveLocation.Found).unit.folder).isEqualTo(device)

        folderOf(own, "user.bin" to byteArrayOf(2)) // the game now saves per user too
        assertThat(locator.locate(arceus, null, synced = device)).isEqualTo(EdenSaveLocation.Moved(device, own))
        assertThat((locator.locate(arceus, null) as EdenSaveLocation.Found).unit.folder).isEqualTo(own) // never synced: as before
        assertThat((locator.locate(arceus, null, synced = own) as EdenSaveLocation.Found).unit.folder).isEqualTo(own)
        assertThat(locator.isConsoleWide(device)).isTrue()
        assertThat(locator.isConsoleWide(own)).isFalse()

        device.toFile().deleteRecursively() // the synced folder is gone: nothing to mix up
        assertThat((locator.locate(arceus, null, synced = device) as EdenSaveLocation.Found).unit.folder).isEqualTo(own)
    }

    /**
     * With the save in both folders, the owner's pick of one stands: Loom syncs it and no longer
     * stops on the other. A pick that is neither of the two (another title's, another profile's) is no pick.
     */
    @Test fun theFolderTheOwnerPickedOfTheTwoIsTheOne() {
        val eden = thorEden()
        val zeros = "0".repeat(32)
        val device = users(eden).resolve("$zeros/$arceus")
        val own = users(eden).resolve("$profile/$arceus")
        folderOf(device, "device.bin" to byteArrayOf(1))
        folderOf(own, "user.bin" to byteArrayOf(2))
        val locator = EdenSaveLocator(eden)
        assertThat(locator.locate(arceus, null, synced = device)).isEqualTo(EdenSaveLocation.Moved(device, own))
        assertThat(locator.bothFolders(arceus, null)).containsExactly(device, own).inOrder()

        assertThat((locator.locate(arceus, null, synced = device, pinned = device) as EdenSaveLocation.Found).unit)
            .isEqualTo(SaveRoot(arceus.toString(), device))
        assertThat((locator.locate(arceus, null, synced = device, pinned = own) as EdenSaveLocation.Found).unit)
            .isEqualTo(SaveRoot(arceus.toString(), own))
        assertThat((locator.locate(arceus, null, synced = own, pinned = device) as EdenSaveLocation.Found).unit.folder).isEqualTo(device)

        val elsewhere = users(eden).resolve("$profile/$wonder")
        assertThat(locator.locate(arceus, null, synced = device, pinned = elsewhere)).isEqualTo(EdenSaveLocation.Moved(device, own))
        assertThat(Files.exists(device) && Files.exists(own)).isTrue() // looking moves nothing
    }

    /** Several profiles and none chosen: only the console-wide folder can be named. */
    @Test fun withoutAProfileOnlyTheConsoleWideFolderIsKnown() {
        val eden = thorEden()
        Files.createDirectories(users(eden).resolve("A".repeat(32)))
        assertThat(EdenSaveLocator(eden).bothFolders(arceus, null)).containsExactly(users(eden).resolve("${"0".repeat(32)}/$arceus"))
        assertThat(EdenSaveLocator(eden).bothFolders(arceus, profile)).hasSize(2)
    }

    @Test fun withoutEdensSaveFolderThereIsNoPlaceForSaves() {
        val empty = tmp.newFolder("Eden").toPath()
        assertThat(EdenSaveLocator(empty).locate(wonder, null)).isEqualTo(EdenSaveLocation.NoSaveFolder)
        assertThat(EdenSaveLocator.holdsEdenData(empty)).isFalse()
        Files.createDirectories(empty.resolve("user/save"))
        assertThat(EdenSaveLocator.holdsEdenData(empty)).isTrue()
        assertThat(EdenSaveLocator(empty).locate(wonder, null)).isEqualTo(EdenSaveLocation.NoProfile)
    }

    /** What the owner chooses from when Loom can't read a game's title ID: the profile's folders and the device saves, newest first. */
    @Test fun listsTheTitleFoldersNewestFirst() {
        val eden = thorEden()
        val zeros = "0".repeat(32)
        folderOf(users(eden).resolve("$profile/$arceus"), "main" to ByteArray(100))
        folderOf(users(eden).resolve("$zeros/0100AAAA0000B000"), "save.bin" to ByteArray(10))
        Files.createDirectories(users(eden).resolve("$profile/not-a-title"))
        fun touch(dir: Path, at: String) = Files.walk(dir).use { w ->
            w.filter(Files::isRegularFile).forEach { Files.setLastModifiedTime(it, FileTime.from(Instant.parse(at))) }
        }
        touch(users(eden).resolve("$profile/$wonder"), "2026-10-14T10:00:00Z")
        touch(users(eden).resolve("$profile/$arceus"), "2026-10-14T12:00:00Z")
        touch(users(eden).resolve("$zeros/0100AAAA0000B000"), "2026-10-01T12:00:00Z")
        val folders = EdenSaveLocator(eden).titleFolders(profile)
        assertThat(folders.map { it.titleId.toString() }).containsExactly("01001F5010DFA000", "010015100B514000", "0100AAAA0000B000").inOrder()
        assertThat(folders[1].fileCount).isEqualTo(4)
        assertThat(folders[1].bytes).isEqualTo(512L + 64 + 32 + 16)
        assertThat(folders[0].newestModified).isEqualTo(Instant.parse("2026-10-14T12:00:00Z"))
    }
}
