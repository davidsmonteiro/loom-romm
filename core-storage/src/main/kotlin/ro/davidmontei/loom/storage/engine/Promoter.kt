package ro.davidmontei.loom.storage.engine

import java.nio.file.Path

enum class PromoteOutcome {
    /** The install just became visible in its system folder. */
    PROMOTED,

    /** A previous attempt already moved it there (crash after the rename). */
    ALREADY_PROMOTED,

    /** Something else already lives at the destination. Nothing was touched. */
    DEST_EXISTS,

    /** Neither the staged entry nor the destination exists: staging was lost. */
    MISSING,
}

/**
 * Makes a finished install visible with one rename from the staging area into the system folder
 * (ADR 0001). Idempotent, so a promote interrupted at any point can simply be run again.
 *
 * If the rename crosses mounts (EXDEV), the entry is copied to a dot-prefixed `.<name>.loomtmp`
 * beside the destination (Cocoon ignores dot-names), renamed within that folder, and only then
 * removed from staging.
 */
class Promoter(private val files: FileOps, private val crash: CrashPoints = CrashPoints.None) {

    fun promote(staged: Path, target: Path): PromoteOutcome {
        val tmp = target.resolveSibling(".${target.fileName}.loomtmp")
        val stagedExists = files.exists(staged)
        val targetExists = files.exists(target)
        return when {
            stagedExists && !targetExists -> {
                files.createDirectories(target.parent)
                crash.hit(CrashPoint.BEFORE_PROMOTE_RENAME)
                try {
                    files.atomicMove(staged, target)
                } catch (e: CrossDeviceException) {
                    files.deleteRecursively(tmp)
                    files.copyRecursively(staged, tmp)
                    files.atomicMove(tmp, target)
                    files.deleteRecursively(staged)
                }
                crash.hit(CrashPoint.AFTER_PROMOTE_RENAME)
                PromoteOutcome.PROMOTED
            }
            !stagedExists && targetExists -> {
                files.deleteRecursively(tmp)
                PromoteOutcome.ALREADY_PROMOTED
            }
            stagedExists && targetExists -> {
                // EXDEV fallback crashed after its rename but before cleaning staging: same entry twice.
                if (files.sizeOf(staged) == files.sizeOf(target) && !files.exists(tmp)) {
                    files.deleteRecursively(staged)
                    PromoteOutcome.ALREADY_PROMOTED
                } else {
                    PromoteOutcome.DEST_EXISTS
                }
            }
            files.exists(tmp) -> {
                files.atomicMove(tmp, target)
                PromoteOutcome.PROMOTED
            }
            else -> PromoteOutcome.MISSING
        }
    }
}
