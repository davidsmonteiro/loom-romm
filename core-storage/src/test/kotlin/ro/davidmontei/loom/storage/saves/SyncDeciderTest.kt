package ro.davidmontei.loom.storage.saves

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ro.davidmontei.loom.storage.saves.NegotiatedAction.CONFLICT
import ro.davidmontei.loom.storage.saves.NegotiatedAction.DELETE
import ro.davidmontei.loom.storage.saves.NegotiatedAction.DOWNLOAD
import ro.davidmontei.loom.storage.saves.NegotiatedAction.NO_OP
import ro.davidmontei.loom.storage.saves.NegotiatedAction.OTHER
import ro.davidmontei.loom.storage.saves.NegotiatedAction.UPLOAD
import ro.davidmontei.loom.storage.saves.SyncDecision.BOTH_CHANGED
import ro.davidmontei.loom.storage.saves.SyncDecision.DISAGREES_WITH_SERVER
import ro.davidmontei.loom.storage.saves.SyncDecision.FIRST_SYNC_DIFFERS
import ro.davidmontei.loom.storage.saves.SyncDecision.FOREIGN_EMULATOR
import ro.davidmontei.loom.storage.saves.SyncDecision.IN_SYNC
import ro.davidmontei.loom.storage.saves.SyncDecision.LOCAL_CHANGED
import ro.davidmontei.loom.storage.saves.SyncDecision.LOCAL_MISSING
import ro.davidmontei.loom.storage.saves.SyncDecision.NEW_ON_DEVICE
import ro.davidmontei.loom.storage.saves.SyncDecision.NEW_ON_SERVER
import ro.davidmontei.loom.storage.saves.SyncDecision.NOTHING_TO_SYNC
import ro.davidmontei.loom.storage.saves.SyncDecision.SAME_CONTENT
import ro.davidmontei.loom.storage.saves.SyncDecision.SERVER_CHANGED
import ro.davidmontei.loom.storage.saves.SyncDecision.SERVER_DELETED
import ro.davidmontei.loom.storage.saves.SyncDecision.SERVER_ROLLED_BACK
import ro.davidmontei.loom.storage.saves.SyncDecision.SERVER_SAYS_CONFLICT
import ro.davidmontei.loom.storage.saves.SyncDecision.SERVER_SLOT_GONE
import ro.davidmontei.loom.storage.saves.SyncDecision.SERVER_WENT_BACK
import java.time.Duration
import java.time.Instant

/**
 * Loom's sync rule, case by case. Hashes: h0 is what both sides held at the last sync, h1 a newer
 * save made on the Thor, h2 a newer one made elsewhere. The letters (a)-(f) are the design's
 * conflict sources; "trap" marks a RomM 5.3.1 negotiate answer that would lose or loop a save.
 */
class SyncDeciderTest {
    private val t0 = Instant.parse("2026-10-01T10:00:00Z")
    private val t1 = t0.plus(Duration.ofHours(5))

    /** Synced: Loom's hash of the unit and the server's hash of the head agree. */
    private val synced = SaveBaseline(localHash = "h0", serverSaveId = 10, serverHash = "h0", serverUpdatedAt = t0)
    private val headAtSync = ServerHead(10, "h0", t0, "azahar")
    private val newerHead = ServerHead(11, "h2", t1, "azahar")

    private data class Case(
        val name: String,
        val local: String?,
        val baseline: SaveBaseline?,
        val server: ServerHead?,
        val expected: SyncDecision,
        val negotiated: NegotiatedAction? = null,
    )

