package ro.davidmontei.loom.storage.mapping

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

class PlatformMappingTest {
    private val shipped = MappingJson.shippedDefaults()

    private fun resourceLines(name: String) = javaClass.getResource(name)!!.readText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    // --- the shipped defaults, checked against the real vocabularies of both sides ---

    @Test fun everyDefaultSlugIsARealRommSlug() {
        val rommSlugs = resourceLines("/romm-5.3.1-platform-slugs.txt")
        shipped.platforms.mapNotNull { it.match.slug }.forEach { slug ->
            assertWithMessage("RomM 5.3.1 has platform slug '$slug'").that(rommSlugs).contains(slug)
        }
    }

    @Test fun everyDefaultFolderIsARealCocoonFolder() {
        val cocoon = resourceLines("/cocoon-platform-folders.txt")
        shipped.platforms.forEach { entry ->
            assertWithMessage("Cocoon has a '${entry.folder}' folder (${entry.match})").that(cocoon).contains(entry.folder)
        }
    }

    @Test fun defaultsHaveNoDuplicateMatches() {
        val matches = shipped.platforms.map { it.match }
        assertThat(matches).containsNoDuplicates()
    }

    // --- resolution ---

    @Test fun slugMapsToCocoonFolder() {
        val mapper = PlatformMapper(shipped)
        assertThat(mapper.resolve("ngc", "gc").folder).isEqualTo("gc")
        assertThat(mapper.resolve("3ds", "3ds").folder).isEqualTo("n3ds")
        assertThat(mapper.resolve("sms", "mastersystem").folder).isEqualTo("mastersystem")
        assertThat(mapper.resolve("dc", "dc").folder).isEqualTo("dreamcast")
        assertThat(mapper.resolve("snes", "snes").source).isEqualTo(MappingSource.DEFAULT)
    }

    @Test fun picoCollisionGoesTheRightWay() {
        val mapper = PlatformMapper(shipped)
        assertThat(mapper.resolve("pico", "pico8").folder).isEqualTo("pico8") // RomM pico = PICO-8
        assertThat(mapper.resolve("sega-pico", "segapico").folder).isEqualTo("pico") // Cocoon pico = Sega Pico
    }

    @Test fun arcadeIsSplitByServerFolder() {
        val mapper = PlatformMapper(shipped)
        assertThat(mapper.resolve("arcade", "fbneo").folder).isEqualTo("fbneo")
        assertThat(mapper.resolve("arcade", "naomi").folder).isEqualTo("naomi")
        assertThat(mapper.resolve("arcade", "arcade").folder).isEqualTo("mame")
        assertThat(mapper.resolve("arcade", "fbneo").archive).isEqualTo(ArchivePolicy.KEEP)
    }

    @Test fun discSystemsExtractByDefault() {
        val mapper = PlatformMapper(shipped)
        assertThat(mapper.resolve("psx", "psx").archive).isEqualTo(ArchivePolicy.EXTRACT)
        assertThat(mapper.resolve("psx", "psx").multiDisc).isEqualTo(MultiDiscLayout.M3U_DIR)
        assertThat(mapper.resolve("snes", "snes").archive).isEqualTo(ArchivePolicy.KEEP)
    }

    @Test fun unknownPlatformFallsBackToServerFolderAndSaysSo() {
        val resolved = PlatformMapper(shipped).resolve("hyperscan", "hyperscan")
        assertThat(resolved.folder).isEqualTo("hyperscan")
        assertThat(resolved.source).isEqualTo(MappingSource.SERVER_FOLDER)
        assertThat(resolved.include).containsExactly("game")
    }

    @Test fun userOverrideWinsPerFieldAndInheritsTheRest() {
        val overrides = listOf(PlatformMappingEntry(PlatformMatch(slug = "psx"), folder = "ps1"))
        val resolved = PlatformMapper(shipped, overrides).resolve("psx", "psx")
        assertThat(resolved.folder).isEqualTo("ps1")
        assertThat(resolved.archive).isEqualTo(ArchivePolicy.EXTRACT) // inherited from the default row
        assertThat(resolved.source).isEqualTo(MappingSource.USER)
    }

