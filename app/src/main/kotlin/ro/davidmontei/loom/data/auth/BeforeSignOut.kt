package ro.davidmontei.loom.data.auth

/**
 * Hook for what signing out tells RomM first, while Loom still holds the token to tell it (RomM no
 * longer offering this device for games sent from its web UI). Never stops the sign-out.
 */
fun interface BeforeSignOut {
    suspend fun beforeSignOut()
}
