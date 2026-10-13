package ro.davidmontei.loom.storage.engine

import java.io.IOException

/** Failures the runner turns into job events. */
sealed class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Worth retrying in a moment: connection dropped, 5xx, timeout. */
    class Transient(message: String, cause: Throwable? = null) : EngineException(message, cause)

    /** Retrying won't help until the user does something. */
    class Fatal(val failure: Failure, cause: Throwable? = null) : EngineException(failure.detail ?: failure.code.name, cause)

    /** The volume went away (SD card removed); park the job until it's back. */
    class StorageGone(cause: Throwable? = null) : EngineException("Storage unavailable", cause)

    /** The bytes on disk don't match what RomM recorded for this part. */
    class Corrupt(val romFileId: Int, message: String) : EngineException(message)
}

/** Maps low-level I/O errors on local files to engine failures (ENOSPC, EFBIG, unmounted card). */
internal fun IOException.classifyLocal(stillMounted: Boolean): EngineException {
    val m = message.orEmpty()
    return when {
        !stillMounted -> EngineException.StorageGone(this)
        m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) ->
            EngineException.Fatal(Failure(FailureCode.INSUFFICIENT_SPACE, "The SD card filled up during the download"), this)
        m.contains("EFBIG") || m.contains("File too large", ignoreCase = true) ->
            EngineException.Fatal(
                Failure(FailureCode.FILE_TOO_LARGE_FOR_SD, "This card can't hold files over 4 GB (FAT32). Reformat it as exFAT.", retryable = false),
                this,
            )
        m.contains("EROFS") || m.contains("Read-only file system", ignoreCase = true) -> EngineException.StorageGone(this)
        else -> EngineException.Fatal(Failure(FailureCode.INTERNAL, m.ifEmpty { "File error" }), this)
    }
}

/** Thrown by [CrashPoints] in tests to simulate the process dying at an exact moment. */
class SimulatedCrash(val point: CrashPoint) : Error("Simulated crash at $point")

enum class CrashPoint { AFTER_PART_BYTES, AFTER_ASSEMBLY_MANIFEST, MID_ASSEMBLY, BEFORE_PROMOTE_RENAME, AFTER_PROMOTE_RENAME, MID_EXTRACT }

/** Test hook: lets a test kill the runner at a named point. No-op in production. */
fun interface CrashPoints {
    fun hit(point: CrashPoint)

    companion object {
        val None = CrashPoints { }
    }
}
