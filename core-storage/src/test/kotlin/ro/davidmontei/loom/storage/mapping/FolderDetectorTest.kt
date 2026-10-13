package ro.davidmontei.loom.storage.mapping

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ro.davidmontei.loom.storage.mapping.FolderDecision.Why

class FolderDetectorTest {
    private val mapper = PlatformMapper(MappingJson.shippedDefaults())
    private val arcade = mapper.resolve("arcade", "arcade") // mame, then arcade, fbneo, fba
    private val genesis = mapper.resolve("genesis", "genesis") // genesis, then megadrive

    private fun folder(name: String, games: Int = 0) = SystemFolder(name, games)

    /** The AYN Thor's ES-DE template: every system folder exists, empty. */
    private val esdeTemplate = listOf("arcade", "mame", "fbneo", "fba", "genesis", "megadrive", "snes", "psx").map { folder(it) }

    @Test fun theUsersChoiceStands() {
        val chosen = PlatformMapper(MappingJson.shippedDefaults(), listOf(PlatformMappingEntry(PlatformMatch(slug = "arcade"), folder = "arcade")))
            .resolve("arcade", "arcade")
        val decision = FolderDetector.decide(chosen, listOf(folder("arcade"), folder("mame", games = 40)))
        assertThat(decision).isEqualTo(FolderDecision.Use("arcade", Why.CHOSEN))
    }

    @Test fun theOnlyCandidateHoldingGamesIsTheOne() {
        val card = esdeTemplate.map { if (it.name == "arcade") it.copy(games = 3) else it }
        assertThat(FolderDetector.decide(arcade, card)).isEqualTo(FolderDecision.Use("arcade", Why.HAS_GAMES))
    }

    @Test fun theOnlyCandidateOnTheCardIsTheOne() {
        assertThat(FolderDetector.decide(genesis, listOf(folder("megadrive"), folder("snes"))))
            .isEqualTo(FolderDecision.Use("megadrive", Why.ONLY_FOLDER))
    }

    @Test fun withNothingOnTheCardTheDefaultIsCreated() {
        assertThat(FolderDetector.decide(genesis, listOf(folder("snes", games = 9))))
            .isEqualTo(FolderDecision.Use("genesis", Why.NEW_FOLDER))
    }

    /** What the Thor's card looks like: arcade, mame, fbneo and fba all exist, all empty. */
    @Test fun severalEmptyCandidatesMeanAsking() {
        val decision = FolderDetector.decide(arcade, esdeTemplate) as FolderDecision.Ask
        assertThat(decision.options.map { it.name }).containsExactly("mame", "arcade", "fbneo", "fba").inOrder()
        assertThat(decision.suggested).isEqualTo("mame")
    }

    @Test fun gamesInMoreThanOneCandidateMeanAskingWithThoseFirst() {
        val card = listOf(folder("genesis"), folder("megadrive", games = 5))
        assertThat(FolderDetector.decide(genesis, card)).isEqualTo(FolderDecision.Use("megadrive", Why.HAS_GAMES))
        val both = listOf(folder("genesis", games = 2), folder("megadrive", games = 5))
        val decision = FolderDetector.decide(genesis, both) as FolderDecision.Ask
        assertThat(decision.options.map { it.name }).containsExactly("genesis", "megadrive").inOrder()
    }

    @Test fun folderNamesMatchWhateverTheirCase() {
        assertThat(FolderDetector.decide(arcade, listOf(folder("MAME"))))
            .isEqualTo(FolderDecision.Use("MAME", Why.ONLY_FOLDER)) // the card's own spelling
    }
}
