package ro.davidmontei.loom.storage.plan

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.storage.mapping.MultiDiscLayout

class LayoutDeciderTest {
    private fun files(vararg paths: String) = paths.map { PayloadFile(it, 100) }

    private fun decide(
        stem: String,
        payload: List<PayloadFile>,
        texts: Map<String, String> = emptyMap(),
        layout: MultiDiscLayout = MultiDiscLayout.M3U_DIR,
        folder: String = "psx",
    ) = LayoutDecider.decide(stem, payload, layout, folder) { texts[it] }

    private fun Layout.m3u() = generated.single { it.to.endsWith(".m3u") }.content

    @Test fun singleFileIsTheEntry() {
        val l = decide("Chrono Trigger (USA)", files("Chrono Trigger (USA).sfc"), folder = "snes")
        assertThat(l.kind).isEqualTo(EntryKind.FILE)
        assertThat(l.entryName).isEqualTo("Chrono Trigger (USA).sfc")
        assertThat(l.placements).containsExactly(Placement("Chrono Trigger (USA).sfc", ""))
    }

    /** Found on the Thor: a PS2 7z held "SLUS-20115 (1.00).bin", and that is what Cocoon listed. */
    @Test fun aFileUnpackedAloneTakesTheGamesName() {
        val l = decide("Super Bust-A-Move (USA)", listOf(PayloadFile("SLUS-20115 (1.00).bin", 100, unpacked = true)), folder = "ps2")
        assertThat(l.entryName).isEqualTo("Super Bust-A-Move (USA).bin")
        assertThat(l.placements).containsExactly(Placement("SLUS-20115 (1.00).bin", ""))
    }

    @Test fun aDownloadedFileKeepsRommsNameAndCompoundExtension() {
        val l = decide("celeste", files("celeste.p8.png"), folder = "pico8")
        assertThat(l.entryName).isEqualTo("celeste.p8.png")
    }

    @Test fun nestedSingleFileIsFlattened() {
        val l = decide("Metroid Prime", files("Metroid Prime/Metroid Prime.rvz"), folder = "gc")
        assertThat(l.kind).isEqualTo(EntryKind.FILE)
        assertThat(l.entryName).isEqualTo("Metroid Prime.rvz")
        assertThat(l.placements.single().from).isEqualTo("Metroid Prime/Metroid Prime.rvz")
    }

    @Test fun junkAndDocumentsDoNotTurnASingleFileIntoAFolder() {
        val l = decide("Game", files("Game.iso", "readme.txt", "__MACOSX/._Game.iso", "cover.jpg"), folder = "ps2")
        assertThat(l.kind).isEqualTo(EntryKind.FILE)
        assertThat(l.entryName).isEqualTo("Game.iso")
    }

    @Test fun multiDiscCueBinBecomesM3uFolderInDiscOrder() {
        val payload = files(
            "Final Fantasy VII (USA) (Disc 2).cue", "Final Fantasy VII (USA) (Disc 2).bin",
            "Final Fantasy VII (USA) (Disc 1).cue", "Final Fantasy VII (USA) (Disc 1).bin",
        )
        val texts = mapOf(
            "Final Fantasy VII (USA) (Disc 1).cue" to "FILE \"Final Fantasy VII (USA) (Disc 1).bin\" BINARY\n",
            "Final Fantasy VII (USA) (Disc 2).cue" to "FILE \"Final Fantasy VII (USA) (Disc 2).bin\" BINARY\n",
        )
        val l = decide("Final Fantasy VII (USA)", payload, texts)
        assertThat(l.entryName).isEqualTo("Final Fantasy VII (USA).m3u")
        assertThat(l.kind).isEqualTo(EntryKind.DIRECTORY)
        assertThat(l.isDiscSet).isTrue()
        assertThat(l.needsSubfolderScan).isFalse()
        assertThat(l.m3u()).isEqualTo("Final Fantasy VII (USA) (Disc 1).cue\nFinal Fantasy VII (USA) (Disc 2).cue\n")
        assertThat(l.generated.single().to).isEqualTo("Final Fantasy VII (USA).m3u")
        assertThat(l.placements.map { it.to }).containsExactlyElementsIn(payload.map { it.relPath })
    }

    @Test fun singleDiscWithSeveralTracksStillGetsAPlaylist() {
        val payload = files("Game.cue", "Game (Track 1).bin", "Game (Track 2).bin")
        val texts = mapOf("Game.cue" to "FILE \"Game (Track 1).bin\" BINARY\nFILE \"Game (Track 2).bin\" BINARY\n")
        val l = decide("Game", payload, texts)
        assertThat(l.entryName).isEqualTo("Game.m3u")
        assertThat(l.m3u()).isEqualTo("Game.cue\n")
    }

    @Test fun dreamcastGdiAndTracks() {
        val payload = files("disc.gdi", "track01.bin", "track02.raw", "track03.bin")
        val texts = mapOf("disc.gdi" to "3\n1 0 4 2352 track01.bin 0\n2 756 0 2352 track02.raw 0\n3 45000 4 2352 track03.bin 0\n")
        val l = decide("Sonic Adventure (USA)", payload, texts, folder = "dreamcast")
        assertThat(l.entryName).isEqualTo("Sonic Adventure (USA).m3u")
        assertThat(l.m3u()).isEqualTo("disc.gdi\n")
    }

