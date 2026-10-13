package ro.davidmontei.loom.storage.plan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiscsTest {
    @Test fun cueReferencesQuotedAndBareFiles() {
        val cue = """
            REM COMMENT "x"
            FILE "Final Fantasy VII (Disc 1) (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
                INDEX 01 00:00:00
            FILE track02.bin BINARY
              TRACK 02 AUDIO
        """.trimIndent()
        assertThat(Discs.referencedBy("Game.cue", cue))
            .containsExactly("Final Fantasy VII (Disc 1) (Track 1).bin", "track02.bin").inOrder()
    }

    @Test fun gdiReferencesTrackColumn() {
        val gdi = """
            3
            1 0 4 2352 track01.bin 0
            2 756 0 2352 "track 02.raw" 0
            3 45000 4 2352 track03.bin 0
        """.trimIndent()
        assertThat(Discs.referencedBy("disc.gdi", gdi)).containsExactly("track01.bin", "track 02.raw", "track03.bin").inOrder()
    }

    @Test fun ccdAndMdsUseCompanionFiles() {
        assertThat(Discs.referencedBy("dir/Game.ccd", null)).containsExactly("Game.img", "Game.sub")
        assertThat(Discs.referencedBy("Game.mds", null)).containsExactly("Game.mdf")
    }

    @Test fun discNumbersFromCommonTags() {
        assertThat(Discs.discNumber("Final Fantasy VII (USA) (Disc 2).cue")).isEqualTo(2)
        assertThat(Discs.discNumber("Game [Disk 3 of 4].chd")).isEqualTo(3)
        assertThat(Discs.discNumber("Game (CD1).iso")).isEqualTo(1)
        assertThat(Discs.discNumber("Game Disc 10.chd")).isEqualTo(10)
        assertThat(Discs.discNumber("Amiga Game (Side B).adf")).isEqualTo(2)
        assertThat(Discs.discNumber("Game (USA).cue")).isNull()
    }

    @Test fun orderIsNumericThenNatural() {
        val names = listOf("G (Disc 10).chd", "G (Disc 2).chd", "G (Disc 1).chd", "Bonus.chd")
        assertThat(Discs.order(names)).containsExactly("G (Disc 1).chd", "G (Disc 2).chd", "G (Disc 10).chd", "Bonus.chd").inOrder()
        assertThat(Discs.order(listOf("track10.cue", "track2.cue"))).containsExactly("track2.cue", "track10.cue").inOrder()
    }

    @Test fun m3uIsUtf8LinesWithTrailingNewlineAndParsesBack() {
        val text = M3u.build(listOf("Final Fantasy VII (Disc 1).cue", "Pokémon (Disc 2).cue"))
        assertThat(text).isEqualTo("Final Fantasy VII (Disc 1).cue\nPokémon (Disc 2).cue\n")
        assertThat(text).doesNotContain("\r")
        assertThat(M3u.parse("﻿#EXTM3U\r\na.cue\r\n\r\nb.cue")).containsExactly("a.cue", "b.cue").inOrder()
    }

    @Test fun junkDetection() {
        assertThat(Discs.isJunkPath("__MACOSX/Game/._a.cue")).isTrue()
        assertThat(Discs.isJunkPath("._Game.cue")).isTrue()
        assertThat(Discs.isJunkPath("Thumbs.db")).isTrue()
        assertThat(Discs.isJunkPath("Game.cue")).isFalse()
        assertThat(Discs.isDocument("readme.TXT")).isTrue()
    }
}