    private val cases = listOf(
        // Nothing to do.
        Case("nothing changed", "h0", synced, headAtSync, IN_SYNC),
        Case("nothing changed, negotiate agrees", "h0", synced, headAtSync, IN_SYNC, NO_OP),
        Case("archive's extra roots make the two hashes differ for good",
            "hL", synced.copy(localHash = "hL", serverHash = "hS"), headAtSync.copy(contentHash = "hS"), IN_SYNC),
        Case("another client rewrote the head in place with the same bytes", "h0", synced, headAtSync.copy(updatedAt = t1), IN_SYNC),
        Case("no save anywhere", null, null, null, NOTHING_TO_SYNC),
        Case("save gone on both sides", null, synced, null, NOTHING_TO_SYNC),
        Case("Thor's save was deleted; the server's stays put", null, synced, headAtSync, LOCAL_MISSING),

        // Uploads: the server is unchanged since the baseline (or empty and never synced).
        Case("first save made on the Thor", "h1", null, null, NEW_ON_DEVICE),
        Case("played on the Thor", "h1", synced, headAtSync, LOCAL_CHANGED),
        Case("played on the Thor, negotiate agrees", "h1", synced, headAtSync, LOCAL_CHANGED, UPLOAD),

        // Downloads: the Thor's save is unchanged since the baseline, or absent.
        Case("new device, the server has an Azahar save", null, null, headAtSync, NEW_ON_SERVER),
        Case("played on another device", "h0", synced, newerHead, SERVER_CHANGED),
        Case("played on another device, negotiate agrees", "h0", synced, newerHead, SERVER_CHANGED, DOWNLOAD),
        Case("another Azahar client rewrote the head in place", "h0", synced, headAtSync.copy(contentHash = "h2", updatedAt = t1), SERVER_CHANGED),
        Case("Thor's save deleted, then played elsewhere", null, synced, newerHead, SERVER_CHANGED),
        Case("server couldn't hash its new head", "h0", synced, newerHead.copy(contentHash = null), SERVER_CHANGED),

        // Adopt: the same bytes on both sides, without a baseline saying so.
        Case("(d) reinstall with the same save", "h0", null, headAtSync, SAME_CONTENT),
        Case("both moved on to the same bytes", "h2", synced, newerHead, SAME_CONTENT),
        Case("same bytes uploaded by another emulator", "h2", null, newerHead.copy(emulator = "mgba"), SAME_CONTENT),
        Case("trap: identical content and no sync row; negotiate's no_op writes none", "h0", null, headAtSync, SAME_CONTENT, NO_OP),

        // Conflicts.
        Case("(a) negotiate says conflict", "h1", synced, headAtSync, SERVER_SAYS_CONFLICT, CONFLICT),
        Case("(b) both sides changed", "h1", synced, newerHead, BOTH_CHANGED),
        Case("(b) trap: another device's head looks newer, negotiate says download over Thor progress",
            "h1", synced, newerHead, BOTH_CHANGED, DOWNLOAD),
        Case("(b) trap: Thor's mtime is newer, negotiate says upload (RomM would answer 409)",
            "h1", synced, newerHead, BOTH_CHANGED, UPLOAD),
        Case("(b) negotiate wants nothing while the Thor changed (untracked, clock skew)", "h1", synced, headAtSync, DISAGREES_WITH_SERVER, NO_OP),
        Case("(b) negotiate wants a download while nothing changed", "h0", synced, headAtSync, DISAGREES_WITH_SERVER, DOWNLOAD),
        Case("(b) negotiate wants an upload of a save only the server changed", "h0", synced, newerHead, DISAGREES_WITH_SERVER, UPLOAD),
        Case("(b) negotiate offers the head to a Thor that deleted its save", null, synced, headAtSync, DISAGREES_WITH_SERVER, DOWNLOAD),
        Case("(b) an answer from a newer RomM is never acted on alone", "h0", synced, headAtSync, DISAGREES_WITH_SERVER, OTHER),
        Case("(b) a delete while RomM still has a head", "h0", synced, headAtSync, DISAGREES_WITH_SERVER, DELETE),
        Case("(d) first sync, different saves", "h1", null, headAtSync, FIRST_SYNC_DIFFERS),
        Case("(d) first sync, server couldn't hash its save", "h1", null, headAtSync.copy(contentHash = null), FIRST_SYNC_DIFFERS),
        Case("(e) the server's newest version was deleted",
            "h2", synced.copy(localHash = "h2", serverSaveId = 11, serverHash = "h2", serverUpdatedAt = t1), headAtSync, SERVER_WENT_BACK),
        Case("(e) trap: newest version deleted, negotiate's timestamps say upload",
            "h2", synced.copy(localHash = "h2", serverSaveId = 11, serverHash = "h2", serverUpdatedAt = t1), headAtSync, SERVER_WENT_BACK, UPLOAD),
        Case("(e) a different head from the very same second", "h0", synced, ServerHead(12, "h3", t0, "azahar"), SERVER_WENT_BACK),
        Case("(e) slot emptied on the server", "h0", synced, null, SERVER_SLOT_GONE),
        Case("(e) trap: slot emptied, negotiate says upload (would re-create it)", "h0", synced, null, SERVER_SLOT_GONE, UPLOAD),
        Case("(e) slot emptied while the Thor played on", "h1", synced, null, SERVER_SLOT_GONE),
        Case("(e) RomM 5.4.0 says the Thor's save was deleted there", "h0", synced, null, SERVER_DELETED, DELETE),
        Case("(e) RomM 5.4.0 says a save Loom never synced from here was deleted there", "h1", null, null, SERVER_DELETED, DELETE),
        Case("(e) RomM 5.4.0 says the Thor holds a version it deleted",
            "h2", synced.copy(localHash = "h2", serverSaveId = 11, serverHash = "h2", serverUpdatedAt = t1), headAtSync, SERVER_ROLLED_BACK, DOWNLOAD),
        Case("(f) newer save from another emulator", "h0", synced, newerHead.copy(emulator = "mgba"), FOREIGN_EMULATOR),
        Case("(f) trap: negotiate says download a web player save", "h0", synced, newerHead.copy(emulator = "mgba"), FOREIGN_EMULATOR, DOWNLOAD),
        Case("(f) newer save uploaded without an emulator", "h0", synced, newerHead.copy(emulator = null), FOREIGN_EMULATOR),
        Case("(f) new device, the server's save is another emulator's", null, null, headAtSync.copy(emulator = "mgba"), FOREIGN_EMULATOR),
    )

