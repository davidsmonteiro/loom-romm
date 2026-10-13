package ro.davidmontei.loom.storage.plan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameSanitizerTest {
    private fun s(name: String) = NameSanitizer.sanitize(name)

    @Test fun keepsOrdinaryRomNamesUntouched() {
        assertThat(s("Chrono Trigger (USA).sfc")).isEqualTo("Chrono Trigger (USA).sfc")
        assertThat(s("Pokémon - Emerald Version (USA, Europe) [!].gba")).isEqualTo("Pokémon - Emerald Version (USA, Europe) [!].gba")
        assertThat(s("Tom & Jerry #2 50%.nes")).isEqualTo("Tom & Jerry #2 50%.nes")
    }

    @Test fun replacesCharactersExfatRejects() {
        assertThat(s("Zelda: A Link to the Past.sfc")).isEqualTo("Zelda - A Link to the Past.sfc")
        assertThat(s("What?/Why*<>|\".iso")).isEqualTo("What__Why_____.iso")
        assertThat(s("back\\slash.bin")).isEqualTo("back_slash.bin")
    }

    @Test fun stripsLeadingDotsSoCocoonDoesNotHideTheFile() {
        assertThat(s(".hack Infection.iso")).isEqualTo("hack Infection.iso")
        assertThat(s("...dots")).isEqualTo("dots")
    }

    @Test fun trimsTrailingDotsAndSpacesAndControlCharacters() {
        assertThat(s("Game.  ")).isEqualTo("Game")
        assertThat(s("Bad\u0000Name\u0007.nes")).isEqualTo("BadName.nes")
        assertThat(s("  Many    spaces  .sfc")).isEqualTo("Many spaces .sfc")
    }

    @Test fun reservedDosNamesAreEscaped() {
        assertThat(s("CON")).isEqualTo("CON_")
        assertThat(s("aux.txt")).isEqualTo("aux_.txt")
        assertThat(s("Console.iso")).isEqualTo("Console.iso")
    }

    @Test fun normalisesToNfc() {
        val decomposed = "Pokémon.gb"
        assertThat(s(decomposed)).isEqualTo("Pokémon.gb")
    }

    @Test fun emptyResultFallsBack() {
        assertThat(NameSanitizer.sanitize("...", fallback = "rom-7")).isEqualTo("rom-7")
        assertThat(NameSanitizer.sanitize("\u0001")).isEqualTo("untitled")
    }

    @Test fun longNamesFitInBytesKeepTheExtensionAndWholeCharacters() {
        val long = "Ж".repeat(200) + ".chd" // 2 bytes per letter
        val out = s(long)
        assertThat(out.toByteArray().size).isAtMost(NameSanitizer.MAX_BYTES)
        assertThat(out).endsWith(".chd")
        assertThat(out.dropLast(4).all { it == 'Ж' }).isTrue()

        val emoji = "🎮".repeat(100) + ".iso"
        val e = s(emoji)
        assertThat(e.toByteArray().size).isAtMost(NameSanitizer.MAX_BYTES)
        assertThat(e.codePoints().allMatch { it == 0x1F3AE || it.toChar() in ".iso" }).isTrue()
    }

    @Test fun pathsAreSanitisedPerComponentAndTraversalDropped() {
        assertThat(NameSanitizer.sanitizePath("Disc 1/Game: One.cue")).isEqualTo("Disc 1/Game - One.cue")
        assertThat(NameSanitizer.sanitizePath("../../etc/passwd")).isEqualTo("etc/passwd")
        assertThat(NameSanitizer.sanitizePath("a\\b\\.c")).isEqualTo("a/b/c")
    }

    @Test fun keyIsCaseInsensitiveAndNormalised() {
        assertThat(NameSanitizer.key("SNES/Game.SFC")).isEqualTo(NameSanitizer.key("snes/game.sfc"))
        assertThat(NameSanitizer.key("Straße")).isEqualTo(NameSanitizer.key("STRASSE"))
        assertThat(NameSanitizer.key("Pokémon")).isEqualTo(NameSanitizer.key("POKÉMON"))
    }

    @Test fun collisionSuffixGoesBeforeExtension() {
        assertThat(NameSanitizer.withSuffix("Game.sfc", 42, isDirectory = false)).isEqualTo("Game (romm-42).sfc")
        assertThat(NameSanitizer.withSuffix("Final Fantasy VII.m3u", 7, isDirectory = true)).isEqualTo("Final Fantasy VII (romm-7).m3u")
        assertThat(NameSanitizer.withSuffix("Game Folder", 7, isDirectory = true)).isEqualTo("Game Folder (romm-7)")
    }
}