    @Test fun overrideByServerFolderBeatsOverrideBySlug() {
        val overrides = listOf(
            PlatformMappingEntry(PlatformMatch(slug = "arcade"), folder = "arcade"),
            PlatformMappingEntry(PlatformMatch(fsSlug = "fbneo"), folder = "fba"),
        )
        val mapper = PlatformMapper(shipped, overrides)
        assertThat(mapper.resolve("arcade", "fbneo").folder).isEqualTo("fba")
        // Any user override outranks every shipped row, even the more specific fsSlug ones.
        assertThat(mapper.resolve("arcade", "mame").folder).isEqualTo("arcade")
        assertThat(mapper.resolve("arcade", "naomi").folder).isEqualTo("arcade")
    }

    @Test fun includeCategoriesControlWhatDownloads() {
        val overrides = listOf(PlatformMappingEntry(PlatformMatch(slug = "switch"), include = listOf("game", "update", "dlc")))
        val sw = PlatformMapper(shipped, overrides).resolve("switch", "switch")
        assertThat(sw.includes(null)).isTrue()
        assertThat(sw.includes("update")).isTrue()
        assertThat(sw.includes("manual")).isFalse()
        val snes = PlatformMapper(shipped).resolve("snes", "snes")
        assertThat(snes.includes("game")).isTrue()
        assertThat(snes.includes("dlc")).isFalse()
    }

    // --- import / export ---

    @Test fun exportFoldsOverridesIntoDefaultsAndRoundTrips() {
        val overrides = listOf(
            PlatformMappingEntry(PlatformMatch(slug = "psx"), folder = "ps1"),
            PlatformMappingEntry(PlatformMatch(slug = "hyperscan"), folder = "hyperscan", archive = ArchivePolicy.EXTRACT),
        )
        val exported = PlatformMapper(shipped, overrides).export()
        val text = MappingJson.encode(exported)
        val back = MappingJson.parse(text)
        assertThat(back.schemaVersion).isEqualTo(1)
        assertThat(back.platforms.first { it.match.slug == "psx" }.folder).isEqualTo("ps1")
        assertThat(back.platforms.first { it.match.slug == "psx" }.archive).isEqualTo(ArchivePolicy.EXTRACT)
        assertThat(back.platforms.last().match.slug).isEqualTo("hyperscan")
        assertThat(text).contains("\"archive\": \"extract\"")
    }

    @Test fun importRejectsUnsafeFolders() {
        val bad = """{"schemaVersion":1,"platforms":[{"match":{"slug":"snes"},"folder":"../Android"}]}"""
        assertThrows(InvalidMappingException::class.java) { MappingJson.parse(bad) }
        val hidden = """{"schemaVersion":1,"platforms":[{"match":{"slug":"snes"},"folder":".snes"}]}"""
        assertThrows(InvalidMappingException::class.java) { MappingJson.parse(hidden) }
    }

    @Test fun importRejectsUnknownCategoryAndFutureVersions() {
        assertThrows(InvalidMappingException::class.java) {
            MappingJson.parse("""{"platforms":[{"match":{"slug":"snes"},"include":["roms"]}]}""")
        }
        assertThrows(InvalidMappingException::class.java) { MappingJson.parse("""{"schemaVersion":2}""") }
        assertThrows(InvalidMappingException::class.java) { MappingJson.parse("not json") }
        assertThrows(InvalidMappingException::class.java) {
            MappingJson.parse("""{"platforms":[{"match":{},"folder":"x"}]}""")
        }
    }

    @Test fun importIgnoresUnknownFieldsForwardCompat() {
        val file = MappingJson.parse("""{"schemaVersion":1,"platforms":[{"match":{"slug":"snes"},"folder":"sfc","colour":"red"}]}""")
        assertThat(file.platforms.single().folder).isEqualTo("sfc")
    }

    @Test fun folderValidation() {
        assertThat(FolderNames.validate("snes")).isNull()
        assertThat(FolderNames.validate("sg-1000")).isNull()
        assertThat(FolderNames.validate("a/b")).isNotNull()
        assertThat(FolderNames.validate("")).isNotNull()
        assertThat(FolderNames.validate("x:y")).isNotNull()
        assertThat(FolderNames.fromServer(".hidden")).isEqualTo("hidden")
    }
}
