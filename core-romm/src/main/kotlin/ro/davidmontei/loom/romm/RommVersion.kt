package ro.davidmontei.loom.romm

/** The RomM release whose OpenAPI schema the DTOs in this module were written against. */
const val ROMM_CONTRACT_VERSION = "5.4.0"

/** Device-authorization pairing arrived in RomM 5.0.0; Loom doesn't support anything older. */
val MIN_SUPPORTED_ROMM = RommVersion(5, 0, 0)

/** The first RomM with device installs ("send to device") and server-side save baselines. */
val ROMM_5_4 = RommVersion(5, 4, 0)

/**
 * A RomM server version. Pre-release and build suffixes ("5.4.0-beta.1") are dropped, so a
 * pre-release compares equal to its final release. RomM dev builds report "development", which is
 * treated as newer than anything.
 */
data class RommVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<RommVersion> {
    override fun compareTo(other: RommVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val DEVELOPMENT = RommVersion(9999, 0, 0)

        fun parse(raw: String): RommVersion? {
            val text = raw.trim().removePrefix("v")
            if (text.equals("development", ignoreCase = true)) return DEVELOPMENT
            val core = text.substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.isEmpty() || parts.size > 3) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return RommVersion(numbers[0], numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
        }
    }
}
