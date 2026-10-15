package ro.davidmontei.loom.storage.saves

/**
 * Emulator ids as RomM's saves carry them, compared as the emulators they name. PPSSPP Gold, the
 * paid build Argosy uploads as `ppsspp_gold`, keeps the same saves on the same memory stick as
 * PPSSPP (`ppsspp`), so a save from either is one emulator's. Every other id is its own.
 */
object EmulatorIds {
    /** PPSSPP Gold's id, as Argosy uploads its saves. */
    const val PPSSPP_GOLD = "ppsspp_gold"

    private val SAME_AS = mapOf(PPSSPP_GOLD to PpssppSaveLocator.EMULATOR_ID)

    /** Whether a save RomM holds from [server] (null: the web UI, an old upload) is [local]'s own. */
    fun same(server: String?, local: String): Boolean = server != null && canonical(server) == canonical(local)

    private fun canonical(id: String) = SAME_AS[id] ?: id
}