    @Test fun chdSetOrdersNaturally() {
        val l = decide("Riven", files("Riven (Disc 10).chd", "Riven (Disc 2).chd", "Riven (Disc 1).chd"), folder = "psx")
        assertThat(M3u.parse(l.m3u())).containsExactly("Riven (Disc 1).chd", "Riven (Disc 2).chd", "Riven (Disc 10).chd").inOrder()
    }

    @Test fun shippedPlaylistIsReusedWhenItResolves() {
        val payload = files("Game (Disc 1).chd", "Game (Disc 2).chd", "Game Collection.m3u")
        val texts = mapOf("Game Collection.m3u" to "#EXTM3U\nGame (Disc 2).chd\ngame (disc 1).chd\n")
        val l = decide("Game", payload, texts)
        assertThat(l.m3u()).isEqualTo("Game (Disc 2).chd\nGame (Disc 1).chd\n")
        assertThat(l.placements.map { it.to }).doesNotContain("Game Collection.m3u")
    }

    @Test fun brokenShippedPlaylistIsRegenerated() {
        val payload = files("Game (Disc 1).chd", "Game (Disc 2).chd", "Game.m3u")
        val texts = mapOf("Game.m3u" to "Game (Disc 1).cue\nGame (Disc 2).cue\n")
        val l = decide("Game", payload, texts)
        assertThat(l.m3u()).isEqualTo("Game (Disc 1).chd\nGame (Disc 2).chd\n")
    }

    @Test fun renamedTracksAreRewrittenInTheCue() {
        val payload = files("Game: Part 1.cue", "Game: Part 1.bin")
        val texts = mapOf("Game: Part 1.cue" to "FILE \"Game: Part 1.bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        val l = decide("Game: Part 1", payload, texts)
        assertThat(l.entryName).isEqualTo("Game - Part 1.m3u")
        val cue = l.generated.single { it.to.endsWith(".cue") }
        assertThat(cue.to).isEqualTo("Game - Part 1.cue")
        assertThat(cue.content).contains("FILE \"Game - Part 1.bin\" BINARY")
        assertThat(l.placements.map { it.from }).doesNotContain("Game: Part 1.cue") // replaced by the rewrite
        assertThat(l.m3u()).isEqualTo("Game - Part 1.cue\n")
    }

    @Test fun folderLayoutKeepsPlaylistInsidePlainFolder() {
        val l = decide("Riven", files("Riven (Disc 1).chd", "Riven (Disc 2).chd"), layout = MultiDiscLayout.FOLDER)
        assertThat(l.entryName).isEqualTo("Riven")
        assertThat(l.needsSubfolderScan).isTrue()
        assertThat(l.generated.single().to).isEqualTo("Riven.m3u")
    }

    @Test fun nonDiscFolderGameIsMirrored() {
        val payload = files("PS3_GAME/USRDIR/EBOOT.BIN", "PS3_GAME/PARAM.SFO", "PS3_DISC.SFB")
        val l = decide("Demon's Souls (USA)", payload, folder = "ps3")
        assertThat(l.kind).isEqualTo(EntryKind.DIRECTORY)
        assertThat(l.entryName).isEqualTo("Demon's Souls (USA)")
        assertThat(l.isDiscSet).isFalse()
        assertThat(l.needsSubfolderScan).isTrue()
        assertThat(l.placements.map { it.to }).containsExactly("PS3_GAME/USRDIR/EBOOT.BIN", "PS3_GAME/PARAM.SFO", "PS3_DISC.SFB")
        assertThat(l.generated).isEmpty()
    }

    @Test fun archiveWrapperFolderIsCollapsed() {
        val payload = files("Game (USA)/Game (USA).cue", "Game (USA)/Game (USA).bin")
        val texts = mapOf("Game (USA)/Game (USA).cue" to "FILE \"Game (USA).bin\" BINARY\n")
        val l = decide("Game (USA)", payload, texts)
        assertThat(l.entryName).isEqualTo("Game (USA).m3u")
        assertThat(l.placements).contains(Placement("Game (USA)/Game (USA).bin", "Game (USA).bin"))
        assertThat(l.m3u()).isEqualTo("Game (USA).cue\n")
    }

    @Test fun loneBinOnPsxIsWrappedBecauseCocoonHidesIt() {
        val l = decide("Crash Bandicoot (USA)", files("Crash Bandicoot (USA).bin"), folder = "psx")
        assertThat(l.entryName).isEqualTo("Crash Bandicoot (USA).m3u")
        assertThat(l.m3u()).isEqualTo("Crash Bandicoot (USA).bin\n")
        // …but a .bin cartridge dump elsewhere stays a plain file.
        assertThat(decide("Sonic", files("Sonic.bin"), folder = "genesis").kind).isEqualTo(EntryKind.FILE)
    }

    @Test fun nameCollisionsInsideTheEntryAreDeduplicated() {
        val l = decide("Pack", files("a/Game?.dat", "a/Game*.dat", "b.cfg"), folder = "dos")
        assertThat(l.placements.map { it.to }).containsExactly("a/Game_.dat", "a/Game__2.dat", "b.cfg")
    }

    @Test fun leadingDotEntryNamesAreMadeVisible() {
        val l = decide(".hack Infection", files(".hack Infection.iso"), folder = "ps2")
        assertThat(l.entryName).isEqualTo("hack Infection.iso")
    }
}