    /** Every local save, baseline and head [noCombinationLosesASave] tries. */
    private val locals = listOf(null, "h0", "h1", "h2", "hL")
    private val baselines = listOf(null, synced, synced.copy(localHash = "hL", serverHash = "hS"))
    private val heads = listOf(
        null, headAtSync, newerHead, headAtSync.copy(updatedAt = t1), headAtSync.copy(contentHash = "h2", updatedAt = t1),
        newerHead.copy(emulator = "mgba"), newerHead.copy(emulator = null), newerHead.copy(contentHash = null),
        ServerHead(9, "h3", t0.minusSeconds(60), "azahar"), ServerHead(12, "h3", t0, "azahar"),
    )

    @Test fun everyCaseDecidesAsTheDesignSays() {
        for (case in cases) {
            val decision = SyncDecider.decide(case.local, "azahar", case.baseline, case.server, case.negotiated)
            assertWithMessage(case.name).that(decision).isEqualTo(case.expected)
        }
    }

    /** Every reason the decider can give is covered above (the 409 one is recorded by the upload). */
    @Test fun theTableCoversEveryDecision() {
        val covered = cases.map { it.expected }.toSet()
        assertThat(SyncDecision.entries.toSet() - covered).containsExactly(SyncDecision.UPLOAD_REJECTED)
    }

    /**
     * The safety rules, over every combination: a download only when the Thor's save is unchanged
     * or absent and the head is a newer one from the same emulator; an upload only when the
     * server is unchanged; and never against what negotiate said.
     */
    @Test fun noCombinationLosesASave() {
        val answers = listOf(null) + NegotiatedAction.entries
        for (local in locals) for (base in baselines) for (head in heads) for (answer in answers) {
            val decision = SyncDecider.decide(local, "azahar", base, head, answer)
            val what = "local=$local base=$base head=$head negotiated=$answer -> $decision"
            assertWithMessage(what).that(decision).isNotEqualTo(SyncDecision.UPLOAD_REJECTED)
            when (decision.outcome) {
                SyncOutcome.DOWNLOAD -> {
                    assertWithMessage(what).that(local == null || local == base?.localHash).isTrue()
                    assertWithMessage(what).that(head!!.emulator).isEqualTo("azahar")
                    if (base != null) assertWithMessage(what).that(head.updatedAt.isAfter(base.serverUpdatedAt)).isTrue()
                }
                SyncOutcome.UPLOAD -> {
                    assertWithMessage(what).that(local).isNotNull()
                    if (head != null) {
                        assertWithMessage(what).that(base).isNotNull()
                        assertWithMessage(what).that(head.saveId to head.contentHash).isEqualTo(base!!.serverSaveId to base.serverHash)
                    }
                }
                SyncOutcome.ADOPT -> assertWithMessage(what).that(local).isEqualTo(head?.contentHash)
                SyncOutcome.NO_OP, SyncOutcome.CONFLICT -> Unit
            }
            if (answer != null && decision.outcome in setOf(SyncOutcome.UPLOAD, SyncOutcome.DOWNLOAD, SyncOutcome.NO_OP)) {
                assertWithMessage(what).that(answer.name).isEqualTo(decision.outcome.name)
            }
        }
    }

    /**
     * RomM 5.4.0's `delete` is a question, never an action: Loom has no way to delete the Thor's
     * save, and doesn't quietly upload it back either. With the slot empty, the question says why.
     */
    @Test fun deleteIsNeverFollowedAlone() {
        for (local in locals) for (base in baselines) for (head in heads) {
            val decision = SyncDecider.decide(local, "azahar", base, head, DELETE)
            val what = "local=$local base=$base head=$head -> $decision"
            if (local != null && head == null) assertWithMessage(what).that(decision).isEqualTo(SERVER_DELETED)
            assertWithMessage(what).that(decision.outcome).isAnyOf(SyncOutcome.CONFLICT, SyncOutcome.ADOPT)
        }
    }

    @Test fun reportedTimeKeepsAnUnchangedSaveAtItsSyncTime() {
        assertThat(SyncDecider.reportedUpdatedAt("h0", newestModified = t1, baseline = synced)).isEqualTo(t0)
    }

    /** The Thor's clock may be behind the server's: a changed save must still read as newer. */
    @Test fun reportedTimeMovesAChangedSavePastItsSyncTime() {
        assertThat(SyncDecider.reportedUpdatedAt("h1", newestModified = t0.minusSeconds(30), baseline = synced))
            .isEqualTo(t0.plusSeconds(1))
        assertThat(SyncDecider.reportedUpdatedAt("h1", newestModified = t1, baseline = synced)).isEqualTo(t1)
    }

    @Test fun reportedTimeWithoutABaselineIsTheSavesOwn() {
        assertThat(SyncDecider.reportedUpdatedAt("h1", newestModified = t1, baseline = null)).isEqualTo(t1)
    }
}
