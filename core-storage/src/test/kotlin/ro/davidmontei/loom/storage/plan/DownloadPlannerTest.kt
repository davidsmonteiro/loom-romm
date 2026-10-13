package ro.davidmontei.loom.storage.plan

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import ro.davidmontei.loom.storage.mapping.MappingJson
import ro.davidmontei.loom.storage.mapping.PlatformMapper
import ro.davidmontei.loom.storage.mapping.PlatformMappingEntry
import ro.davidmontei.loom.storage.mapping.PlatformMatch

class DownloadPlannerTest {
    private val mapper = PlatformMapper(MappingJson.shippedDefaults())

    private fun file(id: Int, name: String, path: String, size: Long = 100, sha1: String? = null, md5: String? = null,
                     crc: String? = null, category: String? = null, members: List<ArchiveMember>? = null) =
        RomFileSnapshot(id, name, path, size, crc, md5, sha1, category, members)

    private fun single(name: String, slug: String, f: RomFileSnapshot) =
        RomSnapshot(1, name, name.substringBeforeLast('.'), "roms/$slug/$name", false, false, "2026-10-01T00:00:00+00:00", listOf(f))

    @Test fun singleFilePrefersSha1() {
        val rom = single("Chrono Trigger (USA).sfc", "snes", file(5, "Chrono Trigger (USA).sfc", "roms/snes",
            sha1 = "ABC123", md5 = "def", crc = "1"))
        val plan = DownloadPlanner.plan(rom, mapper.resolve("snes", "snes"))
        assertThat(plan.systemFolder).isEqualTo("snes")
        assertThat(plan.entryStem).isEqualTo("Chrono Trigger (USA)")
        val part = plan.parts.single()
        assertThat(part.relPath).isEqualTo("Chrono Trigger (USA).sfc")
        assertThat(part.check).isEqualTo(PartCheck.Hash("sha1", "abc123"))
        assertThat(part.extract).isFalse()
    }

    @Test fun emptyHashesFallBackInOrderThenToSize() {
        val md5 = DownloadPlanner.plan(single("a.gba", "gba", file(1, "a.gba", "roms/gba", sha1 = "", md5 = "FF")), mapper.resolve("gba", "gba"))
        assertThat(md5.parts.single().check).isEqualTo(PartCheck.Hash("md5", "ff"))
        val crc = DownloadPlanner.plan(single("a.gba", "gba", file(1, "a.gba", "roms/gba", crc = "beef")), mapper.resolve("gba", "gba"))
        assertThat(crc.parts.single().check).isEqualTo(PartCheck.Hash("crc32", "0000beef"))
        val none = DownloadPlanner.plan(single("a.nsp", "switch", file(1, "a.nsp", "roms/switch")), mapper.resolve("switch", "switch"))
        assertThat(none.parts.single().check).isEqualTo(PartCheck.SizeOnly)
    }

    @Test fun zipIsVerifiedByMembersNeverByTheContentHash() {
        val members = listOf(ArchiveMember("Game.nds", 5000, "cafebabe", null, null))
        val rom = single("Game.zip", "nds", file(1, "Game.zip", "roms/nds", sha1 = "f".repeat(40), members = members))
        val nds = DownloadPlanner.plan(rom, mapper.resolve("nds", "nds"))
        assertThat(nds.parts.single().check).isEqualTo(PartCheck.ArchiveMembers(members))
        assertThat(nds.parts.single().extract).isTrue() // nds extracts by default
        assertThat(nds.extractionBytes).isEqualTo(5000)

        val mame = DownloadPlanner.plan(rom.copy(files = listOf(rom.files.single())), mapper.resolve("arcade", "mame"))
        assertThat(mame.parts.single().extract).isFalse()
        assertThat(mame.parts.single().check).isInstanceOf(PartCheck.ArchiveMembers::class.java)
    }

    @Test fun zipWithoutMembersIsCheckedForIntegrityAndRarBySize() {
        val zip = DownloadPlanner.plan(single("a.zip", "snes", file(1, "a.zip", "roms/snes", sha1 = "x")), mapper.resolve("snes", "snes"))
        assertThat(zip.parts.single().check).isEqualTo(PartCheck.ArchiveIntegrity)
        val rar = DownloadPlanner.plan(single("a.rar", "snes", file(1, "a.rar", "roms/snes", sha1 = "x")), mapper.resolve("snes", "snes"))
        assertThat(rar.parts.single().check).isEqualTo(PartCheck.SizeOnly)
    }

    @Test fun folderRomKeepsRelativePathsAndFiltersCategories() {
        val rom = RomSnapshot(
            id = 9, fsName = "Final Fantasy VII (USA)", fsNameNoExt = "Final Fantasy VII (USA)",
            fullPath = "roms/psx/Final Fantasy VII (USA)", hasMultipleFiles = true, hasNestedSingleFile = false,
            updatedAt = "2026-10-01T00:00:00+00:00",
            files = listOf(
                file(1, "FF7 (Disc 1).cue", "roms/psx/Final Fantasy VII (USA)", sha1 = "a"),
                file(2, "FF7 (Disc 1).bin", "roms/psx/Final Fantasy VII (USA)/", sha1 = "b"),
                file(3, "FF7 (Disc 2).cue", "roms/psx/Final Fantasy VII (USA)/Disc 2", category = "game"),
                file(4, "Manual.pdf", "roms/psx/Final Fantasy VII (USA)/manual", category = "manual"),
            ),
        )
        val plan = DownloadPlanner.plan(rom, mapper.resolve("psx", "psx"))
        assertThat(plan.parts.map { it.relPath }).containsExactly("FF7 (Disc 1).cue", "FF7 (Disc 1).bin", "Disc 2/FF7 (Disc 2).cue").inOrder()
        assertThat(plan.entryStem).isEqualTo("Final Fantasy VII (USA)")
        assertThat(plan.totalBytes).isEqualTo(300)

        val withManuals = PlatformMapper(MappingJson.shippedDefaults(),
            listOf(PlatformMappingEntry(PlatformMatch(slug = "psx"), include = listOf("game", "manual"))))
        assertThat(DownloadPlanner.plan(rom, withManuals.resolve("psx", "psx")).parts).hasSize(4)
    }

    @Test fun nothingToDownloadIsExplicit() {
        val rom = single("x.sfc", "snes", file(1, "Manual.pdf", "roms/snes", category = "manual"))
        assertThrows(NothingToDownloadException::class.java) { DownloadPlanner.plan(rom, mapper.resolve("snes", "snes")) }
        assertThrows(NothingToDownloadException::class.java) {
            DownloadPlanner.plan(rom.copy(files = emptyList()), mapper.resolve("snes", "snes"))
        }
    }

    @Test fun fingerprintChangesWithServerFiles() {
        val rom = single("a.sfc", "snes", file(1, "a.sfc", "roms/snes", sha1 = "1"))
        val same = rom.copy()
        val changed = rom.copy(files = listOf(rom.files.single().copy(size = 101)))
        assertThat(DownloadPlanner.fingerprint(rom)).isEqualTo(DownloadPlanner.fingerprint(same))
        assertThat(DownloadPlanner.fingerprint(rom)).isNotEqualTo(DownloadPlanner.fingerprint(changed))
    }
}
